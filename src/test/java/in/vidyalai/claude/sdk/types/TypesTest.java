package in.vidyalai.claude.sdk.types;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import in.vidyalai.claude.sdk.types.config.AIModel;
import in.vidyalai.claude.sdk.types.config.AgentDefinition;
import in.vidyalai.claude.sdk.types.config.SandboxSettings;
import in.vidyalai.claude.sdk.types.config.SettingSource;
import in.vidyalai.claude.sdk.types.config.SystemPromptPreset;
import in.vidyalai.claude.sdk.types.config.ToolsPreset;
import in.vidyalai.claude.sdk.types.hook.HookEvent;
import in.vidyalai.claude.sdk.types.hook.HookOutput;
import in.vidyalai.claude.sdk.types.hook.HookSpecificOutput;
import in.vidyalai.claude.sdk.types.mcp.McpHttpServerConfig;
import in.vidyalai.claude.sdk.types.mcp.McpSseServerConfig;
import in.vidyalai.claude.sdk.types.mcp.McpStdioServerConfig;
import in.vidyalai.claude.sdk.types.message.AssistantMessage;
import in.vidyalai.claude.sdk.types.message.AssistantMessageError;
import in.vidyalai.claude.sdk.types.message.ContentBlock;
import in.vidyalai.claude.sdk.types.message.Message;
import in.vidyalai.claude.sdk.types.message.ResultMessage;
import in.vidyalai.claude.sdk.types.message.StreamEvent;
import in.vidyalai.claude.sdk.types.message.SystemMessage;
import in.vidyalai.claude.sdk.types.message.TextBlock;
import in.vidyalai.claude.sdk.types.message.ThinkingBlock;
import in.vidyalai.claude.sdk.types.message.ToolResultBlock;
import in.vidyalai.claude.sdk.types.message.ToolUseBlock;
import in.vidyalai.claude.sdk.types.message.UserMessage;
import in.vidyalai.claude.sdk.types.permission.PermissionBehavior;
import in.vidyalai.claude.sdk.types.permission.PermissionDecision;
import in.vidyalai.claude.sdk.types.permission.PermissionMode;
import in.vidyalai.claude.sdk.types.permission.PermissionResultAllow;
import in.vidyalai.claude.sdk.types.permission.PermissionResultDeny;
import in.vidyalai.claude.sdk.types.permission.PermissionRuleValue;
import in.vidyalai.claude.sdk.types.permission.PermissionUpdate;
import in.vidyalai.claude.sdk.types.permission.PermissionUpdateDestination;

class TypesTest {

    @Test
    void permissionMode_fromValue() {
        assertThat(PermissionMode.fromValue("default")).isEqualTo(PermissionMode.DEFAULT);
        assertThat(PermissionMode.fromValue("acceptEdits")).isEqualTo(PermissionMode.ACCEPT_EDITS);
        assertThat(PermissionMode.fromValue("plan")).isEqualTo(PermissionMode.PLAN);
        assertThat(PermissionMode.fromValue("bypassPermissions")).isEqualTo(PermissionMode.BYPASS_PERMISSIONS);

        assertThatThrownBy(() -> PermissionMode.fromValue("unknown"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void hookEvent_fromValue() {
        assertThat(HookEvent.fromValue("PreToolUse")).isEqualTo(HookEvent.PRE_TOOL_USE);
        assertThat(HookEvent.fromValue("PostToolUse")).isEqualTo(HookEvent.POST_TOOL_USE);
        assertThat(HookEvent.fromValue("UserPromptSubmit")).isEqualTo(HookEvent.USER_PROMPT_SUBMIT);
        assertThat(HookEvent.fromValue("Stop")).isEqualTo(HookEvent.STOP);
        assertThat(HookEvent.fromValue("SubagentStop")).isEqualTo(HookEvent.SUBAGENT_STOP);
        assertThat(HookEvent.fromValue("PreCompact")).isEqualTo(HookEvent.PRE_COMPACT);
    }

    @Test
    void assistantMessageError_fromValue() {
        assertThat(AssistantMessageError.fromValue("authentication_failed"))
                .isEqualTo(AssistantMessageError.AUTHENTICATION_FAILED);
        assertThat(AssistantMessageError.fromValue("billing_error")).isEqualTo(AssistantMessageError.BILLING_ERROR);
        assertThat(AssistantMessageError.fromValue("rate_limit")).isEqualTo(AssistantMessageError.RATE_LIMIT);
        assertThat(AssistantMessageError.fromValue(null)).isNull();
        assertThat(AssistantMessageError.fromValue("something_unknown")).isEqualTo(AssistantMessageError.UNKNOWN);
    }

    @Test
    void textBlock_typeMethod() {
        TextBlock block = new TextBlock("Hello");
        assertThat(block.type()).isEqualTo("text");
        assertThat(block.text()).isEqualTo("Hello");
    }

    @Test
    void thinkingBlock_typeMethod() {
        ThinkingBlock block = new ThinkingBlock("Let me think...", "sig123");
        assertThat(block.type()).isEqualTo("thinking");
        assertThat(block.thinking()).isEqualTo("Let me think...");
        assertThat(block.signature()).isEqualTo("sig123");
    }

    @Test
    void toolUseBlock_typeMethod() {
        ToolUseBlock block = new ToolUseBlock("id-123", "Bash", Map.of("command", "ls"));
        assertThat(block.type()).isEqualTo("tool_use");
        assertThat(block.id()).isEqualTo("id-123");
        assertThat(block.name()).isEqualTo("Bash");
        assertThat(block.input()).containsEntry("command", "ls");
    }

    @Test
    void toolResultBlock_typeMethod() {
        ToolResultBlock block = new ToolResultBlock("id-123", "output text", false);
        assertThat(block.type()).isEqualTo("tool_result");
        assertThat(block.toolUseId()).isEqualTo("id-123");
        assertThat(block.content()).isEqualTo("output text");
        assertThat(block.isError()).isFalse();
    }

    @Test
    void permissionResultAllow_behavior() {
        PermissionResultAllow result = new PermissionResultAllow();
        assertThat(result.behavior()).isEqualTo("allow");
        assertThat(result.updatedInput()).isNull();
        assertThat(result.updatedPermissions()).isNull();

        PermissionResultAllow withInput = new PermissionResultAllow(Map.of("key", "value"));
        assertThat(withInput.updatedInput()).containsEntry("key", "value");
    }

    @Test
    void permissionResultDeny_behavior() {
        PermissionResultDeny result = new PermissionResultDeny("Not allowed");
        assertThat(result.behavior()).isEqualTo("deny");
        assertThat(result.message()).isEqualTo("Not allowed");
        assertThat(result.interrupt()).isFalse();

        PermissionResultDeny withInterrupt = new PermissionResultDeny("Critical", true);
        assertThat(withInterrupt.interrupt()).isTrue();
    }

    @Test
    void permissionUpdate_toMap() {
        PermissionUpdate update = PermissionUpdate.setMode(
                PermissionMode.BYPASS_PERMISSIONS,
                PermissionUpdateDestination.SESSION);

        Map<String, Object> map = update.toMap();
        assertThat(map).containsEntry("type", "setMode");
        assertThat(map).containsEntry("mode", "bypassPermissions");
        assertThat(map).containsEntry("destination", "session");
    }

    @SuppressWarnings("null")
    @Test
    void permissionUpdate_addRules() {
        PermissionUpdate update = PermissionUpdate.addRules(
                List.of(new PermissionRuleValue("Bash", "allow all")),
                PermissionBehavior.ALLOW,
                PermissionUpdateDestination.PROJECT_SETTINGS);

        Map<String, Object> map = update.toMap();
        assertThat(map).containsEntry("type", "addRules");
        assertThat(map).containsEntry("behavior", "allow");
        assertThat(map).containsKey("rules");
    }

    @Test
    void hookOutput_builder() {
        HookOutput output = HookOutput.builder()
                .shouldContinue(true)
                .reason("Approved by policy")
                .build();

        Map<String, Object> map = output.toMap();
        assertThat(map).containsEntry("continue", true);
        assertThat(map).containsEntry("reason", "Approved by policy");
    }

    @Test
    void hookOutput_async() {
        HookOutput output = HookOutput.async(5000);

        Map<String, Object> map = output.toMap();
        assertThat(map).containsEntry("async", true);
        assertThat(map).containsEntry("asyncTimeout", 5000);
    }

    @Test
    void hookSpecificOutput_preToolUse() {
        HookSpecificOutput output = HookSpecificOutput.preToolUse()
                .permissionDecision(PermissionDecision.ALLOW)
                .permissionDecisionReason("Safe operation")
                .build();

        Map<String, Object> map = output.toMap();
        assertThat(map).containsEntry("hookEventName", "PreToolUse");
        assertThat(map).containsEntry("permissionDecision", "allow");
        assertThat(map).containsEntry("permissionDecisionReason", "Safe operation");
    }

    @Test
    void agentDefinition_creation() {
        AgentDefinition agent = new AgentDefinition(
                "Test agent",
                "You are a test agent",
                List.of("Bash", "Read"),
                AIModel.SONNET);

        assertThat(agent.description()).isEqualTo("Test agent");
        assertThat(agent.prompt()).isEqualTo("You are a test agent");
        assertThat(agent.tools()).containsExactly("Bash", "Read");
        assertThat(agent.model()).isEqualTo(AIModel.SONNET);
    }

    @Test
    void sandboxSettings_creation() {
        SandboxSettings sandbox = new SandboxSettings(true);
        assertThat(sandbox.enabled()).isTrue();
    }

    @Test
    void mcpServerConfig_stdio() {
        McpStdioServerConfig config = new McpStdioServerConfig("node", List.of("server.js"));
        assertThat(config.type()).isEqualTo("stdio");
        assertThat(config.command()).isEqualTo("node");
        assertThat(config.args()).containsExactly("server.js");
    }

    @Test
    void mcpServerConfig_sse() {
        McpSseServerConfig config = new McpSseServerConfig("https://example.com/sse");
        assertThat(config.type()).isEqualTo("sse");
        assertThat(config.url()).isEqualTo("https://example.com/sse");
    }

    @Test
    void mcpServerConfig_http() {
        McpHttpServerConfig config = new McpHttpServerConfig("https://example.com/api");
        assertThat(config.type()).isEqualTo("http");
        assertThat(config.url()).isEqualTo("https://example.com/api");
    }

    @SuppressWarnings("null")
    @Test
    void patternMatchingOnMessage() {
        Message message = new AssistantMessage(
                List.of(new TextBlock("Hello")),
                "claude-sonnet-4-5",
                null,
                null);

        String result = switch (message) {
            case UserMessage u -> "user: " + u.content();
            case AssistantMessage a -> "assistant: " + a.getTextContent();
            case SystemMessage s -> "system: " + s.subtype();
            case ResultMessage r -> "result: " + r.result();
            case StreamEvent e -> "event: " + e.eventType();
        };

        assertThat(result).isEqualTo("assistant: Hello");
    }

    @Test
    void patternMatchingOnContentBlock() {
        ContentBlock block = new ToolUseBlock("id", "Bash", Map.of("cmd", "ls"));

        String result = switch (block) {
            case TextBlock t -> "text: " + t.text();
            case ThinkingBlock t -> "thinking: " + t.thinking();
            case ToolUseBlock t -> "tool: " + t.name();
            case ToolResultBlock t -> "result: " + t.toolUseId();
        };

        assertThat(result).isEqualTo("tool: Bash");
    }

    // ==================== UserMessage Tests ====================

    @Test
    void userMessage_withStringContent() {
        UserMessage msg = new UserMessage("Hello, Claude!", null, null, null);
        assertThat(msg.type()).isEqualTo("user");
        assertThat(msg.contentAsString()).isEqualTo("Hello, Claude!");
        assertThat(msg.uuid()).isNull();
        assertThat(msg.parentToolUseId()).isNull();
        assertThat(msg.toolUseResult()).isNull();
    }

    @SuppressWarnings("null")
    @Test
    void userMessage_withContentBlocks() {
        List<ContentBlock> blocks = List.of(
                new TextBlock("Hello"),
                new ToolUseBlock("tool-1", "Read", Map.of("file", "/test.txt")));
        UserMessage msg = new UserMessage(blocks, "uuid-123", null, null);

        assertThat(msg.type()).isEqualTo("user");
        assertThat(msg.contentAsBlocks()).hasSize(2);
        assertThat(msg.uuid()).isEqualTo("uuid-123");
    }

    @Test
    void userMessage_withParentToolUseId() {
        UserMessage msg = new UserMessage("Hello", null, "parent-tool-123", null);

        assertThat(msg.parentToolUseId()).isEqualTo("parent-tool-123");
    }

    // ==================== AssistantMessage Tests ====================

    @SuppressWarnings("null")
    @Test
    void assistantMessage_withTextContent() {
        List<ContentBlock> content = List.of(new TextBlock("Hello, human!"));
        AssistantMessage msg = new AssistantMessage(content, "claude-sonnet-4-5", null, null);

        assertThat(msg.type()).isEqualTo("assistant");
        assertThat(msg.content()).hasSize(1);
        assertThat(msg.model()).isEqualTo("claude-sonnet-4-5");
        assertThat(msg.getTextContent()).isEqualTo("Hello, human!");
        assertThat(msg.hasToolUse()).isFalse();
    }

    @SuppressWarnings("null")
    @Test
    void assistantMessage_withThinkingContent() {
        List<ContentBlock> content = List.of(
                new ThinkingBlock("I'm thinking...", "sig-123"),
                new TextBlock("Here's my answer"));
        AssistantMessage msg = new AssistantMessage(content, "claude-opus-4-5", null, null);

        assertThat(msg.content()).hasSize(2);
        assertThat(msg.content().get(0)).isInstanceOf(ThinkingBlock.class);
        ThinkingBlock thinking = (ThinkingBlock) msg.content().get(0);
        assertThat(thinking.thinking()).isEqualTo("I'm thinking...");
        assertThat(thinking.signature()).isEqualTo("sig-123");
    }

    @SuppressWarnings("null")
    @Test
    void assistantMessage_withToolUse() {
        List<ContentBlock> content = List.of(
                new TextBlock("Let me read that file"),
                new ToolUseBlock("tool-1", "Read", Map.of("file_path", "/test.txt")));
        AssistantMessage msg = new AssistantMessage(content, "claude-sonnet-4-5", null, null);

        assertThat(msg.hasToolUse()).isTrue();
        assertThat(msg.content()).hasSize(2);
    }

    @SuppressWarnings("null")
    @Test
    void assistantMessage_withParentToolUseId() {
        List<ContentBlock> content = List.of(new TextBlock("Response"));
        AssistantMessage msg = new AssistantMessage(content, "claude-sonnet-4-5", "parent-tool-123", null);

        assertThat(msg.parentToolUseId()).isEqualTo("parent-tool-123");
    }

    @SuppressWarnings("null")
    @Test
    void assistantMessage_withError() {
        List<ContentBlock> content = List.of(new TextBlock("Error occurred"));
        AssistantMessage msg = new AssistantMessage(content, "claude-sonnet-4-5", null,
                AssistantMessageError.RATE_LIMIT);

        assertThat(msg.error()).isEqualTo(AssistantMessageError.RATE_LIMIT);
    }

    // ==================== ResultMessage Tests ====================

    @Test
    void resultMessage_creation() {
        ResultMessage msg = new ResultMessage(
                "success",
                1500,
                1200,
                false,
                1,
                "session-123",
                0.01,
                null,
                "Task completed",
                null);

        assertThat(msg.type()).isEqualTo("result");
        assertThat(msg.subtype()).isEqualTo("success");
        assertThat(msg.durationMs()).isEqualTo(1500);
        assertThat(msg.durationApiMs()).isEqualTo(1200);
        assertThat(msg.isError()).isFalse();
        assertThat(msg.numTurns()).isEqualTo(1);
        assertThat(msg.sessionId()).isEqualTo("session-123");
        assertThat(msg.totalCostUsd()).isEqualTo(0.01);
        assertThat(msg.result()).isEqualTo("Task completed");
    }

    @Test
    void resultMessage_withUsage() {
        Map<String, Object> usage = Map.of(
                "input_tokens", 100,
                "output_tokens", 50);
        ResultMessage msg = new ResultMessage(
                "success", 1500, 1200, false, 1, "session-123", 0.01, usage, null, null);

        assertThat(msg.usage()).isNotNull();
        assertThat(msg.usage()).containsEntry("input_tokens", 100);
        assertThat(msg.usage()).containsEntry("output_tokens", 50);
    }

    @Test
    void resultMessage_withStructuredOutput() {
        Map<String, Object> structuredOutput = Map.of("key", "value");
        ResultMessage msg = new ResultMessage(
                "success", 1500, 1200, false, 1, "session-123", 0.01, null, null, structuredOutput);

        assertThat(msg.structuredOutput()).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> resultStructuredOutput = (Map<String, Object>) msg.structuredOutput();
        assertThat(resultStructuredOutput).containsEntry("key", "value");
    }

    // ==================== SystemMessage Tests ====================

    @Test
    void systemMessage_creation() {
        Map<String, Object> data = Map.of("version", "1.0", "extra", "value");
        SystemMessage msg = new SystemMessage("init", data);

        assertThat(msg.type()).isEqualTo("system");
        assertThat(msg.subtype()).isEqualTo("init");
        assertThat(msg.data()).containsEntry("version", "1.0");
        assertThat(msg.data()).containsEntry("extra", "value");
    }

    // ==================== StreamEvent Tests ====================

    @Test
    void streamEvent_creation() {
        Map<String, Object> event = Map.of("type", "content_block_delta", "delta", "text");
        StreamEvent msg = new StreamEvent("event-123", "session-456", event, null);

        assertThat(msg.type()).isEqualTo("stream_event");
        assertThat(msg.uuid()).isEqualTo("event-123");
        assertThat(msg.sessionId()).isEqualTo("session-456");
        assertThat(msg.event()).containsEntry("type", "content_block_delta");
        assertThat(msg.eventType()).isEqualTo("content_block_delta");
    }

    @Test
    void streamEvent_withParentToolUseId() {
        Map<String, Object> event = Map.of("type", "content_block_delta");
        StreamEvent msg = new StreamEvent("event-123", "session-456", event, "parent-tool-123");

        assertThat(msg.parentToolUseId()).isEqualTo("parent-tool-123");
    }

    // ==================== ToolResultBlock Tests ====================

    @Test
    void toolResultBlock_withError() {
        ToolResultBlock block = new ToolResultBlock("tool-123", "File not found", true);

        assertThat(block.type()).isEqualTo("tool_result");
        assertThat(block.toolUseId()).isEqualTo("tool-123");
        assertThat(block.content()).isEqualTo("File not found");
        assertThat(block.isError()).isTrue();
    }

    // ==================== SystemPromptPreset Tests ====================

    @Test
    void systemPromptPreset_claudeCode() {
        SystemPromptPreset preset = SystemPromptPreset.claudeCode();

        assertThat(preset.type()).isEqualTo("preset");
        assertThat(preset.preset()).isEqualTo("claude_code");
        assertThat(preset.append()).isNull();
    }

    @Test
    void systemPromptPreset_claudeCodeWithAppend() {
        SystemPromptPreset preset = SystemPromptPreset.claudeCode("Be concise.");

        assertThat(preset.type()).isEqualTo("preset");
        assertThat(preset.preset()).isEqualTo("claude_code");
        assertThat(preset.append()).isEqualTo("Be concise.");
    }

    // ==================== ToolsPreset Tests ====================

    @Test
    void toolsPreset_claudeCode() {
        ToolsPreset preset = ToolsPreset.claudeCode();

        assertThat(preset.type()).isEqualTo("preset");
        assertThat(preset.preset()).isEqualTo("claude_code");
    }

    // ==================== SettingSource Tests ====================

    @Test
    void settingSource_values() {
        assertThat(SettingSource.USER.getValue()).isEqualTo("user");
        assertThat(SettingSource.PROJECT.getValue()).isEqualTo("project");
        assertThat(SettingSource.LOCAL.getValue()).isEqualTo("local");
    }

}
