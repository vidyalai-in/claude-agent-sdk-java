package in.vidyalai.claude.sdk;

import static in.vidyalai.claude.sdk.internal.PipeTransport.awaitUntil;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Iterator;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import in.vidyalai.claude.sdk.exceptions.ClaudeSDKException;
import in.vidyalai.claude.sdk.exceptions.QueryFailedException;
import in.vidyalai.claude.sdk.internal.PipeTransport;
import in.vidyalai.claude.sdk.types.message.Message;

/**
 * Interrupting a thread that waits for the next message raises an error, with
 * the interrupt flag restored. It used to end the stream quietly, so an
 * interrupted {@code ClaudeSDK.query()} returned a truncated list as if the
 * run had finished.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@SuppressWarnings("null")
class ConsumerInterruptTest {

    private static final long WAIT_SECONDS = 5;

    @AfterEach
    void clearFlag() {
        Thread.interrupted();
    }

    private static ClaudeSDKClient connected(PipeTransport transport) {
        ClaudeSDKClient client = ClaudeSDKClient.withTransportFactory(ClaudeAgentOptions.defaults(),
                effective -> transport);
        client.connect();
        return client;
    }

    @Test
    void receiveMessagesThrowsWhenTheConsumerIsInterrupted() {
        try (ClaudeSDKClient client = connected(new PipeTransport())) {
            Iterator<Message> messages = client.receiveMessages();
            Thread.currentThread().interrupt();
            assertThatThrownBy(messages::hasNext)
                    .isExactlyInstanceOf(ClaudeSDKException.class)
                    .hasMessage("Interrupted while waiting for the next message")
                    .hasCauseInstanceOf(InterruptedException.class);
            assertThat(Thread.interrupted()).as("interrupt flag restored").isTrue();
        }
    }

    @Test
    void receiveResponseThrowsWhenTheConsumerIsInterrupted() {
        try (ClaudeSDKClient client = connected(new PipeTransport())) {
            Iterator<Message> messages = client.receiveResponse().iterator();
            Thread.currentThread().interrupt();
            assertThatThrownBy(messages::hasNext)
                    .isInstanceOf(ClaudeSDKException.class)
                    .hasCauseInstanceOf(InterruptedException.class);
            assertThat(Thread.interrupted()).as("interrupt flag restored").isTrue();
        }
    }

    @Test
    void anInterruptedQueryFailsInsteadOfReturningATruncatedList() throws Exception {
        PipeTransport transport = new PipeTransport();
        AtomicReference<@Nullable Throwable> failure = new AtomicReference<>();
        AtomicBoolean returned = new AtomicBoolean();
        AtomicBoolean flagAfter = new AtomicBoolean();
        Thread caller = new Thread(() -> {
            try {
                ClaudeSDK.query("hi", ClaudeAgentOptions.defaults(), transport);
                returned.set(true);
            } catch (Throwable t) {
                failure.set(t);
            }
            flagAfter.set(Thread.currentThread().isInterrupted());
        }, "query-caller");
        caller.start();
        // The prompt is written once initialize is done: the caller is now
        // collecting messages, and none will come.
        awaitUntil("the prompt to be sent", WAIT_SECONDS, () -> transport.writtenFrames().stream()
                .anyMatch(frame -> "user".equals(frame.get("type"))));
        caller.interrupt();
        caller.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS * 3));

        assertThat(caller.isAlive()).isFalse();
        assertThat(returned.get()).as("query returned normally").isFalse();
        assertThat(failure.get()).isInstanceOf(QueryFailedException.class);
        assertThat(failure.get().getCause())
                .isInstanceOf(ClaudeSDKException.class)
                .hasCauseInstanceOf(InterruptedException.class);
        assertThat(flagAfter.get()).as("interrupt flag restored").isTrue();
        // The handler still closed the transport.
        assertThat(transport.closeCalls()).isPositive();
    }

}
