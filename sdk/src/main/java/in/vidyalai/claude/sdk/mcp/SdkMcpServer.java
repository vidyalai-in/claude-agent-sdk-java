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
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;

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
public final class SdkMcpServer implements McpMessageHandler {

    private static final Logger logger = Logger.getLogger(SdkMcpServer.class.getName());
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = McpJson.MAPPER;

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

    /**
     * The MCP protocol versions this server actually implements, newest first.
     *
     * <p>
     * {@code 2025-06-18} is claimed because every delta it brings for a
     * tools-only server is honored here: batching was <i>removed</i>, a tool's
     * {@code title} moved to the top level (emitted below), {@code _meta} on a
     * tool is already sent, and {@code outputSchema} is optional.
     *
     * <p>
     * {@code 2025-03-26} is deliberately absent. It made JSON-RPC batching
     * mandatory to receive, and a batch is a top-level <i>array</i> — which
     * {@code SDKControlMcpMessageRequest.message} cannot even represent, being
     * typed as a map. Claiming a version whose one required change we could
     * not honor would be a lie the client acts on.
     */
    private static final List<String> SUPPORTED_PROTOCOL_VERSIONS =
            List.of("2025-06-18", "2024-11-05");

    private static final String LATEST_PROTOCOL_VERSION = SUPPORTED_PROTOCOL_VERSIONS.get(0);
    private static final String KEY_CAPABILITIES = "capabilities";
    private static final String KEY_TOOLS = "tools";
    private static final String KEY_SERVER_INFO = "serverInfo";
    private static final String KEY_NAME = "name";
    private static final String KEY_VERSION = "version";
    private static final String VERSION = "1.0.0";
    private static final String KEY_DESCRIPTION = "description";
    private static final String KEY_INPUT_SCHEMA = "inputSchema";
    private static final String KEY_ARGUMENTS = "arguments";
    private static final String KEY_ANNOTATIONS = "annotations";
    private static final String KEY_TITLE = "title";
    private static final String KEY_REQUEST_ID = "requestId";

    // Schema keys
    private static final String KEY_TYPE = "type";
    private static final String KEY_OBJECT = "object";
    private static final String KEY_PROPERTIES = "properties";

    // MCP method names
    private static final String METHOD_INITIALIZE = "initialize";
    private static final String METHOD_LIST_TOOLS = "tools/list";
    private static final String METHOD_CALL_TOOL = "tools/call";
    private static final String METHOD_INITIALIZED = "notifications/initialized";
    private static final String METHOD_CANCELLED = "notifications/cancelled";
    private static final String METHOD_PING = "ping";

    // JSON-RPC error codes
    private static final int ERROR_CODE_METHOD_NOT_FOUND = -32601;
    private static final int ERROR_CODE_INVALID_PARAMS = -32602;
    private static final int ERROR_CODE_INTERNAL_ERROR = -32603;

    /**
     * "Request cancelled" — what a request settled by
     * {@code notifications/cancelled} is answered with, matching the Python
     * SDK and mcp's own HTTP transport.
     */
    private static final int ERROR_CODE_REQUEST_CANCELLED = -32800;

    // JSON-RPC version
    private static final String JSONRPC_VERSION = "2.0";

    /**
     * MCP declares tool input schemas as JSON Schema. Absent a {@code $schema}
     * keyword, 2020-12 is the dialect the specification is written against.
     */
    private static final SpecificationVersion DEFAULT_DIALECT = SpecificationVersion.DRAFT_2020_12;

    private final String name;
    private final String version;
    private final Map<String, SdkMcpTool<?>> tools;

    /**
     * Compiled {@code inputSchema} per tool name, used to validate arguments
     * before a handler runs. A tool whose schema could not be compiled is
     * absent here and is simply not validated — a schema this server cannot
     * understand must not make an otherwise working tool uncallable.
     */
    private final Map<String, Schema> inputSchemas;

    /**
     * Why a tool's {@code inputSchema} could not be compiled, per tool name.
     *
     * <p>
     * A tool listed here cannot be called: its published contract is one this
     * server cannot check, so running the handler would hand it arguments
     * nobody validated.
     */
    private final Map<String, String> schemaFailures;

    /**
     * Tool calls the CLI has not been answered about yet, keyed by JSON-RPC
     * request id, so {@code notifications/cancelled} can find one.
     */
    private final Map<String, InFlight> inFlight = new ConcurrentHashMap<>();

    private SdkMcpServer(String name, String version, List<SdkMcpTool<?>> tools) {
        this.name = name;
        this.version = version;
        this.tools = new ConcurrentHashMap<>();
        for (SdkMcpTool<?> tool : tools) {
            this.tools.put(tool.name(), tool);
        }
        Map<String, Schema> compiled = new HashMap<>();
        Map<String, String> failures = new HashMap<>();
        compileInputSchemas(this.tools.values(), compiled, failures);
        this.inputSchemas = Map.copyOf(compiled);
        this.schemaFailures = Map.copyOf(failures);
    }

    /** One tool call awaiting its answer. */
    private record InFlight(
            Object id,
            String toolName,
            CallContext context,
            CompletableFuture<@Nullable Map<String, Object>> response) {
    }

    private static void compileInputSchemas(
            Collection<SdkMcpTool<?>> tools,
            Map<String, Schema> compiled,
            Map<String, String> failures) {
        Map<SpecificationVersion, SchemaRegistry> registries = new HashMap<>();
        for (SdkMcpTool<?> tool : tools) {
            Map<String, Object> schema = tool.inputSchema();
            if ((schema == null) || schema.isEmpty()) {
                continue;
            }
            try {
                JsonNode node = MAPPER.valueToTree(schema);
                SpecificationVersion dialect =
                        SpecificationVersion.fromSchemaNode(node).orElse(DEFAULT_DIALECT);
                SchemaRegistry registry = registries.computeIfAbsent(
                        dialect, SchemaRegistry::withDefaultDialect);

                String invalid = describeSchemaErrors(registry, dialect, node);
                if (invalid != null) {
                    logger.warning(() -> "Tool '" + tool.name() + "' declares an inputSchema that is "
                            + "not valid JSON Schema, so it cannot be called: " + invalid);
                    failures.put(tool.name(), invalid);
                    continue;
                }

                compiled.put(tool.name(), registry.getSchema(node));
            } catch (RuntimeException e) {
                logger.log(Level.WARNING,
                        "Tool '" + tool.name() + "' has an inputSchema this server cannot compile, "
                                + "so it cannot be called: " + e.getMessage(),
                        e);
                failures.put(tool.name(), describeFailure(e));
            }
        }
    }

    /**
     * Checks a declared {@code inputSchema} against its own dialect's
     * meta-schema.
     *
     * <p>
     * The validator compiles a malformed schema without complaint and then
     * mis-validates against it — {@code "type": "bogus"} matches nothing, and
     * {@code "properties": "a string"} is ignored outright, so every call
     * either fails for the wrong reason or is waved through unchecked. Python
     * catches these because {@code jsonschema} checks the schema; this is
     * where Java catches them.
     *
     * @return what is wrong with the schema, or null when it is valid
     */
    @Nullable
    private static String describeSchemaErrors(
            SchemaRegistry registry, SpecificationVersion dialect, JsonNode schema) {
        List<Error> errors = registry.getSchema(SchemaLocation.of(dialect.getDialectId())).validate(schema);
        if ((errors == null) || errors.isEmpty()) {
            return null;
        }
        return joinDistinct(errors);
    }

    /**
     * Validates {@code arguments} against a tool's declared {@code inputSchema}.
     *
     * @return the error text to report, or null when the arguments are valid
     *         (or the tool has no usable schema)
     */
    @Nullable
    private String validateArguments(String toolName, Map<String, Object> arguments) {
        String failure = schemaFailures.get(toolName);
        if (failure != null) {
            // Fail closed, as the Python SDK does. A tool whose published
            // contract this server cannot read cannot be called safely: the
            // handler would receive arguments nobody checked. Deliberately
            // *not* prefixed "Input validation error" — a broken schema is a
            // server defect the model cannot route around, and telling it the
            // arguments were wrong invites an endless retry.
            return "Tool '" + toolName + "' has an inputSchema this server cannot use, "
                    + "so it cannot be called: " + failure;
        }
        Schema schema = inputSchemas.get(toolName);
        if (schema == null) {
            return null;
        }
        List<Error> errors;
        try {
            errors = schema.validate(MAPPER.valueToTree(arguments));
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Could not validate arguments for tool '" + toolName + "'", e);
            return "Tool '" + toolName + "' could not validate its arguments: " + describeFailure(e);
        }
        if ((errors == null) || errors.isEmpty()) {
            return null;
        }
        return joinDistinct(errors);
    }

    /**
     * The errors as one sentence, de-duplicated and sorted so the same bad
     * input always reports the same way.
     */
    private static String joinDistinct(List<Error> errors) {
        List<String> described = new ArrayList<>();
        for (Error error : errors) {
            String text = describe(error);
            if (!described.contains(text)) {
                described.add(text);
            }
        }
        described.sort(null);
        return String.join("; ", described);
    }

    private static String describe(Error error) {
        String location = String.valueOf(error.getInstanceLocation());
        String message = error.getMessage();
        return (location.isEmpty() || "$".equals(location))
                ? message
                : location + " " + message;
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
            String title = toolAnnotation.title();
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

            // Instantiate ToolAnnotations from the class referenced in @Tool
            ToolAnnotations annotations = null;
            Class<? extends ToolAnnotations> annotationsClass = toolAnnotation.annotations();
            try {
                annotations = annotationsClass.getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                throw new IllegalArgumentException(
                        "Cannot instantiate ToolAnnotations class '" + annotationsClass.getName()
                                + "'. Ensure it has a public no-arg constructor.",
                        e);
            }

            // Create tool that invokes the method
            method.setAccessible(true);
            SdkMcpTool<Map<String, Object>> tool = SdkMcpTool.create(
                    toolName, description, title, inputSchema,
                    (args, context) -> invokeToolMethod(instance, method, args, context),
                    annotations);
            tools.add(tool);
        }

        if (tools.isEmpty()) {
            logger.warning("No @Tool annotated methods found in " + instance.getClass().getName());
        }

        return create(name, version, tools);
    }

    private static CompletableFuture<ToolResult> invokeToolMethod(
            Object instance, Method method, Map<String, Object> args, ToolCallContext context) {
        try {
            Object[] orderedArgs = buildMethodArguments(method, args, context);
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

    /**
     * Whether this parameter is the injected {@link ToolCallContext} rather
     * than a tool argument.
     */
    private static boolean isContextParameter(Parameter parameter) {
        return ToolCallContext.class.isAssignableFrom(parameter.getType());
    }

    /** The parameters that come from the caller's arguments, context aside. */
    private static List<Parameter> declaredParameters(Parameter[] parameters) {
        List<Parameter> declared = new ArrayList<>();
        for (Parameter parameter : parameters) {
            if (!isContextParameter(parameter)) {
                declared.add(parameter);
            }
        }
        return declared;
    }

    private static Object[] buildMethodArguments(
            Method method, Map<String, Object> args, ToolCallContext context) {
        Parameter[] parameters = method.getParameters();
        List<Parameter> declared = declaredParameters(parameters);
        Object[] values = new Object[parameters.length];

        if ((declared.size() == 1) && Map.class.isAssignableFrom(declared.get(0).getType())) {
            // Special case: method(Map<String,Object>), with or without a
            // ToolCallContext alongside it.
            for (int i = 0; i < parameters.length; i++) {
                values[i] = (isContextParameter(parameters[i]) ? context : args);
            }
            return values;
        }

        for (int i = 0; i < parameters.length; i++) {
            Parameter param = parameters[i];
            if (isContextParameter(param)) {
                values[i] = context;
                continue;
            }
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
        // A ToolCallContext parameter is injected by the server, not supplied
        // by the caller, so it never appears in the published schema.
        List<Parameter> parameters = declaredParameters(method.getParameters());

        // If method accepts Map<String, Object> (standard pattern), use empty object
        // schema
        if ((parameters.size() == 1) && Map.class.isAssignableFrom(parameters.get(0).getType())) {
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
     * Handles one MCP JSON-RPC message and returns the response, if one is
     * due.
     *
     * <p>
     * JSON-RPC has three shapes and this tells them apart before doing
     * anything else. A message with a {@code method} and an {@code id} is a
     * <b>request</b> and is answered. A message with a {@code method} and no
     * {@code id} is a <b>notification</b> and must never be answered — the
     * future completes with null, and the caller acknowledges the control
     * request that carried it. A message with no {@code method} is a
     * <b>response</b>, or junk; this server sends the CLI no requests, so
     * nothing here is its to match, and it is ignored rather than answered.
     *
     * @param message the JSON-RPC message
     * @return a future with the response, or with null when no reply is due
     */
    @Override
    public CompletableFuture<@Nullable Map<String, Object>> handleMessage(Map<String, Object> message) {
        Object id = message.get(KEY_ID);
        try {
            if (!(message.get(KEY_METHOD) instanceof String method)) {
                logger.fine(() -> "Ignoring a message with no method for SDK MCP server '"
                        + name + "': " + message.keySet());
                return completed(null);
            }

            // The MCP specification forbids a null request id, so "no id" and
            // "id: null" both mean notification.
            if (id == null) {
                handleNotification(method, asMap(message.get(KEY_PARAMS)));
                return completed(null);
            }

            return switch (method) {
                case METHOD_INITIALIZE -> handleInitialize(id, asMap(message.get(KEY_PARAMS)));
                case METHOD_PING -> completed(successResponse(id, Map.of()));
                case METHOD_LIST_TOOLS -> handleListTools(id);
                case METHOD_CALL_TOOL -> handleCallTool(id, message.get(KEY_PARAMS));
                // Everything else, resources and prompts included. That is the
                // right answer, not an omission: this server advertises only
                // the "tools" capability, so a spec-conformant client never
                // asks for the rest.
                default -> completed(errorResponse(
                        id, ERROR_CODE_METHOD_NOT_FOUND, "Method not found: " + method));
            };
        } catch (RuntimeException e) {
            // Nothing malformed the CLI sends may escape this method
            // synchronously; the caller has no id to answer with once it does.
            logger.log(Level.WARNING,
                    "SDK MCP server '" + name + "' could not dispatch a message", e);
            return completed(errorResponse(id, ERROR_CODE_INTERNAL_ERROR, describeFailure(e)));
        }
    }

    private void handleNotification(String method, @Nullable Map<String, Object> params) {
        switch (method) {
            case METHOD_INITIALIZED ->
                logger.fine(() -> "SDK MCP server '" + name + "' initialized");
            case METHOD_CANCELLED -> handleCancelled(params);
            default -> logger.fine(() -> "Dropping a " + method
                    + " notification for SDK MCP server '" + name + "': not supported");
        }
    }

    /**
     * Settles the call the CLI has given up on.
     *
     * <p>
     * The pending request is answered {@code -32800}, and the handler's
     * {@link ToolCallContext} is cancelled so a tool that is watching can
     * stop. Nothing is sent in reply to the notification itself.
     */
    private void handleCancelled(@Nullable Map<String, Object> params) {
        Object requestId = ((params != null) ? params.get(KEY_REQUEST_ID) : null);
        if (requestId == null) {
            logger.fine("Ignoring a notifications/cancelled that names no requestId");
            return;
        }
        InFlight entry = inFlight.remove(idKey(requestId));
        if (entry == null) {
            // Already finished, or never ours. Either way a notification is
            // never answered, not even to say so.
            logger.fine(() -> "No in-flight request " + requestId + " to cancel on SDK MCP server '"
                    + name + "'");
            return;
        }
        Object reason = ((params != null) ? params.get("reason") : null);
        logger.fine(() -> "Cancelling " + entry.toolName() + " (request " + requestId + "): " + reason);
        cancel(entry);
    }

    /** Cancels one call: signal the handler first, then settle the request. */
    private static void cancel(InFlight entry) {
        entry.context().cancel();
        entry.response().complete(errorResponse(
                entry.id(), ERROR_CODE_REQUEST_CANCELLED, "Request cancelled"));
    }

    /**
     * Abandons the calls still in flight for a connection that is going away.
     *
     * <p>
     * Not a permanent shutdown: one server can be registered with more than
     * one client, and stays usable for the next one. Idempotent.
     */
    @Override
    public void close() {
        for (InFlight entry : List.copyOf(inFlight.values())) {
            if (inFlight.remove(idKey(entry.id()), entry)) {
                cancel(entry);
            }
        }
    }

    private CompletableFuture<@Nullable Map<String, Object>> handleInitialize(
            Object id, @Nullable Map<String, Object> params) {
        String requested = ((params != null) && (params.get(KEY_PROTOCOL_VERSION) instanceof String v))
                ? v
                : null;
        // The specification's rule: echo the client's version when we speak
        // it, otherwise answer with the newest we do and let the client decide
        // whether to go on.
        String negotiated = ((requested != null) && SUPPORTED_PROTOCOL_VERSIONS.contains(requested))
                ? requested
                : LATEST_PROTOCOL_VERSION;
        if ((requested != null) && !requested.equals(negotiated)) {
            logger.fine(() -> "Client asked for MCP protocol " + requested
                    + "; offering " + negotiated + " instead");
        }

        Map<String, Object> result = new HashMap<>();
        result.put(KEY_PROTOCOL_VERSION, negotiated);
        result.put(KEY_CAPABILITIES, Map.of(KEY_TOOLS, Map.of()));
        result.put(KEY_SERVER_INFO, Map.of(KEY_NAME, name, KEY_VERSION, version));

        return completed(successResponse(id, result));
    }

    private CompletableFuture<@Nullable Map<String, Object>> handleListTools(Object id) {
        List<Map<String, Object>> toolList = tools.values().stream()
                .map(SdkMcpServer::describeTool)
                .toList();

        return completed(successResponse(id, Map.of(KEY_TOOLS, toolList)));
    }

    private static Map<String, Object> describeTool(SdkMcpTool<?> tool) {
        Map<String, Object> toolInfo = new HashMap<>();
        toolInfo.put(KEY_NAME, tool.name());
        toolInfo.put(KEY_DESCRIPTION, tool.description());
        toolInfo.put(KEY_INPUT_SCHEMA, tool.inputSchema());

        // A tool may carry a title without carrying any hint, so ask even when
        // no annotations were declared -- reading this off tool.annotations()
        // alone is what used to drop such a title on the floor.
        ToolAnnotations declared = tool.annotations();
        Map<String, Object> annotationsMap =
                ((declared != null) ? declared : ToolAnnotations.NONE).toMap(tool.title());
        if (annotationsMap != null) {
            toolInfo.put(KEY_ANNOTATIONS, annotationsMap);
        }

        // MCP 2025-06-18 promotes title to the top level, where it takes
        // precedence over annotations.title. Sent to every client: one that
        // predates the field strips what it does not know.
        String title = tool.title();
        if ((title != null) && !title.isBlank()) {
            toolInfo.put(KEY_TITLE, title.trim());
        }

        if (declared != null) {
            // The MCP SDK's Zod schema strips unknown annotation fields, so
            // Anthropic-specific hints use _meta with namespaced keys instead.
            // maxResultSizeChars controls the CLI's layer-2 tool-result spill
            // threshold (toolResultStorage.ts maybePersistLargeToolResult).
            Map<String, Object> meta = buildMeta(declared);
            if (meta != null) {
                toolInfo.put("_meta", meta);
            }
        }

        return toolInfo;
    }

    @Nullable
    private static Map<String, Object> buildMeta(ToolAnnotations annotations) {
        Integer maxResultSize = annotations.maxResultSizeChars();
        if (maxResultSize == null) {
            return null;
        }
        return Map.of("anthropic/maxResultSizeChars", maxResultSize);
    }

    private CompletableFuture<@Nullable Map<String, Object>> handleCallTool(Object id, @Nullable Object rawParams) {
        Map<String, Object> params = asMap(rawParams);
        if (params == null) {
            return completed(errorResponse(
                    id, ERROR_CODE_INVALID_PARAMS, "Invalid params: expected an object"));
        }
        if (!(params.get(KEY_NAME) instanceof String toolName)) {
            return completed(errorResponse(id, ERROR_CODE_INVALID_PARAMS,
                    "Invalid params: 'name' is required and must be a string"));
        }
        // Absent, explicitly null, and "not an object" all mean no arguments.
        Map<String, Object> arguments = asMap(params.get(KEY_ARGUMENTS));
        if (arguments == null) {
            arguments = Map.of();
        }

        SdkMcpTool<?> tool = tools.get(toolName);
        if (tool == null) {
            // A tool-execution error, not a protocol one, matching the Python
            // SDK. The model reads an isError result and can correct itself; a
            // JSON-RPC error it never sees.
            return completed(successResponse(
                    id, toolErrorResult("Tool '" + toolName + "' not found")));
        }

        // "Servers MUST validate all tool inputs" (MCP spec, Security
        // Considerations). Reported as a tool-execution error so the text
        // reaches the model as tool output it can act on -- an unreadable JVM
        // ClassCastException from a handler that was handed arguments it never
        // agreed to accept helps nobody, and running the handler at all risks
        // half-applying a side effect before it throws.
        String validationError = validateArguments(toolName, arguments);
        if (validationError != null) {
            String text = (schemaFailures.containsKey(toolName)
                    ? validationError
                    : "Input validation error: " + validationError);
            return completed(successResponse(id, toolErrorResult(text)));
        }

        return invokeTool(id, toolName, tool, arguments);
    }

    private CompletableFuture<@Nullable Map<String, Object>> invokeTool(
            Object id, String toolName, SdkMcpTool<?> tool, Map<String, Object> arguments) {

        CallContext context = new CallContext();
        CompletableFuture<@Nullable Map<String, Object>> response = new CompletableFuture<>();
        InFlight entry = new InFlight(id, toolName, context, response);
        String key = idKey(id);

        if (inFlight.putIfAbsent(key, entry) != null) {
            // Ids are unique among a session's unanswered requests, so this
            // means two sessions share one server. Refusing is what the Python
            // SDK does, and it beats delivering a response to the wrong caller.
            logger.warning(() -> "SDK MCP server '" + name + "' already has request id " + id
                    + " in flight (is the same server registered under two names?)");
            return completed(errorResponse(id, ERROR_CODE_INTERNAL_ERROR,
                    "Request id " + id + " is already in flight"));
        }

        try {
            @SuppressWarnings("rawtypes")
            SdkMcpTool rawTool = tool;
            @SuppressWarnings("unchecked")
            CompletableFuture<ToolResult> result = rawTool.invoke(arguments, context);
            result.whenComplete((toolResult, ex) -> {
                inFlight.remove(key, entry);
                Map<String, Object> settled = ((ex != null)
                        ? successResponse(id, toolErrorResult(describeFailure(unwrap(ex))))
                        : successResponse(id, toolResult.toMap()));
                // False means the call was already answered -- cancelled. The
                // late result is dropped, which is the whole contract.
                if (!response.complete(settled)) {
                    logger.fine(() -> "Discarding the late result of cancelled tool " + toolName);
                } else if (ex != null) {
                    logger.log(Level.WARNING, "Tool execution failed: " + toolName, unwrap(ex));
                }
            });
        } catch (RuntimeException e) {
            // The handler threw instead of returning a future.
            inFlight.remove(key, entry);
            logger.log(Level.WARNING, "Tool invocation failed: " + toolName, e);
            response.complete(successResponse(id, toolErrorResult(describeFailure(e))));
        }

        return response;
    }

    private static Throwable unwrap(Throwable ex) {
        return ((ex.getCause() != null) ? ex.getCause() : ex);
    }

    /**
     * A {@code tools/call} result carrying {@code isError: true}.
     *
     * <p>
     * A tool that fails while running has produced a result, not a broken
     * request: the MCP specification reports those "in tool results with
     * {@code isError: true}" so the model can see what went wrong and adapt.
     * A JSON-RPC error would instead say the call could not be processed.
     */
    private static Map<String, Object> toolErrorResult(String message) {
        return ToolResult.builder().addText(message).isError(true).build().toMap();
    }

    /** Exception text for a model to read; never null, never empty. */
    private static String describeFailure(Throwable cause) {
        String message = cause.getMessage();
        return ((message != null) && !message.isBlank())
                ? message
                : cause.getClass().getSimpleName();
    }

    private static CompletableFuture<@Nullable Map<String, Object>> completed(
            @Nullable Map<String, Object> response) {
        return CompletableFuture.completedFuture(response);
    }

    /** The value as a string-keyed map, or null when it is not an object. */
    @Nullable
    private static Map<String, Object> asMap(@Nullable Object value) {
        if (value instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            return typed;
        }
        return null;
    }

    /**
     * A stable key for a JSON-RPC id.
     *
     * <p>
     * The id in a {@code tools/call} and the {@code requestId} in the
     * {@code notifications/cancelled} that settles it must land on the same
     * entry, and JSON gives no guarantee the two decode to the same numeric
     * box — an {@code Integer} one time, a {@code Long} the next.
     */
    private static String idKey(Object id) {
        if (id instanceof Number number) {
            double value = number.doubleValue();
            return ((value == Math.rint(value)) ? ("n" + number.longValue()) : ("n" + number));
        }
        return "s" + id;
    }

    /** The {@link ToolCallContext} handed to one running tool. */
    private static final class CallContext implements ToolCallContext {

        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final Queue<Runnable> listeners = new ConcurrentLinkedQueue<>();

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }

        @Override
        public void onCancel(Runnable callback) {
            listeners.add(callback);
            if (cancelled.get()) {
                // Registered after the fact, or raced with the cancellation.
                runListeners();
            }
        }

        void cancel() {
            if (cancelled.compareAndSet(false, true)) {
                runListeners();
            }
        }

        /**
         * Runs and removes every queued listener, so one can never run twice
         * however the registration and the cancellation interleave.
         */
        private void runListeners() {
            for (Runnable listener = listeners.poll();
                    listener != null;
                    listener = listeners.poll()) {
                try {
                    listener.run();
                } catch (RuntimeException e) {
                    logger.log(Level.WARNING, "A tool cancellation listener failed", e);
                }
            }
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
