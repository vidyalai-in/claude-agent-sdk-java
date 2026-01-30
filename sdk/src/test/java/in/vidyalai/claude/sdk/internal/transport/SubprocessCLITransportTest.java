package in.vidyalai.claude.sdk.internal.transport;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.types.config.AIModel;
import in.vidyalai.claude.sdk.types.config.AgentDefinition;
import in.vidyalai.claude.sdk.types.config.SandboxIgnoreViolations;
import in.vidyalai.claude.sdk.types.config.SandboxNetworkConfig;
import in.vidyalai.claude.sdk.types.config.SandboxSettings;
import in.vidyalai.claude.sdk.types.config.SdkBeta;
import in.vidyalai.claude.sdk.types.config.SettingSource;
import in.vidyalai.claude.sdk.types.config.SystemPromptPreset;
import in.vidyalai.claude.sdk.types.config.ToolsPreset;
import in.vidyalai.claude.sdk.types.mcp.McpHttpServerConfig;
import in.vidyalai.claude.sdk.types.mcp.McpServerConfig;
import in.vidyalai.claude.sdk.types.mcp.McpSseServerConfig;
import in.vidyalai.claude.sdk.types.mcp.McpStdioServerConfig;
import in.vidyalai.claude.sdk.types.permission.PermissionMode;

/**
 * Tests for SubprocessCLITransport.
 * Note: buildCommand is private, so we test via options validation instead.
 */
class SubprocessCLITransportTest {

    @Test
    void testOptionsWithSystemPromptString() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .systemPrompt("You are a helpful assistant")
                .build();

        assertThat(options.systemPrompt()).isEqualTo("You are a helpful assistant");
    }

    @Test
    void testOptionsWithSystemPromptPreset() {
        SystemPromptPreset preset = SystemPromptPreset.claudeCode();
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .systemPrompt(preset)
                .build();

        assertThat(options.systemPrompt()).isEqualTo(preset);
    }

    @Test
    void testOptionsWithTools() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .tools(List.of("Bash", "Read", "Write"))
                .build();

        assertThat(options.tools()).isEqualTo(List.of("Bash", "Read", "Write"));
    }

    @Test
    void testOptionsWithToolsPreset() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .tools(ToolsPreset.claudeCode())
                .build();

        assertThat(options.tools()).isInstanceOf(ToolsPreset.class);
    }

    @Test
    void testOptionsWithAllowedTools() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .allowedTools(List.of("Bash", "Read"))
                .build();

        assertThat(options.allowedTools()).containsExactly("Bash", "Read");
    }

    @Test
    void testOptionsWithDisallowedTools() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .disallowedTools(List.of("Write", "Edit"))
                .build();

        assertThat(options.disallowedTools()).containsExactly("Write", "Edit");
    }

    @Test
    void testOptionsWithModel() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .model("claude-sonnet-4-5")
                .build();

        assertThat(options.model()).isEqualTo("claude-sonnet-4-5");
    }

    @Test
    void testOptionsWithFallbackModel() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .fallbackModel("claude-haiku-3-5")
                .build();

        assertThat(options.fallbackModel()).isEqualTo("claude-haiku-3-5");
    }

    @Test
    void testOptionsWithPermissionMode() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .permissionMode(PermissionMode.BYPASS_PERMISSIONS)
                .build();

        assertThat(options.permissionMode()).isEqualTo(PermissionMode.BYPASS_PERMISSIONS);
    }

    @Test
    void testOptionsWithAllPermissionModes() {
        for (PermissionMode mode : PermissionMode.values()) {
            ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                    .permissionMode(mode)
                    .build();

            assertThat(options.permissionMode()).isEqualTo(mode);
        }
    }

    @Test
    void testOptionsWithMaxTurns() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .maxTurns(5)
                .build();

        assertThat(options.maxTurns()).isEqualTo(5);
    }

    @Test
    void testOptionsWithMaxBudgetUsd() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .maxBudgetUsd(1.5)
                .build();

        assertThat(options.maxBudgetUsd()).isEqualTo(1.5);
    }

    @Test
    void testOptionsWithMaxThinkingTokens() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .maxThinkingTokens(8000)
                .build();

        assertThat(options.maxThinkingTokens()).isEqualTo(8000);
    }

    @Test
    void testOptionsWithContinueConversation() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .continueConversation(true)
                .build();

        assertThat(options.continueConversation()).isTrue();
    }

    @Test
    void testOptionsWithResume() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .resume("session-123")
                .build();

        assertThat(options.resume()).isEqualTo("session-123");
    }

    @Test
    void testOptionsWithForkSession() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .forkSession(true)
                .build();

        assertThat(options.forkSession()).isTrue();
    }

    @Test
    void testOptionsWithAddDirs() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .addDirs(List.of(Path.of("/path/to/dir1"), Path.of("/path/to/dir2")))
                .build();

        assertThat(options.addDirs()).containsExactly(Path.of("/path/to/dir1"), Path.of("/path/to/dir2"));
    }

    @SuppressWarnings("null")
    @Test
    void testOptionsWithMcpServersMap() {
        Map<String, McpServerConfig> servers = Map.of(
                "my-server", new McpStdioServerConfig("node", List.of("server.js")));
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .mcpServers(servers)
                .build();

        assertThat(options.mcpServers()).isEqualTo(servers);
    }

    @Test
    void testOptionsWithMcpServersPath() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .mcpServers(Path.of("/path/to/mcp-config.json"))
                .build();

        assertThat(options.mcpServers()).isEqualTo(Path.of("/path/to/mcp-config.json"));
    }

    @Test
    void testOptionsWithSettings() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .settings("{\"key\": \"value\"}")
                .build();

        assertThat(options.settings()).isEqualTo("{\"key\": \"value\"}");
    }

    @Test
    void testOptionsWithSandbox() {
        SandboxSettings sandbox = new SandboxSettings(true);
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .sandbox(sandbox)
                .build();

        assertThat(options.sandbox()).isEqualTo(sandbox);
    }

    @Test
    void testOptionsWithOutputFormat() {
        Map<String, Object> outputFormat = Map.of(
                "type", "json_schema",
                "schema", Map.of("type", "object"));
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .outputFormat(outputFormat)
                .build();

        assertThat(options.outputFormat()).isEqualTo(outputFormat);
    }

    @Test
    void testOptionsWithEnableFileCheckpointing() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .enableFileCheckpointing(true)
                .build();

        assertThat(options.enableFileCheckpointing()).isTrue();
    }

    @Test
    void testOptionsWithIncludePartialMessages() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .includePartialMessages(true)
                .build();

        assertThat(options.includePartialMessages()).isTrue();
    }

    @Test
    void testOptionsWithExtraArgs() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .extraArgs(Map.of("custom-flag", "custom-value"))
                .build();

        assertThat(options.extraArgs()).containsEntry("custom-flag", "custom-value");
    }

    @Test
    void testOptionsWithUser() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .user("testuser")
                .build();

        assertThat(options.user()).isEqualTo("testuser");
    }

    @Test
    void testOptionsWithPermissionPromptToolName() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .permissionPromptToolName("stdio")
                .build();

        assertThat(options.permissionPromptToolName()).isEqualTo("stdio");
    }

    @Test
    void testOptionsWithBetas() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .betas(List.of(SdkBeta.CONTEXT_1M))
                .build();

        assertThat(options.betas()).containsExactly(SdkBeta.CONTEXT_1M);
    }

    @Test
    void testOptionsWithSettingSources() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .settingSources(List.of(SettingSource.USER, SettingSource.PROJECT))
                .build();

        assertThat(options.settingSources()).containsExactly(SettingSource.USER, SettingSource.PROJECT);
    }

    @Test
    void testOptionsWithAgents() {
        AgentDefinition agent = new AgentDefinition(
                "Test agent",
                "You are a test agent",
                List.of("Bash"),
                AIModel.SONNET);
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .agents(Map.of("test-agent", agent))
                .build();

        assertThat(options.agents()).containsKey("test-agent");
    }

    @Test
    void testOptionsCombined() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .model("claude-sonnet-4-5")
                .permissionMode(PermissionMode.BYPASS_PERMISSIONS)
                .maxTurns(10)
                .systemPrompt("Be helpful")
                .build();

        assertThat(options.model()).isEqualTo("claude-sonnet-4-5");
        assertThat(options.permissionMode()).isEqualTo(PermissionMode.BYPASS_PERMISSIONS);
        assertThat(options.maxTurns()).isEqualTo(10);
        assertThat(options.systemPrompt()).isEqualTo("Be helpful");
    }

    // ==================== CLI Path Tests ====================

    @Test
    void testOptionsWithCliPath() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .cliPath(Path.of("/usr/local/bin/claude"))
                .build();

        assertThat(options.cliPath()).isEqualTo(Path.of("/usr/local/bin/claude"));
    }

    @Test
    void testOptionsWithCwd() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .cwd(Path.of("/custom/working/dir"))
                .build();

        assertThat(options.cwd()).isEqualTo(Path.of("/custom/working/dir"));
    }

    // ==================== Session Continuation Tests ====================

    @Test
    void testSessionContinuationOptions() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .continueConversation(true)
                .resume("session-123")
                .forkSession(true)
                .build();

        assertThat(options.continueConversation()).isTrue();
        assertThat(options.resume()).isEqualTo("session-123");
        assertThat(options.forkSession()).isTrue();
    }

    // ==================== System Prompt Preset Tests ====================

    @SuppressWarnings("null")
    @Test
    void testOptionsWithSystemPromptPresetAndAppend() {
        SystemPromptPreset preset = SystemPromptPreset.claudeCode("Be concise.");
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .systemPrompt(preset)
                .build();

        assertThat(options.systemPrompt()).isInstanceOf(SystemPromptPreset.class);
        SystemPromptPreset actual = (SystemPromptPreset) options.systemPrompt();
        assertThat(actual.append()).isEqualTo("Be concise.");
    }

    // ==================== Tools Configuration Tests ====================

    @Test
    void testOptionsWithToolsEmptyArray() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .tools(List.of())
                .build();

        assertThat(options.tools()).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<String> tools = (List<String>) options.tools();
        assertThat(tools).isEmpty();
    }

    @Test
    void testOptionsWithMultipleToolConfigs() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .tools(List.of("Bash", "Read", "Write"))
                .allowedTools(List.of("Edit"))
                .disallowedTools(List.of("Execute"))
                .build();

        assertThat(options.tools()).isEqualTo(List.of("Bash", "Read", "Write"));
        assertThat(options.allowedTools()).containsExactly("Edit");
        assertThat(options.disallowedTools()).containsExactly("Execute");
    }

    // ==================== Sandbox Configuration Tests ====================

    @SuppressWarnings("null")
    @Test
    void testOptionsWithSandboxMinimal() {
        SandboxSettings sandbox = new SandboxSettings(true);
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .sandbox(sandbox)
                .build();

        assertThat(options.sandbox()).isNotNull();
        assertThat(options.sandbox().enabled()).isTrue();
    }

    @SuppressWarnings("null")
    @Test
    void testOptionsWithSandboxAndSettings() {
        SandboxSettings sandbox = new SandboxSettings(
                true,
                true,
                List.of("git", "docker"),
                null,
                null,
                null,
                false);
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .settings("{\"permissions\": {\"allow\": [\"Bash(ls:*)\"]}}")
                .sandbox(sandbox)
                .build();

        assertThat(options.settings()).contains("permissions");
        assertThat(options.sandbox()).isNotNull();
        assertThat(options.sandbox().enabled()).isTrue();
        assertThat(options.sandbox().autoAllowBashIfSandboxed()).isTrue();
        assertThat(options.sandbox().excludedCommands()).containsExactly("git", "docker");
    }

    @SuppressWarnings("null")
    @Test
    void testSandboxNetworkConfig() {
        SandboxNetworkConfig network = new SandboxNetworkConfig(
                List.of("/tmp/ssh-agent.sock"),
                false,
                true,
                8080,
                8081);
        SandboxSettings sandbox = new SandboxSettings(
                true,
                null,
                null,
                null,
                network,
                null,
                null);

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .sandbox(sandbox)
                .build();

        assertThat(options.sandbox().network()).isNotNull();
        assertThat(options.sandbox().network().allowUnixSockets()).containsExactly("/tmp/ssh-agent.sock");
        assertThat(options.sandbox().network().allowAllUnixSockets()).isFalse();
        assertThat(options.sandbox().network().allowLocalBinding()).isTrue();
        assertThat(options.sandbox().network().httpProxyPort()).isEqualTo(8080);
        assertThat(options.sandbox().network().socksProxyPort()).isEqualTo(8081);
    }

    @SuppressWarnings("null")
    @Test
    void testSandboxIgnoreViolationsConfig() {
        SandboxIgnoreViolations ignore = new SandboxIgnoreViolations(
                List.of("/tmp"),
                List.of("localhost", "127.0.0.1"));
        SandboxSettings sandbox = new SandboxSettings(
                true,
                null,
                null,
                null,
                null,
                ignore,
                null);

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .sandbox(sandbox)
                .build();

        assertThat(options.sandbox().ignoreViolations()).isNotNull();
        assertThat(options.sandbox().ignoreViolations().file()).containsExactly("/tmp");
        assertThat(options.sandbox().ignoreViolations().network()).containsExactly("localhost", "127.0.0.1");
    }

    // ==================== MCP Server Configuration Tests ====================

    @SuppressWarnings("null")
    @Test
    void testOptionsWithMcpServersStdio() {
        McpStdioServerConfig server = new McpStdioServerConfig(
                "node",
                List.of("server.js", "--port", "3000"),
                Map.of("NODE_ENV", "production"));
        Map<String, McpServerConfig> servers = Map.of("test-server", server);

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .mcpServers(servers)
                .build();

        assertThat(options.mcpServers()).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, McpServerConfig> resultServers = (Map<String, McpServerConfig>) options.mcpServers();
        assertThat(resultServers).containsKey("test-server");
        McpStdioServerConfig resultServer = (McpStdioServerConfig) resultServers.get("test-server");
        assertThat(resultServer.command()).isEqualTo("node");
        assertThat(resultServer.args()).containsExactly("server.js", "--port", "3000");
        assertThat(resultServer.env()).containsEntry("NODE_ENV", "production");
    }

    @SuppressWarnings("null")
    @Test
    void testOptionsWithMcpServersSSE() {
        McpSseServerConfig server = new McpSseServerConfig(
                "https://api.example.com/sse",
                Map.of("Authorization", "Bearer token123"));
        Map<String, McpServerConfig> servers = Map.of("sse-server", server);

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .mcpServers(servers)
                .build();

        @SuppressWarnings("unchecked")
        Map<String, McpServerConfig> resultServers = (Map<String, McpServerConfig>) options.mcpServers();
        McpSseServerConfig resultServer = (McpSseServerConfig) resultServers.get("sse-server");
        assertThat(resultServer.url()).isEqualTo("https://api.example.com/sse");
        assertThat(resultServer.headers()).containsEntry("Authorization", "Bearer token123");
    }

    @SuppressWarnings("null")
    @Test
    void testOptionsWithMcpServersHttp() {
        McpHttpServerConfig server = new McpHttpServerConfig(
                "https://api.example.com/mcp",
                Map.of("X-API-Key", "key123"));
        Map<String, McpServerConfig> servers = Map.of("http-server", server);

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .mcpServers(servers)
                .build();

        @SuppressWarnings("unchecked")
        Map<String, McpServerConfig> resultServers = (Map<String, McpServerConfig>) options.mcpServers();
        McpHttpServerConfig resultServer = (McpHttpServerConfig) resultServers.get("http-server");
        assertThat(resultServer.url()).isEqualTo("https://api.example.com/mcp");
        assertThat(resultServer.headers()).containsEntry("X-API-Key", "key123");
    }

    // Note: mcpServers as JSON string is not supported directly in the builder.
    // Use Path or Map<String, McpServerConfig> instead.

    // ==================== Extra Args Tests ====================

    @Test
    void testOptionsWithExtraArgsMultiple() {
        Map<String, String> extraArgs = Map.of(
                "new-flag", "value",
                "another-option", "test-value");
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .extraArgs(extraArgs)
                .build();

        assertThat(options.extraArgs()).containsEntry("new-flag", "value");
        assertThat(options.extraArgs()).containsEntry("another-option", "test-value");
    }

    @Test
    void testOptionsWithExtraArgsBooleanFlag() {
        // Boolean flags have null value in Python, in Java we use empty string
        Map<String, String> extraArgs = new java.util.HashMap<>();
        extraArgs.put("boolean-flag", "");
        extraArgs.put("another-flag", "value");

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .extraArgs(extraArgs)
                .build();

        assertThat(options.extraArgs()).containsEntry("boolean-flag", "");
        assertThat(options.extraArgs()).containsEntry("another-flag", "value");
    }

    // ==================== Environment Variables Tests ====================

    @Test
    void testOptionsWithEnvironmentVariables() {
        Map<String, String> env = Map.of(
                "MY_VAR", "my_value",
                "ANOTHER_VAR", "another_value");
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .env(env)
                .build();

        assertThat(options.env()).containsEntry("MY_VAR", "my_value");
        assertThat(options.env()).containsEntry("ANOTHER_VAR", "another_value");
    }

    // ==================== Output Format Tests ====================

    @SuppressWarnings("null")
    @Test
    void testOptionsWithJsonSchemaOutputFormat() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "result", Map.of("type", "string"),
                        "confidence", Map.of("type", "number")),
                "required", List.of("result"));
        Map<String, Object> outputFormat = Map.of(
                "type", "json_schema",
                "schema", schema);

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .outputFormat(outputFormat)
                .build();

        assertThat(options.outputFormat()).containsEntry("type", "json_schema");
        @SuppressWarnings("unchecked")
        Map<String, Object> resultSchema = (Map<String, Object>) options.outputFormat().get("schema");
        assertThat(resultSchema).containsEntry("type", "object");
    }

    // ==================== Max Buffer Size Tests ====================

    @Test
    void testOptionsWithMaxBufferSize() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .maxBufferSize(2048)
                .build();

        assertThat(options.maxBufferSize()).isEqualTo(2048);
    }

    // ==================== Full Options Combination Test ====================

    @SuppressWarnings("null")
    @Test
    void testAllOptionsCombined() {
        SandboxSettings sandbox = new SandboxSettings(true);
        Map<String, McpServerConfig> mcpServers = Map.of(
                "test", new McpStdioServerConfig("node", List.of("server.js")));
        AgentDefinition agent = new AgentDefinition("Test", "Test prompt");

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .model("claude-sonnet-4-5")
                .fallbackModel("claude-haiku-3-5")
                .permissionMode(PermissionMode.BYPASS_PERMISSIONS)
                .maxTurns(10)
                .maxBudgetUsd(5.0)
                .maxBufferSize(2048)
                .maxThinkingTokens(8000)
                .systemPrompt("Be helpful and concise")
                .tools(List.of("Bash", "Read", "Write"))
                .allowedTools(List.of("Edit"))
                .disallowedTools(List.of("Execute"))
                .cwd(Path.of("/workspace"))
                .cliPath(Path.of("/usr/local/bin/claude"))
                .continueConversation(false)
                .resume("session-abc")
                .forkSession(true)
                .settings("{\"key\": \"value\"}")
                .sandbox(sandbox)
                .mcpServers(mcpServers)
                .env(Map.of("ENV_VAR", "value"))
                .extraArgs(Map.of("custom", "arg"))
                .user("testuser")
                .betas(List.of(SdkBeta.CONTEXT_1M))
                .agents(Map.of("test-agent", agent))
                .includePartialMessages(true)
                .enableFileCheckpointing(true)
                .build();

        // Verify all options
        assertThat(options.model()).isEqualTo("claude-sonnet-4-5");
        assertThat(options.fallbackModel()).isEqualTo("claude-haiku-3-5");
        assertThat(options.permissionMode()).isEqualTo(PermissionMode.BYPASS_PERMISSIONS);
        assertThat(options.maxTurns()).isEqualTo(10);
        assertThat(options.maxBudgetUsd()).isEqualTo(5.0);
        assertThat(options.maxBufferSize()).isEqualTo(2048);
        assertThat(options.maxThinkingTokens()).isEqualTo(8000);
        assertThat(options.systemPrompt()).isEqualTo("Be helpful and concise");
        assertThat(options.tools()).isEqualTo(List.of("Bash", "Read", "Write"));
        assertThat(options.allowedTools()).containsExactly("Edit");
        assertThat(options.disallowedTools()).containsExactly("Execute");
        assertThat(options.cwd()).isEqualTo(Path.of("/workspace"));
        assertThat(options.cliPath()).isEqualTo(Path.of("/usr/local/bin/claude"));
        assertThat(options.continueConversation()).isFalse();
        assertThat(options.resume()).isEqualTo("session-abc");
        assertThat(options.forkSession()).isTrue();
        assertThat(options.settings()).isEqualTo("{\"key\": \"value\"}");
        assertThat(options.sandbox()).isNotNull();
        assertThat(options.mcpServers()).isNotNull();
        assertThat(options.env()).containsEntry("ENV_VAR", "value");
        assertThat(options.extraArgs()).containsEntry("custom", "arg");
        assertThat(options.user()).isEqualTo("testuser");
        assertThat(options.betas()).containsExactly(SdkBeta.CONTEXT_1M);
        assertThat(options.agents()).containsKey("test-agent");
        assertThat(options.includePartialMessages()).isTrue();
        assertThat(options.enableFileCheckpointing()).isTrue();
    }

}
