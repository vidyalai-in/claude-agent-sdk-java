package in.vidyalai.claude.sdk.mcp;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation for defining MCP tools declaratively.
 *
 * <p>
 * Use this annotation on methods to define them as MCP tools.
 * The annotated method must accept a single {@code Map<String, Object>}
 * parameter
 * and return a {@link ToolResult} or {@code CompletableFuture<ToolResult>}.
 *
 * <h2>Example Usage</h2>
 *
 * <pre>{@code
 * public class MyTools {
 *
 *     @Tool(name = "greet", description = "Greet a user")
 *     public ToolResult greet(Map<String, Object> args) {
 *         String name = (String) args.get("name");
 *         return ToolResult.text("Hello, " + name + "!");
 *     }
 *
 *     @Tool(name = "add", description = "Add two numbers")
 *     public CompletableFuture<ToolResult> add(Map<String, Object> args) {
 *         double a = ((Number) args.get("a")).doubleValue();
 *         double b = ((Number) args.get("b")).doubleValue();
 *         return CompletableFuture.completedFuture(
 *                 ToolResult.text("Result: " + (a + b)));
 *     }
 * }
 *
 * // Create server from annotated instance
 * MyTools tools = new MyTools();
 * SdkMcpServer server = SdkMcpServer.fromAnnotatedMethods("myserver", tools);
 * }</pre>
 *
 * <h2>Input Schema</h2>
 * <p>
 * The input schema can be specified as a JSON string. If not provided, the SDK
 * will automatically generate an MCP-compliant JSON Schema based on the method
 * parameters:
 *
 * <h3>Automatic Schema Generation</h3>
 * <p>
 * When {@code inputSchema} is not specified:
 * <ul>
 * <li>Methods with {@code Map<String, Object>} parameter get an empty object
 * schema</li>
 * <li>Java types are mapped to JSON Schema types (String → "string", int →
 * "integer", etc.)</li>
 * <li>All parameters are marked as required</li>
 * </ul>
 *
 * <p>
 * <b>Note:</b> Automatic generation requires compiling with the
 * {@code -parameters} flag
 * to preserve parameter names.
 *
 * <h3>Explicit Schema</h3>
 * <p>
 * You can provide an explicit schema as a JSON string:
 *
 * <pre>{@code
 * @Tool(name = "search", description = "Search for items", inputSchema = """
 *         {
 *             "type": "object",
 *             "properties": {
 *                 "query": {"type": "string"},
 *                 "limit": {"type": "integer", "default": 10}
 *             },
 *             "required": ["query"]
 *         }
 *         """)
 * public ToolResult search(Map<String, Object> args) {
 *     // ...
 * }
 * }</pre>
 *
 * @see SdkMcpServer#fromAnnotatedMethods(String, Object)
 * @see SdkMcpTool
 * @see ToolResult
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Tool {

    /**
     * The unique name of the tool.
     * <p>
     * This is what Claude will use to reference the tool in function calls.
     */
    String name();

    /**
     * Human-readable description of what the tool does.
     * <p>
     * This helps Claude understand when to use the tool.
     */
    String description();

    /**
     * JSON Schema for the tool's input parameters.
     * <p>
     * Optional. If not specified, an empty object schema is used.
     * <p>
     * Example:
     * 
     * <pre>{@code
     * inputSchema = """
     *         {
     *             "type": "object",
     *             "properties": {
     *                 "name": {"type": "string"}
     *             },
     *             "required": ["name"]
     *         }
     *         """
     * }</pre>
     */
    String inputSchema() default "";

}
