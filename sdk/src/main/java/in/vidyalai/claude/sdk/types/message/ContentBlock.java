package in.vidyalai.claude.sdk.types.message;

/**
 * Sealed interface representing content blocks in messages.
 *
 * <p>
 * Content blocks can be one of:
 * <ul>
 * <li>{@link TextBlock} - Plain text content</li>
 * <li>{@link ThinkingBlock} - Claude's reasoning/thinking content</li>
 * <li>{@link ToolUseBlock} - Tool invocation request</li>
 * <li>{@link ToolResultBlock} - Result from tool execution</li>
 * <li>{@link ServerToolUseBlock} - Server-side tool invocation (advisor, web_search, etc.)</li>
 * <li>{@link ServerToolResultBlock} - Result from a server-side tool</li>
 * </ul>
 *
 * <p>
 * Use pattern matching to handle different block types:
 *
 * <pre>{@code
 * switch (block) {
 *     case TextBlock text -> System.out.println(text.text());
 *     case ToolUseBlock tool -> System.out.println("Tool: " + tool.name());
 *     case ThinkingBlock thinking -> System.out.println("Thinking: " + thinking.thinking());
 *     case ToolResultBlock result -> System.out.println("Result: " + result.content());
 *     case ServerToolUseBlock stu -> System.out.println("Server tool: " + stu.name());
 *     case ServerToolResultBlock str -> System.out.println("Server tool result for " + str.toolUseId());
 * }
 * }</pre>
 */
public sealed interface ContentBlock
        permits TextBlock, ThinkingBlock, ToolUseBlock, ToolResultBlock, ServerToolUseBlock, ServerToolResultBlock {

    /**
     * Returns the type identifier for this content block.
     *
     * @return the type string ("text", "thinking", "tool_use", "tool_result",
     *         "server_tool_use", or "server_tool_result")
     */
    String type();

}
