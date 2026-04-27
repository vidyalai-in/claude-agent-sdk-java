package in.vidyalai.claude.sdk.types.session;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Entry returned by {@link SessionStore#listSessions(String)}.
 *
 * @param sessionId session UUID
 * @param mtime     last-modified time in Unix epoch milliseconds. Adapters
 *                  without native modification time (e.g. Redis) must maintain
 *                  their own index.
 */
public record SessionStoreListEntry(
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("mtime") long mtime) {
}
