package in.vidyalai.claude.sdk.internal.transport;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;

import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.exceptions.CLIConnectionException;
import in.vidyalai.claude.sdk.exceptions.CLIJSONDecodeException;
import in.vidyalai.claude.sdk.exceptions.CLINotFoundException;
import in.vidyalai.claude.sdk.exceptions.ProcessException;
import in.vidyalai.claude.sdk.internal.PyStrings;
import in.vidyalai.claude.sdk.internal.SdkVersion;
import in.vidyalai.claude.sdk.internal.Threads;
import in.vidyalai.claude.sdk.internal.Uninterruptibly;
import in.vidyalai.claude.sdk.transport.Transport;
import in.vidyalai.claude.sdk.types.config.SdkBeta;
import in.vidyalai.claude.sdk.types.config.SettingSource;
import in.vidyalai.claude.sdk.types.config.SystemPromptCustom;
import in.vidyalai.claude.sdk.types.config.SystemPromptFile;
import in.vidyalai.claude.sdk.types.config.SystemPromptPreset;
import in.vidyalai.claude.sdk.types.config.ThinkingConfig;
import in.vidyalai.claude.sdk.types.config.ThinkingConfigAdaptive;
import in.vidyalai.claude.sdk.types.config.ThinkingConfigDisabled;
import in.vidyalai.claude.sdk.types.config.ThinkingConfigEnabled;
import in.vidyalai.claude.sdk.types.mcp.McpServerConfig;

/**
 * Subprocess transport implementation using Claude Code CLI.
 *
 * <p>
 * This transport spawns a Claude Code CLI process and communicates
 * via stdin/stdout using JSON streaming.
 *
 * <h2>Thread Safety</h2>
 * <p>
 * This class is <b>partially thread-safe</b> with the following guarantees:
 *
 * <ul>
 * <li><b>connect()</b>: Thread-safe. Multiple concurrent calls are protected by
 * synchronization.
 * Only one process will be spawned. Version check subprocess is properly
 * cleaned up even if it times out.</li>
 * <li><b>write()</b>: Thread-safe. Multiple threads can safely call write()
 * concurrently.</li>
 * <li><b>endInput()</b>: Thread-safe. Can be called concurrently with
 * write().</li>
 * <li><b>readMessages()</b>: <b>NOT thread-safe.</b> Can only be called ONCE
 * per instance.
 * Multiple calls will throw {@link IllegalStateException}. The returned
 * iterator reads
 * from a shared BufferedReader which is not thread-safe for concurrent
 * access.</li>
 * <li><b>close()</b>: Thread-safe. Can be called concurrently with other
 * operations. Every step is bounded, as in the Python SDK: the write lock
 * (5s), the wait for the CLI to exit after stdin EOF (5s), SIGTERM then 5s,
 * SIGKILL then 5s, and each executor shutdown (2s, then 1s forced). Logs
 * warnings if resources don't terminate within timeouts.</li>
 * </ul>
 *
 * <h2>Usage Pattern</h2>
 * <p>
 * The intended usage pattern is:
 * 
 * <pre>{@code
 * Transport transport = new SubprocessCLITransport(...);
 * transport.connect();  // Thread-safe
 *
 * // Option 1: Single-threaded
 * Iterator<Map<String, Object>> messages = transport.readMessages();  // Once only!
 * while (messages.hasNext()) {
 *     Map<String, Object> msg = messages.next();
 *     // Process message
 * }
 *
 * // Option 2: With concurrent writes
 * Thread reader = new Thread(() -> {
 *     Iterator<Map<String, Object>> messages = transport.readMessages();
 *     while (messages.hasNext()) {
 *         // Process messages
 *     }
 * });
 * reader.start();
 *
 * // From another thread:
 * transport.write("{...}");  // Thread-safe
 *
 * // Cleanup from any thread:
 * transport.close();  // Thread-safe
 * }</pre>
 *
 * <h2>Threading Architecture</h2>
 * <p>
 * This class uses two separate {@link ExecutorService} instances with named
 * virtual threads:
 * <ul>
 * <li><b>Stderr Executor:</b> Single-threaded executor for reading stderr
 * stream. Thread name: "SPCTransport-Stderr-0"</li>
 * <li><b>Message Reader Executor:</b> Single-threaded executor for reading
 * stdout messages. Thread name: "SPCTransport-MsgReader-0"</li>
 * </ul>
 *
 * <h2>Resource Management</h2>
 * <p>
 * This class manages multiple resources that are properly cleaned up on
 * {@link #close()}:
 * <ul>
 * <li>CLI subprocess and associated streams (stdin, stdout, stderr)</li>
 * <li>Stderr ExecutorService and its virtual thread</li>
 * <li>Message Reader ExecutorService and its virtual thread</li>
 * <li>Temporary files created for long command lines</li>
 * <li>File descriptors for all BufferedReaders and Writers</li>
 * </ul>
 *
 * <h2>Shutdown Behavior</h2>
 * <p>
 * When {@link #close()} is called:
 * <ol>
 * <li>Closes stdin to signal end of input to the CLI process, under the write
 * lock (waited for up to 5s; without it, stdin is closed later off the calling
 * thread)</li>
 * <li>Waits up to 5s for the CLI to exit, then sends SIGTERM and waits 5s, then
 * SIGKILL and waits 5s. stdout and stderr stay open meanwhile, so the CLI's
 * last output still reaches the readers</li>
 * <li>Shuts down the stderr and message reader executors (2s graceful, then 1s
 * forced)</li>
 * <li>Closes the stdout and stderr pipes underneath their readers</li>
 * </ol>
 * <p>
 * <b>Note:</b> In rare cases where threads are stuck in uninterruptible I/O or
 * the CLI process is hung, they may continue running briefly after
 * {@code close()} returns. This is logged as a warning. Such resources will
 * eventually terminate when they complete their blocking operation or are
 * force-killed by the OS.
 *
 * <p>
 * <b>Important:</b> Always call {@link #close()} when done to prevent resource
 * leaks.
 * This class is {@link AutoCloseable} and should be used with
 * try-with-resources when possible.
 *
 * @see Transport
 */
public class SubprocessCLITransport implements Transport {

    private static final Logger logger = Logger.getLogger(SubprocessCLITransport.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEFAULT_MSG_Q_SIZE = 1000;
    private static final int DEFAULT_MAX_BUFFER_SIZE = 1024 * 1024; // 1MB
    private static final String MINIMUM_CLAUDE_CODE_VERSION = "2.0.0";
    /**
     * First Claude Code version that honors {@code client_composed} on user
     * messages, which {@link ClaudeAgentOptions#verbatimPrompts()} relies on.
     */
    static final String VERBATIM_PROMPTS_MINIMUM_CLAUDE_CODE_VERSION = "2.1.248";

    /** The app label a developer sets, via {@link ClaudeAgentOptions#clientApp()} or the environment. */
    static final String EXPLICIT_CLIENT_APP_ENV = "CLAUDE_AGENT_SDK_CLIENT_APP";

    /**
     * The app label the Python SDK derives and passes apart from
     * {@link #EXPLICIT_CLIENT_APP_ENV}. This SDK never sets it, and drops an
     * inherited one.
     */
    static final String DERIVED_CLIENT_APP_ENV = "CLAUDE_AGENT_SDK_DERIVED_CLIENT_APP";

    /** A dotted version prefix, as the CLI reports it in {@code claude_code_version}. */
    private static final Pattern CLI_VERSION = Pattern.compile("(\\d{1,9})\\.(\\d{1,9})\\.(\\d{1,9})");
    /**
     * Asks the CLI for {@code session_state_changed} frames marked
     * {@code sdk_host_only}, which QueryHandler reads to tell when the run is
     * over and keeps out of the caller's stream. CLIs that predate it send no
     * frames.
     */
    static final String SDK_READS_SESSION_STATE_ENV = "CLAUDE_CODE_SDK_READS_SESSION_STATE";
    private static final String CLAUDE_CLI_NAME = "claude";

    /**
     * cmd.exe metacharacters, plus the quote character cmd.exe uses to toggle
     * its quoting state and {@code !}, which expands like {@code %} when delayed
     * expansion is enabled. Windows argument quoting follows the MSVCRT argv
     * rules — it adds quotes only around whitespace — so in a whitespace-free
     * argument these characters reach a cmd.exe command line verbatim. See
     * {@link #rejectWindowsCmdMetacharacters}.
     */
    private static final String CMD_EXE_METACHARACTERS = "&|<>^%!\"";

    /**
     * Characters that may never appear in a skill name. Parentheses and commas
     * are delimiters to the {@code --allowedTools} tokenizer; control characters
     * (C0, DEL, C1) never appear in a skill directory name. U+FEFF is here
     * rather than in the surrounding-whitespace check because the CLI trims it
     * as whitespace while {@link Character#isWhitespace} does not. See
     * {@link #validateSkillName}.
     */
    private static final Pattern SKILL_NAME_INVALID_CHARS =
            Pattern.compile("[(),\\x00-\\x1f\\x7f-\\x9f\\ufeff]");

    /**
     * The {@code skills} value meaning "every discovered skill". Mirrors the
     * Python SDK's {@code _SKILLS_ALL}.
     */
    private static final String SKILLS_ALL = "all";

    // Track live CLI subprocesses so we can terminate them when the parent
    // JVM exits. Mirrors the Python SDK's atexit handler and the TypeScript
    // SDK's parent-exit cleanup, preventing orphaned ``claude`` processes from
    // leaking when callers crash or exit before calling close().
    private static final Set<Process> ACTIVE_CHILDREN =
            ConcurrentHashMap.newKeySet();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            for (Process p : ACTIVE_CHILDREN) {
                try {
                    if (p.isAlive()) {
                        p.destroy();
                    }
                } catch (Exception ignored) {
                    // best-effort
                }
            }
            ACTIVE_CHILDREN.clear();
        }, "SPCTransport-ShutdownHook"));
    }

    private final ClaudeAgentOptions options;
    private final String cliPath;
    @Nullable
    private final Path cwd;
    private final int maxBufferSize;
    private final int maxMsgQSize;
    private final ReentrantLock writeLock = new ReentrantLock();
    private final AtomicBoolean iteratorCreated = new AtomicBoolean(false);
    private final AtomicBoolean ready = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    // Set once the version in the first init message has been checked; starts
    // set when CLAUDE_AGENT_SDK_SKIP_VERSION_CHECK disables the check.
    private final AtomicBoolean versionChecked = new AtomicBoolean(
            System.getenv("CLAUDE_AGENT_SDK_SKIP_VERSION_CHECK") != null);

    // Executor services for background tasks with named virtual threads
    private final ExecutorService stderrExecutor;
    private final ExecutorService messageReaderExecutor;

    @Nullable
    private volatile Process process;
    @Nullable
    private volatile BufferedWriter stdin;
    @Nullable
    private volatile Reader stdout;
    // Set once close() has finished, so a reader stuck on a pipe some other
    // process still holds open cannot keep hasNext() waiting forever.
    private volatile boolean closeCompleted;
    @Nullable
    private volatile Exception exitError;

    /**
     * Creates a new subprocess transport.
     *
     * @param options the agent options
     */
    public SubprocessCLITransport(ClaudeAgentOptions options) {
        this.options = options;
        Path path = options.cliPath();
        this.cliPath = ((path != null) ? path.toString()
                : findCli(System.getenv("PATH"), options.allowUnsafeWindowsBatchCli()));
        this.cwd = options.cwd();
        Integer buffSize = options.maxBufferSize();
        this.maxBufferSize = ((buffSize != null) ? buffSize : DEFAULT_MAX_BUFFER_SIZE);
        Integer msgQSize = options.maxMsgQSize();
        this.maxMsgQSize = ((msgQSize != null) ? msgQSize : DEFAULT_MSG_Q_SIZE);

        // Create named executor services for background tasks
        // Stderr reader: Single-threaded executor for reading stderr
        this.stderrExecutor = Threads.newSingleThreadExecutor("SPCTransport-Stderr-");

        // Message reader: Single-threaded executor for reading stdout messages
        this.messageReaderExecutor = Threads.newSingleThreadExecutor("SPCTransport-MsgReader-");
    }

    /**
     * Resolves the CLI path against the supplied {@code PATH} value.
     *
     * <p>
     * Package-private with {@code PATH} injected so the platform-specific
     * discovery order can be tested; the production caller passes
     * {@code System.getenv("PATH")}.
     *
     * <p>
     * On Windows only a native {@code claude.exe} is found, unless the caller
     * opted in with
     * {@link ClaudeAgentOptions.Builder#allowUnsafeWindowsBatchCli(boolean)}:
     * then, with no {@code claude.exe} anywhere, npm's {@code claude.cmd} (or a
     * {@code claude.bat}) shim is returned as a last resort. The Python SDK has
     * no such opt-in and never finds a shim; this keeps auto-discovery working
     * for Java callers who accepted that risk. The shim is looked up in
     * absolute {@code PATH} entries only, like {@code claude.exe}.
     *
     * @param pathEnv          the {@code PATH} environment value, or null
     * @param allowBatchShim   whether a Windows batch shim may be returned
     * @return the resolved CLI path
     * @throws CLINotFoundException if no CLI could be located
     */
    static String findCli(@Nullable String pathEnv, boolean allowBatchShim) {
        boolean windows = isWindows();

        List<Path> locations;
        if (windows) {
            // Only a native claude.exe: CreateProcess can't run an extensionless
            // wrapper script, and connect() refuses npm's claude.cmd shim. And
            // only in absolute PATH entries: a relative entry ("", ".", "tools")
            // names a directory under the working directory, which another
            // local user may control — a binary-planting probe.
            String exe = findInAbsoluteWindowsPath(CLAUDE_CLI_NAME + ".exe", pathEnv);
            if (exe != null) {
                return exe;
            }
            // The POSIX-shaped locations are deliberately not probed: a
            // rooted-but-driveless "/usr/local/bin/claude" resolves against
            // the current drive, and an extensionless match is not runnable.
            locations = List.of(Path.of(System.getProperty("user.home"), ".local", "bin",
                    CLAUDE_CLI_NAME + ".exe"));
        } else {
            String pathCli = findInPath(CLAUDE_CLI_NAME, pathEnv);
            if (pathCli != null) {
                return pathCli;
            }
            locations = List.of(
                    Path.of(System.getProperty("user.home"), ".npm-global", "bin", CLAUDE_CLI_NAME),
                    Path.of("/usr/local/bin/" + CLAUDE_CLI_NAME),
                    Path.of(System.getProperty("user.home"), ".local", "bin", CLAUDE_CLI_NAME),
                    Path.of(System.getProperty("user.home"), "node_modules", ".bin", CLAUDE_CLI_NAME),
                    Path.of(System.getProperty("user.home"), ".yarn", "bin", CLAUDE_CLI_NAME),
                    Path.of(System.getProperty("user.home"), ".claude", "local", CLAUDE_CLI_NAME));
        }

        for (Path path : locations) {
            if (Files.exists(path) && Files.isRegularFile(path)) {
                return path.toString();
            }
        }

        if (windows && allowBatchShim) {
            // Opted in to running a batch CLI: with no native executable
            // anywhere, fall back to npm's shim. connect() still enforces the
            // opt-in's conditions (enforceWindowsBatchCliPolicy).
            for (String shimName : List.of(CLAUDE_CLI_NAME + ".cmd", CLAUDE_CLI_NAME + ".bat")) {
                String shim = findInAbsoluteWindowsPath(shimName, pathEnv);
                if (shim != null) {
                    return shim;
                }
            }
        }

        if (windows) {
            // npm's Windows install is a claude.cmd shim, which connect()
            // refuses (rejectWindowsBatchCli) without the opt-in, so do not
            // recommend it.
            throw new CLINotFoundException("""
                    Claude Code not found. Install the native claude.exe with (PowerShell):
                      irm https://claude.ai/install.ps1 | iex
                    Or provide the path to a claude.exe via ClaudeAgentOptions:
                      ClaudeAgentOptions.builder().cliPath(Path.of("C:\\\\path\\\\to\\\\claude.exe"))
                    (npm install -g @anthropic-ai/claude-code produces a claude.cmd shim,
                    which this SDK refuses to run on Windows.)
                    """);
        }

        throw new CLINotFoundException("""
                Claude Code not found. Install by following: https://code.claude.com/docs/en/setup
                If already installed locally, try adding it to PATH Or provide the path via
                ClaudeAgentOptions: ClaudeAgentOptions(cli_path='/path/to/claude')
                """);
    }

    /**
     * The first {@code fileName} in an absolute {@code PATH} entry, for
     * Windows discovery.
     *
     * <p>
     * Relative entries are skipped because they resolve against the working
     * directory. Only the exact name is probed (no {@code PATHEXT}
     * resolution), so npm's {@code claude.cmd} shim and an extensionless
     * git-bash / WSL wrapper never shadow a native executable in a later
     * directory.
     */
    @Nullable
    private static String findInAbsoluteWindowsPath(String fileName, @Nullable String pathEnv) {
        if (pathEnv == null) {
            return null;
        }
        for (String entry : pathEnv.split(";")) {
            if (entry.isEmpty()) {
                continue;
            }
            Path dir;
            try {
                dir = Path.of(entry);
            } catch (InvalidPathException e) {
                continue;
            }
            if (!dir.isAbsolute()) {
                continue;
            }
            Path candidate = dir.resolve(fileName);
            if (Files.isRegularFile(candidate)) {
                return candidate.toString();
            }
        }
        return null;
    }

    /**
     * Finds {@code executable} in a POSIX {@code PATH} value, the way a shell
     * would: the first directory holding a regular, executable file of that
     * name wins.
     */
    @Nullable
    static String findInPath(String executable, @Nullable String pathEnv) {
        if (pathEnv == null) {
            return null;
        }
        for (String dir : pathEnv.split(":")) {
            if (dir.isEmpty()) {
                continue;
            }
            Path path = Path.of(dir, executable);
            if (Files.exists(path) && Files.isRegularFile(path) && Files.isExecutable(path)) {
                return path.toString();
            }
        }
        return null;
    }

    /**
     * Whether the SDK is running on Windows.
     *
     * <p>
     * Read from {@code os.name} on every call rather than cached, so tests can
     * exercise the Windows-only paths on a POSIX host.
     */
    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * Whether {@code cliPath} names a {@code .bat}/{@code .cmd} batch script on
     * Windows. Always false off Windows; see {@link #rejectWindowsBatchCli} for
     * why spawning such a script is refused.
     *
     * <p>
     * Deliberately plain string logic rather than {@link Path}: path parsing
     * differs between the POSIX hosts these tests run on and the Windows hosts
     * the code guards, and only string logic behaves identically on both.
     *
     * <p>
     * Every path component is classified, not only the final one. Win32 opens a
     * path after lexical normalization — {@code .}/{@code ..} collapsing,
     * repeated separators, and position-dependent trailing dot/space trimming —
     * and re-deriving the effective final component here is a race against that
     * ruleset: get one rule slightly wrong and a spelling such as
     * {@code claude.cmd\...\..} resolves to claude.cmd on Windows while the
     * simulation lands on some other name. Refusing whenever <i>any</i>
     * component carries a batch extension closes that whole class, because every
     * normalization trick still has to spell the {@code .bat}/{@code .cmd}
     * component somewhere in the string. It costs nothing legitimate: no real
     * claude.exe lives beneath a directory named like a batch file.
     *
     * <p>
     * Within a component, Win32 finds the extension with a last-dot scan over
     * the whole component, NTFS stream spec included — {@code claude:evil.cmd}
     * has extension {@code .cmd} — while a stream spec also opens its base file
     * — {@code claude.cmd:stream} opens claude.cmd — and a drive prefix
     * ({@code C:claude.cmd}) rides in the same component. Splitting each
     * component on {@code :} covers all of these; colons cannot appear in real
     * file names, so nothing legitimate is over-refused. A bare {@code .cmd}
     * counts as a batch extension, as Win32 {@code PathFindExtension} treats it.
     */
    static boolean isWindowsBatchCli(String cliPath) {
        if (!isWindows()) {
            return false;
        }
        for (String component : cliPath.replace('\\', '/').split("/", -1)) {
            for (String segment : component.split(":", -1)) {
                String trimmed = stripTrailingDotsAndSpaces(segment).toLowerCase(Locale.ROOT);
                if (trimmed.endsWith(".bat") || trimmed.endsWith(".cmd")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Strips the trailing dots and spaces Windows drops at path resolution. */
    private static String stripTrailingDotsAndSpaces(String value) {
        int end = value.length();
        while (end > 0 && (value.charAt(end - 1) == '.' || value.charAt(end - 1) == ' ')) {
            end--;
        }
        return value.substring(0, end);
    }

    /**
     * Refuses to execute a {@code .bat}/{@code .cmd} script as the CLI on
     * Windows.
     *
     * <p>
     * Windows has no shebang mechanism: the OS runs batch scripts by silently
     * rewriting the spawn into a {@code cmd.exe /c} invocation, and cmd.exe
     * re-parses the whole command line at execution time. Argument quoting
     * follows the MSVCRT argv rules, not cmd.exe's, so cmd.exe metacharacters
     * inside an argument value — for example a session title passed to
     * {@code --resume} — reach cmd.exe unescaped and can execute injected
     * commands. Reliable escaping for cmd.exe does not exist ({@code %VAR%}
     * expands even inside double quotes), so spawning a batch script with
     * runtime-provided arguments cannot be made safe. Refusing is the same
     * remediation Node.js shipped for this vulnerability class
     * (CVE-2024-27980, "BatBadBut").
     *
     * <p>
     * Without the {@code allowUnsafeWindowsBatchCli} opt-in, {@link #findCli}
     * never returns one on Windows, so this applies to an explicit
     * {@link ClaudeAgentOptions#cliPath()}, such as npm's {@code claude.cmd}
     * shim. The alternatives in the error message avoid
     * cmd.exe entirely.
     *
     * <p>
     * A caller who cannot migrate off the shim can opt in with
     * {@link ClaudeAgentOptions.Builder#allowUnsafeWindowsBatchCli(boolean)},
     * which routes through {@link #enforceWindowsBatchCliPolicy} instead of
     * this method.
     *
     * @param cliPath the resolved CLI path
     * @throws CLIConnectionException if the path names a batch script on Windows
     */
    static void rejectWindowsBatchCli(String cliPath) throws CLIConnectionException {
        if (!isWindowsBatchCli(cliPath)) {
            return;
        }
        throw new CLIConnectionException(
                "Refusing to execute batch script '" + cliPath + "': Windows runs "
                        + ".bat/.cmd files via cmd.exe, which can execute commands "
                        + "injected through CLI arguments, and no reliable escaping for "
                        + "cmd.exe exists. Use a native claude executable instead: "
                        + "install Claude Code natively "
                        + "(irm https://claude.ai/install.ps1 | iex) or point "
                        + "ClaudeAgentOptions.cliPath(...) at a claude.exe. If migrating "
                        + "is not currently possible, the risk can be accepted explicitly "
                        + "with ClaudeAgentOptions.builder().allowUnsafeWindowsBatchCli(true) "
                        + "— read that method's javadoc first; it also requires "
                        + "-Djdk.lang.Process.allowAmbiguousCommands=false.");
    }

    /**
     * The JVM switch that makes {@code ProcessImpl} guard a batch-script spawn.
     *
     * <p>
     * It defaults to {@code "true"}, which selects a legacy mode whose escape
     * set is empty — nothing but whitespace is quoted, and an embedded quote is
     * accepted. Setting it to {@code false} selects
     * {@code VERIFICATION_CMD_BAT} for a non-{@code .exe} target: arguments
     * containing {@code " < > & | ^} or whitespace are quoted, and an argument
     * carrying an embedded quote throws. That is the difference between an
     * unguarded cmd.exe re-parse and a guarded one, so the batch-CLI opt-in
     * insists on it.
     */
    private static final String ALLOW_AMBIGUOUS_COMMANDS_PROPERTY =
            "jdk.lang.Process.allowAmbiguousCommands";

    /** Set once per transport so the opt-in warning is not logged per retry. */
    private final AtomicBoolean batchCliWarningLogged = new AtomicBoolean(false);

    /**
     * Applies the Windows batch-CLI policy: refuse by default, or admit the
     * spawn under the conditions {@code allowUnsafeWindowsBatchCli} demands.
     *
     * <p>
     * A bare opt-in would hand back the whole {@code cmd.exe} re-parse hole, so
     * enabling it is not a plain bypass. It additionally requires
     * {@link #ALLOW_AMBIGUOUS_COMMANDS_PROPERTY} to be {@code false} — without
     * which the JDK quotes nothing — and leaves the caller subject to the argv
     * sweep in {@link #rejectCmdMetacharactersInArgv}, which covers the
     * {@code %} and {@code !} expansions the JDK's rules omit. The net effect
     * is a narrower attack surface than simply not having the check.
     *
     * @param cliPath the resolved CLI path
     * @throws CLIConnectionException if the path names a batch script and
     *                                either the opt-in or the required JVM
     *                                switch is absent
     */
    void enforceWindowsBatchCliPolicy(String cliPath) throws CLIConnectionException {
        if (!isWindowsBatchCli(cliPath)) {
            return;
        }
        if (!options.allowUnsafeWindowsBatchCli()) {
            rejectWindowsBatchCli(cliPath);
            return;
        }
        String value = System.getProperty(ALLOW_AMBIGUOUS_COMMANDS_PROPERTY);
        if (!"false".equalsIgnoreCase(value)) {
            throw new CLIConnectionException(
                    "allowUnsafeWindowsBatchCli(true) was set for '" + cliPath + "', but "
                            + ALLOW_AMBIGUOUS_COMMANDS_PROPERTY + " is "
                            + ((value == null) ? "unset (defaults to true)" : ("'" + value + "'"))
                            + ". In that mode the JDK quotes nothing but whitespace when "
                            + "spawning a batch script, so cmd.exe metacharacters in an "
                            + "argument value reach it verbatim — the opt-in would simply "
                            + "restore the vulnerability. Start the JVM with "
                            + "-D" + ALLOW_AMBIGUOUS_COMMANDS_PROPERTY + "=false, which "
                            + "makes the JDK quote cmd.exe metacharacters and reject "
                            + "arguments containing a quote.");
        }
        if (batchCliWarningLogged.compareAndSet(false, true)) {
            logger.warning(
                    "Executing Windows batch CLI '" + cliPath + "' through cmd.exe because "
                            + "allowUnsafeWindowsBatchCli(true) was set. cmd.exe re-parses "
                            + "the command line; %VAR% still expands from the environment. "
                            + "The caller has accepted responsibility for the CLI path and "
                            + "for every argument value. Prefer a native claude.exe.");
        }
    }

    /**
     * Sweeps every CLI argument for cmd.exe metacharacters before a batch-script
     * spawn.
     *
     * <p>
     * This is the half the JDK does not cover. With
     * {@link #ALLOW_AMBIGUOUS_COMMANDS_PROPERTY} set to {@code false} the JDK
     * quotes {@code " < > & | ^} and whitespace and rejects embedded quotes, but
     * {@code %} and {@code !} are absent from its escape set and quoting does
     * not stop {@code %VAR%} expansion: an unquoted {@code %FOO%} that expands
     * to {@code x&calc} is re-parsed by cmd.exe. Rejecting the whole
     * {@link #CMD_EXE_METACHARACTERS} set across the entire argv closes that
     * argument-side vector.
     *
     * <p>
     * Runs only for a batch CLI on Windows. A native executable involves no
     * cmd.exe hop, and the narrower {@code resume}/{@code sessionId} checks in
     * {@link #rejectWindowsCmdMetacharacters} continue to cover it.
     *
     * @param cliPath the resolved CLI path
     * @param cmd     the fully built argv, including the executable at index 0
     * @throws IllegalArgumentException if any argument carries a metacharacter
     */
    void rejectCmdMetacharactersInArgv(String cliPath, List<String> cmd) {
        if (!options.allowUnsafeWindowsBatchCli() || !isWindowsBatchCli(cliPath)) {
            return;
        }
        // Skip index 0: the executable path itself is not caller-supplied
        // argument data, and isWindowsBatchCli has already classified it.
        for (int i = 1; i < cmd.size(); i++) {
            String arg = cmd.get(i);
            Set<Character> bad = new TreeSet<>();
            for (char c : arg.toCharArray()) {
                if ((CMD_EXE_METACHARACTERS.indexOf(c) >= 0) || (c == '\r') || (c == '\n')) {
                    bad.add(c);
                }
            }
            if (!bad.isEmpty()) {
                // Name the preceding flag when there is one; an argument value
                // is far easier to trace back than a bare index.
                String context = ((i > 1) && cmd.get(i - 1).startsWith("--"))
                        ? ("the value of " + cmd.get(i - 1))
                        : ("CLI argument " + i);
                throw new IllegalArgumentException(
                        "Refusing to pass " + context + " to a Windows batch CLI: it "
                                + "contains cmd.exe metacharacters " + bad + ", which "
                                + "cmd.exe re-parses. Remove them, or switch to a native "
                                + "claude.exe so no cmd.exe hop is involved.");
            }
        }
    }

    /**
     * Defense in depth for Windows: rejects cmd.exe metacharacters.
     *
     * <p>
     * With batch-script spawning refused ({@link #rejectWindowsBatchCli}) these
     * characters are harmless, because argument quoting is correct for native
     * executables. They are rejected anyway so that {@code resume},
     * {@code sessionId}, {@code resumeSessionAt} and {@code resumeDropsTurn}
     * values, which applications commonly take from external input, stay inert
     * even if a cmd.exe hop is ever reintroduced between the SDK and the CLI.
     * No format is imposed beyond this — resume values may be arbitrary session
     * titles, not only UUIDs — and POSIX behavior is unchanged.
     *
     * @param optionName the option being validated, for the error message
     * @param value      the caller-supplied value
     * @throws IllegalArgumentException if the value carries cmd.exe
     *                                  metacharacters or newlines on Windows
     */
    static void rejectWindowsCmdMetacharacters(String optionName, String value) {
        if (!isWindows()) {
            return;
        }
        Set<Character> bad = new TreeSet<>();
        for (char c : value.toCharArray()) {
            if (CMD_EXE_METACHARACTERS.indexOf(c) >= 0 || c == '\r' || c == '\n') {
                bad.add(c);
            }
        }
        if (!bad.isEmpty()) {
            throw new IllegalArgumentException(
                    optionName + " value '" + value + "' contains characters that are "
                            + "unsafe to pass on a Windows command line: " + bad);
        }
    }

    /**
     * Builds the {@code --settings} value, merging sandbox settings in if
     * provided.
     *
     * <p>
     * Without a sandbox the settings value passes through as-is. With one, the
     * settings are parsed (inline JSON) or read (a path, relative to
     * {@code cwd} as the CLI reads it, with or without a BOM) and merged with
     * the sandbox into one JSON object. Settings that can't be loaded throw:
     * launching without them would drop the caller's permission rules and
     * hooks.
     *
     * @throws CLIConnectionException if the settings cannot be loaded
     */
    @Nullable
    private String buildSettingsValue() {
        String settings = options.settings();
        if (options.sandbox() == null) {
            return settings;
        }

        Map<String, Object> settingsObj = new LinkedHashMap<>();
        if (settings != null) {
            String json = settings.trim();
            if (!(json.startsWith("{") && json.endsWith("}"))) {
                json = readSettingsFile(json);
            }
            Object parsed;
            try {
                // Trailing content after the object fails, as Python's
                // json.loads does, rather than being silently dropped.
                parsed = MAPPER.readerFor(Object.class)
                        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .readValue(json);
            } catch (JsonProcessingException e) {
                // Not e.getMessage() and no cause: Jackson quotes the offending
                // source, and inline settings can hold secrets.
                throw new CLIConnectionException("Failed to load settings: not valid JSON"
                        + ((e.getLocation() != null)
                                ? (" (line " + e.getLocation().getLineNr()
                                        + ", column " + e.getLocation().getColumnNr() + ")")
                                : ""));
            }
            if (!(parsed instanceof Map<?, ?> map)) {
                throw new CLIConnectionException("Failed to load settings: expected a JSON object, got "
                        + ((parsed == null) ? "null" : parsed.getClass().getSimpleName()));
            }
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                settingsObj.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        settingsObj.put("sandbox", options.sandbox());

        try {
            return MAPPER.writeValueAsString(settingsObj);
        } catch (JsonProcessingException e) {
            throw new CLIConnectionException("Failed to serialize settings: " + e.getOriginalMessage());
        }
    }

    /** Reads a settings file as the CLI does: relative to its cwd, BOM stripped. */
    private String readSettingsFile(String settingsPath) {
        try {
            Path path = (cwd != null) ? cwd.resolve(settingsPath) : Path.of(settingsPath);
            String content = Files.readString(path, StandardCharsets.UTF_8);
            return (content.startsWith("\uFEFF") ? content.substring(1) : content);
        } catch (IOException | InvalidPathException e) {
            throw new CLIConnectionException("Failed to load settings: cannot read " + settingsPath
                    + " (" + e.getClass().getSimpleName() + ")", e);
        }
    }

    record McpConfigPayload(Map<String, McpServerConfig> mcpServers) {
    }

    /**
     * Applies SDK-managed environment variable defaults to the given environment map.
     *
     * <p>
     * Package-private for testing.
     *
     * @param options the agent options
     * @param env     the environment map to mutate
     */
    static void applyEnvDefaults(ClaudeAgentOptions options, Map<String, String> env) {
        // Filter CLAUDECODE so SDK-spawned subprocesses don't think
        // they're running inside a Claude Code parent
        env.remove("CLAUDECODE");

        // CLAUDE_CODE_ENTRYPOINT defaults to sdk-java; options.env can override it.
        // CLAUDE_AGENT_SDK_VERSION is always set by the SDK.
        env.put("CLAUDE_CODE_ENTRYPOINT", "sdk-java");
        env.putAll(options.env());
        env.put("CLAUDE_AGENT_SDK_VERSION", SdkVersion.VERSION);

        // A derived label from an outer SDK session names that session's app,
        // so it is never passed on. This SDK derives none of its own (see
        // ClaudeAgentOptions.Builder#clientApp). An explicit clientApp wins over
        // options.env and the inherited value; set counts even when empty,
        // which is how a developer opts out.
        env.remove(DERIVED_CLIENT_APP_ENV);
        if (options.clientApp() != null) {
            env.put(EXPLICIT_CLIENT_APP_ENV, options.clientApp());
        }

        // QueryHandler waits for the CLI's session_state_changed "idle" before
        // closing stdin on a run that serves control requests. Ask for the
        // frames it drops (sdk_host_only) unless the caller chose a value, in
        // any case; CLAUDE_CODE_EMIT_SESSION_STATE_EVENTS stays the caller's
        // own opt-in to seeing them.
        if (env.keySet().stream().noneMatch(SDK_READS_SESSION_STATE_ENV::equalsIgnoreCase)) {
            env.put(SDK_READS_SESSION_STATE_ENV, "1");
        }

        if (options.enableFileCheckpointing()) {
            env.put("CLAUDE_CODE_ENABLE_SDK_FILE_CHECKPOINTING", "true");
        }

        injectTraceContext(options, env);
    }

    /**
     * Best-effort propagation of the active OpenTelemetry W3C trace context
     * (TRACEPARENT/TRACESTATE) to the CLI subprocess so its spans parent
     * under the caller's distributed trace.
     *
     * <p>Uses reflection to avoid taking a hard dependency on
     * {@code opentelemetry-api}. No-op when the API is not on the
     * classpath or when there's no active span. Explicit values from
     * {@link ClaudeAgentOptions#env()} always win.
     */
    @SuppressWarnings("unchecked")
    private static void injectTraceContext(ClaudeAgentOptions options, Map<String, String> env) {
        try {
            // GlobalOpenTelemetry.get() returns a package-private
            // ObfuscatedOpenTelemetry wrapper, so reflect through the public
            // OpenTelemetry / ContextPropagators / TextMapPropagator
            // interfaces to avoid IllegalAccessException.
            Class<?> globalOtelClass = Class.forName("io.opentelemetry.api.GlobalOpenTelemetry");
            Object openTelemetry = globalOtelClass.getMethod("get").invoke(null);

            Class<?> openTelemetryIface = Class.forName("io.opentelemetry.api.OpenTelemetry");
            Object propagators = openTelemetryIface.getMethod("getPropagators").invoke(openTelemetry);

            Class<?> propagatorsIface = Class.forName("io.opentelemetry.context.propagation.ContextPropagators");
            Object textMapPropagator = propagatorsIface.getMethod("getTextMapPropagator").invoke(propagators);

            Class<?> contextClass = Class.forName("io.opentelemetry.context.Context");
            Object currentContext = contextClass.getMethod("current").invoke(null);

            Class<?> setterClass = Class.forName("io.opentelemetry.context.propagation.TextMapSetter");
            Map<String, String> carrier = new HashMap<>();
            Object setter = java.lang.reflect.Proxy.newProxyInstance(
                    setterClass.getClassLoader(),
                    new Class<?>[] { setterClass },
                    (proxy, method, args) -> {
                        if ("set".equals(method.getName()) && args != null && args.length == 3) {
                            ((Map<String, String>) args[0]).put((String) args[1], (String) args[2]);
                        }
                        return null;
                    });

            Class<?> textMapPropagatorIface = Class.forName(
                    "io.opentelemetry.context.propagation.TextMapPropagator");
            textMapPropagatorIface
                    .getMethod("inject", contextClass, Object.class, setterClass)
                    .invoke(textMapPropagator, currentContext, carrier, setter);

            if (carrier.containsKey("traceparent")) {
                // Active span present: scrub stale inherited W3C context
                // before writing the fresh values, so an inherited
                // TRACESTATE isn't paired with a new TRACEPARENT.
                // ClaudeAgentOptions.env always wins.
                for (String key : List.of("TRACEPARENT", "TRACESTATE")) {
                    if (!options.env().containsKey(key)) {
                        env.remove(key);
                    }
                }
                for (Map.Entry<String, String> entry : carrier.entrySet()) {
                    String upperKey = entry.getKey().toUpperCase();
                    if (!options.env().containsKey(upperKey)) {
                        env.put(upperKey, entry.getValue());
                    }
                }
            }
        } catch (ClassNotFoundException e) {
            // OpenTelemetry not on classpath — no-op.
        } catch (Exception e) {
            // Best-effort tracing must never break connect().
            logger.fine("OTEL trace context injection failed: " + e.getMessage());
        }
    }

    /**
     * Result of applying {@code skills} defaults to {@code allowed_tools}
     * and {@code setting_sources}. Mirrors Python SDK's
     * {@code _apply_skills_defaults} return tuple.
     */
    record SkillsDefaultsResult(List<String> allowedTools, @Nullable List<SettingSource> settingSources) {
    }

    /**
     * Rejects {@code skills} values that are neither a list nor {@code "all"}.
     *
     * <p>
     * The builder's {@code skills(List)} / {@code skillsAll()} pair makes this
     * unreachable through the public API — Java's type system does here what
     * the Python SDK's {@code _reject_non_list_skills} does at runtime, where a
     * bare string iterates as characters and any other iterable builds rules
     * that are then dropped from the initialize request, installing no skill
     * filter at all. The check is kept so a raw-typed or reflective caller gets
     * the same fail-closed behavior rather than a silent no-op.
     *
     * @param skills the caller-supplied value
     * @throws IllegalArgumentException if the value is not a list or
     *                                  {@code "all"}
     */
    static void rejectNonListSkills(Object skills) {
        if ((skills instanceof List<?>) || SKILLS_ALL.equals(skills)) {
            return;
        }
        // Mirrors the Python SDK's `Did you mean [...]?` hint, rendered as the
        // Java call the caller should have written.
        String suggestion = (skills instanceof String s)
                ? (" Did you mean List.of(\"" + s + "\")?")
                : "";
        throw new IllegalArgumentException(
                "ClaudeAgentOptions.skills must be a list of skill names or \"all\", got "
                        + describeSkillValue(skills) + "." + suggestion);
    }

    /**
     * Rejects skill names that cannot ride safely in a {@code Skill(name)} rule.
     *
     * <p>
     * Names from {@code options.skills()} are formatted into the
     * {@code --allowedTools} value, which the CLI splits into rules on commas
     * and spaces outside parentheses. That tokenizer honors no escape sequences
     * — escaping exists only in the per-rule grammar, applied after splitting —
     * so a name carrying a delimiter cannot be passed through reliably: what it
     * tokenizes into depends on what surrounds it. A crafted name could
     * otherwise close its own rule and append others, e.g.
     * {@code "x),Bash(*"} yielding {@code Skill(x),Bash(*)}.
     *
     * <p>
     * Names that tokenize cleanly but can never match the listed skill are
     * rejected too, so a dead rule fails loudly here instead of silently
     * granting nothing. Each check below states its own reason.
     *
     * @param name the caller-supplied skill name
     * @throws IllegalArgumentException if the name is unusable
     */
    static void validateSkillName(@Nullable Object name) {
        if (!(name instanceof String skillName)) {
            throw new IllegalArgumentException(
                    "Skill names must be strings, got " + describeSkillValue(name));
        }
        if (skillNameStrip(skillName).isEmpty()) {
            throw new IllegalArgumentException("Skill names must be non-empty strings");
        }
        if (hasUnpairedSurrogate(skillName)) {
            throw new IllegalArgumentException(
                    "Invalid skill name '" + skillName + "': contains an unpaired surrogate"
                            + " code point, which can never match a skill the CLI discovered.");
        }
        if (!skillName.equals(skillNameStrip(skillName))) {
            throw new IllegalArgumentException(
                    "Invalid skill name '" + skillName + "': leading or trailing whitespace"
                            + " can never match — the Skill tool trims the invoked name.");
        }
        if (SKILL_NAME_INVALID_CHARS.matcher(skillName).find()) {
            throw new IllegalArgumentException(
                    "Invalid skill name '" + skillName + "': parentheses, commas, control"
                            + " characters, and byte-order marks are not allowed. Names match"
                            + " the skill's directory name, or 'plugin:skill' for"
                            + " plugin-qualified skills.");
        }
        if ("*".equals(skillName)) {
            throw new IllegalArgumentException(
                    "Invalid skill name '*': use skillsAll() to enable every skill.");
        }
        if (skillName.endsWith(":*") || skillName.endsWith(" *")) {
            throw new IllegalArgumentException(
                    "Invalid skill name '" + skillName + "': wildcard-suffix names are not"
                            + " allowed; list each skill by its exact name.");
        }
        if (skillName.startsWith("/")) {
            throw new IllegalArgumentException(
                    "Invalid skill name '" + skillName + "': skill names may not start with"
                            + " '/'. The skills option takes the canonical name, not the"
                            + " slash-command form.");
        }
        if (skillName.contains("\\\\")) {
            throw new IllegalArgumentException(
                    "Invalid skill name '" + skillName + "': consecutive backslashes are not"
                            + " allowed — the per-rule parser collapses them, so the rule"
                            + " would name a different skill.");
        }
        if (skillName.endsWith("\\")) {
            throw new IllegalArgumentException(
                    "Invalid skill name '" + skillName + "': names may not end with an"
                            + " unpaired backslash.");
        }
    }

    /**
     * Strips the characters the CLI treats as surrounding whitespace.
     *
     * <p>
     * {@link String#strip()} alone is not enough: it follows
     * {@link Character#isWhitespace}, which excludes the non-breaking spaces
     * (U+00A0, U+2007, U+202F) that Python's {@code str.strip()} does remove.
     * {@link Character#isSpaceChar} covers those, so the union matches the
     * Python SDK's notion of a padded name. U+FEFF is deliberately not included
     * — like Python, this SDK rejects it through
     * {@link #SKILL_NAME_INVALID_CHARS} instead.
     */
    private static String skillNameStrip(String name) {
        int start = 0;
        int end = name.length();
        while ((start < end) && isSkillNamePad(name.charAt(start))) {
            start++;
        }
        while ((end > start) && isSkillNamePad(name.charAt(end - 1))) {
            end--;
        }
        return name.substring(start, end);
    }

    private static boolean isSkillNamePad(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
    }

    /**
     * Reports whether the name contains a surrogate that is not part of a
     * well-formed pair.
     *
     * <p>
     * This diverges from the Python SDK, which rejects <em>every</em> surrogate
     * code point: a Python {@code str} holds code points, so an astral
     * character is one non-surrogate item and any surrogate present is
     * necessarily unpaired. Java strings are UTF-16, where astral characters are
     * legitimately stored as a high/low pair, so the Python rule would reject
     * ordinary names like {@code "𝕤kill"}. Only lone surrogates — which no
     * CLI-discovered name can contain — are rejected here.
     */
    private static boolean hasUnpairedSurrogate(String name) {
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if ((i + 1 >= name.length()) || !Character.isLowSurrogate(name.charAt(i + 1))) {
                    return true;
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                return true;
            }
        }
        return false;
    }

    /** Renders a rejected value for an error message, quoting strings. */
    private static String describeSkillValue(@Nullable Object value) {
        return (value instanceof String s) ? ("'" + s + "'") : String.valueOf(value);
    }

    /**
     * Computes the effective allowedTools and settingSources after applying
     * the {@code skills} option. When {@code skills} is {@code "all"},
     * injects the bare {@code Skill} tool; when it is a list, injects
     * {@code Skill(name)} for each entry. In either case
     * {@code settingSources} defaults to {@code [user, project]} when unset
     * so the CLI discovers installed skills without the caller having to
     * wire up both options manually. {@code null} is a no-op.
     *
     * <p>Each listed skill name is validated before being formatted into a
     * rule; see {@link #validateSkillName}.
     *
     * <p>Does not mutate the original options.
     *
     * @throws IllegalArgumentException if {@code skills} is neither a list nor
     *                                  {@code "all"}, or if any listed name is
     *                                  unusable
     */
    SkillsDefaultsResult applySkillsDefaults() {
        List<String> allowedTools = new ArrayList<>(options.allowedTools());
        List<SettingSource> settingSources = (options.settingSources() != null)
                ? new ArrayList<>(options.settingSources())
                : null;

        Object skills = options.skills();
        if (skills == null) {
            return new SkillsDefaultsResult(allowedTools, settingSources);
        }
        rejectNonListSkills(skills);

        if (SKILLS_ALL.equals(skills)) {
            if (!allowedTools.contains("Skill")) {
                allowedTools.add("Skill");
            }
        } else if (skills instanceof List<?> skillList) {
            for (Object name : skillList) {
                validateSkillName(name);
                String pattern = "Skill(" + name + ")";
                if (!allowedTools.contains(pattern)) {
                    allowedTools.add(pattern);
                }
            }
        }

        if (settingSources == null) {
            settingSources = List.of(SettingSource.USER, SettingSource.PROJECT);
        }

        return new SkillsDefaultsResult(allowedTools, settingSources);
    }

    /** Python truthiness for an optional string flag: neither null nor empty. */
    private static boolean isSet(@Nullable String value) {
        return (value != null) && !value.isEmpty();
    }

    /**
     * Serializes a flag's JSON value, failing the connect if it can't be.
     *
     * @throws CLIConnectionException if the value cannot be serialized
     */
    private static String toJson(Object value, String what) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new CLIConnectionException("Failed to serialize " + what + ": " + e.getOriginalMessage(), e);
        }
    }

    @SuppressWarnings({ "unchecked", "null" })
    List<String> buildCommand() {
        List<String> cmd = new ArrayList<>();
        cmd.add(cliPath);
        cmd.add("--output-format");
        cmd.add("stream-json");
        cmd.add("--verbose");

        // System prompt
        if (options.systemPrompt() == null) {
            cmd.add("--system-prompt");
            cmd.add("");
        } else if (options.systemPrompt() instanceof String sp) {
            cmd.add("--system-prompt");
            cmd.add(sp);
        } else if (options.systemPrompt() instanceof SystemPromptCustom spCustom) {
            cmd.add("--system-prompt");
            cmd.add(spCustom.prompt());
        } else if (options.systemPrompt() instanceof SystemPromptPreset spPreset) {
            if ("preset".equals(spPreset.type()) && (spPreset.append() != null)) {
                cmd.add("--append-system-prompt");
                cmd.add(spPreset.append());
            }
        } else if (options.systemPrompt() instanceof SystemPromptFile spFile) {
            cmd.add("--system-prompt-file");
            cmd.add(spFile.path());
        }

        // Tools
        if (options.tools() != null) {
            cmd.add("--tools");
            if (options.tools() instanceof List<?> tools) {
                if (((List<?>) tools).isEmpty()) {
                    cmd.add("");
                } else {
                    List<String> toolList = (List<String>) tools;
                    cmd.add(String.join(",", toolList));
                }
            } else {
                // Preset object - 'claude_code' preset maps to 'default'
                cmd.add("default");
            }
        }

        SkillsDefaultsResult skillsDefaults = applySkillsDefaults();
        List<String> effectiveAllowedTools = skillsDefaults.allowedTools();
        List<SettingSource> effectiveSettingSources = skillsDefaults.settingSources();

        if (!effectiveAllowedTools.isEmpty()) {
            cmd.add("--allowedTools");
            cmd.add(String.join(",", effectiveAllowedTools));
        }

        // Several flags below are skipped when empty or zero rather than only
        // when null: the Python SDK tests them for truthiness, so "" and 0
        // mean "not set" there. Flags it tests with `is not None`
        // (maxBudgetUsd, taskBudget, thinking, effort, resumeDropsTurn,
        // settingSources) keep the null check.
        if ((options.maxTurns() != null) && (options.maxTurns() != 0)) {
            cmd.add("--max-turns");
            cmd.add(String.valueOf(options.maxTurns()));
        }

        if (options.maxBudgetUsd() != null) {
            cmd.add("--max-budget-usd");
            cmd.add(String.valueOf(options.maxBudgetUsd()));
        }

        if (!options.disallowedTools().isEmpty()) {
            cmd.add("--disallowedTools");
            cmd.add(String.join(",", options.disallowedTools()));
        }

        if (options.taskBudget() != null) {
            cmd.add("--task-budget");
            cmd.add(String.valueOf(options.taskBudget().total()));
        }

        if (isSet(options.model())) {
            cmd.add("--model");
            cmd.add(options.model());
        }

        if (isSet(options.fallbackModel())) {
            cmd.add("--fallback-model");
            cmd.add(options.fallbackModel());
        }

        if (!options.betas().isEmpty()) {
            cmd.add("--betas");
            cmd.add(String.join(",", options.betas().stream().map(SdkBeta::getValue).toList()));
        }

        if (isSet(options.permissionPromptToolName())) {
            cmd.add("--permission-prompt-tool");
            cmd.add(options.permissionPromptToolName());
        }

        if (options.permissionMode() != null) {
            cmd.add("--permission-mode");
            cmd.add(options.permissionMode().getValue());
        }

        if (options.continueConversation()) {
            cmd.add("--continue");
        }

        // Pass these as --flag=value rather than as two argv tokens. The CLI
        // declares --resume with an optional value, so in the two-token form a
        // dash-leading value is not bound to the flag and is instead parsed as
        // a separate CLI flag -- letting an untrusted value inject arbitrary
        // flags. The equals form always binds the value to the flag.
        if (isSet(options.resume())) {
            rejectWindowsCmdMetacharacters("resume", options.resume());
            cmd.add("--resume=" + options.resume());
        }

        if (isSet(options.sessionId())) {
            rejectWindowsCmdMetacharacters("sessionId", options.sessionId());
            cmd.add("--session-id=" + options.sessionId());
        }

        // Settings and sandbox
        String settingsValue = buildSettingsValue();
        if (isSet(settingsValue)) {
            cmd.add("--settings");
            cmd.add(settingsValue);
        }

        for (Path dir : options.addDirs()) {
            cmd.add("--add-dir");
            cmd.add(dir.toString());
        }

        // MCP servers
        if (options.mcpServers() != null) {
            if (options.mcpServers() instanceof Map<?, ?> servers) {
                Map<String, McpServerConfig> mcpServers = (Map<String, McpServerConfig>) servers;
                if (!mcpServers.isEmpty()) {
                    // Raise rather than drop the flag, as json.dumps does in
                    // the Python SDK: a session without the caller's servers
                    // is not the session they configured.
                    cmd.add("--mcp-config");
                    cmd.add(toJson(new McpConfigPayload(mcpServers), "MCP server config"));
                }
            } else if ((options.mcpServers() instanceof String s) && !s.isEmpty()) {
                cmd.add("--mcp-config");
                cmd.add(s);
            } else if (options.mcpServers() instanceof Path p) {
                cmd.add("--mcp-config");
                cmd.add(p.toString());
            }
        }

        if (options.includePartialMessages()) {
            cmd.add("--include-partial-messages");
        }

        if (options.includeHookEvents()) {
            cmd.add("--include-hook-events");
        }

        if (options.strictMcpConfig()) {
            cmd.add("--strict-mcp-config");
        }

        if (options.forkSession()) {
            cmd.add("--fork-session");
        }

        // Equals form so the value can never be parsed as a separate flag, even
        // if the CLI's declaration of these options ever changes.
        if (options.resumeSessionAt() != null && !options.resumeSessionAt().isEmpty()) {
            rejectWindowsCmdMetacharacters("resumeSessionAt", options.resumeSessionAt());
            cmd.add("--resume-session-at=" + options.resumeSessionAt());
        }

        // Null check, not emptiness: an empty string is forwarded so the CLI
        // rejects it as a malformed declaration instead of the SDK silently
        // disarming the guard the caller believes is armed.
        if (options.resumeDropsTurn() != null) {
            rejectWindowsCmdMetacharacters("resumeDropsTurn", options.resumeDropsTurn());
            cmd.add("--resume-drops-turn=" + options.resumeDropsTurn());
        }

        if (options.sessionStore() != null) {
            cmd.add("--session-mirror");
        }

        // Agents are always sent via initialize request (matching TypeScript/Python
        // SDKs)
        // This avoids platform-specific command-line argument length limits (ARG_MAX)
        // No --agents CLI flag needed

        // Setting sources — pass even when empty so the CLI knows to disable
        // all filesystem settings (matches Python SDK fix #822).
        if (effectiveSettingSources != null) {
            cmd.add("--setting-sources=" + String.join(",",
                    effectiveSettingSources.stream().map(SettingSource::getValue).toList()));
        }

        // Plugins
        for (ClaudeAgentOptions.SdkPluginConfig plugin : options.plugins()) {
            if ("local".equals(plugin.type())) {
                cmd.add("--plugin-dir");
                cmd.add(plugin.path());
            } else {
                // As the Python SDK's ValueError: a plugin the caller asked
                // for is never silently left out.
                throw new IllegalArgumentException("Unsupported plugin type: " + plugin.type());
            }
        }

        // Extra args
        for (Map.Entry<String, @Nullable String> entry : options.extraArgs().entrySet()) {
            String value = entry.getValue();
            if ((value == null) || value.isBlank()) {
                // Boolean flag without value. Null is the Python SDK's
                // spelling; blank is the Java one, because Map.of cannot hold
                // a null.
                cmd.add("--" + entry.getKey());
            } else if (value.startsWith("-")) {
                // In the two-token form a dash-leading value is not bound to its
                // flag when the CLI declares the option with an optional value —
                // it parses as a separate flag instead, the same injection the
                // --resume equals form above closes. The equals form always
                // binds.
                cmd.add("--" + entry.getKey() + "=" + value);
            } else {
                cmd.add("--" + entry.getKey());
                cmd.add(value);
            }
        }

        // Resolve thinking config -> --thinking / --max-thinking-tokens
        // `thinking` takes precedence over the deprecated `max_thinking_tokens`
        ThinkingConfig thinking = options.thinking();
        if (thinking != null) {
            in.vidyalai.claude.sdk.types.config.ThinkingDisplay display = null;
            if (thinking instanceof ThinkingConfigAdaptive adaptive) {
                cmd.add("--thinking");
                cmd.add("adaptive");
                display = adaptive.display();
            } else if (thinking instanceof ThinkingConfigEnabled enabled) {
                cmd.add("--max-thinking-tokens");
                cmd.add(String.valueOf(enabled.budgetTokens()));
                display = enabled.display();
            } else if (thinking instanceof ThinkingConfigDisabled) {
                cmd.add("--thinking");
                cmd.add("disabled");
            } else {
                throw new IllegalStateException(
                        "Unhandled thinking config type: " + thinking.getClass().getName());
            }
            // Forward --thinking-display only for adaptive/enabled (the
            // disabled case has no display option). Mirrors the Python SDK
            // mypy-narrowed branch.
            if (display != null) {
                cmd.add("--thinking-display");
                cmd.add(display.getValue());
            }
        } else {
            @SuppressWarnings("deprecation")
            Integer maxThinkingTokens = options.maxThinkingTokens();
            if (maxThinkingTokens != null) {
                cmd.add("--max-thinking-tokens");
                cmd.add(String.valueOf(maxThinkingTokens));
            }
        }

        // Add effort level if specified
        String effort = options.effort();
        if (effort != null) {
            cmd.add("--effort");
            cmd.add(effort);
        }

        // Extract schema from output_format structure if provided
        // Expected: {"type": "json_schema", "schema": {...}}
        Map<String, Object> outputFormat = options.outputFormat();
        if ((outputFormat != null) && ("json_schema".equals(outputFormat.get("type")))) {
            Object schema = outputFormat.get("schema");
            if (schema != null) {
                cmd.add("--json-schema");
                cmd.add(toJson(schema, "output format JSON schema"));
            }
        }

        cmd.add("--input-format");
        cmd.add("stream-json");

        return cmd;
    }

    @SuppressWarnings("null")
    @Override
    public synchronized void connect() throws CLIConnectionException {
        if (process != null) {
            return;
        }

        rejectUnsupportedUser(options);

        // Validate the resolved CLI before spawning it. This covers every route
        // to the executable path: PATH discovery, an explicit
        // ClaudeAgentOptions.cliPath, and the bundled-location fallbacks.
        // Refuses a Windows batch script unless the caller opted in, in which
        // case it enforces the conditions that opt-in carries. Nothing else
        // runs the CLI: its version is read from its first init message.
        enforceWindowsBatchCliPolicy(cliPath);

        List<String> cmd = buildCommand();
        // Second half of the batch-CLI opt-in: no argument may carry a cmd.exe
        // metacharacter. Deferred to here because it needs the built argv.
        rejectCmdMetacharactersInArgv(cliPath, cmd);
        logger.fine("Launching Claude Code with: %s".formatted(String.join(" ", cmd)));
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);

            // Set environment
            Map<String, String> env = pb.environment();
            applyEnvDefaults(options, env);

            if (cwd != null) {
                pb.directory(cwd.toFile());
                env.put("PWD", cwd.toString());
            }

            // Pipe stderr only when the caller registered a callback.
            // Otherwise the CLI inherits this process's stderr, as the Python
            // SDK's stderr=None does: a pipe nobody drains fills up (about
            // 64 KB) and blocks the CLI on its next write.
            boolean shouldPipeStderr = options.stderrCallback() != null;
            pb.redirectErrorStream(false);
            if (!shouldPipeStderr) {
                pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            }

            logger.fine("Claude ENV:" + env);
            process = pb.start();
            ACTIVE_CHILDREN.add(process);

            stdin = new BufferedWriter(
                    new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            stdout = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8);

            // Start stderr reader thread if needed
            if (shouldPipeStderr) {
                Reader localStderr = new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8);
                // Submit stderr reading task to dedicated executor
                stderrExecutor.submit(() -> handleStderr(localStderr));
            }

            ready.set(true);

        } catch (IOException e) {
            // Only a missing file is "not found", as the Python SDK maps only
            // FileNotFoundError: a CLI that exists but can't be run (EACCES,
            // ENOEXEC, ...) is a connection failure with its cause intact.
            if (isFileNotFound(e)) {
                if ((cwd != null) && (!Files.exists(cwd))) {
                    CLIConnectionException error = new CLIConnectionException(
                            "Working directory does not exist: " + cwd, e);
                    exitError = error;
                    throw error;
                }
                CLINotFoundException error = new CLINotFoundException("Claude Code not found at", cliPath, e);
                exitError = error;
                throw error;
            }
            CLIConnectionException error = new CLIConnectionException(
                    "Failed to start Claude Code: " + describe(e), e);
            exitError = error;
            throw error;
        } catch (RuntimeException e) {
            CLIConnectionException error = new CLIConnectionException(
                    "Failed to start Claude Code: " + describe(e), e);
            exitError = error;
            throw error;
        }
    }

    /**
     * Refuses the {@code user} option, which the Java SDK cannot honor.
     *
     * <p>
     * The Python SDK spawns the CLI as that user. {@link ProcessBuilder} has
     * no way to switch users, and running the CLI as the JVM's user instead
     * would quietly give it a different identity, home directory and
     * credentials than the caller asked for.
     *
     * @throws CLIConnectionException if {@code user} is set
     */
    static void rejectUnsupportedUser(ClaudeAgentOptions options) {
        if (options.user() != null) {
            throw new CLIConnectionException(
                    "The 'user' option (\"" + options.user() + "\") is not supported by the Java SDK: "
                            + "Java cannot start the Claude Code CLI as another OS user, and running it as "
                            + "the JVM's user would ignore the option. Remove user(...) from the options, "
                            + "or point cliPath(...) at a wrapper that switches users "
                            + "(e.g. a script running: sudo -u <user> claude \"$@\").");
        }
    }

    /**
     * Whether a spawn failure means the file is missing ({@code ENOENT}).
     *
     * <p>
     * {@link ProcessBuilder#start()} reports every OS error as a plain
     * {@link IOException} whose message carries the errno, e.g.
     * {@code Cannot run program "x": error=2, No such file or directory}, or
     * {@code Exec failed, error: 2 (No such file or directory)} from newer
     * JDKs' spawn helper; {@code CreateProcess error=2} on Windows has the
     * same number.
     */
    static boolean isFileNotFound(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if ((t instanceof java.nio.file.NoSuchFileException) || (t instanceof java.io.FileNotFoundException)) {
                return true;
            }
            String message = t.getMessage();
            if ((message != null) && ENOENT_MESSAGE.matcher(message).find()) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    /** The errno part of a spawn failure for a missing file. */
    private static final Pattern ENOENT_MESSAGE = Pattern.compile("error(?:=|: )2(?!\\d)");

    /** A throwable's message, or its class name when it has none. */
    private static String describe(Throwable e) {
        return (e.getMessage() != null) ? e.getMessage() : e.getClass().getName();
    }

    /**
     * Reads the CLI's stderr and hands each line to the stderr callback.
     *
     * <p>
     * Frames lines as the Python SDK's {@code _handle_stderr} does: split on
     * {@code \n} only (a bare {@code \r}, as a progress line uses, stays in the
     * line), trailing whitespace stripped, blank lines skipped. A line longer
     * than {@code maxBufferSize} is passed on in pieces rather than buffered
     * without bound, and a last line with no newline is still delivered.
     *
     * <p>
     * Package-private for tests.
     */
    void handleStderr(Reader stderr) {
        LineFramer framer = new LineFramer(stderr, maxBufferSize);
        try (stderr) {
            String line;
            while ((line = framer.readLine()) != null) {
                emitStderrLine(line);
            }
        } catch (Exception e) {
            logger.log(Level.FINE, "stderr stream read failed", e);
            // Deliver what was read of the line the failure cut off: a
            // diagnostic written just before the CLI died is the one the
            // caller needs.
            emitStderrLine(framer.flush());
        }
    }

    private void emitStderrLine(String rawLine) {
        String line = PyStrings.rstrip(rawLine);
        if (line.isEmpty()) {
            return;
        }
        Consumer<String> callback = options.stderrCallback();
        if (callback != null) {
            // Isolate per-line so a raise in the user's callback doesn't
            // terminate the loop and silently drop every subsequent stderr
            // line for the rest of the session.
            try {
                callback.accept(line);
            } catch (Throwable t) {
                logger.log(Level.FINE, "stderr callback raised; continuing", t);
            }
        }
    }

    /** Reads one JSON object per stdout line, rejecting anything after it. */
    private static final ObjectReader STDOUT_LINE_READER = MAPPER
            .readerFor(new TypeReference<Map<String, Object>>() {
            })
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /**
     * Parses one complete line of the CLI's NDJSON stdout, as the Python SDK's
     * {@code _parse_stdout_line} does.
     *
     * <p>
     * Returns null for lines that carry no message: blank lines, and non-JSON
     * output such as {@code [SandboxDebug] ...} that some CLI builds write to
     * stdout. A line that starts like JSON but does not parse is corrupt — the
     * line is complete, so no later data can complete it — and throws, as does
     * one with content after the object ({@code {"a":1} junk}), which Python's
     * {@code json.loads} rejects too.
     *
     * <p>
     * Package-private for tests.
     *
     * @throws CLIJSONDecodeException if the line looks like JSON but is not a
     *                                single valid JSON object
     */
    @Nullable
    static Map<String, Object> parseStdoutLine(String rawLine) {
        // A complete line's surrounding whitespace (e.g. the \r of a CRLF) is
        // meaningless; whitespace inside a value is never at a line's edge.
        String line = PyStrings.strip(rawLine);
        if (line.isEmpty()) {
            return null;
        }
        if (!line.startsWith("{")) {
            logger.fine("Skipping non-JSON line from CLI stdout: " + line.substring(0, Math.min(line.length(), 200)));
            return null;
        }
        try {
            return STDOUT_LINE_READER.readValue(line);
        } catch (JsonProcessingException e) {
            throw new CLIJSONDecodeException(line, e);
        }
    }

    /**
     * The message one framed stdout line carries, or null if none.
     *
     * <p>
     * Bounds a single message, whether it is complete yet or not: a line
     * longer than {@code maxBufferSize} throws. A line cut off by the end of
     * the stream ({@code terminated} false and within the bound) is either a
     * last message whose newline the producer omitted (returned) or one cut
     * off mid-write, which is unrecoverable and dropped rather than raised.
     *
     * <p>
     * Package-private for tests.
     *
     * @throws CLIJSONDecodeException if the line is too long, or is a complete
     *                                line that looks like JSON but does not
     *                                parse
     */
    @Nullable
    static Map<String, Object> stdoutMessage(String line, boolean terminated, int maxBufferSize) {
        if (line.length() > maxBufferSize) {
            throw new CLIJSONDecodeException(
                    "JSON message exceeded maximum buffer size of " + maxBufferSize + " bytes",
                    new IllegalStateException(
                            "Buffer size " + line.length() + " exceeds limit " + maxBufferSize));
        }
        if (terminated) {
            return parseStdoutLine(line);
        }
        try {
            return parseStdoutLine(line);
        } catch (CLIJSONDecodeException e) {
            logger.fine("Dropping truncated JSON at end of CLI stdout: "
                    + line.substring(0, Math.min(line.length(), 200)));
            return null;
        }
    }

    /**
     * Splits a character stream into lines on {@code \n}, the way the Python
     * SDK's {@code _LineFramer} does for a chunked stream.
     *
     * <p>
     * Unlike {@link BufferedReader#readLine()}, a bare {@code \r} does not end a
     * line, and a line is never held past {@code maxLength}: once the pending
     * part of a line exceeds it, that part is returned unterminated so the
     * caller can reject it (stdout) or pass it on (stderr). Characters are
     * never stripped here; only a complete line may be.
     *
     * <p>
     * Not thread-safe: one reader thread owns it.
     */
    static final class LineFramer {

        private final Reader in;
        private final int maxLength;
        private final char[] buf = new char[8192];
        private int pos;
        private int lim;
        private final StringBuilder pending = new StringBuilder();
        private boolean terminated;

        LineFramer(Reader in, int maxLength) {
            this.in = in;
            this.maxLength = maxLength;
        }

        /**
         * Returns the next line without its {@code \n}, the part of a line
         * that grew past {@code maxLength}, or the unterminated tail at end of
         * stream; null once the stream is exhausted.
         */
        @Nullable
        String readLine() throws IOException {
            terminated = false;
            while (true) {
                if (pos >= lim) {
                    int n = in.read(buf, 0, buf.length);
                    if (n < 0) {
                        lim = 0;
                        pos = 0;
                        return (pending.length() == 0) ? null : flush();
                    }
                    lim = n;
                    pos = 0;
                }
                int start = pos;
                while ((pos < lim) && (buf[pos] != '\n')) {
                    pos++;
                }
                pending.append(buf, start, pos - start);
                if (pos < lim) {
                    pos++; // consume the newline
                    terminated = true;
                    return flush();
                }
                if (pending.length() > maxLength) {
                    return flush();
                }
            }
        }

        /** Whether the last line {@link #readLine()} returned ended in a newline. */
        boolean lastLineTerminated() {
            return terminated;
        }

        /** Takes whatever part of a line has been read so far. */
        String flush() {
            String line = pending.toString();
            pending.setLength(0);
            return line;
        }

    }

    /**
     * The warning for {@link ClaudeAgentOptions#verbatimPrompts()} on a CLI too
     * old to honor {@code client_composed}, or {@code null} when none applies.
     * A CLI that reports no version counts as too old.
     *
     * <p>
     * Package-private for testing.
     *
     * @param version the dotted version the CLI reported, or null if none
     */
    @Nullable
    static String verbatimPromptsWarning(ClaudeAgentOptions options, @Nullable String version, String cliPath) {
        if (!options.verbatimPrompts()
                || ((version != null)
                        && (compareVersions(version, VERBATIM_PROMPTS_MINIMUM_CLAUDE_CODE_VERSION) >= 0))) {
            return null;
        }
        return String.format(
                "verbatimPrompts is enabled, but Claude Code version %s at %s ignores it: prompts will "
                        + "still have @path mentions expanded and slash commands dispatched. "
                        + "Claude Code %s or later is required.",
                ((version != null) ? version : "unknown"), cliPath, VERBATIM_PROMPTS_MINIMUM_CLAUDE_CODE_VERSION);
    }

    /**
     * The warning for a CLI whose init message reports no version, or
     * {@code null} when it reports one. Versions below the minimum, and the
     * first 2.0 releases, don't send {@code claude_code_version}.
     *
     * <p>
     * Package-private for testing.
     */
    @Nullable
    static String unsupportedVersionWarning(@Nullable String version, String cliPath) {
        if (version != null) {
            return null;
        }
        return String.format(
                "Claude Code at %s is too old to report its version, and may be unsupported in the "
                        + "Agent SDK. Minimum required version is %s. Some features may not work correctly.",
                cliPath, MINIMUM_CLAUDE_CODE_VERSION);
    }

    /**
     * The dotted version in an init frame's {@code claude_code_version}, or
     * null when absent or unparseable.
     */
    @Nullable
    static String reportedCliVersion(Map<String, Object> initFrame) {
        Object raw = initFrame.get("claude_code_version");
        if (raw == null) {
            return null;
        }
        Matcher m = CLI_VERSION.matcher(String.valueOf(raw));
        return m.lookingAt() ? (m.group(1) + "." + m.group(2) + "." + m.group(3)) : null;
    }

    /**
     * Warns, once per transport, if the CLI's first init message shows it is an
     * old version.
     *
     * <p>
     * The version comes from the running CLI rather than from a separate
     * {@code cliPath -v}, so {@code cliPath} is only ever executed by
     * {@link #connect()}. The CLI sends an init message per turn; only the
     * first is checked. Skipped entirely when
     * {@code CLAUDE_AGENT_SDK_SKIP_VERSION_CHECK} is set.
     *
     * <p>
     * Package-private for testing.
     */
    void checkClaudeVersion(Map<String, Object> data) {
        if (!"system".equals(data.get("type")) || !"init".equals(data.get("subtype"))
                || !versionChecked.compareAndSet(false, true)) {
            return;
        }
        String version = reportedCliVersion(data);
        String warning = unsupportedVersionWarning(version, cliPath);
        if (warning != null) {
            logger.warning(warning);
        }
        String verbatimWarning = verbatimPromptsWarning(options, version, cliPath);
        if (verbatimWarning != null) {
            logger.warning(verbatimWarning);
        }
    }

    private static int compareVersions(String v1, String v2) {
        String[] parts1 = v1.split("\\.");
        String[] parts2 = v2.split("\\.");
        int len = Math.max(parts1.length, parts2.length);

        for (int i = 0; i < len; i++) {
            int p1 = ((i < parts1.length) ? Integer.parseInt(parts1[i]) : 0);
            int p2 = ((i < parts2.length) ? Integer.parseInt(parts2[i]) : 0);
            if (p1 != p2) {
                return Integer.compare(p1, p2);
            }
        }
        return 0;
    }

    @SuppressWarnings("null")
    @Override
    public void write(String data) throws CLIConnectionException {
        logger.fine("Writing message to CLI: " + data);
        writeLock.lock();
        try {
            // All checks inside lock to prevent TOCTOU races with close()/end_input()
            if ((!ready.get()) || (stdin == null)) {
                throw new CLIConnectionException("ProcessTransport is not ready for writing");
            }

            if ((process != null) && (!process.isAlive())) {
                throw new CLIConnectionException(
                        "Cannot write to terminated process (exit code: " + process.exitValue() + ")");
            }

            if (exitError != null) {
                throw new CLIConnectionException(
                        "Cannot write to process that exited with error: " + exitError.getMessage(), exitError);
            }

            try {
                stdin.write(data);
                stdin.flush();
            } catch (IOException e) {
                ready.set(false);
                exitError = new CLIConnectionException("Failed to write to process stdin: " + e.getMessage(), e);
                throw (CLIConnectionException) exitError;
            }
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void endInput() {
        logger.fine("Ending input to CLI");
        writeLock.lock();
        try {
            if (stdin != null) {
                try {
                    stdin.close();
                } catch (IOException e) {
                    // Ignore
                }
                stdin = null;
            }
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public Iterator<Map<String, Object>> readMessages() {
        if (!iteratorCreated.compareAndSet(false, true)) {
            throw new IllegalStateException(
                    "readMessages() can only be called once per transport instance. " +
                            "Multiple concurrent readers on the same stdout stream is not supported.");
        }
        return new MessageIterator();
    }

    @Override
    public boolean isReady() {
        return ready.get();
    }

    /**
     * Closes stdin, waits for the CLI to exit, then terminates it if needed.
     *
     * <p>
     * Every step is bounded: the write lock, the grace period after stdin
     * EOF, SIGTERM, SIGKILL, and joining the reader threads. A caller whose
     * interrupt flag is set, or who is interrupted meanwhile, still gets the
     * whole graceful sequence: the flag is cleared for the duration and set
     * again on return. Otherwise an interrupted close() would skip stdin EOF
     * and kill the CLI before it could flush its session file.
     */
    @Override
    public void close() {
        logger.fine("Closing CLI transport");
        // Use atomic compare-and-set for thread-safe idempotent close
        if (closed.getAndSet(true)) {
            return; // Already closed
        }
        boolean interrupted = Thread.interrupted();
        try {
            interrupted |= closeGracefully();
        } finally {
            if (interrupted || Thread.interrupted()) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * The body of {@link #close()}, run with the interrupt flag clear.
     *
     * @return whether an interrupt arrived meanwhile
     */
    private boolean closeGracefully() {
        boolean interrupted = false;
        Process localProcess = process;
        if (localProcess == null) {
            ready.set(false);
            // Shutdown executors even if process is null
            interrupted |= shutdownExecutors();
            closeCompleted = true;
            return interrupted;
        }

        // Close stdin, holding the write lock so a concurrent write() can't
        // race it. Bounded, as in the Python SDK: a writer blocked on a full
        // stdin pipe must not pin close() forever.
        boolean locked = false;
        long lockDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(closeStepTimeoutMillis);
        while (true) {
            try {
                locked = writeLock.tryLock(Math.max(0L, lockDeadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        ready.set(false);
        if (locked) {
            try {
                closeQuietly(stdin);
                stdin = null;
            } finally {
                writeLock.unlock();
            }
        } else {
            logger.warning("A write to the CLI's stdin is still blocked; stopping the CLI without closing stdin");
        }

        // Wait for graceful shutdown after stdin EOF, then terminate if
        // needed. The subprocess needs time to flush its session file after
        // receiving EOF on stdin; without this grace period, SIGTERM can
        // interrupt the write and lose the last assistant message. Every step
        // is bounded, as in the Python SDK.
        //
        // stdout and stderr stay open meanwhile: the CLI may still be writing
        // (and the stderr callback still reading) while it shuts down, and
        // closing a BufferedReader would block on the lock its reader thread
        // holds inside readLine() anyway. The ProcessHandle signals are used
        // rather than Process.destroy(), which also closes the streams and so
        // can block on a writer stuck in a full stdin pipe.
        ProcessHandle handle = localProcess.toHandle();
        AtomicBoolean interruptedWaiting = new AtomicBoolean();
        if (!waitForExit(localProcess, interruptedWaiting)) {
            logger.warning("Process did not exit within grace period, sending SIGTERM");
            handle.destroy();
            if (!waitForExit(localProcess, interruptedWaiting)) {
                logger.warning("Process did not terminate after SIGTERM, forcing kill");
                handle.destroyForcibly();
                waitForExit(localProcess, interruptedWaiting);
            }
        }
        interrupted |= interruptedWaiting.get();

        // Only stop tracking a child we actually reaped. A still-running process
        // (destroyForcibly raced, or waitFor timed out) stays in the set so the
        // JVM shutdown-hook reaper gets a chance at it — dropping it here is what
        // turns a wedged close() into a leaked child.
        if (!localProcess.isAlive()) {
            ACTIVE_CHILDREN.remove(localProcess);
        }

        // The readers have reached end of stream now unless something else
        // holds the pipes open; give them their bounded time to deliver what
        // is left, then close the streams underneath them. Closing the raw
        // streams (not the readers) never waits on a reader's lock.
        interrupted |= shutdownExecutors();
        if (!locked) {
            // The blocked write fails once the CLI is gone; close stdin when
            // it lets go of the lock, off this thread.
            Threads.start("SPCTransport-StdinClose-", () -> {
                writeLock.lock();
                try {
                    closeQuietly(stdin);
                    stdin = null;
                } finally {
                    writeLock.unlock();
                }
            });
        }
        closeQuietly(localProcess.getInputStream());
        closeQuietly(localProcess.getErrorStream());

        process = null;
        stdout = null;
        exitError = null;
        closeCompleted = true;
        return interrupted;
    }

    /**
     * Waits up to {@link #closeStepTimeoutMillis} for {@code process} to
     * exit. An interrupt does not cut the wait short; it is recorded in
     * {@code interrupted}.
     *
     * @return whether the process exited
     */
    private boolean waitForExit(Process process, AtomicBoolean interrupted) {
        return Uninterruptibly.waitFor(process, closeStepTimeoutMillis, TimeUnit.MILLISECONDS, interrupted);
    }

    /**
     * The bound on each step of {@link #close()}: lock, wait, terminate, kill.
     * Package-private so tests can shorten it.
     */
    long closeStepTimeoutMillis = TimeUnit.SECONDS.toMillis(5);

    private static void closeQuietly(@Nullable Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException e) {
            // Ignore
        }
    }

    /**
     * Shuts down executor services gracefully with timeout.
     *
     * @return whether an interrupt arrived meanwhile (the waits go on)
     */
    private boolean shutdownExecutors() {
        boolean interrupted = shutdownExecutor(stderrExecutor, "Stderr");
        interrupted |= shutdownExecutor(messageReaderExecutor, "MsgReader");
        return interrupted;
    }

    private static boolean shutdownExecutor(ExecutorService executor, String name) {
        AtomicBoolean interrupted = new AtomicBoolean();
        executor.shutdown();
        if (!Uninterruptibly.awaitTermination(executor, 2, TimeUnit.SECONDS, interrupted)) {
            logger.fine(name + " executor did not terminate gracefully, forcing shutdown");
            executor.shutdownNow();
            // Verify forced termination
            if (!Uninterruptibly.awaitTermination(executor, 1, TimeUnit.SECONDS, interrupted)) {
                logger.warning(name + " executor did not terminate even after shutdownNow()");
            }
        }
        return interrupted.get();
    }

    /**
     * Iterator implementation for reading messages.
     */
    private class MessageIterator implements Iterator<Map<String, Object>> {

        private final BlockingQueue<Map<String, Object>> queue = new LinkedBlockingQueue<>(maxMsgQSize);
        private final AtomicBoolean done = new AtomicBoolean(false);
        @Nullable
        private Map<String, Object> nextMessage = null;

        MessageIterator() {
            // Submit message reading task to dedicated executor
            messageReaderExecutor.submit(this::readLoop);
        }

        /**
         * Reads stdout until it ends, as the Python SDK's
         * {@code _read_messages_impl} does: one message per line, a corrupt or
         * oversized line ends the stream with {@link CLIJSONDecodeException},
         * and a clean end of stream is followed by the exit-code check.
         */
        private void readLoop() {
            // Capture references locally to prevent NPE from concurrent close()
            Reader localStdout = stdout;
            Process localProcess = process;

            if ((localStdout == null) || (localProcess == null)) {
                done.set(true);
                return;
            }

            Exception error = null;
            boolean checkExit = true;
            LineFramer framer = new LineFramer(localStdout, maxBufferSize);
            try {
                String line;
                while ((line = framer.readLine()) != null) {
                    Map<String, Object> data = stdoutMessage(line, framer.lastLineTerminated(), maxBufferSize);
                    if (data == null) {
                        continue;
                    }
                    logger.fine("Received message from CLI: " + line);
                    checkClaudeVersion(data);
                    if (!enqueue(data)) {
                        break;
                    }
                }
            } catch (CLIJSONDecodeException e) {
                // As in the Python SDK, the decode error ends the stream; the
                // CLI is still running, so there is no exit code to wait for.
                error = e;
                checkExit = false;
            } catch (IOException e) {
                // Stream closed - normal termination
            } catch (RuntimeException e) {
                error = new CLIConnectionException("Unexpected error reading messages: " + e.getMessage(), e);
                checkExit = false;
            } finally {
                if (checkExit) {
                    try {
                        int exitCode = localProcess.waitFor();
                        if (exitCode != 0) {
                            error = new ProcessException(
                                    "Command failed with exit code " + exitCode,
                                    exitCode,
                                    "Check stderr output for details");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                // The error first, then done: hasNext() reads exitError once it
                // sees done, so the other order can end the stream without it.
                if (error != null) {
                    exitError = error;
                }
                done.set(true);
                closeQuietly(localStdout);
            }
        }

        /**
         * Hands a message to the consumer, waiting for room rather than
         * dropping it. Returns false if interrupted or the transport closed.
         */
        private boolean enqueue(Map<String, Object> data) {
            try {
                while (!queue.offer(data, 400, TimeUnit.MILLISECONDS)) {
                    if (closed.get()) {
                        return false;
                    }
                }
                return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        @Override
        public boolean hasNext() {
            if (nextMessage != null) {
                return true;
            }

            while (!(readerFinished() && queue.isEmpty())) {
                try {
                    Map<String, Object> msg;
                    if (!readerFinished()) {
                        // Reader still active - wait up to 0.4s for next message
                        msg = queue.poll(400, TimeUnit.MILLISECONDS);
                        if (msg != null) {
                            nextMessage = msg;
                            return true;
                        }
                        // Timeout - loop back to recheck done flag
                    } else {
                        // Reader finished - drain remaining messages without blocking
                        msg = queue.poll();
                        if (msg != null) {
                            nextMessage = msg;
                            return true;
                        }
                        // No more messages and reader is done
                        break;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }

            // Check for exit errors and propagate them
            if (exitError instanceof ProcessException pe) {
                throw pe;
            } else if (exitError instanceof CLIJSONDecodeException je) {
                throw je;
            } else if (exitError instanceof CLIConnectionException ce) {
                throw ce;
            }

            return false;
        }

        private boolean readerFinished() {
            return done.get() || closeCompleted;
        }

        @Override
        public Map<String, Object> next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            Map<String, Object> msg = nextMessage;
            nextMessage = null;
            return msg;
        }

    }

}
