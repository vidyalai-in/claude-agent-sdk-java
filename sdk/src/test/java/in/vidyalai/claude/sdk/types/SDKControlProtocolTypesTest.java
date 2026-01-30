package in.vidyalai.claude.sdk.types;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import in.vidyalai.claude.sdk.types.control.request.SDKControlInterruptRequest;
import in.vidyalai.claude.sdk.types.control.request.SDKControlPermissionRequest;
import in.vidyalai.claude.sdk.types.control.request.SDKControlRequest;
import in.vidyalai.claude.sdk.types.control.request.SDKControlRequestData;
import in.vidyalai.claude.sdk.types.control.request.SDKControlRewindFilesRequest;
import in.vidyalai.claude.sdk.types.control.request.SDKControlSetModelRequest;
import in.vidyalai.claude.sdk.types.control.request.SDKControlSetPermissionModeRequest;
import in.vidyalai.claude.sdk.types.control.response.ControlErrorResponse;
import in.vidyalai.claude.sdk.types.control.response.ControlResponse;
import in.vidyalai.claude.sdk.types.control.response.ControlResponseData;
import in.vidyalai.claude.sdk.types.control.response.SDKControlResponse;
import in.vidyalai.claude.sdk.types.permission.PermissionBehavior;
import in.vidyalai.claude.sdk.types.permission.PermissionMode;
import in.vidyalai.claude.sdk.types.permission.PermissionRuleValue;
import in.vidyalai.claude.sdk.types.permission.PermissionUpdate;

/**
 * Tests for SDK Control Protocol types.
 */
public class SDKControlProtocolTypesTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void testSDKControlInterruptRequest() throws Exception {
        SDKControlInterruptRequest request = new SDKControlInterruptRequest();
        assertThat(request.subtype()).isEqualTo("interrupt");

        // Test serialization
        String json = mapper.writeValueAsString(request);
        assertThat(json).contains("\"subtype\":\"interrupt\"");
    }

    @SuppressWarnings("null")
    @Test
    public void testSDKControlPermissionRequest() throws Exception {
        Map<String, Object> input = Map.of("command", "ls");
        List<PermissionUpdate> suggestions = List.of(
                PermissionUpdate.addRules(
                        List.of(new PermissionRuleValue("Bash", "allow")),
                        PermissionBehavior.ALLOW,
                        null));

        SDKControlPermissionRequest request = new SDKControlPermissionRequest(
                "Bash",
                input,
                suggestions,
                "/some/path");

        assertThat(request.subtype()).isEqualTo("can_use_tool");
        assertThat(request.toolName()).isEqualTo("Bash");
        assertThat(request.input()).isEqualTo(input);
        assertThat(request.permissionSuggestions()).hasSize(1);
        assertThat(request.blockedPath()).isEqualTo("/some/path");
    }

    @Test
    public void testSDKControlSetModelRequest() throws Exception {
        SDKControlSetModelRequest request = new SDKControlSetModelRequest("claude-sonnet-4-5");
        assertThat(request.subtype()).isEqualTo("set_model");
        assertThat(request.model()).isEqualTo("claude-sonnet-4-5");

        // Test with null model
        SDKControlSetModelRequest nullModelRequest = new SDKControlSetModelRequest(null);
        assertThat(nullModelRequest.model()).isNull();
    }

    @Test
    public void testSDKControlSetPermissionModeRequest() throws Exception {
        SDKControlSetPermissionModeRequest request = new SDKControlSetPermissionModeRequest(
                PermissionMode.ACCEPT_EDITS);

        assertThat(request.subtype()).isEqualTo("set_permission_mode");
        assertThat(request.mode()).isEqualTo(PermissionMode.ACCEPT_EDITS);
    }

    @Test
    public void testSDKControlRewindFilesRequest() throws Exception {
        SDKControlRewindFilesRequest request = new SDKControlRewindFilesRequest("msg-123");
        assertThat(request.subtype()).isEqualTo("rewind_files");
        assertThat(request.userMessageId()).isEqualTo("msg-123");

        // Test JSON serialization
        String json = mapper.writeValueAsString(request);
        assertThat(json).contains("\"user_message_id\":\"msg-123\"");
    }

    @Test
    public void testControlResponse() throws Exception {
        Map<String, Object> responseData = Map.of("status", "ok");
        ControlResponse response = new ControlResponse("req-123", responseData);

        assertThat(response.subtype()).isEqualTo("success");
        assertThat(response.requestId()).isEqualTo("req-123");
        assertThat(response.response()).isEqualTo(responseData);

        // Test JSON serialization
        String json = mapper.writeValueAsString(response);
        assertThat(json).contains("\"request_id\":\"req-123\"");
        assertThat(json).contains("\"status\":\"ok\"");
    }

    @Test
    public void testControlErrorResponse() throws Exception {
        ControlErrorResponse response = new ControlErrorResponse("req-456", "Something went wrong");

        assertThat(response.subtype()).isEqualTo("error");
        assertThat(response.requestId()).isEqualTo("req-456");
        assertThat(response.error()).isEqualTo("Something went wrong");

        // Test JSON serialization
        String json = mapper.writeValueAsString(response);
        assertThat(json).contains("\"request_id\":\"req-456\"");
        assertThat(json).contains("\"error\":\"Something went wrong\"");
    }

    @Test
    public void testSDKControlRequest() throws Exception {
        SDKControlInterruptRequest requestData = new SDKControlInterruptRequest();
        SDKControlRequest request = new SDKControlRequest("req-789", requestData);

        assertThat(request.type()).isEqualTo("control_request");
        assertThat(request.requestId()).isEqualTo("req-789");
        assertThat(request.request()).isEqualTo(requestData);
    }

    @Test
    public void testSDKControlResponse() throws Exception {
        ControlResponse responseData = new ControlResponse("req-123", Map.of("ok", true));
        SDKControlResponse response = new SDKControlResponse(responseData);

        assertThat(response.type()).isEqualTo("control_response");
        assertThat(response.response()).isEqualTo(responseData);
    }

    @Test
    public void testSDKControlRequestDataPolymorphism() {
        // Test that all request types are valid SDKControlRequestData instances
        SDKControlRequestData interrupt = new SDKControlInterruptRequest();
        SDKControlRequestData permission = new SDKControlPermissionRequest("Bash", Map.of(), null, null);
        SDKControlRequestData setModel = new SDKControlSetModelRequest("claude-opus-4-5");
        SDKControlRequestData setMode = new SDKControlSetPermissionModeRequest(PermissionMode.DEFAULT);
        SDKControlRequestData rewindFiles = new SDKControlRewindFilesRequest("msg-123");

        assertThat(interrupt).isInstanceOf(SDKControlRequestData.class);
        assertThat(permission).isInstanceOf(SDKControlRequestData.class);
        assertThat(setModel).isInstanceOf(SDKControlRequestData.class);
        assertThat(setMode).isInstanceOf(SDKControlRequestData.class);
        assertThat(rewindFiles).isInstanceOf(SDKControlRequestData.class);
    }

    @Test
    public void testControlResponseDataPolymorphism() {
        // Test that both response types are valid ControlResponseData instances
        ControlResponseData success = new ControlResponse("req-1", Map.of());
        ControlResponseData error = new ControlErrorResponse("req-2", "error");

        assertThat(success).isInstanceOf(ControlResponseData.class);
        assertThat(error).isInstanceOf(ControlResponseData.class);
    }

    @SuppressWarnings("null")
    @Test
    public void testSDKControlPermissionRequestDeserialization() throws Exception {
        // Test the full JSON deserialization path that includes permission_suggestions
        // This is the code path that was failing before the @JsonCreator fix
        String json = """
                {
                    "type": "control_request",
                    "request_id": "req-123",
                    "request": {
                        "subtype": "can_use_tool",
                        "tool_name": "Write",
                        "input": {"file_path": "/tmp/test.txt", "content": "hello"},
                        "permission_suggestions": [
                            {
                                "type": "addRules",
                                "rules": [
                                    {"toolName": "Write", "ruleContent": "/tmp/*"}
                                ],
                                "behavior": "allow",
                                "destination": "session"
                            },
                            {
                                "type": "setMode",
                                "mode": "acceptEdits",
                                "destination": "session"
                            }
                        ],
                        "blocked_path": "/tmp/test.txt"
                    }
                }
                """;

        // This should not throw an exception (used to fail before the fix)
        SDKControlRequest request = mapper.readValue(json, SDKControlRequest.class);

        assertThat(request.type()).isEqualTo("control_request");
        assertThat(request.requestId()).isEqualTo("req-123");
        assertThat(request.request()).isInstanceOf(SDKControlPermissionRequest.class);

        SDKControlPermissionRequest permReq = (SDKControlPermissionRequest) request.request();
        assertThat(permReq.toolName()).isEqualTo("Write");
        assertThat(permReq.input()).containsEntry("file_path", "/tmp/test.txt");
        assertThat(permReq.permissionSuggestions()).hasSize(2);
        assertThat(permReq.blockedPath()).isEqualTo("/tmp/test.txt");

        // Verify the first permission suggestion (addRules)
        PermissionUpdate firstSuggestion = permReq.permissionSuggestions().get(0);
        assertThat(firstSuggestion.rules()).hasSize(1);
        assertThat(firstSuggestion.rules().get(0).toolName()).isEqualTo("Write");
        assertThat(firstSuggestion.rules().get(0).ruleContent()).isEqualTo("/tmp/*");
        assertThat(firstSuggestion.behavior()).isEqualTo(PermissionBehavior.ALLOW);

        // Verify the second permission suggestion (setMode)
        PermissionUpdate secondSuggestion = permReq.permissionSuggestions().get(1);
        assertThat(secondSuggestion.mode()).isEqualTo(PermissionMode.ACCEPT_EDITS);
    }

}
