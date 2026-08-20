# Exception Types API Reference

Error handling and exception hierarchy.

## Exception Hierarchy

```
ClaudeSDKException (RuntimeException)
├── CLIConnectionException
├── CLINotFoundException
├── ProcessException
│   └── ResultException
├── CLIJSONDecodeException
├── MessageParseException
└── QueryFailedException
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

**Actionable error after error-result exits**: when the CLI emits a `ResultMessage` with `isError=true` (for example `error_max_turns`, `error_during_execution`, or a `success` subtype with `apiErrorStatus` set) it then exits non-zero on purpose. The trailing `ProcessException` would carry only `"Command failed with exit code N"`, which is not actionable, so the reader replaces it with a `ResultException` (see below). The reset is per-turn — a fresh crash later in the run keeps its original `ProcessException` message.

## ResultException

The CLI reported a terminal error result and exited. A `ProcessException`
subclass, so existing `catch (ProcessException e)` handlers keep working.

```java
public class ResultException extends ProcessException {
    public ResultException(String message, @Nullable Map<String, Object> data,
                           @Nullable Integer exitCode);

    @Nullable public String subtype();          // "error_max_turns", "error_during_execution",
                                                // ... or "success" for a mid-turn API failure
    public List<String> errors();               // never null; empty for API failures
    @Nullable public String result();           // result text; the "API Error: ..." prose
    @Nullable public Integer apiErrorStatus();  // HTTP status of the failing API call
    @Nullable public String terminalReason();   // e.g. "api_error", "max_turns"
    @Nullable public String sessionId();
    public Map<String, Object> data();          // raw result payload, unmodifiable
}
```

The message is `"Claude Code returned an error result: <text>"` plus
`ProcessException`'s `" (exit code: N)"` suffix. `<text>` is the result's
`errors` array joined by `"; "`, falling back to the result text, then a
non-`success` `subtype`, then `"API error (HTTP <status>)"`. The original
`ProcessException` for the non-zero exit is the `getCause()`.

Branch on the payload rather than the text:

```java
} catch (ResultException e) {
    if ("api_error".equals(e.terminalReason())) {
        retry();
    } else if ("error_max_turns".equals(e.subtype())) {
        // ...
    }
}
```

**Where it surfaces:**

- The collecting `ClaudeSDK.query(...)` family wraps it in a
  `QueryFailedException` so the messages received before the failure are not
  lost; the `ResultException` is that exception's `getCause()`. This is the
  usual way to see it.
- Directly, from a failed control request — most importantly an `initialize`
  the CLI refuses during startup (a resume rejected by `resumeDropsTurn`).
  That happens before any message is collected, so it is not wrapped.
- **Not** from `ClaudeSDKClient.receiveResponse()`: that terminates at the
  `ResultMessage` (exactly as the Python SDK's `receive_response()` does) and
  so never observes the CLI's exit. Check `ResultMessage.isError()` there
  instead. `receiveMessages()` runs to end-of-stream and does raise, but on a
  live client stdin stays open, so an error result mid-session does not end
  the stream.

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

## QueryFailedException

A collecting query ended in an error result. Carries the messages that had
already arrived.

```java
public class QueryFailedException extends ClaudeSDKException {
    public QueryFailedException(String message, Throwable cause, List<Message> partialMessages);

    public List<Message> partialMessages();   // never null; unmodifiable
    public ResultMessage resultMessage();     // last ResultMessage received, or null
}
```

**Causes**:
- `error_max_turns` — `maxTurns` reached
- `error_max_budget_usd` — `maxBudgetUsd` reached
- `error_during_execution` — including a resume refused by `resumeDropsTurn`

**Why it exists**: the CLI reports these conditions by emitting a *complete*
turn — assistant messages plus a final `ResultMessage` carrying the subtype,
cost and usage — and only then exiting non-zero, on purpose, for shell
consumers. The streaming APIs (`ClaudeSDKClient.receiveMessages()` and
`receiveResponse()`) hand each of those messages to the consumer as it arrives
and raise only at the end, so nothing is lost there. A collecting call has to
either return a list or throw; throwing this carries both the error and the
messages, so `ClaudeSDK.query(...)` is as informative as the streaming path.

Thrown only by the collecting `ClaudeSDK.query(...)` family (including
`queryForText` and `queryForResult`, which delegate to it). Because it extends
`ClaudeSDKException`, existing `catch (ClaudeSDKException e)` blocks keep
working unchanged.

```java
try {
    List<Message> messages = ClaudeSDK.query("Summarize the README", options);
    // ... normal path
} catch (QueryFailedException e) {
    // The turn is usually complete — inspect what actually happened.
    ResultMessage result = e.resultMessage();
    if (result != null && "error_max_budget_usd".equals(result.subtype())) {
        System.out.printf("Stopped by the budget cap after $%.4f%n", result.totalCostUsd());
    }
    for (Message msg : e.partialMessages()) {
        if (msg instanceof AssistantMessage a) {
            System.out.println(a.getTextContent());
        }
    }
}
```

`partialMessages()` is empty when the run failed before producing anything (a
CLI that could not start, for instance). It is not serialized — a deserialized
instance reports an empty list rather than null, because `Message` is not
declared `Serializable`.

Catch this whenever you set `maxTurns` or `maxBudgetUsd`: reaching a cap you
configured yourself is an expected outcome, not a crash.

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
} catch (QueryFailedException e) {
    // Run stopped at a limit; the messages so far are still available.
    System.err.println("Run ended early: " + e.getMessage());
} catch (ClaudeSDKException e) {
    System.err.println("SDK error: " + e.getMessage());
}
```

Order matters: `QueryFailedException` must be caught before
`ClaudeSDKException`, since it is a subclass.

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
