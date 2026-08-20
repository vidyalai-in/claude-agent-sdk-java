package in.vidyalai.claude.sdk.internal;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
import in.vidyalai.claude.sdk.exceptions.ResultException;
import in.vidyalai.claude.sdk.mcp.SdkMcpServer;
import in.vidyalai.claude.sdk.transport.Transport;
import in.vidyalai.claude.sdk.types.config.AgentDefinition;
import in.vidyalai.claude.sdk.types.control.request.SDKControlInitializeRequest;
import in.vidyalai.claude.sdk.types.control.request.SDKControlInterruptRequest;
import in.vidyalai.claude.sdk.types.control.request.SDKControlMCPStatusRequest;
import in.vidyalai.claude.sdk.types.control.request.SDKControlMcpMessageRequest;
import in.vidyalai.claude.sdk.types.control.request.SDKControlMcpReconnectRequest;
import in.vidyalai.claude.sdk.types.control.request.SDKControlMcpToggleRequest;
import in.vidyalai.claude.sdk.types.control.request.SDKControlPermissionRequest;
import in.vidyalai.claude.sdk.types.control.request.SDKControlRequest;
import in.vidyalai.claude.sdk.types.control.request.SDKControlRequestData;
import in.vidyalai.claude.sdk.types.control.request.SDKControlRewindFilesRequest;
import in.vidyalai.claude.sdk.types.control.request.SDKControlSetModelRequest;
import in.vidyalai.claude.sdk.types.control.request.SDKControlSetPermissionModeRequest;
import in.vidyalai.claude.sdk.types.control.request.SDKControlStopTaskRequest;
import in.vidyalai.claude.sdk.types.control.request.SDKHookCallbackRequest;
import in.vidyalai.claude.sdk.types.control.response.ControlErrorResponse;
import in.vidyalai.claude.sdk.types.control.response.ControlResponse;
import in.vidyalai.claude.sdk.types.control.response.ControlResponseData;
import in.vidyalai.claude.sdk.types.control.response.SDKControlResponse;
import in.vidyalai.claude.sdk.types.hook.HookContext;
import in.vidyalai.claude.sdk.types.hook.HookEvent;
import in.vidyalai.claude.sdk.types.hook.HookMatcher;
import in.vidyalai.claude.sdk.types.hook.input.HookInput;
import in.vidyalai.claude.sdk.types.hook.output.HookOutput;
import in.vidyalai.claude.sdk.types.control.request.SDKControlGetContextUsageRequest;
import in.vidyalai.claude.sdk.types.mcp.ContextUsageResponse;
import in.vidyalai.claude.sdk.types.mcp.McpStatusResponse;
import in.vidyalai.claude.sdk.types.message.Message;
import in.vidyalai.claude.sdk.types.message.TaskUpdatedMessage;
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

    /**
     * Task types whose completion runs a follow-up turn, and which therefore may
     * still need the control channel after the turn's result frame.
     *
     * <p>
     * This mirrors the set the CLI itself holds a result back for, which is
     * narrower than its notion of "delegated agent work". The types left out are
     * left out on purpose, and none is merely an oversight: background shells
     * and monitors run indefinitely by design, so deferring the close on one
     * withholds it forever rather than briefly; teammates are long-lived too,
     * with a status that stays running for their whole lifetime, so they never
     * settle the ledger; remote agents can be long-running monitors the CLI
     * likewise refuses to wait on. Anything added here must be a type that
     * reliably reaches a terminal status, or it will hang the query — see
     * {@link #trackTaskLifecycle}.
     */
    private static final Set<String> DEFERRING_TASK_TYPES =
            Set.of("local_agent", "local_workflow");
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
    @Nullable
    private final Map<String, AgentDefinition> agents;
    @Nullable
    private final Boolean excludeDynamicSections;
    /**
     * Skills allowlist sent on the initialize control request. Either
     * {@code null}, the string {@code "all"}, or a {@code List<String>} of
     * skill names. Only explicit lists are forwarded over the wire — both
     * {@code null} and {@code "all"} omit the field (the CLI treats omission
     * as "no filter").
     */
    @Nullable
    private final Object skills;
    /**
     * Whether to ask the CLI, via initialize, to forward subagent text and
     * thinking blocks in addition to {@code tool_use}/{@code tool_result}.
     * Only sent over the wire when true; older CLIs ignore unknown fields.
     */
    private final boolean forwardSubagentText;
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

    // Completed when a run-ending result arrives (a result frame with no tasks
    // in flight) so the stdin-closing waiter can wake. Named for history — it
    // once tracked the literal first result.
    private final CompletableFuture<Void> firstResultEvent = new CompletableFuture<>();
    private final Duration streamCloseTimeout;

    // Task IDs of started-but-not-finished tasks. A result frame ends one turn,
    // not the run: background tasks keep running past it and still need stdin
    // for hook/SDK-MCP control responses, so a result arriving while this set is
    // non-empty must not close stdin. Only touched from the reader thread, but
    // kept concurrent because close() may inspect it from another thread.
    private final Set<String> inflightTasks = ConcurrentHashMap.newKeySet();

    // SessionStore mirroring (set via setTranscriptMirrorBatcher)
    @Nullable
    private volatile TranscriptMirrorBatcher transcriptMirrorBatcher;

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
        this(transport, isStreamingMode, canUseTool, hooks, null, null, null, null, initializeTimeout, DEFAULT_MSG_Q_SIZE);
    }

    /**
     * Creates a new QueryHandler with SDK MCP server support.
     *
     * @param transport         the transport for I/O
     * @param isStreamingMode   whether using streaming (bidirectional) mode
     * @param canUseTool        optional callback for tool permission requests (may
     *                          be null)
     * @param hooks             optional hook configurations
     * @param sdkMcpServers          optional SDK MCP servers for in-process tool
     *                                 execution
     * @param agents                   optional agent definitions to send via initialize
     *                                 request
     * @param excludeDynamicSections   optional preset-prompt flag for cross-user caching
     * @param skills                   optional skill allowlist sent via initialize so the CLI can
     *                                 filter which skills are loaded into the system prompt
     *                                 ({@code null}, the string {@code "all"}, or a {@code List<String>})
     * @param initializeTimeout        timeout for the initialize request
     * @param maxMsgQSize              max message queue size
     */
    public QueryHandler(
            Transport transport,
            boolean isStreamingMode,
            ClaudeAgentOptions.CanUseTool canUseTool, // may be null
            @Nullable Map<HookEvent, List<HookMatcher>> hooks,
            @Nullable Map<String, SdkMcpServer> sdkMcpServers,
            @Nullable Map<String, AgentDefinition> agents,
            @Nullable Boolean excludeDynamicSections,
            @Nullable Object skills,
            Duration initializeTimeout,
            @Nullable Integer maxMsgQSize) {
        this(transport, isStreamingMode, canUseTool, hooks, sdkMcpServers, agents,
                excludeDynamicSections, skills, false, initializeTimeout, maxMsgQSize);
    }

    /**
     * Creates a new QueryHandler with SDK MCP server support and subagent text
     * forwarding.
     *
     * @param transport                the transport for I/O
     * @param isStreamingMode          whether using streaming (bidirectional) mode
     * @param canUseTool               optional callback for tool permission requests (may
     *                                 be null)
     * @param hooks                    optional hook configurations
     * @param sdkMcpServers            optional SDK MCP servers for in-process tool
     *                                 execution
     * @param agents                   optional agent definitions to send via initialize
     *                                 request
     * @param excludeDynamicSections   optional preset-prompt flag for cross-user caching
     * @param skills                   optional skill allowlist sent via initialize so the CLI can
     *                                 filter which skills are loaded into the system prompt
     *                                 ({@code null}, the string {@code "all"}, or a {@code List<String>})
     * @param forwardSubagentText      ask the CLI (via initialize) to forward subagent
     *                                 text/thinking blocks, not just tool_use/tool_result
     * @param initializeTimeout        timeout for the initialize request
     * @param maxMsgQSize              max message queue size
     */
    public QueryHandler(
            Transport transport,
            boolean isStreamingMode,
            ClaudeAgentOptions.CanUseTool canUseTool, // may be null
            @Nullable Map<HookEvent, List<HookMatcher>> hooks,
            @Nullable Map<String, SdkMcpServer> sdkMcpServers,
            @Nullable Map<String, AgentDefinition> agents,
            @Nullable Boolean excludeDynamicSections,
            @Nullable Object skills,
            boolean forwardSubagentText,
            Duration initializeTimeout,
            @Nullable Integer maxMsgQSize) {
        this.transport = transport;
        this.isStreamingMode = isStreamingMode;
        this.canUseTool = canUseTool;
        this.hooks = hooks;
        this.sdkMcpServers = sdkMcpServers;
        this.agents = agents;
        this.excludeDynamicSections = excludeDynamicSections;
        this.skills = skills;
        this.forwardSubagentText = forwardSubagentText;
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

            // Send initialize request with agents (sent via stdin, no size limit)
            // This matches the Python SDK behavior where agents are always sent via
            // the initialize request instead of CLI flags to avoid ARG_MAX limits
            // 'all' and omitted are equivalent at the wire level (no
            // filter), so only send the field when it's an explicit list.
            @SuppressWarnings("unchecked")
            List<String> skillsForWire = (skills instanceof List<?>)
                    ? (List<String>) skills
                    : null;

            SDKControlInitializeRequest request = new SDKControlInitializeRequest(
                    hooksConfig.isEmpty() ? null : hooksConfig,
                    ((agents == null) || agents.isEmpty()) ? null : agents,
                    excludeDynamicSections,
                    skillsForWire,
                    forwardSubagentText ? Boolean.TRUE : null);

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
            // Keep an already-typed SDK failure intact (e.g. the
            // ResultException built from an error result the CLI emitted
            // before answering this initialize) so the caller can branch on
            // it; only opaque failures get the wrapper.
            if (e instanceof ClaudeSDKException sdkError) {
                throw sdkError;
            }
            throw new ClaudeSDKException("Failed to initialize: " + e.getMessage(), e);
        }
    }

    /**
     * Attach a {@link TranscriptMirrorBatcher} that receives
     * {@code transcript_mirror} frames.
     *
     * <p>When set, the read loop peels {@code transcript_mirror} frames off
     * stdout (they are not yielded to consumers), enqueues them on the
     * batcher, and flushes before yielding each {@code result} message.
     */
    public void setTranscriptMirrorBatcher(TranscriptMirrorBatcher batcher) {
        this.transcriptMirrorBatcher = batcher;
    }

    /**
     * Surface a {@link in.vidyalai.claude.sdk.types.session.SessionStore#append}
     * failure as a {@code mirror_error} system message in the consumer stream.
     *
     * <p>Called from the batcher's {@code onError}; the dropped batch is not
     * retried (at-most-once delivery), so this is the consumer's only signal.
     * Non-blocking — if the message buffer is full the error is logged and
     * dropped rather than back-pressuring the read loop.
     */
    public void reportMirrorError(in.vidyalai.claude.sdk.types.session.@Nullable SessionKey key, String error) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "system");
        msg.put("subtype", "mirror_error");
        msg.put("error", error);
        if (key != null) {
            Map<String, Object> keyMap = new HashMap<>();
            keyMap.put("project_key", key.projectKey());
            keyMap.put("session_id", key.sessionId());
            if (key.subpath() != null) {
                keyMap.put("subpath", key.subpath());
            }
            msg.put("key", keyMap);
            msg.put("session_id", key.sessionId());
        } else {
            msg.put("session_id", "");
        }
        msg.put("uuid", java.util.UUID.randomUUID().toString());
        if (!messageQueue.offer(msg)) {
            logger.warning("Dropping mirror_error message (buffer full)");
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
            try {
                readerExecutor.submit(this::readMessages);
            } catch (RejectedExecutionException e) {
                // Executor was shut down between closed check and submit
                // This can happen in concurrent start()/close() scenarios
                readerStarted.set(false); // Reset state
                throw new IllegalStateException("QueryHandler is closed", e);
            }
        }
        // If already started, this is a no-op (idempotent)
    }

    /**
     * Tracks in-flight tasks from {@code system} task lifecycle frames.
     *
     * <p>
     * {@code task_started} marks a task in flight; {@code task_notification} or
     * a {@code task_updated} patch with a terminal status clears it. Terminal
     * completion can arrive as either frame (not every terminal task emits a
     * notification), so both are handled, and removal is idempotent.
     *
     * <p>
     * This is a mitigation, not a complete answer. An empty set means "nothing
     * we know of is running", which is not the same as "the run is over": a task
     * that settles <i>before</i> the turn's result frame leaves the set empty at
     * that result, so stdin closes even though the completion may still wake the
     * parent for a continuation turn. No ledger can close that gap, because it
     * cannot distinguish a settled task whose continuation is pending from no
     * work at all — that needs a run-boundary signal from the CLI. What this
     * does fix is the common ordering, where the task outlives the turn that
     * spawned it.
     *
     * <p>
     * Only delegated agent work is tracked ({@link #DEFERRING_TASK_TYPES}). A
     * background <i>shell</i> — {@code Bash(run_in_background=true)} on a dev
     * server or {@code tail -f} — is reported through these frames too, but it
     * may never reach a terminal status, and the CLI in stream-json mode only
     * exits on stdin EOF. Tracking one would therefore withhold the close
     * forever rather than briefly: no terminal frame, no process exit, so not
     * even the reader's {@code finally} runs.
     *
     * <p>
     * {@code background_tasks_changed} is deliberately not consumed, in either
     * direction. Its payload is the live <i>background</i> set, while a subagent
     * is registered in the foreground and only flips to backgrounded later,
     * without a second {@code task_started}. Narrowing against it would drop an
     * agent that goes on to outlive its turn — the very bug this prevents.
     * Widening from it is no better: the snapshot spans every background task
     * type and carries nothing marking an observer agent, whose start and
     * terminal frames are both suppressed, so it could admit an id no later
     * frame ever clears.
     *
     * @param message the system message to inspect
     */
    private void trackTaskLifecycle(Map<String, Object> message) {
        if (!(message.get("task_id") instanceof String taskId) || taskId.isEmpty()) {
            return;
        }
        Object subtype = message.get("subtype");
        if ("task_started".equals(subtype)) {
            if (DEFERRING_TASK_TYPES.contains(message.get("task_type"))) {
                inflightTasks.add(taskId);
            }
        } else if ("task_notification".equals(subtype)) {
            inflightTasks.remove(taskId);
        } else if ("task_updated".equals(subtype)) {
            if (message.get("patch") instanceof Map<?, ?> patch
                    && patch.get("status") instanceof String status
                    && TaskUpdatedMessage.TERMINAL_TASK_STATUSES.contains(status)) {
                inflightTasks.remove(taskId);
            }
        }
    }

    /**
     * Picks the most informative text from a {@code result} frame with
     * {@code is_error}.
     *
     * <p>
     * Terminal errors the CLI raises itself ({@code error_max_turns},
     * {@code error_during_execution}, ...) carry their prose in
     * {@code errors[]}. A run that ends on an API failure instead arrives as
     * {@code subtype: "success"} with {@code is_error: true}, an empty
     * {@code errors[]} and the "API Error: ..." prose in {@code result} —
     * falling back to the subtype there produced the self-contradictory
     * "Claude Code returned an error result: success". Prefer {@code errors[]},
     * then {@code result}, then a non-success {@code subtype}, then the HTTP
     * status.
     */
    static String errorResultText(Map<String, Object> message) {
        List<String> errors = ResultException.normalizeErrors(message.get("errors"));
        if (!errors.isEmpty()) {
            return String.join("; ", errors);
        }
        if (message.get("result") instanceof String result && !result.isBlank()) {
            return result.strip();
        }
        if (message.get("subtype") instanceof String subtype
                && !subtype.isEmpty() && !"success".equals(subtype)) {
            return subtype;
        }
        Object status = message.get("api_error_status");
        if (status != null) {
            return "API error (HTTP " + status + ")";
        }
        return "unknown error";
    }

    private void readMessages() {
        // Tracks the most recent error result's payload so we can replace the
        // generic "Command failed with exit code 1" ProcessException that
        // the CLI raises after emitting an error result with a typed
        // ResultException. Mirrors the TypeScript and Python SDKs'
        // lastErrorResultText / _last_error_result.
        AtomicReference<Map<String, Object>> lastErrorResult = new AtomicReference<>();
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
                    // (the Python SDK cancels asyncio tasks; in Java, the control
                    // executor handles each request in its own virtual thread and
                    // we currently don't track them individually for cancellation)
                    continue;
                } else if ("transcript_mirror".equals(msgType)) {
                    // SessionStore write path: peel mirror frames off stdout
                    // and hand to the batcher; do NOT yield to consumers.
                    TranscriptMirrorBatcher batcher = transcriptMirrorBatcher;
                    if (batcher != null) {
                        Object filePath = message.get("filePath");
                        Object entriesObj = message.get("entries");
                        if (filePath instanceof String fp && entriesObj instanceof List<?> entriesList) {
                            List<in.vidyalai.claude.sdk.types.session.SessionStoreEntry> entries = new ArrayList<>();
                            for (Object item : entriesList) {
                                if (item instanceof Map<?, ?> m) {
                                    @SuppressWarnings("unchecked")
                                    Map<String, Object> em = (Map<String, Object>) m;
                                    try {
                                        entries.add(in.vidyalai.claude.sdk.types.session.SessionStoreEntry.of(em));
                                    } catch (RuntimeException re) {
                                        logger.fine("Skipping malformed transcript_mirror entry: " + re.getMessage());
                                    }
                                }
                            }
                            if (!entries.isEmpty()) {
                                batcher.enqueue(fp, entries);
                            }
                        }
                    }
                    continue;
                }

                // Track task lifecycle frames so a result can tell "one turn
                // ended" apart from "the run is done".
                if ("system".equals(msgType)) {
                    trackTaskLifecycle(message);
                }

                // Track results for proper stream closure
                if ("result".equals(msgType)) {
                    // Flush pending transcript mirror entries before yielding
                    // result so consumers observing the result can rely on the
                    // SessionStore being up to date for this turn.
                    TranscriptMirrorBatcher batcher = transcriptMirrorBatcher;
                    if (batcher != null) {
                        try {
                            batcher.flush().get(5, TimeUnit.SECONDS);
                        } catch (Exception flushEx) {
                            logger.fine("Mirror flush before result completed exceptionally: "
                                    + flushEx.getMessage());
                        }
                    }
                    if (inflightTasks.isEmpty()) {
                        firstResultEvent.complete(null);
                    } else {
                        // One turn ended, but background tasks are still running
                        // and may need hook/SDK-MCP control responses over stdin.
                        // Closing it now silently disables hooks and fails
                        // SDK-MCP calls with "Stream closed". Each task
                        // completion wakes the parent for a follow-up turn, so a
                        // later result frame arrives with no tasks in flight and
                        // closes stdin then.
                        logger.fine("Result received with " + inflightTasks.size()
                                + " task(s) in flight; keeping stdin open");
                    }
                    Object isErr = message.get("is_error");
                    if (isErr instanceof Boolean b && b) {
                        lastErrorResult.set(message);
                    } else {
                        lastErrorResult.set(null);
                    }
                } else if (!("system".equals(msgType)
                        && "session_state_changed".equals(message.get("subtype")))) {
                    // Anything other than the post-turn session_state_changed
                    // marker means the conversation moved on; a ProcessException
                    // now is a fresh crash, not the expected exit from a prior
                    // error result. Mirrors the TS/Python SDK reset logic.
                    lastErrorResult.set(null);
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
                // When the CLI emits a result with is_error=true (e.g.
                // error_max_turns, error_during_execution, or an API failure)
                // it then exits non-zero on purpose. The trailing
                // ProcessException carries no information beyond "exit code 1"
                // — replace it with a ResultException carrying what the CLI
                // already reported so the exception is actionable and typed.
                // Mirrors the TS/Python SDKs.
                String errorText;
                Throwable pendingError = e;
                Map<String, Object> errorResult = lastErrorResult.get();
                boolean replaced = (e instanceof in.vidyalai.claude.sdk.exceptions.ProcessException
                        && errorResult != null);
                if (replaced) {
                    errorText = "Claude Code returned an error result: " + errorResultText(errorResult);
                    // stderr deliberately not carried over: the transport's value
                    // is a generic placeholder, and the result text is the real
                    // cause.
                    ResultException resultException = new ResultException(
                            errorText,
                            errorResult,
                            ((in.vidyalai.claude.sdk.exceptions.ProcessException) e).getExitCode());
                    resultException.initCause(e);
                    pendingError = resultException;
                    logger.fine("Replacing ProcessException with ResultException: " + errorText);
                } else {
                    errorText = e.getMessage();
                    logger.log(Level.SEVERE, "Fatal error in message reader: " + errorText, e);
                }
                // Signal all pending control requests so they fail fast instead
                // of timing out. This includes an `initialize` still in flight
                // when the CLI reports an error result during startup (e.g. a
                // refused resume), so that path sees the same actionable text.
                for (Map.Entry<String, CompletableFuture<ControlResponse>> entry : pendingControlResponses
                        .entrySet()) {
                    entry.getValue().completeExceptionally(pendingError);
                }
                pendingControlResponses.clear();

                // Put the error in the stream so iterators can raise it. The
                // typed exception rides along so receiveMessages() rethrows it
                // as-is (ResultException / ProcessException with its exit code,
                // CLIJSONDecodeException, ...) instead of flattening it to a
                // bare ClaudeSDKException(String).
                try {
                    Map<String, Object> errMessage = new HashMap<>();
                    errMessage.put("type", "error");
                    errMessage.put("error", errorText);
                    errMessage.put("exception", pendingError);
                    messageQueue.offer(errMessage, 1, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        } finally {
            // Final flush before end-of-stream so an early stdout EOF or
            // transport error doesn't drop entries batched this turn.
            TranscriptMirrorBatcher batcher = transcriptMirrorBatcher;
            if (batcher != null) {
                try {
                    batcher.flush().get(5, TimeUnit.SECONDS);
                } catch (Exception flushEx) {
                    logger.fine("Mirror flush at end-of-stream completed exceptionally: "
                            + flushEx.getMessage());
                }
            }
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
                    ToolPermissionContext context = new ToolPermissionContext(
                            null,
                            suggestions,
                            permissionReq.toolUseId(),
                            permissionReq.agentId(),
                            permissionReq.blockedPath(),
                            permissionReq.decisionReason(),
                            permissionReq.title(),
                            permissionReq.displayName(),
                            permissionReq.description());

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
                case SDKControlMcpReconnectRequest ignored -> {
                    // MCP reconnect is sent from SDK to CLI, not CLI to SDK
                    throw new ClaudeSDKException("Unexpected mcp_reconnect request from CLI: " + ignored);
                }
                case SDKControlMcpToggleRequest ignored -> {
                    // MCP toggle is sent from SDK to CLI, not CLI to SDK
                    throw new ClaudeSDKException("Unexpected mcp_toggle request from CLI: " + ignored);
                }
                case SDKControlStopTaskRequest ignored -> {
                    // Stop task is sent from SDK to CLI, not CLI to SDK
                    throw new ClaudeSDKException("Unexpected stop_task request from CLI: " + ignored);
                }
                case SDKControlGetContextUsageRequest ignored -> {
                    // Get context usage is sent from SDK to CLI, not CLI to SDK
                    throw new ClaudeSDKException("Unexpected get_context_usage request from CLI: " + ignored);
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
            // The reader completes pending requests with the exception it
            // failed with — a ResultException carrying the CLI's error result,
            // a ProcessException with its exit code, ... Surface it as-is so
            // callers can branch on the type instead of on a wrapper's text.
            // Mirrors the Python SDK's `raise result`.
            Throwable cause = (e instanceof java.util.concurrent.ExecutionException) ? e.getCause() : e;
            if (cause instanceof ClaudeSDKException sdkError) {
                throw sdkError;
            }
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
     * Sends a get current MCP server connection status control request.
     *
     * @return typed MCP server status response
     * @throws ClaudeSDKException if the mcp status request fails
     */
    public McpStatusResponse getMcpStatus() {
        SDKControlMCPStatusRequest request = new SDKControlMCPStatusRequest();
        SDKControlResponse response = sendControlRequest(request, Duration.ofSeconds(RESULT_WAIT_SECS));
        Map<String, Object> rawResponse = ((ControlResponse) response.response()).response();
        return MAPPER.convertValue(rawResponse, McpStatusResponse.class);
    }

    /**
     * Sends a get context usage control request.
     *
     * @return typed context usage response
     * @throws ClaudeSDKException if the context usage request fails
     */
    public ContextUsageResponse getContextUsage() {
        SDKControlGetContextUsageRequest request = new SDKControlGetContextUsageRequest();
        SDKControlResponse response = sendControlRequest(request, Duration.ofSeconds(RESULT_WAIT_SECS));
        Map<String, Object> rawResponse = ((ControlResponse) response.response()).response();
        return MAPPER.convertValue(rawResponse, ContextUsageResponse.class);
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
     * Reconnects a disconnected or failed MCP server.
     *
     * @param serverName the name of the MCP server to reconnect
     * @throws ClaudeSDKException if the reconnect request fails
     */
    public void reconnectMcpServer(String serverName) {
        SDKControlMcpReconnectRequest request = new SDKControlMcpReconnectRequest(serverName);
        sendControlRequest(request, Duration.ofSeconds(RESULT_WAIT_SECS));
    }

    /**
     * Enables or disables an MCP server.
     *
     * @param serverName the name of the MCP server to toggle
     * @param enabled    true to enable the server, false to disable it
     * @throws ClaudeSDKException if the toggle request fails
     */
    public void toggleMcpServer(String serverName, boolean enabled) {
        SDKControlMcpToggleRequest request = new SDKControlMcpToggleRequest(serverName, enabled);
        sendControlRequest(request, Duration.ofSeconds(RESULT_WAIT_SECS));
    }

    /**
     * Stops a running task.
     *
     * <p>
     * After this resolves, a {@code task_notification} system message with
     * status {@code "stopped"} will be emitted by the CLI in the message stream.
     *
     * @param taskId the task ID from task_notification events
     * @throws ClaudeSDKException if the stop request fails
     */
    public void stopTask(String taskId) {
        SDKControlStopTaskRequest request = new SDKControlStopTaskRequest(taskId);
        sendControlRequest(request, Duration.ofSeconds(RESULT_WAIT_SECS));
    }

    /**
     * Whether the CLI may still send control requests that need a reply.
     *
     * <p>SDK MCP servers, hooks, and the {@code canUseTool} permission callback
     * are all served over the control protocol: the CLI writes a
     * {@code control_request} to stdout and blocks until the SDK writes the
     * matching {@code control_response} to stdin. Closing stdin while any of
     * these are configured makes every later request fail CLI-side with
     * "Stream closed". Mirrors the TypeScript SDK's
     * {@code hasBidirectionalNeeds}.
     */
    private boolean hasBidirectionalNeeds() {
        // Read each field once: a second read is what makes the null check
        // above it non-narrowing, and there is no reason to read twice.
        Map<HookEvent, List<HookMatcher>> hookConfig = hooks;
        Map<String, SdkMcpServer> mcpServers = sdkMcpServers;
        return ((hookConfig != null) && (!hookConfig.isEmpty()))
                || ((mcpServers != null) && (!mcpServers.isEmpty()))
                || (canUseTool != null);
    }

    /**
     * Streams input messages to transport.
     *
     * <p>If SDK MCP servers, hooks, or a {@code canUseTool} callback are
     * present, waits for a run-ending result before closing stdin so
     * bidirectional control protocol traffic keeps working.
     *
     * <p>Known limitation: the wait is released by the first result frame that
     * arrives with no tasks in flight, so a prompt iterator that yields several
     * user messages (several turns) releases the hold at the first turn
     * boundary; control requests from later turns can then find stdin closed.
     * Single-message and string prompts — the common one-shot shapes — are
     * fully covered.
     */
    public void streamInput(Iterator<Map<String, Object>> stream) {
        int written = 0;
        try {
            while (stream.hasNext() && (!closed.get())) {
                Map<String, Object> message = stream.next();
                String json = MAPPER.writeValueAsString(message);
                transport.write(json + "\n");
                written++;
            }
        } catch (Exception e) {
            // A caller-supplied prompt iterator (or the write) failed. Don't
            // leave stdin open — the CLI would wait for input forever and the
            // consumer's iteration would never finish — fall through and close
            // it like a normal end of input.
            if (!closed.get()) {
                logger.log(Level.WARNING, "Prompt stream failed; closing stdin", e);
            }
        }

        try {
            if (written > 0 && hasBidirectionalNeeds()) {
                try {
                    firstResultEvent.get(streamCloseTimeout.toMillis(), TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    logger.fine("Timed out waiting for first result, closing input stream");
                } catch (Exception e) {
                    // Ignore other exceptions
                }
            }
            // Nothing sent means no result will arrive to release the hold, so
            // close immediately (mirrors the TypeScript SDK's messageCount
            // guard).
            if (!closed.get()) {
                transport.endInput();
            }
        } catch (Exception e) {
            if (!closed.get()) {
                logger.log(Level.WARNING, "Error closing input stream", e);
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
            // 0. Final-flush mirror entries before tearing down so .return()/break
            // don't drop the current turn when the process exits immediately.
            TranscriptMirrorBatcher batcher = transcriptMirrorBatcher;
            if (batcher != null) {
                try {
                    batcher.close().get(5, TimeUnit.SECONDS);
                } catch (Exception flushEx) {
                    logger.fine("Mirror close flush completed exceptionally: " + flushEx.getMessage());
                }
            }

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
    /**
     * Turns a synthetic {@code error} frame back into the exception the reader
     * thread failed with.
     *
     * <p>
     * The reader stows the exception on the frame so its type and payload —
     * {@link ResultException}'s result fields, a {@code ProcessException}'s
     * exit code, a {@code CLIJSONDecodeException}'s raw line — reach the
     * consumer instead of being flattened to a message string. Frames without
     * one (or with a non-runtime throwable) fall back to a
     * {@link ClaudeSDKException} carrying the text.
     */
    private static RuntimeException toThrowable(Map<String, Object> message) {
        Object exception = message.get("exception");
        if (exception instanceof RuntimeException runtime) {
            return runtime;
        }
        String text = (message.get("error") instanceof String s) ? s : "Unknown error";
        if (exception instanceof Throwable cause) {
            return new ClaudeSDKException(text, cause);
        }
        return new ClaudeSDKException(text);
    }

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
                            throw toThrowable(message);
                        }

                        Message parsed = MessageParser.parse(message);
                        if (parsed != null) {
                            nextMessage = parsed;
                            return true;
                        }
                        // Unknown message type, skip and continue
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
