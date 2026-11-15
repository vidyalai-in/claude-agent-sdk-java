package examples;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.ClaudeSDKClient;
import in.vidyalai.claude.sdk.mcp.SdkMcpServer;
import in.vidyalai.claude.sdk.mcp.SdkMcpTool;
import in.vidyalai.claude.sdk.mcp.ToolResult;
import in.vidyalai.claude.sdk.types.message.AssistantMessage;
import in.vidyalai.claude.sdk.types.message.ContentBlock;
import in.vidyalai.claude.sdk.types.message.Message;
import in.vidyalai.claude.sdk.types.message.ResultMessage;
import in.vidyalai.claude.sdk.types.message.SessionMessage;
import in.vidyalai.claude.sdk.types.message.ToolResultBlock;
import in.vidyalai.claude.sdk.types.message.ToolUseBlock;
import in.vidyalai.claude.sdk.types.message.UserMessage;
import in.vidyalai.claude.sdk.types.session.InMemorySessionStore;
import in.vidyalai.claude.sdk.types.session.SessionKey;
import in.vidyalai.claude.sdk.types.session.SessionStoreEntry;

/**
 * Reading back a turn that made several tool calls in one response.
 *
 * <p>
 * When the model makes parallel tool calls, the CLI writes every call before
 * the first result, and then writes each result under its own call's entry. The
 * transcript forks there, and only one branch leads on to the next turn.
 * {@link ClaudeSDK#getSessionMessages(String, Path)} (the local transcript) and
 * {@link ClaudeSDK#getSessionMessagesFromStore} (the mirrored entries) both put
 * the turn back together: every call, and every result after its call.
 * </p>
 *
 * <p>
 * This example runs such a turn against a slow SDK MCP tool, then checks both
 * readers against the tool calls the live stream showed. It is the Java
 * counterpart of the Python SDK's
 * {@code e2e-tests/test_parallel_tool_session_reads.py}, and like it depends on
 * the model actually making the calls in parallel: when the model makes them
 * one response at a time the transcript does not fork, and the example reports
 * that it skipped the check. It exits non-zero when a reader gets the turn
 * wrong.
 * </p>
 */
public class ParallelToolSessionReadsExample {

    private static final List<String> KEYS = List.of("alpha", "beta", "gamma");
    private static final String TOOL = "mcp__slow__lookup";
    private static final String PROMPT = "Look up " + String.join(", ", KEYS) + " with the " + TOOL
            + " tool, one call per key. Make all " + KEYS.size() + " tool calls in a single response, so they"
            + " run in parallel: do not wait for one result before making the next call. Then reply with the"
            + " single word DONE.";

    public static void main(String[] args) throws Exception {
        System.out.println("=== Parallel Tool Session Reads Example ===\n");
        // A fresh project directory, so the session is the only one in it.
        Path cwd = Files.createTempDirectory("parallel-tool-reads").toRealPath();
        int exitCode;
        try {
            exitCode = run(cwd);
        } finally {
            deleteRecursively(cwd);
        }
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    private static int run(Path cwd) {
        // Slow, so every call of the response is written before any result.
        SdkMcpTool<Map<String, Object>> lookup = SdkMcpTool.create(
                "lookup",
                "Look up the value stored for a key",
                Map.of("type", "object",
                        "properties", Map.of("key", Map.of("type", "string")),
                        "required", List.of("key")),
                args -> CompletableFuture.supplyAsync(
                        () -> ToolResult.text("value of " + args.get("key")),
                        CompletableFuture.delayedExecutor(1, TimeUnit.SECONDS)));

        InMemorySessionStore store = new InMemorySessionStore();
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .cwd(cwd)
                .sessionStore(store)
                .mcpServers(Map.of("slow", SdkMcpServer.create("slow", List.of(lookup)).toConfig()))
                .allowedTools(List.of(TOOL))
                .maxTurns(4)
                .build();

        String sessionId = null;
        Set<String> liveCalls = new TreeSet<>();
        Set<String> liveResults = new TreeSet<>();
        try (ClaudeSDKClient client = ClaudeSDK.createClient(options)) {
            client.connect();
            client.query(PROMPT);
            for (Message message : client.receiveResponse()) {
                if (message instanceof ResultMessage result) {
                    if (result.isError()) {
                        System.out.println("FAILED: the run ended in an error: " + result);
                        return 1;
                    }
                    sessionId = result.sessionId();
                } else if ((message instanceof AssistantMessage assistant)
                        && (assistant.parentToolUseId() == null)) {
                    for (ContentBlock block : assistant.content()) {
                        if (block instanceof ToolUseBlock toolUse) {
                            System.out.println("Tool call:   " + toolUse.id() + " " + toolUse.input());
                            liveCalls.add(toolUse.id());
                        }
                    }
                } else if ((message instanceof UserMessage user) && (user.parentToolUseId() == null)) {
                    List<ContentBlock> blocks = user.contentAsBlocks();
                    for (ContentBlock block : ((blocks != null) ? blocks : List.<ContentBlock>of())) {
                        if (block instanceof ToolResultBlock toolResult) {
                            System.out.println("Tool result: " + toolResult.toolUseId());
                            liveResults.add(toolResult.toolUseId());
                        }
                    }
                }
            }
        }

        if (sessionId == null) {
            System.out.println("FAILED: the run reported no session id");
            return 1;
        }
        if (liveCalls.isEmpty()) {
            System.out.println("FAILED: the model never called the lookup tool");
            return 1;
        }
        if (!liveCalls.equals(liveResults)) {
            System.out.println("FAILED: not every live tool call got a result: calls " + liveCalls
                    + ", results " + liveResults);
            return 1;
        }

        // The readers are only under test if the transcript forked: some entry
        // is the parent of two user/assistant entries.
        if (!transcriptForks(store.load(new SessionKey(ClaudeSDK.projectKeyForDirectory(cwd), sessionId, null)))) {
            System.out.println("\nSKIPPED: the model made the tool calls one at a time, so the transcript did"
                    + " not fork and there is nothing to reassemble. Run the example again.");
            return 0;
        }

        List<SessionMessage> local = ClaudeSDK.getSessionMessages(sessionId, cwd);
        List<SessionMessage> mirrored = ClaudeSDK.getSessionMessagesFromStore(store, sessionId, cwd, null, 0);

        List<String> problems = new ArrayList<>();
        problems.addAll(check("local", local, liveCalls));
        problems.addAll(check("store", mirrored, liveCalls));
        if (!uuids(local).equals(uuids(mirrored))) {
            problems.add("the local and store readers returned different messages");
        }

        System.out.println();
        if (!problems.isEmpty()) {
            problems.forEach(problem -> System.out.println("FAILED: " + problem));
            return 1;
        }
        System.out.printf("Both readers returned the forked turn whole: %d messages, all %d calls and"
                + " their results, each result after its call.%n", local.size(), liveCalls.size());
        return 0;
    }

    private static boolean transcriptForks(List<SessionStoreEntry> entries) {
        Set<String> parents = new HashSet<>();
        for (SessionStoreEntry entry : entries) {
            Object type = entry.get("type");
            Object parent = entry.get("parentUuid");
            if (("user".equals(type) || "assistant".equals(type)) && (entry.get("uuid") != null)
                    && (parent instanceof String p) && !p.isEmpty() && !parents.add(p)) {
                return true;
            }
        }
        return false;
    }

    /** What is wrong with one reader's view of the turn; empty when nothing is. */
    private static List<String> check(String reader, List<SessionMessage> messages, Set<String> liveCalls) {
        List<String> problems = new ArrayList<>();
        Map<String, Integer> callIndex = new HashMap<>();
        List<Map.Entry<Integer, String>> results = new ArrayList<>();
        List<String> callIds = new ArrayList<>();
        List<String> resultIds = new ArrayList<>();
        Map<Integer, Object> responseAt = new HashMap<>();
        for (int i = 0; i < messages.size(); i++) {
            Map<?, ?> body = body(messages.get(i));
            if ("assistant".equals(messages.get(i).type())) {
                responseAt.put(i, body.get("id"));
            }
            for (Map<?, ?> block : blocks(body)) {
                if ("tool_use".equals(block.get("type")) && (block.get("id") instanceof String id)) {
                    callIds.add(id);
                    callIndex.put(id, i);
                } else if ("tool_result".equals(block.get("type"))
                        && (block.get("tool_use_id") instanceof String id)) {
                    results.add(Map.entry(i, id));
                    resultIds.add(id);
                }
            }
        }

        // Every call and every result, each exactly once.
        List<String> expected = new ArrayList<>(liveCalls);
        if (!sorted(callIds).equals(expected)) {
            problems.add(reader + ": tool calls " + sorted(callIds) + ", expected " + expected);
        }
        if (!sorted(resultIds).equals(expected)) {
            problems.add(reader + ": tool results " + sorted(resultIds) + ", expected " + expected);
        }
        if (new HashSet<>(uuids(messages)).size() != messages.size()) {
            problems.add(reader + ": a message appears more than once");
        }

        // Every result follows its call, with nothing but that call's own
        // response in between.
        for (Map.Entry<Integer, String> result : results) {
            Integer start = callIndex.get(result.getValue());
            if (start == null) {
                continue; // already reported above
            }
            if (start >= result.getKey()) {
                problems.add(reader + ": the result of " + result.getValue() + " comes before its call");
                continue;
            }
            Set<Object> between = new HashSet<>();
            for (int i = start; i < result.getKey(); i++) {
                if (responseAt.containsKey(i)) {
                    between.add(responseAt.get(i));
                }
            }
            if (!between.equals(Set.of(responseAt.get(start)))) {
                problems.add(reader + ": another response sits between " + result.getValue()
                        + " and its result");
            }
        }

        // And the conversation still ends on the final reply.
        if (messages.isEmpty() || !"user".equals(messages.get(0).type())) {
            problems.add(reader + ": the conversation does not start with the prompt");
        } else {
            SessionMessage last = messages.get(messages.size() - 1);
            boolean lastCallsATool = blocks(body(last)).stream().anyMatch(b -> "tool_use".equals(b.get("type")));
            if (!"assistant".equals(last.type()) || lastCallsATool) {
                problems.add(reader + ": the conversation does not end on the final reply");
            }
        }
        return problems;
    }

    private static Map<?, ?> body(SessionMessage message) {
        return ((message.message() instanceof Map<?, ?> map) ? map : Map.of());
    }

    private static List<Map<?, ?>> blocks(Map<?, ?> body) {
        List<Map<?, ?>> blocks = new ArrayList<>();
        if (body.get("content") instanceof List<?> content) {
            for (Object block : content) {
                if (block instanceof Map<?, ?> map) {
                    blocks.add(map);
                }
            }
        }
        return blocks;
    }

    private static List<String> uuids(List<SessionMessage> messages) {
        return messages.stream().map(message -> message.uuid()).toList();
    }

    private static List<String> sorted(List<String> ids) {
        return ids.stream().sorted().toList();
    }

    private static void deleteRecursively(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
