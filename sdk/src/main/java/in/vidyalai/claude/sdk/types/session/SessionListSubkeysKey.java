package in.vidyalai.claude.sdk.types.session;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Key argument to {@link SessionStore#listSubkeys(SessionListSubkeysKey)} (no {@code subpath}).
 *
 * @param projectKey caller-defined scope (see {@link SessionKey#projectKey()})
 * @param sessionId  the session UUID
 */
public record SessionListSubkeysKey(
        @JsonProperty("project_key") String projectKey,
        @JsonProperty("session_id") String sessionId) {
}
