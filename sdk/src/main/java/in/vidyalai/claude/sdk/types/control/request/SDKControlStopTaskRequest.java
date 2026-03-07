package in.vidyalai.claude.sdk.types.control.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;

/**
 * Request to stop a running task.
 *
 * <p>
 * Part of the SDK Control Protocol for bidirectional communication
 * between the SDK and CLI.
 *
 * @param taskId the task ID from task_notification events
 */
@JsonTypeName("stop_task")
public record SDKControlStopTaskRequest(
        @JsonProperty("task_id") String taskId) implements SDKControlRequestData {

    @Override
    public String subtype() {
        return "stop_task";
    }

}
