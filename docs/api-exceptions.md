# Exception Types API Reference

Error handling and exception hierarchy.

## Exception Hierarchy

```
ClaudeSDKException (RuntimeException)
├── CLIConnectionException
├── CLINotFoundException
├── ProcessException
├── CLIJSONDecodeException
└── MessageParseException
```

## ClaudeSDKException

Base exception for all SDK errors.

```java
public class ClaudeSDKException extends RuntimeException {
    public ClaudeSDKException(String message);
    public ClaudeSDKException(String message, Throwable cause);
}
```

## CLIConnectionException

Connection to Claude Code CLI failed.

```java
public class CLIConnectionException extends ClaudeSDKException {
    public CLIConnectionException(String message);
    public CLIConnectionException(String message, Throwable cause);
}
```

**Causes**:
- CLI not found
- Process failed to start
- Connection timeout
- Network issues (remote transport)

## CLINotFoundException

Claude Code CLI executable not found.

```java
public class CLINotFoundException extends ClaudeSDKException {
    public CLINotFoundException(String message);
}
```

**Solution**:
- Install Claude Code CLI
- Specify custom path with `.cliPath()`

## ProcessException

CLI process failed or crashed.

```java
public class ProcessException extends ClaudeSDKException {
    public ProcessException(String message);
    public ProcessException(String message, Throwable cause);
}
```

**Causes**:
- CLI crashed
- Invalid arguments
- Resource exhaustion

**Actionable error after error-result exits**: when the CLI emits a `ResultMessage` with `isError=true` (for example `error_max_turns`, `error_during_execution`, or a `success` subtype with `apiErrorStatus` set) it then exits non-zero on purpose. The trailing `ProcessException` would carry only `"Command failed with exit code N"`, which is not actionable. Starting in v0.1.15, `QueryHandler.readMessages` replaces that synthetic `{"type":"error"}` payload with `"Claude Code returned an error result: <text>"`, where `<text>` is built from the result's `errors` array (joined by `"; "`) or from the result `subtype` when the array is empty. The reset is per-turn — a fresh crash later in the run keeps its original `ProcessException` message.

## CLIJSONDecodeException

Failed to parse JSON from CLI.

```java
public class CLIJSONDecodeException extends ClaudeSDKException {
    public CLIJSONDecodeException(String message, Throwable cause);
}
```

**Causes**:
- Malformed JSON
- Unexpected format
- CLI version mismatch

## MessageParseException

Failed to parse message into typed object.

```java
public class MessageParseException extends ClaudeSDKException {
    public MessageParseException(String message, Throwable cause);
}
```

**Causes**:
- Unknown message type
- Missing required fields
- Type conversion error

## Error Handling Examples

### Basic Try-Catch

```java
try {
    List<Message> messages = ClaudeSDK.query(prompt, options);
} catch (CLINotFoundException e) {
    System.err.println("Claude CLI not installed");
} catch (CLIConnectionException e) {
    System.err.println("Connection failed: " + e.getMessage());
} catch (ProcessException e) {
    System.err.println("CLI crashed: " + e.getMessage());
} catch (ClaudeSDKException e) {
    System.err.println("SDK error: " + e.getMessage());
}
```

### With Resource Management

```java
try (ClaudeSDKClient client = ClaudeSDK.createClient()) {
    client.connect();
    // Use client
} catch (CLIConnectionException e) {
    log.error("Failed to connect", e);
    throw new ApplicationException("Service unavailable", e);
} catch (ClaudeSDKException e) {
    log.error("SDK error", e);
    throw new ApplicationException("Internal error", e);
}
```

### Retrylogic

```java
int maxRetries = 3;
for (int i = 0; i < maxRetries; i++) {
    try {
        return ClaudeSDK.query(prompt, options);
    } catch (CLIConnectionException e) {
        if (i == maxRetries - 1) throw e;
        Thread.sleep(1000 * (i + 1));  // Exponential backoff
    }
}
```

## See Also
- [Error Handling Example](../examples/src/main/java/examples/ErrorHandling.java)
