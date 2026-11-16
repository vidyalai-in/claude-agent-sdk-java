# Exception Types API Reference

Error handling and exception hierarchy.

## Exception Hierarchy

```
ClaudeSDKException (RuntimeException)
├── CLIConnectionException
│   └── CLINotFoundException
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

Thrown as is (cause `InterruptedException`) by the message iterators of
`ClaudeSDKClient.receiveMessages()` and `receiveResponse()` when the thread
waiting in `hasNext()` is interrupted. The interrupt flag is restored. Since
0.2.6; before, an interrupt ended the stream as if it were complete.

## CLIConnectionException

Connection to Claude Code CLI failed.

```java
public class CLIConnectionException extends ClaudeSDKException {
    public CLIConnectionException(String message);
    public CLIConnectionException(String message, Throwable cause);
}
```

**Causes**:
- CLI not found (as the `CLINotFoundException` subclass)
- Process failed to start for any reason other than a missing executable, for example `EACCES` (not executable) or `ENOEXEC` (not a valid binary): `"Failed to start Claude Code: ..."`, with the spawn's `IOException` as the cause. Before 0.2.6 these were reported as `CLINotFoundException`
- The `cwd` working directory does not exist
- `user(...)` is set: the Java SDK cannot run the CLI as another OS user (see [user()](./feature-configuration-options.md#user))
- An `mcpServers` map or an `outputFormat` JSON schema that cannot be serialized (`"Failed to serialize ..."`); before 0.2.6 the flag was dropped with a warning
- Connection timeout
- Network issues (remote transport)
- `settings` that can't be loaded for merging with `sandbox` (`"Failed to load settings: ..."`): a missing or unreadable file, invalid JSON, or JSON that is not an object. See [settings()](./feature-configuration-options.md#settings)
- A `HookOnFailure.BLOCK` matcher on a CLI that does not report `hook_on_failure_supported`, or that reports `hooks_applied: false`. See [Hooks → Failure policy](./feature-hooks.md#failure-policy-onfailure)

## CLINotFoundException

Claude Code CLI executable not found.

```java
public class CLINotFoundException extends CLIConnectionException {
    public CLINotFoundException();                                         // default install hint
    public CLINotFoundException(String message);
    public CLINotFoundException(String message, String cliPath);
    public CLINotFoundException(String message, String cliPath, Throwable cause);

    @Nullable public String getCliPath();   // the path that was searched
}
```

A subclass of `CLIConnectionException`, so catch it first when you handle both.

Thrown when the transport is built, before anything is spawned, if no CLI is
found, and by `connect()` when the spawn fails because the executable does not
exist (`ENOENT`; the spawn's exception is the cause). Since 0.2.6 it means only
that: a CLI that exists but cannot be run raises a plain
`CLIConnectionException` instead, so a permissions problem is no longer
reported as "not installed". On Windows only a native `claude.exe` in an absolute `PATH` entry, or
`~/.local/bin/claude.exe`, is found; npm's `claude.cmd` shim is found only with
`allowUnsafeWindowsBatchCli(true)`, and the message says so.

**Solution**:
- Install Claude Code CLI (on Windows, the native installer)
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
    public CLIJSONDecodeException(String line, Throwable cause);

    public String getLine();   // the raw line that failed to parse
}
```

**Causes**:
- A complete stdout line that starts with `{` but does not parse, or has content after the JSON object (`{"a":1} junk`). Since 0.2.6 this ends the message stream with this exception (the collecting `ClaudeSDK.query(...)` wraps it in a `QueryFailedException`); before, one such line silently swallowed every message after it. Lines that do not start with `{` are skipped, not reported
- A stdout line longer than `maxBufferSize`
- CLI version mismatch

See [Transport Layer → Line framing and back-pressure](./feature-transport-layer.md#line-framing-and-back-pressure-026).

## MessageParseException

Failed to parse message into typed object.

```java
public class MessageParseException extends ClaudeSDKException {
    public MessageParseException(String message, Throwable cause);
}
```

**Causes**:
- A message without a `type` field
- Missing required fields
- A known message type with invalid structure (for example `assistant` content that is not a list)

An unknown message type is skipped, not reported. Since 0.2.6 an explicit
`null` or a wrong-typed *optional* field no longer raises this either; see
[Message Types](./feature-message-types.md).

## QueryFailedException

A collecting query ended in an error result, or its thread was interrupted.
Carries the messages that had already arrived.

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
- The calling thread was interrupted while waiting for messages (since 0.2.6).
  The cause is a `ClaudeSDKException` caused by the `InterruptedException`,
  `resultMessage()` is usually `null`, and the interrupt flag is restored. The
  CLI is still shut down gracefully before the exception is thrown.

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
