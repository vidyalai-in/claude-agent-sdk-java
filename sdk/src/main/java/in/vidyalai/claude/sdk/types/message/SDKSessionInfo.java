package in.vidyalai.claude.sdk.types.message;

import org.jspecify.annotations.Nullable;

/**
 * Session metadata returned by {@code ClaudeSDK.listSessions()}.
 *
 * <p>
 * Contains only data extractable from stat and head/tail reads — no full
 * JSONL parsing required.
 *
 * @param sessionId    unique session identifier (UUID)
 * @param summary      display title for the session — custom title,
 *                     auto-generated summary, or first prompt
 * @param lastModified last modified time in milliseconds since epoch
 * @param fileSize     session file size in bytes
 * @param customTitle  user-set session title (may be null)
 * @param firstPrompt  first meaningful user prompt in the session (may be null)
 * @param gitBranch    git branch at the end of the session (may be null)
 * @param cwd          working directory for the session (may be null)
 */
public record SDKSessionInfo(
        String sessionId,
        String summary,
        long lastModified,
        long fileSize,
        @Nullable String customTitle,
        @Nullable String firstPrompt,
        @Nullable String gitBranch,
        @Nullable String cwd) {
}
