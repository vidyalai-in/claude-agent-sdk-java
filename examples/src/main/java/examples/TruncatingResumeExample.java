package examples;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.ClaudeSDKClient;
import in.vidyalai.claude.sdk.exceptions.ClaudeSDKException;
import in.vidyalai.claude.sdk.types.message.AssistantMessage;
import in.vidyalai.claude.sdk.types.message.Message;
import in.vidyalai.claude.sdk.types.message.ResultMessage;
import in.vidyalai.claude.sdk.types.message.SessionMessage;

/**
 * Example demonstrating truncating resume — rewinding a session to an earlier
 * point in its transcript.
 *
 * <p>
 * {@code resumeSessionAt} loads a resumed conversation only up to and including
 * the given transcript-entry UUID, so the turns after it are dropped. Paired
 * with {@code forkSession} that branches into a new session and leaves the
 * original untouched.
 * </p>
 *
 * <p>
 * {@code resumeDropsTurn} makes that truncation <b>safe</b>. Pass the UUID of
 * the user prompt whose turn you intend to discard, and the CLI validates at
 * load time that every entry after the fork point belongs to that turn. If the
 * session absorbed something else mid-turn that you never observed — a queued
 * user message, a background-task notification — the resume is refused instead
 * of silently discarding it. The refusal arrives as an exception whose message
 * contains {@code "Resume rejected by --resume-drops-turn:"}. Treat it as
 * deterministic: clear the fork target and resume plainly rather than retrying.
 * </p>
 *
 * <p>
 * This example builds a two-turn session, forks it at the end of turn 1, and
 * then shows the guard rejecting a fork whose declared discarded turn doesn't
 * match the transcript.
 * </p>
 */
public class TruncatingResumeExample {

    private static final String TURN_1 = "Reply with exactly: one";
    private static final String TURN_2 = "Reply with exactly: two";
    private static final String TURN_3 = "Reply with exactly: three";

    public static void main(String[] args) throws Exception {
        System.out.println("=== Truncating Resume Example ===\n");

        // A scratch working directory keeps the sessions this example creates
        // out of your real project history, and makes the transcript lookups
        // below unambiguous.
        Path cwd = java.nio.file.Files.createTempDirectory("claude-truncating-resume-");
        System.out.printf("Working directory: %s%n%n", cwd);

        // 1. Build a two-turn session and remember where turn 1 ended.
        String sessionId;
        String keepAt;
        try (ClaudeSDKClient client = ClaudeSDK.createClient(
                ClaudeAgentOptions.builder().cwd(cwd).maxTurns(1).build())) {
            client.connect();

            client.query(TURN_1);
            String lastAssistantUuid = null;
            for (Message message : iterate(client)) {
                if (message instanceof AssistantMessage assistant && assistant.uuid() != null) {
                    lastAssistantUuid = assistant.uuid();
                }
            }
            keepAt = lastAssistantUuid;

            client.query(TURN_2);
            String resolvedSessionId = null;
            for (Message message : iterate(client)) {
                if (message instanceof ResultMessage result) {
                    resolvedSessionId = result.sessionId();
                }
            }
            sessionId = resolvedSessionId;
        }

        if ((sessionId == null) || (keepAt == null)) {
            System.out.println("Could not build the source session; aborting.");
            return;
        }
        System.out.printf("Source session %s%n", sessionId);
        System.out.printf("  prompts: %s%n", prompts(sessionId, cwd));
        System.out.printf("  forking at turn-1 entry %s%n%n", keepAt);

        // 2. The prompt UUID of the turn we intend to discard — the transcript
        // entry immediately after the fork point.
        String turn2PromptUuid = promptUuidAfter(sessionId, cwd, keepAt);
        if (turn2PromptUuid == null) {
            System.out.println("Could not locate turn 2's prompt; aborting.");
            return;
        }

        // 3. Fork with a matching resumeDropsTurn — the CLI validates and
        // accepts, so turn 2 is dropped and turn 3 lands right after turn 1.
        ClaudeAgentOptions forkOptions = ClaudeAgentOptions.builder()
                .cwd(cwd)
                .maxTurns(1)
                .resume(sessionId)
                .forkSession(true)
                .resumeSessionAt(keepAt)
                .resumeDropsTurn(turn2PromptUuid)
                .build();

        String forkedSessionId = null;
        for (Message message : ClaudeSDK.query(TURN_3, forkOptions)) {
            if (message instanceof ResultMessage result) {
                forkedSessionId = result.sessionId();
            }
        }
        if (forkedSessionId != null) {
            System.out.printf("Forked session %s%n", forkedSessionId);
            System.out.printf("  prompts: %s  (turn 2 discarded)%n", prompts(forkedSessionId, cwd));
            System.out.printf("  source unchanged: %s%n%n", prompts(sessionId, cwd));
        }

        // 4. The same fork with a resumeDropsTurn that doesn't match the
        // transcript is refused rather than silently discarding turn 2.
        ClaudeAgentOptions refusedOptions = forkOptions.toBuilder()
                .resumeDropsTurn(UUID.randomUUID().toString())
                .build();
        try {
            // Drain; the refusal surfaces as an exception.
            Iterator<Message> refused = ClaudeSDK.query(TURN_3, refusedOptions).iterator();
            while (refused.hasNext()) {
                refused.next();
            }
            System.out.println("Unexpected: the mismatched fork was accepted.");
        } catch (ClaudeSDKException e) {
            String message = String.valueOf(e.getMessage());
            if (message.contains("Resume rejected by --resume-drops-turn:")) {
                System.out.println("Mismatched fork was refused, as intended:");
                System.out.println("  " + message);
            } else {
                throw e;
            }
        }
    }

    /** Collects the current response so the client's stream is fully drained. */
    private static List<Message> iterate(ClaudeSDKClient client) {
        List<Message> messages = new java.util.ArrayList<>();
        for (Message message : client.receiveResponse()) {
            messages.add(message);
        }
        return messages;
    }

    /** The string prompts of a session, in transcript order. */
    private static List<String> prompts(String sessionId, Path cwd) {
        List<String> texts = new ArrayList<>();
        for (SessionMessage entry : ClaudeSDK.getSessionMessages(sessionId, cwd)) {
            String text = "user".equals(entry.type()) ? promptText(entry) : null;
            if (text != null) {
                texts.add(text);
            }
        }
        return texts;
    }

    /** UUID of the first user prompt following {@code afterUuid} in the transcript. */
    private static String promptUuidAfter(String sessionId, Path cwd, String afterUuid) {
        List<SessionMessage> chain = ClaudeSDK.getSessionMessages(sessionId, cwd);
        boolean seen = false;
        for (SessionMessage entry : chain) {
            if (seen && "user".equals(entry.type()) && promptText(entry) != null) {
                return entry.uuid();
            }
            if (afterUuid.equals(entry.uuid())) {
                seen = true;
            }
        }
        return null;
    }

    /** The plain-string prompt of a transcript entry, or null if it has none. */
    private static String promptText(SessionMessage entry) {
        return (entry.message() instanceof java.util.Map<?, ?> message
                && message.get("content") instanceof String content)
                        ? content
                        : null;
    }

}
