package in.vidyalai.claude.sdk.internal;

import java.io.IOException;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.InjectableValues;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.ClaudeSDKClient;
import in.vidyalai.claude.sdk.exceptions.CLIConnectionException;
import in.vidyalai.claude.sdk.exceptions.ClaudeSDKException;
import in.vidyalai.claude.sdk.exceptions.ResultException;
import in.vidyalai.claude.sdk.mcp.McpMessageHandler;
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
import in.vidyalai.claude.sdk.types.hook.HookOnFailure;
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
 * <h2>Transcript-Mirror Delivery Barrier</h2>
 * <p>
 * With a {@link TranscriptMirrorBatcher} attached, a {@code result} is
 * delivered only once the turn it ends is in the {@code SessionStore}, but
 * the reader does not wait for that. At a {@code result} it starts a flush and
 * opens a <i>held segment</i>: the flush, the {@code mirror_error} reports of
 * the drains that flush covers, the result and every regular message read
 * after it. Segments are released strictly in order, each once its flush
 * completes (bounded by {@link TranscriptMirrorBatcher#flushTimeoutMs()}), a
 * segment's mirror errors first, then its messages. Meanwhile the reader goes
 * on reading: {@code control_response} frames settle their requests at once,
 * the CLI's {@code control_request} and {@code control_cancel_request} frames
 * are dispatched as always (they never go through the message queue), and
 * {@code transcript_mirror} frames go to the batcher. So the CLI's
 * {@code can_use_tool} and hook requests for the next turn can be dispatched
 * while this turn's result, and the next turn's own {@code tool_use} message,
 * are still held (up to the flush bound with a slow store): a callback can
 * run before the consumer has seen the messages that led to it. Held messages
 * count toward the {@code maxMsgQSize} soft limit, and while anything is held
 * the reader stops at a hard ceiling of four times it
 * ({@code HARD_LIMIT_FACTOR}) even with a control request pending. The end of
 * the stream, or the
 * error it ends with, is published only after every held segment and the
 * final flush have been released. Without a batcher nothing is held.
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
 * <li>Drops the messages still held behind a transcript-mirror flush:
 * iterators end at once, so they would never be read</li>
 * <li>Waits for the final transcript-mirror flush (bounded by the batcher)</li>
 * <li>Completes all pending control request futures exceptionally</li>
 * <li>Cancels the CLI's control requests still being answered (their
 * callbacks' futures are cancelled and their threads interrupted, except the
 * thread calling {@code close()} from inside a callback; nothing is
 * answered), as the Python SDK cancels its child tasks</li>
 * <li>Closes the transport to unblock the reader thread</li>
 * <li>Shuts down both executors via {@code shutdown()}</li>
 * <li>Waits up to 10 seconds for reader executor to terminate</li>
 * <li>Forces reader shutdown via {@code shutdownNow()} if timeout occurs, waits
 * 2s more</li>
 * <li>Logs warning if reader threads don't terminate after forced shutdown</li>
 * <li>Waits up to 5 seconds for the (already interrupted) control handlers to
 * finish, all but the one calling {@code close()}, then 2s more after a
 * {@code shutdownNow()} (skipped when {@code close()} runs on a handler)</li>
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
    /**
     * The hard ceiling on buffered messages (queued plus held), as a multiple
     * of {@code maxMsgQSize}, while messages are held behind a
     * transcript-mirror flush. Past the soft limit the reader still reads on
     * while an outbound control request waits for its response, but not past
     * this while a flush holds messages back: the consumer cannot drain held
     * messages, so a hung store would otherwise let them grow without bound,
     * at the cost of that request possibly timing out. With nothing held the
     * consumer can always drain, and there is no ceiling (as in 0.2.6).
     */
    static final int HARD_LIMIT_FACTOR = 4;
    /**
     * Deadline for an OUTBOUND control request (SDK to CLI), as in the Python
     * SDK. Inbound requests (canUseTool, hooks, SDK MCP) have no SDK-side
     * deadline: they wait for the callback, a control_cancel_request, or
     * close().
     */
    private static final int RESULT_WAIT_SECS = 60;
    /**
     * How often a blocked reader or consumer wakes to notice close(). Not a
     * deadline: neither gives up, it only re-checks the closed flag.
     */
    private static final long QUEUE_WAKE_MS = 100;
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

    /**
     * The CLI's own wait for background work once stdin is closed; the SDK
     * bounds its wait for the CLI's "idle" by the same value (see
     * {@link #armRunEndCeiling}).
     */
    static final String RUN_END_CEILING_ENV = "CLAUDE_CODE_PRINT_BG_WAIT_CEILING_MS";
    /** The CLI's default background-wait ceiling: 10 minutes. */
    public static final long DEFAULT_RUN_END_CEILING_MS = 600_000;
    /**
     * The longest ceiling honored (~24.8 days), as in the TypeScript and Python
     * SDKs.
     */
    static final long MAX_RUN_END_CEILING_MS = Integer.MAX_VALUE;
    private static final ObjectMapper MAPPER;

    static {
        MAPPER = new ObjectMapper();
        // Configure ObjectMapper for control protocol types
        // Ignore unknown properties for forward compatibility
        MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        // No global null-stripping for Map: maps on the control channel carry
        // user data (updatedInput, hook output, MCP results, streamed user
        // messages) that must reach the CLI as written, explicit nulls
        // included, as Python's json.dumps sends them. SDK-built maps omit
        // their unset keys themselves.
    }

    private final Transport transport;
    private final boolean isStreamingMode;
    // May be null
    private final ClaudeAgentOptions.CanUseTool canUseTool;
    @Nullable
    private final Map<HookEvent, List<HookMatcher>> hooks;
    @Nullable
    private final Map<String, McpMessageHandler> sdkMcpServers;
    /**
     * This connection's session of each SDK MCP server, by the name the CLI
     * addresses it with: what {@code mcp_message} is routed to, and what
     * {@link #close()} closes. One server shared by several clients thus
     * keeps their request ids, cancellations and shutdowns apart.
     */
    @Nullable
    private final Map<String, McpMessageHandler> sdkMcpSessions;
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
    /**
     * Optional system-prompt flag sent via initialize (see
     * {@link in.vidyalai.claude.sdk.types.config.SystemPromptPreset#snapshot()}).
     * Sent whenever set, including {@code false}.
     */
    @Nullable
    private final Boolean systemPromptSnapshot;
    /**
     * Mark every outgoing user message {@code client_composed} so the CLI
     * delivers it as written (no {@code @path} expansion, no slash-command
     * dispatch).
     */
    private final boolean verbatimPrompts;
    /**
     * How long the run stays open past a result with no new turn while the CLI
     * still reports "running"; {@code 0} waits for "idle" however long it takes
     * (see {@link #runEndCeilingMs(Map)}).
     */
    private final long runEndCeilingMs;
    private final Duration initializeTimeout;

    // Control protocol state
    private final Map<String, CompletableFuture<ControlResponse>> pendingControlResponses = new ConcurrentHashMap<>();
    private final Map<String, BiFunction<HookInput, HookContext, CompletableFuture<HookOutput>>> hookCallbacks = new ConcurrentHashMap<>();
    private final AtomicInteger nextCallbackId = new AtomicInteger(0);
    private final AtomicInteger requestCounter = new AtomicInteger(0);

    // Message stream. The queue itself is unbounded: maxMsgQSize is a soft
    // limit the reader enforces in enqueueBlocking, so that it can run past
    // it while an outbound control request waits for its response, up to the
    // hard ceiling (see enqueueBlocking). One FIFO either way, so message
    // order never changes. Messages held behind a transcript-mirror flush
    // (holdback) come before the queue's later additions and count toward
    // the same limits.
    private final BlockingQueue<Map<String, Object>> messageQueue = new LinkedBlockingQueue<>();
    private final int maxMsgQSize;
    // HARD_LIMIT_FACTOR x maxMsgQSize: while anything is held, queued plus
    // held never exceeds it.
    private final int maxBufferedMessages;
    // Wakes a reader waiting for room: signalled when a consumer takes a
    // message, when an outbound control request is registered, when held
    // messages are released or dropped, and on close().
    // Also guards the holdback, heldCount and every addition to messageQueue
    // that must keep its place relative to the holdback (the reader's
    // enqueues, releases, mirror errors and the terminal markers). Never held
    // while calling user code or blocking on anything but its conditions.
    private final ReentrantLock queueLock = new ReentrantLock();
    private final Condition queueRoom = queueLock.newCondition();
    // Signalled when the holdback becomes empty, and on close().
    private final Condition holdbackReleased = queueLock.newCondition();
    // Segments held behind a transcript-mirror flush, oldest first; released
    // strictly in this order (see releaseHeld). Guarded by queueLock.
    private final ArrayDeque<HeldSegment> holdback = new ArrayDeque<>();
    // Messages in the holdback, mirror errors included. Guarded by queueLock.
    private int heldCount = 0;
    // Test seam, set through setBarrierHookForTest; null in production.
    @Nullable
    private volatile UnaryOperator<CompletableFuture<Void>> barrierHookForTest = null;
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean readerStarted = new AtomicBoolean(false);
    @Nullable
    private volatile SDKControlResponse initializationResult = null;

    // Thread management via ExecutorService
    // Separate executors for reader (single thread) and control tasks (concurrent)
    private final ExecutorService readerExecutor;
    private final ExecutorService controlExecutor;

    // Control requests from the CLI still being answered, by request_id, so a
    // control_cancel_request can stop the one it names. Each handler removes
    // its own entry when it finishes, which makes a late cancel a no-op.
    private final Map<String, InflightControlRequest> inflightControlRequests = new ConcurrentHashMap<>();
    // Every control request handler still running, request_id or not, so
    // close() can wait for each one except the handler it is called from.
    private final Set<InflightControlRequest> runningControlRequests = ConcurrentHashMap.newKeySet();
    // The control request the current thread is answering, while it answers
    // it: close() called from inside a callback must not interrupt (or wait
    // for) the very thread that is closing.
    private final ThreadLocal<@Nullable InflightControlRequest> servingRequest = new ThreadLocal<>();

    // Run-end state. The reader thread, the stdin writer (streamInput), the
    // ceiling sleeper and close() all touch it, so every field below is
    // guarded by runLock.
    private final Object runLock = new Object();
    // Completed when the run is over, so the stdin-closing waiter can wake;
    // see readMessages and inflightTasks below. Work the CLI takes up after
    // the run ended swaps in a fresh future (reopenRun).
    private CompletableFuture<Void> runEndedEvent = new CompletableFuture<>();
    private boolean resultReceived = false;
    // The CLI's latest session_state_changed state, or null while it sends none
    // (a CLI too old to honor CLAUDE_CODE_SDK_READS_SESSION_STATE). A CLI that
    // reports state stays "running" while a background agent is live or its
    // completion is still to be handled, and reports "idle" once no further
    // turn is owed.
    @Nullable
    private String sessionState = null;
    // Ends the run if no new turn starts within the ceiling after a result
    // (armRunEndCeiling). The generation tells a sleeper that woke after it was
    // cleared or re-armed to stand down.
    @Nullable
    private Thread runEndCeilingThread = null;
    private long runEndCeilingGeneration = 0;
    // A main-thread turn is under way (its assistant/stream_event frames have
    // started and its result has not arrived): the ceiling counts only the
    // wait between turns, so it is not armed meanwhile.
    private boolean turnInProgress = false;
    // Set once stdin is closed or the reader is gone: the run then stays
    // ended, since nothing can wait on a reopened one.
    private boolean runFinal = false;

    // Task IDs of started-but-not-finished tasks. A result frame ends one turn,
    // not the run: background tasks keep running past it and still need stdin
    // for hook/SDK-MCP control responses, so a result arriving while this set is
    // non-empty must not close stdin. Only touched from the reader thread, but
    // kept concurrent because close() may inspect it from another thread.
    private final Set<String> inflightTasks = ConcurrentHashMap.newKeySet();

    // The message stream's terminal state, kept outside the queue so that a
    // full queue can delay the end of the stream, or the error it ended with,
    // but never hide it. The reader writes streamError (if any) and then
    // streamEnded, after its last enqueue.
    private volatile boolean streamEnded = false;
    @Nullable
    private volatile Map<String, Object> streamError = null;

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
            @Nullable Map<String, McpMessageHandler> sdkMcpServers,
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
            @Nullable Map<String, McpMessageHandler> sdkMcpServers,
            @Nullable Map<String, AgentDefinition> agents,
            @Nullable Boolean excludeDynamicSections,
            @Nullable Object skills,
            boolean forwardSubagentText,
            Duration initializeTimeout,
            @Nullable Integer maxMsgQSize) {
        this(transport, isStreamingMode, canUseTool, hooks, sdkMcpServers, agents,
                excludeDynamicSections, null, skills, forwardSubagentText, false,
                DEFAULT_RUN_END_CEILING_MS, initializeTimeout, maxMsgQSize);
    }

    /**
     * Creates a new QueryHandler with every initialize-request and run-end
     * setting.
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
     * @param systemPromptSnapshot     optional system-prompt flag sent via initialize (see
     *                                 {@link in.vidyalai.claude.sdk.types.config.SystemPromptPreset#snapshot()})
     * @param skills                   optional skill allowlist sent via initialize so the CLI can
     *                                 filter which skills are loaded into the system prompt
     *                                 ({@code null}, the string {@code "all"}, or a {@code List<String>})
     * @param forwardSubagentText      ask the CLI (via initialize) to forward subagent
     *                                 text/thinking blocks, not just tool_use/tool_result
     * @param verbatimPrompts          mark every outgoing user message {@code client_composed}
     *                                 so the CLI delivers it as written
     * @param runEndCeilingMs          how long the run stays open past a result with no new
     *                                 turn while the CLI still reports "running"; {@code 0}
     *                                 waits for "idle" however long it takes
     * @param initializeTimeout        timeout for the initialize request
     * @param maxMsgQSize              max message queue size
     */
    public QueryHandler(
            Transport transport,
            boolean isStreamingMode,
            ClaudeAgentOptions.CanUseTool canUseTool, // may be null
            @Nullable Map<HookEvent, List<HookMatcher>> hooks,
            @Nullable Map<String, McpMessageHandler> sdkMcpServers,
            @Nullable Map<String, AgentDefinition> agents,
            @Nullable Boolean excludeDynamicSections,
            @Nullable Boolean systemPromptSnapshot,
            @Nullable Object skills,
            boolean forwardSubagentText,
            boolean verbatimPrompts,
            long runEndCeilingMs,
            Duration initializeTimeout,
            @Nullable Integer maxMsgQSize) {
        this.transport = transport;
        this.isStreamingMode = isStreamingMode;
        this.canUseTool = canUseTool;
        this.hooks = hooks;
        this.sdkMcpServers = sdkMcpServers;
        this.sdkMcpSessions = openSdkMcpSessions(sdkMcpServers);
        this.agents = agents;
        this.excludeDynamicSections = excludeDynamicSections;
        this.skills = skills;
        this.forwardSubagentText = forwardSubagentText;
        this.systemPromptSnapshot = systemPromptSnapshot;
        this.verbatimPrompts = verbatimPrompts;
        this.runEndCeilingMs = runEndCeilingMs;
        this.initializeTimeout = initializeTimeout;
        int queueSize = ((maxMsgQSize != null) ? maxMsgQSize : DEFAULT_MSG_Q_SIZE);
        if (queueSize <= 0) {
            throw new IllegalArgumentException("maxMsgQSize must be at least 1, got " + queueSize);
        }
        this.maxMsgQSize = queueSize;
        this.maxBufferedMessages = (int) Math.min(Integer.MAX_VALUE, (long) HARD_LIMIT_FACTOR * queueSize);

        // Create separate executors for reader and control tasks.
        // Threads gives virtual threads on Java 21+ and named daemon platform
        // threads on 17-20; the names are the same either way.
        this.readerExecutor = Threads.newSingleThreadExecutor("QueryHandler-Reader-");

        // Control: one thread per request, and it must stay that way.
        // An SDK MCP tool call parks its thread until the tool answers, and
        // the notifications/cancelled that ends it arrives as a *separate*
        // control request. Under any bounded pool that cancellation would
        // queue behind the call it exists to cancel, and deadlock. That is why
        // the Java 17 fallback is an unbounded cached pool, not a fixed one.
        this.controlExecutor = Threads.newThreadPerTaskExecutor("QueryHandler-Control-");
    }

    /**
     * Reads {@code CLAUDE_CODE_PRINT_BG_WAIT_CEILING_MS} as the CLI will see it.
     *
     * <p>
     * {@code optionsEnv} ({@link ClaudeAgentOptions#env()}) overrides the
     * inherited environment, as it does for the CLI subprocess. {@code 0} means
     * no limit; anything that is not a plain non-negative integer falls back to
     * the CLI's default of 10 minutes (the CLI itself also reads spellings such
     * as {@code 1e6}).
     *
     * @param optionsEnv the environment the options pass to the CLI
     * @return the ceiling in milliseconds
     */
    public static long runEndCeilingMs(Map<String, String> optionsEnv) {
        return runEndCeilingMs(optionsEnv, System.getenv());
    }

    static long runEndCeilingMs(Map<String, String> optionsEnv, Map<String, String> ambientEnv) {
        String raw = optionsEnv.containsKey(RUN_END_CEILING_ENV)
                ? optionsEnv.get(RUN_END_CEILING_ENV)
                : ambientEnv.get(RUN_END_CEILING_ENV);
        if (raw == null) {
            return DEFAULT_RUN_END_CEILING_MS;
        }
        String trimmed = raw.strip();
        if (!trimmed.matches("\\+?[0-9]+")) {
            return DEFAULT_RUN_END_CEILING_MS;
        }
        // A value past Long.MAX_VALUE is still a valid, very long ceiling: the
        // sleeper clamps it to MAX_RUN_END_CEILING_MS.
        BigInteger value = new BigInteger(trimmed);
        return value.bitLength() < Long.SIZE ? value.longValue() : Long.MAX_VALUE;
    }

    /**
     * Applies {@link ClaudeAgentOptions#verbatimPrompts()} to an outgoing user
     * message.
     *
     * <p>
     * Returns {@code message} unchanged when the option is off; otherwise a
     * copy with {@code client_composed} set to {@code true} (overwriting any
     * caller-supplied value) so the CLI delivers the text as written.
     *
     * @param message         the user message
     * @param verbatimPrompts whether the option is on
     * @return the message to write
     */
    public static Map<String, Object> stampUserMessage(Map<String, Object> message, boolean verbatimPrompts) {
        if (!verbatimPrompts) {
            return message;
        }
        Map<String, Object> stamped = new HashMap<>(message);
        stamped.put("client_composed", Boolean.TRUE);
        return stamped;
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
            boolean blocksOnFailure = false;
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
                            HookOnFailure onFailure = matcher.onFailure();
                            if (onFailure != null) {
                                matcherConfig.put("onFailure", onFailure.getValue());
                                blocksOnFailure |= (onFailure == HookOnFailure.BLOCK);
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
                    forwardSubagentText ? Boolean.TRUE : null,
                    systemPromptSnapshot);

            SDKControlResponse response = sendControlRequest(request, initializeTimeout);
            if (blocksOnFailure) {
                requireHookOnFailureHonored(response);
            }
            initializationResult = response;
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
     * Refuses an initialize answer that would let an {@code onFailure = BLOCK}
     * hook fail open or never run.
     *
     * <p>
     * A responder that does not honor {@code onFailure}, such as a CLI that
     * predates it, drops the field and answers normally, so the absence of
     * {@code hook_on_failure_supported} is the only sign. {@code hooks_applied:
     * false} means nothing registered these callbacks, as on a process that was
     * already running.
     */
    private static void requireHookOnFailureHonored(SDKControlResponse response) {
        Map<String, Object> body = ((response != null) && (response.response() instanceof ControlResponse ok))
                ? ok.response()
                : null;
        // Python truthiness for the support flag (`not response.get(...)`), and
        // an exact `is False` for hooks_applied, as upstream.
        if ((body == null) || !Sessions.truthy(body.get("hook_on_failure_supported"))) {
            throw new CLIConnectionException(
                    "The process answering initialize does not honor onFailure on hook matchers, "
                            + "so a hook that fails would not block. Update Claude Code if it is old, "
                            + "or remove onFailure(HookOnFailure.BLOCK) from your hooks.");
        }
        if (Boolean.FALSE.equals(body.get("hooks_applied"))) {
            throw new CLIConnectionException(
                    "This session was already running and did not register your hooks, so a hook "
                            + "set to onFailure(HookOnFailure.BLOCK) would never run. Start a new "
                            + "session, or remove onFailure(HookOnFailure.BLOCK) from your hooks.");
        }
    }

    /**
     * Attach a {@link TranscriptMirrorBatcher} that receives
     * {@code transcript_mirror} frames.
     *
     * <p>When set, the read loop peels {@code transcript_mirror} frames off
     * stdout (they are not yielded to consumers) and enqueues them on the
     * batcher. At each {@code result} it starts a flush and holds the result,
     * and the regular messages after it, until that flush completes; it keeps
     * reading meanwhile (see the class Javadoc).
     */
    public void setTranscriptMirrorBatcher(TranscriptMirrorBatcher batcher) {
        this.transcriptMirrorBatcher = batcher;
    }

    /**
     * Surface a {@link in.vidyalai.claude.sdk.types.session.SessionStore#append}
     * failure as a {@code mirror_error} system message in the consumer stream.
     * This is the batcher's {@link MirrorErrorSink}.
     *
     * <p>Called once a batch has been dropped (after its retries, or after a
     * single timed-out attempt), so this is the consumer's only signal.
     * Non-blocking: the queue's limit is one only the reader enforces, so the
     * report is queued even when the reader is waiting for room. Once the
     * stream has ended or the handler is closed no consumer could read it, so
     * it is dropped, with a warning naming the session and the error.
     *
     * <p>Placement: if a held segment's flush covers {@code drain} (its
     * {@code Barrier.drain()} is at least {@code drain}), the first such
     * segment delivers the report right before its messages, so ahead of its
     * {@code result}. Otherwise (a later drain, or a segment already released
     * at the flush bound) it takes its place behind everything read so far.
     *
     * @param key   the session the dropped batch was for, or {@code null}
     * @param error the failure's message
     * @param drain the number of the drain that dropped the batch
     */
    public void reportMirrorError(in.vidyalai.claude.sdk.types.session.@Nullable SessionKey key, String error,
            long drain) {
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
        boolean dropped;
        queueLock.lock();
        try {
            dropped = streamEnded || closed.get();
            if (!dropped) {
                placeLocked(msg, drain);
            }
        } finally {
            queueLock.unlock();
        }
        if (dropped) {
            logger.warning("Dropping a mirror_error reported after the end of the message stream"
                    + " (SessionStore append failed for " + ((key != null) ? key : "an unknown session")
                    + "): " + error);
        }
    }

    /** Places a mirror error by its drain number. Caller holds queueLock. */
    private void placeLocked(Map<String, Object> msg, long drain) {
        for (HeldSegment segment : holdback) {
            if (drain <= segment.drain) {
                segment.mirrorErrors.add(msg);
                heldCount++;
                return;
            }
        }
        HeldSegment tail = holdback.peekLast();
        if (tail != null) {
            tail.messages.add(msg);
            heldCount++;
        } else {
            messageQueue.add(msg);
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
        // The in-band error frame the stream ends with, if the read failed.
        // Queued in the finally below, after the final mirror flush, so a
        // mirror_error that flush reports reaches the consumer ahead of it.
        Map<String, Object> terminalError = null;
        try {
            Iterator<Map<String, Object>> messages = transport.readMessages();
            while (messages.hasNext() && (!closed.get())) {
                Map<String, Object> message = messages.next();
                // Never a cast: a frame with a non-string type must not kill
                // the reader (it reaches the parser, which skips it).
                String msgType = ((message.get("type") instanceof String type) ? type : null);
                // The flush a result is held behind, if any.
                TranscriptMirrorBatcher.Barrier barrier = null;

                // Route control messages. None of these goes through the
                // message queue or the holdback, so they are handled at once
                // even while a result waits for its mirror flush.
                if ("control_response".equals(msgType)) {
                    handleControlResponse(message);
                    continue;
                } else if ("control_request".equals(msgType)) {
                    // Handle incoming control requests from CLI
                    // Use controlExecutor for concurrent control request handling
                    submitControlRequest(message);
                    continue;
                } else if ("control_cancel_request".equals(msgType)) {
                    // The CLI has abandoned a request it sent: stop its handler
                    // and never answer it.
                    if (message.get("request_id") instanceof String cancelId) {
                        InflightControlRequest inflight = inflightControlRequests.remove(cancelId);
                        if (inflight != null) {
                            inflight.cancel();
                        }
                    }
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
                            int skipped = 0;
                            for (Object item : entriesList) {
                                if (item instanceof Map<?, ?> m) {
                                    @SuppressWarnings("unchecked")
                                    Map<String, Object> em = (Map<String, Object>) m;
                                    entries.add(in.vidyalai.claude.sdk.types.session.SessionStoreEntry.of(em));
                                } else {
                                    // Python hands any entry to the store; a
                                    // SessionStoreEntry wraps a JSON object, so a
                                    // non-object entry cannot reach the adapter.
                                    // The CLI writes objects, so this is a
                                    // protocol surprise worth seeing, not noise.
                                    skipped++;
                                }
                            }
                            if (skipped > 0) {
                                logger.warning("Skipping " + skipped + " transcript_mirror entr"
                                        + ((skipped == 1) ? "y" : "ies") + " for " + fp
                                        + " that are not JSON objects; a SessionStore only stores objects");
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
                    boolean hadTasksInFlight = !inflightTasks.isEmpty();
                    trackTaskLifecycle(message);
                    if (hadTasksInFlight && inflightTasks.isEmpty()) {
                        // The ceiling left the last tracked agent alone; the
                        // wait between turns starts over now that it settled.
                        synchronized (runLock) {
                            rearmRunEndCeilingBetweenTurns();
                        }
                    }
                    if ("session_state_changed".equals(message.get("subtype"))) {
                        onSessionState(message.get("state"));
                        // Frames the CLI sent only because the transport asked
                        // for them (CLAUDE_CODE_SDK_READS_SESSION_STATE); the
                        // caller did not opt in.
                        if (Boolean.TRUE.equals(message.get("sdk_host_only"))) {
                            continue;
                        }
                    }
                }

                // Track results for proper stream closure
                if ("result".equals(msgType)) {
                    // Flush pending transcript mirror entries so consumers
                    // observing the result can rely on the SessionStore being
                    // up to date for this turn. The reader does not wait for
                    // it: the result is held behind the flush (enqueueBlocking)
                    // while the reader goes on reading control responses.
                    TranscriptMirrorBatcher batcher = transcriptMirrorBatcher;
                    if (batcher != null) {
                        barrier = batcher.flushBarrier();
                    }
                    synchronized (runLock) {
                        resultReceived = true;
                        turnInProgress = false;
                        // A result ends a turn, not necessarily the run: a
                        // background agent that finished just before it still
                        // wakes the session for another turn, whose hook,
                        // permission and SDK MCP requests need stdin. A CLI that
                        // reports session state stays "running" while such a
                        // turn is owed, so wait for "idle" (some hosts send it
                        // just before the result). Without state events the
                        // result is all there is to go on.
                        if (sessionState == null || "idle".equals(sessionState)
                                || !hasBidirectionalNeeds()) {
                            maybeEndRun();
                        } else if (!"requires_action".equals(sessionState)) {
                            // While the SDK is still answering a request the
                            // ceiling waits for the "running" that follows.
                            armRunEndCeiling();
                        }
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
                    // A main-thread turn is under way, so the ceiling stops (it
                    // counts only the wait between turns, as the CLI's does)
                    // and the run reopens even if the ceiling ended it while no
                    // state changed.
                    if (("assistant".equals(msgType) || "stream_event".equals(msgType))
                            && message.get("parent_tool_use_id") == null) {
                        synchronized (runLock) {
                            turnInProgress = true;
                            reopenRun();
                            clearRunEndCeiling();
                        }
                    }
                }

                // Regular SDK messages go to the stream, behind any held
                // segment. A full queue holds the reader back until the
                // consumer catches up (back-pressure, as the Python SDK's
                // awaited send), and never drops a message.
                if (!enqueueBlocking(message, barrier)) {
                    break; // closed while waiting for room
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
                // The error is also recorded as the stream's terminal state
                // (streamError, published by streamEnded in the finally
                // below), so it can be delayed but never lost.
                Map<String, Object> errMessage = new HashMap<>();
                errMessage.put("type", "error");
                errMessage.put("error", errorText);
                errMessage.put("exception", pendingError);
                terminalError = errMessage;
            }
        } finally {
            // Release what is still held, then make the final flush, so an
            // early stdout EOF or transport error doesn't drop entries
            // batched this turn. The final flush is held like a result's (an
            // empty segment) and starts only once the earlier segments are
            // out, so its own bound is not spent waiting for theirs, as when
            // the reader waited for each flush in turn. The end is published
            // only after both: a mirror_error the final flush reports, and
            // every held message, is not stranded behind the terminal error
            // or the end marker. Stdout is done, so waiting here holds
            // nothing up; each flush is bounded by the batcher, and close()
            // ends the wait.
            awaitHoldbackReleased();
            TranscriptMirrorBatcher batcher = transcriptMirrorBatcher;
            if (batcher != null) {
                holdBehind(batcher.flushBarrier(), null);
                awaitHoldbackReleased();
            }
            Map<String, Object> endMessage = new HashMap<>();
            endMessage.put("type", "end");
            queueLock.lock();
            try {
                // Whatever close() left held is dropped with it.
                dropHoldback();
                if (terminalError != null) {
                    streamError = terminalError;
                    messageQueue.add(terminalError);
                }
                // Signal end of stream. The flag is the guarantee: it is set
                // after the reader's last enqueue, so a consumer that finds it
                // set and the queue empty has seen everything. The in-band
                // marker only wakes a waiting consumer at once (the queue's
                // limit is soft, so it is never refused). Set under queueLock,
                // so no mirror_error can be queued behind it.
                streamEnded = true;
                messageQueue.add(endMessage);
            } finally {
                queueLock.unlock();
            }
            // Unblock the stdin-closing waiter so it doesn't stall on early
            // exit.
            synchronized (runLock) {
                runFinal = true;
                endRun();
            }
        }
    }

    /**
     * A {@code result} (or the end of the stream) held behind a
     * transcript-mirror flush, with everything that must reach the consumer
     * between the segment before it and the one after it.
     */
    private static final class HeldSegment {

        /** The last mirror drain the flush covers. */
        final long drain;
        /** The {@code mirror_error} reports of the drains the flush covers. */
        final List<Map<String, Object>> mirrorErrors = new ArrayList<>();
        /**
         * The result first, then every message read after it, up to the next
         * segment; empty for the end-of-stream flush.
         */
        final List<Map<String, Object>> messages = new ArrayList<>();
        /** Whether the flush has completed. Guarded by queueLock. */
        boolean flushed = false;

        HeldSegment(long drain) {
            this.drain = drain;
        }

        int size() {
            return mirrorErrors.size() + messages.size();
        }
    }

    /**
     * Puts a message on the consumer queue, waiting for room as long as it
     * takes: once {@code maxMsgQSize} messages are waiting, queued or held,
     * the reader stops reading from the CLI until the consumer catches up
     * (back-pressure, as the Python SDK's awaited send; nothing is dropped).
     *
     * <p>
     * With a {@code barrier} (a {@code result}'s mirror flush) the message
     * opens a new held segment instead, unless the flush has already
     * completed and nothing is held; without one it joins the newest held
     * segment, or the queue when nothing is held, so the stream stays in
     * stdout order.
     *
     * <p>
     * The limit is soft. While an outbound control request is waiting for its
     * response, the reader keeps reading past it: the response travels on the
     * same stdout as the messages, so blocking here would leave a control call
     * made by a thread that is not draining the messages ({@code interrupt()},
     * {@code setModel()}, ...) to time out. The same holds while a slow
     * {@code SessionStore} keeps a result held: held messages count toward
     * the limit, so a hung store stops the reader at it, but a pending control
     * request still lets its response through.
     *
     * <p>
     * While messages are held, reading on is bounded too: at
     * {@link #HARD_LIMIT_FACTOR} times {@code maxMsgQSize} buffered messages,
     * queued plus held, the reader waits even while a control request is
     * pending. That request may then time out, but memory stays bounded
     * however long a store hangs. With nothing held there is no ceiling: the
     * consumer can drain, and a control call made meanwhile is never left to
     * time out behind the queue. The queue is one FIFO either way, so message order is
     * unchanged. A wait is woken when a consumer takes a message, when a
     * control request is sent, when held messages are released or dropped and
     * on close(); the {@link #QUEUE_WAKE_MS} timeout is only a backstop.
     *
     * @return false if the handler closed before there was room
     */
    private boolean enqueueBlocking(Map<String, Object> message,
            TranscriptMirrorBatcher.@Nullable Barrier barrier) throws InterruptedException {
        queueLock.lockInterruptibly();
        try {
            while (!closed.get() && mustWaitForRoom(messageQueue.size() + heldCount)) {
                queueRoom.await(QUEUE_WAKE_MS, TimeUnit.MILLISECONDS);
            }
            if (closed.get()) {
                return false;
            }
            if (barrier == null) {
                HeldSegment tail = holdback.peekLast();
                if (tail != null) {
                    tail.messages.add(message);
                    heldCount++;
                } else {
                    messageQueue.add(message);
                }
                return true;
            }
        } finally {
            queueLock.unlock();
        }
        holdBehind(barrier, message);
        return true;
    }

    /**
     * Whether the reader must wait with {@code buffered} messages queued or
     * held: at the hard ceiling while anything is held, and at the soft limit
     * unless an outbound control request is waiting for its response. Caller
     * holds queueLock.
     */
    private boolean mustWaitForRoom(int buffered) {
        if ((buffered >= maxBufferedMessages) && !holdback.isEmpty()) {
            return true;
        }
        return (buffered >= maxMsgQSize) && pendingControlResponses.isEmpty();
    }

    /** Test helper: messages buffered right now, queued plus held. */
    int bufferedCountForTest() {
        queueLock.lock();
        try {
            return messageQueue.size() + heldCount;
        } finally {
            queueLock.unlock();
        }
    }

    /**
     * Test seam: when set, each held segment waits for the flush future
     * {@code hook} returns instead of the flush itself, so a test can complete
     * barriers out of order. Production code never calls it.
     */
    void setBarrierHookForTest(@Nullable UnaryOperator<CompletableFuture<Void>> hook) {
        this.barrierHookForTest = hook;
    }

    /**
     * Holds {@code message} ({@code null}: nothing, for the end-of-stream
     * flush) behind {@code barrier} in a new segment, released by
     * {@link #releaseHeld} once the barrier completes and every earlier
     * segment is released. When nothing is held and the flush is already done
     * the message goes straight to the queue: every report of that flush has
     * been placed ahead of it already.
     */
    private void holdBehind(TranscriptMirrorBatcher.Barrier barrier, @Nullable Map<String, Object> message) {
        CompletableFuture<Void> done = barrier.done();
        UnaryOperator<CompletableFuture<Void>> hook = barrierHookForTest;
        if (hook != null) {
            done = hook.apply(done);
        }
        HeldSegment segment;
        queueLock.lock();
        try {
            if (closed.get()) {
                return;
            }
            if (holdback.isEmpty() && done.isDone()) {
                if (message != null) {
                    messageQueue.add(message);
                }
                return;
            }
            segment = new HeldSegment(barrier.drain());
            if (message != null) {
                segment.messages.add(message);
                heldCount++;
            }
            holdback.addLast(segment);
        } finally {
            queueLock.unlock();
        }
        // Outside the lock: an already-completed barrier releases inline.
        done.whenComplete((r, e) -> releaseHeld(segment));
    }

    /**
     * Marks {@code segment}'s flush complete and releases, in order, every
     * segment at the head of the holdback whose flush has completed: each
     * one's mirror errors, then its messages. A segment whose flush completes
     * before an earlier one's waits for it. Idempotent, safe on any thread
     * (it runs on whichever completes the flush), and a no-op once the
     * handler is closed, so a release racing close() delivers nothing.
     */
    private void releaseHeld(HeldSegment segment) {
        queueLock.lock();
        try {
            segment.flushed = true;
            if (closed.get()) {
                return;
            }
            HeldSegment head;
            while (((head = holdback.peekFirst()) != null) && head.flushed) {
                holdback.removeFirst();
                heldCount -= head.size();
                messageQueue.addAll(head.mirrorErrors);
                messageQueue.addAll(head.messages);
            }
            if (holdback.isEmpty()) {
                holdbackReleased.signalAll();
            }
            // Released messages can now be drained, and with nothing held
            // the hard ceiling no longer applies; a reader parked at a limit
            // re-checks.
            queueRoom.signalAll();
        } finally {
            queueLock.unlock();
        }
    }

    /**
     * Waits, at the end of the stream, until every held segment has been
     * released, or the handler closes. Each flush is bounded by the batcher,
     * so this is too.
     */
    private void awaitHoldbackReleased() {
        queueLock.lock();
        try {
            while (!holdback.isEmpty() && !closed.get()) {
                holdbackReleased.await(QUEUE_WAKE_MS, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            // Only close() interrupts the reader; what is still held is
            // dropped with it.
            Thread.currentThread().interrupt();
        } finally {
            queueLock.unlock();
        }
    }

    /** Drops every held message. Caller holds queueLock. */
    private void dropHoldback() {
        if (!holdback.isEmpty()) {
            logger.fine("Dropping " + heldCount + " message(s) held behind a transcript-mirror flush");
        }
        holdback.clear();
        heldCount = 0;
        holdbackReleased.signalAll();
        queueRoom.signalAll();
    }

    /** Wakes a reader waiting in {@link #enqueueBlocking} to re-check. */
    private void signalQueueRoom() {
        queueLock.lock();
        try {
            queueRoom.signalAll();
        } finally {
            queueLock.unlock();
        }
    }

    /**
     * Settles the outbound request a {@code control_response} answers.
     *
     * <p>
     * As lenient as the Python SDK, so a malformed frame can never kill the
     * reader: a frame without a known {@code request_id} is ignored, any
     * {@code subtype} other than {@code "error"} counts as success, and a
     * success body that is not an object reads as an empty one.
     */
    private void handleControlResponse(Map<String, Object> message) {
        if (!(message.get("response") instanceof Map<?, ?> response)
                || !(response.get("request_id") instanceof String requestId)) {
            return;
        }
        CompletableFuture<ControlResponse> future = pendingControlResponses.remove(requestId);
        if (future == null) {
            return;
        }

        if ("error".equals(response.get("subtype"))) {
            Object error = response.get("error");
            future.completeExceptionally(new ClaudeSDKException(
                    (error != null) ? String.valueOf(error) : "Unknown error"));
        } else {
            Map<String, Object> body = new LinkedHashMap<>();
            if (response.get("response") instanceof Map<?, ?> data) {
                for (Map.Entry<?, ?> entry : data.entrySet()) {
                    body.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            future.complete(new ControlResponse(requestId, body));
        }
    }

    /**
     * Starts answering one control request from the CLI on its own thread,
     * tracked by {@code request_id} so a {@code control_cancel_request} can
     * stop it (Python SDK #751).
     */
    private void submitControlRequest(Map<String, Object> message) {
        InflightControlRequest inflight = new InflightControlRequest();
        String trackedId = ((message.get("request_id") instanceof String id) ? id : null);
        if (trackedId != null) {
            // Registered before the task starts, so it is always there to be
            // removed: the handler cannot finish ahead of the registration.
            inflightControlRequests.put(trackedId, inflight);
        }
        runningControlRequests.add(inflight);
        try {
            inflight.task(controlExecutor.submit(() -> {
                if (!inflight.begin()) {
                    return; // close() gave up on it before it started
                }
                servingRequest.set(inflight);
                try {
                    handleControlRequest(message, inflight);
                } finally {
                    servingRequest.remove();
                    if (trackedId != null) {
                        inflightControlRequests.remove(trackedId, inflight);
                    }
                    runningControlRequests.remove(inflight);
                    inflight.finish();
                }
            }));
        } catch (RejectedExecutionException e) {
            // Closing: the request will never be handled, so stop tracking it.
            if (trackedId != null) {
                inflightControlRequests.remove(trackedId, inflight);
            }
            runningControlRequests.remove(inflight);
            inflight.finish();
            throw e;
        }
    }

    /**
     * One control request from the CLI that is still being answered.
     *
     * <p>
     * It ends exactly one way: answered, or cancelled by the CLI. Settling
     * that with one compare-and-set keeps a cancellation from interrupting a
     * handler partway through writing its response, and a handler that loses
     * the race from writing a response the CLI no longer expects.
     */
    static final class InflightControlRequest {

        private static final int OPEN = 0;
        private static final int ANSWERING = 1;
        private static final int CANCELLED = 2;

        // Whether the handler ran, independent of how the request ended:
        // close() waits for a handler that began, and skips one that never
        // will. Settled by compare-and-set, so the two cannot both happen.
        private static final int NOT_STARTED = 0;
        private static final int RUNNING = 1;
        private static final int SKIPPED = 2;

        private final AtomicInteger state = new AtomicInteger(OPEN);
        private final AtomicInteger phase = new AtomicInteger(NOT_STARTED);
        private final CompletableFuture<Void> finished = new CompletableFuture<>();
        private volatile @Nullable Future<?> task;
        private volatile @Nullable CompletableFuture<?> awaiting;
        // Written before the cancelling compare-and-set, so task() sees it.
        private volatile boolean interruptOnCancel = true;

        /** Called by the handler as it starts; false if close() skipped it. */
        boolean begin() {
            return phase.compareAndSet(NOT_STARTED, RUNNING);
        }

        /** Called by the handler as it ends. */
        void finish() {
            finished.complete(null);
        }

        /**
         * Completes once the handler has ended, or at once for a handler that
         * has not started (it then never will).
         */
        CompletableFuture<Void> whenFinished() {
            if (phase.compareAndSet(NOT_STARTED, SKIPPED)) {
                finished.complete(null);
            }
            return finished;
        }

        void task(Future<?> submitted) {
            task = submitted;
            if (isCancelled()) {
                submitted.cancel(interruptOnCancel);
            }
        }

        boolean isCancelled() {
            return (state.get() == CANCELLED);
        }

        /** Claims the right to respond; false once the CLI has cancelled. */
        boolean beginAnswer() {
            return state.compareAndSet(OPEN, ANSWERING);
        }

        /**
         * Cancels the user callback's future, so code composed on it can stop,
         * then interrupts the handler thread waiting on it. A no-op once the
         * handler has begun answering.
         */
        void cancel() {
            cancel(true);
        }

        /**
         * As {@link #cancel()}, but with {@code interruptHandler} false the
         * handler thread is left alone: the request is still marked cancelled,
         * so no response is written, and its callback's future is still
         * cancelled. That is how {@link QueryHandler#close()} cancels the
         * request whose own callback called it.
         */
        void cancel(boolean interruptHandler) {
            if (state.get() != OPEN) {
                return;
            }
            interruptOnCancel = interruptHandler;
            if (!state.compareAndSet(OPEN, CANCELLED)) {
                return;
            }
            CompletableFuture<?> callback = awaiting;
            if (callback != null) {
                callback.cancel(true);
            }
            Future<?> running = task;
            if (running != null) {
                running.cancel(interruptHandler);
            }
        }

        /**
         * Waits, with no deadline, for a user callback's result. Throws
         * {@link java.util.concurrent.CancellationException} or
         * {@link InterruptedException} once the request is cancelled, by the
         * CLI or by {@link QueryHandler#close()}.
         */
        <T> T await(CompletableFuture<T> callback) throws InterruptedException, ExecutionException {
            return track(callback).get();
        }

        private <T> CompletableFuture<T> track(CompletableFuture<T> callback) {
            awaiting = callback;
            if (isCancelled()) {
                // Cancelled before the callback was handed over.
                callback.cancel(true);
            }
            return callback;
        }
    }

    /**
     * Handles incoming control requests from CLI using strongly-typed classes.
     */
    private void handleControlRequest(Map<String, Object> message, InflightControlRequest inflight) {
        String requestId = null;
        try {
            // Deserialize to typed SDKControlRequest
            SDKControlRequest controlRequest = readControlRequest(message);

            requestId = controlRequest.requestId();
            SDKControlRequestData requestData = controlRequest.request();

            Map<String, Object> responseData = new HashMap<>();

            // Dispatch on request type (discriminated union).
            // An instanceof chain rather than a pattern switch: the SDK targets Java 17.
            // SealedExhaustivenessTest guards these arms against new
            // SDKControlRequestData subtypes, which the compiler no longer can.
            if (requestData instanceof SDKControlPermissionRequest permissionReq) {
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

                // No SDK-side deadline, as in the Python SDK: a human approving
                // a tool can take as long as it takes. The wait ends when the
                // callback completes, the CLI cancels the request
                // (control_cancel_request) or the handler closes.
                CompletableFuture<PermissionResult> resultFuture = canUseTool.apply(toolName, input, context);
                if (resultFuture == null) {
                    throw new ClaudeSDKException("canUseTool callback returned a null future");
                }
                PermissionResult result = inflight.await(resultFuture);
                if (result == null) {
                    throw new ClaudeSDKException(
                            "canUseTool callback completed with null; return PermissionResultAllow or PermissionResultDeny");
                }

                // Serialize permission result to response format
                if (result instanceof PermissionResultAllow allow) {
                    responseData = allow.toMap(input);
                } else if (result instanceof PermissionResultDeny deny) {
                    responseData = deny.toMap(input);
                } else {
                    throw new ClaudeSDKException(
                            "Unhandled permission result type: " + result.getClass().getName());
                }
            } else if (requestData instanceof SDKHookCallbackRequest hookReq) {
                String callbackId = hookReq.callbackId();
                var callback = hookCallbacks.get(callbackId);
                if (callback == null) {
                    throw new ClaudeSDKException("No hook callback found for ID: " + callbackId);
                }

                HookInput hookInput = hookReq.input();
                String toolUseId = hookReq.toolUseId();
                HookContext context = new HookContext(toolUseId);

                // A failed hook must answer with an error frame, never success:
                // HookMatcher.onFailure(BLOCK) relies on it, as the CLI blocks
                // only on an error reply. A null future or a null output is a
                // failure (a callback with no opinion returns an empty
                // HookOutput); an exceptional future reaches the catch below.
                CompletableFuture<HookOutput> outputFuture = callback.apply(hookInput, context);
                if (outputFuture == null) {
                    throw new ClaudeSDKException("Hook callback " + callbackId + " returned a null future");
                }
                // No SDK-side deadline either: the CLI enforces the matcher's
                // timeout itself and follows it with control_cancel_request.
                HookOutput output = inflight.await(outputFuture);
                if (output == null) {
                    throw new ClaudeSDKException("Hook callback " + callbackId
                            + " completed with null; return an empty HookOutput for no decision");
                }
                responseData = output.toMap();
            } else if (requestData instanceof SDKControlMcpMessageRequest mcpReq) {
                responseData.put("mcp_response",
                        handleSdkMcpRequest(mcpReq.serverName(), mcpReq.message(), inflight));
            } else if (requestData instanceof SDKControlMCPStatusRequest) {
                // MCP status is sent from SDK to CLI, not CLI to SDK
                throw new ClaudeSDKException("Unexpected mcp status request from CLI: " + requestData);
            } else if (requestData instanceof SDKControlInterruptRequest) {
                // Interrupt is sent from SDK to CLI, not CLI to SDK
                throw new ClaudeSDKException("Unexpected interrupt request from CLI: " + requestData);
            } else if (requestData instanceof SDKControlInitializeRequest) {
                // Initialize is sent from SDK to CLI, not CLI to SDK
                throw new ClaudeSDKException("Unexpected initialize request from CLI: " + requestData);
            } else if (requestData instanceof SDKControlSetPermissionModeRequest) {
                // Set permission mode is sent from SDK to CLI, not CLI to SDK
                throw new ClaudeSDKException("Unexpected set_permission_mode request from CLI: " + requestData);
            } else if (requestData instanceof SDKControlSetModelRequest) {
                // Set model is sent from SDK to CLI, not CLI to SDK
                throw new ClaudeSDKException("Unexpected set_model request from CLI: " + requestData);
            } else if (requestData instanceof SDKControlRewindFilesRequest) {
                // Rewind files is sent from SDK to CLI, not CLI to SDK
                throw new ClaudeSDKException("Unexpected rewind_files request from CLI: " + requestData);
            } else if (requestData instanceof SDKControlMcpReconnectRequest) {
                // MCP reconnect is sent from SDK to CLI, not CLI to SDK
                throw new ClaudeSDKException("Unexpected mcp_reconnect request from CLI: " + requestData);
            } else if (requestData instanceof SDKControlMcpToggleRequest) {
                // MCP toggle is sent from SDK to CLI, not CLI to SDK
                throw new ClaudeSDKException("Unexpected mcp_toggle request from CLI: " + requestData);
            } else if (requestData instanceof SDKControlStopTaskRequest) {
                // Stop task is sent from SDK to CLI, not CLI to SDK
                throw new ClaudeSDKException("Unexpected stop_task request from CLI: " + requestData);
            } else if (requestData instanceof SDKControlGetContextUsageRequest) {
                // Get context usage is sent from SDK to CLI, not CLI to SDK
                throw new ClaudeSDKException("Unexpected get_context_usage request from CLI: " + requestData);
            } else {
                throw new ClaudeSDKException(
                        "Unhandled control request type: " + requestData.getClass().getName());
            }

            // Send success response
            if (inflight.beginAnswer()) {
                sendControlResponse(new ControlResponse(requestId, responseData));
            } else {
                logger.fine("Dropping the response to cancelled control request " + requestId);
            }
        } catch (Throwable e) {
            // Throwable, not Exception: this runs on a submit()ed task whose
            // Future nobody reads, so an Error here used to vanish without a
            // trace and leave the CLI waiting forever on a response that could
            // never arrive. Answer first, then let a genuine Error keep going.
            if (requestId == null) {
                // Try to extract from raw message if deserialization failed
                requestId = (String) message.get("request_id");
            }
            Level level = ((e instanceof Exception) ? Level.WARNING : Level.SEVERE);
            if (!inflight.beginAnswer()) {
                // The CLI cancelled it: the interruption or cancellation that
                // got us here is expected, and nobody is waiting for an answer.
                logger.fine("Control request " + requestId + " cancelled by the CLI");
            } else if (requestId != null) {
                logger.log(level, "Error in handling control request", e);
                sendControlResponse(new ControlErrorResponse(requestId, describeThrowable(e)));
            } else {
                logger.log(level, "Failed to handle control request without request_id", e);
            }
            if (e instanceof Error error) {
                throw error;
            }
        }
    }

    /**
     * Opens this connection's session of every SDK MCP server.
     *
     * <p>
     * A handler whose {@code openSession()} fails or returns null is used
     * directly, as it would have been before sessions existed.
     */
    @Nullable
    private static Map<String, McpMessageHandler> openSdkMcpSessions(
            @Nullable Map<String, McpMessageHandler> servers) {
        if (servers == null) {
            return null;
        }
        Map<String, McpMessageHandler> sessions = new HashMap<>();
        for (Map.Entry<String, McpMessageHandler> entry : servers.entrySet()) {
            McpMessageHandler server = entry.getValue();
            McpMessageHandler session = null;
            try {
                session = server.openSession();
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, "SDK MCP server '" + entry.getKey()
                        + "' could not open a session; sharing the handler instead", e);
            }
            sessions.put(entry.getKey(), (session != null) ? session : server);
        }
        return sessions;
    }

    /**
     * Reads a CLI control request into its typed form.
     *
     * <p>
     * A hook callback's {@code input} map is supplied to the {@link HookInput}
     * record as well, so {@link HookInput#raw()} carries every key the CLI
     * sent, including the ones the record does not model.
     */
    static SDKControlRequest readControlRequest(Map<String, Object> message) {
        InjectableValues.Std injectables = new InjectableValues.Std();
        if ((message.get("request") instanceof Map<?, ?> request)
                && (request.get("input") instanceof Map<?, ?> input)) {
            injectables.addValue(HookInput.RAW_INJECTABLE_ID, input);
        }
        JsonNode tree = MAPPER.valueToTree(message);
        try {
            return MAPPER.readerFor(SDKControlRequest.class).with(injectables).readValue(tree);
        } catch (IOException e) {
            throw new IllegalArgumentException("Malformed control request: " + e.getMessage(), e);
        }
    }

    /**
     * Routes one JSON-RPC message to this connection's session of the named
     * SDK MCP server.
     *
     * <p>
     * A control request with no {@code server_name} or no {@code message}
     * (an empty one included) is malformed, and fails as a control error, as
     * in the Python SDK. Otherwise this always returns a payload for the
     * enclosing control response. A message that cannot be delivered at all —
     * unknown server, a handler that threw — is answered with a JSON-RPC error
     * <i>inside</i> {@code mcp_response}, so only that one MCP call fails
     * instead of the whole control request. That is what the Python SDK does,
     * and it keeps the control channel healthy through a broken server.
     *
     * @param serverName the SDK MCP server the CLI addressed
     * @param message    the JSON-RPC message to deliver
     * @param inflight   the control request carrying it; cancelling that
     *                   cancels the handler's future
     * @return the payload for the {@code mcp_response} field
     * @throws ClaudeSDKException if the server name or the message is missing
     */
    private Map<String, Object> handleSdkMcpRequest(
            @Nullable String serverName, @Nullable Map<String, Object> message,
            InflightControlRequest inflight) {

        if ((serverName == null) || serverName.isEmpty() || (message == null) || message.isEmpty()) {
            throw new ClaudeSDKException("Missing server_name or message for MCP request");
        }
        Object id = message.get("id");

        Map<String, McpMessageHandler> servers = sdkMcpSessions;
        McpMessageHandler server = ((servers != null) ? servers.get(serverName) : null);
        if (server == null) {
            return jsonRpcError(id, -32601, "Server '" + serverName + "' not found");
        }

        try {
            // No SDK-side deadline, matching the Python SDK. The CLI enforces
            // its own tool timeout and follows it with notifications/cancelled,
            // which settles the call; close() drains whatever is left. A cap
            // here would only turn "slow" into "the control request failed"
            // while leaving the tool running anyway.
            //
            // get(), not join(): join() cannot be interrupted, which would
            // make controlExecutor.shutdownNow() a no-op on this thread.
            Map<String, Object> mcpResponse = inflight.await(server.handleMessage(message));
            // A notification gets no reply, but the control request that
            // carried one still expects an acknowledgement.
            return ((mcpResponse != null)
                    ? mcpResponse
                    : Map.of("jsonrpc", "2.0", "result", Map.of()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return jsonRpcError(id, -32603,
                    "Interrupted while waiting for SDK MCP server '" + serverName + "'");
        } catch (ExecutionException | RuntimeException e) {
            // RuntimeException covers CancellationException, which is what a
            // handler settled by close() completes with, and what a
            // control_cancel_request leaves behind (the caller then drops this
            // reply unsent).
            Throwable cause = ((e.getCause() != null) ? e.getCause() : e);
            logger.log((inflight.isCancelled() ? Level.FINE : Level.WARNING),
                    "SDK MCP server '" + serverName + "' failed", cause);
            return jsonRpcError(id, -32603, describeThrowable(cause));
        }
    }

    private static Map<String, Object> jsonRpcError(@Nullable Object id, int code, String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("error", Map.of("code", code, "message", message));
        return response;
    }

    /**
     * Exception text for an error response: the message, or the class name
     * when there is none; never null, never empty. Same rule as the MCP
     * package's {@code ToolSchemas.describeFailure}, which is package-private
     * there.
     */
    private static String describeThrowable(Throwable cause) {
        String message = cause.getMessage();
        return (((message != null) && !message.isBlank()) ? message : cause.getClass().getSimpleName());
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
        // A reader holding back for room must read on now, or this response
        // would wait behind messages nobody is draining.
        signalQueueRoom();

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
     * After this resolves, the CLI reports the task's end in the message stream
     * as a {@code task_updated} system message whose status is terminal
     * ({@code "killed"} for a stopped task). A {@code task_notification} with
     * status {@code "stopped"} may follow, but is sometimes suppressed, so clear
     * the task id on a terminal status from either message.
     *
     * @param taskId the task ID from the {@code task_started} system message
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
        Map<String, McpMessageHandler> mcpServers = sdkMcpServers;
        return ((hookConfig != null) && (!hookConfig.isEmpty()))
                || ((mcpServers != null) && (!mcpServers.isEmpty()))
                || (canUseTool != null);
    }

    /**
     * Waits for the end of the run (if needed), then closes stdin.
     *
     * <p>If SDK MCP servers, hooks, or a {@code canUseTool} callback require
     * bidirectional communication, keeps stdin open until the run ends: the
     * CLI's "idle" session state after a result, or, from a CLI that reports
     * no session state, the first result with no tracked tasks in flight. A
     * result frame ends one turn, not necessarily the run: background tasks
     * keep running past it, or have just finished and still wake the parent
     * for a follow-up turn, and those turns need stdin for control responses.
     *
     * <p>The wait is bounded between turns: if the CLI still reports "running"
     * {@code CLAUDE_CODE_PRINT_BG_WAIT_CEILING_MS} (10 minutes by default,
     * {@code 0} for no limit) after a result with no new turn, the run ends
     * anyway ({@link #armRunEndCeiling}). A turn under way, a request the SDK
     * is still answering and a tracked background agent still in flight stop
     * that clock. No other timeout applies: the wait is guaranteed to end when
     * the run ends as above, or when the reader exits or the handler closes.
     *
     * <p>Known limitation: from a CLI that reports no session state, a result
     * for an earlier prompt of a multi-message prompt iterator still ends the
     * run even when a later prompt is already queued CLI-side, so control
     * requests from that later turn can find stdin closed. Single-message and
     * string prompts, the common one-shot shapes, are fully covered.
     */
    private void waitForRunEndAndEndInput() {
        if (hasBidirectionalNeeds()) {
            logger.fine("Waiting for the run to end before closing stdin");
            CompletableFuture<Void> runEnded;
            synchronized (runLock) {
                runEnded = runEndedEvent;
            }
            try {
                runEnded.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                // Never completed exceptionally; close stdin regardless.
            }
        }
        synchronized (runLock) {
            runFinal = true;
            clearRunEndCeiling();
        }
        if (!closed.get()) {
            transport.endInput();
        }
    }

    /**
     * Tracks the CLI's {@code session_state_changed} state.
     */
    private void onSessionState(@Nullable Object state) {
        synchronized (runLock) {
            sessionState = (state instanceof String str) ? str : null;
            if ("idle".equals(sessionState)) {
                if (resultReceived) {
                    maybeEndRun();
                }
                return;
            }
            // Work the CLI took up after the run ended (a finished background
            // task woke it) reopens the run until the next "idle".
            reopenRun();
            if ("requires_action".equals(sessionState)) {
                // The host is answering a request; stdin must outlast it.
                clearRunEndCeiling();
            } else {
                rearmRunEndCeilingBetweenTurns();
            }
        }
    }

    /**
     * Ends the run unless a tracked background task is still in flight.
     *
     * <p>One turn ended, but background tasks that are still running may need
     * hook/SDK-MCP control responses over stdin; closing it now silently
     * disables hooks and fails SDK-MCP calls with "Stream closed". Each task
     * completion wakes the parent for a follow-up turn, so a later result (or
     * "idle") ends the run then. A CLI that reports session state never
     * reports "idle" with an agent still live, so this matters for CLIs that
     * report "idle" at every turn end or not at all. Caller holds
     * {@code runLock}.
     */
    private void maybeEndRun() {
        if (!inflightTasks.isEmpty()) {
            logger.fine("Turn ended with " + inflightTasks.size()
                    + " task(s) in flight; keeping stdin open");
            return;
        }
        endRun();
    }

    /**
     * The run is over: wake the stdin-closing waiter. Idempotent. Caller holds
     * {@code runLock}.
     */
    private void endRun() {
        clearRunEndCeiling();
        runEndedEvent.complete(null);
    }

    /**
     * Reopens an ended run for work that started after it ended.
     *
     * <p>A waiter the ended run already woke still closes stdin; this makes a
     * later wait (streamInput's, once its prompts are all written) wait for
     * the new work too. Once stdin is closed, or the reader is gone, the run
     * stays ended. Caller holds {@code runLock}.
     */
    private void reopenRun() {
        if (runEndedEvent.isDone() && !runFinal) {
            runEndedEvent = new CompletableFuture<>();
        }
    }

    /**
     * Ends the run anyway once the ceiling passes with no new turn.
     *
     * <p>The CLI's own background-wait ceiling only counts once stdin is
     * closed, so without this, work that never finishes would hold "running",
     * and stdin, open forever. It counts only the wait between turns:
     * restarted at each result and whenever the CLI reports "running" again,
     * cleared by main-thread turn activity and by "requires_action", and never
     * armed while a turn is under way. Caller holds {@code runLock}.
     */
    private void armRunEndCeiling() {
        clearRunEndCeiling();
        if (runEndCeilingMs <= 0
                || runEndedEvent.isDone()
                || runFinal
                || closed.get()
                || turnInProgress
                || !hasBidirectionalNeeds()) {
            return;
        }
        long generation = runEndCeilingGeneration;
        runEndCeilingThread = Threads.start("QueryHandler-RunEndCeiling-",
                () -> endRunAtCeiling(generation));
    }

    /**
     * Restarts the ceiling if the run is between turns, past a result, with
     * the CLI still reporting work ("running"). Caller holds {@code runLock}.
     */
    private void rearmRunEndCeilingBetweenTurns() {
        if (resultReceived && sessionState != null
                && !"idle".equals(sessionState)
                && !"requires_action".equals(sessionState)) {
            armRunEndCeiling();
        }
    }

    private void endRunAtCeiling(long generation) {
        try {
            Thread.sleep(Math.min(runEndCeilingMs, MAX_RUN_END_CEILING_MS));
        } catch (InterruptedException e) {
            // Cleared or re-armed.
            return;
        }
        synchronized (runLock) {
            // Cleared or re-armed while this sleeper was already waking up.
            if (generation != runEndCeilingGeneration) {
                return;
            }
            runEndCeilingThread = null;
            if (!inflightTasks.isEmpty()) {
                // A tracked background agent is still running and may still
                // need stdin for its hook, permission and SDK MCP requests, so
                // it is not cut off, as without session state. The ceiling
                // starts over once it settles (readMessages).
                logger.fine("No 'idle' " + runEndCeilingMs + "ms after the last result, but "
                        + inflightTasks.size() + " tracked task(s) still in flight; keeping stdin open");
                return;
            }
            logger.fine("No 'idle' " + runEndCeilingMs + "ms after the last result; ending the run");
            endRun();
        }
    }

    /** Control requests from the CLI still being answered. Package-private for testing. */
    int inflightControlRequestCount() {
        return inflightControlRequests.size();
    }

    /** Whether a run-end ceiling sleeper is armed. Package-private for testing. */
    boolean runEndCeilingArmed() {
        synchronized (runLock) {
            return runEndCeilingThread != null;
        }
    }

    /** Caller holds {@code runLock}. */
    private void clearRunEndCeiling() {
        runEndCeilingGeneration++;
        Thread sleeper = runEndCeilingThread;
        runEndCeilingThread = null;
        if (sleeper != null) {
            sleeper.interrupt();
        }
    }

    /**
     * Streams input messages to transport, then closes stdin.
     *
     * <p>If SDK MCP servers, hooks, or a {@code canUseTool} callback are
     * present, waits for the run to end before closing stdin so bidirectional
     * control protocol traffic keeps working. Each prompt written owes a run
     * of its own, so the wait is for the last prompt's run, not an earlier
     * one's. See {@link #waitForRunEndAndEndInput()}.
     */
    public void streamInput(Iterator<Map<String, Object>> stream) {
        int written = 0;
        try {
            while (stream.hasNext() && (!closed.get())) {
                Map<String, Object> message = stream.next();
                // This prompt owes a run of its own, result included: an
                // earlier one having ended does not end it.
                synchronized (runLock) {
                    reopenRun();
                    resultReceived = false;
                    clearRunEndCeiling();
                }
                String json = MAPPER.writeValueAsString(stampUserMessage(message, verbatimPrompts));
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
            if (written > 0) {
                waitForRunEndAndEndInput();
            } else {
                // Nothing was sent, so no result will arrive to release the
                // hold; close immediately (mirrors the TypeScript SDK's
                // messageCount guard).
                synchronized (runLock) {
                    runFinal = true;
                }
                if (!closed.get()) {
                    transport.endInput();
                }
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
     * <p>
     * If the consuming thread is interrupted while {@code hasNext()} waits,
     * the interrupt flag is restored and {@code hasNext()} throws a
     * {@link ClaudeSDKException} ("Interrupted while waiting for the next
     * message") whose cause is the {@link InterruptedException}, rather than
     * reporting a normal end of stream.
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
     * <li>Drops the messages still held behind a transcript-mirror flush;
     * every iterator ends at once, and a flush that completes later releases
     * nothing</li>
     * <li>Waits for the final transcript-mirror flush (bounded by the batcher)</li>
     * <li>Completes all pending control request futures exceptionally</li>
     * <li>Cancels the control request handlers and waits for them to end</li>
     * <li>Closes the underlying transport (stdin EOF, then a bounded wait for
     * the CLI to exit) and waits for the reader thread to terminate</li>
     * <li>Clears all callback maps</li>
     * </ul>
     *
     * <p>
     * It may be called from inside a callback (a {@code canUseTool}, a hook or
     * an SDK MCP tool), for example through {@code ClaudeSDKClient.disconnect()}.
     * The request that callback serves is cancelled, so it is never answered,
     * but its thread, the one closing, is neither interrupted nor waited for.
     *
     * <p>
     * It is safe to call with the interrupt flag set, or to interrupt while it
     * runs: the flag is cleared for the duration, so every step still runs to
     * its bound (the CLI still gets stdin EOF and its grace period, the reader
     * is still joined), and it is set again on return.
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

        // An interrupted caller (e.g. ClaudeSDK.query() after its consumer was
        // interrupted) must still close gracefully: every wait below would
        // otherwise fail at once, leaving the CLI without stdin EOF and the
        // reader unjoined. The flag is restored on the way out.
        AtomicBoolean interrupted = new AtomicBoolean(Thread.interrupted());
        try {
            closeGracefully(interrupted);
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Error during close", e);
        } finally {
            if (Thread.interrupted()) {
                interrupted.set(true);
            }
            if (interrupted.get()) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void closeGracefully(AtomicBoolean interrupted) {
        logger.fine("Closing QueryHandler");

        // The control request whose callback is closing us, if any.
        InflightControlRequest self = servingRequest.get();

        // 0. Drop what is held behind a mirror flush. Iterators end as soon
        // as closed is set, so it would never be read; a release that
        // completes later finds closed set and delivers nothing.
        queueLock.lock();
        try {
            dropHoldback();
        } finally {
            queueLock.unlock();
        }

        // 0a. Final-flush mirror entries before tearing down so .return()/break
        // don't drop the current turn when the process exits immediately.
        TranscriptMirrorBatcher batcher = transcriptMirrorBatcher;
        if (batcher != null) {
            awaitThroughInterrupts(batcher.close(), Long.MAX_VALUE, interrupted);
        }

        // 1. Complete all pending futures exceptionally
        ClaudeSDKException closedException = new ClaudeSDKException("QueryHandler is closed");
        for (CompletableFuture<ControlResponse> future : pendingControlResponses.values()) {
            future.completeExceptionally(closedException);
        }
        pendingControlResponses.clear();

        // 1a. Cancel the CLI's requests still being answered, as the Python
        //     SDK cancels its child tasks. Inbound callbacks have no
        //     deadline (a canUseTool may be waiting on a human), so close()
        //     ends them instead of waiting for them: the callback's future
        //     is cancelled and its handler thread interrupted, and nothing
        //     is answered (the CLI is going away). Every running handler is
        //     reached, request_id or not. The one calling close() is
        //     cancelled without the interrupt: interrupting it would
        //     interrupt this close().
        for (InflightControlRequest inflight : runningControlRequests) {
            inflight.cancel(inflight != self);
        }
        inflightControlRequests.clear();

        // 1b. Tell the SDK MCP servers the connection is going away. A
        //     tool still running holds a control thread on a wait with no
        //     deadline, and only its server can end it -- nothing here can
        //     interrupt a handler. Without this, the wait below would sit
        //     out its full timeout on any run with a tool in flight. Only
        //     this connection's sessions: another client sharing a server
        //     keeps its calls.
        Map<String, McpMessageHandler> servers = sdkMcpSessions;
        if (servers != null) {
            for (Map.Entry<String, McpMessageHandler> entry : servers.entrySet()) {
                try {
                    entry.getValue().close();
                } catch (RuntimeException e) {
                    logger.log(Level.WARNING,
                            "SDK MCP server '" + entry.getKey() + "' failed to close", e);
                }
            }
        }

        // Release the stdin-closing waiter and stop any ceiling sleeper
        synchronized (runLock) {
            runFinal = true;
            endRun();
        }
        // A reader waiting for room in the queue notices close() at once.
        signalQueueRoom();

        // 2. Close the transport to unblock the reader thread. It closes
        //    stdin and gives the CLI its bounded grace period to exit.
        try {
            transport.close();
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Error closing transport", e);
        }
        // A transport restores an interrupt that arrived while it closed.
        if (Thread.interrupted()) {
            interrupted.set(true);
        }

        // 3. Shut down both executors. shutdown(), not shutdownNow(): the
        //    handlers were cancelled (and, all but our own, interrupted) in
        //    1a, and shutdownNow() would interrupt this very thread when
        //    close() runs inside a callback.
        readerExecutor.shutdown();
        controlExecutor.shutdown();
        logger.fine("Initiated executors shutdown, waiting for tasks to complete");

        // 4. Wait for the reader to terminate (should be quick since the
        //    transport is closed).
        if (Uninterruptibly.awaitTermination(readerExecutor, READER_THREAD_JOIN_TIMEOUT_SECS, TimeUnit.SECONDS, interrupted)) {
            logger.fine("Reader executor terminated gracefully");
        } else {
            logger.warning("Reader executor did not terminate gracefully, forcing shutdown");
            readerExecutor.shutdownNow();
            if (!Uninterruptibly.awaitTermination(readerExecutor, 2, TimeUnit.SECONDS, interrupted)) {
                logger.warning(
                        "Reader executor did not terminate even after shutdownNow() - threads may still be running");
            }
        }

        // 5. Wait for the control handlers to end, all but the one calling
        //    close(), which cannot end before close() returns.
        if (awaitControlHandlers(self, TimeUnit.SECONDS.toMillis(CONTROL_THREAD_JOIN_TIMEOUT_SECS), interrupted)) {
            logger.fine("Control handlers terminated gracefully");
        } else {
            logger.warning("Control handlers did not terminate gracefully within "
                    + CONTROL_THREAD_JOIN_TIMEOUT_SECS + " seconds, forcing shutdown");
            if (self == null) {
                // Interrupts them again; never from a handler, whose own
                // thread it would interrupt too.
                List<Runnable> pendingTasks = controlExecutor.shutdownNow();
                logger.fine("Cancelled " + pendingTasks.size() + " pending control tasks");
            }
            if (!awaitControlHandlers(self, TimeUnit.SECONDS.toMillis(2), interrupted)) {
                logger.warning("Control executor did not terminate after shutdownNow() - "
                        + "control request handler threads may still be running");
            }
        }

        // 6. Clear all callback maps to prevent memory leaks
        hookCallbacks.clear();

        // 7. Clear message queue
        messageQueue.clear();

        logger.fine("QueryHandler closed successfully");
    }

    /**
     * Waits for every running control handler except {@code self}, for at
     * most {@code timeoutMs}.
     *
     * @return whether they all ended in time
     */
    private boolean awaitControlHandlers(@Nullable InflightControlRequest self, long timeoutMs,
            AtomicBoolean interrupted) {
        List<CompletableFuture<Void>> others = new ArrayList<>();
        for (InflightControlRequest inflight : runningControlRequests) {
            if (inflight != self) {
                others.add(inflight.whenFinished());
            }
        }
        return awaitThroughInterrupts(CompletableFuture.allOf(others.toArray(new CompletableFuture<?>[0])),
                timeoutMs, interrupted);
    }

    /**
     * Waits for {@code future} for at most {@code timeoutMs}
     * ({@link Long#MAX_VALUE}: no limit, for a future that bounds itself),
     * recording rather than obeying interrupts. Never throws.
     *
     * @return whether the future completed in time
     */
    private static boolean awaitThroughInterrupts(CompletableFuture<?> future, long timeoutMs,
            AtomicBoolean interrupted) {
        long deadline = (timeoutMs == Long.MAX_VALUE)
                ? Long.MAX_VALUE
                : System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (true) {
            try {
                if (deadline == Long.MAX_VALUE) {
                    future.get();
                } else {
                    future.get(Math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                }
                return true;
            } catch (InterruptedException e) {
                interrupted.set(true);
            } catch (TimeoutException e) {
                return false;
            } catch (ExecutionException | RuntimeException e) {
                logger.fine("Wait during close completed exceptionally: " + e.getMessage());
                return true;
            }
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
                // Poll with a timeout rather than take(), so the loop wakes to
                // notice close() and the stream's terminal state.
                while (!done) {
                    // Check closed flag to avoid blocking forever
                    if (closed.get()) {
                        done = true;
                        return false;
                    }

                    // Read the terminal flag BEFORE polling. The reader sets it
                    // after its last enqueue, so once it is seen, a poll that
                    // finds the queue empty means the stream is fully drained:
                    // end it, with the reader's error if it failed. This is
                    // what guarantees the end and the error even when the
                    // in-band markers could not be queued (queue full), and
                    // for an iterator created after another consumed them.
                    boolean ended = streamEnded;
                    Map<String, Object> message = (ended
                            ? messageQueue.poll()
                            : messageQueue.poll(QUEUE_WAKE_MS, TimeUnit.MILLISECONDS));
                    if ((message != null) && !ended) {
                        // There is room again for a reader holding back.
                        signalQueueRoom();
                    }
                    if ((message == null) && ended) {
                        done = true;
                        Map<String, Object> error = streamError;
                        if (error != null) {
                            throw toThrowable(error);
                        }
                        return false;
                    }
                    if (message != null) {
                        // Check for end marker
                        if ("end".equals(message.get("type"))) {
                            done = true;
                            // A stream that ended in an error ends in it for
                            // every iterator, also one that finds only the
                            // end marker because another took the error.
                            Map<String, Object> error = streamError;
                            if (error != null) {
                                throw toThrowable(error);
                            }
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
                // Not the end of the stream: ending quietly here made an
                // interrupted ClaudeSDK.query() return a truncated list.
                // The iterator stays usable: once the flag is cleared, a
                // later hasNext() waits again.
                Thread.currentThread().interrupt();
                throw new ClaudeSDKException("Interrupted while waiting for the next message", e);
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
