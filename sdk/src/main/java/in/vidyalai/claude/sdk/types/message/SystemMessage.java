package in.vidyalai.claude.sdk.types.message;

import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * System message with metadata.
 *
 * <p>
 * For {@code subtype == "init"}, {@code data} is the session metadata frame
 * described by {@link SystemInitData}; read it with {@link #initData()}.
 *
 * <p>
 * The subtypes the SDK models ({@code task_started}, {@code task_progress},
 * {@code task_notification}, {@code task_updated}, {@code hook_started},
 * {@code hook_response}, {@code mirror_error}) parse to their own records,
 * which are not {@code SystemMessage} instances. See {@link Message}.
 *
 * @param subtype the subtype of the system message. Null only when the CLI
 *                sent {@code subtype} as an explicit null or a non-string (the
 *                raw value stays in {@code data})
 * @param data    the raw data dictionary containing all message fields
 */
public record SystemMessage(
        @JsonProperty("subtype") String subtype,
        @JsonProperty("data") Map<String, Object> data) implements Message {

    @Override
    public String type() {
        return "system";
    }

    /**
     * Gets a value from the data map.
     *
     * @param key the key to look up
     * @param <T> the expected type
     * @return the value, or null if not present
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        return (T) data.get(key);
    }

    /**
     * The typed view of {@link #data()} for an {@code init} message.
     *
     * <p>
     * The payload is read as sent, not validated: see {@link SystemInitData}.
     *
     * @return the init data, or null if this is not an {@code init} message
     *         (or if {@link #data()} is null, which the parser never produces)
     * @throws IllegalArgumentException if a field has the wrong JSON shape
     */
    public @Nullable SystemInitData initData() {
        return SystemInitData.SUBTYPE.equals(subtype) ? SystemInitData.from(data) : null;
    }

}
