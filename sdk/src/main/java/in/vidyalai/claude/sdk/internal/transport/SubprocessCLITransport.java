package in.vidyalai.claude.sdk.internal.transport;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.exceptions.CLIConnectionException;
import in.vidyalai.claude.sdk.exceptions.CLIJSONDecodeException;
import in.vidyalai.claude.sdk.exceptions.CLINotFoundException;
import in.vidyalai.claude.sdk.exceptions.ProcessException;
import in.vidyalai.claude.sdk.internal.SdkVersion;
import in.vidyalai.claude.sdk.transport.Transport;
import in.vidyalai.claude.sdk.types.config.SdkBeta;
import in.vidyalai.claude.sdk.types.config.SettingSource;
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
 * operations.
 * Shuts down both executor services gracefully (with 2s timeout each, then
 * forced shutdown with 1s verification), terminates the CLI process (5s
 * graceful, then forced), and cleans up all resources. Logs warnings if
 * resources don't terminate within timeouts.</li>
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
 * <li>Cleans up temporary files created for command-line arguments</li>
 * <li>Closes stdin stream to signal end of input to the CLI process</li>
 * <li>Closes stdout and stderr streams</li>
 * <li>Shuts down stderr executor gracefully (2s timeout), then forced (1s
 * verification)</li>
 * <li>Shuts down message reader executor gracefully (2s timeout), then forced
 * (1s verification)</li>
 * <li>Logs warnings if executor threads don't terminate after forced
 * shutdown</li>
 * <li>Terminates CLI process gracefully via {@code destroy()} (5s timeout)</li>
 * <li>Forces process termination via {@code destroyForcibly()} if still alive
 * (2s wait)</li>
 * <li>Logs warning if process termination required force-kill</li>
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

    // Executor services for background tasks with named virtual threads
    private final ExecutorService stderrExecutor;
    private final ExecutorService messageReaderExecutor;

    @Nullable
    private volatile Process process;
    @Nullable
    private volatile BufferedWriter stdin;
    @Nullable
    private volatile BufferedReader stdout;
    @Nullable
    private volatile BufferedReader stderr;
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
        this.cliPath = ((path != null) ? path.toString() : findCli());
        this.cwd = options.cwd();
        Integer buffSize = options.maxBufferSize();
        this.maxBufferSize = ((buffSize != null) ? buffSize : DEFAULT_MAX_BUFFER_SIZE);
        Integer msgQSize = options.maxMsgQSize();
        this.maxMsgQSize = ((msgQSize != null) ? msgQSize : DEFAULT_MSG_Q_SIZE);

        // Create named executor services for background tasks
        // Stderr reader: Single-threaded executor for reading stderr
        this.stderrExecutor = Executors.newSingleThreadExecutor(
                Thread.ofVirtual()
                        .name("SPCTransport-Stderr-", 0)
                        .factory());

        // Message reader: Single-threaded executor for reading stdout messages
        this.messageReaderExecutor = Executors.newSingleThreadExecutor(
                Thread.ofVirtual()
                        .name("SPCTransport-MsgReader-", 0)
                        .factory());
    }

    private String findCli() {
        return findCli(System.getenv("PATH"));
    }

    /**
     * Resolves the CLI path against the supplied {@code PATH} value.
     *
     * <p>
     * Package-private with {@code PATH} injected so the platform-specific
     * discovery order can be tested; the production caller passes
     * {@code System.getenv("PATH")}.
     *
     * @param pathEnv the {@code PATH} environment value, or null
     * @return the resolved CLI path
     * @throws CLINotFoundException if no CLI could be located
     */
    static String findCli(@Nullable String pathEnv) {
        boolean windows = isWindows();

        // Check PATH
        String pathCli = findInPath(CLAUDE_CLI_NAME, pathEnv);
        if (pathCli != null && (!windows || isWindowsNativeExe(pathCli))) {
            return pathCli;
        }

        // Check common locations. On Windows only the native installer's
        // claude.exe is probed: an extensionless match (a WSL / git-bash script
        // artifact under ~/.local/bin) would preempt the explanatory
        // batch-script refusal with an opaque spawn failure, and a
        // rooted-but-driveless "/usr/local/bin/claude" resolves against the
        // current drive (C: followed by that same path), a location another
        // local user can create — a binary-planting probe.
        List<Path> locations = windows
                ? List.of(Path.of(System.getProperty("user.home"), ".local", "bin",
                        CLAUDE_CLI_NAME + ".exe"))
                : List.of(
                        Path.of(System.getProperty("user.home"), ".npm-global", "bin", CLAUDE_CLI_NAME),
                        Path.of("/usr/local/bin/" + CLAUDE_CLI_NAME),
                        Path.of(System.getProperty("user.home"), ".local", "bin", CLAUDE_CLI_NAME),
                        Path.of(System.getProperty("user.home"), "node_modules", ".bin", CLAUDE_CLI_NAME),
                        Path.of(System.getProperty("user.home"), ".yarn", "bin", CLAUDE_CLI_NAME),
                        Path.of(System.getProperty("user.home"), ".claude", "local", CLAUDE_CLI_NAME));

        for (Path path : locations) {
            if (Files.exists(path) && Files.isRegularFile(path)) {
                return path.toString();
            }
        }

        if (windows) {
            // No native executable was discoverable anywhere. Prefer the npm
            // batch shim over an extensionless wrapper script: connect() turns
            // the shim into the explanatory batch-script refusal, whereas the
            // wrapper only reaches an opaque "not a valid Win32 application"
            // spawn failure. Windows itself resolves .CMD/.BAT ahead of an
            // extensionless name via PATHEXT, so this also matches what the
            // shell would have run.
            String shim = findInPath(CLAUDE_CLI_NAME + ".cmd", pathEnv);
            if (shim == null) {
                shim = findInPath(CLAUDE_CLI_NAME + ".bat", pathEnv);
            }
            if (shim != null) {
                return shim;
            }
        }

        if (pathCli != null) {
            // A non-native PATH hit (an extensionless git-bash / WSL wrapper).
            // Returned so connect() surfaces the spawn error for it rather than
            // a bare not-found error that hides what is actually installed.
            return pathCli;
        }

        if (windows) {
            // npm's Windows install is a claude.cmd shim, which connect()
            // refuses (rejectWindowsBatchCli), so do not recommend it.
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

    @Nullable
    static String findInPath(String executable, @Nullable String pathEnv) {
        if (pathEnv == null) {
            return null;
        }

        boolean windows = isWindows();
        String pathSeparator = windows ? ";" : ":";
        String[] dirs = pathEnv.split(pathSeparator);

        // Only an extensionless name gets the .exe treatment; an explicit
        // "claude.cmd" probe must not go looking for "claude.cmd.exe".
        boolean tryExeSuffix = windows && executable.indexOf('.') < 0;

        // On Windows, sweep the whole PATH for the native executable before
        // considering any extensionless entry. PATH is walked directory-major,
        // so a git-bash / WSL wrapper script named "claude" in an early
        // directory would otherwise shadow a real claude.exe installed in a
        // later one — and the wrapper is not something the OS can run directly.
        if (tryExeSuffix) {
            String exe = firstExecutableIn(dirs, executable + ".exe");
            if (exe != null) {
                return exe;
            }
        }
        return firstExecutableIn(dirs, executable);
    }

    @Nullable
    private static String firstExecutableIn(String[] dirs, String fileName) {
        for (String dir : dirs) {
            if (dir.isEmpty()) {
                continue;
            }
            Path path = Path.of(dir, fileName);
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
     * Whether {@code cliPath}'s final component names an image the OS runs
     * directly ({@code .exe} / {@code .com}).
     *
     * <p>
     * Used only to decide which discovery result to prefer. It is not a
     * security gate: every returned path still passes
     * {@link #rejectWindowsBatchCli} before anything is spawned.
     */
    static boolean isWindowsNativeExe(String cliPath) {
        String name = cliPath.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        name = stripTrailingDotsAndSpaces(name).toLowerCase(Locale.ROOT);
        return name.endsWith(".exe") || name.endsWith(".com");
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
     * In practice this refuses npm's {@code claude.cmd} shim. The alternatives
     * in the error message avoid cmd.exe entirely.
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
                        + "ClaudeAgentOptions.cliPath(...) at a claude.exe.");
    }

    /**
     * Defense in depth for Windows: rejects cmd.exe metacharacters.
     *
     * <p>
     * With batch-script spawning refused ({@link #rejectWindowsBatchCli}) these
     * characters are harmless, because argument quoting is correct for native
     * executables. They are rejected anyway so that {@code resume} and
     * {@code sessionId} values, which applications commonly take from external
     * input, stay inert even if a cmd.exe hop is ever reintroduced between the
     * SDK and the CLI. No format is imposed beyond this — resume values may be
     * arbitrary session titles, not only UUIDs — and POSIX behavior is
     * unchanged.
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
     * Build settings value, merging sandbox settings if provided.
     * 
     * Returns the settings value as either:
     * - A JSON string (if sandbox is provided or settings is JSON)
     * - A file path (if only settings path is provided without sandbox)
     * - Null if neither settings nor sandbox is provided or in case of exception
     */
    @SuppressWarnings("null")
    @Nullable
    private String buildSettingsValue() {
        boolean hasSettings = (options.settings() != null);
        boolean hasSandbox = (options.sandbox() != null);

        if (!(hasSettings || hasSandbox)) {
            return null;
        }

        // If only settings path and no sandbox, pass through as-is
        if (hasSettings && (!hasSandbox)) {
            return options.settings();
        }

        // If we have sandbox settings, merge into JSON
        Map<String, Object> settingsObj = new HashMap<>();

        if (hasSettings) {
            String settingsStr = options.settings().trim();
            if (settingsStr.startsWith("{") && settingsStr.endsWith("}")) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> parsed = MAPPER.readValue(settingsStr, Map.class);
                    settingsObj.putAll(parsed);
                } catch (JsonProcessingException e) {
                    // If parsing fails, try as file path
                    settingsObj = readSettingsFile(settingsStr);
                }
            } else {
                settingsObj = readSettingsFile(settingsStr);
            }
        }

        if (hasSandbox) {
            settingsObj.put("sandbox", options.sandbox());
        }

        try {
            return MAPPER.writeValueAsString(settingsObj);
        } catch (JsonProcessingException e) {
            logger.warning("Failed to serialize settings: " + e.getMessage());
            return null;
        }
    }

    private Map<String, Object> readSettingsFile(String path) {
        try {
            Path settingsPath = Path.of(path);
            if (Files.exists(settingsPath)) {
                String content = Files.readString(settingsPath);
                @SuppressWarnings({ "unchecked", "null" })
                Map<String, Object> parsed = MAPPER.readValue(content, Map.class);
                return parsed;
            }
        } catch (Exception e) {
            logger.warning("Settings file not found or invalid: " + path);
        }
        return new HashMap<>();
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

        if (options.maxTurns() != null) {
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

        if (options.model() != null) {
            cmd.add("--model");
            cmd.add(options.model());
        }

        if (options.fallbackModel() != null) {
            cmd.add("--fallback-model");
            cmd.add(options.fallbackModel());
        }

        if (!options.betas().isEmpty()) {
            cmd.add("--betas");
            cmd.add(String.join(",", options.betas().stream().map(SdkBeta::getValue).toList()));
        }

        if (options.permissionPromptToolName() != null) {
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
        if (options.resume() != null) {
            rejectWindowsCmdMetacharacters("resume", options.resume());
            cmd.add("--resume=" + options.resume());
        }

        if (options.sessionId() != null) {
            rejectWindowsCmdMetacharacters("sessionId", options.sessionId());
            cmd.add("--session-id=" + options.sessionId());
        }

        // Settings and sandbox
        String settingsValue = buildSettingsValue();
        if (settingsValue != null) {
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
                    try {
                        String val = MAPPER.writeValueAsString(new McpConfigPayload(mcpServers));
                        cmd.add("--mcp-config");
                        cmd.add(val);
                    } catch (JsonProcessingException e) {
                        logger.warning("Failed to serialize MCP config: " + e.getMessage());
                    }
                }
            } else if (options.mcpServers() instanceof String s) {
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
            }
        }

        // Extra args
        for (Map.Entry<String, String> entry : options.extraArgs().entrySet()) {
            String value = entry.getValue();
            if ((value == null) || value.isBlank()) {
                // Boolean flag without value
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
            switch (thinking) {
                case ThinkingConfigAdaptive adaptive -> {
                    cmd.add("--thinking");
                    cmd.add("adaptive");
                    display = adaptive.display();
                }
                case ThinkingConfigEnabled enabled -> {
                    cmd.add("--max-thinking-tokens");
                    cmd.add(String.valueOf(enabled.budgetTokens()));
                    display = enabled.display();
                }
                case ThinkingConfigDisabled _ -> {
                    cmd.add("--thinking");
                    cmd.add("disabled");
                }
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
                try {
                    String val = MAPPER.writeValueAsString(schema);
                    cmd.add("--json-schema");
                    cmd.add(val);
                } catch (JsonProcessingException e) {
                    logger.warning("Failed to serialize output schema: " + e.getMessage());
                }
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

        // Validate the resolved CLI before anything is spawned with it. This
        // guards the version probe below as well as the main spawn, and covers
        // every route to the executable path: PATH discovery, an explicit
        // ClaudeAgentOptions.cliPath, and the bundled-location fallbacks.
        rejectWindowsBatchCli(cliPath);

        // Check version (unless skipped)
        if (System.getenv("CLAUDE_AGENT_SDK_SKIP_VERSION_CHECK") == null) {
            checkClaudeVersion();
        }

        List<String> cmd = buildCommand();
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

            // Pipe stderr only when the caller registered a callback. The
            // legacy ``--debug-to-stderr`` flag detection was removed in
            // upstream prep for the CLI flag's removal.
            boolean shouldPipeStderr = options.stderrCallback() != null;
            pb.redirectErrorStream(false);

            logger.fine("Claude ENV:" + env);
            process = pb.start();
            ACTIVE_CHILDREN.add(process);

            stdin = new BufferedWriter(
                    new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            stdout = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

            // Start stderr reader thread if needed
            if (shouldPipeStderr) {
                stderr = new BufferedReader(
                        new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8));
                // Submit stderr reading task to dedicated executor
                stderrExecutor.submit(() -> handleStderr(stderr));
            }

            ready.set(true);

        } catch (IOException e) {
            if ((cwd != null) && (!Files.exists(cwd))) {
                CLIConnectionException error = new CLIConnectionException(
                        "Working directory does not exist: " + cwd, e);
                exitError = error;
                throw error;
            }
            CLINotFoundException error = new CLINotFoundException("Claude Code not found at", cliPath);
            exitError = error;
            throw error;
        } catch (Exception e) {
            CLIConnectionException error = new CLIConnectionException("Failed to start Claude Code");
            exitError = error;
            throw error;
        }
    }

    @SuppressWarnings("null")
    // Package-private for tests.
    void handleStderr(BufferedReader stderr) {
        try (stderr) {
            String line;
            while ((line = stderr.readLine()) != null) {
                if (options.stderrCallback() != null) {
                    // Isolate per-line so a raise in the user's callback doesn't
                    // terminate the loop and silently drop every subsequent
                    // stderr line for the rest of the session.
                    try {
                        options.stderrCallback().accept(line);
                    } catch (Throwable t) {
                        logger.log(Level.FINE, "stderr callback raised; continuing", t);
                    }
                }
            }
        } catch (Exception e) {
            logger.log(Level.FINE, "stderr stream read failed", e);
        }
    }

    private void checkClaudeVersion() {
        Process versionProcess = null;
        try {
            versionProcess = new ProcessBuilder(cliPath, "-v")
                    .redirectErrorStream(true)
                    .start();

            boolean completed = versionProcess.waitFor(2, TimeUnit.SECONDS);
            if (!completed) {
                versionProcess.destroyForcibly();
                return;
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(versionProcess.getInputStream()))) {
                String versionOutput = reader.readLine();
                if (versionOutput != null) {
                    String version = versionOutput.replaceAll("[^0-9.].*", "");
                    if ((!version.isEmpty()) && (compareVersions(version, MINIMUM_CLAUDE_CODE_VERSION) < 0)) {
                        String warning = String.format(
                                "Warning: Claude Code version %s is unsupported. Minimum required: %s",
                                version, MINIMUM_CLAUDE_CODE_VERSION);
                        logger.warning(warning);
                        System.err.println(warning);
                    }
                }
            }
        } catch (Exception e) {
            // Ignore version check errors
        } finally {
            if ((versionProcess != null) && versionProcess.isAlive()) {
                versionProcess.destroyForcibly();
                try {
                    versionProcess.waitFor(1, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private int compareVersions(String v1, String v2) {
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

    @SuppressWarnings("null")
    @Override
    public void close() {
        logger.fine("Closing CLI transport");
        // Use atomic compare-and-set for thread-safe idempotent close
        if (closed.getAndSet(true)) {
            return; // Already closed
        }

        if (process == null) {
            ready.set(false);
            // Shutdown executors even if process is null
            shutdownExecutors();
            return;
        }

        // Close stdin
        writeLock.lock();
        try {
            ready.set(false);
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

        // Close stdout
        if (stdout != null) {
            try {
                stdout.close();
            } catch (IOException e) {
                // Ignore
            }
        }

        // Close stderr reader
        if (stderr != null) {
            try {
                stderr.close();
            } catch (IOException e) {
                // Ignore
            }
        }

        // Shutdown executor services (interrupts running tasks)
        shutdownExecutors();

        // Wait for graceful shutdown after stdin EOF, then terminate if needed.
        // The subprocess needs time to flush its session file after receiving
        // EOF on stdin. Without this grace period, SIGTERM can interrupt the
        // write and cause the last assistant message to be lost.
        if ((process != null) && process.isAlive()) {
            try {
                boolean exited = process.waitFor(5, TimeUnit.SECONDS);
                if (!exited && process.isAlive()) {
                    // Graceful shutdown timed out — force terminate
                    logger.warning("Process did not exit within grace period, sending SIGTERM");
                    process.destroy();
                    boolean terminated = process.waitFor(5, TimeUnit.SECONDS);
                    if (!terminated && process.isAlive()) {
                        logger.warning("Process did not terminate after SIGTERM, forcing kill");
                        process.destroyForcibly();
                        process.waitFor(2, TimeUnit.SECONDS);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }

        // Only stop tracking a child we actually reaped. A still-running process
        // (destroyForcibly raced, or waitFor timed out) stays in the set so the
        // JVM shutdown-hook reaper gets a chance at it — dropping it here is what
        // turns a wedged close() into a leaked child.
        if ((process != null) && !process.isAlive()) {
            ACTIVE_CHILDREN.remove(process);
        }
        process = null;
        stdout = null;
        stderr = null;
        exitError = null;
    }

    /**
     * Shuts down executor services gracefully with timeout.
     */
    private void shutdownExecutors() {
        shutdownExecutor(stderrExecutor, "Stderr");
        shutdownExecutor(messageReaderExecutor, "MsgReader");
    }

    private void shutdownExecutor(ExecutorService executor, String name) {
        // Shutdown executor
        executor.shutdown();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                logger.fine(name + " executor did not terminate gracefully, forcing shutdown");
                executor.shutdownNow();
                // Verify forced termination
                boolean terminated = executor.awaitTermination(1, TimeUnit.SECONDS);
                if (!terminated) {
                    logger.warning(name + " executor did not terminate even after shutdownNow()");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
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

        @SuppressWarnings({ "unchecked", "null" })
        private void readLoop() {
            // Capture references locally to prevent NPE from concurrent close()
            BufferedReader localStdout = stdout;
            Process localProcess = process;

            if ((localStdout == null) || (localProcess == null)) {
                done.set(true);
                return;
            }

            StringBuilder jsonBuffer = new StringBuilder();

            try {
                String line;
                while ((line = localStdout.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) {
                        continue;
                    }

                    // Skip non-JSON lines (e.g. [SandboxDebug]) when not
                    // mid-parse — they corrupt the buffer otherwise.
                    if (jsonBuffer.isEmpty() && !line.startsWith("{")) {
                        logger.fine("Skipping non-JSON line from CLI stdout: "
                                + line.substring(0, Math.min(line.length(), 200)));
                        continue;
                    }

                    // Accumulate partial JSON
                    jsonBuffer.append(line);

                    if (jsonBuffer.length() > maxBufferSize) {
                        int bufferLength = jsonBuffer.length();
                        jsonBuffer.setLength(0);
                        throw new CLIJSONDecodeException(
                                "JSON message exceeded maximum buffer size of " + maxBufferSize + " bytes",
                                new IllegalStateException(
                                        "Buffer size " + bufferLength + " exceeds limit " + maxBufferSize));
                    }

                    try {
                        String json = jsonBuffer.toString();
                        Map<String, Object> data = MAPPER.readValue(json, Map.class);
                        logger.fine("Received message from CLI: " + json);
                        jsonBuffer.setLength(0);
                        // Use offer() with timeout instead of put() to avoid indefinite blocking
                        if (!queue.offer(data, 5, TimeUnit.SECONDS)) {
                            logger.warning("Failed to enqueue message - consumer may have stopped consuming");
                            // Continue trying - consumer might catch up
                        }
                    } catch (JsonProcessingException e) {
                        // Incomplete JSON, continue accumulating
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            } catch (CLIJSONDecodeException e) {
                // Buffer overflow or JSON decode error - store for consumer
                exitError = e;
            } catch (IOException e) {
                // Stream closed - normal termination
            } catch (Exception e) {
                // Catch any other unexpected exceptions
                exitError = new CLIConnectionException("Unexpected error reading messages: " + e.getMessage(), e);
            } finally {
                done.set(true);

                // Check process exit code using local reference
                try {
                    if (localProcess != null) {
                        int exitCode = localProcess.waitFor();
                        if (exitCode != 0) {
                            exitError = new ProcessException(
                                    "Command failed with exit code " + exitCode,
                                    exitCode,
                                    "Check stderr output for details");
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        @Override
        public boolean hasNext() {
            if (nextMessage != null) {
                return true;
            }

            while (!(done.get() && queue.isEmpty())) {
                try {
                    Map<String, Object> msg;
                    if (!done.get()) {
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
