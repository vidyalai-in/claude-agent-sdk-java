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
 * The annotated method either accepts a single {@code Map<String, Object>}
 * parameter holding every argument, or declares one typed parameter per
 * argument (optionally described with {@link ToolParam}), plus an optional
 * {@link ToolCallContext}. It returns a {@link ToolResult} or
 * {@code CompletableFuture<ToolResult>}.
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
 * <li>Java types are mapped to JSON Schema from their generic type
 * ({@code String} → "string", {@code int} → "integer",
 * {@code List<String>} → array of strings, an enum → a string limited to its
 * constants, a record → a nested object, and so on; see
 * {@link SdkMcpServer#fromAnnotatedMethods(String, Object)})</li>
 * <li>All parameters are marked as required, except {@code Optional} ones and
 * those marked {@link ToolParam#required() @ToolParam(required = false)}</li>
 * </ul>
 *
 * <pre>{@code
 * @Tool(name = "paint", description = "Paint some walls")
 * public ToolResult paint(
 *         @ToolParam(description = "Walls to paint") List<String> walls,
 *         @ToolParam(description = "Paint color") Color color) {
 *     ...
 * }
 * }</pre>
 *
 * <p>
 * <b>Note:</b> automatic generation names arguments after the Java
 * parameters, which needs the {@code -parameters} compiler flag, unless each
 * is named with {@link ToolParam#name()}.
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
 * <h2>Tool Annotations</h2>
 * <p>
 * To add semantic hints, implement {@link ToolAnnotations} and reference the
 * class:
 *
 * <pre>{@code
 * public class ReadOnlyHints implements ToolAnnotations {
 *     public String title() { return "Data Reader"; }
 *     public Boolean readOnlyHint() { return true; }
 * }
 *
 * @Tool(name = "read", description = "Read data", annotations = ReadOnlyHints.class)
 * public ToolResult readData(Map<String, Object> args) { ... }
 * }</pre>
 *
 * @see SdkMcpServer#fromAnnotatedMethods(String, Object)
 * @see SdkMcpTool
 * @see ToolResult
 * @see ToolAnnotations
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
     * Optional. If not specified, the schema is generated from the method's
     * parameters.
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

    /**
     * A human-readable title for the tool.
     * <p>
     * Optional. Provides a short, friendly display name distinct from the
     * technical tool name.
     */
    String title() default "";

    /**
     * The {@link ToolAnnotations} implementation class providing semantic hints.
     * <p>
     * Optional. If not specified, no annotation hints are attached to the tool.
     * The referenced class must have a public no-arg constructor.
     */
    Class<? extends ToolAnnotations> annotations() default ToolAnnotations.None.class;

}
