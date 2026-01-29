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
    void testExceptionHierarchy() {
        // Verify the exception hierarchy
        assertThat(new ClaudeSDKException("test")).isInstanceOf(RuntimeException.class);
        assertThat(new CLIConnectionException("test")).isInstanceOf(ClaudeSDKException.class);
        assertThat(new CLINotFoundException()).isInstanceOf(CLIConnectionException.class);
        assertThat(new ProcessException("test")).isInstanceOf(ClaudeSDKException.class);
        assertThat(new CLIJSONDecodeException("test", new RuntimeException())).isInstanceOf(ClaudeSDKException.class);
        assertThat(new MessageParseException("test")).isInstanceOf(ClaudeSDKException.class);
    }

}
