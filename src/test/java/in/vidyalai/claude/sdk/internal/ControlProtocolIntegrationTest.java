package in.vidyalai.claude.sdk.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import in.vidyalai.claude.sdk.types.control.request.SDKControlInitializeRequest;
import in.vidyalai.claude.sdk.types.control.request.SDKControlInterruptRequest;
import in.vidyalai.claude.sdk.types.control.request.SDKControlMCPStatusRequest;
import in.vidyalai.claude.sdk.types.control.request.SDKControlMcpMessageRequest;
import in.vidyalai.claude.sdk.types.control.request.SDKControlPermissionRequest;
import in.vidyalai.claude.sdk.types.control.request.SDKControlRequest;
import in.vidyalai.claude.sdk.types.control.request.SDKControlRequestData;
import in.vidyalai.claude.sdk.types.control.request.SDKControlRewindFilesRequest;
import in.vidyalai.claude.sdk.types.control.request.SDKControlSetModelRequest;
import in.vidyalai.claude.sdk.types.control.request.SDKControlSetPermissionModeRequest;
import in.vidyalai.claude.sdk.types.control.request.SDKHookCallbackRequest;
import in.vidyalai.claude.sdk.types.control.response.ControlErrorResponse;
import in.vidyalai.claude.sdk.types.control.response.ControlResponse;
import in.vidyalai.claude.sdk.types.control.response.SDKControlResponse;
import in.vidyalai.claude.sdk.types.permission.PermissionBehavior;
import in.vidyalai.claude.sdk.types.permission.PermissionMode;
import in.vidyalai.claude.sdk.types.permission.PermissionRuleValue;
import in.vidyalai.claude.sdk.types.permission.PermissionUpdate;

/**
 * Integration tests for SDK Control Protocol type
 * serialization/deserialization.
 */
public class ControlProtocolIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @SuppressWarnings({ "unchecked", "null" })
    @Test
    public void testSDKControlRequestSerialization() throws Exception {
        // Test interrupt request
        SDKControlInterruptRequest interruptReq = new SDKControlInterruptRequest();
        SDKControlRequest request = new SDKControlRequest("req-123", interruptReq);

        String json = MAPPER.writeValueAsString(request);
        assertThat(json).contains("\"request_id\":\"req-123\"");
        assertThat(json).contains("\"subtype\":\"interrupt\"");

        // Verify we can deserialize back
        Map<String, Object> deserialized = MAPPER.readValue(json, Map.class);
        assertThat(deserialized.get("request_id")).isEqualTo("req-123");
        assertThat(deserialized.get("type")).isEqualTo("control_request");
    }

    @SuppressWarnings("null")
    @Test
    public void testSDKControlPermissionRequestSerialization() throws Exception {
        Map<String, Object> input = Map.of("command", "ls -la");
        List<PermissionUpdate> suggestions = List.of(
                PermissionUpdate.addRules(
                        List.of(new PermissionRuleValue("Bash", "allow")),
                        PermissionBehavior.ALLOW,
                        null));

        SDKControlPermissionRequest permReq = new SDKControlPermissionRequest(
                "Bash",
                input,
                suggestions,
                "/some/path");
        SDKControlRequest request = new SDKControlRequest("req-456", permReq);

        String json = MAPPER.writeValueAsString(request);
        assertThat(json).contains("\"tool_name\":\"Bash\"");
        assertThat(json).contains("\"subtype\":\"can_use_tool\"");
        assertThat(json).contains("\"blocked_path\":\"/some/path\"");
    }

    @SuppressWarnings({ "unchecked", "null" })
    @Test
    public void testSDKControlRequestDeserialization() throws Exception {
        // Simulate JSON from CLI
        String json = """
                {
                    "type": "control_request",
                    "request_id": "req-789",
                    "request": {
                        "subtype": "set_model",
                        "model": "claude-sonnet-4-5"
                    }
                }
                """;

        // First deserialize to Map (as transport does)
        Map<String, Object> messageMap = MAPPER.readValue(json, Map.class);

        // Then re-serialize and deserialize to typed class (as QueryHandler does)
        String reJson = MAPPER.writeValueAsString(messageMap);
        SDKControlRequest request = MAPPER.readValue(reJson, SDKControlRequest.class);

        assertThat(request.requestId()).isEqualTo("req-789");
        assertThat(request.request()).isInstanceOf(SDKControlSetModelRequest.class);

        SDKControlSetModelRequest setModelReq = (SDKControlSetModelRequest) request.request();
        assertThat(setModelReq.model()).isEqualTo("claude-sonnet-4-5");
        assertThat(setModelReq.subtype()).isEqualTo("set_model");
    }

    @SuppressWarnings({ "unchecked", "null" })
    @Test
    public void testSDKControlPermissionRequestDeserialization() throws Exception {
        String json = """
                {
                    "type": "control_request",
                    "request_id": "req-123",
                    "request": {
                        "subtype": "can_use_tool",
                        "tool_name": "Write",
                        "input": {"file_path": "/test.txt", "content": "hello"},
                        "permission_suggestions": null,
                        "blocked_path": null
                    }
                }
                """;

        Map<String, Object> messageMap = MAPPER.readValue(json, Map.class);
        String reJson = MAPPER.writeValueAsString(messageMap);
        SDKControlRequest request = MAPPER.readValue(reJson, SDKControlRequest.class);

        assertThat(request.requestId()).isEqualTo("req-123");
        assertThat(request.request()).isInstanceOf(SDKControlPermissionRequest.class);

        SDKControlPermissionRequest permReq = (SDKControlPermissionRequest) request.request();
        assertThat(permReq.toolName()).isEqualTo("Write");
        assertThat(permReq.input()).containsEntry("file_path", "/test.txt");
        assertThat(permReq.subtype()).isEqualTo("can_use_tool");
    }

    @Test
    public void testControlResponseSerialization() throws Exception {
        ControlResponse response = new ControlResponse("req-456", Map.of("status", "ok"));
        SDKControlResponse controlResponse = new SDKControlResponse(response);

        String json = MAPPER.writeValueAsString(controlResponse);
        assertThat(json).contains("\"subtype\":\"success\"");
        assertThat(json).contains("\"request_id\":\"req-456\"");
        assertThat(json).contains("\"status\":\"ok\"");
    }

    @Test
    public void testControlErrorResponseSerialization() throws Exception {
        ControlErrorResponse response = new ControlErrorResponse("req-789", "Something failed");
        SDKControlResponse controlResponse = new SDKControlResponse(response);

        String json = MAPPER.writeValueAsString(controlResponse);
        assertThat(json).contains("\"subtype\":\"error\"");
        assertThat(json).contains("\"request_id\":\"req-789\"");
        assertThat(json).contains("\"error\":\"Something failed\"");
    }

    @SuppressWarnings({ "unchecked", "null" })
    @Test
    public void testAllRequestTypes() throws Exception {
        // Test each request type can be serialized and deserialized
        SDKControlRequestData[] requests = {
                new SDKControlInterruptRequest(),
                new SDKControlPermissionRequest("Bash", Map.of(), null, null),
                new SDKControlInitializeRequest(null),
                new SDKControlSetPermissionModeRequest(PermissionMode.ACCEPT_EDITS),
                new SDKControlSetModelRequest("claude-opus-4-5"),
                new SDKControlRewindFilesRequest("msg-123")
        };

        for (SDKControlRequestData reqData : requests) {
            SDKControlRequest request = new SDKControlRequest("test-id", reqData);
            String json = MAPPER.writeValueAsString(request);

            // Deserialize back through Map (simulating transport)
            Map<String, Object> map = MAPPER.readValue(json, Map.class);
            String reJson = MAPPER.writeValueAsString(map);
            SDKControlRequest deserialized = MAPPER.readValue(reJson, SDKControlRequest.class);

            assertThat(deserialized.requestId()).isEqualTo("test-id");
            assertThat(deserialized.request().subtype()).isEqualTo(reqData.subtype());
        }
    }

    @SuppressWarnings("unused")
    @Test
    public void testPatternMatchingOnRequestTypes() throws Exception {
        SDKControlRequest request = new SDKControlRequest(
                "req-1",
                new SDKControlSetModelRequest("claude-sonnet-4-5"));

        // Pattern matching with switch expression
        String result = switch (request.request()) {
            case SDKControlMCPStatusRequest r -> "mcp_status";
            case SDKControlInterruptRequest r -> "interrupt";
            case SDKControlPermissionRequest r -> "permission";
            case SDKControlSetModelRequest r -> "set_model:" + r.model();
            case SDKControlSetPermissionModeRequest r -> "set_mode";
            case SDKControlInitializeRequest r -> "initialize";
            case SDKHookCallbackRequest r -> "hook";
            case SDKControlMcpMessageRequest r -> "mcp";
            case SDKControlRewindFilesRequest r -> "rewind";
        };

        assertThat(result).isEqualTo("set_model:claude-sonnet-4-5");
    }

}
