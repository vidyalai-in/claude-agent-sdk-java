package in.vidyalai.claude.sdk.types.config;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * AI model to be used in {@link AgentDefinition}.
 */
public enum AIModel {

    SONNET("sonnet"),

    OPUS("opus"),

    HAIKU("haiku"),

    INHERIT("inherit");

    private final String value;

    AIModel(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    public static AIModel fromValue(String value) {
        for (AIModel model : values()) {
            if (model.value.equals(value)) {
                return model;
            }
        }
        throw new IllegalArgumentException("Unknown AI model: " + value);
    }

}
