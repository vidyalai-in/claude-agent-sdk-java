package in.vidyalai.claude.sdk.types.message;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * System message with metadata.
 *
 * @param subtype the subtype of the system message
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

}
