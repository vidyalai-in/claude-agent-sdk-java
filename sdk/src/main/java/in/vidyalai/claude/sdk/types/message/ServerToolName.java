package in.vidyalai.claude.sdk.types.message;

/**
 * Names of server-side tools the API may execute on the model's behalf.
 *
 * <p>Server-side tools appear in the assistant message stream alongside regular
 * {@link ToolUseBlock} instances, but the caller never returns a result for them.
 * Branch on this discriminator to know which server tool was invoked.
 */
public enum ServerToolName {

    ADVISOR("advisor"),
    WEB_SEARCH("web_search"),
    WEB_FETCH("web_fetch"),
    CODE_EXECUTION("code_execution"),
    BASH_CODE_EXECUTION("bash_code_execution"),
    TEXT_EDITOR_CODE_EXECUTION("text_editor_code_execution"),
    TOOL_SEARCH_TOOL_REGEX("tool_search_tool_regex"),
    TOOL_SEARCH_TOOL_BM25("tool_search_tool_bm25");

    private final String value;

    ServerToolName(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static ServerToolName fromValue(String value) {
        for (ServerToolName name : values()) {
            if (name.value.equals(value)) {
                return name;
            }
        }
        throw new IllegalArgumentException("Unknown server tool name: " + value);
    }

    @Override
    public String toString() {
        return value;
    }

}
