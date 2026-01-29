package in.vidyalai.claude.sdk.types;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import in.vidyalai.claude.sdk.types.config.AIModel;
import in.vidyalai.claude.sdk.types.config.AgentDefinition;
import in.vidyalai.claude.sdk.types.config.SandboxIgnoreViolations;
import in.vidyalai.claude.sdk.types.config.SandboxNetworkConfig;
import in.vidyalai.claude.sdk.types.config.SandboxSettings;
import in.vidyalai.claude.sdk.types.config.SdkBeta;
import in.vidyalai.claude.sdk.types.config.SettingSource;
import in.vidyalai.claude.sdk.types.config.SystemPromptPreset;
import in.vidyalai.claude.sdk.types.config.ToolsPreset;
import in.vidyalai.claude.sdk.types.hook.HookContext;
import in.vidyalai.claude.sdk.types.mcp.McpHttpServerConfig;
import in.vidyalai.claude.sdk.types.mcp.McpSseServerConfig;
import in.vidyalai.claude.sdk.types.mcp.McpStdioServerConfig;
import in.vidyalai.claude.sdk.types.message.AssistantMessage;
import in.vidyalai.claude.sdk.types.message.AssistantMessageError;
import in.vidyalai.claude.sdk.types.message.ResultMessage;
import in.vidyalai.claude.sdk.types.message.StreamEvent;
import in.vidyalai.claude.sdk.types.message.SystemMessage;
import in.vidyalai.claude.sdk.types.message.TextBlock;
import in.vidyalai.claude.sdk.types.message.ToolUseBlock;
import in.vidyalai.claude.sdk.types.message.UserMessage;
import in.vidyalai.claude.sdk.types.permission.PermissionBehavior;
import in.vidyalai.claude.sdk.types.permission.PermissionMode;
import in.vidyalai.claude.sdk.types.permission.PermissionRuleValue;
import in.vidyalai.claude.sdk.types.permission.PermissionUpdate;
import in.vidyalai.claude.sdk.types.permission.PermissionUpdateDestination;
import in.vidyalai.claude.sdk.types.permission.ToolPermissionContext;

/**
 * Additional tests for types not covered in TypesTest.
 * Covers presets, beta headers, and more edge cases.
 */
class AdditionalTypesTest {

    // ==================== SystemPromptPreset Tests ====================

    @Test
    void testSystemPromptPresetClaudeCode() {
        SystemPromptPreset preset = SystemPromptPreset.claudeCode();

        assertThat(preset.type()).isEqualTo("preset");
        assertThat(preset.preset()).isEqualTo("claude_code");
        assertThat(preset.append()).isNull();
    }

    @Test
    void testSystemPromptPresetClaudeCodeWithAppend() {
        SystemPromptPreset preset = SystemPromptPreset.claudeCode("Additional instructions");

        assertThat(preset.type()).isEqualTo("preset");
        assertThat(preset.preset()).isEqualTo("claude_code");
        assertThat(preset.append()).isEqualTo("Additional instructions");
    }

    @Test
    void testSystemPromptPresetDirectConstruction() {
        SystemPromptPreset preset = new SystemPromptPreset("custom_preset", "custom append");

        assertThat(preset.preset()).isEqualTo("custom_preset");
        assertThat(preset.append()).isEqualTo("custom append");
    }

    // ==================== ToolsPreset Tests ====================

    @Test
    void testToolsPresetClaudeCode() {
        ToolsPreset preset = ToolsPreset.claudeCode();

        assertThat(preset.type()).isEqualTo("preset");
        assertThat(preset.preset()).isEqualTo("claude_code");
    }

    @Test
    void testToolsPresetDirectConstruction() {
        ToolsPreset preset = new ToolsPreset("custom_preset");

        assertThat(preset.preset()).isEqualTo("custom_preset");
    }

    // ==================== SdkBeta Tests ====================

    @Test
    void testSdkBetaContext1m() {
        SdkBeta beta = SdkBeta.CONTEXT_1M;

        assertThat(beta.getValue()).isEqualTo("context-1m-2025-08-07");
        assertThat(beta.toString()).isEqualTo("context-1m-2025-08-07");
    }

    @Test
    void testSdkBetaFromValue() {
        SdkBeta beta = SdkBeta.fromValue("context-1m-2025-08-07");

        assertThat(beta).isEqualTo(SdkBeta.CONTEXT_1M);
    }

    @SuppressWarnings("null")
    @Test
    void testSdkBetaFromValueUnknown() {
        assertThatThrownBy(() -> SdkBeta.fromValue("unknown-beta"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown SDK beta");
    }

    // ==================== SettingSource Tests ====================

    @Test
    void testSettingSourceValues() {
        assertThat(SettingSource.USER.getValue()).isEqualTo("user");
        assertThat(SettingSource.PROJECT.getValue()).isEqualTo("project");
        assertThat(SettingSource.LOCAL.getValue()).isEqualTo("local");
    }

    @Test
    void testSettingSourceFromValue() {
        assertThat(SettingSource.fromValue("user")).isEqualTo(SettingSource.USER);
        assertThat(SettingSource.fromValue("project")).isEqualTo(SettingSource.PROJECT);
        assertThat(SettingSource.fromValue("local")).isEqualTo(SettingSource.LOCAL);
    }

    // ==================== ResultMessage Tests ====================

    @Test
    void testResultMessageWithAllFields() {
        ResultMessage result = new ResultMessage(
                "success",
                1500,
                1200,
                false,
                3,
                "session-xyz",
                0.0035,
                Map.of("input_tokens", 100, "output_tokens", 50),
                "Task completed successfully",
                null);

        assertThat(result.type()).isEqualTo("result");
        assertThat(result.subtype()).isEqualTo("success");
        assertThat(result.durationMs()).isEqualTo(1500);
        assertThat(result.durationApiMs()).isEqualTo(1200);
        assertThat(result.isError()).isFalse();
        assertThat(result.numTurns()).isEqualTo(3);
        assertThat(result.sessionId()).isEqualTo("session-xyz");
        assertThat(result.totalCostUsd()).isEqualTo(0.0035);
        assertThat(result.result()).isEqualTo("Task completed successfully");
    }

    @Test
    void testResultMessageIsError() {
        ResultMessage success = new ResultMessage("success", 100, 80, false, 1, "s", 0.001, null, "ok", null);
        ResultMessage error = new ResultMessage("error", 100, 80, true, 1, "s", 0.001, null, "failed", null);

        assertThat(success.isError()).isFalse();
        assertThat(error.isError()).isTrue();
    }

    // ==================== UserMessage Tests ====================

    @Test
    void testUserMessageWithUuid() {
        UserMessage message = new UserMessage("Hello", "uuid-123", null, null);

        assertThat(message.uuid()).isEqualTo("uuid-123");
        assertThat(message.type()).isEqualTo("user");
    }

    @Test
    void testUserMessageWithParentToolUseId() {
        UserMessage message = new UserMessage("Hello", null, "tool-use-456", null);

        assertThat(message.parentToolUseId()).isEqualTo("tool-use-456");
    }

    @Test
    void testUserMessageContentAsString() {
        UserMessage stringMessage = new UserMessage("Hello, World!", null, null, null);
        assertThat(stringMessage.contentAsString()).isEqualTo("Hello, World!");

        // Test with list content - contentAsString returns null for structured content
        UserMessage listMessage = new UserMessage(
                List.of(Map.of("type", "text", "text", "Block text")),
                null, null, null);
        assertThat(listMessage.contentAsString()).isNull();
        assertThat(listMessage.content()).isInstanceOf(List.class);
    }

    // ==================== AssistantMessage Tests ====================

    @SuppressWarnings("null")
    @Test
    void testAssistantMessageGetTextContent() {
        AssistantMessage message = new AssistantMessage(
                List.of(
                        new TextBlock("Hello"),
                        new TextBlock("World")),
                "claude-sonnet-4-5",
                null,
                null);

        assertThat(message.getTextContent()).isEqualTo("Hello\nWorld");
    }

    @SuppressWarnings("null")
    @Test
    void testAssistantMessageHasToolUse() {
        AssistantMessage withToolUse = new AssistantMessage(
                List.of(new ToolUseBlock("id", "Bash", Map.of())),
                "model",
                null,
                null);

        AssistantMessage withoutToolUse = new AssistantMessage(
                List.of(new TextBlock("Just text")),
                "model",
                null,
                null);

        assertThat(withToolUse.hasToolUse()).isTrue();
        assertThat(withoutToolUse.hasToolUse()).isFalse();
    }

    @Test
    void testAssistantMessageWithError() {
        AssistantMessage message = new AssistantMessage(
                List.of(),
                "model",
                null,
                AssistantMessageError.RATE_LIMIT);

        assertThat(message.error()).isEqualTo(AssistantMessageError.RATE_LIMIT);
    }

    // ==================== StreamEvent Tests ====================

    @Test
    void testStreamEventEventType() {
        StreamEvent event = new StreamEvent(
                "uuid-123",
                "session-456",
                Map.of("type", "content_block_delta", "index", 0),
                null);

        assertThat(event.eventType()).isEqualTo("content_block_delta");
    }

    @Test
    void testStreamEventWithParentToolUseId() {
        StreamEvent event = new StreamEvent(
                "uuid-123",
                "session-456",
                Map.of("type", "message_start"),
                "parent-tool-789");

        assertThat(event.parentToolUseId()).isEqualTo("parent-tool-789");
    }

    // ==================== SystemMessage Tests ====================

    @Test
    void testSystemMessage() {
        SystemMessage message = new SystemMessage(
                "init",
                Map.of("version", "1.0", "capabilities", List.of("tools", "streaming")));

        assertThat(message.type()).isEqualTo("system");
        assertThat(message.subtype()).isEqualTo("init");
        assertThat(message.data()).containsEntry("version", "1.0");
    }

    // ==================== AgentDefinition Tests ====================

    @Test
    void testAgentDefinitionMinimal() {
        AgentDefinition agent = new AgentDefinition("Test agent", "Test prompt");

        assertThat(agent.description()).isEqualTo("Test agent");
        assertThat(agent.prompt()).isEqualTo("Test prompt");
        assertThat(agent.tools()).isNull();
        assertThat(agent.model()).isNull();
    }

    @Test
    void testAgentDefinitionFull() {
        AgentDefinition agent = new AgentDefinition(
                "Full agent",
                "Full prompt",
                List.of("Bash", "Read", "Write"),
                AIModel.OPUS);

        assertThat(agent.tools()).containsExactly("Bash", "Read", "Write");
        assertThat(agent.model()).isEqualTo(AIModel.OPUS);
    }

    // ==================== SandboxSettings Tests ====================

    @Test
    void testSandboxSettingsMinimal() {
        SandboxSettings sandbox = new SandboxSettings(true);

        assertThat(sandbox.enabled()).isTrue();
        assertThat(sandbox.autoAllowBashIfSandboxed()).isNull();
        assertThat(sandbox.excludedCommands()).isNull();
    }

    @SuppressWarnings("null")
    @Test
    void testSandboxSettingsFull() {
        SandboxNetworkConfig network = new SandboxNetworkConfig(
                List.of("/var/run/docker.sock"),
                false,
                true,
                8080,
                1080);

        SandboxIgnoreViolations ignore = new SandboxIgnoreViolations(
                List.of("/tmp"),
                List.of("localhost"));

        SandboxSettings sandbox = new SandboxSettings(
                true,
                true,
                List.of("git", "docker"),
                null,
                network,
                ignore,
                false);

        assertThat(sandbox.enabled()).isTrue();
        assertThat(sandbox.autoAllowBashIfSandboxed()).isTrue();
        assertThat(sandbox.excludedCommands()).containsExactly("git", "docker");
        assertThat(sandbox.network().httpProxyPort()).isEqualTo(8080);
        assertThat(sandbox.ignoreViolations().file()).containsExactly("/tmp");
    }

    // ==================== PermissionUpdate Tests ====================

    @Test
    void testPermissionUpdateSetModeWithAllDestinations() {
        for (PermissionUpdateDestination dest : PermissionUpdateDestination.values()) {
            PermissionUpdate update = PermissionUpdate.setMode(PermissionMode.ACCEPT_EDITS, dest);
            Map<String, Object> map = update.toMap();
            assertThat(map).containsEntry("destination", dest.getValue());
        }
    }

    @SuppressWarnings("null")
    @Test
    void testPermissionUpdateAddRulesWithAllBehaviors() {
        for (PermissionBehavior behavior : PermissionBehavior.values()) {
            PermissionUpdate update = PermissionUpdate.addRules(
                    List.of(new PermissionRuleValue("Tool", "pattern")),
                    behavior,
                    PermissionUpdateDestination.SESSION);
            Map<String, Object> map = update.toMap();
            assertThat(map).containsEntry("behavior", behavior.getValue());
        }
    }

    @SuppressWarnings("null")
    @Test
    void testPermissionUpdateSerializationRoundTrip_AddRules() {
        // Create a PermissionUpdate with addRules
        PermissionUpdate original = PermissionUpdate.addRules(
                List.of(
                        new PermissionRuleValue("Bash", "*.sh"),
                        new PermissionRuleValue("Write", "/tmp/*")),
                PermissionBehavior.ALLOW,
                PermissionUpdateDestination.SESSION);

        // Serialize to Map
        Map<String, Object> map = original.toMap();

        // Deserialize from Map
        PermissionUpdate deserialized = PermissionUpdate.fromMap(map);

        // Verify the deserialized object matches the original
        assertThat(deserialized.type()).isEqualTo(original.type());
        assertThat(deserialized.rules()).hasSize(2);
        assertThat(deserialized.rules().get(0).toolName()).isEqualTo("Bash");
        assertThat(deserialized.rules().get(0).ruleContent()).isEqualTo("*.sh");
        assertThat(deserialized.rules().get(1).toolName()).isEqualTo("Write");
        assertThat(deserialized.rules().get(1).ruleContent()).isEqualTo("/tmp/*");
        assertThat(deserialized.behavior()).isEqualTo(PermissionBehavior.ALLOW);
        assertThat(deserialized.destination()).isEqualTo(PermissionUpdateDestination.SESSION);
    }

    @Test
    void testPermissionUpdateSerializationRoundTrip_SetMode() {
        // Create a PermissionUpdate with setMode
        PermissionUpdate original = PermissionUpdate.setMode(
                PermissionMode.ACCEPT_EDITS,
                PermissionUpdateDestination.PROJECT_SETTINGS);

        // Serialize to Map
        Map<String, Object> map = original.toMap();

        // Deserialize from Map
        PermissionUpdate deserialized = PermissionUpdate.fromMap(map);

        // Verify the deserialized object matches the original
        assertThat(deserialized.type()).isEqualTo(original.type());
        assertThat(deserialized.mode()).isEqualTo(PermissionMode.ACCEPT_EDITS);
        assertThat(deserialized.destination()).isEqualTo(PermissionUpdateDestination.PROJECT_SETTINGS);
    }

    @Test
    void testPermissionUpdateSerializationRoundTrip_AddDirectories() {
        // Create a PermissionUpdate with addDirectories
        PermissionUpdate original = PermissionUpdate.addDirectories(
                List.of("/home/user/projects", "/opt/app"),
                PermissionUpdateDestination.USER_SETTINGS);

        // Serialize to Map
        Map<String, Object> map = original.toMap();

        // Deserialize from Map
        PermissionUpdate deserialized = PermissionUpdate.fromMap(map);

        // Verify the deserialized object matches the original
        assertThat(deserialized.type()).isEqualTo(original.type());
        assertThat(deserialized.directories()).containsExactly("/home/user/projects", "/opt/app");
        assertThat(deserialized.destination()).isEqualTo(PermissionUpdateDestination.USER_SETTINGS);
    }

    @SuppressWarnings("null")
    @Test
    void testPermissionUpdateSerializationRoundTrip_RemoveRules() {
        // Create a PermissionUpdate with removeRules
        PermissionUpdate original = PermissionUpdate.removeRules(
                List.of(new PermissionRuleValue("Bash")),
                null);

        // Serialize to Map
        Map<String, Object> map = original.toMap();

        // Deserialize from Map
        PermissionUpdate deserialized = PermissionUpdate.fromMap(map);

        // Verify the deserialized object matches the original
        assertThat(deserialized.type()).isEqualTo(original.type());
        assertThat(deserialized.rules()).hasSize(1);
        assertThat(deserialized.rules().get(0).toolName()).isEqualTo("Bash");
        assertThat(deserialized.rules().get(0).ruleContent()).isNull();
        assertThat(deserialized.destination()).isNull();
    }

    // ==================== MCP Server Configs Tests ====================

    @Test
    void testStdioMcpServerConfigWithEnv() {
        McpStdioServerConfig config = new McpStdioServerConfig(
                "node",
                List.of("server.js", "--port", "3000"),
                Map.of("NODE_ENV", "production"));

        assertThat(config.command()).isEqualTo("node");
        assertThat(config.args()).containsExactly("server.js", "--port", "3000");
        assertThat(config.env()).containsEntry("NODE_ENV", "production");
    }

    @Test
    void testSseMcpServerConfigWithHeaders() {
        McpSseServerConfig config = new McpSseServerConfig(
                "https://api.example.com/sse",
                Map.of("Authorization", "Bearer token123"));

        assertThat(config.url()).isEqualTo("https://api.example.com/sse");
        assertThat(config.headers()).containsEntry("Authorization", "Bearer token123");
    }

    @Test
    void testHttpMcpServerConfigWithHeaders() {
        McpHttpServerConfig config = new McpHttpServerConfig(
                "https://api.example.com/mcp",
                Map.of("X-API-Key", "key123"));

        assertThat(config.url()).isEqualTo("https://api.example.com/mcp");
        assertThat(config.headers()).containsEntry("X-API-Key", "key123");
    }

    // ==================== ToolPermissionContext Tests ====================

    @Test
    void testToolPermissionContextEmpty() {
        ToolPermissionContext context = new ToolPermissionContext();

        assertThat(context.signal()).isNull();
        assertThat(context.suggestions()).isEmpty();
    }

    @SuppressWarnings("null")
    @Test
    void testToolPermissionContextWithSuggestions() {
        List<PermissionUpdate> suggestions = List.of(
                PermissionUpdate.setMode(PermissionMode.ACCEPT_EDITS, PermissionUpdateDestination.SESSION),
                PermissionUpdate.addRules(
                        List.of(new PermissionRuleValue("Bash", "*")),
                        PermissionBehavior.ALLOW,
                        PermissionUpdateDestination.SESSION));

        ToolPermissionContext context = new ToolPermissionContext(suggestions);

        assertThat(context.suggestions()).hasSize(2);
    }

    // ==================== HookContext Tests ====================

    @Test
    void testHookContextEmpty() {
        HookContext context = new HookContext();

        assertThat(context.toolUseId()).isNull();
        assertThat(context.signal()).isNull();
    }

    @Test
    void testHookContextWithToolUseId() {
        HookContext context = new HookContext("tool-use-123");

        assertThat(context.toolUseId()).isEqualTo("tool-use-123");
        assertThat(context.signal()).isNull();
    }

    @Test
    void testHookContextFull() {
        Object signal = new Object(); // Placeholder for future abort signal
        HookContext context = new HookContext("tool-use-123", signal);

        assertThat(context.toolUseId()).isEqualTo("tool-use-123");
        assertThat(context.signal()).isEqualTo(signal);
    }

}
