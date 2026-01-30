package in.vidyalai.claude.sdk.mcp;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

/**
 * Definition for an SDK MCP tool.
 *
 * <p>
 * Tools are functions that Claude can call to perform actions or retrieve
 * information.
 * Each tool has a name, description, input schema, and handler function.
 *
 * <p>
 * Use the builder pattern to create tools:
 * 
 * <pre>{@code
 * SdkMcpTool greet = SdkMcpTool.builder("greet", "Greet a user")
 *         .inputSchema(Map.of(
 *                 "type", "object",
 *                 "properties", Map.of("name", Map.of("type", "string")),
 *                 "required", List.of("name")))
 *         .handler(args -> {
 *             String name = (String) args.get("name");
 *             return CompletableFuture.completedFuture(
 *                     ToolResult.text("Hello, " + name + "!"));
 *         })
 *         .build();
 * }</pre>
 *
 * @param <T> the type of the input arguments (typically Map&lt;String,
 *            Object&gt;)
 */
public final class SdkMcpTool<T> {

    private static final String TYPE = "type";
    private static final String OBJECT = "object";
    private static final String PROPERTIES = "properties";

    private final String name;
    private final String description;
    private final Map<String, Object> inputSchema;
    private final Function<T, CompletableFuture<ToolResult>> handler;

    private SdkMcpTool(Builder<T> builder) {
        this.name = builder.name;
        this.description = builder.description;
        this.inputSchema = builder.inputSchema;
        this.handler = builder.handler;
    }

    /**
     * Creates a new tool builder.
     *
     * @param name        the unique identifier for the tool
     * @param description human-readable description of what the tool does
     * @return a new builder
     */
    public static <T> Builder<T> builder(String name, String description) {
        return new Builder<>(name, description);
    }

    /**
     * Creates a simple tool with a map input schema.
     *
     * @param name        the tool name
     * @param description the tool description
     * @param inputSchema JSON schema for input validation
     * @param handler     the function to execute when the tool is called
     * @return a new tool instance
     */
    public static SdkMcpTool<Map<String, Object>> create(
            String name,
            String description,
            Map<String, Object> inputSchema,
            Function<Map<String, Object>, CompletableFuture<ToolResult>> handler) {
        return new Builder<Map<String, Object>>(name, description)
                .inputSchema(inputSchema)
                .handler(handler)
                .build();
    }

    // Getters

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public Map<String, Object> inputSchema() {
        return inputSchema;
    }

    public Function<T, CompletableFuture<ToolResult>> handler() {
        return handler;
    }

    /**
     * Invokes the tool handler with the given arguments.
     *
     * @param args the input arguments
     * @return a future with the tool result
     */
    public CompletableFuture<ToolResult> invoke(T args) {
        return handler.apply(args);
    }

    /**
     * Builder for SdkMcpTool.
     *
     * @param <T> the type of the input arguments (typically Map&lt;String,
     *            Object&gt;)
     */
    public static final class Builder<T> {

        private final String name;
        private final String description;
        private Map<String, Object> inputSchema = Map.of(TYPE, OBJECT, PROPERTIES, Map.of());
        @Nullable
        private Function<T, CompletableFuture<ToolResult>> handler;

        private Builder(String name, String description) {
            this.name = name;
            this.description = description;
        }

        /**
         * Sets the JSON schema for input validation.
         *
         * @param inputSchema the JSON schema
         */
        public Builder<T> inputSchema(Map<String, Object> inputSchema) {
            this.inputSchema = inputSchema;
            return this;
        }

        /**
         * Sets the handler function for this tool.
         *
         * @param handler the function to execute
         */
        public Builder<T> handler(Function<T, CompletableFuture<ToolResult>> handler) {
            this.handler = handler;
            return this;
        }

        /**
         * Builds the tool.
         *
         * @return a new SdkMcpTool instance
         */
        public SdkMcpTool<T> build() {
            if (handler == null) {
                throw new IllegalStateException("Handler is required");
            }
            return new SdkMcpTool<>(this);
        }

    }

}
