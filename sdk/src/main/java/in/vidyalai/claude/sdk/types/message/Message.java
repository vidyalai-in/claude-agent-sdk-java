package in.vidyalai.claude.sdk.types.message;

/**
 * Sealed interface representing messages in a conversation.
 *
 * <p>
 * Messages can be one of:
 * <ul>
 * <li>{@link UserMessage} - Message from the user</li>
 * <li>{@link AssistantMessage} - Message from Claude</li>
 * <li>{@link SystemMessage} - System-level messages</li>
 * <li>{@link TaskStartedMessage} - Task started system message</li>
 * <li>{@link TaskProgressMessage} - Task progress system message</li>
 * <li>{@link TaskNotificationMessage} - Task notification system message</li>
 * <li>{@link ResultMessage} - Final result with cost/usage info</li>
 * <li>{@link StreamEvent} - Partial streaming events</li>
 * <li>{@link RateLimitEvent} - Rate limit status change events</li>
 * </ul>
 *
 * <p>
 * Use pattern matching to handle different message types:
 *
 * <pre>{@code
 * switch (message) {
 *     case UserMessage user -> System.out.println("User: " + user.content());
 *     case AssistantMessage assistant -> handleAssistant(assistant);
 *     case ResultMessage result -> System.out.println("Cost: $" + result.totalCostUsd());
 *     case SystemMessage system -> System.out.println("System: " + system.subtype());
 *     case TaskStartedMessage task -> System.out.println("Task started: " + task.taskId());
 *     case TaskProgressMessage task -> System.out.println("Task progress: " + task.taskId());
 *     case TaskNotificationMessage task -> System.out.println("Task done: " + task.status());
 *     case StreamEvent event -> handleStreamEvent(event);
 *     case RateLimitEvent rle -> handleRateLimit(rle);
 * }
 * }</pre>
 */
public sealed interface Message permits UserMessage, AssistantMessage, SystemMessage,
        TaskStartedMessage, TaskProgressMessage, TaskNotificationMessage,
        ResultMessage, StreamEvent, RateLimitEvent {

    /**
     * Returns the type identifier for this message.
     *
     * @return the type string ("user", "assistant", "system", "result", or
     *         "stream_event")
     */
    String type();

}
