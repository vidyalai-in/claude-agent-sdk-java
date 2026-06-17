package in.vidyalai.claude.sdk.types.message;

import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * System message emitted when a background task's state changes.
 *
 * <p>
 * The CLI emits {@code system}/{@code task_updated} events as a task moves
 * through its lifecycle. {@link #patch()} carries the changed fields (e.g.
 * {@code status}, {@code end_time}); when {@code patch.status} is terminal (see
 * {@link #TERMINAL_TASK_STATUSES}) the task has finished.
 *
 * <p>
 * Lifecycle note: a background task's terminal state can arrive <em>only</em> as
 * a {@code TaskUpdatedMessage} with no accompanying {@link TaskNotificationMessage}
 * — for example a task stopped via {@code TaskStop} reports
 * {@link TaskUpdatedStatus#KILLED} here, and the matching notification is
 * sometimes suppressed. Consumers that track active task IDs should therefore
 * clear them on a terminal status (see {@link #TERMINAL_TASK_STATUSES}) from
 * <em>either</em> a {@link TaskNotificationMessage} or a {@code TaskUpdatedMessage}.
 *
 * <p>
 * Like the other typed task messages, this is a system message: {@link #type()}
 * returns {@code "system"} and the {@code subtype} and {@code data} fields remain
 * populated with the raw payload.
 *
 * @param subtype   always "task_updated"
 * @param data      raw message data
 * @param taskId    unique task identifier
 * @param patch     the changed fields for this update (never null; empty if absent)
 * @param status    the {@code patch.status} value, or null when absent/unknown
 * @param sessionId session identifier (may be null)
 * @param uuid      message UUID (may be null)
 */
public record TaskUpdatedMessage(
        @JsonProperty("subtype") String subtype,
        @JsonProperty("data") Map<String, Object> data,
        @JsonProperty("task_id") String taskId,
        @JsonProperty("patch") Map<String, Object> patch,
        @JsonProperty("status") @Nullable TaskUpdatedStatus status,
        @JsonProperty("session_id") @Nullable String sessionId,
        @JsonProperty("uuid") @Nullable String uuid) implements Message {

    /**
     * Task statuses that mean the task has finished and should be cleared from
     * any "active task" tracking.
     *
     * <p>
     * This set spans both lifecycle vocabularies: {@code task_notification}
     * reports {@code stopped} (the CLI's mapped form of a killed task) while
     * {@code task_updated} reports the raw {@code killed}. Consumers should treat
     * the {@code status} of a {@link TaskNotificationMessage} and a
     * {@code TaskUpdatedMessage} the same way.
     */
    public static final Set<String> TERMINAL_TASK_STATUSES =
            Set.of("completed", "failed", "stopped", "killed");

    @Override
    public String type() {
        return "system";
    }

    /**
     * Returns whether this update's status is terminal (the task has finished).
     *
     * @return true if {@link #status()} is present and terminal
     */
    public boolean isTerminal() {
        return status != null && TERMINAL_TASK_STATUSES.contains(status.getValue());
    }

    /**
     * Gets a value from the data map.
     *
     * @param key the key to look up
     * @param <T> the expected type
     * @return the value, or null if not present
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        return (T) data.get(key);
    }

}
