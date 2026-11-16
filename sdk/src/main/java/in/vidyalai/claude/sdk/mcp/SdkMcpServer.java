package in.vidyalai.claude.sdk.mcp;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CancellationException;
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
     * {@code 2025-11-25} is claimed because nothing it adds is mandatory for a
     * tools-only server: icons, tasks, URL elicitation and sampling with tools
     * are optional or capability-gated; input validation failures are
     * already reported as tool execution errors (SEP-1303); and JSON Schema
     * 2020-12, now the default dialect (SEP-1613), is already the dialect
     * arguments are validated against.
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
            List.of("2025-11-25", "2025-06-18", "2024-11-05");

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

    /** The tools by name, in registration order (which is the listing order). */
    private final Map<String, SdkMcpTool<?>> tools;

    /**
     * The {@code inputSchema} each tool is published and validated with: what
     * it declared when that is a full JSON Schema, or the expansion of the
     * {@code {name: type}} shorthand (see {@link SdkMcpTool#inputSchema()}).
     */
    private final Map<String, Map<String, Object>> wireSchemas;

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
     * The session {@link #handleMessage} and {@link #close()} act on when the
     * server is called directly rather than through {@link #openSession()}.
     */
    private final Session defaultSession = new Session();

    private SdkMcpServer(String name, String version, List<SdkMcpTool<?>> tools) {
        this.name = name;
        this.version = version;
        Map<String, SdkMcpTool<?>> byName = new LinkedHashMap<>();
        Map<String, Map<String, Object>> schemas = new LinkedHashMap<>();
        for (SdkMcpTool<?> tool : tools) {
            byName.put(tool.name(), tool);
            schemas.put(tool.name(), ToolSchemas.wireSchema(tool.name(), tool.inputSchema()));
        }
        this.tools = Collections.unmodifiableMap(byName);
        this.wireSchemas = Collections.unmodifiableMap(schemas);
        Map<String, Schema> compiled = new HashMap<>();
        Map<String, String> failures = new HashMap<>();
        compileInputSchemas(this.wireSchemas, compiled, failures);
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
            Map<String, Map<String, Object>> schemas,
            Map<String, Schema> compiled,
            Map<String, String> failures) {
        Map<SpecificationVersion, SchemaRegistry> registries = new HashMap<>();
        for (Map.Entry<String, Map<String, Object>> toolSchema : schemas.entrySet()) {
            String toolName = toolSchema.getKey();
            Map<String, Object> schema = toolSchema.getValue();
            if (schema.isEmpty()) {
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
                    logger.warning(() -> "Tool '" + toolName + "' declares an inputSchema that is "
                            + "not valid JSON Schema, so it cannot be called: " + invalid);
                    failures.put(toolName, invalid);
                    continue;
                }

                compiled.put(toolName, registry.getSchema(node));
            } catch (RuntimeException e) {
                logger.log(Level.WARNING,
                        "Tool '" + toolName + "' has an inputSchema this server cannot compile, "
                                + "so it cannot be called: " + e.getMessage(),
                        e);
                failures.put(toolName, ToolSchemas.describeFailure(e));
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
            return "Tool '" + toolName + "' could not validate its arguments: " + ToolSchemas.describeFailure(e);
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
     * <li>Parameter types are mapped to JSON Schema from their generic type:
     * {@code String} and {@code char} to strings, the integer and floating
     * point types to {@code integer} and {@code number}, {@code boolean},
     * an enum to a string limited to its constants, {@code List<T>},
     * {@code Set<T>} and arrays to an array of {@code T}, {@code Map<String, V>}
     * to an object of {@code V}, a record to a nested object, and value types
     * such as {@code UUID}, {@code URI}, {@code Path} and the
     * {@code java.time} types to strings</li>
     * <li>Every parameter is required, except an {@code Optional} one or one
     * marked {@link ToolParam#required() @ToolParam(required = false)}</li>
     * <li>{@link ToolParam#description() @ToolParam(description = ...)} puts
     * a description on the property</li>
     * </ul>
     * Arguments are validated against that schema and then converted into the
     * declared parameter types before the method runs.
     *
     * <p>
     * <b>Note:</b> parameter names come from the {@code -parameters} compiler
     * flag, or from {@link ToolParam#name()}.
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
            ToolSchemas.MethodBinding binding = ToolSchemas.bindMethod(method);
            Map<String, Object> inputSchema;
            if (schemaJson.isEmpty()) {
                // Generate schema from method parameters if not provided
                inputSchema = binding.schema();
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
                    (args, context) -> invokeToolMethod(instance, method, binding.arguments(), args, context),
                    annotations);
            tools.add(tool);
        }

        if (tools.isEmpty()) {
            logger.warning("No @Tool annotated methods found in " + instance.getClass().getName());
        }

        return create(name, version, tools);
    }

    private static CompletableFuture<ToolResult> invokeToolMethod(
            Object instance, Method method, ToolSchemas.ArgumentBuilder arguments,
            Map<String, Object> args, ToolCallContext context) {
        try {
            Object result = method.invoke(instance, arguments.build(args, context));

            // A null result from a non-void method (or a future completing
            // with null) is answered as an isError "no result", as the Python
            // SDK answers a handler returning None. A void method keeps its
            // historical text result.
            boolean isVoid = (method.getReturnType() == void.class) || (method.getReturnType() == Void.class);
            boolean isVoidFuture = (method.getGenericReturnType() instanceof java.lang.reflect.ParameterizedType pt)
                    && (pt.getActualTypeArguments().length == 1) && (pt.getActualTypeArguments()[0] == Void.class);
            if (result instanceof CompletableFuture<?> future) {
                return future.thenApply(r -> {
                    if (r instanceof ToolResult tr) {
                        return tr;
                    }
                    if (r == null) {
                        // CompletableFuture<Void> is the async void: success.
                        return isVoidFuture ? ToolResult.text(String.valueOf((Object) null)) : null;
                    }
                    return ToolResult.text(String.valueOf(r));
                });
            } else if (result instanceof ToolResult tr) {
                return CompletableFuture.completedFuture(tr);
            } else if ((result == null) && !isVoid) {
                return CompletableFuture.completedFuture(null);
            } else {
                return CompletableFuture.completedFuture(ToolResult.text(String.valueOf(result)));
            }
        } catch (Exception e) {
            logger.log(Level.WARNING,
                    "Tool method invocation failed: %s#%s".formatted(instance.getClass().getName(), method.getName()),
                    e);
            // What the method threw, not the reflection wrapper around it; and
            // its class name when it carries no message, never "null".
            Throwable cause = (((e instanceof InvocationTargetException) && (e.getCause() != null))
                    ? e.getCause()
                    : e);
            return CompletableFuture.completedFuture(ToolResult.error(ToolSchemas.describeFailure(cause)));
        }
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
     * <p>
     * Called on the server itself, this serves one shared default session.
     * The SDK does not: each client gets its own {@link #openSession()
     * session}, so concurrent clients never see each other's request ids,
     * cancellations or shutdown.
     *
     * @param message the JSON-RPC message
     * @return a future with the response, or with null when no reply is due
     */
    @Override
    public CompletableFuture<@Nullable Map<String, Object>> handleMessage(Map<String, Object> message) {
        return defaultSession.handleMessage(message);
    }

    /**
     * Abandons the calls still in flight on the default session.
     *
     * <p>
     * Not a permanent shutdown, and it never touches a session opened with
     * {@link #openSession()}: those belong to their clients, and each is
     * closed by the client that opened it. Idempotent.
     */
    @Override
    public void close() {
        defaultSession.close();
    }

    /**
     * Opens a session of this server for one connection.
     *
     * <p>
     * The session shares the tools and schemas, and owns everything that is
     * per-connection: the table of calls in flight (so two clients may use
     * the same JSON-RPC ids at once), the {@code notifications/cancelled}
     * that settle them, and {@link McpMessageHandler#close() close()}, which
     * abandons only that session's calls. The Python SDK keeps the same split
     * with one {@code SdkMcpBridge} per query.
     *
     * @return a new session
     */
    @Override
    public McpMessageHandler openSession() {
        return new Session();
    }

    /** One connection's view of the server. */
    private final class Session implements McpMessageHandler {

        /**
         * Tool calls the CLI has not been answered about yet, keyed by
         * JSON-RPC request id, so {@code notifications/cancelled} can find
         * one.
         */
        private final Map<String, InFlight> inFlight = new ConcurrentHashMap<>();

        @Override
        public CompletableFuture<@Nullable Map<String, Object>> handleMessage(Map<String, Object> message) {
            Object id = message.get(KEY_ID);
            try {
                if (!(message.get(KEY_METHOD) instanceof String method)) {
                    logger.fine(() -> "Ignoring a message with no method for SDK MCP server '"
                            + name + "': " + message.keySet());
                    return completed(null);
                }

                // The MCP specification forbids a null request id, so "no id"
                // and "id: null" both mean notification.
                if (id == null) {
                    handleNotification(method, asMap(message.get(KEY_PARAMS)));
                    return completed(null);
                }

                return switch (method) {
                    case METHOD_INITIALIZE -> handleInitialize(id, asMap(message.get(KEY_PARAMS)));
                    case METHOD_PING -> completed(successResponse(id, Map.of()));
                    case METHOD_LIST_TOOLS -> handleListTools(id);
                    case METHOD_CALL_TOOL -> handleCallTool(id, message.get(KEY_PARAMS));
                    // Everything else, resources and prompts included. That is
                    // the right answer, not an omission: this server advertises
                    // only the "tools" capability, so a spec-conformant client
                    // never asks for the rest. -32601 is JSON-RPC's code for
                    // it, deliberately; the Python SDK's mcp library answers
                    // -32602.
                    default -> completed(errorResponse(
                            id, ERROR_CODE_METHOD_NOT_FOUND, "Method not found: " + method));
                };
            } catch (RuntimeException e) {
                // Nothing malformed the CLI sends may escape this method
                // synchronously; the caller has no id to answer with once it
                // does.
                logger.log(Level.WARNING,
                        "SDK MCP server '" + name + "' could not dispatch a message", e);
                return completed(errorResponse(id, ERROR_CODE_INTERNAL_ERROR, ToolSchemas.describeFailure(e)));
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
                // Already finished, or never ours. Either way a notification
                // is never answered, not even to say so.
                logger.fine(() -> "No in-flight request " + requestId + " to cancel on SDK MCP server '"
                        + name + "'");
                return;
            }
            Object reason = ((params != null) ? params.get("reason") : null);
            logger.fine(() -> "Cancelling " + entry.toolName() + " (request " + requestId + "): " + reason);
            cancel(entry);
        }

        /**
         * Abandons this session's calls still in flight. Idempotent, and the
         * session stays usable.
         */
        @Override
        public void close() {
            for (InFlight entry : List.copyOf(inFlight.values())) {
                if (inFlight.remove(idKey(entry.id()), entry)) {
                    cancel(entry);
                }
            }
        }

        /** A session of the same server opens a sibling, not a sub-session. */
        @Override
        public McpMessageHandler openSession() {
            return SdkMcpServer.this.openSession();
        }

        private CompletableFuture<@Nullable Map<String, Object>> handleCallTool(
                Object id, @Nullable Object rawParams) {
            Map<String, Object> params = asMap(rawParams);
            if (params == null) {
                return completed(errorResponse(
                        id, ERROR_CODE_INVALID_PARAMS, "Invalid params: expected an object"));
            }
            if (!(params.get(KEY_NAME) instanceof String toolName)) {
                return completed(errorResponse(id, ERROR_CODE_INVALID_PARAMS,
                        "Invalid params: 'name' is required and must be a string"));
            }
            // Absent and explicitly null mean no arguments; anything else that
            // is not an object is a malformed request, as the Python SDK's mcp
            // library reports it.
            Object rawArguments = params.get(KEY_ARGUMENTS);
            Map<String, Object> arguments = asMap(rawArguments);
            if ((arguments == null) && (rawArguments != null)) {
                return completed(errorResponse(id, ERROR_CODE_INVALID_PARAMS,
                        "Invalid params: 'arguments' must be an object"));
            }
            if (arguments == null) {
                arguments = Map.of();
            }

            SdkMcpTool<?> tool = tools.get(toolName);
            if (tool == null) {
                // A tool-execution error, not a protocol one, matching the
                // Python SDK. The model reads an isError result and can correct
                // itself; a JSON-RPC error it never sees.
                return completed(successResponse(
                        id, toolErrorResult("Tool '" + toolName + "' not found")));
            }

            // "Servers MUST validate all tool inputs" (MCP spec, Security
            // Considerations). Reported as a tool-execution error so the text
            // reaches the model as tool output it can act on -- an unreadable
            // JVM ClassCastException from a handler that was handed arguments
            // it never agreed to accept helps nobody, and running the handler
            // at all risks half-applying a side effect before it throws.
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
                // Ids are unique among a session's unanswered requests, and
                // every client has a session of its own, so this is the CLI
                // reusing an id it has not had an answer for. Refusing is what
                // the Python SDK does, and it beats delivering a response to
                // the wrong caller.
                logger.warning(() -> "SDK MCP server '" + name + "' already has request id " + id
                        + " in flight on this connection");
                return completed(errorResponse(id, ERROR_CODE_INTERNAL_ERROR,
                        "Request id " + id + " is already in flight"));
            }
            // A caller that cancels the response has given up on the call,
            // just as notifications/cancelled says: QueryHandler does so when
            // the CLI sends control_cancel_request for the control request
            // carrying it.
            response.whenComplete((ignored, ex) -> {
                if ((ex instanceof CancellationException) && inFlight.remove(key, entry)) {
                    context.cancel();
                }
            });

            CompletableFuture<ToolResult> result;
            try {
                @SuppressWarnings("rawtypes")
                SdkMcpTool rawTool = tool;
                @SuppressWarnings("unchecked")
                CompletableFuture<ToolResult> invoked = rawTool.invoke(arguments, context);
                result = invoked;
            } catch (RuntimeException e) {
                // The handler threw instead of returning a future.
                settle(entry, key, toolErrorResult(ToolSchemas.describeFailure(e)), e);
                return response;
            }
            if (result == null) {
                settle(entry, key, toolErrorResult("Tool '" + toolName + "' returned no result"), null);
                return response;
            }

            result.whenComplete((toolResult, ex) -> {
                // Whatever happens in here, the response must complete: an
                // exception escaping this callback is swallowed by the future,
                // and the CLI would wait for the call forever.
                try {
                    if (ex != null) {
                        Throwable cause = unwrap(ex);
                        settle(entry, key, toolErrorResult(ToolSchemas.describeFailure(cause)), cause);
                    } else if (toolResult == null) {
                        // The Python SDK reports a handler that returns
                        // nothing as an isError result too.
                        settle(entry, key,
                                toolErrorResult("Tool '" + toolName + "' completed with no result"), null);
                    } else {
                        settle(entry, key, toolResult.toMap(), null);
                    }
                } catch (RuntimeException | java.lang.Error e) {
                    settle(entry, key, toolErrorResult(ToolSchemas.describeFailure(e)), e);
                }
            });

            return response;
        }

        /**
         * Answers one call, unless it was already answered (cancelled), in
         * which case the late result is dropped -- the whole contract.
         */
        private void settle(InFlight entry, String key, Map<String, Object> result, @Nullable Throwable failure) {
            inFlight.remove(key, entry);
            if (!entry.response().complete(successResponse(entry.id(), result))) {
                logger.fine(() -> "Discarding the late result of cancelled tool " + entry.toolName());
            } else if (failure != null) {
                logger.log(Level.WARNING, "Tool execution failed: " + entry.toolName(), failure);
            }
        }

    }

    /** Cancels one call: signal the handler first, then settle the request. */
    private static void cancel(InFlight entry) {
        entry.context().cancel();
        entry.response().complete(errorResponse(
                entry.id(), ERROR_CODE_REQUEST_CANCELLED, "Request cancelled"));
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
        List<Map<String, Object>> toolList = new ArrayList<>();
        for (Map.Entry<String, SdkMcpTool<?>> tool : tools.entrySet()) {
            toolList.add(describeTool(tool.getValue(), wireSchemas.getOrDefault(tool.getKey(), Map.of())));
        }
        return completed(successResponse(id, Map.of(KEY_TOOLS, toolList)));
    }

    private static Map<String, Object> describeTool(SdkMcpTool<?> tool, Map<String, Object> inputSchema) {
        Map<String, Object> toolInfo = new HashMap<>();
        toolInfo.put(KEY_NAME, tool.name());
        toolInfo.put(KEY_DESCRIPTION, tool.description());
        toolInfo.put(KEY_INPUT_SCHEMA, inputSchema);

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
