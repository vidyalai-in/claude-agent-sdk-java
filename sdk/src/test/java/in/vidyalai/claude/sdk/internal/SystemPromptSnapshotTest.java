package in.vidyalai.claude.sdk.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.ClaudeSDKClient;
import in.vidyalai.claude.sdk.internal.ForwardSubagentTextTest.RespondingTransport;
import in.vidyalai.claude.sdk.transport.Transport;
import in.vidyalai.claude.sdk.types.config.SystemPromptCustom;
import in.vidyalai.claude.sdk.types.config.SystemPromptFile;
import in.vidyalai.claude.sdk.types.config.SystemPromptPreset;

/**
 * The system prompt's {@code snapshot} reaches the CLI on the
 * {@code initialize} control request as {@code systemPromptSnapshot}
 * (Python SDK #1268).
 *
 * <p>
 * It is taken only from the preset and custom forms, and sent whenever set,
 * including {@code false}; unset, the field is omitted so older CLIs see the
 * request unchanged.
 */
class SystemPromptSnapshotTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<QueryHandler> handlersToClose = new ArrayList<>();
    private final List<Transport> transportsToClose = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (QueryHandler h : handlersToClose) {
            try {
                h.close();
            } catch (Exception ignored) {
                // best-effort
            }
        }
        handlersToClose.clear();
        for (Transport t : transportsToClose) {
            try {
                t.close();
            } catch (Exception ignored) {
                // best-effort
            }
        }
        transportsToClose.clear();
    }

    @SuppressWarnings({ "unchecked", "null" })
    private static Map<String, Object> request(String written) throws Exception {
        Map<String, Object> frame = MAPPER.readValue(written, Map.class);
        return (Map<String, Object>) frame.get("request");
    }

    private Map<String, Object> initializeRequestFor(Boolean systemPromptSnapshot) throws Exception {
        RespondingTransport transport = new RespondingTransport();
        transportsToClose.add(transport);

        QueryHandler handler = new QueryHandler(
                transport, true, null, null, null, null, null, systemPromptSnapshot, null,
                false, false, QueryHandler.DEFAULT_RUN_END_CEILING_MS, Duration.ofSeconds(10), null);
        handlersToClose.add(handler);
        transport.connect();
        handler.start();
        handler.initialize();

        return request(transport.firstWrite());
    }

    private Map<String, Object> initializeRequestForQuery(Object systemPrompt) throws Exception {
        RespondingTransport transport = new RespondingTransport();
        transportsToClose.add(transport);

        ClaudeSDK.query("hi", withSystemPrompt(systemPrompt), transport);
        return request(transport.firstWrite());
    }

    private Map<String, Object> initializeRequestForClient(Object systemPrompt) throws Exception {
        RespondingTransport transport = new RespondingTransport();
        transportsToClose.add(transport);

        try (ClaudeSDKClient client = new ClaudeSDKClient(withSystemPrompt(systemPrompt), transport)) {
            client.connect();
        }
        return request(transport.firstWrite());
    }

    private static ClaudeAgentOptions withSystemPrompt(Object systemPrompt) {
        ClaudeAgentOptions.Builder builder = ClaudeAgentOptions.builder();
        if (systemPrompt instanceof SystemPromptCustom custom) {
            builder.systemPrompt(custom);
        } else if (systemPrompt instanceof SystemPromptPreset preset) {
            builder.systemPrompt(preset);
        } else if (systemPrompt instanceof SystemPromptFile file) {
            builder.systemPrompt(file);
        } else {
            builder.systemPrompt((String) systemPrompt);
        }
        return builder.build();
    }

    @Test
    void initializeSendsSnapshot_evenWhenFalse() throws Exception {
        assertThat(initializeRequestFor(false))
                .containsEntry("subtype", "initialize")
                .containsEntry("systemPromptSnapshot", false);
        assertThat(initializeRequestFor(true))
                .containsEntry("systemPromptSnapshot", true);
    }

    @Test
    void initializeOmitsSnapshotWhenUnset() throws Exception {
        assertThat(initializeRequestFor(null))
                .containsEntry("subtype", "initialize")
                .doesNotContainKey("systemPromptSnapshot");
    }

    @Test
    void queryPassesSnapshotOnlyForThePresetAndCustomForms() throws Exception {
        assertThat(initializeRequestForQuery(SystemPromptCustom.of("Be helpful", false)))
                .containsEntry("systemPromptSnapshot", false);
        assertThat(initializeRequestForQuery(SystemPromptPreset.claudeCode().withSnapshot(true)))
                .containsEntry("systemPromptSnapshot", true);
        assertThat(initializeRequestForQuery(SystemPromptPreset.claudeCode()))
                .doesNotContainKey("systemPromptSnapshot");
        assertThat(initializeRequestForQuery(new SystemPromptFile("/p.md")))
                .doesNotContainKey("systemPromptSnapshot");
        assertThat(initializeRequestForQuery("Be helpful"))
                .doesNotContainKey("systemPromptSnapshot");
    }

    @Test
    void clientConnectSendsSnapshot() throws Exception {
        assertThat(initializeRequestForClient(SystemPromptCustom.of("Be helpful", false)))
                .containsEntry("systemPromptSnapshot", false);
        assertThat(initializeRequestForClient(SystemPromptPreset.claudeCode().withSnapshot(true)))
                .containsEntry("systemPromptSnapshot", true);
    }

    @Test
    void clientConnectOmitsSnapshotWhenUnset() throws Exception {
        assertThat(initializeRequestForClient(SystemPromptPreset.claudeCode()))
                .doesNotContainKey("systemPromptSnapshot");
    }
}
