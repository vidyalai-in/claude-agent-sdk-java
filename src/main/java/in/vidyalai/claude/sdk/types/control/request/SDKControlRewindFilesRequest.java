package in.vidyalai.claude.sdk.types.control.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;

/**
 * Request to rewind tracked files to their state at a specific user message.
 *
 * <p>
 * Part of the SDK Control Protocol for bidirectional communication
 * between the SDK and CLI. Requires file checkpointing to be enabled
 * via the {@code enable_file_checkpointing} option.
 *
 * @param userMessageId the user message ID to rewind to
 */
@JsonTypeName("rewind_files")
public record SDKControlRewindFilesRequest(
        @JsonProperty("user_message_id") String userMessageId) implements SDKControlRequestData {

    @Override
    public String subtype() {
        return "rewind_files";
    }

}
