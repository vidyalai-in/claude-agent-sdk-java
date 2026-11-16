package in.vidyalai.claude.sdk;

import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import in.vidyalai.claude.sdk.exceptions.CLIConnectionException;
import in.vidyalai.claude.sdk.exceptions.ClaudeSDKException;
import in.vidyalai.claude.sdk.internal.CanUseToolConfig;
import in.vidyalai.claude.sdk.internal.McpServers;
import in.vidyalai.claude.sdk.internal.QueryHandler;
import in.vidyalai.claude.sdk.internal.SessionResume;
import in.vidyalai.claude.sdk.internal.SessionStoreValidation;
import in.vidyalai.claude.sdk.internal.TranscriptMirrorBatcher;
import in.vidyalai.claude.sdk.internal.transport.SubprocessCLITransport;
import in.vidyalai.claude.sdk.mcp.McpMessageHandler;
import in.vidyalai.claude.sdk.transport.Transport;
import in.vidyalai.claude.sdk.types.config.SystemPromptCustom;
import in.vidyalai.claude.sdk.types.config.SystemPromptPreset;
import in.vidyalai.claude.sdk.types.mcp.ContextUsageResponse;
import in.vidyalai.claude.sdk.types.mcp.McpStatusResponse;
import in.vidyalai.claude.sdk.types.message.Message;
import in.vidyalai.claude.sdk.types.message.ResultMessage;
import in.vidyalai.claude.sdk.types.permission.PermissionMode;

/**
 * Client for bidirectional, interactive conversations with Claude Code.
 *
 * <p>
 * This client provides full control over the conversation flow with support
 * for streaming, interrupts, and dynamic message sending. For simple one-shot
 * queries, consider using {@link ClaudeSDK#query} instead.
 *
 * <h2>Key Features</h2>
 * <ul>
 * <li><b>Bidirectional</b>: Send and receive messages at any time</li>
 * <li><b>Stateful</b>: Maintains conversation context across messages</li>
 * <li><b>Interactive</b>: Send follow-ups based on responses</li>
 * <li><b>Control flow</b>: Support for interrupts and session management</li>
 * </ul>
 *
 * When to use ClaudeSDKClient:
 * - Building chat interfaces or conversational UIs
 * - Interactive debugging or exploration sessions
 * - Multi-turn conversations with context
 * - When you need to react to Claude's responses
 * - Real-time applications with user input
 * - When you need interrupt capabilities
 *
 * When to use {@link ClaudeSDK#query} instead:
 * - Simple one-off questions
 * - Batch processing of prompts
 * - Fire-and-forget automation scripts
 * - When all inputs are known upfront
 * - Stateless operations
 *
 * <h2>Example Usage</h2>
 *
 * <pre>{@code
 * var options = ClaudeAgentOptions.builder()
 *         .permissionMode(PermissionMode.BYPASS_PERMISSIONS)
 *         .build();
 *
 * try (var client = new ClaudeSDKClient(options)) {
 *     client.connect();
 *     client.sendMessage("What is 2+2?");
 *
 *     for (Message msg : client.receiveResponse()) {
 *         if (msg instanceof AssistantMessage assistant) {
 *             System.out.println(assistant.getTextContent());
 *         }
 *     }
 * }
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 * <p>
 * This class is <b>partially thread-safe</b> with the following guarantees:
 *
 * <ul>
 * <li><b>connect():</b> Thread-safe and idempotent. Multiple concurrent calls
 * are protected by synchronization. Only one connection will be established.
 * Throws {@link IllegalStateException} if called after close().</li>
 *
 * <li><b>sendMessage() / query():</b> Thread-safe. Multiple threads can send
 * messages concurrently.</li>
 *
 * <li><b>receiveMessages():</b> Thread-safe. Can be called multiple times to
 * create multiple iterators. Each iterator reads from the same shared message
 * queue, so messages will be distributed across iterators. For typical usage,
 * create a single iterator per client instance.</li>
 *
 * <li><b>receiveResponse():</b> Thread-safe. Can be called multiple times.
 * Each call creates a new iterator that reads from the shared message
 * queue.</li>
 *
 * <li><b>Control methods (interrupt, setModel, setPermissionMode, etc.):</b>
 * Thread-safe. Can be called concurrently from any thread.</li>
 *
 * <li><b>close() / disconnect():</b> Thread-safe and idempotent. Can be called
 * concurrently with other operations. Subsequent calls after the first are
 * no-ops.</li>
 * </ul>
 *
 * <h2>Resource Management</h2>
 * <p>
 * This class manages resources that are properly cleaned up on
 * {@link #close()}:
 * <ul>
 * <li>QueryHandler and its thread pools (reader and control executors)</li>
 * <li>Transport and CLI subprocess</li>
 * <li>Message iterators and queues</li>
 * </ul>
 *
 * <p>
 * <b>Important:</b> Always call {@link #close()} when done to prevent resource
 * leaks. This class is {@link AutoCloseable} and should be used with
 * try-with-resources when possible.
 */
public class ClaudeSDKClient implements AutoCloseable {

    private static final Logger logger = Logger.getLogger(ClaudeSDKClient.class.getName());

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ClaudeAgentOptions options;
    @Nullable
    private final Transport customTransport;
    /**
     * Builds the default transport from the effective options when no custom
     * transport was given. A seam for tests that need a scripted transport
     * while store-backed resume still runs (a custom transport skips it).
     */
    private final Function<ClaudeAgentOptions, Transport> transportFactory;

    // Thread safety: AtomicBoolean for state management
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * Serializes connect(), disconnect() and close(), so a disconnect cannot
     * delete the materialized CLAUDE_CONFIG_DIR while another thread is still
     * closing the CLI that uses it, nor slip in during a connect and leave
     * {@code connected} set on a closed handler. Reentrant: a failed connect
     * releases its resources while holding it.
     */
    private final ReentrantLock lifecycleLock = new ReentrantLock();

    // Thread safety: Volatile for visibility across threads
    @Nullable
    private volatile Transport transport;
    @Nullable
    private volatile QueryHandler query;

    /**
     * Materialized resume state when {@code sessionStore} + {@code resume}/
     * {@code continueConversation} are combined. Cleaned up on disconnect.
     */
    private volatile SessionResume.@Nullable MaterializedResume materialized;

    /**
     * Creates a new client with the specified options.
     *
     * @param options the agent options
     */
    public ClaudeSDKClient(ClaudeAgentOptions options) {
        this(options, null);
    }

    /**
     * Creates a new client with default options.
     */
    public ClaudeSDKClient() {
        this(ClaudeAgentOptions.defaults(), null);
    }

    /**
     * Creates a new client with a custom transport.
     *
     * <p>
     * When {@code transport} is non-null it is used instead of the default
     * subprocess transport, and the CLI-flag options in {@code options} are not
     * applied to it; see {@link ClaudeSDK#query(String, ClaudeAgentOptions, Transport)}
     * for what the SDK does and does not configure on a custom transport.
     *
     * @param options   the agent options
     * @param transport custom transport implementation, or null for the
     *                  default subprocess transport
     */
    public ClaudeSDKClient(ClaudeAgentOptions options, @Nullable Transport transport) {
        this(options, transport, SubprocessCLITransport::new);
    }

    /**
     * Creates a client whose default transport comes from
     * {@code transportFactory}. Package-private: a test seam.
     */
    static ClaudeSDKClient withTransportFactory(ClaudeAgentOptions options,
            Function<ClaudeAgentOptions, Transport> transportFactory) {
        return new ClaudeSDKClient(options, null, transportFactory);
    }

    private ClaudeSDKClient(ClaudeAgentOptions options, @Nullable Transport transport,
            Function<ClaudeAgentOptions, Transport> transportFactory) {
        this.options = options;
        this.customTransport = transport;
        this.transportFactory = transportFactory;
    }

    /**
     * Connects to Claude Code.
     *
     * <p>
     * This initializes the connection and performs the initialization handshake.
     * After connecting, you can send messages and receive responses.
     *
     * @throws CLIConnectionException if connection fails
     */
    public void connect() throws CLIConnectionException {
        connect(null);
    }

    /**
     * Connects to Claude Code with an initial prompt.
     *
     * <p>
     * This method is thread-safe and idempotent. Multiple concurrent calls are
     * protected by synchronization, and only one connection will be established.
     *
     * <p>
     * A failed connect leaves nothing behind: whatever it had set up by then
     * (the CLI process and transport, the control-protocol threads, the
     * SessionStore mirror, the temporary {@code CLAUDE_CONFIG_DIR} a
     * store-backed resume materializes, which holds a copy of the
     * credentials) is released before the failure is rethrown, as the Python
     * SDK's {@code connect()} does.
     *
     * <p>
     * Any non-null {@code initialPrompt} is sent, the empty string included,
     * as in the Python SDK; pass {@code null} to connect without one.
     *
     * <p>
     * {@code connect()}, {@link #disconnect()} and {@link #close()} are
     * serialized: each waits for one already running on another thread. A
     * {@code close()} that arrives while a connect is under way makes that
     * connect release what it set up and throw {@link IllegalStateException}.
     *
     * @param initialPrompt optional initial prompt to send
     * @throws CLIConnectionException if connection fails
     * @throws IllegalStateException  if client has been closed
     */
    public void connect(@Nullable String initialPrompt) throws CLIConnectionException {
        lifecycleLock.lock();
        try {
            connectLocked(initialPrompt);
        } finally {
            lifecycleLock.unlock();
        }
    }

    private void connectLocked(@Nullable String initialPrompt) {
        // Check if already closed
        if (closed.get()) {
            throw new IllegalStateException("Client is closed. Cannot connect.");
        }

        // If already connected, return early (idempotent)
        if (connected.get()) {
            logger.fine("Already connected, ignoring duplicate connect() call");
            return;
        }

        // Fail fast on invalid sessionStore option combinations before
        // spawning the subprocess.
        SessionStoreValidation.validate(options);

        // resume/continue + sessionStore: load the session from the store
        // into a temp CLAUDE_CONFIG_DIR for the subprocess to resume from.
        // Skipped when a custom transport was supplied — the materialized
        // options never reach a pre-constructed transport, so loading the
        // store and writing .credentials.json to a temp dir would be wasted
        // work. A failure here leaves nothing behind: materialization removes
        // its own temp dir before throwing.
        if (customTransport == null) {
            try {
                materialized = SessionResume.materializeResumeSession(options);
            } catch (java.io.IOException | RuntimeException e) {
                throw new CLIConnectionException(
                        "Failed to materialize session from session store: " + e.getMessage(), e);
            }
        }

        try {
            connectInner(initialPrompt);
        } catch (RuntimeException | Error e) {
            // Whatever failed (building the transport, e.g. a missing CLI;
            // spawning it; initialize, or its timeout; the initial prompt),
            // release everything set up so far: the query handler with its
            // threads and mirror batcher, the transport and its process, and
            // only then the temp CLAUDE_CONFIG_DIR they point at. Mirrors the
            // Python SDK, whose connect() calls disconnect() on any failure.
            releaseResources();
            throw e;
        }
        if (closed.get()) {
            // close() was called meanwhile and could not wait for us (its
            // thread was interrupted): nobody else will release this.
            releaseResources();
            throw new IllegalStateException("Client was closed while connecting");
        }
    }

    private void connectInner(@Nullable String initialPrompt) {
        // Validate permission settings and route prompts over the control protocol
        ClaudeAgentOptions effectiveOptions = CanUseToolConfig.configureCanUseTool(options);

        SessionResume.MaterializedResume resume = materialized;
        if (resume != null) {
            effectiveOptions = SessionResume.applyMaterializedOptions(effectiveOptions, resume);
        }

        // Use provided custom transport or create subprocess transport.
        // Assigned before connect() so a failure from here on closes it.
        Transport chosen = ((customTransport != null) ? customTransport : transportFactory.apply(effectiveOptions));
        transport = chosen;
        chosen.connect();

        // Calculate initialize timeout
        long timeoutMs = Long.parseLong(System.getenv().getOrDefault("CLAUDE_CODE_STREAM_CLOSE_TIMEOUT", "60000"));
        Duration initializeTimeout = Duration.ofMillis(Math.max(timeoutMs, 60000));

        // Extract SDK MCP servers from options
        Map<String, McpMessageHandler> sdkMcpServers = McpServers.extract(effectiveOptions);

        // Extract exclude_dynamic_sections from preset system prompt for the
        // initialize request (older CLIs ignore unknown initialize fields)
        Boolean excludeDynamicSections = extractExcludeDynamicSections(effectiveOptions);

        // Create QueryHandler
        QueryHandler q = new QueryHandler(
                chosen,
                true, // ClaudeSDKClient always uses streaming mode
                effectiveOptions.canUseTool(),
                effectiveOptions.hooks(),
                sdkMcpServers,
                effectiveOptions.agents(), // Agents sent via initialize request (no CLI flag)
                excludeDynamicSections,
                extractSystemPromptSnapshot(effectiveOptions),
                effectiveOptions.skills(),
                effectiveOptions.forwardSubagentText(),
                effectiveOptions.verbatimPrompts(),
                QueryHandler.runEndCeilingMs(effectiveOptions.env()),
                initializeTimeout,
                effectiveOptions.maxMsgQSize());
        query = q;

        // Wire the SessionStore mirror batcher BEFORE starting the read
        // loop so we don't miss any transcript_mirror frames the CLI emits
        // immediately on init.
        if (effectiveOptions.sessionStore() != null) {
            TranscriptMirrorBatcher batcher = SessionResume.buildMirrorBatcher(
                    effectiveOptions.sessionStore(),
                    resume,
                    effectiveOptions.env(),
                    q::reportMirrorError,
                    effectiveOptions.sessionStoreFlush());
            q.setTranscriptMirrorBatcher(batcher);
        }

        // Start reading messages and initialize
        q.start();
        q.initialize();

        // Send the initial prompt if provided (streaming mode doesn't send it
        // via the command line), the empty string included, as the Python
        // SDK does. Written directly with transport.write() rather than
        // sendMessage() so it is part of connection setup.
        if (initialPrompt != null) {
            Map<String, Object> message = userMessage(initialPrompt, "default");
            try {
                String json = MAPPER.writeValueAsString(
                        QueryHandler.stampUserMessage(message, effectiveOptions.verbatimPrompts()));
                chosen.write(json + "\n");
            } catch (JsonProcessingException e) {
                throw new CLIConnectionException("Failed to serialize initial prompt", e);
            }
        }

        // Connected only once everything above succeeded.
        connected.set(true);
    }

    /**
     * The message {@link #sendMessage(String, String)}, {@link #query(String)}
     * and {@link #connect(String)} write for a string prompt, with an explicit
     * {@code parent_tool_use_id: null} as the Python SDK sends it.
     */
    private static Map<String, Object> userMessage(String prompt, String sessionId) {
        Map<String, Object> messageContent = new LinkedHashMap<>();
        messageContent.put("role", "user");
        messageContent.put("content", prompt);
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "user");
        message.put("message", messageContent);
        message.put("parent_tool_use_id", null);
        message.put("session_id", sessionId);
        return message;
    }

    /**
     * Sends a text message to Claude.
     *
     * @param prompt the message to send
     * @throws CLIConnectionException if not connected or write fails
     */
    public void sendMessage(String prompt) throws CLIConnectionException {
        sendMessage(prompt, "default");
    }

    /**
     * Sends a text message to Claude with a specific session ID.
     *
     * @param prompt    the message to send
     * @param sessionId the session identifier
     * @throws CLIConnectionException if not connected or write fails
     * @throws IllegalStateException  if client is closed
     */
    @SuppressWarnings("null")
    public void sendMessage(String prompt, String sessionId) throws CLIConnectionException {
        ensureConnected();

        Map<String, Object> message = userMessage(prompt, sessionId);

        try {
            transport.write(MAPPER.writeValueAsString(
                    QueryHandler.stampUserMessage(message, options.verbatimPrompts())) + "\n");
        } catch (JsonProcessingException e) {
            throw new CLIConnectionException("Failed to serialize message", e);
        }
    }

    /**
     * Sends a query to Claude (string or message stream).
     *
     * <p>
     * This method is similar to Python's {@code client.query()} method.
     * It can accept either a simple string prompt or a stream of messages.
     *
     * <pre>{@code
     * // Simple string prompt
     * client.query("What is 2+2?");
     *
     * // Message stream
     * List<Map<String, Object>> messages = List.of(
     *         Map.of("type", "user", "session_id", "default",
     *                 "message", Map.of("role", "user", "content", "First message")),
     *         Map.of("type", "user", "session_id", "default",
     *                 "message", Map.of("role", "user", "content", "Follow-up")));
     * client.query(messages.iterator());
     * }</pre>
     *
     * @param prompt the prompt to send
     * @throws CLIConnectionException if not connected
     */
    public void query(String prompt) throws CLIConnectionException {
        query(prompt, "default");
    }

    /**
     * Sends a query to Claude with a specific session ID.
     *
     * @param prompt    the prompt to send
     * @param sessionId the session identifier
     * @throws CLIConnectionException if not connected
     */
    public void query(String prompt, String sessionId) throws CLIConnectionException {
        sendMessage(prompt, sessionId);
    }

    /**
     * Sends a stream of messages to Claude.
     *
     * <p>
     * Each message in the iterator should have the structure:
     *
     * <pre>{@code
     * {
     *     "type": "user",
     *     "session_id": "default",
     *     "message": {"role": "user", "content": "..."}
     * }
     * }</pre>
     *
     * <p>
     * {@code session_id} is filled in with {@code "default"} on any message
     * that omits it; supply it yourself, or use
     * {@link #query(Iterator, String)}, to address a specific session.
     *
     * <p>
     * Use this overload rather than {@link #query(String)} when a message needs
     * fields the string form does not build — {@code origin} attribution,
     * structured content blocks, an explicit {@code uuid}. Like
     * {@link #query(String)}, this leaves the CLI's stdin open, so it can be
     * called repeatedly across a session.
     *
     * <p>
     * The messages are written before this method returns, which keeps
     * successive calls in order. Drive a lazy or unbounded iterator from your
     * own thread if you need to read responses while it is still producing.
     *
     * @param messageStream an iterator of message dictionaries
     * @throws CLIConnectionException if not connected or a write fails
     * @throws IllegalStateException  if client is closed
     */
    public void query(Iterator<Map<String, Object>> messageStream) throws CLIConnectionException {
        query(messageStream, "default");
    }

    /**
     * Sends a stream of messages to Claude, addressed to a specific session.
     *
     * <p>
     * Messages are written verbatim except that {@code session_id} is filled in
     * with {@code sessionId} when a message omits it; the caller's maps are
     * never mutated. See {@link #query(Iterator)} for the full contract.
     *
     * @param messageStream an iterator of message dictionaries
     * @param sessionId     the session identifier to default onto each message
     * @throws CLIConnectionException if not connected or a write fails
     * @throws IllegalStateException  if client is closed
     */
    @SuppressWarnings("null")
    public void query(Iterator<Map<String, Object>> messageStream, String sessionId)
            throws CLIConnectionException {
        ensureConnected();

        while (messageStream.hasNext()) {
            Map<String, Object> message = messageStream.next();
            if (!message.containsKey("session_id")) {
                // Copy rather than mutate: the caller's map may be immutable,
                // and a query() call should not rewrite its inputs.
                Map<String, Object> withSession = new LinkedHashMap<>(message);
                withSession.put("session_id", sessionId);
                message = withSession;
            }
            try {
                transport.write(MAPPER.writeValueAsString(
                        QueryHandler.stampUserMessage(message, options.verbatimPrompts())) + "\n");
            } catch (JsonProcessingException e) {
                throw new CLIConnectionException("Failed to serialize streamed message", e);
            }
        }
    }

    /**
     * Returns an iterator over all messages from Claude.
     *
     * <p>
     * This iterator blocks until messages are available.
     *
     * <p>
     * This method can be called multiple times to create multiple iterators.
     * Each iterator reads from the same shared message queue, so messages will
     * be distributed across all active iterators.
     *
     * <p>
     * <b>Note:</b> For typical usage, you should create only one iterator per
     * client instance. Creating multiple iterators is supported but will cause
     * messages to be distributed unpredictably across the iterators.
     *
     * <p>
     * Usage:
     *
     * <pre>{@code
     * Iterator<Message> messages = client.receiveMessages();
     * while (messages.hasNext()) {
     *     Message msg = messages.next();
     *     // Process message
     * }
     * }</pre>
     *
     * <p>
     * If the consuming thread is interrupted while {@code hasNext()} waits for
     * a message, {@code hasNext()} restores the interrupt flag and throws a
     * {@link ClaudeSDKException} whose cause is the
     * {@link InterruptedException}; an interrupt never looks like the end of
     * the stream.
     *
     * @return an iterator over messages
     * @throws CLIConnectionException if not connected
     * @throws IllegalStateException  if client is closed
     */
    @SuppressWarnings("null")
    public Iterator<Message> receiveMessages() throws CLIConnectionException {
        ensureConnected();
        return query.receiveMessages();
    }

    /**
     * Returns an iterable over messages until and including a ResultMessage.
     *
     * <p>
     * This is a convenience method for single-response workflows.
     * The iteration terminates after yielding a ResultMessage.
     *
     * <p>
     * This method can be called multiple times. Each call creates a new iterator
     * that reads from the shared message queue. Messages will be distributed
     * across all active iterators.
     *
     * <p>
     * For multi-response workflows, use {@link #receiveMessages()} directly and
     * manually check for ResultMessage.
     *
     * <p>
     * An interrupt while waiting for a message is reported as in
     * {@link #receiveMessages()}: a {@link ClaudeSDKException} caused by the
     * {@link InterruptedException}, with the interrupt flag restored.
     *
     * <p>
     * Usage:
     *
     * <pre>{@code
     * // Single response workflow
     * for (Message msg : client.receiveResponse()) {
     *     if (msg instanceof AssistantMessage am) {
     *         System.out.println(am.getTextContent());
     *     }
     *     // Automatically stops after ResultMessage
     * }
     * }</pre>
     *
     * @return an iterable over response messages
     * @throws CLIConnectionException if not connected
     * @throws IllegalStateException  if client is closed
     */
    public Iterable<Message> receiveResponse() throws CLIConnectionException {
        ensureConnected();

        return () -> new Iterator<>() {

            private final Iterator<Message> inner = receiveMessages();

            private boolean done = false;
            @Nullable
            private Message next = null;

            @Override
            public boolean hasNext() {
                if (done) {
                    return false;
                }
                if (next != null) {
                    return true;
                }
                if (inner.hasNext()) {
                    next = inner.next();
                    return true;
                }
                return false;
            }

            @Override
            public Message next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                Message msg = next;
                next = null;
                if (msg instanceof ResultMessage) {
                    done = true;
                }
                return msg;
            }

        };
    }

    /**
     * Get current MCP server connection status (only works with streaming mode).
     * Queries the Claude Code CLI for the live connection status of all configured
     * MCP servers.
     *
     * @return typed MCP status response with a {@code mcpServers} list. Each entry includes:
     *         name, status ('connected', 'pending', 'failed', 'needs-auth', 'disabled'),
     *         serverInfo (when connected), error (when failed), config, scope, and tools.
     * @throws CLIConnectionException if not connected
     * @throws IllegalStateException  if client is closed
     */
    @SuppressWarnings("null")
    public McpStatusResponse getMcpStatus() throws CLIConnectionException {
        ensureConnected();
        return query.getMcpStatus();
    }

    /**
     * Gets a breakdown of current context window usage by category.
     *
     * <p>
     * Returns the same data shown by the {@code /context} command in the CLI,
     * including token counts per category, total usage, and detailed
     * breakdowns of MCP tools, memory files, and agents.
     *
     * @return context usage response with categories, totals, and breakdowns
     * @throws CLIConnectionException if not connected
     * @throws ClaudeSDKException     if the request fails
     */
    public ContextUsageResponse getContextUsage() {
        if (query == null) {
            throw new CLIConnectionException("Not connected. Call connect() first.");
        }
        return query.getContextUsage();
    }

    /**
     * Reconnects a disconnected or failed MCP server (only works with streaming
     * mode).
     *
     * <p>
     * Use this to retry connecting to an MCP server that failed to connect or was
     * disconnected.
     *
     * @param serverName the name of the MCP server to reconnect
     * @throws CLIConnectionException if not connected
     * @throws IllegalStateException  if client is closed
     */
    @SuppressWarnings("null")
    public void reconnectMcpServer(String serverName) throws CLIConnectionException {
        ensureConnected();
        query.reconnectMcpServer(serverName);
    }

    /**
     * Enables or disables an MCP server (only works with streaming mode).
     *
     * <p>
     * Disabling a server disconnects it and removes its tools from the available tool
     * set. Enabling a server reconnects it and makes its tools available again.
     *
     * @param serverName the name of the MCP server to toggle
     * @param enabled    true to enable the server, false to disable it
     * @throws CLIConnectionException if not connected
     * @throws IllegalStateException  if client is closed
     */
    @SuppressWarnings("null")
    public void toggleMcpServer(String serverName, boolean enabled) throws CLIConnectionException {
        ensureConnected();
        query.toggleMcpServer(serverName, enabled);
    }

    /**
     * Stops a running task (only works with streaming mode).
     *
     * <p>
     * After this resolves, the CLI reports the task's end in the message stream
     * as a {@code TaskUpdatedMessage} whose status is terminal ({@code "killed"}
     * for a stopped task). A {@code TaskNotificationMessage} with status
     * {@code "stopped"} may follow, but is sometimes suppressed, so clear the
     * task id on a terminal status from either message (see
     * {@code TaskUpdatedMessage.TERMINAL_TASK_STATUSES}).
     *
     * @param taskId the task ID from the {@code TaskStartedMessage}
     * @throws CLIConnectionException if not connected
     * @throws IllegalStateException  if client is closed
     */
    @SuppressWarnings("null")
    public void stopTask(String taskId) throws CLIConnectionException {
        ensureConnected();
        query.stopTask(taskId);
    }

    /**
     * Sends an interrupt signal to stop the current operation.
     *
     * @throws CLIConnectionException if not connected
     * @throws IllegalStateException  if client is closed
     */
    @SuppressWarnings("null")
    public void interrupt() throws CLIConnectionException {
        ensureConnected();
        query.interrupt();
    }

    /**
     * Changes the permission mode during conversation.
     *
     * @param mode the new permission mode ("default", "acceptEdits",
     *             "bypassPermissions")
     * @throws CLIConnectionException if not connected
     * @throws IllegalStateException  if client is closed
     */
    @SuppressWarnings("null")
    public void setPermissionMode(PermissionMode mode) throws CLIConnectionException {
        ensureConnected();
        query.setPermissionMode(mode);
    }

    /**
     * Changes the AI model during conversation.
     *
     * @param model the model to use, or null for default, e.g. 'claude-sonnet-5'
     * @throws CLIConnectionException if not connected
     * @throws IllegalStateException  if client is closed
     */
    @SuppressWarnings("null")
    public void setModel(@Nullable String model) throws CLIConnectionException {
        ensureConnected();
        query.setModel(model);
    }

    /**
     * Rewinds tracked files to their state at a specific user message.
     *
     * <p>
     * Requires {@code enableFileCheckpointing(true)} to track file changes,
     * and {@code extraArgs(Map.of("replay-user-messages", ""))} to receive
     * {@code UserMessage} objects with a {@code uuid} in the response stream.
     *
     * @param userMessageId UUID of the user message to rewind to
     * @throws CLIConnectionException if not connected
     * @throws IllegalStateException  if client is closed
     */
    @SuppressWarnings("null")
    public void rewindFiles(String userMessageId) throws CLIConnectionException {
        ensureConnected();
        query.rewindFiles(userMessageId);
    }

    /**
     * Gets server initialization info including available commands and output
     * styles.
     *
     * Returns initialization information from the Claude Code server including:
     * - Available commands (slash commands, system commands, etc.)
     * - Current and available output styles
     * - Server capabilities
     *
     * @return server info from the initialize response ({@code {}} when the
     *         CLI's response carried no body)
     * @throws CLIConnectionException if not connected
     * @throws IllegalStateException  if client is closed
     */
    @Nullable
    @SuppressWarnings("null")
    public Map<String, Object> getServerInfo() throws CLIConnectionException {
        ensureConnected();
        return query.getInitializationResult();
    }

    /**
     * Disconnects from Claude Code and releases resources.
     *
     * <p>
     * This method closes the QueryHandler (which closes the transport) and
     * clears all cached state. It is called by {@link #close()} and can be
     * called directly.
     *
     * <p>
     * This method is safe to call multiple times (idempotent), and from
     * inside a callback ({@code canUseTool}, a hook, an SDK MCP tool): the
     * request that callback is serving is then left unanswered, and the CLI
     * still gets stdin EOF and its grace period to exit. It is also safe with
     * the calling thread's interrupt flag set; the flag is preserved.
     *
     * <p>
     * It is serialized with {@link #connect()} and {@link #close()}: a call
     * made while another thread connects or disconnects waits for it, so the
     * temporary {@code CLAUDE_CONFIG_DIR} of a store-backed resume is only
     * removed after the CLI using it has exited. If the calling thread is
     * interrupted during that wait (as {@code disconnect()} from a callback is
     * when another thread is already disconnecting and cancels that
     * callback), it returns at once with the interrupt flag set and leaves
     * the work to the other thread.
     */
    public void disconnect() {
        // tryLock() first: an uncontended disconnect must run even when the
        // caller's interrupt flag is already set.
        if (!lifecycleLock.tryLock()) {
            try {
                lifecycleLock.lockInterruptibly();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.fine("Interrupted waiting for a concurrent connect/disconnect; leaving it to that thread");
                return;
            }
        }
        try {
            releaseResources();
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * Releases whatever a connect set up, connected or not, so it also runs
     * for a connect that failed partway. Idempotent: each resource is
     * dropped once released. Called with {@link #lifecycleLock} held.
     */
    private void releaseResources() {
        // Close QueryHandler (which closes the transport, the mirror batcher
        // and its own threads)
        QueryHandler q = query;
        Transport t = transport;
        query = null;
        transport = null;
        if (q != null) {
            try {
                q.close();
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error closing QueryHandler", e);
            }
        } else if (t != null) {
            // The transport was built (and maybe spawned) but the query
            // handler that owns closing it was not.
            try {
                t.close();
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error closing transport", e);
            }
        }

        // Clean up materialized resume temp dir AFTER the subprocess/reader
        // have closed so we don't pull the rug out from under them.
        SessionResume.MaterializedResume m = materialized;
        materialized = null;
        if (m != null) {
            try {
                m.cleanup();
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error cleaning up materialized resume temp dir", e);
            }
        }

        // Reset connection flag
        connected.set(false);
    }

    /**
     * Closes the client and releases all resources.
     *
     * <p>
     * This method is thread-safe and idempotent. Multiple concurrent calls are
     * safe, and subsequent calls after the first are no-ops.
     *
     * <p>
     * Shutdown sequence:
     * <ol>
     * <li>Set closed flag (atomic)</li>
     * <li>Close QueryHandler (which closes transport and shuts down executors)</li>
     * <li>Clear all cached state</li>
     * </ol>
     */
    @Override
    public void close() {
        // Use atomic getAndSet for thread-safe idempotent close
        if (closed.getAndSet(true)) {
            return; // Already closed
        }

        logger.fine("Closing ClaudeSDKClient");
        disconnect();
        logger.fine("ClaudeSDKClient closed");
    }

    /**
     * Checks if the client is connected and not closed.
     *
     * @return true if connected and not closed
     */
    public boolean isConnected() {
        return (connected.get() && (!closed.get()) && (query != null) && (transport != null) && transport.isReady());
    }

    /**
     * Ensures the client is connected and not closed.
     *
     * @throws IllegalStateException  if client is closed
     * @throws CLIConnectionException if not connected
     */
    private void ensureConnected() throws CLIConnectionException {
        if (closed.get()) {
            throw new IllegalStateException("Client is closed");
        }
        if ((!connected.get()) || (query == null) || (transport == null)) {
            throw new CLIConnectionException("Not connected. Call connect() first.");
        }
    }

    /**
     * Extracts exclude_dynamic_sections from a preset system prompt.
     *
     * @return the boolean value if set, or null
     */
    @Nullable
    private static Boolean extractExcludeDynamicSections(ClaudeAgentOptions options) {
        Object systemPrompt = options.systemPrompt();
        if (systemPrompt instanceof SystemPromptPreset preset) {
            return preset.excludeDynamicSections();
        }
        return null;
    }

    /**
     * Extracts {@code snapshot} from a preset or custom system prompt for the
     * initialize request (older CLIs ignore unknown initialize fields).
     *
     * @return the boolean value if set, or null
     */
    @Nullable
    private static Boolean extractSystemPromptSnapshot(ClaudeAgentOptions options) {
        Object systemPrompt = options.systemPrompt();
        if (systemPrompt instanceof SystemPromptPreset preset) {
            return preset.snapshot();
        }
        if (systemPrompt instanceof SystemPromptCustom custom) {
            return custom.snapshot();
        }
        return null;
    }

}
