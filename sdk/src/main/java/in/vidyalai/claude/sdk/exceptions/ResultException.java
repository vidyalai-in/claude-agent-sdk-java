package in.vidyalai.claude.sdk.exceptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Raised when the CLI exits after reporting a terminal error result.
 *
 * <p>
 * The CLI ends a failed run by emitting a {@code result} message with
 * {@code is_error: true} (delivered to the consumer as a
 * {@link in.vidyalai.claude.sdk.types.message.ResultMessage}) and then exiting
 * non-zero. This exception replaces the bare "exit code 1"
 * {@link ProcessException} for that case and carries the result's payload, so
 * callers can branch on <i>why</i> the run failed without string matching:
 *
 * <pre>{@code
 * try {
 *     List<Message> messages = ClaudeSDK.query(prompt, options);
 * } catch (QueryFailedException e) {
 *     if (e.getCause() instanceof ResultException cause) {
 *         if ("api_error".equals(cause.terminalReason())) {
 *             retry();
 *         } else if ("error_max_turns".equals(cause.subtype())) {
 *             // ...
 *         }
 *     }
 * }
 * }</pre>
 *
 * <p>
 * It extends {@link ProcessException}, so existing
 * {@code catch (ProcessException e)} handlers keep working.
 *
 * <h2>Where it surfaces</h2>
 * <ul>
 * <li><b>The collecting {@link in.vidyalai.claude.sdk.ClaudeSDK#query(String)}
 * family</b> wraps it in a {@link QueryFailedException} so the messages
 * received before the failure are not lost; this exception is its
 * {@linkplain Throwable#getCause() cause}. That is the usual way to see it.</li>
 * <li><b>Directly, from a failed control request</b> — most importantly an
 * {@code initialize} the CLI refuses during startup (a resume rejected by
 * {@code resumeDropsTurn}, say). That happens before any message is collected,
 * so it is not wrapped.</li>
 * <li><b>Not</b> from
 * {@link in.vidyalai.claude.sdk.ClaudeSDKClient#receiveResponse()}, which
 * terminates at the {@code ResultMessage} (as the Python SDK's
 * {@code receive_response()} does) and therefore never observes the CLI's
 * subsequent exit. Inspect
 * {@link in.vidyalai.claude.sdk.types.message.ResultMessage#isError()} there
 * instead. {@code receiveMessages()} runs to end-of-stream and does raise, but
 * on a live client stdin stays open, so an error result mid-session does not
 * end the stream.</li>
 * </ul>
 *
 * @see QueryFailedException
 */
public class ResultException extends ProcessException {

    private static final long serialVersionUID = 1L;

    /**
     * Not serialized: the payload is an arbitrary decoded-JSON map whose values
     * are not guaranteed {@link java.io.Serializable}. A deserialized instance
     * reports an empty map rather than null; the typed accessors below are
     * plain serializable fields and survive the round trip.
     */
    @Nullable
    private final transient Map<String, Object> data;

    @Nullable
    private final String subtype;

    private final List<String> errors;

    @Nullable
    private final String result;

    @Nullable
    private final Integer apiErrorStatus;

    @Nullable
    private final String terminalReason;

    @Nullable
    private final String sessionId;

    /**
     * Creates a new exception from a {@code result} frame payload.
     *
     * @param message  the detail message
     * @param data     the raw {@code result} message payload as emitted by the
     *                 CLI, or null when unavailable
     * @param exitCode the CLI process exit code, or null when unavailable
     */
    public ResultException(String message, @Nullable Map<String, Object> data, @Nullable Integer exitCode) {
        super(message, exitCode, null);
        this.data = data;
        Map<String, Object> payload = (data != null) ? data : Map.of();
        this.subtype = asString(payload.get("subtype"));
        this.errors = normalizeErrors(payload.get("errors"));
        this.result = asString(payload.get("result"));
        this.apiErrorStatus = (payload.get("api_error_status") instanceof Number n) ? n.intValue() : null;
        this.terminalReason = asString(payload.get("terminal_reason"));
        this.sessionId = asString(payload.get("session_id"));
    }

    @Nullable
    private static String asString(@Nullable Object value) {
        return (value instanceof String s) ? s : null;
    }

    /**
     * Normalizes the {@code errors} field of a {@code result} frame to clean
     * strings.
     *
     * <p>
     * The CLI emits a list of strings; a bare string is tolerated (older or
     * buggy emitters) and non-string or blank entries are dropped, so the
     * structured {@link #errors()} and the exception text always agree.
     *
     * @param raw the raw {@code errors} value from the result frame
     * @return the cleaned error strings, never null (empty when there are none)
     */
    public static List<String> normalizeErrors(@Nullable Object raw) {
        List<?> items;
        if (raw instanceof String s) {
            items = List.of(s);
        } else if (raw instanceof List<?> l) {
            items = l;
        } else {
            return List.of();
        }
        List<String> cleaned = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof String s && !s.isBlank()) {
                cleaned.add(s.strip());
            }
        }
        return List.copyOf(cleaned);
    }

    /**
     * The result subtype.
     *
     * <p>
     * {@code "error_max_turns"}, {@code "error_during_execution"}, ... — or
     * {@code "success"} when the agent loop itself completed but the last turn
     * was an API error.
     *
     * @return the subtype, or null if the CLI did not report one
     */
    @Nullable
    public String subtype() {
        return subtype;
    }

    /**
     * The error strings reported by the CLI.
     *
     * @return an unmodifiable list, never null (empty when the CLI reported
     *         none — an API failure puts its prose in {@link #result()} instead)
     */
    public List<String> errors() {
        return (errors != null) ? errors : List.of();
    }

    /**
     * The result text, if any.
     *
     * <p>
     * For API failures this holds the {@code "API Error: ..."} prose.
     *
     * @return the result text, or null if the CLI did not report one
     */
    @Nullable
    public String result() {
        return result;
    }

    /**
     * The HTTP status of the failing API call, if any.
     *
     * @return the status code, or null if the failure was not an API call
     */
    @Nullable
    public Integer apiErrorStatus() {
        return apiErrorStatus;
    }

    /**
     * Why the run ended, if reported by the CLI.
     *
     * @return e.g. {@code "api_error"} or {@code "max_turns"}, or null
     */
    @Nullable
    public String terminalReason() {
        return terminalReason;
    }

    /**
     * The session the result belongs to, if reported.
     *
     * @return the session ID, or null
     */
    @Nullable
    public String sessionId() {
        return sessionId;
    }

    /**
     * The raw {@code result} message payload as emitted by the CLI.
     *
     * @return an unmodifiable view of the payload, never null (empty when the
     *         payload was unavailable or lost to deserialization)
     */
    public Map<String, Object> data() {
        return (data != null) ? java.util.Collections.unmodifiableMap(data) : Map.of();
    }

}
