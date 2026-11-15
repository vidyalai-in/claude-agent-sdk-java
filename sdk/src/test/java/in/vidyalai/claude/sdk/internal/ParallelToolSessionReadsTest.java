package in.vidyalai.claude.sdk.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.ObjectMapper;

import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.types.message.SessionMessage;
import in.vidyalai.claude.sdk.types.session.InMemorySessionStore;
import in.vidyalai.claude.sdk.types.session.SessionKey;
import in.vidyalai.claude.sdk.types.session.SessionStoreEntry;

/**
 * A turn that runs tools in parallel is returned whole by
 * {@code getSessionMessages} (Python SDK #1422).
 *
 * <p>
 * The CLI writes one assistant entry per content block, all sharing the
 * response's {@code message.id}, and one tool_result entry per call whose
 * {@code parentUuid} is that call's own entry. The transcript forks, and the
 * {@code parentUuid} walk alone would follow only the branch the next turn
 * continued from.
 */
class ParallelToolSessionReadsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path home;

    private String originalHome;

    @BeforeEach
    void redirectHome() {
        originalHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
    }

    @AfterEach
    void restoreHome() {
        System.setProperty("user.home", originalHome);
    }

    // --- helpers -------------------------------------------------------------

    private static Map<String, Object> entry(String type, String uuid, String parentUuid, Object content) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("type", type);
        e.put("uuid", uuid);
        e.put("parentUuid", parentUuid);
        e.put("sessionId", "s");
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", type);
        message.put("content", content);
        e.put("message", message);
        return e;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> message(Map<String, Object> entry) {
        return (Map<String, Object>) entry.get("message");
    }

    private record Turn(List<Map<String, Object>> calls, List<Map<String, Object>> results) {
    }

    /**
     * One assistant turn making {@code tools} parallel calls, as the CLI writes
     * it. {@code resultOrder} is the order the results are written in.
     */
    private static Turn parallelToolTurn(String parentUuid, int tools, int... resultOrder) {
        List<Map<String, Object>> calls = new ArrayList<>();
        for (int i = 0; i < tools; i++) {
            Map<String, Object> call = entry("assistant", "call-" + i,
                    calls.isEmpty() ? parentUuid : (String) calls.get(calls.size() - 1).get("uuid"),
                    List.of(Map.of("type", "tool_use", "id", "toolu_" + i, "name", "Read", "input", Map.of())));
            call.put("timestamp", String.format("2026-01-01T00:00:01.%03dZ", i));
            message(call).put("id", "msg_parallel");
            calls.add(call);
        }
        List<Map<String, Object>> results = new ArrayList<>();
        for (int position = 0; position < resultOrder.length; position++) {
            int i = resultOrder[position];
            Map<String, Object> result = entry("user", "result-" + i, "call-" + i,
                    List.of(Map.of("type", "tool_result", "tool_use_id", "toolu_" + i, "content", "ok")));
            result.put("sourceToolAssistantUUID", "call-" + i);
            result.put("timestamp", String.format("2026-01-01T00:00:02.%03dZ", position));
            results.add(result);
        }
        return new Turn(calls, results);
    }

    private static Map<String, Object> bare(String type, String uuid, String parentUuid) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("type", type);
        e.put("uuid", uuid);
        e.put("parentUuid", parentUuid);
        return e;
    }

    @SafeVarargs
    private static List<Map<String, Object>> concat(List<Map<String, Object>>... parts) {
        List<Map<String, Object>> all = new ArrayList<>();
        for (List<Map<String, Object>> part : parts) {
            all.addAll(part);
        }
        return all;
    }

    private static List<String> messageUuids(List<SessionMessage> messages) {
        List<String> ids = new ArrayList<>();
        for (SessionMessage m : messages) {
            ids.add(m.uuid());
        }
        return ids;
    }

    private static List<String> messageTypes(List<SessionMessage> messages) {
        List<String> types = new ArrayList<>();
        for (SessionMessage m : messages) {
            types.add(m.type());
        }
        return types;
    }

    private static List<String> uuids(List<Map<String, Object>> chain) {
        return chain.stream().map(e -> (String) e.get("uuid")).toList();
    }

    private String writeTranscript(List<Map<String, Object>> entries) throws IOException {
        String sid = UUID.randomUUID().toString();
        Path projectDir = home.resolve(".claude").resolve("projects").resolve("proj");
        Files.createDirectories(projectDir);
        StringBuilder jsonl = new StringBuilder();
        for (Map<String, Object> e : entries) {
            Map<String, Object> copy = new LinkedHashMap<>(e);
            copy.put("sessionId", sid);
            jsonl.append(MAPPER.writeValueAsString(copy)).append('\n');
        }
        Files.writeString(projectDir.resolve(sid + ".jsonl"), jsonl.toString());
        return sid;
    }

    @SuppressWarnings("unchecked")
    private static List<String> blockIds(List<SessionMessage> messages, String blockType, String field) {
        List<String> ids = new ArrayList<>();
        for (SessionMessage m : messages) {
            if (((Map<String, Object>) m.message()).get("content") instanceof List<?> content) {
                for (Object block : content) {
                    if (block instanceof Map<?, ?> b && blockType.equals(b.get("type"))) {
                        ids.add((String) b.get(field));
                    }
                }
            }
        }
        return ids;
    }

    // --- getSessionMessages --------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = { "0,1,2", "2,1,0", "1,2,0", "0,2,1" })
    void parallelToolCallsKeepEveryCallAndResult(String order) throws IOException {
        int[] resultOrder = Arrays.stream(order.split(",")).mapToInt(Integer::parseInt).toArray();
        Turn turn = parallelToolTurn("prompt", 3, resultOrder);
        String sid = writeTranscript(concat(
                List.of(entry("user", "prompt", null, "read them")),
                turn.calls(), turn.results(),
                // The next response continues from the last result written.
                List.of(entry("assistant", "answer",
                        (String) turn.results().get(2).get("uuid"), "done"))));

        List<SessionMessage> messages = Sessions.getSessionMessages(sid, null, null, 0);

        assertThat(blockIds(messages, "tool_use", "id")).containsExactly("toolu_0", "toolu_1", "toolu_2");
        assertThat(blockIds(messages, "tool_result", "tool_use_id"))
                .containsExactlyInAnyOrder("toolu_0", "toolu_1", "toolu_2");
        assertThat(messages).hasSize(8);
        assertThat(new HashSet<>(messageUuids(messages))).hasSize(8);
        // The turn stays in one piece: prompt, calls, results, answer.
        assertThat(messages.get(0).uuid()).isEqualTo("prompt");
        assertThat(messageTypes(messages.subList(1, 7)))
                .containsExactly("assistant", "assistant", "assistant", "user", "user", "user");
        assertThat(messages.get(7).uuid()).isEqualTo("answer");
    }

    @Test
    void parallelToolCallsAsLastTurn() throws IOException {
        Turn turn = parallelToolTurn("prompt", 2, 1, 0);
        String sid = writeTranscript(concat(
                List.of(entry("user", "prompt", null, "read them")), turn.calls(), turn.results()));

        assertThat(messageUuids(Sessions.getSessionMessages(sid, null, null, 0)))
                .containsExactly("prompt", "call-0", "call-1", "result-1", "result-0");
    }

    @Test
    void pagingCountsTheRecoveredCallsAndResults() throws IOException {
        Turn turn = parallelToolTurn("prompt", 3, 0, 1, 2);
        String sid = writeTranscript(concat(
                List.of(entry("user", "prompt", null, "read them")), turn.calls(), turn.results(),
                List.of(entry("assistant", "answer", "result-2", "k"))));

        assertThat(messageUuids(Sessions.getSessionMessages(sid, null, 3, 4)))
                .containsExactly("result-0", "result-1", "result-2");
    }

    @Test
    void storeReadKeepsEveryCallAndResult() {
        InMemorySessionStore store = new InMemorySessionStore();
        String sid = UUID.randomUUID().toString();
        Turn turn = parallelToolTurn("prompt", 3, 0, 1, 2);
        // The next response continues from the first result, so the walk back
        // from it reaches only call-0.
        List<SessionStoreEntry> entries = concat(
                List.of(entry("user", "prompt", null, "read them")), turn.calls(), turn.results(),
                List.of(entry("assistant", "answer", "result-0", "done")))
                .stream().map(SessionStoreEntry::of).toList();
        store.append(new SessionKey(ClaudeSDK.projectKeyForDirectory(null), sid, null), entries);

        assertThat(messageUuids(ClaudeSDK.getSessionMessagesFromStore(store, sid, null, null, 0)))
                .containsExactly("prompt", "call-0", "call-1", "call-2",
                        "result-1", "result-2", "result-0", "answer");
    }

    // --- buildConversationChain ----------------------------------------------

    @Test
    void recoversSiblingCallsDroppedByTheWalk() {
        Turn turn = parallelToolTurn("prompt", 3, 1, 2, 0);
        List<Map<String, Object>> chain = Sessions.buildConversationChain(concat(
                List.of(bare("user", "prompt", null)), turn.calls(), turn.results(),
                List.of(bare("assistant", "answer", "result-0"))));
        // The off-branch calls and their results go right after the last call
        // on the branch, ahead of that branch's own result.
        assertThat(uuids(chain)).containsExactly("prompt", "call-0", "call-1", "call-2",
                "result-1", "result-2", "result-0", "answer");
    }

    @Test
    void recoveredResultsAreInCompletionOrder() {
        // The second call finished first.
        Turn turn = parallelToolTurn("prompt", 3, 1, 0, 2);
        List<Map<String, Object>> chain = Sessions.buildConversationChain(concat(
                List.of(bare("user", "prompt", null)), turn.calls(), turn.results(),
                List.of(bare("assistant", "answer", "result-2"))));
        assertThat(uuids(chain)).containsExactly("prompt", "call-0", "call-1", "call-2",
                "result-1", "result-0", "result-2", "answer");
    }

    @Test
    void recoveredCallsAreInTimestampOrder() {
        Turn turn = parallelToolTurn("prompt", 3, 1, 2, 0);
        // call-2 reached the file before call-1.
        List<Map<String, Object>> chain = Sessions.buildConversationChain(concat(
                List.of(bare("user", "prompt", null),
                        turn.calls().get(0), turn.calls().get(2), turn.calls().get(1)),
                turn.results(),
                List.of(bare("assistant", "answer", "result-0"))));
        assertThat(uuids(chain).subList(1, 4)).containsExactly("call-0", "call-1", "call-2");
    }

    @Test
    void sequentialToolCallsAreUnchanged() {
        List<Map<String, Object>> entries = List.of(
                bare("user", "prompt", null),
                withMessage(bare("assistant", "call-0", "prompt"), "msg_1",
                        List.of(Map.of("type", "tool_use", "id", "toolu_0"))),
                withMessage(bare("user", "result-0", "call-0"), null,
                        List.of(Map.of("type", "tool_result", "tool_use_id", "toolu_0"))),
                withMessage(bare("assistant", "call-1", "result-0"), "msg_1",
                        List.of(Map.of("type", "tool_use", "id", "toolu_1"))),
                withMessage(bare("user", "result-1", "call-1"), null,
                        List.of(Map.of("type", "tool_result", "tool_use_id", "toolu_1"))));
        assertThat(Sessions.buildConversationChain(entries)).isEqualTo(entries);
    }

    private static Map<String, Object> withMessage(Map<String, Object> e, String id, List<Object> content) {
        Map<String, Object> message = new LinkedHashMap<>();
        if (id != null) {
            message.put("id", id);
        }
        message.put("content", content);
        e.put("message", message);
        return e;
    }

    @Test
    void abandonedBranchIsNotRecovered() {
        // Only entries that share a response id with the chain are recovered;
        // an earlier attempt at the same turn is a different response.
        Turn turn = parallelToolTurn("prompt", 2, 0, 1);
        List<Map<String, Object>> chain = Sessions.buildConversationChain(concat(
                List.of(bare("user", "prompt", null),
                        withMessage(bare("assistant", "old-call", "prompt"), "msg_abandoned",
                                List.of(Map.of("type", "tool_use", "id", "toolu_old"))),
                        withMessage(bare("user", "old-result", "old-call"), null,
                                List.of(Map.of("type", "tool_result", "tool_use_id", "toolu_old")))),
                turn.calls(), turn.results()));
        assertThat(uuids(chain)).containsExactly("prompt", "call-0", "call-1", "result-0", "result-1");
    }

    @Test
    void recoversResultWhoseParentIsOutsideTheTurn() {
        // Matched to its call by tool_use_id.
        Turn turn = parallelToolTurn("prompt", 2, 0, 1);
        turn.results().get(0).put("parentUuid", "missing");
        turn.results().get(0).remove("sourceToolAssistantUUID");
        List<Map<String, Object>> chain = Sessions.buildConversationChain(concat(
                List.of(bare("user", "prompt", null)), turn.calls(), turn.results(),
                List.of(bare("assistant", "answer", "result-1"))));
        assertThat(uuids(chain)).containsExactly("prompt", "call-0", "call-1", "result-0", "result-1", "answer");
    }

    @Test
    void doesNotDuplicateAnAnsweredCall() {
        Turn turn = parallelToolTurn("prompt", 2, 0, 1);
        Map<String, Object> duplicate = new LinkedHashMap<>(turn.results().get(1));
        duplicate.put("uuid", "result-1-copy");
        duplicate.put("parentUuid", "elsewhere");
        duplicate.remove("sourceToolAssistantUUID");
        List<Map<String, Object>> chain = Sessions.buildConversationChain(concat(
                List.of(bare("user", "prompt", null)), turn.calls(), turn.results(),
                List.of(duplicate, bare("assistant", "answer", "result-1"))));
        assertThat(uuids(chain)).containsExactly("prompt", "call-0", "call-1", "result-0", "result-1", "answer");
    }

    @Test
    void doesNotPullInAnotherAgentsResult() {
        // A sidechain result that reuses a main-chain tool_use_id.
        Turn turn = parallelToolTurn("prompt", 2, 0, 1);
        Map<String, Object> stray = turn.results().get(0);
        stray.put("parentUuid", "sidechain-call");
        stray.put("isSidechain", true);
        stray.put("agentId", "agent-1");
        stray.remove("sourceToolAssistantUUID");
        List<Map<String, Object>> chain = Sessions.buildConversationChain(concat(
                List.of(bare("user", "prompt", null)), turn.calls(), turn.results(),
                List.of(bare("assistant", "answer", "result-1"))));
        assertThat(uuids(chain)).containsExactly("prompt", "call-0", "call-1", "result-1", "answer");
    }

    @Test
    void recoveryToleratesMalformedEntries() {
        Turn turn = parallelToolTurn("prompt", 2, 0, 1);
        turn.results().get(0).put("timestamp", 12345);
        List<Object> oddContent = new ArrayList<>();
        oddContent.add("text");
        oddContent.add(null);
        oddContent.add(Map.of("type", "tool_use"));
        Map<String, Object> odd = bare("assistant", "odd", "no-message");
        Map<String, Object> oddMessage = new LinkedHashMap<>();
        oddMessage.put("id", 7);
        oddMessage.put("content", oddContent);
        odd.put("message", oddMessage);
        List<Map<String, Object>> chain = Sessions.buildConversationChain(concat(
                List.of(bare("user", "prompt", null)), turn.calls(), turn.results(),
                List.of(bare("assistant", "no-message", "result-1"), odd,
                        withMessage(bare("user", "odd-result", "odd"), null,
                                List.of(Map.of("type", "tool_result", "tool_use_id", 3))))));
        assertThat(uuids(chain)).containsExactly("prompt", "call-0", "call-1", "result-0", "result-1",
                "no-message", "odd", "odd-result");
    }
    // --- store paging and tie-breaks -------------------------------------------

    private InMemorySessionStore storeWith(String sid, List<Map<String, Object>> entries) {
        InMemorySessionStore store = new InMemorySessionStore();
        store.append(new SessionKey(ClaudeSDK.projectKeyForDirectory(null), sid, null),
                entries.stream().map(SessionStoreEntry::of).toList());
        return store;
    }

    @Test
    void storePagingAppliesOffsetBeforeLimit() {
        // Python: messages[offset:offset + limit]. The store path used to cut
        // to `limit` first and then skip `offset` of what was left.
        String sid = UUID.randomUUID().toString();
        Turn turn = parallelToolTurn("prompt", 3, 0, 1, 2);
        InMemorySessionStore store = storeWith(sid, concat(
                List.of(entry("user", "prompt", null, "read them")), turn.calls(), turn.results(),
                List.of(entry("assistant", "answer", "result-2", "k"))));

        assertThat(messageUuids(ClaudeSDK.getSessionMessagesFromStore(store, sid, null, 3, 4)))
                .containsExactly("result-0", "result-1", "result-2");
        assertThat(messageUuids(ClaudeSDK.getSessionMessagesFromStore(store, sid, null, 2, 1)))
                .containsExactly("call-0", "call-1");
        assertThat(messageUuids(ClaudeSDK.getSessionMessagesFromStore(store, sid, null, 0, 6)))
                .containsExactly("result-2", "answer");
        assertThat(ClaudeSDK.getSessionMessagesFromStore(store, sid, null, null, 99)).isEmpty();
    }

    @Test
    void diskPagingTreatsZeroLimitAsNoLimit() throws IOException {
        Turn turn = parallelToolTurn("prompt", 2, 0, 1);
        String sid = writeTranscript(concat(
                List.of(entry("user", "prompt", null, "read them")), turn.calls(), turn.results()));

        assertThat(Sessions.getSessionMessages(sid, null, 0, 1)).hasSize(4);
    }

    @Test
    void storeReadWithTiedTimestampsKeepsCallAndFileOrder() {
        // As upstream's store test: no timestamps and no sourceToolAssistantUUID,
        // so the stable sort and the parentUuid index alone decide the order.
        String sid = UUID.randomUUID().toString();
        Turn turn = parallelToolTurn("prompt", 3, 0, 1, 2);
        for (Map<String, Object> e : concat(turn.calls(), turn.results())) {
            e.remove("timestamp");
            e.remove("sourceToolAssistantUUID");
        }
        InMemorySessionStore store = storeWith(sid, concat(
                List.of(entry("user", "prompt", null, "read them")), turn.calls(), turn.results(),
                List.of(entry("assistant", "answer", "result-0", "done"))));

        assertThat(messageUuids(ClaudeSDK.getSessionMessagesFromStore(store, sid, null, null, 0)))
                .containsExactly("prompt", "call-0", "call-1", "call-2",
                        "result-1", "result-2", "result-0", "answer");
    }

    // --- leaf selection, as Python's _build_conversation_chain -----------------

    @Test
    void metaTerminalDoesNotPullTheLeafBackToItsAncestor() {
        // The nearest user/assistant entry of each terminal is the leaf; a meta
        // leaf is not a main leaf, so the later main leaf (result-1) wins.
        Turn turn = parallelToolTurn("prompt", 2, 1, 0);
        Map<String, Object> meta = bare("user", "meta", "result-0");
        meta.put("isMeta", true);
        List<Map<String, Object>> chain = Sessions.buildConversationChain(concat(
                List.of(bare("user", "prompt", null)), turn.calls(), turn.results(), List.of(meta)));
        assertThat(uuids(chain)).containsExactly("prompt", "call-0", "call-1", "result-0", "result-1");
    }

    @Test
    void onlySidechainLeavesFallBackToTheMostRecentLeaf() {
        Map<String, Object> side = bare("user", "s1", "a1");
        side.put("isSidechain", true);
        List<Map<String, Object>> chain = Sessions.buildConversationChain(List.of(
                bare("user", "prompt", null), bare("assistant", "a1", "prompt"), side));
        assertThat(uuids(chain)).containsExactly("prompt", "a1", "s1");
    }

    @Test
    void flagsUsePythonTruthiness() {
        // An empty teamName is no team; isSidechain "true" counts as set.
        Map<String, Object> team = bare("assistant", "a2", "u2");
        team.put("teamName", "");
        Map<String, Object> side = bare("assistant", "s1", "prompt");
        side.put("isSidechain", "true");
        List<Map<String, Object>> chain = Sessions.buildConversationChain(List.of(
                bare("user", "prompt", null), bare("assistant", "a1", "prompt"),
                bare("user", "u2", "a1"), team, side));
        assertThat(uuids(chain)).containsExactly("prompt", "a1", "u2", "a2");
        assertThat(Sessions.isVisibleMessage(team)).isTrue();
        assertThat(Sessions.isVisibleMessage(side)).isFalse();
    }

    @Test
    void nonStringUuidsAndParentsAreTolerated() {
        Map<String, Object> numericUuid = bare("user", "x", null);
        numericUuid.put("uuid", 5);
        Map<String, Object> numericParent = bare("assistant", "a1", null);
        numericParent.put("parentUuid", 7);
        List<Map<String, Object>> chain = Sessions.buildConversationChain(List.of(
                bare("user", "prompt", null), numericUuid, numericParent));
        assertThat(uuids(chain)).containsExactly("a1");
    }
}
