package in.vidyalai.claude.sdk.types.config;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Memory scope for an {@link AgentDefinition}.
 */
public enum MemoryScope {

    USER("user"),

    PROJECT("project"),

    LOCAL("local");

    private final String value;

    MemoryScope(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    public static MemoryScope fromValue(String value) {
        for (MemoryScope scope : values()) {
            if (scope.value.equals(value)) {
                return scope;
            }
        }
        throw new IllegalArgumentException("Unknown memory scope: " + value);
    }

}
