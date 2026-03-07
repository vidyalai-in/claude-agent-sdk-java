package examples;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.types.message.SDKSessionInfo;
import in.vidyalai.claude.sdk.types.message.SessionMessage;

/**
 * Example demonstrating the session listing APIs.
 *
 * <p>
 * Claude Code stores conversation history in {@code ~/.claude/projects/}.
 * These APIs let you read that history without running the CLI:
 * </p>
 * <ul>
 * <li>{@link ClaudeSDK#listSessions()} — list all sessions across all
 * projects</li>
 * <li>{@link ClaudeSDK#listSessions(Path)} — list sessions for a specific
 * project directory</li>
 * <li>{@link ClaudeSDK#getSessionMessages(String)} — retrieve full message
 * history for a session</li>
 * </ul>
 *
 * <p>
 * Sessions are read directly from the JSONL files on disk. Only the first and
 * last 64 KB of each file are read for listing (fast), while the full file is
 * read when retrieving messages.
 * </p>
 */
public class SessionListingExample {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    public static void main(String[] args) {
        System.out.println("=== Session Listing Example ===\n");

        // Example 1: List most recent sessions across all projects
        listRecentSessions();

        // Example 2: List sessions for the current project
        listCurrentProjectSessions();

        // Example 3: Get messages from the most recent session
        readMostRecentSession();
    }

    @SuppressWarnings("null")
    static void listRecentSessions() {
        System.out.println("--- Recent Sessions (all projects, limit 5) ---");

        List<SDKSessionInfo> sessions = ClaudeSDK.listSessions(null, 5, true);

        if (sessions.isEmpty()) {
            System.out.println("No sessions found in ~/.claude/projects/");
            System.out.println("Run a Claude Code session first to see results here.\n");
            return;
        }

        for (SDKSessionInfo session : sessions) {
            String time = FORMATTER.format(Instant.ofEpochMilli(session.lastModified()));
            System.out.printf("[%s] %s%n", time, session.summary());
            System.out.printf("  id:  %s%n", session.sessionId());
            if (session.cwd() != null) {
                System.out.printf("  cwd: %s%n", session.cwd());
            }
            if (session.gitBranch() != null) {
                System.out.printf("  git: %s%n", session.gitBranch());
            }
            if (session.firstPrompt() != null && !session.firstPrompt().equals(session.summary())) {
                System.out.printf("  prompt: %s%n", session.firstPrompt());
            }
            System.out.println();
        }
    }

    static void listCurrentProjectSessions() {
        System.out.println("--- Sessions for Current Directory ---");

        Path cwd = Path.of(System.getProperty("user.dir"));
        List<SDKSessionInfo> sessions = ClaudeSDK.listSessions(cwd);

        if (sessions.isEmpty()) {
            System.out.printf("No sessions found for: %s%n%n", cwd);
            return;
        }

        System.out.printf("Found %d session(s) for: %s%n", sessions.size(), cwd);
        for (SDKSessionInfo session : sessions) {
            String time = FORMATTER.format(Instant.ofEpochMilli(session.lastModified()));
            System.out.printf("  [%s] %s (%.1f KB)%n",
                    time, session.summary(), session.fileSize() / 1024.0);
        }
        System.out.println();
    }

    static void readMostRecentSession() {
        System.out.println("--- Messages from Most Recent Session ---");

        List<SDKSessionInfo> sessions = ClaudeSDK.listSessions(null, 1, false);

        if (sessions.isEmpty()) {
            System.out.println("No sessions found.\n");
            return;
        }

        SDKSessionInfo mostRecent = sessions.get(0);
        System.out.printf("Session: %s%n", mostRecent.summary());
        System.out.printf("ID: %s%n%n", mostRecent.sessionId());

        List<SessionMessage> messages = ClaudeSDK.getSessionMessages(mostRecent.sessionId());

        if (messages.isEmpty()) {
            System.out.println("No messages found in session.\n");
            return;
        }

        System.out.printf("Messages (%d total):%n", messages.size());

        // Show first 3 messages for brevity
        int shown = Math.min(3, messages.size());
        for (int i = 0; i < shown; i++) {
            SessionMessage msg = messages.get(i);
            System.out.printf("%n[%s] uuid=%s%n", msg.type().toUpperCase(), msg.uuid());

            Object message = msg.message();
            if (message instanceof Map<?, ?> m) {
                Object content = m.get("content");
                if (content instanceof String s) {
                    System.out.printf("  %s%n", truncate(s, 120));
                } else if (content instanceof List<?> list && !list.isEmpty()) {
                    Object first = list.get(0);
                    if (first instanceof Map<?, ?> block && block.get("text") instanceof String t) {
                        System.out.printf("  %s%n", truncate(t, 120));
                    }
                }
            }
        }

        if (messages.size() > shown) {
            System.out.printf("%n  ... and %d more messages%n", messages.size() - shown);
        }
        System.out.println();
    }

    private static String truncate(String s, int max) {
        s = s.replace("\n", "\\n");
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
