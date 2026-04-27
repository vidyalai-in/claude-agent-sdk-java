package in.vidyalai.claude.sdk.types.session;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Identifies a session transcript or subagent transcript in a {@link SessionStore}.
 *
 * <p>Main transcripts have no {@code subpath}; subagent transcripts include a
 * {@code subpath} like {@code "subagents/agent-{id}"} that mirrors the on-disk
 * directory structure.
 *
 * @param projectKey caller-defined scope. Default: sanitized cwd. Multi-tenant
 *                   deployments should set this to a tenant ID or project name.
 *                   Paths longer than 200 characters are truncated and suffixed
 *                   with a portable djb2 hash so the same path yields the same
 *                   key across runtimes.
 * @param sessionId  the session UUID
 * @param subpath    omit (null) for the main transcript; set for subagent files.
 *                   Empty string is invalid — pass {@code null} for the main
 *                   transcript. Opaque to the adapter — just use it as a storage
 *                   key suffix.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionKey(
        @JsonProperty("project_key") String projectKey,
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("subpath") @Nullable String subpath) {

    /**
     * Convenience factory for a main transcript key (no subpath).
     */
    public static SessionKey main(String projectKey, String sessionId) {
        return new SessionKey(projectKey, sessionId, null);
    }

    /**
     * Convenience factory for a subagent transcript key.
     */
    public static SessionKey subagent(String projectKey, String sessionId, String subpath) {
        return new SessionKey(projectKey, sessionId, subpath);
    }

}
