package in.vidyalai.claude.sdk.types.session;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Incrementally-maintained session summary.
 *
 * <p>Stores obtain this from {@code foldSessionSummary(...)} inside
 * {@link SessionStore#append(SessionKey, java.util.List)} and persist it verbatim;
 * they return the full set from {@link SessionStore#listSessionSummaries(String)}.
 * The {@code data} field is opaque SDK-owned state — stores MUST NOT interpret it.
 *
 * @param sessionId session UUID
 * @param mtime     storage write time of the sidecar, in Unix epoch milliseconds.
 *                  Must use the same clock source as the {@code mtime} returned by
 *                  {@link SessionStore#listSessions(String)} for this session
 * @param data      opaque SDK-owned summary state. Persist verbatim; do not interpret.
 */
public record SessionSummaryEntry(
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("mtime") long mtime,
        @JsonProperty("data") Map<String, Object> data) {
}
