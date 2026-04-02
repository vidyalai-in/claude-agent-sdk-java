package in.vidyalai.claude.sdk.types.mcp;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response from {@code ClaudeSDKClient.getContextUsage()}.
 *
 * <p>
 * Provides a breakdown of current context window usage by category,
 * matching the data shown by the {@code /context} command in the CLI.
 *
 * @param categories           token usage broken down by category
 * @param totalTokens          total tokens in the context window
 * @param maxTokens            effective maximum tokens (may be reduced by
 *                             autocompact buffer)
 * @param rawMaxTokens         raw model context window size
 * @param percentage           percentage of context window used (0-100)
 * @param model                model name the context usage is calculated for
 * @param isAutoCompactEnabled whether autocompact is enabled
 * @param memoryFiles          CLAUDE.md and memory files loaded
 * @param mcpTools             MCP tools with name, serverName, tokens
 * @param agents               agent definitions with agentType, source, tokens
 * @param gridRows             visual grid representation for CLI display
 * @param autoCompactThreshold token threshold for autocompact trigger
 * @param deferredBuiltinTools built-in tools deferred from initial list
 * @param systemTools          system tools with name and token counts
 * @param systemPromptSections system prompt sections with name and token counts
 * @param slashCommands        slash command usage summary
 * @param skills               skill usage summary with frontmatter breakdown
 * @param messageBreakdown     detailed breakdown of message tokens by type
 * @param apiUsage             cumulative API usage for the session
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ContextUsageResponse(
        @JsonProperty("categories") List<ContextUsageCategory> categories,
        @JsonProperty("totalTokens") int totalTokens,
        @JsonProperty("maxTokens") int maxTokens,
        @JsonProperty("rawMaxTokens") int rawMaxTokens,
        @JsonProperty("percentage") double percentage,
        @JsonProperty("model") String model,
        @JsonProperty("isAutoCompactEnabled") boolean isAutoCompactEnabled,
        @JsonProperty("memoryFiles") List<Map<String, Object>> memoryFiles,
        @JsonProperty("mcpTools") List<Map<String, Object>> mcpTools,
        @JsonProperty("agents") List<Map<String, Object>> agents,
        @JsonProperty("gridRows") List<List<Map<String, Object>>> gridRows,
        @JsonProperty("autoCompactThreshold") @Nullable Integer autoCompactThreshold,
        @JsonProperty("deferredBuiltinTools") @Nullable List<Map<String, Object>> deferredBuiltinTools,
        @JsonProperty("systemTools") @Nullable List<Map<String, Object>> systemTools,
        @JsonProperty("systemPromptSections") @Nullable List<Map<String, Object>> systemPromptSections,
        @JsonProperty("slashCommands") @Nullable Map<String, Object> slashCommands,
        @JsonProperty("skills") @Nullable Map<String, Object> skills,
        @JsonProperty("messageBreakdown") @Nullable Map<String, Object> messageBreakdown,
        @JsonProperty("apiUsage") @Nullable Map<String, Object> apiUsage) {
}
