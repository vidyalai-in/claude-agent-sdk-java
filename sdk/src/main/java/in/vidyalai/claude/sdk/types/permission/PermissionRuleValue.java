package in.vidyalai.claude.sdk.types.permission;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Permission rule value for tool permissions.
 *
 * <p>
 * <b>JSON Naming Convention:</b> This type uses {@code camelCase} for JSON
 * field names because it represents data <b>sent to the CLI</b> in control
 * protocol responses.
 * See {@link in.vidyalai.claude.sdk.types} package documentation for details.
 *
 * @param toolName    the name of the tool this rule applies to
 * @param ruleContent optional content for the rule
 */
public record PermissionRuleValue(
        @JsonProperty("toolName") String toolName,
        @JsonProperty("ruleContent") @Nullable String ruleContent) {

    /**
     * Creates a permission rule for a tool without content.
     *
     * @param toolName the tool name
     */
    public PermissionRuleValue(String toolName) {
        this(toolName, null);
    }

}
