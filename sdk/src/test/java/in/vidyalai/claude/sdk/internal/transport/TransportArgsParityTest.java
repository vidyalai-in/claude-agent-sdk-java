package in.vidyalai.claude.sdk.internal.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;

import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.exceptions.CLIConnectionException;
import in.vidyalai.claude.sdk.mcp.McpMessageHandler;
import in.vidyalai.claude.sdk.types.config.AgentDefinition;
import in.vidyalai.claude.sdk.types.config.EffortLevel;
import in.vidyalai.claude.sdk.types.config.SandboxNetworkConfig;
import in.vidyalai.claude.sdk.types.config.SandboxSettings;
import in.vidyalai.claude.sdk.types.config.ThinkingConfigEnabled;
import in.vidyalai.claude.sdk.types.mcp.McpHttpServerConfig;
import in.vidyalai.claude.sdk.types.mcp.McpSdkServerConfig;
import in.vidyalai.claude.sdk.types.mcp.McpServerConfig;
import in.vidyalai.claude.sdk.types.mcp.McpServerStatusConfig;
import in.vidyalai.claude.sdk.types.mcp.McpSseServerConfig;
import in.vidyalai.claude.sdk.types.mcp.McpStdioServerConfig;

/**
 * The argv and JSON the transport hands the CLI, compared with what the Python
 * SDK builds for the same options.
 */
@SuppressWarnings("null")
class TransportArgsParityTest {

    private static final ObjectMapper PLAIN = new ObjectMapper();

    private static List<String> command(Consumer<ClaudeAgentOptions.Builder> configure) {
        ClaudeAgentOptions.Builder builder = ClaudeAgentOptions.builder().cliPath(Path.of("/usr/bin/claude"));
        configure.accept(builder);
        SubprocessCLITransport transport = new SubprocessCLITransport(builder.build());
        try {
            return transport.buildCommand();
        } finally {
            transport.close();
        }
    }

    private static String valueOf(List<String> cmd, String flag) {
        int idx = cmd.indexOf(flag);
        assertThat(idx).as("%s in %s", flag, cmd).isGreaterThanOrEqualTo(0);
        return cmd.get(idx + 1);
    }

    private static String mcpConfig(Map<String, McpServerConfig> servers) {
        return valueOf(command(b -> b.mcpServers(servers)), "--mcp-config");
    }

    private static String settings(Consumer<ClaudeAgentOptions.Builder> configure) {
        return valueOf(command(configure), "--settings");
    }

    // ---- #1: unset fields are omitted, not sent as null ---------------------

    @Test
    void stdioConfigWithCommandOnly() {
        assertThat(mcpConfig(Map.of("s", new McpStdioServerConfig("npx"))))
                .isEqualTo("{\"mcpServers\":{\"s\":{\"type\":\"stdio\",\"command\":\"npx\"}}}");
    }

    @Test
    void stdioConfigWithCommandAndArgs() {
        assertThat(mcpConfig(Map.of("s", new McpStdioServerConfig("npx", List.of("-y", "srv")))))
                .isEqualTo("{\"mcpServers\":{\"s\":{\"type\":\"stdio\",\"command\":\"npx\",\"args\":[\"-y\",\"srv\"]}}}");
    }

    @Test
    void stdioConfigWithEverything() {
        assertThat(mcpConfig(Map.of("s", new McpStdioServerConfig("npx", List.of("a"), Map.of("K", "V")))))
                .isEqualTo("{\"mcpServers\":{\"s\":{\"type\":\"stdio\",\"command\":\"npx\",\"args\":[\"a\"],"
                        + "\"env\":{\"K\":\"V\"}}}}");
    }

    @Test
    void httpConfigs() {
        assertThat(mcpConfig(Map.of("h", new McpHttpServerConfig("https://x"))))
                .isEqualTo("{\"mcpServers\":{\"h\":{\"type\":\"http\",\"url\":\"https://x\"}}}");
        assertThat(mcpConfig(Map.of("h", new McpHttpServerConfig("https://x", Map.of("A", "b")))))
                .isEqualTo("{\"mcpServers\":{\"h\":{\"type\":\"http\",\"url\":\"https://x\",\"headers\":{\"A\":\"b\"}}}}");
    }

    @Test
    void sseConfigs() {
        assertThat(mcpConfig(Map.of("e", new McpSseServerConfig("https://x"))))
                .isEqualTo("{\"mcpServers\":{\"e\":{\"type\":\"sse\",\"url\":\"https://x\"}}}");
        assertThat(mcpConfig(Map.of("e", new McpSseServerConfig("https://x", Map.of("A", "b")))))
                .isEqualTo("{\"mcpServers\":{\"e\":{\"type\":\"sse\",\"url\":\"https://x\",\"headers\":{\"A\":\"b\"}}}}");
    }

    @Test
    void sdkConfigSendsOnlyTypeAndName() {
        McpMessageHandler handler = message -> CompletableFuture.completedFuture(null);
        assertThat(mcpConfig(Map.of("tools", new McpSdkServerConfig("tools", handler))))
                .isEqualTo("{\"mcpServers\":{\"tools\":{\"type\":\"sdk\",\"name\":\"tools\"}}}");
    }

    @Test
    void sandboxWithEnabledOnly() {
        assertThat(settings(b -> b.sandbox(new SandboxSettings(true))))
                .isEqualTo("{\"sandbox\":{\"enabled\":true}}");
    }

    @Test
    void sandboxWithNothingSet() {
        assertThat(settings(b -> b.sandbox(new SandboxSettings())))
                .isEqualTo("{\"sandbox\":{}}");
    }

    @Test
    void sandboxNetworkOmitsUnsetFields() {
        SandboxNetworkConfig network = new SandboxNetworkConfig(List.of("/tmp/ssh.sock"), null, true, null, null);
        SandboxSettings sandbox = new SandboxSettings(true, null, null, null, network, null, null);
        assertThat(settings(b -> b.sandbox(sandbox)))
                .isEqualTo("{\"sandbox\":{\"enabled\":true,\"network\":{\"allowUnixSockets\":[\"/tmp/ssh.sock\"],"
                        + "\"allowLocalBinding\":true}}}");
    }

    @Test
    void userSettingsKeepTheirOwnNulls() {
        // Python's json.dumps re-serializes the user's settings as parsed,
        // nulls included; only the SDK's own records drop unset fields.
        assertThat(settings(b -> b.settings("{\"model\":null,\"a\":1}").sandbox(new SandboxSettings(true))))
                .isEqualTo("{\"model\":null,\"a\":1,\"sandbox\":{\"enabled\":true}}");
    }

    @Test
    void mcpConfigInsideAgentDefinitionKeepsItsType() throws Exception {
        AgentDefinition agent = new AgentDefinition("d", "p", null, null, null, null, null,
                List.of("named", Map.of("inline", new McpStdioServerConfig("npx")), new McpHttpServerConfig("https://x")),
                null, null, null, null, null);
        String expected = "{\"description\":\"d\",\"prompt\":\"p\",\"mcpServers\":[\"named\","
                + "{\"inline\":{\"type\":\"stdio\",\"command\":\"npx\"}},{\"type\":\"http\",\"url\":\"https://x\"}]}";

        assertThat(PLAIN.writeValueAsString(agent)).isEqualTo(expected);
        // As the control channel serializes the initialize request
        ObjectMapper control = new ObjectMapper()
                .setDefaultPropertyInclusion(JsonInclude.Value.construct(
                        JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL));
        assertThat(control.writeValueAsString(Map.of("a", agent))).isEqualTo("{\"a\":" + expected + "}");
    }

    @Test
    void statusConfigsStillParseAndSerializeOneTypeKey() throws Exception {
        McpServerStatusConfig parsed = PLAIN.readValue("{\"type\":\"http\",\"url\":\"u\"}", McpServerStatusConfig.class);
        assertThat(parsed).isEqualTo(new McpHttpServerConfig("u"));
        assertThat(PLAIN.readValue("{\"command\":\"c\"}", McpServerStatusConfig.class))
                .isEqualTo(new McpStdioServerConfig("c"));
        assertThat(PLAIN.writeValueAsString(parsed)).isEqualTo("{\"type\":\"http\",\"url\":\"u\"}");
        assertThat(PLAIN.readValue("{\"type\":\"sse\",\"url\":\"u\"}", McpServerConfig.class))
                .isEqualTo(new McpSseServerConfig("u"));
    }

    // ---- #36: numeric agent effort -------------------------------------------

    @Test
    void numericAgentEffortIsAJsonNumber() throws Exception {
        AgentDefinition agent = new AgentDefinition("test", "p").withEffort(32000);

        assertThat(PLAIN.readTree(PLAIN.writeValueAsString(agent)).get("effort").isInt()).isTrue();
        assertThat(PLAIN.writeValueAsString(agent)).contains("\"effort\":32000");
        assertThat(agent.numericEffort()).isEqualTo(32000);
        assertThat(agent.effort()).isNull();
    }

    @Test
    void levelAgentEffortIsAJsonString() throws Exception {
        AgentDefinition high = new AgentDefinition("test", "p").withEffort("high");
        AgentDefinition xhigh = new AgentDefinition("test", "p").withEffort(EffortLevel.XHIGH);
        AgentDefinition legacy = new AgentDefinition("d", "p", null, null, null, null, null, null, null, null,
                null, "low", null);

        assertThat(PLAIN.writeValueAsString(high)).contains("\"effort\":\"high\"");
        assertThat(PLAIN.writeValueAsString(xhigh)).contains("\"effort\":\"xhigh\"");
        assertThat(PLAIN.writeValueAsString(legacy)).contains("\"effort\":\"low\"");
        assertThat(legacy.effort()).isEqualTo("low");
        assertThat(legacy.numericEffort()).isNull();
        assertThat(PLAIN.writeValueAsString(new AgentDefinition("d", "p"))).doesNotContain("effort");
    }

    @Test
    void withEffortReplacesTheOtherForm() {
        AgentDefinition agent = new AgentDefinition("d", "p").withEffort(5).withEffort("max");
        assertThat(agent.effort()).isEqualTo("max");
        assertThat(agent.numericEffort()).isNull();
        assertThat(agent.withEffort(7).effort()).isNull();
    }

    @Test
    void bothEffortFormsAreRejected() {
        assertThatThrownBy(() -> new AgentDefinition("d", "p", null, null, null, null, null, null, null, null,
                null, "high", null, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not both");
    }

    // ---- #37: "" and 0 mean "not set", as Python truthiness -------------------

    @Test
    void emptyStringAndZeroFlagsAreOmitted() {
        List<String> cmd = command(b -> b.model("").fallbackModel("").maxTurns(0).resume("").sessionId("")
                .settings("").permissionPromptToolName("").mcpServersJson(""));

        assertThat(cmd).doesNotContain("--model", "--fallback-model", "--max-turns", "--settings",
                "--permission-prompt-tool", "--mcp-config");
        assertThat(cmd).noneMatch(a -> a.startsWith("--resume") || a.startsWith("--session-id"));
    }

    @Test
    void flagsPythonTestsForNoneAreStillSent() {
        List<String> cmd = command(b -> b.maxBudgetUsd(0.0).settingSources(List.of()).maxTurns(-1));

        assertThat(valueOf(cmd, "--max-budget-usd")).isEqualTo("0.0");
        assertThat(valueOf(cmd, "--max-turns")).isEqualTo("-1");
        assertThat(cmd).contains("--setting-sources=");
    }

    @Test
    void setValuesAreStillSent() {
        List<String> cmd = command(b -> b.model("m").fallbackModel("f").maxTurns(3).resume("r").sessionId("s")
                .settings("{}").permissionPromptToolName("p").mcpServersJson("{\"mcpServers\":{}}"));

        assertThat(valueOf(cmd, "--model")).isEqualTo("m");
        assertThat(valueOf(cmd, "--fallback-model")).isEqualTo("f");
        assertThat(valueOf(cmd, "--max-turns")).isEqualTo("3");
        assertThat(valueOf(cmd, "--settings")).isEqualTo("{}");
        assertThat(valueOf(cmd, "--permission-prompt-tool")).isEqualTo("p");
        assertThat(valueOf(cmd, "--mcp-config")).isEqualTo("{\"mcpServers\":{}}");
        assertThat(cmd).contains("--resume=r", "--session-id=s");
    }

    // ---- #38: extraArgs ----------------------------------------------------

    @Test
    void extraArgsNullValueIsABareFlag() {
        // Port of test_build_command_with_extra_args
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("new-flag", "value");
        extra.put("boolean-flag", null);
        extra.put("another-option", "test-value");

        List<String> cmd = command(b -> b.extraArgs(extra));

        assertThat(String.join(" ", cmd)).contains("--new-flag value", "--another-option test-value");
        int idx = cmd.indexOf("--boolean-flag");
        assertThat(idx).isGreaterThanOrEqualTo(0);
        assertThat(cmd.get(idx + 1)).startsWith("--");
    }

    @Test
    void extraArgsBlankValueIsStillABareFlag() {
        List<String> cmd = command(b -> b.extraArgs(Map.of("replay-user-messages", "")));
        int idx = cmd.indexOf("--replay-user-messages");
        assertThat(idx).isGreaterThanOrEqualTo(0);
        assertThat(cmd.get(idx + 1)).startsWith("--");
    }

    @Test
    void extraArgsKeepInsertionOrder() {
        Map<String, String> extra = new LinkedHashMap<>();
        for (String key : List.of("zeta", "alpha", "mid", "beta", "omega", "gamma", "delta", "eps", "kappa")) {
            extra.put(key, null);
        }
        ClaudeAgentOptions options = ClaudeAgentOptions.builder().extraArgs(extra).build();

        assertThat(options.extraArgs().keySet()).containsExactlyElementsOf(extra.keySet());
        assertThat(options.toBuilder().build().extraArgs().keySet()).containsExactlyElementsOf(extra.keySet());
        List<String> flags = new ArrayList<>();
        for (String arg : command(b -> b.extraArgs(extra))) {
            if (arg.startsWith("--") && extra.containsKey(arg.substring(2))) {
                flags.add(arg.substring(2));
            }
        }
        assertThat(flags).containsExactlyElementsOf(extra.keySet());
    }

    @Test
    void extraArgsAreCopied() {
        Map<String, String> extra = new HashMap<>();
        extra.put("a", null);
        ClaudeAgentOptions options = ClaudeAgentOptions.builder().extraArgs(extra).build();
        extra.put("b", "x");

        assertThat(options.extraArgs()).containsOnlyKeys("a");
        assertThatThrownBy(() -> options.extraArgs().put("c", "y")).isInstanceOf(UnsupportedOperationException.class);
    }

    // ---- Tier 3 ------------------------------------------------------------

    @Test
    void nonLocalPluginIsRejected() {
        assertThatThrownBy(() -> command(b -> b.plugins(List.of(new ClaudeAgentOptions.SdkPluginConfig("npm", "x")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unsupported plugin type: npm");
    }

    @Test
    void unserializableJsonSchemaFailsInsteadOfDroppingTheFlag() {
        Map<String, Object> outputFormat = Map.of("type", "json_schema", "schema", Map.of("bad", new Object()));
        assertThatThrownBy(() -> command(b -> b.outputFormat(outputFormat)))
                .isInstanceOf(CLIConnectionException.class)
                .hasMessageContaining("Failed to serialize output format JSON schema");
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    @Test
    void unserializableMcpConfigFailsInsteadOfDroppingTheFlag() {
        Map env = new HashMap();
        env.put("K", new Object());
        McpStdioServerConfig config = new McpStdioServerConfig("npx", List.of(), env);
        assertThatThrownBy(() -> command(b -> b.mcpServers(Map.of("s", config))))
                .isInstanceOf(CLIConnectionException.class)
                .hasMessageContaining("Failed to serialize MCP server config");
    }

    @Test
    void zeroThinkingBudgetIsSent() {
        // Python: cmd.extend(["--max-thinking-tokens", str(t["budget_tokens"])])
        assertThat(valueOf(command(b -> b.thinking(new ThinkingConfigEnabled(0))), "--max-thinking-tokens"))
                .isEqualTo("0");
        assertThatThrownBy(() -> new ThinkingConfigEnabled(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void userOptionIsRejected() {
        @SuppressWarnings("deprecation")
        ClaudeAgentOptions options = ClaudeAgentOptions.builder().user("someone").build();
        assertThatThrownBy(() -> SubprocessCLITransport.rejectUnsupportedUser(options))
                .isInstanceOf(CLIConnectionException.class)
                .hasMessageContaining("not supported by the Java SDK");
    }

    @Test
    void spawnErrorClassification() {
        assertThat(SubprocessCLITransport.isFileNotFound(
                new java.io.IOException("Cannot run program \"/x\": error=2, No such file or directory"))).isTrue();
        assertThat(SubprocessCLITransport.isFileNotFound(
                new java.io.IOException("Cannot run program \"/x\": CreateProcess error=2, The system cannot find"))).isTrue();
        assertThat(SubprocessCLITransport.isFileNotFound(
                new java.io.IOException("Cannot run program \"/x\": Exec failed, error: 2 (No such file or directory)")))
                .isTrue();
        assertThat(SubprocessCLITransport.isFileNotFound(
                new java.io.IOException("Cannot run program \"/x\": error=13, Permission denied"))).isFalse();
        assertThat(SubprocessCLITransport.isFileNotFound(
                new java.io.IOException("Cannot run program \"/x\": Exec failed, error: 13 (Permission denied)")))
                .isFalse();
        assertThat(SubprocessCLITransport.isFileNotFound(
                new java.io.IOException("Cannot run program \"/x\": error=20, Not a directory"))).isFalse();
    }

}
