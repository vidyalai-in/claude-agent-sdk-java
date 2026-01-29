package in.vidyalai.claude.sdk.types.control.request;

import in.vidyalai.claude.sdk.types.hook.HookEvent;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonTypeName;

/**
 * Request to initialize the control protocol.
 *
 * <p>
 * Part of the SDK Control Protocol for bidirectional communication
 * between the SDK and CLI. This is the first request sent to establish
 * the control protocol connection and register hooks.
 */
@JsonTypeName("initialize")
public record SDKControlInitializeRequest(
        @Nullable Map<HookEvent, List<Map<String, Object>>> hooks) implements SDKControlRequestData {

    @Override
    public String subtype() {
        return "initialize";
    }

}
