package in.vidyalai.claude.sdk.mcp;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import in.vidyalai.claude.sdk.types.mcp.McpSdkServerConfig;

/**
 * In-process MCP server that runs within the Java application.
 *
 * <p>
 * Unlike external MCP servers that run as separate processes, SDK MCP servers
 * run directly in your application's process. This provides:
 * <ul>
 * <li>Better performance (no IPC overhead)</li>
 * <li>Simpler deployment (single process)</li>
 * <li>Easier debugging (same process)</li>
 * <li>Direct access to your application's state</li>
 * </ul>
 *
 * <p>
 * Example usage:
 * 
 * <pre>{@code
 * SdkMcpTool<Map<String, Object>> greet = SdkMcpTool.create(
 *         "greet", "Greet a user",
 *         Map.of("type", "object", "properties", Map.of("name", Map.of("type", "string"))),
 *         args -> CompletableFuture.completedFuture(
 *                 ToolResult.text("Hello, " + args.get("name") + "!")));
 *
 * SdkMcpServer server = SdkMcpServer.create("myserver", "1.0.0", List.of(greet));
 *
 * // Use with ClaudeAgentOptions
 * var options = ClaudeAgentOptions.builder()
 *         .mcpServers(Map.of("myserver", server.toConfig()))
 *         .build();
 * }</pre>
 */
public final class SdkMcpServer {

    private static final Logger logger = Logger.getLogger(SdkMcpServer.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // JSON-RPC message keys
    private static final String KEY_METHOD = "method";
    private static final String KEY_ID = "id";
    private static final String KEY_PARAMS = "params";
    private static final String KEY_JSONRPC = "jsonrpc";
    private static final String KEY_RESULT = "result";
    private static final String KEY_ERROR = "error";
    private static final String KEY_CODE = "code";
    private static final String KEY_MESSAGE = "message";

    // MCP protocol keys
    private static final String KEY_PROTOCOL_VERSION = "protocolVersion";
    private static final String PROTOCOL_VERSION = "2024-11-05";
    private static final String KEY_CAPABILITIES = "capabilities";
    private static final String KEY_TOOLS = "tools";
    private static final String KEY_SERVER_INFO = "serverInfo";
    private static final String KEY_NAME = "name";
    private static final String KEY_VERSION = "version";
    private static final String VERSION = "1.0.0";
    private static final String KEY_DESCRIPTION = "description";
    private static final String KEY_INPUT_SCHEMA = "inputSchema";
    private static final String KEY_ARGUMENTS = "arguments";

    // Schema keys
    private static final String KEY_TYPE = "type";
    private static final String KEY_OBJECT = "object";
    private static final String KEY_PROPERTIES = "properties";

    // MCP method names
    private static final String METHOD_INITIALIZE = "initialize";
    private static final String METHOD_LIST_TOOLS = "tools/list";
    private static final String METHOD_CALL_TOOL = "tools/call";
    private static final String METHOD_INITIALIZED = "notifications/initialized";

    // JSON-RPC error codes
    private static final int ERROR_CODE_METHOD_NOT_FOUND = -32601;
    private static final int ERROR_CODE_INTERNAL_ERROR = -32603;

    // JSON-RPC version
    private static final String JSONRPC_VERSION = "2.0";

    private final String name;
    private final String version;
    private final Map<String, SdkMcpTool<?>> tools;

    private SdkMcpServer(String name, String version, List<SdkMcpTool<?>> tools) {
        this.name = name;
        this.version = version;
        this.tools = new ConcurrentHashMap<>();
        for (SdkMcpTool<?> tool : tools) {
            this.tools.put(tool.name(), tool);
        }
    }

    /**
     * Creates a new SDK MCP server.
     *
     * @param name    the server name
     * @param version the server version
     * @param tools   list of tools to register
     * @return a new server instance
     */
    public static SdkMcpServer create(String name, String version, List<SdkMcpTool<?>> tools) {
        return new SdkMcpServer(name, version, ((tools != null) ? tools : List.of()));
    }

    /**
     * Creates a new SDK MCP server with default version.
     *
     * @param name  the server name
     * @param tools list of tools to register
     * @return a new server instance
     */
    public static SdkMcpServer create(String name, List<SdkMcpTool<?>> tools) {
        return create(name, VERSION, tools);
    }

    /**
     * Creates an SDK MCP server from methods annotated with {@link Tool}.
     *
     * <p>
     * This method scans the provided object for methods annotated with
     * {@code @Tool} and creates tools from them. If no {@code inputSchema}
     * is provided in the annotation, the SDK automatically generates an
     * MCP-compliant JSON Schema from the method parameters.
     *
     * <h3>Automatic Schema Generation</h3>
     * <p>
     * When {@code inputSchema} is not specified in the {@code @Tool} annotation:
     * <ul>
     * <li>Methods with {@code Map<String, Object>} parameter receive an empty
     * object schema</li>
     * <li>Parameter types are mapped to JSON Schema types (String → "string",
     * int → "integer", etc.)</li>
     * <li>All parameters are marked as required in the generated schema</li>
     * </ul>
     *
     * <p>
     * <b>Note:</b> Compile with the {@code -parameters} flag to enable parameter
     * name
     * reflection for automatic schema generation.
     *
     * <pre>{@code
     *     public class MyTools {
     *         // Automatic schema generation (empty object schema)
     *         @Tool(name = "greet", description = "Greet a user")
     *         public ToolResult greet(Map<String, Object> args) {
     *             return ToolResult.text("Hello, " + args.get("name") + "!");
     *         }
     *
     *         // Explicit schema (takes precedence)
     *         @Tool(name = "search", description = "Search items", inputSchema = "{\"type\": \"object\", ...}")
     *         public ToolResult search(Map<String, Object> args) {
     *             // ...
     *         }
     *     }
     *
     *     MyTools tools = new MyTools();
     *     SdkMcpServer server = SdkMcpServer.fromAnnotatedMethods("myserver", tools);
     * }</pre>
     *
     * @param name     the server name
     * @param instance the object containing annotated methods
     * @return a new server instance
     */
    public static SdkMcpServer fromAnnotatedMethods(String name, Object instance) {
        return fromAnnotatedMethods(name, VERSION, instance);
    }

    /**
     * Creates an SDK MCP server from methods annotated with {@link Tool}.
     *
     * @param name     the server name
     * @param version  the server version
     * @param instance the object containing annotated methods
     * @return a new server instance
     */
    public static SdkMcpServer fromAnnotatedMethods(String name, String version, Object instance) {
        List<SdkMcpTool<?>> tools = new ArrayList<>();

        for (Method method : instance.getClass().getDeclaredMethods()) {
            if (!method.isAnnotationPresent(Tool.class)) {
                continue;
            }

            @Nullable
            Tool toolAnnotation = method.getAnnotation(Tool.class);
            String toolName = toolAnnotation.name();
            String description = toolAnnotation.description();
            String schemaJson = toolAnnotation.inputSchema();

            // Parse input schema
            Map<String, Object> inputSchema;
            if (schemaJson.isEmpty()) {
                // Generate schema from method parameters if not provided
                inputSchema = generateSchemaFromMethod(method);
            } else {
                try {
                    inputSchema = MAPPER.readValue(schemaJson, new TypeReference<>() {
                    });
                } catch (Exception e) {
                    throw new IllegalArgumentException(
                            "Invalid JSON schema for tool '" + toolName + "': " + e.getMessage(), e);
                }
            }

            // Create tool that invokes the method
            method.setAccessible(true);
            SdkMcpTool<Map<String, Object>> tool = SdkMcpTool.create(
                    toolName, description, inputSchema,
                    args -> invokeToolMethod(instance, method, args));
            tools.add(tool);
        }

        if (tools.isEmpty()) {
            logger.warning("No @Tool annotated methods found in " + instance.getClass().getName());
        }

        return create(name, version, tools);
    }

    private static CompletableFuture<ToolResult> invokeToolMethod(
            Object instance, Method method, Map<String, Object> args) {
        try {
            Object[] orderedArgs = buildMethodArguments(method, args);
            Object result = method.invoke(instance, orderedArgs);

            if (result instanceof CompletableFuture<?> future) {
                return future.thenApply(r -> {
                    if (r instanceof ToolResult tr) {
                        return tr;
                    }
                    return ToolResult.text(String.valueOf(r));
                });
            } else if (result instanceof ToolResult tr) {
                return CompletableFuture.completedFuture(tr);
            } else {
                return CompletableFuture.completedFuture(ToolResult.text(String.valueOf(result)));
            }
        } catch (Exception e) {
            logger.log(Level.WARNING,
                    "Tool method invocation failed: %s#%s".formatted(instance.getClass().getName(), method.getName()),
                    e);
            Throwable cause = ((e.getCause() != null) ? e.getCause() : e);
            return CompletableFuture.completedFuture(ToolResult.error(cause.getMessage()));
        }
    }

    private static Object[] buildMethodArguments(Method method, Map<String, Object> args) {
        Parameter[] parameters = method.getParameters();
        if ((parameters.length == 1) &&
                Map.class.isAssignableFrom(parameters[0].getType())) {
            // Special case: method(Map<String,Object>)
            return new Object[] { args };
        }

        Object[] values = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            Parameter param = parameters[i];
            if (!param.isNamePresent()) {
                logger.warning("Parameter names not available for method " + method.getName()
                        + ". Compile with -parameters flag for enabling dynamic calling.");
                // Fall back to method(Map<String,Object>)
                return new Object[] { args };
            }

            String name = param.getName(); // requires -parameters
            Class<?> targetType = param.getType();

            Object rawValue = args.get(name);

            if (rawValue == null) {
                values[i] = null;
                continue;
            }

            // Convert into correct Java type
            Object converted = MAPPER.convertValue(rawValue, targetType);
            values[i] = converted;
        }

        return values;
    }

    /**
     * Generates MCP-compliant JSON Schema from method parameters.
     *
     * <p>
     * This method inspects the method parameters and generates a JSON Schema
     * object that complies with the MCP protocol specification (2020-12).
     *
     * <p>
     * If the method has a single parameter of type Map<String, Object>,
     * returns an empty object schema per MCP specification.
     *
     * <p>
     * Otherwise, generates schema with properties based on parameter types:
     * <ul>
     * <li>String → "string"</li>
     * <li>Integer types (int, Integer, long, Long, etc.) → "integer"</li>
     * <li>Floating point types (float, Float, double, Double, etc.) → "number"</li>
     * <li>Boolean types → "boolean"</li>
     * <li>Map → "object"</li>
     * <li>List, arrays → "array"</li>
     * </ul>
     *
     * @param method the method to generate schema for
     * @return MCP-compliant JSON Schema object
     */
    private static Map<String, Object> generateSchemaFromMethod(Method method) {
        Parameter[] parameters = method.getParameters();

        // If method accepts Map<String, Object> (standard pattern), use empty object
        // schema
        if ((parameters.length == 1) && Map.class.isAssignableFrom(parameters[0].getType())) {
            // MCP-compliant: type=object with empty properties
            return Map.of(
                    KEY_TYPE, KEY_OBJECT,
                    KEY_PROPERTIES, Map.of());
        }

        // Generate schema from typed parameters
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        for (Parameter param : parameters) {
            if (!param.isNamePresent()) {
                logger.warning("Parameter names not available for method " + method.getName()
                        + ". Compile with -parameters flag for automatic schema generation.");
                // Fall back to empty object schema
                return Map.of(
                        KEY_TYPE, KEY_OBJECT,
                        KEY_PROPERTIES, Map.of());
            }

            String paramName = param.getName();
            Class<?> paramType = param.getType();

            Map<String, Object> propertySchema = generatePropertySchema(paramType);
            properties.put(paramName, propertySchema);

            // All parameters are required by default
            required.add(paramName);
        }

        // Build MCP-compliant schema
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put(KEY_TYPE, KEY_OBJECT);
        schema.put(KEY_PROPERTIES, properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }

        return schema;
    }

    /**
     * Maps Java types to JSON Schema type definitions per MCP specification.
     *
     * @param javaType the Java class type
     * @return JSON Schema property definition
     */
    private static Map<String, Object> generatePropertySchema(Class<?> javaType) {
        // String types
        if ((javaType == String.class) || (javaType == CharSequence.class)) {
            return Map.of(KEY_TYPE, "string");
        }

        // Integer types
        if ((javaType == int.class) || (javaType == Integer.class)
                || (javaType == long.class) || (javaType == Long.class)
                || (javaType == short.class) || (javaType == Short.class)
                || (javaType == byte.class) || (javaType == Byte.class)
                || (javaType == BigInteger.class)) {
            return Map.of(KEY_TYPE, "integer");
        }

        // Number types (floating point)
        if ((javaType == double.class) || (javaType == Double.class)
                || (javaType == float.class) || (javaType == Float.class)
                || (javaType == BigDecimal.class)
                || (Number.class.isAssignableFrom(javaType))) {
            return Map.of(KEY_TYPE, "number");
        }

        // Boolean types
        if ((javaType == boolean.class) || (javaType == Boolean.class)) {
            return Map.of(KEY_TYPE, "boolean");
        }

        // Array types
        if (javaType.isArray() || Collection.class.isAssignableFrom(javaType)) {
            return Map.of(KEY_TYPE, "array", "items", Map.of("type", "object"));
        }

        // Object types
        if (Map.class.isAssignableFrom(javaType)) {
            return Map.of(KEY_TYPE, KEY_OBJECT, "additionalProperties", true);
        }

        // Default to object for complex types
        logger.warning("Unknown parameter type: " + javaType.getName() + ", defaulting to 'object'");
        return Map.of(KEY_TYPE, KEY_OBJECT);
    }

    // Getters

    public String name() {
        return name;
    }

    public String version() {
        return version;
    }

    /**
     * Creates a configuration object for use with ClaudeAgentOptions.
     *
     * @return an McpSdkServerConfig
     */
    public McpSdkServerConfig toConfig() {
        return new McpSdkServerConfig(name, this);
    }

    /**
     * Handles an MCP JSON-RPC message and returns the response.
     *
     * <p>
     * This method is called by the QueryHandler to route MCP messages
     * to this server.
     *
     * @param message the JSON-RPC message
     * @return a future with the response message
     */
    public CompletableFuture<Map<String, Object>> handleMessage(Map<String, Object> message) {
        String method = (String) message.get(KEY_METHOD);
        Object id = message.get(KEY_ID);
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) message.getOrDefault(KEY_PARAMS, Map.of());

        return switch (method) {
            case METHOD_INITIALIZE -> handleInitialize(id);
            case METHOD_LIST_TOOLS -> handleListTools(id);
            case METHOD_CALL_TOOL -> handleCallTool(id, params);
            case METHOD_INITIALIZED -> CompletableFuture.completedFuture(
                    Map.of(KEY_JSONRPC, JSONRPC_VERSION, KEY_RESULT, Map.of()));
            default -> CompletableFuture
                    .completedFuture(errorResponse(id, ERROR_CODE_METHOD_NOT_FOUND, "Method not found: " + method));
        };
    }

    private CompletableFuture<Map<String, Object>> handleInitialize(Object id) {
        Map<String, Object> result = new HashMap<>();
        result.put(KEY_PROTOCOL_VERSION, PROTOCOL_VERSION);
        result.put(KEY_CAPABILITIES, Map.of(KEY_TOOLS, Map.of()));
        result.put(KEY_SERVER_INFO, Map.of(KEY_NAME, name, KEY_VERSION, version));

        return CompletableFuture.completedFuture(successResponse(id, result));
    }

    private CompletableFuture<Map<String, Object>> handleListTools(Object id) {
        List<Map<String, Object>> toolList = tools.values().stream()
                .map(tool -> {
                    Map<String, Object> toolInfo = new HashMap<>();
                    toolInfo.put(KEY_NAME, tool.name());
                    toolInfo.put(KEY_DESCRIPTION, tool.description());
                    toolInfo.put(KEY_INPUT_SCHEMA, tool.inputSchema());
                    return toolInfo;
                })
                .toList();

        return CompletableFuture.completedFuture(
                successResponse(id, Map.of(KEY_TOOLS, toolList)));
    }

    @SuppressWarnings("unchecked")
    private CompletableFuture<Map<String, Object>> handleCallTool(Object id, Map<String, Object> params) {
        String toolName = (String) params.get(KEY_NAME);
        Map<String, Object> arguments = (Map<String, Object>) params.getOrDefault(KEY_ARGUMENTS, Map.of());

        SdkMcpTool<?> tool = tools.get(toolName);
        if (tool == null) {
            return CompletableFuture.completedFuture(
                    errorResponse(id, ERROR_CODE_METHOD_NOT_FOUND, "Tool not found: " + toolName));
        }

        try {
            // Invoke the tool handler
            @SuppressWarnings({ "rawtypes" })
            SdkMcpTool rawTool = tool;
            CompletableFuture<ToolResult> future = rawTool.invoke(arguments);
            return future
                    .thenApply(result -> {
                        Map<String, Object> responseData = result.toMap();
                        return successResponse(id, responseData);
                    })
                    .exceptionally(ex -> {
                        Throwable cause = ((ex.getCause() != null) ? ex.getCause() : ex);
                        logger.log(Level.WARNING, "Tool execution failed: " + toolName, cause);
                        return errorResponse(id, ERROR_CODE_INTERNAL_ERROR,
                                "Tool execution failed: " + cause.getMessage());
                    });
        } catch (Exception e) {
            logger.log(Level.WARNING, "Tool invocation failed: " + toolName, e);
            return CompletableFuture.completedFuture(
                    errorResponse(id, ERROR_CODE_INTERNAL_ERROR, "Tool invocation failed: " + e.getMessage()));
        }
    }

    private static Map<String, Object> successResponse(Object id, Map<String, Object> result) {
        Map<String, Object> response = new HashMap<>();
        response.put(KEY_JSONRPC, JSONRPC_VERSION);
        response.put(KEY_ID, id);
        response.put(KEY_RESULT, result);
        return response;
    }

    private static Map<String, Object> errorResponse(Object id, int code, String message) {
        Map<String, Object> response = new HashMap<>();
        response.put(KEY_JSONRPC, JSONRPC_VERSION);
        response.put(KEY_ID, id);
        response.put(KEY_ERROR, Map.of(KEY_CODE, code, KEY_MESSAGE, message));
        return response;
    }

}
