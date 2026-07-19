# Transport Layer

Custom transport implementations for Claude Code communication.

## Overview

The transport layer handles I/O with Claude Code CLI. You can implement custom transports for remote connections or alternative communication methods.

## Transport Interface

```java
public interface Transport extends AutoCloseable {
    void connect() throws CLIConnectionException;
    void write(String data) throws CLIConnectionException;
    Iterator<Map<String, Object>> readMessages();
    void endInput();
    boolean isReady();
    void close();
}
```

## Default Implementation

### SubprocessCLITransport

The default transport spawns Claude Code CLI as a subprocess:

```java
Transport transport = new SubprocessCLITransport(
    prompt,           // Initial prompt (null for streaming)
    isStreaming,      // Streaming mode
    options           // Configuration options
);
```

**Features**:
- Manages subprocess lifecycle
- Stdin/stdout communication
- Buffered reading
- Stderr callback support
- Automatic cleanup
- Graceful shutdown with grace period (waits for subprocess to flush session file after stdin EOF before sending SIGTERM)
- Sets `CLAUDE_CODE_ENTRYPOINT=sdk-java` by default (overridable via `ClaudeAgentOptions.env()`)

### CLI flag forwarding

`SubprocessCLITransport.buildCommand()` translates `ClaudeAgentOptions` into CLI flags. Notable flags relevant to recent options:

| Option | CLI flag(s) | Notes |
|---|---|---|
| `sessionStore(...)` | `--session-mirror` | Added when `sessionStore != null`. Tells the CLI to emit `transcript_mirror` frames on stdout, which the SDK peels off and forwards to the configured `SessionStore`. |
| `thinking(ThinkingConfigAdaptive(SUMMARIZED))` | `--thinking adaptive --thinking-display summarized` | `--thinking-display` is forwarded only for `Adaptive` and `Enabled` configs (and only when `display != null`); `Disabled` never emits it. |
| `thinking(ThinkingConfigEnabled(20000, OMITTED))` | `--max-thinking-tokens 20000 --thinking-display omitted` | Both flags emitted together when `display` is set. |

**Stderr piping**: stderr is piped only when `options.stderrCallback() != null`. The legacy `--debug-to-stderr` extra-arg detection was removed in 0.1.13 (prep for the CLI flag's deprecation). To capture verbose CLI debug output, pass `extraArgs(Map.of("debug-file", "/path/to/log"))` and read that file instead.

**Stderr callback isolation** (0.1.16): each `stderrCallback.accept(line)` call is wrapped in a per-line `try/catch(Throwable)`. A throwing callback is caught, logged at `FINE`, and the read loop continues with the next line. Previously a throw would exit the loop and silently drop every subsequent stderr line for the rest of the session. Outer-loop failures (stream closed unexpectedly, I/O errors) are also logged at `FINE` instead of being silently swallowed.

**Orphan-child cleanup** (0.1.18): every spawned CLI process is registered in a static `ACTIVE_CHILDREN` set, and a JVM shutdown hook best-effort terminates any that are still alive if the process exits before `close()` runs. `close()` escalates termination (grace period → `destroy()` / SIGTERM → `destroyForcibly()` / SIGKILL) and then removes the process from `ACTIVE_CHILDREN` **only after confirming it is no longer alive** (`!process.isAlive()`). A child that somehow survives the escalation (a raced kill, or a `waitFor` that timed out) therefore stays tracked, so the shutdown-hook reaper still gets a chance at it instead of leaking as an orphaned `claude` process.

**`resume` / `sessionId` argv flag-injection hardening** (0.1.19): `buildCommand()` emits `resume` and `sessionId` as single `--flag=value` argv tokens (`--resume=<value>`, `--session-id=<value>`) rather than as two separate tokens (`--resume`, `<value>`). The CLI declares `--resume` with an *optional* value, so in the two-token form a dash-leading value is not bound to the flag and is instead parsed as an independent CLI flag. An application that routes untrusted input into `resume`/`sessionId` (e.g. a "resume my session" endpoint that reads a session ID from a request) could therefore inject arbitrary CLI flags — `resume("--version")` silently ran `claude --version` and yielded zero messages. The equals form always binds the value to the flag, and the CLI then rejects a dash-leading value as an invalid session ID. This is argv-level (one argument per option, **no shell is involved**), so it is flag injection, not command execution, and only affects apps forwarding untrusted input into these options. Matches the `--setting-sources=` style already used elsewhere in `buildCommand()`.

## Custom Transport

Implement `Transport` interface for custom communication:

```java
public class RemoteTransport implements Transport {
    private Socket socket;
    private BufferedWriter writer;
    private BufferedReader reader;
    
    @Override
    public void connect() throws CLIConnectionException {
        try {
            socket = new Socket("remote-host", 8080);
            writer = new BufferedWriter(
                new OutputStreamWriter(socket.getOutputStream())
            );
            reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream())
            );
        } catch (IOException e) {
            throw new CLIConnectionException("Connection failed", e);
        }
    }
    
    @Override
    public void write(String data) throws CLIConnectionException {
        try {
            writer.write(data);
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            throw new CLIConnectionException("Write failed", e);
        }
    }
    
    @Override
    public Iterator<Map<String, Object>> readMessages() {
        return new Iterator<>() {
            private final ObjectMapper mapper = new ObjectMapper();
            
            @Override
            public boolean hasNext() {
                return true;  // Or check connection
            }
            
            @Override
            public Map<String, Object> next() {
                try {
                    String line = reader.readLine();
                    if (line == null) {
                        throw new NoSuchElementException();
                    }
                    return mapper.readValue(line, Map.class);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        };
    }
    
    @Override
    public void endInput() {
        try {
            writer.close();
        } catch (IOException e) {
            // Log error
        }
    }
    
    @Override
    public boolean isReady() {
        return socket != null && socket.isConnected();
    }
    
    @Override
    public void close() {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException e) {
            // Log error
        }
    }
}
```

## Using Custom Transport

```java
// Create custom transport
Transport transport = new RemoteTransport();

// Use with ClaudeSDK.query
List<Message> messages = ClaudeSDK.query(
    prompt,
    options,
    transport  // Custom transport
);

// Or with streaming query
List<Message> messages = ClaudeSDK.query(
    messageStream,
    options,
    transport
);
```

## Best Practices

1. **Thread Safety**: Ensure write() is thread-safe
2. **Resource Management**: Implement close() properly
3. **Error Handling**: Throw CLIConnectionException for errors
4. **Blocking Reads**: readMessages() should block until data available
5. **JSON Format**: Messages must be JSON objects, one per line

## See Also
- [Architecture](./architecture.md#transport-layer) - Transport layer design
- Transport source code: `sdk/src/main/java/in/vidyalai/claude/sdk/transport/`
