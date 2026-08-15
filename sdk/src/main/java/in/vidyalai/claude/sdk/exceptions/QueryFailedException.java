package in.vidyalai.claude.sdk.exceptions;

import java.util.List;

import in.vidyalai.claude.sdk.types.message.Message;
import in.vidyalai.claude.sdk.types.message.ResultMessage;

/**
 * Raised when a collecting query ends in an error, carrying the messages that
 * had already arrived.
 *
 * <p>
 * The CLI reports conditions such as {@code error_max_turns} and
 * {@code error_max_budget_usd} by emitting a {@link ResultMessage} with that
 * subtype and <i>then</i> exiting non-zero on purpose, for the benefit of shell
 * consumers. The SDK surfaces that exit as an exception rather than a return
 * value, so the error is never silently swallowed.
 *
 * <p>
 * The streaming APIs — {@link in.vidyalai.claude.sdk.ClaudeSDKClient#receiveMessages()}
 * and {@link in.vidyalai.claude.sdk.ClaudeSDKClient#receiveResponse()} — hand
 * each message to the consumer as it arrives and only raise at the end, so
 * nothing is lost there. The collecting
 * {@link in.vidyalai.claude.sdk.ClaudeSDK#query(String)} family has no such
 * luxury: it must either return a list or throw. This exception is how it does
 * both, keeping the collecting API as informative as the streaming one (and as
 * Python's generator, which yields every message before raising).
 *
 * <p>
 * The turn is usually complete when this is thrown — {@code error_max_turns}
 * and {@code error_max_budget_usd} both produce a full conversation plus a
 * final {@link ResultMessage}. Inspect it to find out what happened:
 *
 * <pre>{@code
 * try {
 *     List<Message> messages = ClaudeSDK.query(prompt, options);
 *     // ...
 * } catch (QueryFailedException e) {
 *     ResultMessage result = e.resultMessage();
 *     if (result != null && "error_max_budget_usd".equals(result.subtype())) {
 *         System.out.println("Spent $" + result.totalCostUsd() + " before stopping");
 *     }
 * }
 * }</pre>
 */
public class QueryFailedException extends ClaudeSDKException {

    private static final long serialVersionUID = 1L;

    /**
     * Not serialized: {@link Message} is not declared {@link java.io.Serializable}.
     * A deserialized instance reports an empty list rather than null.
     */
    private final transient List<Message> partialMessages;

    /**
     * Creates a new exception carrying the messages collected before the error.
     *
     * @param message         the detail message
     * @param cause           the underlying error
     * @param partialMessages the messages received before the run ended
     */
    public QueryFailedException(String message, Throwable cause, List<Message> partialMessages) {
        super(message, cause);
        this.partialMessages = List.copyOf(partialMessages);
    }

    /**
     * The messages received before the run ended, in arrival order.
     *
     * <p>
     * Usually a complete turn: the CLI emits its final {@link ResultMessage}
     * before exiting non-zero. Empty when the run failed before producing
     * anything (a CLI that could not start, for instance).
     *
     * @return an unmodifiable list of the messages received, never null
     */
    public List<Message> partialMessages() {
        return (partialMessages != null) ? partialMessages : List.of();
    }

    /**
     * The final {@link ResultMessage}, if one arrived before the error.
     *
     * <p>
     * Convenience for the common case: its {@code subtype} names the condition
     * that ended the run ({@code "error_max_turns"},
     * {@code "error_max_budget_usd"}, ...) and its {@code totalCostUsd} and
     * {@code usage} report what the run consumed.
     *
     * @return the last result message received, or null if none arrived
     */
    public ResultMessage resultMessage() {
        List<Message> received = partialMessages();
        for (int i = received.size() - 1; i >= 0; i--) {
            if (received.get(i) instanceof ResultMessage result) {
                return result;
            }
        }
        return null;
    }

}
