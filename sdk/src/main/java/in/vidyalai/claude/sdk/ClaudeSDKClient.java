package in.vidyalai.claude.sdk;

import java.time.Duration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import in.vidyalai.claude.sdk.exceptions.CLIConnectionException;
import in.vidyalai.claude.sdk.internal.QueryHandler;
import in.vidyalai.claude.sdk.internal.transport.SubprocessCLITransport;
import in.vidyalai.claude.sdk.mcp.SdkMcpServer;
import in.vidyalai.claude.sdk.transport.Transport;
import in.vidyalai.claude.sdk.types.mcp.McpSdkServerConfig;
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
 * <li>Streaming ExecutorService for background message streaming</li>
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

    // Thread safety: AtomicBoolean for state management
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    // Executor service for streaming input in background
    private volatile ExecutorService streamingExecutor;

    // Thread safety: Volatile for visibility across threads
    @Nullable
    private volatile Transport transport;
    @Nullable
    private volatile QueryHandler query;

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
     * Creates a new client with custom transport (for testing).
     *
     * @param options   the agent options
     * @param transport custom transport implementation
     */
    public ClaudeSDKClient(ClaudeAgentOptions options, @Nullable Transport transport) {
        this.options = options;
        this.customTransport = transport;

        // Set entrypoint for tracking (matches Python SDK)
        System.setProperty("CLAUDE_CODE_ENTRYPOINT", "sdk-java-client");
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
     * @param initialPrompt optional initial prompt to send
     * @throws CLIConnectionException if connection fails
     * @throws IllegalStateException  if client has been closed
     */
    @SuppressWarnings("null")
    public synchronized void connect(@Nullable String initialPrompt) throws CLIConnectionException {
        // Check if already closed
        if (closed.get()) {
            throw new IllegalStateException("Client is closed. Cannot connect.");
        }

        // If already connected, return early (idempotent)
        if (connected.get()) {
            logger.fine("Already connected, ignoring duplicate connect() call");
            return;
        }

        // Create executor service for streaming tasks with named virtual threads
        this.streamingExecutor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual()
                        .name("ClaudeSDKClient-Streaming-", 0)
                        .factory());

        // Validate permission settings
        ClaudeAgentOptions effectiveOptions = options;
        if (options.canUseTool() != null) {
            if (options.permissionPromptToolName() != null) {
                throw new IllegalArgumentException(
                        "canUseTool callback cannot be used with permissionPromptToolName. " +
                                "Please use one or the other.");
            }
            // Automatically set permission_prompt_tool_name to "stdio" for control protocol
            effectiveOptions = options.withPermissionPromptToolName("stdio");
        }

        // Use provided custom transport or create subprocess transport
        if (customTransport != null) {
            transport = customTransport;
        } else {
            transport = new SubprocessCLITransport(initialPrompt, true, effectiveOptions);
        }

        transport.connect();

        // Calculate initialize timeout
        long timeoutMs = Long.parseLong(System.getenv().getOrDefault("CLAUDE_CODE_STREAM_CLOSE_TIMEOUT", "60000"));
        Duration initializeTimeout = Duration.ofMillis(Math.max(timeoutMs, 60000));

        // Extract SDK MCP servers from options
        Map<String, SdkMcpServer> sdkMcpServers = extractSdkMcpServers(effectiveOptions);

        // Create QueryHandler
        query = new QueryHandler(
                transport,
                true, // ClaudeSDKClient always uses streaming mode
                effectiveOptions.canUseTool(),
                effectiveOptions.hooks(),
                sdkMcpServers,
                initializeTimeout,
                effectiveOptions.maxMsgQSize());

        // Start reading messages and initialize
        query.start();
        query.initialize();

        // Mark as connected
        connected.set(true);

        // Send initial prompt if provided (streaming mode doesn't send it via command
        // line)
        if ((initialPrompt != null) && (!initialPrompt.isBlank())) {
            sendMessage(initialPrompt);
        }
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

        Map<String, Object> message = new HashMap<>();
        message.put("type", "user");
        message.put("session_id", sessionId);

        Map<String, Object> innerMessage = new HashMap<>();
        innerMessage.put("role", "user");
        innerMessage.put("content", prompt);
        message.put("message", innerMessage);

        try {
            transport.write(MAPPER.writeValueAsString(message) + "\n");
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
     * NOTE: Ensure that session_id is set correctly in each message
     *
     * <pre>{@code
     * {
     *     "type": "user",
     *     "session_id": "default",
     *     "message": {"role": "user", "content": "..."}
     * }
     * }</pre>
     *
     * @param messageStream an iterator of message dictionaries
     * @throws CLIConnectionException if not connected
     * @throws IllegalStateException  if client is closed
     */
    @SuppressWarnings("null")
    public void query(Iterator<Map<String, Object>> messageStream) throws CLIConnectionException {
        ensureConnected();

        // Stream messages in background using executor service
        streamingExecutor.submit(() -> query.streamInput(messageStream));
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
     * @return Map with MCP server status information. Contains a 'mcpServers' key
     *         with a list of server status objects, each having:
     *         - 'name': Server name (str)
     *         - 'status': Connection status ('connected', 'pending', 'failed',
     *         'needs-auth', 'disabled')
     * 
     * @throws CLIConnectionException if not connected
     * @throws IllegalStateException  if client is closed
     */
    @SuppressWarnings("null")
    public Map<String, Object> getMcpStatus() throws CLIConnectionException {
        ensureConnected();
        return query.getMcpStatus();
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
     * @param model the model to use, or null for default e.g. 'claude-sonnet-4-5'
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
     * Requires file checkpointing to be enabled via the
     * {@code enableFileCheckpointing} option AND
     * `extra_args={"replay-user-messages": None}` to receive UserMessage
     * objects with `uuid` in the response stream
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
     * @return initialization info, or null if not in streaming mode
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
     * This method closes the QueryHandler (which closes the transport), shuts down
     * the streaming executor, and clears all cached state. It is called by
     * {@link #close()} and can be called directly.
     *
     * <p>
     * This method is safe to call multiple times (idempotent).
     */
    public void disconnect() {
        if (!connected.get()) {
            return;
        }

        // Close QueryHandler (which will close transport)
        if (query != null) {
            try {
                query.close();
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error closing QueryHandler", e);
            }
            query = null;
        }

        // Shutdown streaming executor gracefully
        shutdownStreamingExecutor();

        // Clear cached state
        transport = null;

        // Reset connection flag
        connected.set(false);
    }

    /**
     * Shuts down the streaming executor gracefully.
     */
    private void shutdownStreamingExecutor() {
        streamingExecutor.shutdown();
        try {
            if (!streamingExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                logger.fine("Streaming executor did not terminate gracefully, forcing shutdown");
                streamingExecutor.shutdownNow();
                boolean terminated = streamingExecutor.awaitTermination(2, TimeUnit.SECONDS);
                if (!terminated) {
                    logger.warning("Streaming executor did not terminate even after shutdownNow()");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            streamingExecutor.shutdownNow();
        } finally {
            streamingExecutor = null;
        }
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
     * Extracts SDK MCP servers from the options' mcpServers map.
     */
    @Nullable
    private static Map<String, SdkMcpServer> extractSdkMcpServers(ClaudeAgentOptions options) {
        Object mcpServers = options.mcpServers();
        if (mcpServers == null) {
            return null;
        }

        // Only handle Map<String, McpServerConfig> - other types (Path, String) are
        // handled by CLI
        if (!(mcpServers instanceof Map<?, ?> serverMap)) {
            return null;
        }

        if (serverMap.isEmpty()) {
            return null;
        }

        Map<String, SdkMcpServer> sdkServers = new HashMap<>();
        for (Map.Entry<?, ?> entry : serverMap.entrySet()) {
            if (entry.getValue() instanceof McpSdkServerConfig sdkConfig) {
                sdkServers.put((String) entry.getKey(), sdkConfig.instance());
            }
        }

        return (sdkServers.isEmpty() ? null : sdkServers);
    }

}
