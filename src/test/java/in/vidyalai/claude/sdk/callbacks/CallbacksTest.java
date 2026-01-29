package in.vidyalai.claude.sdk.callbacks;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.types.config.CompactTriggerType;
import in.vidyalai.claude.sdk.types.hook.HookContext;
import in.vidyalai.claude.sdk.types.hook.HookEvent;
import in.vidyalai.claude.sdk.types.hook.HookInput;
import in.vidyalai.claude.sdk.types.hook.HookMatcher;
import in.vidyalai.claude.sdk.types.hook.HookOutput;
import in.vidyalai.claude.sdk.types.hook.HookSpecificOutput;
import in.vidyalai.claude.sdk.types.hook.PostToolUseHookInput;
import in.vidyalai.claude.sdk.types.hook.PreCompactHookInput;
import in.vidyalai.claude.sdk.types.hook.PreToolUseHookInput;
import in.vidyalai.claude.sdk.types.hook.StopHookInput;
import in.vidyalai.claude.sdk.types.hook.SubagentStopHookInput;
import in.vidyalai.claude.sdk.types.hook.UserPromptSubmitHookInput;
import in.vidyalai.claude.sdk.types.permission.PermissionDecision;
import in.vidyalai.claude.sdk.types.permission.PermissionMode;
import in.vidyalai.claude.sdk.types.permission.PermissionResultAllow;
import in.vidyalai.claude.sdk.types.permission.PermissionResultDeny;
import in.vidyalai.claude.sdk.types.permission.PermissionUpdate;
import in.vidyalai.claude.sdk.types.permission.PermissionUpdateDestination;
import in.vidyalai.claude.sdk.types.permission.ToolPermissionContext;

/**
 * Tests for tool permission callbacks and hooks.
 * Equivalent to Python's test_tool_callbacks.py
 */
class CallbacksTest {

    // ==================== Permission Callback Tests ====================

    @Test
    void testPermissionCallbackAllow() {
        ClaudeAgentOptions.CanUseTool callback = (toolName, input, context) -> CompletableFuture
                .completedFuture(new PermissionResultAllow());

        var result = callback.apply("Bash", Map.of("command", "ls"), new ToolPermissionContext());

        assertThat(result.join()).isInstanceOf(PermissionResultAllow.class);
    }

    @Test
    void testPermissionCallbackDeny() {
        ClaudeAgentOptions.CanUseTool callback = (toolName, input, context) -> CompletableFuture.completedFuture(
                new PermissionResultDeny("Tool not allowed", false));

        var result = callback.apply("Bash", Map.of(), new ToolPermissionContext()).join();

        assertThat(result).isInstanceOf(PermissionResultDeny.class);
        PermissionResultDeny deny = (PermissionResultDeny) result;
        assertThat(deny.message()).isEqualTo("Tool not allowed");
        assertThat(deny.interrupt()).isFalse();
    }

    @Test
    void testPermissionCallbackDenyWithInterrupt() {
        ClaudeAgentOptions.CanUseTool callback = (toolName, input, context) -> CompletableFuture.completedFuture(
                new PermissionResultDeny("Critical error", true));

        var result = callback.apply("Bash", Map.of(), new ToolPermissionContext()).join();

        assertThat(result).isInstanceOf(PermissionResultDeny.class);
        PermissionResultDeny deny = (PermissionResultDeny) result;
        assertThat(deny.interrupt()).isTrue();
    }

    @SuppressWarnings("null")
    @Test
    void testPermissionCallbackInputModification() {
        ClaudeAgentOptions.CanUseTool callback = (toolName, input, context) -> {
            // Modify the input
            Map<String, Object> modifiedInput = new java.util.HashMap<>(input);
            modifiedInput.put("safe_mode", true);
            return CompletableFuture.completedFuture(
                    new PermissionResultAllow(modifiedInput, null));
        };

        var result = callback.apply("Bash", Map.of("command", "rm -rf"), new ToolPermissionContext()).join();

        assertThat(result).isInstanceOf(PermissionResultAllow.class);
        PermissionResultAllow allow = (PermissionResultAllow) result;
        assertThat(allow.updatedInput()).containsEntry("safe_mode", true);
        assertThat(allow.updatedInput()).containsEntry("command", "rm -rf");
    }

    @SuppressWarnings("null")
    @Test
    void testPermissionCallbackWithPermissionUpdates() {
        ClaudeAgentOptions.CanUseTool callback = (toolName, input, context) -> {
            List<PermissionUpdate> updates = List.of(
                    PermissionUpdate.setMode(PermissionMode.BYPASS_PERMISSIONS, PermissionUpdateDestination.SESSION));
            return CompletableFuture.completedFuture(
                    new PermissionResultAllow(null, updates));
        };

        var result = callback.apply("Bash", Map.of(), new ToolPermissionContext()).join();

        assertThat(result).isInstanceOf(PermissionResultAllow.class);
        PermissionResultAllow allow = (PermissionResultAllow) result;
        assertThat(allow.updatedPermissions()).hasSize(1);
    }

    @SuppressWarnings("null")
    @Test
    void testPermissionCallbackReceivesToolName() {
        AtomicReference<String> capturedToolName = new AtomicReference<>();

        ClaudeAgentOptions.CanUseTool callback = (toolName, input, context) -> {
            capturedToolName.set(toolName);
            return CompletableFuture.completedFuture(new PermissionResultAllow());
        };

        callback.apply("CustomTool", Map.of(), new ToolPermissionContext()).join();

        assertThat(capturedToolName.get()).isEqualTo("CustomTool");
    }

    @SuppressWarnings("null")
    @Test
    void testPermissionCallbackReceivesInput() {
        AtomicReference<Map<String, Object>> capturedInput = new AtomicReference<>();

        ClaudeAgentOptions.CanUseTool callback = (toolName, input, context) -> {
            capturedInput.set(input);
            return CompletableFuture.completedFuture(new PermissionResultAllow());
        };

        Map<String, Object> testInput = Map.of("key1", "value1", "key2", 42);
        callback.apply("Tool", testInput, new ToolPermissionContext()).join();

        assertThat(capturedInput.get()).containsEntry("key1", "value1");
        assertThat(capturedInput.get()).containsEntry("key2", 42);
    }

    @SuppressWarnings("null")
    @Test
    void testPermissionCallbackReceivesContext() {
        AtomicReference<ToolPermissionContext> capturedContext = new AtomicReference<>();

        ClaudeAgentOptions.CanUseTool callback = (toolName, input, context) -> {
            capturedContext.set(context);
            return CompletableFuture.completedFuture(new PermissionResultAllow());
        };

        List<PermissionUpdate> suggestions = List.of(
                PermissionUpdate.setMode(PermissionMode.ACCEPT_EDITS, PermissionUpdateDestination.SESSION));
        ToolPermissionContext context = new ToolPermissionContext(null, suggestions);
        callback.apply("Tool", Map.of(), context).join();

        assertThat(capturedContext.get().suggestions()).hasSize(1);
    }

    @Test
    void testPermissionCallbackAsync() {
        ClaudeAgentOptions.CanUseTool callback = (toolName, input, context) -> CompletableFuture.supplyAsync(() -> {
            try {
                Thread.sleep(10); // Simulate async work
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new PermissionResultAllow();
        });

        var result = callback.apply("Bash", Map.of(), new ToolPermissionContext()).join();

        assertThat(result).isInstanceOf(PermissionResultAllow.class);
    }

    // ==================== Hook Tests ====================

    @Test
    void testHookCallbackExecution() {
        AtomicBoolean hookCalled = new AtomicBoolean(false);

        HookMatcher.HookCallback callback = (input, context) -> {
            hookCalled.set(true);
            return CompletableFuture.completedFuture(HookOutput.empty());
        };

        callback.apply(
                new PreToolUseHookInput("session", "/path", "/cwd", "default", "Bash", Map.of()),
                new HookContext("tool-use-123")).join();

        assertThat(hookCalled.get()).isTrue();
    }

    @Test
    void testHookCallbackReceivesInput() {
        AtomicReference<HookInput> capturedInput = new AtomicReference<>();

        HookMatcher.HookCallback callback = (input, context) -> {
            capturedInput.set(input);
            return CompletableFuture.completedFuture(HookOutput.empty());
        };

        PreToolUseHookInput input = new PreToolUseHookInput(
                "session-123", "/transcript", "/cwd", "bypassPermissions",
                "Bash", Map.of("command", "ls"));
        callback.apply(input, new HookContext("tool-123")).join();

        assertThat(capturedInput.get()).isInstanceOf(PreToolUseHookInput.class);
        PreToolUseHookInput captured = (PreToolUseHookInput) capturedInput.get();
        assertThat(captured.toolName()).isEqualTo("Bash");
        assertThat(captured.toolInput()).containsEntry("command", "ls");
        assertThat(captured.sessionId()).isEqualTo("session-123");
    }

    @Test
    void testHookCallbackReceivesContext() {
        AtomicReference<HookContext> capturedContext = new AtomicReference<>();

        HookMatcher.HookCallback callback = (input, context) -> {
            capturedContext.set(context);
            return CompletableFuture.completedFuture(HookOutput.empty());
        };

        HookContext context = new HookContext("tool-use-456");
        callback.apply(
                new PreToolUseHookInput("s", "/t", "/c", "d", "Tool", Map.of()),
                context).join();

        assertThat(capturedContext.get().toolUseId()).isEqualTo("tool-use-456");
    }

    @Test
    void testHookOutputWithReason() {
        HookOutput output = HookOutput.builder()
                .reason("Approved by policy")
                .build();

        Map<String, Object> map = output.toMap();

        assertThat(map).containsEntry("reason", "Approved by policy");
    }

    @Test
    void testHookOutputEmpty() {
        HookOutput output = HookOutput.empty();

        Map<String, Object> map = output.toMap();

        // Empty output should have minimal fields
        assertThat(map).doesNotContainKey("reason");
    }

    @Test
    void testHookSpecificOutputPreToolUse() {
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
    void testHookSpecificOutputPostToolUse() {
        HookSpecificOutput output = HookSpecificOutput.postToolUse()
                .additionalContext("Additional context here")
                .build();

        Map<String, Object> map = output.toMap();

        assertThat(map).containsEntry("hookEventName", "PostToolUse");
        assertThat(map).containsEntry("additionalContext", "Additional context here");
    }

    @Test
    void testHookSpecificOutputUserPromptSubmit() {
        HookSpecificOutput output = HookSpecificOutput.userPromptSubmit()
                .additionalContext("Modified prompt context")
                .build();

        Map<String, Object> map = output.toMap();

        assertThat(map).containsEntry("hookEventName", "UserPromptSubmit");
        assertThat(map).containsEntry("additionalContext", "Modified prompt context");
    }

    @Test
    void testHookMatcherCreation() {
        HookMatcher.HookCallback callback = (input, context) -> CompletableFuture.completedFuture(HookOutput.empty());

        HookMatcher matcher = new HookMatcher("Bash", List.of(callback));

        assertThat(matcher.matcher()).isEqualTo("Bash");
        assertThat(matcher.hooks()).hasSize(1);
        assertThat(matcher.timeoutSeconds()).isNull();
    }

    @Test
    void testHookMatcherWithTimeout() {
        HookMatcher.HookCallback callback = (input, context) -> CompletableFuture.completedFuture(HookOutput.empty());

        HookMatcher matcher = new HookMatcher("Bash", List.of(callback), 30.0);

        assertThat(matcher.timeoutSeconds()).isEqualTo(30.0);
    }

    @Test
    void testHookMatcherWithMultipleCallbacks() {
        HookMatcher.HookCallback callback1 = (input, context) -> CompletableFuture
                .completedFuture(HookOutput.builder().reason("callback1").build());
        HookMatcher.HookCallback callback2 = (input, context) -> CompletableFuture
                .completedFuture(HookOutput.builder().reason("callback2").build());

        HookMatcher matcher = new HookMatcher("*", List.of(callback1, callback2));

        assertThat(matcher.hooks()).hasSize(2);
    }

    // ==================== Integration Tests ====================

    @Test
    void testOptionsWithCanUseTool() {
        ClaudeAgentOptions.CanUseTool callback = (toolName, input, context) -> CompletableFuture
                .completedFuture(new PermissionResultAllow());

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .canUseTool(callback)
                .build();

        assertThat(options.canUseTool()).isNotNull();
    }

    @Test
    void testOptionsWithHooks() {
        HookMatcher.HookCallback callback = (input, context) -> CompletableFuture.completedFuture(HookOutput.empty());

        HookMatcher matcher = new HookMatcher("Bash", List.of(callback));

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .hooks(Map.of(HookEvent.PRE_TOOL_USE, List.of(matcher)))
                .build();

        assertThat(options.hooks()).containsKey(HookEvent.PRE_TOOL_USE);
    }

    @Test
    void testOptionsWithMultipleHookEvents() {
        HookMatcher.HookCallback callback = (input, context) -> CompletableFuture.completedFuture(HookOutput.empty());

        HookMatcher preToolMatcher = new HookMatcher("*", List.of(callback));
        HookMatcher postToolMatcher = new HookMatcher("*", List.of(callback));
        HookMatcher stopMatcher = new HookMatcher(null, List.of(callback));

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .hooks(Map.of(
                        HookEvent.PRE_TOOL_USE, List.of(preToolMatcher),
                        HookEvent.POST_TOOL_USE, List.of(postToolMatcher),
                        HookEvent.STOP, List.of(stopMatcher)))
                .build();

        assertThat(options.hooks()).hasSize(3);
    }

    @Test
    void testHookInputTypes() {
        // Test all hook input types
        PreToolUseHookInput preToolUse = new PreToolUseHookInput(
                "s", "/t", "/c", "d", "Tool", Map.of());
        assertThat(preToolUse.hookEventName()).isEqualTo("PreToolUse");

        PostToolUseHookInput postToolUse = new PostToolUseHookInput(
                "s", "/t", "/c", "d", "Tool", Map.of(), "result");
        assertThat(postToolUse.hookEventName()).isEqualTo("PostToolUse");
        assertThat(postToolUse.toolResponse()).isEqualTo("result");

        UserPromptSubmitHookInput userPrompt = new UserPromptSubmitHookInput(
                "s", "/t", "/c", "d", "Hello");
        assertThat(userPrompt.hookEventName()).isEqualTo("UserPromptSubmit");
        assertThat(userPrompt.prompt()).isEqualTo("Hello");

        StopHookInput stop = new StopHookInput("s", "/t", "/c", "d", true);
        assertThat(stop.hookEventName()).isEqualTo("Stop");
        assertThat(stop.stopHookActive()).isTrue();

        SubagentStopHookInput subagentStop = new SubagentStopHookInput("s", "/t", "/c", "d", false);
        assertThat(subagentStop.hookEventName()).isEqualTo("SubagentStop");

        PreCompactHookInput preCompact = new PreCompactHookInput(
                "s", "/t", "/c", "d", CompactTriggerType.MANUAL, "custom instructions");
        assertThat(preCompact.hookEventName()).isEqualTo("PreCompact");
        assertThat(preCompact.trigger()).isEqualTo(CompactTriggerType.MANUAL);
        assertThat(preCompact.customInstructions()).isEqualTo("custom instructions");
    }

}
