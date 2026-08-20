package in.vidyalai.claude.sdk.exceptions;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Tests for exception types.
 * Equivalent to Python's test_errors.py
 */
class ExceptionsTest {

    @Test
    void testBaseException() {
        ClaudeSDKException exception = new ClaudeSDKException("Test error");
        assertThat(exception.getMessage()).isEqualTo("Test error");
        assertThat(exception).isInstanceOf(RuntimeException.class);
    }

    @Test
    void testBaseExceptionWithCause() {
        RuntimeException cause = new RuntimeException("Original error");
        ClaudeSDKException exception = new ClaudeSDKException("Wrapped error", cause);

        assertThat(exception.getMessage()).isEqualTo("Wrapped error");
        assertThat(exception.getCause()).isEqualTo(cause);
    }

    @Test
    void testCLINotFoundException() {
        CLINotFoundException exception = new CLINotFoundException();

        assertThat(exception).isInstanceOf(CLIConnectionException.class);
        assertThat(exception.getMessage()).contains("Claude Code not found");
        assertThat(exception.getMessage()).contains("https://code.claude.com/docs/en/setup");
        assertThat(exception.getMessage()).contains("ClaudeAgentOptions");
    }

    @Test
    void testCLINotFoundExceptionWithPath() {
        CLINotFoundException exception = new CLINotFoundException("CLI not found at path", "/custom/path/claude");

        assertThat(exception.getMessage()).contains("/custom/path/claude");
        assertThat(exception.getCliPath()).isEqualTo("/custom/path/claude");
    }

    @Test
    void testCLIConnectionException() {
        CLIConnectionException exception = new CLIConnectionException("Connection failed");

        assertThat(exception).isInstanceOf(ClaudeSDKException.class);
        assertThat(exception.getMessage()).isEqualTo("Connection failed");
    }

    @Test
    void testCLIConnectionExceptionWithCause() {
        Exception cause = new Exception("Network error");
        CLIConnectionException exception = new CLIConnectionException("Connection failed", cause);

        assertThat(exception.getCause()).isEqualTo(cause);
    }

    @Test
    void testProcessException() {
        ProcessException exception = new ProcessException("Process failed", 1, "Error output on stderr");

        assertThat(exception).isInstanceOf(ClaudeSDKException.class);
        assertThat(exception.getExitCode()).isEqualTo(1);
        assertThat(exception.getStderr()).isEqualTo("Error output on stderr");
        assertThat(exception.getMessage()).contains("exit code");
        assertThat(exception.getMessage()).contains("Error output on stderr");
    }

    @Test
    void testProcessExceptionWithHighExitCode() {
        ProcessException exception = new ProcessException("Killed", 137, "Killed");

        assertThat(exception.getExitCode()).isEqualTo(137);
        assertThat(exception.getMessage()).contains("137");
    }

    @Test
    void testProcessExceptionMessageOnly() {
        ProcessException exception = new ProcessException("Simple error");

        assertThat(exception.getMessage()).isEqualTo("Simple error");
        assertThat(exception.getExitCode()).isNull();
        assertThat(exception.getStderr()).isNull();
    }

    @Test
    void testJSONDecodeException() {
        RuntimeException cause = new RuntimeException("Parse error");
        CLIJSONDecodeException exception = new CLIJSONDecodeException("Invalid JSON at line 5", cause);

        assertThat(exception).isInstanceOf(ClaudeSDKException.class);
        assertThat(exception.getMessage()).contains("Invalid JSON at line 5");
        assertThat(exception.getCause()).isEqualTo(cause);
    }

    @Test
    void testMessageParseException() {
        MessageParseException exception = new MessageParseException("Missing required field");

        assertThat(exception).isInstanceOf(ClaudeSDKException.class);
        assertThat(exception.getMessage()).isEqualTo("Missing required field");
    }

    @Test
    void testMessageParseExceptionWithData() {
        java.util.Map<String, Object> data = java.util.Map.of("type", "unknown");
        MessageParseException exception = new MessageParseException("Unknown type", data);

        assertThat(exception.getData()).containsEntry("type", "unknown");
        assertThat(exception.getMessage()).contains("Unknown type");
    }

    @Test
    void testResultExceptionCarriesPayload() {
        java.util.Map<String, Object> data = new java.util.HashMap<>();
        data.put("type", "result");
        data.put("subtype", "success");
        data.put("is_error", true);
        data.put("errors", java.util.List.of());
        data.put("result", "API Error: Stream idle timeout - no chunks received");
        data.put("api_error_status", null);
        data.put("terminal_reason", "api_error");
        data.put("session_id", "s-1");

        ResultException exception = new ResultException(
                "Claude Code returned an error result: x", data, 1);

        assertThat(exception).isInstanceOf(ProcessException.class);
        assertThat(exception).isInstanceOf(ClaudeSDKException.class);
        assertThat(exception.getExitCode()).isEqualTo(1);
        assertThat(exception.data()).containsEntry("session_id", "s-1");
        assertThat(exception.subtype()).isEqualTo("success");
        assertThat(exception.errors()).isEmpty();
        assertThat(exception.result()).isEqualTo("API Error: Stream idle timeout - no chunks received");
        assertThat(exception.apiErrorStatus()).isNull();
        assertThat(exception.terminalReason()).isEqualTo("api_error");
        assertThat(exception.sessionId()).isEqualTo("s-1");
        assertThat(exception.getMessage()).contains("exit code: 1");
    }

    @Test
    void testResultExceptionToleratesMissingOrMalformedFields() {
        java.util.Map<String, Object> data = new java.util.HashMap<>();
        data.put("errors", 42);
        data.put("api_error_status", "500");

        ResultException exception = new ResultException("boom", data, null);

        assertThat(exception.subtype()).isNull();
        assertThat(exception.errors()).isEmpty();
        assertThat(exception.result()).isNull();
        assertThat(exception.apiErrorStatus()).isNull();
        assertThat(exception.terminalReason()).isNull();
        assertThat(exception.sessionId()).isNull();
        assertThat(exception.getExitCode()).isNull();
        assertThat(new ResultException("boom", null, null).data()).isEmpty();
    }

    @Test
    void testResultExceptionNormalizesErrorsLikeTheMessageText() {
        // A bare-string `errors` is kept and blank entries are dropped, so the
        // structured field agrees with the text the reader builds from it.
        assertThat(new ResultException("m", java.util.Map.of("errors", "boom"), null).errors())
                .containsExactly("boom");
        assertThat(new ResultException("m",
                java.util.Map.of("errors", java.util.Arrays.asList(" ", "x ", 3)), null).errors())
                        .containsExactly("x");
    }

    @Test
    void testResultExceptionDataIsUnmodifiable() {
        ResultException exception = new ResultException(
                "m", new java.util.HashMap<>(java.util.Map.of("subtype", "error_max_turns")), 1);

        assertThat(exception.data()).containsEntry("subtype", "error_max_turns");
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> exception.data().put("subtype", "other"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void testResultExceptionSurvivesSerialization() throws Exception {
        // Java's counterpart to Python's pickle round-trip: exceptions cross
        // process and cache boundaries via serialization, so the typed
        // accessors must survive it. `data` is deliberately transient (an
        // arbitrary decoded-JSON map is not guaranteed Serializable) and
        // reports empty afterwards, which is what the javadoc promises.
        java.util.Map<String, Object> data = new java.util.HashMap<>();
        data.put("subtype", "error_max_turns");
        data.put("errors", java.util.List.of("too many"));
        data.put("session_id", "s");
        data.put("terminal_reason", "max_turns");
        data.put("api_error_status", 529);
        ResultException original = new ResultException(
                "Claude Code returned an error result: too many", data, 1);

        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.io.ObjectOutputStream out = new java.io.ObjectOutputStream(bytes)) {
            out.writeObject(original);
        }
        ResultException clone;
        try (java.io.ObjectInputStream in = new java.io.ObjectInputStream(
                new java.io.ByteArrayInputStream(bytes.toByteArray()))) {
            clone = (ResultException) in.readObject();
        }

        assertThat(clone.getMessage()).isEqualTo(original.getMessage());
        assertThat(clone.getExitCode()).isEqualTo(1);
        assertThat(clone.subtype()).isEqualTo("error_max_turns");
        assertThat(clone.errors()).containsExactly("too many");
        assertThat(clone.sessionId()).isEqualTo("s");
        assertThat(clone.terminalReason()).isEqualTo("max_turns");
        assertThat(clone.apiErrorStatus()).isEqualTo(529);
        // The raw payload does not survive; it degrades to empty, never null.
        assertThat(clone.data()).isEmpty();
    }

    @Test
    void testExceptionHierarchy() {
        // Verify the exception hierarchy
        assertThat(new ClaudeSDKException("test")).isInstanceOf(RuntimeException.class);
        assertThat(new CLIConnectionException("test")).isInstanceOf(ClaudeSDKException.class);
        assertThat(new CLINotFoundException()).isInstanceOf(CLIConnectionException.class);
        assertThat(new ProcessException("test")).isInstanceOf(ClaudeSDKException.class);
        assertThat(new CLIJSONDecodeException("test", new RuntimeException())).isInstanceOf(ClaudeSDKException.class);
        assertThat(new MessageParseException("test")).isInstanceOf(ClaudeSDKException.class);
        assertThat(new ResultException("test", null, null)).isInstanceOf(ProcessException.class);
    }

}
