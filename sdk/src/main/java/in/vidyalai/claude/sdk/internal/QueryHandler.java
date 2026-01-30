package in.vidyalai.claude.sdk.internal;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.ClaudeSDKClient;
import in.vidyalai.claude.sdk.exceptions.ClaudeSDKException;
import in.vidyalai.claude.sdk.mcp.SdkMcpServer;
import in.vidyalai.claude.sdk.transport.Transport;
import in.vidyalai.claude.sdk.types.control.request.SDKControlInitializeRequest;
import in.vidyalai.claude.sdk.types.control.request.SDKControlInterruptRequest;
import in.vidyalai.claude.sdk.types.control.request.SDKControlMCPStatusRequest;
import in.vidyalai.claude.sdk.types.control.request.SDKControlMcpMessageRequest;
import in.vidyalai.claude.sdk.types.control.request.SDKControlPermissionRequest;
import in.vidyalai.claude.sdk.types.control.request.SDKControlRequest;
import in.vidyalai.claude.sdk.types.control.request.SDKControlRequestData;
import in.vidyalai.claude.sdk.types.control.request.SDKControlRewindFilesRequest;
import in.vidyalai.claude.sdk.types.control.request.SDKControlSetModelRequest;
import in.vidyalai.claude.sdk.types.control.request.SDKControlSetPermissionModeRequest;
import in.vidyalai.claude.sdk.types.control.request.SDKHookCallbackRequest;
import in.vidyalai.claude.sdk.types.control.response.ControlErrorResponse;
import in.vidyalai.claude.sdk.types.control.response.ControlResponse;
import in.vidyalai.claude.sdk.types.control.response.ControlResponseData;
import in.vidyalai.claude.sdk.types.control.response.SDKControlResponse;
import in.vidyalai.claude.sdk.types.hook.HookContext;
import in.vidyalai.claude.sdk.types.hook.HookEvent;
import in.vidyalai.claude.sdk.types.hook.HookInput;
import in.vidyalai.claude.sdk.types.hook.HookMatcher;
import in.vidyalai.claude.sdk.types.hook.HookOutput;
import in.vidyalai.claude.sdk.types.message.Message;
import in.vidyalai.claude.sdk.types.permission.PermissionMode;
import in.vidyalai.claude.sdk.types.permission.PermissionResult;
import in.vidyalai.claude.sdk.types.permission.PermissionResultAllow;
import in.vidyalai.claude.sdk.types.permission.PermissionResultDeny;
import in.vidyalai.claude.sdk.types.permission.PermissionUpdate;
import in.vidyalai.claude.sdk.types.permission.ToolPermissionContext;

/**
 * Handles bidirectional control protocol on top of Transport.
 *
 * <p>
 * This class manages:
 * <ul>
 * <li>Control request/response routing</li>
 * <li>Hook callbacks</li>
 * <li>Tool permission callbacks</li>
 * <li>Message streaming</li>
 * <li>Initialization handshake</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>
 * This class is <b>thread-safe</b> with the following guarantees:
 *
 * <ul>
 * <li><b>start():</b> Thread-safe and idempotent. Multiple concurrent calls are
 * protected by atomic flags. Only one reader task will be started. Throws
 * {@link IllegalStateException} if called after close().</li>
 *
 * <li><b>initialize():</b> Thread-safe and idempotent. Multiple concurrent
 * calls
 * are protected by atomic compare-and-set. Only one initialization request is
 * sent.
 * Subsequent calls return the cached result. If initialization fails,
 * concurrent threads
 * will see the failure and can retry, but this may cause hook callbacks to be
 * registered
 * multiple times - the implementation clears callbacks on failure to mitigate
 * this.</li>
 *
 * <li><b>receiveMessages():</b> Thread-safe. Can be called multiple times to
 * create multiple iterators. Each iterator reads from the same shared message
 * queue, so messages will be distributed across iterators. For typical usage,
 * create a single iterator per QueryHandler instance.</li>
 *
 * <li><b>sendControlRequest():</b> Thread-safe. Multiple threads can send
 * control
 * requests concurrently (interrupt, setModel, setPermissionMode, etc.).</li>
 *
 * <li><b>streamInput():</b> Thread-safe. Can be called from any thread,
 * typically
 * called from a background thread to stream messages to the CLI.</li>
 *
 * <li><b>close():</b> Thread-safe and idempotent. Can be called concurrently
 * with
 * other operations. Shuts down both reader and control executors gracefully
 * with
 * separate timeouts, followed by forced shutdown if needed. Subsequent calls
 * after
 * the first are no-ops.</li>
 * </ul>
 *
 * <h2>Threading Architecture</h2>
 * <p>
 * This class uses two separate {@link ExecutorService} instances:
 * <ul>
 * <li><b>Reader Executor:</b> Single-threaded executor using
 * {@code Executors.newSingleThreadExecutor()} with named virtual thread
 * "QueryHandler-Reader-0". Ensures only one thread reads from transport.</li>
 *
 * <li><b>Control Executor:</b> Multi-threaded executor using
 * {@code Executors.newThreadPerTaskExecutor()} with named virtual threads
 * "QueryHandler-Control-N". Allows concurrent processing of control requests
 * (permissions, hooks, MCP messages).</li>
 * </ul>
 *
 * <h2>Usage Pattern</h2>
 * <p>
 * The intended usage pattern is:
 *
 * <pre>{@code
 * Transport transport = new SubprocessCLITransport(...);
 * transport.connect();
 *
 * QueryHandler handler = new QueryHandler(transport, true, ...);
 * handler.start();           // Start reader thread
 * handler.initialize();      // Initialize control protocol
 *
 * // Stream input from background thread
 * Thread.startVirtualThread(() -> handler.streamInput(messageIterator));
 *
 * // Receive messages from main thread
 * Iterator<Message> messages = handler.receiveMessages();
 * while (messages.hasNext()) {
 *     Message msg = messages.next();
 *     // Process message
 * }
 *
 * // Cleanup
 * handler.close();  // Thread-safe, waits for threads
 * }</pre>
 *
 * <h2>Resource Management</h2>
 * <p>
 * This class manages multiple resources that are properly cleaned up on
 * {@link #close()}:
 * <ul>
 * <li>Reader ExecutorService - single-threaded executor for message
 * reading</li>
 * <li>Control ExecutorService - multi-threaded executor for concurrent control
 * request handling</li>
 * <li>Reader task (named virtual thread: "QueryHandler-Reader-0")</li>
 * <li>Control request handler tasks (named virtual threads:
 * "QueryHandler-Control-N")</li>
 * <li>Hook callback references and their associated closures</li>
 * <li>Pending CompletableFutures for control request/response tracking</li>
 * <li>Message queue containing buffered messages</li>
 * <li>Underlying transport (stdin/stdout/stderr streams, CLI process)</li>
 * </ul>
 *
 * <p>
 * <b>Important:</b> Always call {@link #close()} when done to prevent resource
 * leaks. This class is {@link AutoCloseable} and should be used with
 * try-with-resources when possible:
 *
 * <pre>{@code
 * try (QueryHandler handler = new QueryHandler(...)) {
 *     handler.start();
 *     handler.initialize();
 *     // Use handler
 * } // Automatic cleanup
 * }</pre>
 *
 * <h2>Shutdown Behavior</h2>
 * <p>
 * When {@link #close()} is called:
 * <ol>
 * <li>Sets the closed flag to prevent new operations</li>
 * <li>Completes all pending control request futures exceptionally</li>
 * <li>Closes the transport to unblock the reader thread</li>
 * <li>Initiates graceful shutdown of both executors via {@code shutdown()}</li>
 * <li>Waits up to 10 seconds for reader executor to terminate</li>
 * <li>Forces reader shutdown via {@code shutdownNow()} if timeout occurs, waits
 * 2s more</li>
 * <li>Logs warning if reader threads don't terminate after forced shutdown</li>
 * <li>Waits up to 5 seconds for control executor to terminate</li>
 * <li>Forces control executor shutdown via {@code shutdownNow()} if timeout
 * occurs, waits 2s more</li>
 * <li>Logs warning if control threads don't terminate after forced
 * shutdown</li>
 * <li>Clears all callback maps and queues to enable garbage collection</li>
 * </ol>
 * <p>
 * <b>Note:</b> In rare cases where threads are stuck in uninterruptible I/O
 * operations,
 * they may continue running briefly after {@code close()} returns. This is
 * logged as a
 * warning. Such threads will eventually terminate when they complete their I/O
 * operation.
 *
 * @see Transport
 * @see ClaudeSDKClient
 */
public class QueryHandler implements AutoCloseable {

    private static final Logger logger = Logger.getLogger(QueryHandler.class.getName());
    private static final SecureRandom random = new SecureRandom();
    private static final int DEFAULT_MSG_Q_SIZE = 1000;
    private static final int RESULT_WAIT_SECS = 60;
    private static final int READER_THREAD_JOIN_TIMEOUT_SECS = 10;
    private static final int CONTROL_THREAD_JOIN_TIMEOUT_SECS = 5;
    private static final ObjectMapper MAPPER;

    static {
        MAPPER = new ObjectMapper();
        // Configure ObjectMapper for control protocol types
        // Ignore unknown properties for forward compatibility
        MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        // Don't include null values in serialization
        MAPPER.configOverride(Map.class)
                .setInclude(JsonInclude.Value.construct(
                        JsonInclude.Include.NON_NULL,
                        JsonInclude.Include.NON_NULL));
    }

    private final Transport transport;
    private final boolean isStreamingMode;
    // May be null
    private final ClaudeAgentOptions.CanUseTool canUseTool;
    @Nullable
    private final Map<HookEvent, List<HookMatcher>> hooks;
    @Nullable
    private final Map<String, SdkMcpServer> sdkMcpServers;
    private final Duration initializeTimeout;

    // Control protocol state
    private final Map<String, CompletableFuture<ControlResponse>> pendingControlResponses = new ConcurrentHashMap<>();
    private final Map<String, BiFunction<HookInput, HookContext, CompletableFuture<HookOutput>>> hookCallbacks = new ConcurrentHashMap<>();
    private final AtomicInteger nextCallbackId = new AtomicInteger(0);
    private final AtomicInteger requestCounter = new AtomicInteger(0);

    // Message stream
    private final BlockingQueue<Map<String, Object>> messageQueue;
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean readerStarted = new AtomicBoolean(false);
    @Nullable
    private volatile SDKControlResponse initializationResult = null;

    // Thread management via ExecutorService
    // Separate executors for reader (single thread) and control tasks (concurrent)
    private final ExecutorService readerExecutor;
    private final ExecutorService controlExecutor;

    // Track first result for proper stream closure
    private final CompletableFuture<Void> firstResultEvent = new CompletableFuture<>();
    private final Duration streamCloseTimeout;

    /**
     * Creates a new QueryHandler.
     *
     * @param transport         the transport for I/O
     * @param isStreamingMode   whether using streaming (bidirectional) mode
     * @param canUseTool        optional callback for tool permission requests
     * @param hooks             optional hook configurations
     * @param initializeTimeout timeout for the initialize request
     */
    public QueryHandler(
            Transport transport,
            boolean isStreamingMode,
            ClaudeAgentOptions.CanUseTool canUseTool, // may be null
            @Nullable Map<HookEvent, List<HookMatcher>> hooks,
            Duration initializeTimeout) {
        this(transport, isStreamingMode, canUseTool, hooks, null, initializeTimeout, DEFAULT_MSG_Q_SIZE);
    }

    /**
     * Creates a new QueryHandler with SDK MCP server support.
     *
     * @param transport         the transport for I/O
     * @param isStreamingMode   whether using streaming (bidirectional) mode
     * @param canUseTool        optional callback for tool permission requests (may
     *                          be null)
     * @param hooks             optional hook configurations
     * @param sdkMcpServers     optional SDK MCP servers for in-process tool
     *                          execution
     * @param initializeTimeout timeout for the initialize request
     * @param maxMsgQSize       max message queue size
     */
    public QueryHandler(
            Transport transport,
            boolean isStreamingMode,
            ClaudeAgentOptions.CanUseTool canUseTool, // may be null
            @Nullable Map<HookEvent, List<HookMatcher>> hooks,
            @Nullable Map<String, SdkMcpServer> sdkMcpServers,
            Duration initializeTimeout,
            @Nullable Integer maxMsgQSize) {
        this.transport = transport;
        this.isStreamingMode = isStreamingMode;
        this.canUseTool = canUseTool;
        this.hooks = hooks;
        this.sdkMcpServers = sdkMcpServers;
        this.initializeTimeout = initializeTimeout;
        this.messageQueue = new LinkedBlockingQueue<>((maxMsgQSize != null) ? maxMsgQSize : DEFAULT_MSG_Q_SIZE);

        // Create separate executors for reader and control tasks
        // Reader: Single-threaded executor with named virtual thread
        this.readerExecutor = Executors.newSingleThreadExecutor(
                Thread.ofVirtual()
                        .name("QueryHandler-Reader-", 0)
                        .factory());

        // Control: Multi-threaded executor for concurrent control request handling
        this.controlExecutor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual()
                        .name("QueryHandler-Control-", 0)
                        .factory());

        // Get stream close timeout from env, default 60 seconds
        long timeoutMs = Long.parseLong(System.getenv().getOrDefault("CLAUDE_CODE_STREAM_CLOSE_TIMEOUT", "60000"));
        this.streamCloseTimeout = Duration.ofMillis(timeoutMs);
    }

    /**
     * Initializes the control protocol if in streaming mode.
     *
     * @return initialization response with supported commands, or null if not
     *         streaming
     * @throws ClaudeSDKException if initialization fails
     */
    @Nullable
    public SDKControlResponse initialize() {
        if (!isStreamingMode) {
            return null;
        }

        // Use compare-and-set to prevent double initialization
        if (!initialized.compareAndSet(false, true)) {
            // Already initialized, return cached result
            return initializationResult;
        }

        try {
            // Build hooks configuration for initialization
            Map<HookEvent, List<Map<String, Object>>> hooksConfig = new HashMap<>();
            if (hooks != null) {
                for (Map.Entry<HookEvent, List<HookMatcher>> entry : hooks.entrySet()) {
                    List<HookMatcher> matchers = entry.getValue();
                    if ((matchers != null) && (!matchers.isEmpty())) {
                        List<Map<String, Object>> matcherConfigs = new ArrayList<>();
                        for (HookMatcher matcher : matchers) {
                            List<String> callbackIds = new ArrayList<>();
                            for (HookMatcher.HookCallback callback : matcher.hooks()) {
                                String callbackId = "hook_" + nextCallbackId.getAndIncrement();
                                hookCallbacks.put(callbackId, callback);
                                callbackIds.add(callbackId);
                            }

                            Map<String, Object> matcherConfig = new HashMap<>();
                            matcherConfig.put("matcher", matcher.matcher());
                            matcherConfig.put("hookCallbackIds", callbackIds);
                            if (matcher.timeoutSeconds() != null) {
                                matcherConfig.put("timeout", matcher.timeoutSeconds());
                            }
                            matcherConfigs.add(matcherConfig);
                        }
                        hooksConfig.put(entry.getKey(), matcherConfigs);
                    }
                }
            }

            // Send initialize request
            SDKControlInitializeRequest request = new SDKControlInitializeRequest(
                    hooksConfig.isEmpty() ? null : hooksConfig);

            initializationResult = sendControlRequest(request, initializeTimeout);
            return initializationResult;
        } catch (Exception e) {
            // Reset initialized flag on failure so retry is possible
            initialized.set(false);
            // Clean up hook callbacks registered during failed initialization
            hookCallbacks.clear();
            // Close transport to prevent resource leak
            try {
                transport.close();
            } catch (Exception closeEx) {
                logger.log(Level.WARNING, "Failed to close transport after initialization failure", closeEx);
            }
            throw new ClaudeSDKException("Failed to initialize: " + e.getMessage(), e);
        }
    }

    /**
     * Starts reading messages from transport.
     *
     * @throws IllegalStateException if already closed or already started
     */
    public void start() {
        if (closed.get()) {
            throw new IllegalStateException("QueryHandler is closed");
        }

        // Use atomic compare-and-set to ensure only one reader task is started
        if (readerStarted.compareAndSet(false, true)) {
            readerExecutor.submit(this::readMessages);
        }
        // If already started, this is a no-op (idempotent)
    }

    private void readMessages() {
        try {
            Iterator<Map<String, Object>> messages = transport.readMessages();
            while (messages.hasNext() && (!closed.get())) {
                Map<String, Object> message = messages.next();
                String msgType = (String) message.get("type");

                // Route control messages
                if ("control_response".equals(msgType)) {
                    handleControlResponse(message);
                    continue;
                } else if ("control_request".equals(msgType)) {
                    // Handle incoming control requests from CLI
                    // Use controlExecutor for concurrent control request handling
                    controlExecutor.submit(() -> handleControlRequest(message));
                    continue;
                } else if ("control_cancel_request".equals(msgType)) {
                    // TODO: Implement cancellation support
                    continue;
                }

                // Track results for proper stream closure
                if ("result".equals(msgType)) {
                    firstResultEvent.complete(null);
                }

                // Regular SDK messages go to the stream
                // Use offer with timeout to prevent deadlock if queue is full
                if (!messageQueue.offer(message, 5, TimeUnit.SECONDS)) {
                    logger.warning("Message queue full, dropping message: " + message);
                }
            }
        } catch (InterruptedException e) {
            // Thread was interrupted during shutdown
            Thread.currentThread().interrupt();
            logger.fine("Reader thread interrupted");
        } catch (Exception e) {
            if (!closed.get()) {
                logger.log(Level.SEVERE, "Fatal error in message reader: " + e.getMessage(), e);
                // Signal all pending control requests
                for (Map.Entry<String, CompletableFuture<ControlResponse>> entry : pendingControlResponses
                        .entrySet()) {
                    entry.getValue().completeExceptionally(e);
                }
                pendingControlResponses.clear();

                // Put error in stream so iterators can handle it
                try {
                    Map<String, Object> errMessage = new HashMap<>();
                    errMessage.put("type", "error");
                    errMessage.put("error", e.getMessage());
                    messageQueue.offer(errMessage, 1, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        } finally {
            // Signal end of stream
            try {
                Map<String, Object> endMessage = new HashMap<>();
                endMessage.put("type", "end");
                messageQueue.offer(endMessage, 1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // Complete firstResultEvent in case it's still pending
            firstResultEvent.complete(null);
        }
    }

    @SuppressWarnings("null")
    private void handleControlResponse(Map<String, Object> message) {
        SDKControlResponse controlResponse = MAPPER.convertValue(message, SDKControlResponse.class);
        ControlResponseData response = controlResponse.response();
        if (response == null) {
            return;
        }

        String requestId = response.requestId();
        CompletableFuture<ControlResponse> future = pendingControlResponses.remove(requestId);
        if (future == null) {
            return;
        }

        switch (response) {
            case ControlResponse success -> future.complete(success);
            case ControlErrorResponse err -> future.completeExceptionally(new ClaudeSDKException(err.error()));
        }
    }

    /**
     * Handles incoming control requests from CLI using strongly-typed classes.
     */
    @SuppressWarnings("null")
    private void handleControlRequest(Map<String, Object> message) {
        String requestId = null;
        try {
            // Deserialize to typed SDKControlRequest
            SDKControlRequest controlRequest = MAPPER.convertValue(message, SDKControlRequest.class);

            requestId = controlRequest.requestId();
            SDKControlRequestData requestData = controlRequest.request();

            Map<String, Object> responseData = new HashMap<>();

            // Pattern match on request type (discriminated union)
            switch (requestData) {
                case SDKControlPermissionRequest permissionReq -> {
                    if (canUseTool == null) {
                        throw new ClaudeSDKException("canUseTool callback is not provided");
                    }

                    String toolName = permissionReq.toolName();
                    Map<String, Object> input = permissionReq.input();

                    // Build context with permission suggestions from CLI
                    List<PermissionUpdate> suggestions = ((permissionReq.permissionSuggestions() != null)
                            ? permissionReq.permissionSuggestions()
                            : List.of());
                    ToolPermissionContext context = new ToolPermissionContext(null, suggestions);

                    CompletableFuture<PermissionResult> resultFuture = canUseTool.apply(toolName, input, context);
                    PermissionResult result = resultFuture.get(RESULT_WAIT_SECS, TimeUnit.SECONDS);

                    // Serialize permission result to response format
                    switch (result) {
                        case PermissionResultAllow allow -> {
                            responseData = allow.toMap(input);
                        }
                        case PermissionResultDeny deny -> {
                            responseData = deny.toMap(input);
                        }
                    }
                }
                case SDKHookCallbackRequest hookReq -> {
                    String callbackId = hookReq.callbackId();
                    var callback = hookCallbacks.get(callbackId);
                    if (callback == null) {
                        throw new ClaudeSDKException("No hook callback found for ID: " + callbackId);
                    }

                    HookInput hookInput = hookReq.input();
                    String toolUseId = hookReq.toolUseId();
                    HookContext context = new HookContext(toolUseId);

                    CompletableFuture<HookOutput> outputFuture = callback.apply(hookInput, context);
                    HookOutput output = outputFuture.get(RESULT_WAIT_SECS, TimeUnit.SECONDS);
                    responseData = output.toMap();
                }
                case SDKControlMcpMessageRequest mcpReq -> {
                    // Route MCP message to SDK MCP server
                    String serverName = mcpReq.serverName();
                    Map<String, Object> mcpMessage = mcpReq.message();

                    if ((serverName == null) || (mcpMessage == null)) {
                        throw new ClaudeSDKException("Missing name or message for SDK MCP server");
                    }

                    if ((sdkMcpServers == null) || (!sdkMcpServers.containsKey(serverName))) {
                        throw new ClaudeSDKException("SDK MCP server not found: " + serverName);
                    }

                    SdkMcpServer server = sdkMcpServers.get(serverName);
                    CompletableFuture<Map<String, Object>> mcpResponseFuture = server.handleMessage(mcpMessage);
                    Map<String, Object> mcpResponse = mcpResponseFuture.get(RESULT_WAIT_SECS, TimeUnit.SECONDS);
                    responseData.put("mcp_response", mcpResponse);
                }
                case SDKControlMCPStatusRequest ignored -> {
                    // Interrupt is sent from SDK to CLI, not CLI to SDK
                    throw new ClaudeSDKException("Unexpected mcp status request from CLI: " + ignored);
                }
                case SDKControlInterruptRequest ignored -> {
                    // Interrupt is sent from SDK to CLI, not CLI to SDK
                    throw new ClaudeSDKException("Unexpected interrupt request from CLI: " + ignored);
                }
                case SDKControlInitializeRequest ignored -> {
                    // Initialize is sent from SDK to CLI, not CLI to SDK
                    throw new ClaudeSDKException("Unexpected initialize request from CLI: " + ignored);
                }
                case SDKControlSetPermissionModeRequest ignored -> {
                    // Set permission mode is sent from SDK to CLI, not CLI to SDK
                    throw new ClaudeSDKException("Unexpected set_permission_mode request from CLI: " + ignored);
                }
                case SDKControlSetModelRequest ignored -> {
                    // Set model is sent from SDK to CLI, not CLI to SDK
                    throw new ClaudeSDKException("Unexpected set_model request from CLI: " + ignored);
                }
                case SDKControlRewindFilesRequest ignored -> {
                    // Rewind files is sent from SDK to CLI, not CLI to SDK
                    throw new ClaudeSDKException("Unexpected rewind_files request from CLI: " + ignored);
                }
            }

            // Send success response
            sendControlResponse(new ControlResponse(requestId, responseData));
        } catch (Exception e) {
            // Send error response
            if (requestId == null) {
                // Try to extract from raw message if deserialization failed
                requestId = (String) message.get("request_id");
            }
            if (requestId != null) {
                logger.log(Level.WARNING, "Error in handling control request", e);
                sendControlResponse(new ControlErrorResponse(requestId, e.getMessage()));
            } else {
                logger.log(Level.WARNING, "Failed to handle control request without request_id", e);
            }
        }
    }

    private void sendControlResponse(ControlResponseData crData) {
        // Check if closed before attempting to write
        if (closed.get()) {
            logger.fine("Skipping control response send, handler is closed");
            return;
        }

        SDKControlResponse cr = new SDKControlResponse(crData);
        try {
            String json = MAPPER.writeValueAsString(cr);
            transport.write(json + "\n");
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to send control response", e);
        }
    }

    /**
     * Sends a strongly-typed control request and waits for response.
     *
     * @param requestData the typed request data
     * @param timeout     timeout duration
     * @return control response
     * @throws ClaudeSDKException if the request fails or times out
     */
    private SDKControlResponse sendControlRequest(SDKControlRequestData requestData, Duration timeout) {
        if (!isStreamingMode) {
            throw new ClaudeSDKException("Control requests require streaming mode");
        }

        if (closed.get()) {
            throw new ClaudeSDKException("QueryHandler is closed");
        }

        // Generate unique request ID
        String requestId = "req_" + requestCounter.incrementAndGet() + "_" + randomHex(4);

        // Create future for response
        CompletableFuture<ControlResponse> future = new CompletableFuture<>();
        pendingControlResponses.put(requestId, future);

        // Build and send typed request
        SDKControlRequest controlRequest = new SDKControlRequest(requestId, requestData);

        try {
            // Serialize to JSON - the request will have proper structure:
            // {"type": "control_request", "request_id": "...", "request": {...}}
            String json = MAPPER.writeValueAsString(controlRequest);
            transport.write(json + "\n");

            // Wait for response
            ControlResponse result = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!requestId.equals(result.requestId())) {
                throw new IllegalStateException(
                        "Result ids don't match: %s != %s".formatted(requestId, result.requestId()));
            }
            return new SDKControlResponse(result);
        } catch (TimeoutException e) {
            throw new ClaudeSDKException("Control request timeout: " + requestData.subtype());
        } catch (Exception e) {
            throw new ClaudeSDKException("Control request failed: " + e.getMessage(), e);
        } finally {
            // Always remove from pending map to prevent memory leak
            pendingControlResponses.remove(requestId);
        }
    }

    private static String randomHex(int bytes) {
        byte[] data = new byte[bytes];
        random.nextBytes(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : data) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * Sends an get current MCP server connection status control request.
     *
     * @return MCP server connection status map
     * @throws ClaudeSDKException if the mcp status request fails
     */
    public Map<String, Object> getMcpStatus() {
        SDKControlMCPStatusRequest request = new SDKControlMCPStatusRequest();
        SDKControlResponse response = sendControlRequest(request, Duration.ofSeconds(RESULT_WAIT_SECS));
        return ((ControlResponse) response.response()).response();
    }

    /**
     * Sends an interrupt control request.
     *
     * @throws ClaudeSDKException if the interrupt request fails
     */
    public void interrupt() {
        SDKControlInterruptRequest request = new SDKControlInterruptRequest();
        sendControlRequest(request, Duration.ofSeconds(RESULT_WAIT_SECS));
    }

    /**
     * Changes the permission mode.
     *
     * @param mode the new permission mode
     * @throws ClaudeSDKException if the request fails
     */
    public void setPermissionMode(PermissionMode mode) {
        SDKControlSetPermissionModeRequest request = new SDKControlSetPermissionModeRequest(mode);
        sendControlRequest(request, Duration.ofSeconds(RESULT_WAIT_SECS));
    }

    /**
     * Changes the model.
     *
     * @param model the model identifier, or null to reset to default
     * @throws ClaudeSDKException if the request fails
     */
    public void setModel(@Nullable String model) {
        SDKControlSetModelRequest request = new SDKControlSetModelRequest(model);
        sendControlRequest(request, Duration.ofSeconds(RESULT_WAIT_SECS));
    }

    /**
     * Rewinds tracked files to their state at a specific user message.
     * Requires file checkpointing to be enabled via the `enable_file_checkpointing`
     * option
     *
     * @param userMessageId the UUID of the user message to rewind to
     * @throws ClaudeSDKException if the request fails
     */
    public void rewindFiles(String userMessageId) {
        SDKControlRewindFilesRequest request = new SDKControlRewindFilesRequest(userMessageId);
        sendControlRequest(request, Duration.ofSeconds(RESULT_WAIT_SECS));
    }

    /**
     * Streams input messages to transport.
     */
    public void streamInput(Iterator<Map<String, Object>> stream) {
        try {
            while (stream.hasNext() && (!closed.get())) {
                Map<String, Object> message = stream.next();
                String json = MAPPER.writeValueAsString(message);
                transport.write(json + "\n");
            }

            // If we have hooks or SDK MCP servers that need bidirectional communication,
            // wait for first result
            @SuppressWarnings("null")
            boolean needsBidirectional = (((hooks != null) && (!hooks.isEmpty()))
                    || ((sdkMcpServers != null) && (!sdkMcpServers.isEmpty())));
            if (needsBidirectional) {
                try {
                    firstResultEvent.get(streamCloseTimeout.toMillis(), TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    logger.fine("Timed out waiting for first result, closing input stream");
                } catch (Exception e) {
                    // Ignore other exceptions
                }
            }

            // After all messages sent, end input
            if (!closed.get()) {
                transport.endInput();
            }
        } catch (Exception e) {
            if (!closed.get()) {
                logger.log(Level.WARNING, "Error streaming input", e);
            }
        }
    }

    /**
     * Returns an iterator over SDK messages (not control messages).
     *
     * <p>
     * This method can be called multiple times to create multiple iterators.
     * Each iterator reads from the same shared message queue, so messages will
     * be distributed across all active iterators.
     *
     * <p>
     * <b>Note:</b> For typical usage, you should create only one iterator per
     * QueryHandler instance. Creating multiple iterators is supported but will
     * cause messages to be distributed unpredictably across the iterators.
     *
     * <p>
     * Usage:
     *
     * <pre>{@code
     * Iterator<Message> messages = handler.receiveMessages();
     * while (messages.hasNext()) {
     *     Message msg = messages.next();
     *     // Process message
     * }
     * }</pre>
     *
     * @return an iterator over messages received from the CLI
     */
    public Iterator<Message> receiveMessages() {
        return new MessageIterator();
    }

    /**
     * Gets the initialization result.
     *
     * @return the initialization result, or null if not initialized
     */
    @Nullable
    public Map<String, Object> getInitializationResult() {
        if (initializationResult == null) {
            return null;
        }

        return ((ControlResponse) initializationResult.response()).response();
    }

    /**
     * Closes this QueryHandler and releases all resources.
     *
     * <p>
     * This method:
     * <ul>
     * <li>Interrupts and waits for the reader thread to terminate</li>
     * <li>Interrupts and waits for all control request handler threads</li>
     * <li>Completes all pending control request futures exceptionally</li>
     * <li>Clears all callback maps</li>
     * <li>Closes the underlying transport</li>
     * </ul>
     *
     * <p>
     * This method is idempotent and can be safely called multiple times.
     * Subsequent calls after the first are no-ops.
     */
    @Override
    public void close() {
        // Use atomic compare-and-set for thread-safe idempotent close
        if (closed.getAndSet(true)) {
            return; // Already closed
        }

        logger.fine("Closing QueryHandler");

        try {
            // 1. Complete all pending futures exceptionally
            ClaudeSDKException closedException = new ClaudeSDKException("QueryHandler is closed");
            for (CompletableFuture<ControlResponse> future : pendingControlResponses.values()) {
                future.completeExceptionally(closedException);
            }
            pendingControlResponses.clear();

            // Complete firstResultEvent if still pending
            firstResultEvent.complete(null);

            // 2. Close transport FIRST to unblock any waiting hasNext() calls
            // This ensures the reader thread can exit its message loop quickly
            try {
                transport.close();
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error closing transport", e);
            }

            // 3. Graceful shutdown of both executors
            readerExecutor.shutdown();
            controlExecutor.shutdown();
            logger.fine("Initiated executors shutdown, waiting for tasks to complete");

            // 4. Wait for reader executor to terminate (should be quick since transport is
            // closed)
            boolean readerTerminated = readerExecutor.awaitTermination(
                    READER_THREAD_JOIN_TIMEOUT_SECS,
                    TimeUnit.SECONDS);

            if (!readerTerminated) {
                logger.warning("Reader executor did not terminate gracefully, forcing shutdown");
                readerExecutor.shutdownNow();
                boolean forcedTermination = readerExecutor.awaitTermination(2, TimeUnit.SECONDS);
                if (!forcedTermination) {
                    logger.warning(
                            "Reader executor did not terminate even after shutdownNow() - threads may still be running");
                }
            } else {
                logger.fine("Reader executor terminated gracefully");
            }

            // 5. Wait for control executor to terminate
            boolean controlTerminated = controlExecutor.awaitTermination(
                    CONTROL_THREAD_JOIN_TIMEOUT_SECS,
                    TimeUnit.SECONDS);

            if (!controlTerminated) {
                // Force shutdown if graceful shutdown timed out
                logger.warning("Control executor did not terminate gracefully within " +
                        CONTROL_THREAD_JOIN_TIMEOUT_SECS + " seconds, forcing shutdown");
                List<Runnable> pendingTasks = controlExecutor.shutdownNow();
                logger.fine("Cancelled " + pendingTasks.size() + " pending control tasks");

                // Wait a bit more for forced shutdown
                controlTerminated = controlExecutor.awaitTermination(2, TimeUnit.SECONDS);

                if (!controlTerminated) {
                    logger.warning("Control executor did not terminate after shutdownNow() - " +
                            "control request handler threads may still be running");
                }
            } else {
                logger.fine("Control executor terminated gracefully");
            }

            // 6. Clear all callback maps to prevent memory leaks
            hookCallbacks.clear();

            // 7. Clear message queue
            messageQueue.clear();

            logger.fine("QueryHandler closed successfully");

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.log(Level.WARNING, "Interrupted during close, forcing executors shutdown", e);
            readerExecutor.shutdownNow();
            controlExecutor.shutdownNow();
            try {
                transport.close();
            } catch (Exception te) {
                logger.log(Level.WARNING, "Failed to close transport after interruption", te);
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error during close", e);
        }
    }

    /**
     * Iterator for SDK messages from the message queue.
     *
     * <p>
     * This iterator blocks waiting for messages and handles special control
     * messages like "end" and "error".
     */
    private class MessageIterator implements Iterator<Message> {

        @Nullable
        private Message nextMessage = null;
        private boolean done = false;

        @Override
        public boolean hasNext() {
            if (nextMessage != null) {
                return true;
            }
            if (done) {
                return false;
            }

            try {
                // Use poll with timeout instead of blocking take() to ensure graceful
                // termination.
                // Blocking take() would risk deadlock if the reader thread crashes or is
                // interrupted
                // before sending the "end" message. Polling allows us to periodically check the
                // closed flag and terminate within 400ms even if the queue is empty.
                while (!done) {
                    // Check closed flag to avoid blocking forever
                    if (closed.get()) {
                        done = true;
                        return false;
                    }

                    // Poll with timeout to periodically wake up and check closed flag
                    Map<String, Object> message = messageQueue.poll(400, TimeUnit.MILLISECONDS);
                    if (message != null) {
                        // Check for end marker
                        if ("end".equals(message.get("type"))) {
                            done = true;
                            return false;
                        }
                        if ("error".equals(message.get("type"))) {
                            done = true;
                            throw new ClaudeSDKException((String) message.get("error"));
                        }

                        nextMessage = MessageParser.parse(message);
                        return true;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                done = true;
            }
            return false;
        }

        @Override
        public Message next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            Message msg = nextMessage;
            nextMessage = null;
            return msg;
        }

    }

}
