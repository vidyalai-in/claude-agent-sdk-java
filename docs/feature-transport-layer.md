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
