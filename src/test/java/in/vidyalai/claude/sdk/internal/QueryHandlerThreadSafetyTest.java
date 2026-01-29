package in.vidyalai.claude.sdk.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import in.vidyalai.claude.sdk.exceptions.CLIConnectionException;
import in.vidyalai.claude.sdk.transport.Transport;
import in.vidyalai.claude.sdk.types.control.response.ControlResponse;
import in.vidyalai.claude.sdk.types.control.response.SDKControlResponse;
import in.vidyalai.claude.sdk.types.message.Message;

/**
 * Thread safety tests for QueryHandler.
 *
 * <p>
 * These tests verify that concurrent operations are handled correctly:
 * <ul>
 * <li>Multiple threads calling start()</li>
 * <li>Multiple calls to receiveMessages() (allowed - creates multiple
 * iterators)</li>
 * <li>Concurrent initialize() calls</li>
 * <li>Concurrent start() and close()</li>
 * <li>Multiple close() calls</li>
 * <li>Resource cleanup on close</li>
 * </ul>
 */
class QueryHandlerThreadSafetyTest {

    private ExecutorService executor;
    private final List<QueryHandler> handlersToClose = new ArrayList<>();
    private final List<Transport> transportsToClose = new ArrayList<>();

    @AfterEach
    void tearDown() throws InterruptedException {
        // Close all handlers
        for (QueryHandler handler : handlersToClose) {
            try {
                handler.close();
            } catch (Exception e) {
                // Ignore
            }
        }
        handlersToClose.clear();

        // Close all transports
        for (Transport transport : transportsToClose) {
            try {
                transport.close();
            } catch (Exception e) {
                // Ignore
            }
        }
        transportsToClose.clear();

        // Shutdown executor
        if (executor != null) {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @Timeout(10)
    void testMultipleReceiveMessagesCallsAllowed() {
        // Given: A QueryHandler
        MockTransport transport = createMockTransport();
        QueryHandler handler = createQueryHandler(transport, false);

        // When: Multiple calls to receiveMessages()
        Iterator<Message> iter1 = handler.receiveMessages();
        Iterator<Message> iter2 = handler.receiveMessages();
        Iterator<Message> iter3 = handler.receiveMessages();

        // Then: All should succeed (no exceptions thrown)
        assertThat(iter1).isNotNull();
        assertThat(iter2).isNotNull();
        assertThat(iter3).isNotNull();
    }

    @Test
    @Timeout(10)
    void testConcurrentReceiveMessagesCallsFromMultipleThreads() throws Exception {
        // Given: A QueryHandler
        MockTransport transport = createMockTransport();
        QueryHandler handler = createQueryHandler(transport, false);
        executor = Executors.newFixedThreadPool(5);

        int threadCount = 5;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        List<Exception> exceptions = new ArrayList<>();

        // When: Multiple threads try to call receiveMessages() simultaneously
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    handler.receiveMessages();
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    synchronized (exceptions) {
                        exceptions.add(e);
                    }
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(5, TimeUnit.SECONDS);
        assertThat(completed).isTrue();

        // Then: All should succeed (multiple iterators allowed)
        assertThat(successCount.get()).isEqualTo(threadCount);
        assertThat(exceptions).isEmpty();
    }

    @Test
    @Timeout(10)
    void testConcurrentStartCalls() throws Exception {
        // Given: A QueryHandler
        MockTransport transport = createMockTransport();
        QueryHandler handler = createQueryHandler(transport, false);
        executor = Executors.newFixedThreadPool(10);

        int threadCount = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        List<Exception> exceptions = new ArrayList<>();

        // When: Multiple threads try to call start() simultaneously
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    handler.start();
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    synchronized (exceptions) {
                        exceptions.add(e);
                    }
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(5, TimeUnit.SECONDS);
        assertThat(completed).isTrue();

        // Then: All should succeed (start is idempotent)
        assertThat(successCount.get()).isEqualTo(threadCount);
        assertThat(exceptions).isEmpty();
    }

    @Test
    @Timeout(10)
    void testConcurrentInitializeCallsNonStreaming() throws Exception {
        // Given: A QueryHandler in non-streaming mode (doesn't send control request)
        MockTransport transport = createMockTransport();
        QueryHandler handler = createQueryHandler(transport, false); // non-streaming
        handler.start();
        executor = Executors.newFixedThreadPool(10);

        int threadCount = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        List<Map<String, Object>> results = new ArrayList<>();
        List<Exception> exceptions = new ArrayList<>();

        // When: Multiple threads try to initialize simultaneously
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    SDKControlResponse response = handler.initialize();
                    if (response == null) {
                        synchronized (results) {
                            results.add(null);
                        }
                        return;
                    }
                    Map<String, Object> result = ((ControlResponse) response.response()).response();
                    synchronized (results) {
                        results.add(result);
                    }
                } catch (Exception e) {
                    synchronized (exceptions) {
                        exceptions.add(e);
                    }
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(5, TimeUnit.SECONDS);
        assertThat(completed).isTrue();

        // Then: All should return null (non-streaming mode)
        assertThat(exceptions).isEmpty();
        assertThat(results).hasSize(threadCount);
        // All results should be null for non-streaming mode
        for (Map<String, Object> result : results) {
            assertThat(result).isNull();
        }
    }

    @SuppressWarnings("null")
    @Test
    @Timeout(10)
    void testStartAfterClose() throws Exception {
        // Given: A QueryHandler that has been closed
        MockTransport transport = createMockTransport();
        QueryHandler handler = createQueryHandler(transport, false);
        handler.close();

        // When/Then: start() should throw IllegalStateException
        assertThatThrownBy(() -> handler.start())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
    }

    @Test
    @Timeout(10)
    void testConcurrentStartAndClose() throws Exception {
        // Given: A QueryHandler
        MockTransport transport = createMockTransport();
        QueryHandler handler = createQueryHandler(transport, false);
        executor = Executors.newFixedThreadPool(10);

        int threadCount = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger startSuccessCount = new AtomicInteger(0);
        AtomicInteger startExceptionCount = new AtomicInteger(0);
        AtomicInteger closeSuccessCount = new AtomicInteger(0);

        // When: Half threads call start(), half call close() simultaneously
        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    if (index % 2 == 0) {
                        handler.start();
                        startSuccessCount.incrementAndGet();
                    } else {
                        handler.close();
                        closeSuccessCount.incrementAndGet();
                    }
                } catch (IllegalStateException e) {
                    // Expected if start() called after close()
                    startExceptionCount.incrementAndGet();
                } catch (Exception e) {
                    // Other exceptions are not expected
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(5, TimeUnit.SECONDS);
        assertThat(completed).isTrue();

        // Then: No crashes, all operations completed
        assertThat(startSuccessCount.get() + startExceptionCount.get() + closeSuccessCount.get())
                .isEqualTo(threadCount);
    }

    @Test
    @Timeout(10)
    void testMultipleCloseCalls() throws Exception {
        // Given: A QueryHandler
        MockTransport transport = createMockTransport();
        QueryHandler handler = createQueryHandler(transport, false);
        handler.start();
        executor = Executors.newFixedThreadPool(10);

        int threadCount = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        List<Exception> exceptions = new ArrayList<>();

        // When: Multiple threads call close() simultaneously
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    handler.close();
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    synchronized (exceptions) {
                        exceptions.add(e);
                    }
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(5, TimeUnit.SECONDS);
        assertThat(completed).isTrue();

        // Then: All should succeed (close is idempotent)
        assertThat(successCount.get()).isEqualTo(threadCount);
        assertThat(exceptions).isEmpty();
    }

    @Test
    @Timeout(10)
    void testCloseWaitsForReaderThread() throws Exception {
        // Given: A QueryHandler with a started reader thread
        MockStreamingTransport transport = new MockStreamingTransport();
        transportsToClose.add(transport);
        QueryHandler handler = new QueryHandler(
                transport,
                false,
                null,
                null,
                Duration.ofSeconds(60));
        handlersToClose.add(handler);

        handler.start();

        // Wait a bit to ensure reader thread is running
        Thread.sleep(100);

        // When: Close is called
        long startTime = System.currentTimeMillis();
        handler.close();
        long duration = System.currentTimeMillis() - startTime;

        // Then: Close should complete quickly (reader thread should terminate)
        assertThat(duration).isLessThan(2000); // Should complete well under 10s timeout
        assertThat(transport.isClosed()).isTrue();
    }

    @Test
    @Timeout(15)
    void testResourceCleanupOnClose() throws Exception {
        // Given: A QueryHandler with various resources (non-streaming to avoid
        // initialize)
        MockTransport transport = createMockTransport();
        QueryHandler handler = createQueryHandler(transport, false); // non-streaming
        handler.start();

        // Create a message iterator
        handler.receiveMessages();

        // When: Close is called
        handler.close();

        // Then: All resources should be cleaned up
        assertThat(transport.isClosed()).isTrue();

        // Subsequent operations should fail or no-op
        assertThatThrownBy(() -> handler.start())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @Timeout(10)
    void testInitializeIsIdempotentNonStreaming() {
        // Given: A QueryHandler in non-streaming mode
        MockTransport transport = createMockTransport();
        QueryHandler handler = createQueryHandler(transport, false); // non-streaming
        handler.start();

        // When: Initialize called multiple times
        SDKControlResponse result1 = handler.initialize();
        SDKControlResponse result2 = handler.initialize();
        SDKControlResponse result3 = handler.initialize();

        // Then: All return null (non-streaming mode doesn't initialize)
        assertThat(result1).isNull();
        assertThat(result2).isNull();
        assertThat(result3).isNull();
    }

    @Test
    @Timeout(10)
    void testStartIsIdempotent() throws Exception {
        // Given: A QueryHandler
        MockTransport transport = createMockTransport();
        QueryHandler handler = createQueryHandler(transport, false);

        // When: Start called multiple times
        handler.start();
        handler.start();
        handler.start();

        // Then: No exceptions, single reader thread started
        // (implicitly verified by no errors)

        handler.close();
    }

    // Helper methods

    private MockTransport createMockTransport() {
        MockTransport transport = new MockTransport();
        transportsToClose.add(transport);
        return transport;
    }

    private QueryHandler createQueryHandler(MockTransport transport, boolean streaming) {
        QueryHandler handler = new QueryHandler(
                transport,
                streaming,
                null,
                null,
                Duration.ofSeconds(60));
        handlersToClose.add(handler);
        return handler;
    }

    /**
     * Mock transport for testing.
     */
    static class MockTransport implements Transport {

        private final AtomicBoolean ready = new AtomicBoolean(false);
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final AtomicReference<Iterator<Map<String, Object>>> messagesIterator = new AtomicReference<>();

        @Override
        public void connect() {
            ready.set(true);
        }

        @Override
        public Iterator<Map<String, Object>> readMessages() {
            // Return an empty iterator
            Iterator<Map<String, Object>> iter = new Iterator<>() {
                private boolean endSent = false;

                @Override
                public boolean hasNext() {
                    return !endSent && !closed.get();
                }

                @Override
                public Map<String, Object> next() {
                    if (!hasNext()) {
                        throw new java.util.NoSuchElementException();
                    }
                    endSent = true;
                    Map<String, Object> endMsg = new HashMap<>();
                    endMsg.put("type", "end");
                    return endMsg;
                }
            };
            messagesIterator.set(iter);
            return iter;
        }

        @Override
        public void write(String data) throws CLIConnectionException {
            if (closed.get()) {
                throw new CLIConnectionException("Transport closed");
            }
        }

        @Override
        public void endInput() {
            // No-op
        }

        @Override
        public boolean isReady() {
            return ready.get() && !closed.get();
        }

        @Override
        public void close() {
            closed.set(true);
        }

        public boolean isClosed() {
            return closed.get();
        }

    }

    /**
     * Mock transport that responds to control requests (for initialize testing).
     */
    static class MockRespondingTransport implements Transport {

        private final AtomicBoolean ready = new AtomicBoolean(false);
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final AtomicBoolean reading = new AtomicBoolean(false);
        private volatile String lastWrite = null;

        @Override
        public void connect() {
            ready.set(true);
        }

        @Override
        public Iterator<Map<String, Object>> readMessages() {
            reading.set(true);
            return new Iterator<>() {
                private boolean responseSent = false;
                private boolean endSent = false;

                @Override
                public boolean hasNext() {
                    return !closed.get() && (!endSent);
                }

                @Override
                public Map<String, Object> next() {
                    if (!responseSent && lastWrite != null && lastWrite.contains("initialize")) {
                        responseSent = true;
                        // Return initialize response
                        Map<String, Object> response = new HashMap<>();
                        response.put("type", "control_response");
                        Map<String, Object> responseData = new HashMap<>();
                        responseData.put("request_id", extractRequestId(lastWrite));
                        responseData.put("subtype", "success");
                        responseData.put("response", Map.of("protocolVersion", "1.0.0"));
                        response.put("response", responseData);
                        return response;
                    } else {
                        endSent = true;
                        Map<String, Object> endMsg = new HashMap<>();
                        endMsg.put("type", "end");
                        return endMsg;
                    }
                }

                private String extractRequestId(String json) {
                    // Simple extraction - find "request_id":"xxx"
                    int start = json.indexOf("\"request_id\":\"") + 14;
                    int end = json.indexOf("\"", start);
                    return json.substring(start, end);
                }
            };
        }

        @Override
        public void write(String data) throws CLIConnectionException {
            if (closed.get()) {
                throw new CLIConnectionException("Transport closed");
            }
            lastWrite = data;
        }

        @Override
        public void endInput() {
            // No-op
        }

        @Override
        public boolean isReady() {
            return ready.get() && !closed.get();
        }

        @Override
        public void close() {
            closed.set(true);
        }

        public boolean isClosed() {
            return closed.get();
        }

    }

    /**
     * Mock streaming transport that simulates a long-running reader.
     */
    static class MockStreamingTransport implements Transport {

        private final AtomicBoolean ready = new AtomicBoolean(false);
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final AtomicBoolean reading = new AtomicBoolean(false);

        @Override
        public void connect() {
            ready.set(true);
        }

        @Override
        public Iterator<Map<String, Object>> readMessages() {
            reading.set(true);
            return new Iterator<>() {
                @Override
                public boolean hasNext() {
                    // Keep reading until closed
                    while (!closed.get()) {
                        try {
                            Thread.sleep(10);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return false;
                        }
                    }
                    return false;
                }

                @Override
                public Map<String, Object> next() {
                    Map<String, Object> msg = new HashMap<>();
                    msg.put("type", "end");
                    return msg;
                }
            };
        }

        @Override
        public void write(String data) throws CLIConnectionException {
            if (closed.get()) {
                throw new CLIConnectionException("Transport closed");
            }
        }

        @Override
        public void endInput() {
            // No-op
        }

        @Override
        public boolean isReady() {
            return ready.get() && !closed.get();
        }

        @Override
        public void close() {
            closed.set(true);
        }

        public boolean isClosed() {
            return closed.get();
        }

        public boolean isReading() {
            return reading.get();
        }

    }

}
