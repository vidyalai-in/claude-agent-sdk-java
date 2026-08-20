package in.vidyalai.claude.sdk.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The JSON-RPC surface of {@link SdkMcpServer#handleMessage(Map)}.
 *
 * <p>
 * JSON-RPC has three message shapes and the server used to tell them apart by
 * switching on {@code method} alone, which threw on anything without one and
 * answered notifications that must never be answered. These tests pin the
 * classification, the handful of methods the server implements, and the
 * version handshake.
 *
 * <p>
 * Note the {@link HashMap}s: several of these messages carry an explicit
 * {@code null}, which {@code Map.of} rejects — which is a large part of why
 * nobody wrote them before.
 */
class SdkMcpDispatchTest {

    private static SdkMcpServer serverWith(SdkMcpTool<?>... tools) {
        return SdkMcpServer.create("test", "1.0.0", List.of(tools));
    }

    private static SdkMcpTool<Map<String, Object>> echo() {
        return SdkMcpTool.create("echo", "Echoes its arguments", Map.of(),
                args -> CompletableFuture.completedFuture(ToolResult.text(String.valueOf(args))));
    }

    private static Map<String, Object> send(SdkMcpServer server, Map<String, Object> message)
            throws Exception {
        return server.handleMessage(message).get();
    }

    private static Map<String, Object> request(Object id, String method, Object params) {
        Map<String, Object> message = new HashMap<>();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        message.put("method", method);
        message.put("params", params);
        return message;
    }

    private static Map<String, Object> notification(String method, Object params) {
        Map<String, Object> message = new HashMap<>();
        message.put("jsonrpc", "2.0");
        message.put("method", method);
        message.put("params", params);
        return message;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(@Nullable Object value) {
        assertThat(value).as("expected an object").isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> resultOf(Map<String, Object> response) {
        assertThat(response).as("expected a result, got: %s", response).containsKey("result");
        return (Map<String, Object>) response.get("result");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> errorOf(Map<String, Object> response) {
        assertThat(response).as("expected an error, got: %s", response).containsKey("error");
        return (Map<String, Object>) response.get("error");
    }

    // --- message classification

    @Test
    void aNotificationIsNeverAnswered() throws Exception {
        // JSON-RPC forbids a response to a notification. This one used to get
        // a malformed {jsonrpc, result} with no id at all.
        assertThat(send(serverWith(), notification("notifications/initialized", null))).isNull();
    }

    @Test
    void anUnsupportedNotificationIsNeverAnswered() throws Exception {
        // ...and this one used to get a -32601, which is worse: an error
        // response to a notification.
        assertThat(send(serverWith(), notification("notifications/roots/list_changed", Map.of())))
                .isNull();
    }

    @Test
    void aMessageWithNoMethodIsIgnoredRatherThanThrowing() throws Exception {
        // A JSON-RPC *response*. Switching on a null method used to throw
        // NullPointerException straight out of handleMessage, past every bit
        // of error handling below it.
        Map<String, Object> response = new HashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", 7);
        response.put("result", Map.of());

        assertThat(send(serverWith(), response)).isNull();
    }

    @Test
    void anEmptyMessageIsIgnored() throws Exception {
        assertThat(send(serverWith(), new HashMap<>())).isNull();
    }

    @Test
    void aRequestWithAnExplicitNullIdIsTreatedAsANotification() throws Exception {
        // The MCP specification forbids a null request id, so there is nobody
        // to answer.
        assertThat(send(serverWith(), request(null, "tools/list", null))).isNull();
    }

    // --- methods

    @Test
    void pingIsAnsweredWithAnEmptyResult() throws Exception {
        // Required of every MCP participant; this used to be a -32601, which a
        // conformant client may read as a dead connection.
        Map<String, Object> response = send(serverWith(), request(9, "ping", null));

        assertThat(response).containsEntry("id", 9);
        assertThat(resultOf(response)).isEmpty();
    }

    @Test
    void anUnknownMethodIsMethodNotFound() throws Exception {
        Map<String, Object> response = send(serverWith(), request(1, "resources/list", null));

        assertThat(errorOf(response)).containsEntry("code", -32601);
    }

    @Test
    void nonObjectParamsAreInvalidParams() throws Exception {
        // Used to be a ClassCastException escaping handleMessage.
        Map<String, Object> response = send(serverWith(echo()), request(1, "tools/call", "oops"));

        assertThat(errorOf(response)).containsEntry("code", -32602);
    }

    @Test
    void aCallWithNoToolNameIsInvalidParams() throws Exception {
        Map<String, Object> response = send(serverWith(echo()), request(1, "tools/call", Map.of()));

        assertThat(errorOf(response)).containsEntry("code", -32602);
        assertThat(String.valueOf(errorOf(response).get("message"))).contains("'name'");
    }

    // --- protocol version negotiation

    private static String protocolVersionFor(Object requested) throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("protocolVersion", requested);
        Map<String, Object> response = send(serverWith(), request(1, "initialize", params));
        return String.valueOf(resultOf(response).get("protocolVersion"));
    }

    @Test
    void initializeEchoesAVersionTheServerSpeaks() throws Exception {
        assertThat(protocolVersionFor("2025-06-18")).isEqualTo("2025-06-18");
        assertThat(protocolVersionFor("2024-11-05")).isEqualTo("2024-11-05");
    }

    @Test
    void initializeRefusesToClaim20250326() throws Exception {
        // That revision made JSON-RPC batching mandatory to receive, and a
        // batch is a top-level array — which the control request carrying
        // these messages types as a map and cannot represent. Claiming it
        // would be a promise the client acts on and we cannot keep.
        assertThat(protocolVersionFor("2025-03-26")).isEqualTo("2025-06-18");
    }

    @Test
    void initializeFallsBackToTheLatestSupportedVersion() throws Exception {
        assertThat(protocolVersionFor("1999-01-01")).isEqualTo("2025-06-18");
    }

    @Test
    void initializeWithoutAVersionStillHandshakes() throws Exception {
        // List.of(...).contains(null) throws, so this path needs its own guard.
        assertThat(protocolVersionFor(null)).isEqualTo("2025-06-18");

        Map<String, Object> response = send(serverWith(), request(1, "initialize", null));
        assertThat(resultOf(response)).containsKeys("protocolVersion", "capabilities", "serverInfo");
    }

    // --- tools/list

    @SuppressWarnings("unchecked")
    private static Map<String, Object> onlyListedTool(SdkMcpServer server) throws Exception {
        Map<String, Object> result = resultOf(send(server, request(1, "tools/list", null)));
        List<Map<String, Object>> tools = (List<Map<String, Object>>) result.get("tools");
        assertThat(tools).hasSize(1);
        return tools.get(0);
    }

    @Test
    void aTitleIsListedEvenWithNoAnnotations() throws Exception {
        // The title used to be dropped whenever no hints were declared, because
        // the whole annotations block was skipped when annotations() was null.
        SdkMcpTool<Map<String, Object>> titled = SdkMcpTool.<Map<String, Object>>builder("titled", "Has a title")
                .title("My Nice Title")
                .handler(args -> CompletableFuture.completedFuture(ToolResult.text("x")))
                .build();

        Map<String, Object> listed = onlyListedTool(serverWith(titled));

        assertThat(listed).containsEntry("title", "My Nice Title");
        assertThat(asMap(listed.get("annotations"))).containsEntry("title", "My Nice Title");
    }

    @Test
    void aToolWithNoTitleListsNeither() throws Exception {
        Map<String, Object> listed = onlyListedTool(serverWith(echo()));

        assertThat(listed).doesNotContainKey("title");
        assertThat(listed).doesNotContainKey("annotations");
    }

    // --- tools/call arguments

    @Test
    void absentArgumentsReachTheHandlerAsAnEmptyMap() throws Exception {
        AtomicReference<Object> seen = new AtomicReference<>();
        SdkMcpTool<Map<String, Object>> capture = SdkMcpTool.create(
                "capture", "Records what it was given", Map.of(),
                args -> {
                    seen.set(args);
                    return CompletableFuture.completedFuture(ToolResult.text("ok"));
                });

        send(serverWith(capture), request(1, "tools/call", Map.of("name", "capture")));

        assertThat(seen.get()).isEqualTo(Map.of());
    }

    @Test
    void explicitlyNullArgumentsReachTheHandlerAsAnEmptyMap() throws Exception {
        // Verified against the real server: getOrDefault returns the *stored*
        // null, so the handler used to be handed null and NPE on it.
        AtomicReference<Object> seen = new AtomicReference<>();
        SdkMcpTool<Map<String, Object>> capture = SdkMcpTool.create(
                "capture", "Records what it was given", Map.of(),
                args -> {
                    seen.set(args);
                    return CompletableFuture.completedFuture(ToolResult.text("ok"));
                });

        Map<String, Object> params = new HashMap<>();
        params.put("name", "capture");
        params.put("arguments", null);
        send(serverWith(capture), request(1, "tools/call", params));

        assertThat(seen.get()).isEqualTo(Map.of());
    }

}
