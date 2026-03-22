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
 * @param fileSize     session file size in bytes. Only populated for local
 *                     JSONL storage; may be null for remote storage backends.
 * @param customTitle  session title — user-set custom title or AI-generated
 *                     title (may be null)
 * @param firstPrompt  first meaningful user prompt in the session (may be null)
 * @param gitBranch    git branch at the end of the session (may be null)
 * @param cwd          working directory for the session (may be null)
 * @param tag          user-set session tag (may be null)
 * @param createdAt    creation time in milliseconds since epoch, extracted from
 *                     the first entry's ISO timestamp field. More reliable than
 *                     file birthtime on some filesystems. May be null.
 */
public record SDKSessionInfo(
        String sessionId,
        String summary,
        long lastModified,
        @Nullable Long fileSize,
        @Nullable String customTitle,
        @Nullable String firstPrompt,
        @Nullable String gitBranch,
        @Nullable String cwd,
        @Nullable String tag,
        @Nullable Long createdAt) {

    /**
     * Backwards-compatible constructor without tag and createdAt fields.
     */
    public SDKSessionInfo(String sessionId, String summary, long lastModified,
            @Nullable Long fileSize, @Nullable String customTitle,
            @Nullable String firstPrompt, @Nullable String gitBranch,
            @Nullable String cwd) {
        this(sessionId, summary, lastModified, fileSize, customTitle,
                firstPrompt, gitBranch, cwd, null, null);
    }

}
