package examples;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.jspecify.annotations.Nullable;

import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.mcp.SdkMcpServer;
import in.vidyalai.claude.sdk.mcp.SdkMcpTool;
import in.vidyalai.claude.sdk.mcp.Tool;
import in.vidyalai.claude.sdk.mcp.ToolAnnotations;
import in.vidyalai.claude.sdk.mcp.ToolCallContext;
import in.vidyalai.claude.sdk.mcp.ToolResult;
import in.vidyalai.claude.sdk.types.mcp.McpSdkServerConfig;
import in.vidyalai.claude.sdk.types.mcp.McpStdioServerConfig;
import in.vidyalai.claude.sdk.types.message.AssistantMessage;
import in.vidyalai.claude.sdk.types.message.ContentBlock;
import in.vidyalai.claude.sdk.types.message.Message;
import in.vidyalai.claude.sdk.types.message.TextBlock;
import in.vidyalai.claude.sdk.types.message.ToolUseBlock;

/**
 * Example showing how to create and use SDK MCP servers with custom tools.
 */
public class McpServer {

    public static void main(String[] args) {
        // Example 1: Using @Tool annotation
        System.out.println("=== Annotated Tools ===");
        annotatedTools();

        // Example 2: Programmatic tool creation
        System.out.println("\n=== Programmatic Tools ===");
        programmaticTools();

        // Example 3: Calculator example
        System.out.println("\n=== Calculator ===");
        calculator();

        // Example 4: Mixed servers (SDK + external)
        System.out.println("\n=== Mixed Servers ===");
        mixedServers();

        // Example 5: Cancellation and richer result content
        System.out.println("\n=== Cancellable Tools and Result Content ===");
        cancellableTools();
    }

    /**
     * Tools defined using @Tool annotation.
     */
    public static class AnnotatedTools {

        public static class RIHints implements ToolAnnotations {

            @Override
            public @Nullable Boolean idempotentHint() {
                return Boolean.TRUE;
            }

            @Override
            public @Nullable Boolean readOnlyHint() {
                return Boolean.TRUE;
            }

        }

        @Tool(name = "greet", description = "Greet a user by first and last name", title = "Greet User", annotations = RIHints.class)
        public CompletableFuture<ToolResult> greet(String first, String last) {
            return CompletableFuture.completedFuture(
                    ToolResult.text("Hello, " + first + " " + last + "! Nice to meet you."));
        }

        @Tool(name = "get_time", description = "Get the current time", title = "Get Time", annotations = RIHints.class)
        public CompletableFuture<ToolResult> getTime(Map<String, Object> args) {
            String time = java.time.LocalTime.now().toString();
            return CompletableFuture.completedFuture(
                    ToolResult.text("The current time is: " + time));
        }

        @Tool(name = "reverse_string", description = "Reverse a string", title = "String Reverse", annotations = RIHints.class)
        public CompletableFuture<ToolResult> reverseString(String input) {
            String reversed = new StringBuilder(input).reverse().toString();
            return CompletableFuture.completedFuture(
                    ToolResult.text("Reversed: " + reversed));
        }

    }

    /**
     * Using annotated tools with ClaudeSDKClient.
     */
    static void annotatedTools() {
        // Create MCP server from annotated class
        McpSdkServerConfig serverConfig = ClaudeSDK.createSdkMcpServer(
                "my-tools",
                new AnnotatedTools());

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .mcpServers(Map.of("tools", serverConfig))
                .allowedTools(List.of(
                        "mcp__tools__greet",
                        "mcp__tools__get_time",
                        "mcp__tools__reverse_string"))
                .maxTurns(5)
                .build();

        try (var client = ClaudeSDK.createClient(options)) {
            client.connect();
            client.sendMessage(
                    "Please greet Alice Jon, tell me the current time, and reverse the word 'hello'");

            for (Message msg : client.receiveResponse()) {
                if (msg instanceof AssistantMessage assistant) {
                    for (ContentBlock block : assistant.content()) {
                        if (block instanceof TextBlock text) {
                            System.out.println("Claude: " + text.text());
                        } else if (block instanceof ToolUseBlock toolUse) {
                            System.out.println("Calculating: " + toolUse.name() + " : " + toolUse.input());
                        }
                    }
                }
            }
        }
    }

    /**
     * Creating tools programmatically using SdkMcpTool.
     */
    static void programmaticTools() {
        ToolAnnotations hints = ToolAnnotations.builder().readOnlyHint(true)
                .idempotentHint(true).build();
        // Create a tool programmatically
        SdkMcpTool<Map<String, Object>> uppercaseTool = SdkMcpTool.create(
                "uppercase",
                "Convert text to uppercase",
                "UpperCase Converter",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "text", Map.of(
                                        "type", "string",
                                        "description", "The text to convert")),
                        "required", List.of("text")),
                args -> {
                    String text = (String) args.get("text");
                    return CompletableFuture.completedFuture(
                            ToolResult.text(text.toUpperCase()));
                },
                hints);

        SdkMcpTool<Map<String, Object>> lowercaseTool = SdkMcpTool.create(
                "lowercase",
                "Convert text to lowercase",
                "LowerCase Converter",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "text", Map.of(
                                        "type", "string",
                                        "description", "The text to convert")),
                        "required", List.of("text")),
                args -> {
                    String text = (String) args.get("text");
                    return CompletableFuture.completedFuture(
                            ToolResult.text(text.toLowerCase()));
                },
                hints);

        // Create server with multiple tools
        SdkMcpServer server = SdkMcpServer.create(
                "text-tools",
                "1.0.0",
                List.of(uppercaseTool, lowercaseTool));

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .mcpServers(Map.of("text", server.toConfig()))
                .allowedTools(List.of("mcp__text__uppercase", "mcp__text__lowercase"))
                .maxTurns(5)
                .build();

        try (var client = ClaudeSDK.createClient(options)) {
            client.connect();
            client.sendMessage("Convert 'Hello World' to uppercase, then convert 'GOODBYE' to lowercase");

            for (Message msg : client.receiveResponse()) {
                if (msg instanceof AssistantMessage assistant) {
                    for (ContentBlock block : assistant.content()) {
                        if (block instanceof TextBlock text) {
                            System.out.println("Claude: " + text.text());
                        } else if (block instanceof ToolUseBlock toolUse) {
                            System.out.println("Calculating: " + toolUse.name() + " : " + toolUse.input());
                        }
                    }
                }
            }
        }
    }

    /**
     * Calculator tool example.
     */
    static void calculator() {
        ToolAnnotations hints = ToolAnnotations.builder().readOnlyHint(true)
                .idempotentHint(true).build();
        SdkMcpTool<Map<String, Object>> calcTool = SdkMcpTool.create(
                "calculate",
                "Perform mathematical calculations",
                "Calculator",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "a", Map.of("type", "number", "description", "First number"),
                                "b", Map.of("type", "number", "description", "Second number"),
                                "operation", Map.of(
                                        "type", "string",
                                        "enum", List.of("add", "subtract", "multiply", "divide"),
                                        "description", "The operation to perform")),
                        "required", List.of("a", "b", "operation")),
                args -> {
                    double a = ((Number) args.get("a")).doubleValue();
                    double b = ((Number) args.get("b")).doubleValue();
                    String op = (String) args.get("operation");

                    double result = switch (op) {
                        case "add" -> a + b;
                        case "subtract" -> a - b;
                        case "multiply" -> a * b;
                        case "divide" -> {
                            if (b == 0) {
                                throw new ArithmeticException("Cannot divide by zero");
                            }
                            yield a / b;
                        }
                        default -> throw new IllegalArgumentException("Unknown operation: " + op);
                    };

                    return CompletableFuture.completedFuture(
                            ToolResult.text(a + " " + op + " " + b + " = " + result));
                },
                hints);

        SdkMcpServer server = SdkMcpServer.create("calculator", "1.0.0", List.of(calcTool));

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .mcpServers(Map.of("calc", server.toConfig()))
                .allowedTools(List.of("mcp__calc__calculate"))
                .maxTurns(5)
                .build();

        try (var client = ClaudeSDK.createClient(options)) {
            client.connect();
            client.sendMessage("Calculate: 15 * 7, then 100 / 4, then 50 + 30");

            for (Message msg : client.receiveResponse()) {
                if (msg instanceof AssistantMessage assistant) {
                    for (ContentBlock block : assistant.content()) {
                        if (block instanceof TextBlock text) {
                            System.out.println("Claude: " + text.text());
                        } else if (block instanceof ToolUseBlock toolUse) {
                            System.out.println("Calculating: " + toolUse.name() + " : " + toolUse.input());
                        }
                    }
                }
            }
        }
    }

    /**
     * Mixing SDK servers with external servers.
     */
    @SuppressWarnings("unused")
    static void mixedServers() {
        // SDK MCP server (in-process)
        McpSdkServerConfig sdkServer = ClaudeSDK.createSdkMcpServer(
                "custom-tools",
                new AnnotatedTools());

        // External stdio server (hypothetical)
        McpStdioServerConfig externalServer = new McpStdioServerConfig(
                "node",
                List.of("path/to/server.js"),
                Map.of("NODE_ENV", "production"));

        // Note: In a real application, you would configure actual external servers
        // This example shows the structure for mixing server types

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .mcpServers(Map.of(
                        "sdk", sdkServer
                // "external", externalServer // Uncomment if external server exists
                ))
                .allowedTools(List.of(
                        "mcp__sdk__greet",
                        "mcp__sdk__get_time"))
                .maxTurns(5)
                .build();

        System.out.println("Configuration created with SDK server");
        System.out.println("SDK server tools: greet, get_time, reverse_string");
        System.out.println("(External server would be added similarly)");
    }

    /**
     * A tool that can be cancelled, and results richer than plain text.
     *
     * <p>
     * When a tool outruns the CLI's MCP timeout the CLI gives up on it and
     * sends {@code notifications/cancelled}. The call is answered without
     * waiting — but the handler keeps running unless it looks, because a
     * {@code CompletableFuture} cannot be interrupted from outside. A handler
     * that takes a {@link ToolCallContext} alongside its arguments can see
     * that and stop.
     *
     * <p>
     * To watch it happen, run with a short timeout:
     * {@code MCP_TOOL_TIMEOUT=5000 mvn exec:java -Dexec.mainClass="examples.McpServer" -pl examples}
     */
    static void cancellableTools() {
        // Two-argument handler: the second parameter is the cancellation
        // signal for this one call.
        SdkMcpTool<Map<String, Object>> countdown = SdkMcpTool.create(
                "slow_count",
                "Count slowly to a number, one second per step",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "to", Map.of("type", "integer", "description", "How high to count")),
                        "required", List.of("to")),
                (args, context) -> CompletableFuture.supplyAsync(() -> {
                    int to = ((Number) args.get("to")).intValue();
                    int reached = 0;
                    for (int i = 1; i <= to; i++) {
                        if (context.isCancelled()) {
                            // Nobody is waiting for this result any more, so
                            // stop rather than finish the work for nothing.
                            System.out.println("  [slow_count] cancelled at " + reached);
                            break;
                        }
                        reached = i;
                        sleep();
                    }
                    return ToolResult.text("Reached " + reached);
                }));

        // onCancel is for work that cannot poll — a blocking read, a call out
        // to another service — where something has to be closed to unblock it.
        SdkMcpTool<Map<String, Object>> report = SdkMcpTool.create(
                "build_report",
                "Produce a report with a link to the full document",
                Map.of("type", "object", "properties", Map.of()),
                (args, context) -> {
                    context.onCancel(() -> System.out.println("  [build_report] releasing resources"));
                    return CompletableFuture.completedFuture(ToolResult.builder()
                            // Structured data, without bringing your own mapper.
                            .addJson(Map.of("rows", 42, "status", "ok"))
                            // A resource link is flattened to the text the CLI
                            // renders: name, URI, description on their own lines.
                            .addResourceLink("Full report", "file:///tmp/report.md", "Every row")
                            .build());
                });

        SdkMcpServer server = SdkMcpServer.create("reporting", "1.0.0", List.of(countdown, report));

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .mcpServers(Map.of("reporting", server.toConfig()))
                .allowedTools(List.of("mcp__reporting__slow_count", "mcp__reporting__build_report"))
                .maxTurns(5)
                .build();

        try (var client = ClaudeSDK.createClient(options)) {
            client.connect();
            client.sendMessage("Call slow_count with to=8, then call build_report. "
                    + "Summarize both results in one sentence.");

            for (Message msg : client.receiveResponse()) {
                if (msg instanceof AssistantMessage assistant) {
                    for (ContentBlock block : assistant.content()) {
                        if (block instanceof TextBlock text) {
                            System.out.println("Claude: " + text.text());
                        } else if (block instanceof ToolUseBlock toolUse) {
                            System.out.println("Calling: " + toolUse.name() + " " + toolUse.input());
                        }
                    }
                }
            }
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

}
