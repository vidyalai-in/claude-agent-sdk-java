package in.vidyalai.claude.sdk.types.config;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Setting sources for loading configuration.
 */
public enum SettingSource {

    /**
     * User-level settings.
     */
    USER("user"),

    /**
     * Project-level settings.
     */
    PROJECT("project"),

    /**
     * Local settings.
     */
    LOCAL("local");

    private final String value;

    SettingSource(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    public static SettingSource fromValue(String value) {
        for (SettingSource source : values()) {
            if (source.value.equals(value)) {
                return source;
            }
        }
        throw new IllegalArgumentException("Unknown setting source: " + value);
    }

}
