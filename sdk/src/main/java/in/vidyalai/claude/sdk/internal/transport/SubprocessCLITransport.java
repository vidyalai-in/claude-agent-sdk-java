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
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

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
import in.vidyalai.claude.sdk.types.config.SystemPromptPreset;
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

    // Platform-specific command line length limits
    private static final int CMD_LENGTH_LIMIT = System.getProperty("os.name").toLowerCase().contains("win")
            ? 8000
            : 100000;

    private final String prompt;
    private final boolean isStreaming;
    private final ClaudeAgentOptions options;
    private final String cliPath;
    @Nullable
    private final Path cwd;
    private final int maxBufferSize;
    private final int maxMsgQSize;
    private final ReentrantLock writeLock = new ReentrantLock();
    private final List<Path> tempFiles = Collections.synchronizedList(new ArrayList<>());
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
     * @param prompt      the prompt to send (null for streaming mode)
     * @param isStreaming whether to use streaming mode
     * @param options     the agent options
     */
    public SubprocessCLITransport(@Nullable String prompt, boolean isStreaming, ClaudeAgentOptions options) {
        this.prompt = ((prompt != null) ? prompt : "");
        this.isStreaming = isStreaming;
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
        // Check PATH
        String pathCli = findInPath(CLAUDE_CLI_NAME);
        if (pathCli != null) {
            return pathCli;
        }

        // Check common locations
        List<Path> locations = List.of(
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

        throw new CLINotFoundException("""
                Claude Code not found. Install by following: https://code.claude.com/docs/en/setup
                If already installed locally, try adding it to PATH Or provide the path via
                ClaudeAgentOptions: ClaudeAgentOptions(cli_path='/path/to/claude')
                """);
    }

    @Nullable
    private String findInPath(String executable) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) {
            return null;
        }

        String pathSeparator = System.getProperty("os.name").toLowerCase().contains("win") ? ";" : ":";
        String[] dirs = pathEnv.split(pathSeparator);

        for (String dir : dirs) {
            Path path = Path.of(dir, executable);
            if (Files.exists(path) && Files.isExecutable(path)) {
                return path.toString();
            }
            // On Windows, also check with .exe extension
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                path = Path.of(dir, executable + ".exe");
                if (Files.exists(path) && Files.isExecutable(path)) {
                    return path.toString();
                }
            }
        }
        return null;
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

    @SuppressWarnings({ "unchecked", "null" })
    private List<String> buildCommand() {
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

        if (!options.allowedTools().isEmpty()) {
            cmd.add("--allowedTools");
            cmd.add(String.join(",", options.allowedTools()));
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

        if (options.resume() != null) {
            cmd.add("--resume");
            cmd.add(options.resume());
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

        if (options.forkSession()) {
            cmd.add("--fork-session");
        }

        // Agents
        if ((options.agents() != null) && (!options.agents().isEmpty())) {
            try {
                String agentsJson = MAPPER.writeValueAsString(options.agents());
                cmd.add("--agents");
                cmd.add(agentsJson);
            } catch (JsonProcessingException e) {
                logger.warning("Failed to serialize agents: " + e.getMessage());
            }
        }

        // Setting sources
        String sourcesValue = ((options.settingSources() != null)
                ? String.join(",", options.settingSources().stream().map(SettingSource::getValue).toList())
                : "");
        cmd.add("--setting-sources");
        cmd.add(sourcesValue);

        // Plugins
        for (ClaudeAgentOptions.SdkPluginConfig plugin : options.plugins()) {
            if ("local".equals(plugin.type())) {
                cmd.add("--plugin-dir");
                cmd.add(plugin.path());
            }
        }

        // Extra args
        for (Map.Entry<String, String> entry : options.extraArgs().entrySet()) {
            cmd.add("--" + entry.getKey());
            if ((entry.getValue() != null) && (!entry.getValue().isBlank())) {
                cmd.add(entry.getValue());
            }
        }

        if (options.maxThinkingTokens() != null) {
            cmd.add("--max-thinking-tokens");
            cmd.add(String.valueOf(options.maxThinkingTokens()));
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

        // Prompt handling - MUST come after all flags
        // because everything after "--" is treated as arguments
        if (isStreaming) {
            cmd.add("--input-format");
            cmd.add("stream-json");
        } else {
            cmd.add("--print");
            cmd.add("--");
            cmd.add(prompt);
        }

        // Handle long command lines
        if ((String.join(" ", cmd).length() > CMD_LENGTH_LIMIT) && (options.agents() != null)) {
            optimizeCommandLine(cmd);
        }

        return cmd;
    }

    private void optimizeCommandLine(List<String> cmd) {
        int agentsIdx = cmd.indexOf("--agents");
        if ((agentsIdx >= 0) && (agentsIdx + 1 < cmd.size())) {
            String agentsJson = cmd.get(agentsIdx + 1);
            try {
                Path tempFile = Files.createTempFile("claude-agents-", ".json");
                Files.writeString(tempFile, agentsJson);
                tempFiles.add(tempFile);
                cmd.set(agentsIdx + 1, "@" + tempFile);
                logger.info("Command line too long, using temp file for --agents: " + tempFile);
            } catch (IOException e) {
                logger.warning("Failed to optimize command line: " + e.getMessage());
            }
        }
    }

    @SuppressWarnings("null")
    @Override
    public synchronized void connect() throws CLIConnectionException {
        if (process != null) {
            return;
        }

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
            env.putAll(options.env());
            env.put("CLAUDE_CODE_ENTRYPOINT", "sdk-java");
            env.put("CLAUDE_AGENT_SDK_VERSION", SdkVersion.VERSION);

            if (options.enableFileCheckpointing()) {
                env.put("CLAUDE_CODE_ENABLE_SDK_FILE_CHECKPOINTING", "true");
            }

            if (cwd != null) {
                pb.directory(cwd.toFile());
                env.put("PWD", cwd.toString());
            }

            // Configure stderr handling
            boolean shouldPipeStderr = ((options.stderrCallback() != null)
                    || options.extraArgs().containsKey("debug-to-stderr"));
            pb.redirectErrorStream(false);

            logger.fine("Claude ENV:" + env);
            process = pb.start();

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

            // Close stdin immediately if not streaming
            if (!isStreaming) {
                stdin.close();
                stdin = null;
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
    private void handleStderr(BufferedReader stderr) {
        try (stderr) {
            String line;
            while ((line = stderr.readLine()) != null) {
                if (options.stderrCallback() != null) {
                    options.stderrCallback().accept(line);
                }
            }
        } catch (Exception e) {
            // Stream closed
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

        // Clean up temp files
        for (Path tempFile : tempFiles) {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException e) {
                // Ignore
            }
        }
        tempFiles.clear();

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

        // Terminate process
        if ((process != null) && process.isAlive()) {
            process.destroy();
            try {
                boolean terminated = process.waitFor(5, TimeUnit.SECONDS);
                if ((!terminated) && process.isAlive()) {
                    logger.warning("Process did not terminate gracefully, forcing kill");
                    process.destroyForcibly();
                    // Wait a bit for forced termination
                    process.waitFor(2, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
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
