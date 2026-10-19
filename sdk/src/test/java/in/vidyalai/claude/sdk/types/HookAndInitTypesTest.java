package in.vidyalai.claude.sdk.types;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import in.vidyalai.claude.sdk.internal.MessageParser;
import in.vidyalai.claude.sdk.types.hook.input.BackgroundTaskSummary;
import in.vidyalai.claude.sdk.types.hook.input.HookEffort;
import in.vidyalai.claude.sdk.types.hook.input.HookInput;
import in.vidyalai.claude.sdk.types.hook.input.PostToolUseFailureHookInput;
import in.vidyalai.claude.sdk.types.hook.input.PostToolUseHookInput;
import in.vidyalai.claude.sdk.types.hook.input.PreToolUseHookInput;
import in.vidyalai.claude.sdk.types.hook.input.SessionCronSummary;
import in.vidyalai.claude.sdk.types.hook.input.StopHookInput;
import in.vidyalai.claude.sdk.types.hook.input.SubagentStopHookInput;
import in.vidyalai.claude.sdk.types.hook.output.CwdChangedHookSpecificOutput;
import in.vidyalai.claude.sdk.types.hook.output.ElicitationAction;
import in.vidyalai.claude.sdk.types.hook.output.ElicitationHookSpecificOutput;
import in.vidyalai.claude.sdk.types.hook.output.ElicitationResultHookSpecificOutput;
import in.vidyalai.claude.sdk.types.hook.output.FileChangedHookSpecificOutput;
import in.vidyalai.claude.sdk.types.hook.output.HookOutput;
import in.vidyalai.claude.sdk.types.hook.output.HookSpecificOutput;
import in.vidyalai.claude.sdk.types.hook.output.MessageDisplayHookSpecificOutput;
import in.vidyalai.claude.sdk.types.hook.output.PermissionDeniedHookSpecificOutput;
import in.vidyalai.claude.sdk.types.hook.output.PostModelSwitchHookSpecificOutput;
import in.vidyalai.claude.sdk.types.hook.output.PostToolBatchHookSpecificOutput;
import in.vidyalai.claude.sdk.types.hook.output.PreModelSwitchHookSpecificOutput;
import in.vidyalai.claude.sdk.types.hook.output.SessionStartHookSpecificOutput;
import in.vidyalai.claude.sdk.types.hook.output.StopHookSpecificOutput;
import in.vidyalai.claude.sdk.types.hook.output.SubagentStopHookSpecificOutput;
import in.vidyalai.claude.sdk.types.hook.output.UserPromptExpansionHookSpecificOutput;
import in.vidyalai.claude.sdk.types.hook.output.UserPromptSubmitHookSpecificOutput;
import in.vidyalai.claude.sdk.types.hook.output.WorktreeCreateHookSpecificOutput;
import in.vidyalai.claude.sdk.types.message.FastModeDisabledReason;
import in.vidyalai.claude.sdk.types.message.FastModeState;
import in.vidyalai.claude.sdk.types.message.Message;
import in.vidyalai.claude.sdk.types.message.SystemInitData;
import in.vidyalai.claude.sdk.types.message.SystemInitMcpServer;
import in.vidyalai.claude.sdk.types.message.SystemInitPlugin;
import in.vidyalai.claude.sdk.types.message.SystemInitPluginError;
import in.vidyalai.claude.sdk.types.message.SystemMessage;
import in.vidyalai.claude.sdk.types.permission.PermissionDecision;
import in.vidyalai.claude.sdk.types.permission.PermissionMode;

/**
 * Ports Python SDK v0.2.166's hook-specific output types, hook input fields
 * and {@code SystemInitData} tests (PRs #1331, #1332).
 */
class HookAndInitTypesTest {

    /** Required and optional JSON keys of a record, read from its components. */
    private record KeySets(Set<String> required, Set<String> optional) {
    }

    private static KeySets keySets(Class<? extends Record> type) {
        Set<String> required = new HashSet<>();
        Set<String> optional = new HashSet<>();
        for (RecordComponent c : type.getRecordComponents()) {
            // @JsonProperty propagates to the field, not the component
            String key;
            try {
                key = type.getDeclaredField(c.getName()).getAnnotation(JsonProperty.class).value();
            } catch (NoSuchFieldException e) {
                throw new AssertionError(e);
            }
            boolean nullable = c.getAnnotatedType().isAnnotationPresent(Nullable.class);
            (nullable ? optional : required).add(key);
        }
        return new KeySets(required, optional);
    }

    @Nested
    class HookSpecificOutputTypes {

        @Test
        void userPromptSubmitSessionTitleAndSuppress() {
            var output = new UserPromptSubmitHookSpecificOutput(null, "Refactor auth", true);
            assertThat(output.toMap()).isEqualTo(Map.of(
                    "hookEventName", "UserPromptSubmit",
                    "sessionTitle", "Refactor auth",
                    "suppressOriginalPrompt", true));
            // The one-argument constructor still works
            assertThat(new UserPromptSubmitHookSpecificOutput("ctx").toMap())
                    .isEqualTo(Map.of("hookEventName", "UserPromptSubmit", "additionalContext", "ctx"));
        }

        @Test
        void sessionStartOutputFields() {
            var output = new SessionStartHookSpecificOutput(
                    "ctx", "Run the test suite", "auth-refactor", List.of("/repo/.envrc"), true);
            assertThat(output.toMap())
                    .containsEntry("hookEventName", "SessionStart")
                    .containsEntry("watchPaths", List.of("/repo/.envrc"))
                    .containsEntry("reloadSkills", true)
                    .containsEntry("initialUserMessage", "Run the test suite")
                    .containsEntry("sessionTitle", "auth-refactor");
        }

        /**
         * Every field set, then only the required ones: the toMap key sets
         * match the TypeScript SDK's *HookSpecificOutput types.
         */
        @Test
        void outputKeySetsMatchTypeScript() {
            Map<HookSpecificOutput, Set<String>> full = new LinkedHashMap<>();
            full.put(new UserPromptSubmitHookSpecificOutput("c", "t", true),
                    Set.of("additionalContext", "sessionTitle", "suppressOriginalPrompt"));
            full.put(new UserPromptExpansionHookSpecificOutput("c", true),
                    Set.of("additionalContext", "suppressOriginalPrompt"));
            full.put(new SessionStartHookSpecificOutput("c", "m", "t", List.of("/p"), true),
                    Set.of("additionalContext", "initialUserMessage", "sessionTitle", "watchPaths",
                            "reloadSkills"));
            full.put(new PostToolBatchHookSpecificOutput("c"), Set.of("additionalContext"));
            full.put(new StopHookSpecificOutput("c"), Set.of("additionalContext"));
            full.put(new SubagentStopHookSpecificOutput("c"), Set.of("additionalContext"));
            full.put(new PermissionDeniedHookSpecificOutput(true), Set.of("retry"));
            full.put(new PreModelSwitchHookSpecificOutput(PermissionDecision.DENY, "r"),
                    Set.of("permissionDecision", "permissionDecisionReason"));
            full.put(new PostModelSwitchHookSpecificOutput("c"), Set.of("additionalContext"));
            full.put(new ElicitationHookSpecificOutput(ElicitationAction.ACCEPT, Map.of("k", "v")),
                    Set.of("action", "content"));
            full.put(new ElicitationResultHookSpecificOutput(ElicitationAction.DECLINE, Map.of()),
                    Set.of("action", "content"));
            full.put(new CwdChangedHookSpecificOutput(List.of("/p")), Set.of("watchPaths"));
            full.put(new FileChangedHookSpecificOutput(List.of("/p")), Set.of("watchPaths"));
            full.put(new WorktreeCreateHookSpecificOutput("/wt"), Set.of("worktreePath"));
            full.put(new MessageDisplayHookSpecificOutput("shown"), Set.of("displayContent"));

            for (var e : full.entrySet()) {
                Set<String> expected = new HashSet<>(e.getValue());
                expected.add("hookEventName");
                assertThat(e.getKey().toMap().keySet()).as(e.getKey().getClass().getSimpleName())
                        .isEqualTo(expected);
            }

            // Only WorktreeCreate has a required field besides hookEventName
            assertThat(new StopHookSpecificOutput(null).toMap()).isEqualTo(Map.of("hookEventName", "Stop"));
            assertThat(new PreModelSwitchHookSpecificOutput(null, null).toMap())
                    .isEqualTo(Map.of("hookEventName", "PreModelSwitch"));
            assertThat(new WorktreeCreateHookSpecificOutput("/wt").toMap())
                    .isEqualTo(Map.of("hookEventName", "WorktreeCreate", "worktreePath", "/wt"));
            assertThatThrownBy(() -> new WorktreeCreateHookSpecificOutput(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void outputLiterals() {
            assertThat(Arrays.stream(ElicitationAction.values()).map(ElicitationAction::getValue))
                    .containsExactlyInAnyOrder("accept", "decline", "cancel");
            assertThat(new ElicitationHookSpecificOutput(ElicitationAction.CANCEL, null).toMap())
                    .containsEntry("action", "cancel");
            for (PermissionDecision d : List.of(PermissionDecision.ALLOW, PermissionDecision.DENY,
                    PermissionDecision.ASK)) {
                assertThat(new PreModelSwitchHookSpecificOutput(d, null).toMap())
                        .containsEntry("permissionDecision", d.getValue());
            }
            assertThatThrownBy(() -> new PreModelSwitchHookSpecificOutput(PermissionDecision.DEFER, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void everyPermittedOutputIsARegisteredSubtypeWithADistinctEventName() throws Exception {
            Map<Class<?>, String> registered = Arrays
                    .stream(HookSpecificOutput.class.getAnnotation(JsonSubTypes.class).value())
                    .collect(Collectors.toMap(JsonSubTypes.Type::value, JsonSubTypes.Type::name));
            Class<?>[] permitted = HookSpecificOutput.class.getPermittedSubclasses();
            assertThat(registered.keySet()).containsExactlyInAnyOrder(permitted);
            assertThat(new HashSet<>(registered.values())).hasSize(permitted.length);
            assertThat(permitted).hasSize(21);

            // The registered name is what hookEventName() reports
            for (Class<?> type : permitted) {
                RecordComponent[] comps = type.getRecordComponents();
                Object[] args = new Object[comps.length];
                for (int i = 0; i < comps.length; i++) {
                    Class<?> t = comps[i].getType();
                    args[i] = t == String.class ? "x" : t == Map.class ? Map.of() : null;
                }
                var ctor = type.getDeclaredConstructor(
                        Arrays.stream(comps).map(RecordComponent::getType).toArray(Class<?>[]::new));
                HookSpecificOutput output = (HookSpecificOutput) ctor.newInstance(args);
                assertThat(output.hookEventName()).as(type.getSimpleName()).isEqualTo(registered.get(type));
            }
        }

        @Test
        void nestsInHookOutput() {
            HookOutput output = HookOutput.builder()
                    .hookSpecificOutput(new StopHookSpecificOutput("keep going: tests still fail"))
                    .build();
            assertThat(output.toMap()).containsEntry("hookSpecificOutput", Map.of(
                    "hookEventName", "Stop",
                    "additionalContext", "keep going: tests still fail"));
        }

    }

    @Nested
    class HookInputFieldParity {

        private final ObjectMapper mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        private Map<String, Object> base(String event) {
            Map<String, Object> m = new HashMap<>();
            m.put("session_id", "sess-1");
            m.put("transcript_path", "/tmp/transcript");
            m.put("cwd", "/home/user");
            m.put("hook_event_name", event);
            return m;
        }

        @Test
        void baseHookInputPromptIdAndEffort() {
            Map<String, Object> data = base("PreToolUse");
            data.put("prompt_id", "550e8400-e29b-41d4-a716-446655440000");
            data.put("effort", Map.of("level", "high"));
            data.put("tool_name", "Bash");
            data.put("tool_input", Map.of("command", "ls"));
            data.put("tool_use_id", "toolu_1");

            HookInput input = mapper.convertValue(data, HookInput.class);
            assertThat(input).isInstanceOf(PreToolUseHookInput.class);
            assertThat(input.promptId()).isEqualTo("550e8400-e29b-41d4-a716-446655440000");
            assertThat(input.effort()).isEqualTo(new HookEffort("high"));
        }

        @Test
        void baseHookInputNewFieldsAreOptional() {
            Map<String, Object> data = base("UserPromptSubmit");
            data.put("prompt", "hi");
            HookInput input = mapper.convertValue(data, HookInput.class);
            assertThat(input.promptId()).isNull();
            assertThat(input.effort()).isNull();

            // BaseHookInput's new keys are optional on every hook input
            @SuppressWarnings("unchecked")
            Class<? extends Record>[] inputs = (Class<? extends Record>[]) HookInput.class.getPermittedSubclasses();
            assertThat(inputs).hasSize(10);
            for (Class<? extends Record> type : inputs) {
                assertThat(keySets(type).optional()).as(type.getSimpleName()).contains("prompt_id", "effort");
            }
        }

        @Test
        void postToolUseDurationMs() {
            Map<String, Object> data = base("PostToolUse");
            data.put("tool_name", "Bash");
            data.put("tool_input", Map.of("command", "ls"));
            data.put("tool_response", Map.of("stdout", ""));
            data.put("tool_use_id", "toolu_1");
            data.put("duration_ms", 12);
            var input = (PostToolUseHookInput) mapper.convertValue(data, HookInput.class);
            assertThat(input.durationMs()).isEqualTo(12);

            Map<String, Object> failure = base("PostToolUseFailure");
            failure.put("tool_name", "Bash");
            failure.put("tool_input", Map.of());
            failure.put("tool_use_id", "toolu_1");
            failure.put("error", "boom");
            var failed = (PostToolUseFailureHookInput) mapper.convertValue(failure, HookInput.class);
            assertThat(failed.durationMs()).isNull();
            assertThat(keySets(PostToolUseFailureHookInput.class).optional()).contains("duration_ms");
            failure.put("duration_ms", 7);
            assertThat(((PostToolUseFailureHookInput) mapper.convertValue(failure, HookInput.class)).durationMs())
                    .isEqualTo(7);
        }

        @Test
        void stopHookInputBackgroundWork() {
            Map<String, Object> data = base("Stop");
            data.put("stop_hook_active", false);
            data.put("last_assistant_message", "Done.");
            data.put("background_tasks", List.of(Map.of(
                    "id", "task-1",
                    "type", "shell",
                    "status", "running",
                    "description", "npm test",
                    "command", "npm test")));
            data.put("session_crons", List.of(Map.of(
                    "id", "cron-1",
                    "schedule", "0 9 * * 1-5",
                    "recurring", true,
                    "prompt", "check CI")));

            var input = (StopHookInput) mapper.convertValue(data, HookInput.class);
            assertThat(input.lastAssistantMessage()).isEqualTo("Done.");
            assertThat(input.backgroundTasks()).singleElement()
                    .satisfies(t -> {
                        assertThat(t.command()).isEqualTo("npm test");
                        assertThat(t.agentType()).isNull();
                    });
            assertThat(input.sessionCrons()).singleElement()
                    .satisfies(c -> assertThat(c.recurring()).isTrue());
        }

        @Test
        void subagentStopHookInputFieldsAreOptional() {
            Map<String, Object> data = base("SubagentStop");
            data.put("stop_hook_active", true);
            data.put("agent_id", "a1");
            data.put("agent_transcript_path", "/tmp/a1");
            data.put("agent_type", "general-purpose");
            var input = (SubagentStopHookInput) mapper.convertValue(data, HookInput.class);
            assertThat(input.lastAssistantMessage()).isNull();
            assertThat(input.backgroundTasks()).isNull();
            assertThat(input.sessionCrons()).isNull();

            for (String key : List.of("last_assistant_message", "background_tasks", "session_crons")) {
                assertThat(keySets(StopHookInput.class).optional()).contains(key);
                assertThat(keySets(SubagentStopHookInput.class).optional()).contains(key);
            }
        }

        @Test
        void backgroundWorkSummaryKeySets() {
            assertThat(keySets(BackgroundTaskSummary.class)).isEqualTo(new KeySets(
                    Set.of("id", "type", "status", "description"),
                    Set.of("command", "agent_type", "server", "tool", "name")));
            assertThat(keySets(SessionCronSummary.class)).isEqualTo(new KeySets(
                    Set.of("id", "schedule", "recurring", "prompt"), Set.of()));
            assertThat(keySets(HookEffort.class)).isEqualTo(new KeySets(Set.of("level"), Set.of()));
        }

        @Test
        void legacyConstructorsLeaveNewFieldsNull() {
            var input = new StopHookInput("s", "/t", "/c", null, false);
            assertThat(input.lastAssistantMessage()).isNull();
            assertThat(input.promptId()).isNull();
            assertThat(input.effort()).isNull();
        }

    }

    @Nested
    class SystemInitDataTypes {

        @Test
        void keySetsMatchTypeScript() {
            assertThat(keySets(SystemInitData.class)).isEqualTo(new KeySets(
                    Set.of("type", "subtype", "uuid", "session_id", "apiKeySource", "claude_code_version",
                            "cwd", "tools", "mcp_servers", "model", "permissionMode", "slash_commands",
                            "output_style", "skills", "plugins"),
                    Set.of("agents", "betas", "terminal_slash_commands", "plugin_errors", "fast_mode_state",
                            "fast_mode_disabled_reason", "capabilities")));
        }

        @Test
        void nestedKeySets() {
            assertThat(keySets(SystemInitMcpServer.class))
                    .isEqualTo(new KeySets(Set.of("name", "status"), Set.of("source")));
            assertThat(keySets(SystemInitPlugin.class))
                    .isEqualTo(new KeySets(Set.of("name", "path"), Set.of("version")));
            assertThat(keySets(SystemInitPluginError.class))
                    .isEqualTo(new KeySets(Set.of("plugin", "type", "message"), Set.of("path")));
        }

        @Test
        void fastModeLiterals() {
            assertThat(Arrays.stream(FastModeState.values()).map(FastModeState::getValue))
                    .containsExactlyInAnyOrder("off", "cooldown", "on");
            assertThat(Arrays.stream(FastModeDisabledReason.values()).map(FastModeDisabledReason::getValue))
                    .contains("sdk_opt_in_required");
        }

        private Map<String, Object> initFrame() {
            Map<String, Object> data = new HashMap<>();
            data.put("type", "system");
            data.put("subtype", "init");
            data.put("uuid", "u1");
            data.put("session_id", "s1");
            data.put("apiKeySource", "none");
            data.put("claude_code_version", "2.1.284");
            data.put("cwd", "/repo");
            data.put("tools", List.of("Bash"));
            data.put("mcp_servers", List.of(Map.of("name", "docs", "status", "connected")));
            data.put("model", "claude-sonnet-5");
            data.put("permissionMode", "default");
            data.put("slash_commands", List.of("compact"));
            data.put("output_style", "default");
            data.put("skills", List.of());
            data.put("plugins", List.of());
            return data;
        }

        @Test
        void initFrameStillParsesToPlainSystemMessage() {
            // Typing the payload does not change what the parser returns
            Map<String, Object> data = initFrame();
            Message message = MessageParser.parse(data);
            assertThat(message).isExactlyInstanceOf(SystemMessage.class);
            SystemMessage sys = (SystemMessage) message;
            assertThat(sys.data()).isEqualTo(data);

            SystemInitData init = sys.initData();
            assertThat(init).isNotNull();
            assertThat(init.mcpServers().get(0).status()).isEqualTo("connected");
            assertThat(init.mcpServers().get(0).source()).isNull();
            assertThat(init.permissionMode()).isEqualTo(PermissionMode.DEFAULT);
            assertThat(init.claudeCodeVersion()).isEqualTo("2.1.284");
            assertThat(init.capabilities()).isNull();
        }

        @Test
        void optionalFieldsAndUnknownEnumValues() {
            Map<String, Object> data = initFrame();
            data.put("capabilities", List.of("interrupt_receipt_v1"));
            data.put("fast_mode_state", "cooldown");
            data.put("fast_mode_disabled_reason", "some_future_reason");
            data.put("plugin_errors", List.of(Map.of(
                    "plugin", "inline[0]", "type", "path-not-found", "message", "missing", "path", "/p")));
            data.put("a_future_key", 1);

            SystemInitData init = SystemInitData.from(data);
            assertThat(init.capabilities()).containsExactly("interrupt_receipt_v1");
            assertThat(init.fastModeState()).isEqualTo(FastModeState.COOLDOWN);
            // Unrecognized values read as null; the raw value stays in data()
            assertThat(init.fastModeDisabledReason()).isNull();
            assertThat(init.pluginErrors()).singleElement()
                    .satisfies(e -> assertThat(e.path()).isEqualTo("/p"));
        }

        @Test
        void initDataIsNullForOtherSubtypes() {
            assertThat(new SystemMessage("commands_changed", Map.of()).initData()).isNull();
        }

    }

}
