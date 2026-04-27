package in.vidyalai.claude.sdk.types.message;

import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;

import in.vidyalai.claude.sdk.types.session.SessionKey;

/**
 * System message emitted when a {@code SessionStore.append} call fails.
 *
 * <p>Non-fatal — the local-disk transcript is already durable, so the session
 * continues unaffected. The mirrored copy in the external store will be missing
 * the failed batch.
 *
 * <p>Modeled as a top-level record in the {@link Message} sealed hierarchy
 * (Java doesn't allow records to extend records). The {@code subtype} is
 * always {@code "mirror_error"}; the {@code data} field carries the raw
 * payload for {@link SystemMessage} compatibility.
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
