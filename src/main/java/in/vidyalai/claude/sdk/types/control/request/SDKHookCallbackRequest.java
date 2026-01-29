package in.vidyalai.claude.sdk.types.control.request;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;

import in.vidyalai.claude.sdk.types.hook.HookInput;

/**
 * Request to invoke a hook callback.
 *
 * <p>
 * Part of the SDK Control Protocol for bidirectional communication
 * between the SDK and CLI. This request is sent when a hook needs to
 * be executed.
 *
 * @param callbackId unique identifier for the callback
 * @param input      the hook input data
 * @param toolUseId  optional tool use ID if this is a tool-related hook (can be
 *                   null)
 */
@JsonTypeName("hook_callback")
public record SDKHookCallbackRequest(
        @JsonProperty("callback_id") String callbackId,
        HookInput input,
        @JsonProperty("tool_use_id") @Nullable String toolUseId) implements SDKControlRequestData {

    @Override
    public String subtype() {
        return "hook_callback";
    }

}
