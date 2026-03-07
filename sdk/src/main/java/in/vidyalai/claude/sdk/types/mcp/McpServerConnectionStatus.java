package in.vidyalai.claude.sdk.types.mcp;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * MCP server connection status values.
 */
public enum McpServerConnectionStatus {

    CONNECTED("connected"),
    FAILED("failed"),
    NEEDS_AUTH("needs-auth"),
    PENDING("pending"),
    DISABLED("disabled");

    private final String value;

    McpServerConnectionStatus(String value) {
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

    public static McpServerConnectionStatus fromValue(String value) {
        for (McpServerConnectionStatus s : values()) {
            if (s.value.equals(value)) {
                return s;
            }
        }
        throw new IllegalArgumentException("Unknown McpServerConnectionStatus: " + value);
    }

}
