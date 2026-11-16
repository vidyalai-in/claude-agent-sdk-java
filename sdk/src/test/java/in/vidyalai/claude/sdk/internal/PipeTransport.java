package in.vidyalai.claude.sdk.internal;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.databind.ObjectMapper;

import in.vidyalai.claude.sdk.exceptions.CLIConnectionException;
import in.vidyalai.claude.sdk.transport.Transport;

/**
 * A scripted in-memory transport for control-protocol tests.
 *
 * <p>
 * Frames pushed with {@link #push} are read by the SDK in order;
 * {@link #fail} makes the read end with an exception, {@link #end} with EOF.
 * Every write is recorded, and each outgoing {@code control_request} is handed
 * to {@link #replier}, whose answer (if any) is pushed back. It records the
 * reader thread and how many frames the SDK has taken, so a test can wait on
 * those instead of sleeping.
 */
@SuppressWarnings("null")
public final class PipeTransport implements Transport {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Object END = new Object();

    private final LinkedBlockingQueue<Object> inbound = new LinkedBlockingQueue<>();
    private final List<String> writes = new ArrayList<>();
    private final AtomicBoolean ready = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean readEnded = new AtomicBoolean(false);
    private final AtomicInteger taken = new AtomicInteger();
    private final AtomicInteger closeCalls = new AtomicInteger();
    private volatile @Nullable Thread readerThread;

    /**
     * Answers an outgoing control request: given the request frame, returns
     * the frame to push back, or null for no answer. Defaults to an empty
     * success.
     */
    public volatile Function<Map<String, Object>, @Nullable Map<String, Object>> replier =
            PipeTransport::success;

    /** An empty success answer to {@code request}. */
    public static Map<String, Object> success(Map<String, Object> request) {
        return successWith(request, Map.of());
    }

    /** A success answer to {@code request} carrying {@code body}. */
    public static Map<String, Object> successWith(Map<String, Object> request, Object body) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("subtype", "success");
        response.put("request_id", request.get("request_id"));
        response.put("response", body);
        return Map.of("type", "control_response", "response", response);
    }

    /** An error answer to {@code request}. */
    public static Map<String, Object> error(Map<String, Object> request, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("subtype", "error");
        response.put("request_id", request.get("request_id"));
        response.put("error", message);
        return Map.of("type", "control_response", "response", response);
    }

    /** A condition for {@link #awaitUntil}. */
    @FunctionalInterface
    public interface Condition {
        boolean holds() throws Exception;
    }

    /** Polls until {@code condition} holds, failing after {@code seconds}. */
    public static void awaitUntil(String what, long seconds, Condition condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!condition.holds()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Timed out waiting for " + what);
            }
            Thread.sleep(5);
        }
    }

    /** The control responses written for {@code requestId}. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> responsesTo(String requestId) throws Exception {
        List<Map<String, Object>> responses = new ArrayList<>();
        for (Map<String, Object> frame : writtenFrames()) {
            if ("control_response".equals(frame.get("type"))) {
                Map<String, Object> response = (Map<String, Object>) frame.get("response");
                if (requestId.equals(response.get("request_id"))) {
                    responses.add(response);
                }
            }
        }
        return responses;
    }

    /** The control requests the SDK wrote, by subtype. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> requestsWritten(String subtype) throws Exception {
        List<Map<String, Object>> requests = new ArrayList<>();
        for (Map<String, Object> frame : writtenFrames()) {
            if ("control_request".equals(frame.get("type"))
                    && (frame.get("request") instanceof Map<?, ?> request)
                    && subtype.equals(request.get("subtype"))) {
                requests.add((Map<String, Object>) request);
            }
        }
        return requests;
    }

    public void push(Map<String, Object> frame) {
        inbound.add(frame);
    }

    /** Ends the read with {@code error}, once the frames before it are read. */
    public void fail(RuntimeException error) {
        inbound.add(error);
    }

    /** Ends the read (EOF), once the frames before it are read. */
    public void end() {
        inbound.add(END);
    }

    public List<String> writes() {
        synchronized (writes) {
            return List.copyOf(writes);
        }
    }

    /** Writes parsed as JSON objects. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> writtenFrames() throws Exception {
        List<Map<String, Object>> frames = new ArrayList<>();
        for (String written : writes()) {
            frames.add(MAPPER.readValue(written, Map.class));
        }
        return frames;
    }

    /** The thread the SDK reads on, once it has started reading. */
    public @Nullable Thread readerThread() {
        return readerThread;
    }

    /** How many pushed frames the SDK has taken so far. */
    public int taken() {
        return taken.get();
    }

    /** Whether the SDK's read loop got to the end of the stream (EOF, error or close). */
    public boolean readEnded() {
        return readEnded.get();
    }

    public int closeCalls() {
        return closeCalls.get();
    }

    @Override
    public void connect() {
        ready.set(true);
    }

    @Override
    public Iterator<Map<String, Object>> readMessages() {
        return new Iterator<>() {
            private @Nullable Map<String, Object> pending;

            @Override
            public boolean hasNext() {
                readerThread = Thread.currentThread();
                while ((pending == null) && !closed.get()) {
                    Object next;
                    try {
                        next = inbound.poll(20, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        readEnded.set(true);
                        return false;
                    }
                    if (next == END) {
                        readEnded.set(true);
                        return false;
                    }
                    if (next instanceof RuntimeException failure) {
                        readEnded.set(true);
                        throw failure;
                    }
                    if (next != null) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> frame = (Map<String, Object>) next;
                        pending = frame;
                    }
                }
                if (pending == null) {
                    readEnded.set(true);
                }
                return pending != null;
            }

            @Override
            public Map<String, Object> next() {
                Map<String, Object> next = pending;
                if (next == null) {
                    throw new NoSuchElementException();
                }
                pending = null;
                taken.incrementAndGet();
                return next;
            }
        };
    }

    @SuppressWarnings("unchecked")
    @Override
    public void write(String data) {
        if (closed.get()) {
            throw new CLIConnectionException("Transport closed");
        }
        synchronized (writes) {
            writes.add(data);
        }
        Map<String, Object> frame;
        try {
            frame = MAPPER.readValue(data, Map.class);
        } catch (Exception e) {
            throw new CLIConnectionException("Bad frame: " + e.getMessage(), e);
        }
        if ("control_request".equals(frame.get("type"))) {
            Map<String, Object> answer = replier.apply(frame);
            if (answer != null) {
                inbound.add(answer);
            }
        }
    }

    @Override
    public void endInput() {
        // no-op
    }

    @Override
    public boolean isReady() {
        return ready.get() && !closed.get();
    }

    @Override
    public void close() {
        closeCalls.incrementAndGet();
        closed.set(true);
    }

}
