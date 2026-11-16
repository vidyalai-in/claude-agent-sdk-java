package in.vidyalai.claude.sdk.internal;

import static in.vidyalai.claude.sdk.internal.PipeTransport.awaitUntil;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import in.vidyalai.claude.sdk.exceptions.CLIConnectionException;
import in.vidyalai.claude.sdk.types.message.Message;
import in.vidyalai.claude.sdk.types.message.UserMessage;

/**
 * The consumer queue applies back-pressure instead of dropping messages, as
 * the Python SDK's awaited send does, and the end of the stream and the error
 * it ended with always reach the consumer, even when the queue is full.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
@SuppressWarnings("null")
class MessageQueueBackPressureTest {

    private static final long WAIT_SECONDS = 5;
    private static final Duration NO_HANG = Duration.ofSeconds(3);

    private final List<QueryHandler> handlers = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (QueryHandler h : handlers) {
            h.close();
        }
    }

    private QueryHandler start(PipeTransport transport, int queueSize) {
        QueryHandler handler = new QueryHandler(transport, true, null, null, null,
                null, null, null, Duration.ofSeconds(10), queueSize);
        handlers.add(handler);
        transport.connect();
        handler.start();
        return handler;
    }

    private static Map<String, Object> user(String text) {
        return Map.of("type", "user", "message", Map.of("role", "user", "content", text));
    }

    private static String text(Message message) {
        assertThat(message).isInstanceOf(UserMessage.class);
        return String.valueOf(((UserMessage) message).content());
    }

    /** Waits until the reader has finished: its executor thread is idle again. */
    private static void awaitReaderDone(PipeTransport transport) throws Exception {
        awaitUntil("the read loop to end", WAIT_SECONDS, transport::readEnded);
        Thread reader = transport.readerThread();
        assertThat(reader).isNotNull();
        // Idle in the executor's untimed take(); the read loop itself only
        // ever waits with a timeout.
        awaitUntil("the reader to finish", WAIT_SECONDS, () -> reader.getState() == Thread.State.WAITING);
    }

    @Test
    void slowConsumerLosesNothingAndStillSeesTheError() throws Exception {
        PipeTransport transport = new PipeTransport();
        QueryHandler handler = start(transport, 1);
        for (int i = 1; i <= 5; i++) {
            transport.push(user("m" + i));
        }
        transport.fail(new CLIConnectionException("boom"));

        // One message fills the queue; the reader has taken the next and is
        // waiting for room.
        awaitUntil("the reader to wait for room", WAIT_SECONDS, () -> transport.taken() == 2);
        // Longer than the 5s after which the reader used to drop a message it
        // could not queue. Time passing is the very thing under test here.
        Thread.sleep(5_500);

        Iterator<Message> messages = handler.receiveMessages();
        List<String> seen = new ArrayList<>();
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            assertThatThrownBy(() -> {
                while (messages.hasNext()) {
                    seen.add(text(messages.next()));
                }
            }).isInstanceOf(CLIConnectionException.class).hasMessage("boom");
        });
        assertThat(seen).containsExactly("m1", "m2", "m3", "m4", "m5");
    }

    @Test
    void errorIsNotLostWhenTheQueueIsFull() throws Exception {
        PipeTransport transport = new PipeTransport();
        QueryHandler handler = start(transport, 1);
        transport.push(user("m1"));
        transport.fail(new CLIConnectionException("boom"));
        // The reader failed while the queue was full, so the error marker
        // could not be queued (it used to be offered for 1s, then dropped).
        awaitReaderDone(transport);

        Iterator<Message> messages = handler.receiveMessages();
        assertTimeoutPreemptively(NO_HANG, () -> {
            assertThat(text(messages.next())).isEqualTo("m1");
            assertThatThrownBy(messages::hasNext).isInstanceOf(CLIConnectionException.class).hasMessage("boom");
        });
    }

    @Test
    void endIsNotLostWhenTheQueueIsFull() throws Exception {
        PipeTransport transport = new PipeTransport();
        QueryHandler handler = start(transport, 1);
        transport.push(user("m1"));
        transport.end();
        awaitReaderDone(transport);

        Iterator<Message> messages = handler.receiveMessages();
        assertTimeoutPreemptively(NO_HANG, () -> {
            assertThat(text(messages.next())).isEqualTo("m1");
            assertThat(messages.hasNext()).isFalse();
        });
    }

    @Test
    void anIteratorCreatedAfterTheEndSeesTheEnd() throws Exception {
        PipeTransport transport = new PipeTransport();
        QueryHandler handler = start(transport, 10);
        transport.push(user("m1"));
        transport.end();

        Iterator<Message> first = handler.receiveMessages();
        assertTimeoutPreemptively(NO_HANG, () -> {
            assertThat(text(first.next())).isEqualTo("m1");
            assertThat(first.hasNext()).isFalse();
        });
        // The first iterator consumed the end marker; a second one must not
        // wait forever for another.
        Iterator<Message> second = handler.receiveMessages();
        assertTimeoutPreemptively(NO_HANG, () -> assertThat(second.hasNext()).isFalse());
    }

    @Test
    void anIteratorCreatedAfterTheErrorSeesTheError() throws Exception {
        PipeTransport transport = new PipeTransport();
        QueryHandler handler = start(transport, 10);
        transport.fail(new CLIConnectionException("boom"));

        Iterator<Message> first = handler.receiveMessages();
        assertTimeoutPreemptively(NO_HANG, () -> assertThatThrownBy(first::hasNext)
                .isInstanceOf(CLIConnectionException.class));
        Iterator<Message> second = handler.receiveMessages();
        assertTimeoutPreemptively(NO_HANG, () -> assertThatThrownBy(second::hasNext)
                .isInstanceOf(CLIConnectionException.class).hasMessage("boom"));
    }

    @Test
    void closeUnblocksAReaderWaitingForRoom() throws Exception {
        PipeTransport transport = new PipeTransport();
        QueryHandler handler = start(transport, 1);
        transport.push(user("m1"));
        transport.push(user("m2"));
        transport.push(user("m3"));
        awaitUntil("the reader to wait for room", WAIT_SECONDS, () -> transport.taken() == 2);

        long t0 = System.nanoTime();
        handler.close();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

        assertThat(elapsedMs).isLessThan(2_000);
        assertThat(transport.taken()).isEqualTo(2);
    }

}
