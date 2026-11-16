package in.vidyalai.claude.sdk.internal;

import static in.vidyalai.claude.sdk.internal.PipeTransport.awaitUntil;
import static org.assertj.core.api.Assertions.assertThat;
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

import in.vidyalai.claude.sdk.types.message.Message;
import in.vidyalai.claude.sdk.types.message.UserMessage;

/**
 * A full message queue holds the reader back (back-pressure), but never the
 * response to a control request the SDK sent: that response shares stdout
 * with the messages, so a control call made by a thread that is not
 * draining them used to wait out its 60 s timeout. Message order is kept.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
@SuppressWarnings("null")
class ControlResponseHeadOfLineTest {

    private static final long WAIT_SECONDS = 5;
    private static final int QUEUE_SIZE = 3;
    private static final int BUFFERED = 20;

    private final List<QueryHandler> handlers = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (QueryHandler h : handlers) {
            h.close();
        }
    }

    private QueryHandler start(PipeTransport transport) {
        QueryHandler handler = new QueryHandler(transport, true, null, null, null,
                null, null, null, Duration.ofSeconds(10), QUEUE_SIZE);
        handlers.add(handler);
        transport.connect();
        handler.start();
        handler.initialize();
        return handler;
    }

    private static Map<String, Object> user(String text) {
        return Map.of("type", "user", "message", Map.of("role", "user", "content", text));
    }

    /** Whether {@code reader} is parked in the queue's wait for room. */
    private static boolean waitingForRoom(Thread reader) {
        Thread.State state = reader.getState();
        if ((state != Thread.State.TIMED_WAITING) && (state != Thread.State.WAITING)) {
            return false;
        }
        for (StackTraceElement frame : reader.getStackTrace()) {
            if ("enqueueBlocking".equals(frame.getMethodName())) {
                return true;
            }
        }
        return false;
    }

    private static List<String> drain(Iterator<Message> messages, int count) {
        List<String> seen = new ArrayList<>();
        while (seen.size() < count && messages.hasNext()) {
            Message message = messages.next();
            assertThat(message).isInstanceOf(UserMessage.class);
            seen.add(String.valueOf(((UserMessage) message).content()));
        }
        return seen;
    }

    @Test
    void aControlCallCompletesWhileTheQueueIsFullAndNobodyDrains() throws Exception {
        PipeTransport transport = new PipeTransport();
        QueryHandler handler = start(transport);
        int initFrames = transport.taken(); // the initialize response
        for (int i = 0; i < BUFFERED; i++) {
            transport.push(user("m" + i));
        }
        // The queue fills and the reader holds back with one more in hand.
        awaitUntil("the reader to hold back", WAIT_SECONDS,
                () -> transport.taken() == initFrames + QUEUE_SIZE + 1);

        // The interrupt's response is queued behind all the buffered messages.
        assertTimeoutPreemptively(Duration.ofSeconds(5), handler::interrupt);
        assertThat(transport.requestsWritten("interrupt")).hasSize(1);

        // Nothing was dropped or reordered.
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < BUFFERED; i++) {
            expected.add("m" + i);
        }
        assertThat(drain(handler.receiveMessages(), BUFFERED)).containsExactlyElementsOf(expected);
    }

    @Test
    void backPressureResumesOnceNoControlRequestIsPending() throws Exception {
        PipeTransport transport = new PipeTransport();
        QueryHandler handler = start(transport);
        int initFrames = transport.taken(); // the initialize response
        for (int i = 0; i < BUFFERED; i++) {
            transport.push(user("m" + i));
        }
        awaitUntil("the reader to hold back", WAIT_SECONDS,
                () -> transport.taken() == initFrames + QUEUE_SIZE + 1);
        assertTimeoutPreemptively(Duration.ofSeconds(5), handler::interrupt);
        // All buffered messages and the response were read.
        awaitUntil("the response to be read", WAIT_SECONDS,
                () -> transport.taken() == initFrames + BUFFERED + 1);

        // With no control request pending the soft limit applies again: the
        // reader takes one more frame and then waits for room.
        for (int i = BUFFERED; i < BUFFERED + 5; i++) {
            transport.push(user("m" + i));
        }
        awaitUntil("the reader to take the next frame", WAIT_SECONDS,
                () -> transport.taken() == initFrames + BUFFERED + 2);
        Thread reader = transport.readerThread();
        awaitUntil("the reader to wait for room", WAIT_SECONDS, () -> waitingForRoom(reader));
        assertThat(transport.taken()).isEqualTo(initFrames + BUFFERED + 2);

        // Draining lets it go on; everything arrives, in order.
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < BUFFERED + 5; i++) {
            expected.add("m" + i);
        }
        assertThat(drain(handler.receiveMessages(), BUFFERED + 5)).containsExactlyElementsOf(expected);
    }

}
