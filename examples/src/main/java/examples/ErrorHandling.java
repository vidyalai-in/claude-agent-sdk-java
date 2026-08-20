package examples;

import java.nio.file.Path;
import java.util.List;

import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.ClaudeSDKClient;
import in.vidyalai.claude.sdk.exceptions.CLIConnectionException;
import in.vidyalai.claude.sdk.exceptions.CLIJSONDecodeException;
import in.vidyalai.claude.sdk.exceptions.CLINotFoundException;
import in.vidyalai.claude.sdk.exceptions.ClaudeSDKException;
import in.vidyalai.claude.sdk.exceptions.MessageParseException;
import in.vidyalai.claude.sdk.exceptions.ProcessException;
import in.vidyalai.claude.sdk.exceptions.QueryFailedException;
import in.vidyalai.claude.sdk.exceptions.ResultException;
import in.vidyalai.claude.sdk.types.message.AssistantMessage;
import in.vidyalai.claude.sdk.types.message.ContentBlock;
import in.vidyalai.claude.sdk.types.message.Message;
import in.vidyalai.claude.sdk.types.message.ResultMessage;
import in.vidyalai.claude.sdk.types.message.ToolResultBlock;
import in.vidyalai.claude.sdk.types.message.UserMessage;
import in.vidyalai.claude.sdk.types.permission.PermissionMode;

/**
 * Example showing proper error handling with the Claude Agent SDK.
 */
public class ErrorHandling {

    public static void main(String[] args) {
        // Example 1: Handling specific exceptions
        System.out.println("=== Exception Handling ===");
        handleExceptions();

        // Example 2: Error result messages
        System.out.println("\n=== Error Result Messages ===");
        errorResultMessages();

        // Example 3: Tool errors
        System.out.println("\n=== Tool Errors ===");
        toolErrors();

        // Example 4: Connection errors
        System.out.println("\n=== Connection Errors ===");
        connectionErrors();

        // Example 5: Budget exceeded
        System.out.println("\n=== Budget Exceeded ===");
        budgetExceeded();

        // Example 6: API failure reported as a terminal error result
        System.out.println("\n=== API Error Result ===");
        apiErrorResult();
    }

    /**
     * Handling specific SDK exceptions.
     */
    static void handleExceptions() {
        try {
            // Attempt to use SDK with potentially invalid configuration
            ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                    .cliPath(Path.of("/nonexistent/path/to/claude"))
                    .build();

            List<Message> messages = ClaudeSDK.query("Hello", options);
            System.out.println("Success: " + messages.size() + " messages");

        } catch (CLINotFoundException e) {
            // CLI binary not found
            System.err.println("ERROR: Claude Code CLI not found");
            System.err.println("Path tried: " + e.getCliPath());
            System.err.println("\nTo install Claude Code:");
            System.err.println("  curl -fsSL https://claude.ai/install.sh | bash");

        } catch (CLIConnectionException e) {
            // General connection error
            System.err.println("ERROR: Failed to connect to CLI");
            System.err.println("Message: " + e.getMessage());
            if (e.getCause() != null) {
                System.err.println("Cause: " + e.getCause().getMessage());
            }

        } catch (ProcessException e) {
            // CLI process failed
            System.err.println("ERROR: CLI process failed");
            System.err.println("Exit code: " + e.getExitCode());
            System.err.println("Stderr: " + e.getStderr());

        } catch (CLIJSONDecodeException e) {
            // JSON parsing error
            System.err.println("ERROR: Failed to parse JSON response");
            System.err.println("Message: " + e.getMessage());

        } catch (MessageParseException e) {
            // Message structure error
            System.err.println("ERROR: Failed to parse message");
            System.err.println("Message: " + e.getMessage());
            System.err.println("Data: " + e.getData());

        } catch (ClaudeSDKException e) {
            // Catch-all for SDK errors
            System.err.println("ERROR: SDK error occurred");
            System.err.println("Type: " + e.getClass().getSimpleName());
            System.err.println("Message: " + e.getMessage());
        }
    }

    /**
     * Handling error result messages.
     */
    static void errorResultMessages() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .maxTurns(1)
                .build();

        try {
            List<Message> messages = ClaudeSDK.query(
                    "Do something that might fail",
                    options);

            for (Message msg : messages) {
                if (msg instanceof ResultMessage result) {
                    System.out.println("Result subtype: " + result.subtype());
                    System.out.println("Is error: " + result.isError());
                    System.out.println("Session ID: " + result.sessionId());
                    System.out.println("Turns: " + result.numTurns());
                    System.out.println("Cost: $" + result.totalCostUsd());

                    // Check for specific error subtypes
                    switch (result.subtype()) {
                        case "success" -> System.out.println("Completed successfully!");
                        case "error" -> System.out.println("General error occurred");
                        case "error_max_turns" -> System.out.println("Max turns exceeded");
                        case "error_max_budget_usd" -> System.out.println("Budget exceeded");
                        case "error_interrupted" -> System.out.println("Execution interrupted");
                        default -> System.out.println("Unknown result: " + result.subtype());
                    }
                }
            }
        } catch (QueryFailedException e) {
            // The CLI ends a failed run by emitting the error result and then
            // exiting non-zero. The collecting query wraps that in a
            // QueryFailedException so the messages it already gathered are not
            // lost; the typed cause carries the result's payload, so there is
            // no need to string-match the message.
            //
            // A streaming consumer (ClaudeSDKClient.receiveMessages() /
            // receiveResponse()) sees every message as it arrives and is
            // thrown the ResultException directly — catch that instead there.
            System.err.println("Run ended early: " + e.getMessage());
            if (e.getCause() instanceof ResultException cause) {
                System.err.println("Subtype: " + cause.subtype());
                System.err.println("Terminal reason: " + cause.terminalReason());
                System.err.println("Detail: "
                        + (cause.result() != null ? cause.result() : cause.errors()));
            }
        } catch (ClaudeSDKException e) {
            System.err.println("SDK error: " + e.getMessage());
        }
    }

    /**
     * Handling tool execution errors.
     */
    @SuppressWarnings("null")
    static void toolErrors() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .allowedTools(List.of("Read"))
                .permissionMode(PermissionMode.ACCEPT_EDITS)
                .maxTurns(3)
                .build();

        try {
            List<Message> messages = ClaudeSDK.query(
                    "Read the file /nonexistent/path/to/file.txt",
                    options);

            for (Message msg : messages) {
                if (msg instanceof UserMessage user) {
                    // Tool results come as user messages
                    for (ContentBlock block : user.contentAsBlocks()) {
                        if (block instanceof ToolResultBlock result) {
                            if (result.isError()) {
                                System.out.println("Tool error for " + result.toolUseId() + ":");
                                System.out.println("  " + String.valueOf(result.content()));
                            } else {
                                System.out.println("Tool succeeded: " +
                                        truncate(String.valueOf(result.content()), 100));
                            }
                        }
                    }
                } else if (msg instanceof AssistantMessage assistant) {
                    // Check for error field in assistant message
                    if (assistant.error() != null) {
                        System.out.println("Assistant error: " + assistant.error());
                    } else {
                        System.out.println("Claude: " + assistant.getTextContent());
                    }
                }
            }
        } catch (ClaudeSDKException e) {
            System.err.println("SDK error: " + e.getMessage());
        }
    }

    /**
     * Handling connection errors.
     */
    static void connectionErrors() {
        ClaudeSDKClient client = new ClaudeSDKClient();

        try {
            // Try to send message without connecting first
            client.sendMessage("Hello");
            System.out.println("This shouldn't print");

        } catch (CLIConnectionException e) {
            System.out.println("Expected error: " + e.getMessage());
            System.out.println("(Client not connected)");
        }

        // Proper usage with error handling
        try {
            client.connect();
            client.sendMessage("Hello");
            System.out.println("Connected successfully");

            for (Message msg : client.receiveResponse()) {
                if (msg instanceof AssistantMessage a) {
                    System.out.println("Claude: " + truncate(a.getTextContent(), 100));
                }
            }

        } catch (CLIConnectionException e) {
            System.err.println("Connection error: " + e.getMessage());
        } finally {
            try {
                client.close();
                System.out.println("Disconnected");
            } catch (Exception e) {
                System.err.println("Error during disconnect: " + e.getMessage());
            }
        }
    }

    /**
     * Handling budget exceeded scenarios.
     */
    static void budgetExceeded() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .maxBudgetUsd(0.0001) // Very small budget
                .maxTurns(5)
                .build();

        try {
            List<Message> messages = ClaudeSDK.query(
                    "Write a very long story about a dragon",
                    options);

            for (Message msg : messages) {
                if (msg instanceof ResultMessage result) {
                    if ("error_max_budget_usd".equals(result.subtype())) {
                        System.out.println("Budget exceeded!");
                        System.out.println("Cost so far: $" + result.totalCostUsd());
                        System.out.println("Budget was: $0.0001");
                    } else {
                        System.out.println("Result: " + result.subtype());
                        System.out.println("Cost: $" + result.totalCostUsd());
                    }
                } else if (msg instanceof AssistantMessage a) {
                    System.out.println("Partial response: " + truncate(a.getTextContent(), 100));
                }
            }
        } catch (ClaudeSDKException e) {
            System.err.println("SDK error: " + e.getMessage());
        }
    }

    /**
     * A run that fails on the API side, and the shape that failure arrives in.
     *
     * <p>An API-side failure is <em>not</em> reported with an
     * {@code error_*} subtype. The agent loop itself completed, so the CLI
     * emits {@code subtype: "success"} with {@code isError: true}, an empty
     * {@code errors} list, {@code terminalReason: "api_error"}, and the real
     * prose in {@code result} — then exits non-zero. Branch on
     * {@link ResultException#terminalReason()} rather than the subtype.
     *
     * <p>An invalid model id triggers this deterministically and cheaply:
     * the request is rejected before any inference is billed.
     */
    static void apiErrorResult() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .model("claude-nonexistent-model-for-sdk-example")
                .maxTurns(1)
                .build();

        try {
            ClaudeSDK.query("Reply with: pong", options);
            System.out.println("No error — the model id was accepted after all.");
        } catch (QueryFailedException e) {
            ResultMessage result = e.resultMessage();
            if (result != null) {
                System.out.println("Result subtype: " + result.subtype()
                        + " (isError=" + result.isError() + ")");
            }
            if (e.getCause() instanceof ResultException cause) {
                // The exception payload mirrors the ResultMessage above.
                System.out.println("Terminal reason: " + cause.terminalReason());
                System.out.println("API error status: " + cause.apiErrorStatus());
                System.out.println("Detail: " + cause.result());
                System.out.println("Exit code: " + cause.getExitCode());

                // terminalReason only says the failure came from the API, not
                // whether retrying can help: an invalid model id reports
                // api_error with a permanent 404. Branch on the status.
                Integer status = cause.apiErrorStatus();
                if (status != null && (status == 429 || status >= 500)) {
                    System.out.println("-> transient: worth retrying with backoff");
                } else {
                    System.out.println("-> permanent: retrying will not help");
                }
            }
        } catch (ClaudeSDKException e) {
            System.err.println("SDK error: " + e.getMessage());
        }
    }

    /**
     * Comprehensive error handling wrapper.
     */
    public static List<Message> safeQuery(String prompt, ClaudeAgentOptions options) {
        int maxRetries = 3;
        int retryCount = 0;

        while (retryCount < maxRetries) {
            try {
                return ClaudeSDK.query(prompt, options);

            } catch (CLINotFoundException e) {
                // Fatal - can't recover
                throw new RuntimeException("Claude Code CLI not installed", e);

            } catch (ProcessException e) {
                // Might be transient
                retryCount++;
                if (retryCount < maxRetries) {
                    System.out.println("Process error, retrying... (" + retryCount + "/" + maxRetries + ")");
                    try {
                        Thread.sleep(1000 * retryCount); // Exponential backoff
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted during retry", ie);
                    }
                } else {
                    throw new RuntimeException("Max retries exceeded", e);
                }

            } catch (CLIConnectionException e) {
                // Connection issue - retry
                retryCount++;
                if (retryCount < maxRetries) {
                    System.out.println("Connection error, retrying... (" + retryCount + "/" + maxRetries + ")");
                } else {
                    throw new RuntimeException("Max retries exceeded", e);
                }

            } catch (ClaudeSDKException e) {
                // Other SDK errors - don't retry
                throw new RuntimeException("SDK error", e);
            }
        }

        throw new RuntimeException("Unexpected state");
    }

    private static String truncate(String text, int maxLength) {
        if (text == null)
            return "";
        if (text.length() <= maxLength)
            return text;
        return text.substring(0, maxLength) + "...";
    }

}
