package in.vidyalai.claude.sdk.types.message;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import in.vidyalai.claude.sdk.types.permission.PermissionMode;

/**
 * Typed view of {@link SystemMessage#data()} for a {@code system}/{@code init}
 * message.
 *
 * <p>
 * The CLI emits this session metadata at the start of each turn: model,
 * working directory, tools, MCP servers, slash commands, permission mode, and
 * the capabilities list for feature detection. Mirrors the fields the
 * TypeScript SDK reference documents for {@code SDKSystemMessage}, with the
 * same key names as on the wire.
 *
 * <p>
 * The message parser does not validate the payload, so this describes what
 * the CLI sends rather than a guarantee: a field the CLI omits is
 * {@code null}, and an enum value this SDK does not recognize reads as
 * {@code null} (the raw value stays in {@link SystemMessage#data()}). Obtain
 * one once you have checked the subtype:
 *
 * <pre>{@code
 * if (msg instanceof SystemMessage sys && "init".equals(sys.subtype())) {
 *     SystemInitData init = sys.initData();
 *     System.out.println(init.model() + " " + init.capabilities());
 * }
 * }</pre>
 *
 * <p>
 * <b>JSON Naming Convention:</b> This type uses the CLI's own key names, which
 * mix {@code snake_case} and {@code camelCase}, because it represents data
 * <b>received from the CLI</b>.
 *
 * @param type                   always {@code "system"}
 * @param subtype                always {@code "init"}
 * @param uuid                   message UUID
 * @param sessionId              session identifier
 * @param agents                 available agent names
 * @param apiKeySource           where the API credential came from:
 *                               {@code "ANTHROPIC_API_KEY"},
 *                               {@code "apiKeyHelper"},
 *                               {@code "/login managed key"}, or
 *                               {@code "none"} (no API key in use, e.g. a
 *                               claude.ai login or a third-party cloud
 *                               provider)
 * @param betas                  enabled beta features
 * @param claudeCodeVersion      Claude Code CLI version
 * @param cwd                    working directory
 * @param tools                  available tool names
 * @param mcpServers             configured MCP servers and their status
 * @param model                  model in use
 * @param permissionMode         current permission mode
 * @param slashCommands          available slash commands
 * @param terminalSlashCommands  entries of {@code slashCommands} whose
 *                               interface is bound to the local terminal
 *                               (e.g. {@code exit}). Present only when
 *                               non-empty
 * @param outputStyle            active output style
 * @param skills                 available skills
 * @param plugins                loaded plugins
 * @param pluginErrors           plugin load failures. The CLI omits the key
 *                               when nothing failed, but an omitted key does
 *                               not by itself assert a clean load (older CLIs
 *                               and some hosted sessions never send it)
 * @param fastModeState          fast mode state
 * @param fastModeDisabledReason names the check that blocks fast mode, when
 *                               something does
 * @param capabilities           protocol capabilities this CLI supports (e.g.
 *                               {@code "interrupt_receipt_v1"}), for feature
 *                               detection instead of comparing
 *                               {@code claudeCodeVersion}. Open set: ignore
 *                               values you don't recognize. Absent on CLIs
 *                               before 2.1.205
 */
public record SystemInitData(
        @JsonProperty("type") String type,
        @JsonProperty("subtype") String subtype,
        @JsonProperty("uuid") String uuid,
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("agents") @Nullable List<String> agents,
        @JsonProperty("apiKeySource") String apiKeySource,
        @JsonProperty("betas") @Nullable List<String> betas,
        @JsonProperty("claude_code_version") String claudeCodeVersion,
        @JsonProperty("cwd") String cwd,
        @JsonProperty("tools") List<String> tools,
        @JsonProperty("mcp_servers") List<SystemInitMcpServer> mcpServers,
        @JsonProperty("model") String model,
        @JsonProperty("permissionMode") PermissionMode permissionMode,
        @JsonProperty("slash_commands") List<String> slashCommands,
        @JsonProperty("terminal_slash_commands") @Nullable List<String> terminalSlashCommands,
        @JsonProperty("output_style") String outputStyle,
        @JsonProperty("skills") List<String> skills,
        @JsonProperty("plugins") List<SystemInitPlugin> plugins,
        @JsonProperty("plugin_errors") @Nullable List<SystemInitPluginError> pluginErrors,
        @JsonProperty("fast_mode_state") @Nullable FastModeState fastModeState,
        @JsonProperty("fast_mode_disabled_reason") @Nullable FastModeDisabledReason fastModeDisabledReason,
        @JsonProperty("capabilities") @Nullable List<String> capabilities) {

    /** The {@link SystemMessage#subtype()} whose data this type describes. */
    public static final String SUBTYPE = "init";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL, true);

    /**
     * Reads an init payload.
     *
     * @param data the {@link SystemMessage#data()} of an {@code init} message
     * @return the typed view
     * @throws IllegalArgumentException if a field has the wrong JSON shape
     */
    public static SystemInitData from(Map<String, Object> data) {
        return MAPPER.convertValue(data, SystemInitData.class);
    }

}
