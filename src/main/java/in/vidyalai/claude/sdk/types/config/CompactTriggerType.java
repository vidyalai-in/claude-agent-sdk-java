package in.vidyalai.claude.sdk.types.config;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Type of trigger for compaction.
 */
public enum CompactTriggerType {

    /**
     * Manual trigger.
     */
    MANUAL("manual"),

    /**
     * Auto trigger.
     */
    AUTO("auto");

    private final String value;

    CompactTriggerType(String value) {
        this.value = value;
    }

    /**
     * Gets the JSON value for this trigger type.
     *
     * @return the string value
     */
    @JsonValue
    public String getValue() {
        return value;
    }

    /**
     * Converts a string value to a CompactTriggerType.
     *
     * @param value the string value
     * @return the corresponding enum constant
     * @throws IllegalArgumentException if the value is unknown
     */
    public static CompactTriggerType fromValue(String value) {
        for (CompactTriggerType type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown compact trigger type: " + value);
    }

}
