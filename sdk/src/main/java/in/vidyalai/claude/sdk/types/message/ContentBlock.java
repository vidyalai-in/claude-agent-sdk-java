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
 * <li>{@link ImageBlock} - An image, e.g. a rendered PDF page</li>
 * <li>{@link DocumentBlock} - A whole file, e.g. a PDF read by the {@code Read} tool</li>
 * <li>{@link UnknownBlock} - Any block type this SDK does not model, preserved rather than
 * rejected</li>
 * </ul>
 *
 * <p>
 * Because {@link UnknownBlock} exists, a switch over this interface should have a branch for it
 * even though the interface is sealed: a block type added to the CLI after this release arrives
 * as an {@code UnknownBlock} rather than failing to parse.
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
 *     case ImageBlock image -> System.out.println("Image: " + image.mediaType());
 *     case DocumentBlock doc -> System.out.println("Document: " + doc.mediaType());
 *     case UnknownBlock unknown -> System.out.println("Unmodelled block: " + unknown.type());
 * }
 * }</pre>
 */
public sealed interface ContentBlock
        permits TextBlock, ThinkingBlock, ToolUseBlock, ToolResultBlock, ServerToolUseBlock, ServerToolResultBlock,
        ImageBlock, DocumentBlock, UnknownBlock {

    /**
     * Returns the type identifier for this content block.
     *
     * @return the type string ("text", "thinking", "tool_use", "tool_result",
     *         "server_tool_use", "server_tool_result", "image", "document", or — for an
     *         {@link UnknownBlock} — whatever the CLI sent
     */
    String type();

}
