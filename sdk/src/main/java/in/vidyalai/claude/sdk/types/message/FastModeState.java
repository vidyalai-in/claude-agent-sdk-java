package in.vidyalai.claude.sdk.types.message;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Fast mode state reported in {@link SystemInitData#fastModeState()}.
 *
 * @see SystemInitData
 */
public enum FastModeState {

    /** Wire value {@code "off"}. */
    OFF("off"),

    /** Wire value {@code "cooldown"}. */
    COOLDOWN("cooldown"),

    /** Wire value {@code "on"}. */
    ON("on");

    private final String value;

    FastModeState(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }

    public static FastModeState fromValue(String value) {
        for (FastModeState v : values()) {
            if (v.value.equals(value)) {
                return v;
            }
        }
        throw new IllegalArgumentException("Unknown FastModeState: " + value);
    }

}
