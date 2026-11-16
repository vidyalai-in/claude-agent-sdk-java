package in.vidyalai.claude.sdk.types.message;

import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;

import in.vidyalai.claude.sdk.types.session.SessionKey;

/**
 * System message emitted when a {@code SessionStore.append} call fails.
 *
 * <p>Non-fatal — the local-disk transcript is already durable, so the session
 * continues unaffected. The failed batch has been dropped (retries exhausted, or
 * a single attempt timed out), so the external store may be missing it. A
 * timed-out {@code append} may still land.
 *
 * <p><b>Not a {@link SystemMessage} instance.</b> {@link #type()} returns
 * {@code "system"}, but Java records cannot extend one another, so this is a
 * sibling record in the sealed {@link Message} hierarchy:
 * {@code instanceof SystemMessage} is {@code false} for it. (In the Python SDK
 * it subclasses {@code SystemMessage}.) Match it by its own type, or, to
 * handle every system message alike, test {@code "system".equals(message.type())}
 * and read {@code subtype()} (always {@code "mirror_error"}) and
 * {@code data()} (the raw payload), which hold what a {@code SystemMessage}
 * would.
 *
 * @param subtype always {@code "mirror_error"}
 * @param data    the raw data dictionary
 * @param key     the session key that the failed append was targeting
 *                (may be {@code null} if the failure occurred before key resolution)
 * @param error   error message describing the failure
 */
public record MirrorErrorMessage(
        @JsonProperty("subtype") String subtype,
        @JsonProperty("data") Map<String, Object> data,
        @JsonProperty("key") @Nullable SessionKey key,
        @JsonProperty("error") String error) implements Message {

    @Override
    public String type() {
        return "system";
    }

    /**
     * Convenience constructor with the canonical "mirror_error" subtype.
     */
    public MirrorErrorMessage(Map<String, Object> data, @Nullable SessionKey key, String error) {
        this("mirror_error", data, key, error);
    }

}
