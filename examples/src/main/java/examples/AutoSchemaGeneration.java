package examples;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.mcp.Tool;
import in.vidyalai.claude.sdk.mcp.ToolResult;
import in.vidyalai.claude.sdk.types.mcp.McpSdkServerConfig;
import in.vidyalai.claude.sdk.types.message.AssistantMessage;
import in.vidyalai.claude.sdk.types.message.Message;

/**
 * Example demonstrating automatic JSON Schema generation from method
 * parameters.
 *
 * <p>
 * When using @Tool annotation without an explicit inputSchema, the SDK
 * automatically generates MCP-compliant JSON Schema from method parameters:
 *
 * <ul>
 * <li>String → "string"</li>
 * <li>Integer types (int, long, etc.) → "integer"</li>
 * <li>Floating point types (double, float, etc.) → "number"</li>
 * <li>Boolean → "boolean"</li>
 * <li>Map → "object"</li>
 * <li>List, arrays → "array"</li>
 * </ul>
 *
 * <p>
 * <b>Important:</b> For parameter name reflection to work, you must compile
 * with the {@code -parameters} flag. This is already configured in the
 * project's pom.xml.
 */
public class AutoSchemaGeneration {

    public static void main(String[] args) {
        System.out.println("=== Automatic Schema Generation Demo ===\n");

        // Example 1: Map parameter (standard pattern)
        System.out.println("1. Standard Map<String, Object> parameter:");
        standardMapParameter();

        // Example 2: Explicit schema (takes precedence)
        System.out.println("\n2. Explicit schema overrides auto-generation:");
        explicitSchemaExample();

        // Example 3: Explicit schema (takes precedence)
        System.out.println("\n3. Multiple params tool:");
        manyArgsToolExample();

        System.out.println("\n=== Schema Generation Complete ===");
    }

    /**
     * Tools demonstrating automatic schema generation.
     */
    public static class MyTools {

        /**
         * Standard pattern: Map parameter gets empty object schema.
         * Generated schema:
         *
         * <pre>
         * {
         *   "type": "object",
         *   "properties": {}
         * }
         * </pre>
         */
        @Tool(name = "get_time", description = "Get current time for user. Pass name as param")
        public CompletableFuture<ToolResult> standardGreet(Map<String, Object> args) {
            String name = (String) args.getOrDefault("name", "World");
            String time = java.time.LocalTime.now().toString();
            return CompletableFuture.completedFuture(
                    ToolResult.text("Hello, " + name + ". Current time is: " + time));
        }

        /**
         * Explicit schema: Takes precedence over auto-generation.
         * Useful when you need fine-grained control over validation rules.
         */
        @Tool(name = "explicit_greet", description = "Greet with explicit schema", inputSchema = """
                {
                    "type": "object",
                    "properties": {
                        "name": {
                            "type": "string",
                            "description": "The name to greet"
                        },
                        "formal": {
                            "type": "boolean",
                            "description": "Whether to use formal greeting",
                            "default": false
                        }
                    },
                    "required": ["name"]
                }
                """)
        public CompletableFuture<ToolResult> explicitGreet(Map<String, Object> args) {
            String name = (String) args.get("name");
            boolean formal = (boolean) args.getOrDefault("formal", false);
            String greeting = (formal ? "Good day, " + name + "." : "Hey " + name + "!");
            return CompletableFuture.completedFuture(ToolResult.text(greeting));
        }

        /**
         * Simple tool with no parameters.
         */
        @Tool(name = "get_status", description = "Get system status")
        public CompletableFuture<ToolResult> getStatus(Map<String, Object> args) {
            return CompletableFuture.completedFuture(
                    ToolResult.text("System status: OK"));
        }

        /**
         * Simple tool with multple parameters.
         */
        @Tool(name = "get_info", description = "Get name and age of person")
        public CompletableFuture<List<Object>> getInfo(String name, int age) {
            List<Object> result = new ArrayList<>();
            result.add(name);
            result.add(age);
            return CompletableFuture.completedFuture(result);
        }

    }

    /**
     * Demonstrates standard Map parameter pattern.
     */
    static void standardMapParameter() {
        MyTools tools = new MyTools();
        McpSdkServerConfig serverConfig = ClaudeSDK.createSdkMcpServer("auto-schema-demo", tools);

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .mcpServers(Map.of("demo", serverConfig))
                .allowedTools(List.of(
                        "mcp__demo__get_time",
                        "mcp__demo__get_status"))
                .maxTurns(3)
                .build();

        try (var client = ClaudeSDK.createClient(options)) {
            client.connect();
            client.sendMessage("Get time for 'Alice' and check the system status");

            for (Message msg : client.receiveResponse()) {
                if (msg instanceof AssistantMessage assistant) {
                    System.out.println("Claude: " + assistant.getTextContent());
                }
            }
        }
    }

    /**
     * Demonstrates explicit schema taking precedence.
     */
    static void explicitSchemaExample() {
        MyTools tools = new MyTools();
        McpSdkServerConfig serverConfig = ClaudeSDK.createSdkMcpServer("explicit-demo", tools);

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .mcpServers(Map.of("demo", serverConfig))
                .allowedTools(List.of("mcp__demo__explicit_greet"))
                .maxTurns(3)
                .build();

        try (var client = ClaudeSDK.createClient(options)) {
            client.connect();
            client.sendMessage("Greet 'Bob' formally");

            for (Message msg : client.receiveResponse()) {
                if (msg instanceof AssistantMessage assistant) {
                    System.out.println("Claude: " + assistant.getTextContent());
                }
            }
        }
    }

    /**
     * Demonstrates tool with different signature.
     */
    static void manyArgsToolExample() {
        MyTools tools = new MyTools();
        McpSdkServerConfig serverConfig = ClaudeSDK.createSdkMcpServer("info-demo", tools);

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .mcpServers(Map.of("demo", serverConfig))
                .allowedTools(List.of("mcp__demo__get_info"))
                .maxTurns(3)
                .build();

        try (var client = ClaudeSDK.createClient(options)) {
            client.connect();
            client.sendMessage("Get info of 'Alice' whose age is 25");

            for (Message msg : client.receiveResponse()) {
                if (msg instanceof AssistantMessage assistant) {
                    System.out.println("Claude: " + assistant.getTextContent());
                }
            }
        }
    }

}
