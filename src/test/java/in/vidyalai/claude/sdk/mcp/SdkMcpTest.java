package in.vidyalai.claude.sdk.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.Test;

import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.types.mcp.McpSdkServerConfig;

/**
 * Tests for SDK MCP server support.
 * Equivalent to Python's test_sdk_mcp_integration.py
 */
class SdkMcpTest {

    @Test
    void testToolCreation() {
        SdkMcpTool<Map<String, Object>> greet = SdkMcpTool.create(
                "greet",
                "Greet a user",
                Map.of(
                        "type", "object",
                        "properties", Map.of("name", Map.of("type", "string")),
                        "required", List.of("name")),
                args -> CompletableFuture.completedFuture(
                        ToolResult.text("Hello, " + args.get("name") + "!")));

        assertThat(greet.name()).isEqualTo("greet");
        assertThat(greet.description()).isEqualTo("Greet a user");
        assertThat(greet.inputSchema()).containsEntry("type", "object");
    }

    @Test
    void testToolInvocation() throws ExecutionException, InterruptedException {
        SdkMcpTool<Map<String, Object>> add = SdkMcpTool.create(
                "add",
                "Add two numbers",
                Map.of("type", "object"),
                args -> {
                    double a = ((Number) args.get("a")).doubleValue();
                    double b = ((Number) args.get("b")).doubleValue();
                    return CompletableFuture.completedFuture(
                            ToolResult.text("Result: " + (a + b)));
                });

        ToolResult result = add.invoke(Map.of("a", 5, "b", 3)).get();

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).hasSize(1);
        Map<String, Object> content = (Map<String, Object>) result.content().get(0);
        assertThat(content.get("text")).isEqualTo("Result: 8.0");
    }

    @Test
    void testToolResultText() {
        ToolResult result = ToolResult.text("Hello, World!");

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).hasSize(1);
        Map<String, Object> content = (Map<String, Object>) result.content().get(0);
        assertThat(content.get("type")).isEqualTo("text");
        assertThat(content.get("text")).isEqualTo("Hello, World!");
    }

    @Test
    void testToolResultError() {
        ToolResult result = ToolResult.error("Something went wrong");

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).hasSize(1);
        Map<String, Object> content = (Map<String, Object>) result.content().get(0);
        assertThat(content.get("text")).isEqualTo("Something went wrong");
    }

    @Test
    void testToolResultImage() {
        String imageData = Base64.getEncoder().encodeToString("fake-image-data".getBytes());
        ToolResult result = ToolResult.image(imageData, "image/png");

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).hasSize(1);
        Map<String, Object> content = (Map<String, Object>) result.content().get(0);
        assertThat(content.get("type")).isEqualTo("image");
        assertThat(content.get("data")).isEqualTo(imageData);
        assertThat(content.get("mimeType")).isEqualTo("image/png");
    }

    @Test
    void testToolResultBuilder() {
        ToolResult result = ToolResult.builder()
                .addText("Line 1")
                .addText("Line 2")
                .build();

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).hasSize(2);
    }

    @Test
    void testServerCreation() {
        SdkMcpTool<Map<String, Object>> tool1 = SdkMcpTool.create(
                "tool1", "First tool", Map.of("type", "object"),
                args -> CompletableFuture.completedFuture(ToolResult.text("ok")));
        SdkMcpTool<Map<String, Object>> tool2 = SdkMcpTool.create(
                "tool2", "Second tool", Map.of("type", "object"),
                args -> CompletableFuture.completedFuture(ToolResult.text("ok")));

        SdkMcpServer server = SdkMcpServer.create("test-server", "1.0.0", List.of(tool1, tool2));

        assertThat(server.name()).isEqualTo("test-server");
        assertThat(server.version()).isEqualTo("1.0.0");
    }

    @Test
    void testServerDefaultVersion() {
        SdkMcpServer server = SdkMcpServer.create("test-server", List.of());

        assertThat(server.version()).isEqualTo("1.0.0");
    }

    @Test
    void testServerToConfig() {
        SdkMcpServer server = SdkMcpServer.create("my-server", List.of());
        McpSdkServerConfig config = server.toConfig();

        assertThat(config.name()).isEqualTo("my-server");
        assertThat(config.instance()).isEqualTo(server);
        assertThat(config.type()).isEqualTo("sdk");
    }

    @Test
    void testServerHandleInitialize() throws ExecutionException, InterruptedException {
        SdkMcpServer server = SdkMcpServer.create("test", List.of());

        Map<String, Object> response = server.handleMessage(Map.of(
                "jsonrpc", "2.0",
                "id", 1,
                "method", "initialize",
                "params", Map.of())).get();

        assertThat(response).containsEntry("jsonrpc", "2.0");
        assertThat(response).containsEntry("id", 1);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) response.get("result");
        assertThat(result).containsKey("protocolVersion");
        assertThat(result).containsKey("serverInfo");
    }

    @Test
    void testServerHandleListTools() throws ExecutionException, InterruptedException {
        SdkMcpTool<Map<String, Object>> greet = SdkMcpTool.create(
                "greet", "Greet a user",
                Map.of("type", "object", "properties", Map.of()),
                args -> CompletableFuture.completedFuture(ToolResult.text("Hello!")));

        SdkMcpServer server = SdkMcpServer.create("test", List.of(greet));

        Map<String, Object> response = server.handleMessage(Map.of(
                "jsonrpc", "2.0",
                "id", 2,
                "method", "tools/list",
                "params", Map.of())).get();

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) response.get("result");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tools = (List<Map<String, Object>>) result.get("tools");

        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).get("name")).isEqualTo("greet");
        assertThat(tools.get(0).get("description")).isEqualTo("Greet a user");
    }

    @Test
    void testServerHandleToolCall() throws ExecutionException, InterruptedException {
        SdkMcpTool<Map<String, Object>> greet = SdkMcpTool.create(
                "greet", "Greet a user",
                Map.of("type", "object"),
                args -> CompletableFuture.completedFuture(
                        ToolResult.text("Hello, " + args.get("name") + "!")));

        SdkMcpServer server = SdkMcpServer.create("test", List.of(greet));

        Map<String, Object> response = server.handleMessage(Map.of(
                "jsonrpc", "2.0",
                "id", 3,
                "method", "tools/call",
                "params", Map.of(
                        "name", "greet",
                        "arguments", Map.of("name", "World"))))
                .get();

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) response.get("result");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");

        assertThat(content).hasSize(1);
        assertThat(content.get(0).get("text")).isEqualTo("Hello, World!");
    }

    @Test
    void testServerToolNotFound() throws ExecutionException, InterruptedException {
        SdkMcpServer server = SdkMcpServer.create("test", List.of());

        Map<String, Object> response = server.handleMessage(Map.of(
                "jsonrpc", "2.0",
                "id", 4,
                "method", "tools/call",
                "params", Map.of("name", "nonexistent", "arguments", Map.of()))).get();

        assertThat(response).containsKey("error");
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) response.get("error");
        assertThat(error.get("message").toString()).contains("Tool not found");
    }

    @Test
    void testServerErrorHandling() throws ExecutionException, InterruptedException {
        SdkMcpTool<Map<String, Object>> failingTool = SdkMcpTool.create(
                "fail", "A failing tool",
                Map.of("type", "object"),
                args -> {
                    throw new RuntimeException("Intentional failure");
                });

        SdkMcpServer server = SdkMcpServer.create("test", List.of(failingTool));

        Map<String, Object> response = server.handleMessage(Map.of(
                "jsonrpc", "2.0",
                "id", 5,
                "method", "tools/call",
                "params", Map.of("name", "fail", "arguments", Map.of()))).get();

        // The response should indicate an error per JSON-RPC spec
        assertThat(response).containsKey("error");
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) response.get("error");
        assertThat(error.get("message").toString()).contains("Intentional failure");
    }

    @Test
    void testCreateSdkMcpServerConvenience() {
        SdkMcpTool<Map<String, Object>> tool = SdkMcpTool.create(
                "test", "Test tool",
                Map.of("type", "object"),
                args -> CompletableFuture.completedFuture(ToolResult.text("ok")));

        McpSdkServerConfig config = ClaudeSDK.createSdkMcpServer("my-server", List.of(tool));

        assertThat(config.name()).isEqualTo("my-server");
        assertThat(config.type()).isEqualTo("sdk");
    }

    @Test
    void testCreateSdkMcpServerWithVersion() {
        McpSdkServerConfig config = ClaudeSDK.createSdkMcpServer("my-server", "2.0.0", List.of());

        assertThat(config.name()).isEqualTo("my-server");
    }

    // Test @Tool annotation
    static class AnnotatedTools {
        @Tool(name = "greet", description = "Greet a user", inputSchema = """
                {
                    "type": "object",
                    "properties": {
                        "name": {"type": "string"}
                    }
                }
                """)
        public ToolResult greet(Map<String, Object> args) {
            return ToolResult.text("Hello, " + args.get("name") + "!");
        }

        @Tool(name = "add", description = "Add two numbers")
        public CompletableFuture<ToolResult> add(Map<String, Object> args) {
            double a = ((Number) args.get("a")).doubleValue();
            double b = ((Number) args.get("b")).doubleValue();
            return CompletableFuture.completedFuture(ToolResult.text("Sum: " + (a + b)));
        }
    }

    @Test
    void testFromAnnotatedMethods() {
        AnnotatedTools tools = new AnnotatedTools();
        SdkMcpServer server = SdkMcpServer.fromAnnotatedMethods("annotated-server", tools);

        assertThat(server.name()).isEqualTo("annotated-server");
    }

    @Test
    void testAnnotatedToolInvocation() throws ExecutionException, InterruptedException {
        AnnotatedTools tools = new AnnotatedTools();
        SdkMcpServer server = SdkMcpServer.fromAnnotatedMethods("test", tools);

        Map<String, Object> response = server.handleMessage(Map.of(
                "jsonrpc", "2.0",
                "id", 1,
                "method", "tools/call",
                "params", Map.of(
                        "name", "greet",
                        "arguments", Map.of("name", "Test User"))))
                .get();

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) response.get("result");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");

        assertThat(content.get(0).get("text")).isEqualTo("Hello, Test User!");
    }

    @Test
    void testAnnotatedAsyncToolInvocation() throws ExecutionException, InterruptedException {
        AnnotatedTools tools = new AnnotatedTools();
        SdkMcpServer server = SdkMcpServer.fromAnnotatedMethods("test", tools);

        Map<String, Object> response = server.handleMessage(Map.of(
                "jsonrpc", "2.0",
                "id", 2,
                "method", "tools/call",
                "params", Map.of(
                        "name", "add",
                        "arguments", Map.of("a", 10, "b", 20))))
                .get();

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) response.get("result");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");

        assertThat(content.get(0).get("text")).isEqualTo("Sum: 30.0");
    }

    @Test
    void testCreateSdkMcpServerFromAnnotated() {
        AnnotatedTools tools = new AnnotatedTools();
        McpSdkServerConfig config = ClaudeSDK.createSdkMcpServer("from-annotated", tools);

        assertThat(config.name()).isEqualTo("from-annotated");
        assertThat(config.type()).isEqualTo("sdk");
    }

    // Test automatic schema generation from method parameters
    static class SchemaGenerationTools {
        @Tool(name = "with_explicit_schema", description = "Tool with explicit schema", inputSchema = """
                {
                    "type": "object",
                    "properties": {
                        "custom": {"type": "string"}
                    }
                }
                """)
        public ToolResult withExplicitSchema(Map<String, Object> args) {
            return ToolResult.text("explicit: " + args.get("custom"));
        }

        @Tool(name = "with_map_param", description = "Tool with Map parameter, should get empty schema")
        public ToolResult withMapParam(Map<String, Object> args) {
            return ToolResult.text("map: " + args);
        }
    }

    @Test
    void testSchemaGenerationWithExplicitSchema() throws ExecutionException, InterruptedException {
        SchemaGenerationTools tools = new SchemaGenerationTools();
        SdkMcpServer server = SdkMcpServer.fromAnnotatedMethods("schema-test", tools);

        Map<String, Object> response = server.handleMessage(Map.of(
                "jsonrpc", "2.0",
                "id", 1,
                "method", "tools/list",
                "params", Map.of())).get();

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) response.get("result");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> toolsList = (List<Map<String, Object>>) result.get("tools");

        // Find the tool with explicit schema
        Map<String, Object> explicitTool = toolsList.stream()
                .filter(t -> "with_explicit_schema".equals(t.get("name")))
                .findFirst()
                .orElseThrow();

        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) explicitTool.get("inputSchema");

        // Verify explicit schema is used
        assertThat(schema.get("type")).isEqualTo("object");
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertThat(properties).containsKey("custom");
    }

    @Test
    void testSchemaGenerationWithMapParam() throws ExecutionException, InterruptedException {
        SchemaGenerationTools tools = new SchemaGenerationTools();
        SdkMcpServer server = SdkMcpServer.fromAnnotatedMethods("schema-test", tools);

        Map<String, Object> response = server.handleMessage(Map.of(
                "jsonrpc", "2.0",
                "id", 1,
                "method", "tools/list",
                "params", Map.of())).get();

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) response.get("result");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> toolsList = (List<Map<String, Object>>) result.get("tools");

        // Find the tool with Map parameter
        Map<String, Object> mapTool = toolsList.stream()
                .filter(t -> "with_map_param".equals(t.get("name")))
                .findFirst()
                .orElseThrow();

        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) mapTool.get("inputSchema");

        // Verify MCP-compliant empty object schema
        assertThat(schema.get("type")).isEqualTo("object");
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertThat(properties).isEmpty();
    }

    // Test automatic schema generation with typed parameters
    static class TypedParameterTools {
        @Tool(name = "calculate", description = "Calculate with multiple typed parameters")
        public ToolResult calculate(int a, double b, String operation, boolean verbose) {
            double result = switch (operation) {
                case "add" -> a + b;
                case "subtract" -> a - b;
                case "multiply" -> a * b;
                case "divide" -> a / b;
                default -> 0;
            };
            String output = verbose ? "Result of " + a + " " + operation + " " + b + " = " + result
                    : String.valueOf(result);
            return ToolResult.text(output);
        }

        @Tool(name = "process_data", description = "Process data with various types")
        public ToolResult processData(String name, Integer age, Boolean active, Long timestamp) {
            return ToolResult.text("Processed: " + name + ", " + age + ", " + active + ", " + timestamp);
        }

        @Tool(name = "array_test", description = "Test array and list types")
        public ToolResult arrayTest(List<String> items, int[] numbers) {
            return ToolResult.text("Items: " + items.size() + ", Numbers: " + numbers.length);
        }

        @Tool(name = "object_test", description = "Test object types")
        public ToolResult objectTest(Map<String, Object> config, Object data) {
            return ToolResult.text("Config: " + config + ", Data: " + data);
        }

        @Tool(name = "number_types", description = "Test various number types")
        public ToolResult numberTypes(float floatVal, Double doubleVal, short shortVal, Long longVal, byte byteVal) {
            return ToolResult.text(
                    "Numbers: " + floatVal + ", " + doubleVal + ", " + shortVal + ", " + longVal + ", " + byteVal);
        }
    }

    @Test
    void testSchemaGenerationWithMultipleTypedParams() throws ExecutionException, InterruptedException {
        TypedParameterTools tools = new TypedParameterTools();
        SdkMcpServer server = SdkMcpServer.fromAnnotatedMethods("typed-test", tools);

        Map<String, Object> response = server.handleMessage(Map.of(
                "jsonrpc", "2.0",
                "id", 1,
                "method", "tools/list",
                "params", Map.of())).get();

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) response.get("result");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> toolsList = (List<Map<String, Object>>) result.get("tools");

        // Find the calculate tool
        Map<String, Object> calcTool = toolsList.stream()
                .filter(t -> "calculate".equals(t.get("name")))
                .findFirst()
                .orElseThrow();

        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) calcTool.get("inputSchema");

        // Verify schema structure
        assertThat(schema.get("type")).isEqualTo("object");

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertThat(properties).hasSize(4);

        // Verify type mappings
        @SuppressWarnings("unchecked")
        Map<String, Object> aProp = (Map<String, Object>) properties.get("a");
        assertThat(aProp.get("type")).isEqualTo("integer");

        @SuppressWarnings("unchecked")
        Map<String, Object> bProp = (Map<String, Object>) properties.get("b");
        assertThat(bProp.get("type")).isEqualTo("number");

        @SuppressWarnings("unchecked")
        Map<String, Object> opProp = (Map<String, Object>) properties.get("operation");
        assertThat(opProp.get("type")).isEqualTo("string");

        @SuppressWarnings("unchecked")
        Map<String, Object> verboseProp = (Map<String, Object>) properties.get("verbose");
        assertThat(verboseProp.get("type")).isEqualTo("boolean");

        // Verify all parameters are required
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) schema.get("required");
        assertThat(required).containsExactlyInAnyOrder("a", "b", "operation", "verbose");
    }

    @Test
    void testSchemaGenerationWithObjectWrapperTypes() throws ExecutionException, InterruptedException {
        TypedParameterTools tools = new TypedParameterTools();
        SdkMcpServer server = SdkMcpServer.fromAnnotatedMethods("typed-test", tools);

        Map<String, Object> response = server.handleMessage(Map.of(
                "jsonrpc", "2.0",
                "id", 1,
                "method", "tools/list",
                "params", Map.of())).get();

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) response.get("result");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> toolsList = (List<Map<String, Object>>) result.get("tools");

        // Find the process_data tool
        Map<String, Object> tool = toolsList.stream()
                .filter(t -> "process_data".equals(t.get("name")))
                .findFirst()
                .orElseThrow();

        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) tool.get("inputSchema");
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");

        // Verify wrapper types are correctly mapped
        @SuppressWarnings("unchecked")
        Map<String, Object> nameProp = (Map<String, Object>) properties.get("name");
        assertThat(nameProp.get("type")).isEqualTo("string");

        @SuppressWarnings("unchecked")
        Map<String, Object> ageProp = (Map<String, Object>) properties.get("age");
        assertThat(ageProp.get("type")).isEqualTo("integer");

        @SuppressWarnings("unchecked")
        Map<String, Object> activeProp = (Map<String, Object>) properties.get("active");
        assertThat(activeProp.get("type")).isEqualTo("boolean");

        @SuppressWarnings("unchecked")
        Map<String, Object> timestampProp = (Map<String, Object>) properties.get("timestamp");
        assertThat(timestampProp.get("type")).isEqualTo("integer");
    }

    @Test
    void testSchemaGenerationWithArrayTypes() throws ExecutionException, InterruptedException {
        TypedParameterTools tools = new TypedParameterTools();
        SdkMcpServer server = SdkMcpServer.fromAnnotatedMethods("typed-test", tools);

        Map<String, Object> response = server.handleMessage(Map.of(
                "jsonrpc", "2.0",
                "id", 1,
                "method", "tools/list",
                "params", Map.of())).get();

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) response.get("result");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> toolsList = (List<Map<String, Object>>) result.get("tools");

        // Find the array_test tool
        Map<String, Object> tool = toolsList.stream()
                .filter(t -> "array_test".equals(t.get("name")))
                .findFirst()
                .orElseThrow();

        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) tool.get("inputSchema");
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");

        // Verify List maps to array
        @SuppressWarnings("unchecked")
        Map<String, Object> itemsProp = (Map<String, Object>) properties.get("items");
        assertThat(itemsProp.get("type")).isEqualTo("array");

        // Verify Java array maps to array
        @SuppressWarnings("unchecked")
        Map<String, Object> numbersProp = (Map<String, Object>) properties.get("numbers");
        assertThat(numbersProp.get("type")).isEqualTo("array");
    }

    @Test
    void testSchemaGenerationWithObjectTypes() throws ExecutionException, InterruptedException {
        TypedParameterTools tools = new TypedParameterTools();
        SdkMcpServer server = SdkMcpServer.fromAnnotatedMethods("typed-test", tools);

        Map<String, Object> response = server.handleMessage(Map.of(
                "jsonrpc", "2.0",
                "id", 1,
                "method", "tools/list",
                "params", Map.of())).get();

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) response.get("result");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> toolsList = (List<Map<String, Object>>) result.get("tools");

        // Find the object_test tool
        Map<String, Object> tool = toolsList.stream()
                .filter(t -> "object_test".equals(t.get("name")))
                .findFirst()
                .orElseThrow();

        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) tool.get("inputSchema");
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");

        // Verify Map maps to object
        @SuppressWarnings("unchecked")
        Map<String, Object> configProp = (Map<String, Object>) properties.get("config");
        assertThat(configProp.get("type")).isEqualTo("object");

        // Verify Object maps to object
        @SuppressWarnings("unchecked")
        Map<String, Object> dataProp = (Map<String, Object>) properties.get("data");
        assertThat(dataProp.get("type")).isEqualTo("object");
    }

    @Test
    void testSchemaGenerationWithVariousNumberTypes() throws ExecutionException, InterruptedException {
        TypedParameterTools tools = new TypedParameterTools();
        SdkMcpServer server = SdkMcpServer.fromAnnotatedMethods("typed-test", tools);

        Map<String, Object> response = server.handleMessage(Map.of(
                "jsonrpc", "2.0",
                "id", 1,
                "method", "tools/list",
                "params", Map.of())).get();

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) response.get("result");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> toolsList = (List<Map<String, Object>>) result.get("tools");

        // Find the number_types tool
        Map<String, Object> tool = toolsList.stream()
                .filter(t -> "number_types".equals(t.get("name")))
                .findFirst()
                .orElseThrow();

        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) tool.get("inputSchema");
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");

        // float -> number
        @SuppressWarnings("unchecked")
        Map<String, Object> floatProp = (Map<String, Object>) properties.get("floatVal");
        assertThat(floatProp.get("type")).isEqualTo("number");

        // Double -> number
        @SuppressWarnings("unchecked")
        Map<String, Object> doubleProp = (Map<String, Object>) properties.get("doubleVal");
        assertThat(doubleProp.get("type")).isEqualTo("number");

        // short -> integer
        @SuppressWarnings("unchecked")
        Map<String, Object> shortProp = (Map<String, Object>) properties.get("shortVal");
        assertThat(shortProp.get("type")).isEqualTo("integer");

        // Long -> integer
        @SuppressWarnings("unchecked")
        Map<String, Object> longProp = (Map<String, Object>) properties.get("longVal");
        assertThat(longProp.get("type")).isEqualTo("integer");

        // byte -> integer
        @SuppressWarnings("unchecked")
        Map<String, Object> byteProp = (Map<String, Object>) properties.get("byteVal");
        assertThat(byteProp.get("type")).isEqualTo("integer");
    }

}
