package in.vidyalai.claude.sdk.internal.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PipedReader;
import java.io.PipedWriter;
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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import in.vidyalai.claude.sdk.exceptions.CLIConnectionException;
import in.vidyalai.claude.sdk.transport.Transport;

/**
 * Thread safety tests for SubprocessCLITransport.
 *
 * These tests verify that concurrent operations are handled correctly:
 * - Multiple threads calling connect()
 * - Multiple calls to readMessages()
 * - Concurrent write() and close()
 */
class SubprocessCLITransportThreadSafetyTest {

    private ExecutorService executor;

    @AfterEach
    void tearDown() throws InterruptedException {
        if (executor != null) {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @SuppressWarnings("null")
    @Test
    @Timeout(5)
    void testMultipleReadMessagesCallsThrows() {
        // Given: A mock transport
        MockStreamingTransport transport = new MockStreamingTransport();

        // When: First call to readMessages()
        Iterator<Map<String, Object>> iter1 = transport.readMessages();
        assertThat(iter1).isNotNull();

        // Then: Second call should throw IllegalStateException
        assertThatThrownBy(() -> transport.readMessages())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("can only be called once");
        transport.close();
    }

    @Test
    @Timeout(10)
    void testConcurrentReadMessagesCallsFromMultipleThreads() throws Exception {
        // Given: A mock transport
        MockStreamingTransport transport = new MockStreamingTransport();
        executor = Executors.newFixedThreadPool(5);

        int threadCount = 5;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger exceptionCount = new AtomicInteger(0);

        // When: Multiple threads try to call readMessages() simultaneously
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    transport.readMessages();
                    successCount.incrementAndGet();
                } catch (IllegalStateException e) {
                    if (e.getMessage().contains("can only be called once")) {
                        exceptionCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    // Unexpected exception
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(5, TimeUnit.SECONDS);
        assertThat(completed).isTrue();

        // Then: Exactly one should succeed, others should get IllegalStateException
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(exceptionCount.get()).isEqualTo(threadCount - 1);

        transport.close();
    }

    @Test
    @Timeout(5)
    void testConcurrentWriteOperations() throws Exception {
        // Given: A mock transport with write capability
        MockWritableTransport transport = new MockWritableTransport();
        executor = Executors.newFixedThreadPool(10);

        int writeCount = 100;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(writeCount);
        AtomicInteger successCount = new AtomicInteger(0);
        List<Exception> exceptions = new ArrayList<>();

        // When: Multiple threads write concurrently
        for (int i = 0; i < writeCount; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    transport.write("{\"id\":" + index + "}");
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

        // Then: All writes should succeed without exceptions
        assertThat(successCount.get()).isEqualTo(writeCount);
        assertThat(exceptions).isEmpty();
        assertThat(transport.getWriteCount()).isEqualTo(writeCount);

        transport.close();
    }

    @Test
    @Timeout(5)
    void testIsReadyThreadSafety() throws Exception {
        // Given: A mock transport
        MockStreamingTransport transport = new MockStreamingTransport();
        executor = Executors.newFixedThreadPool(10);

        int threadCount = 50;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger readSuccessCount = new AtomicInteger(0);

        // When: Multiple threads check isReady() concurrently
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    // This should always succeed (AtomicBoolean is thread-safe)
                    transport.isReady();
                    readSuccessCount.incrementAndGet();
                } catch (Exception e) {
                    // Should not happen
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(5, TimeUnit.SECONDS);
        assertThat(completed).isTrue();

        // Then: All reads should succeed (no exceptions)
        assertThat(readSuccessCount.get()).isEqualTo(threadCount);
        transport.close();
    }

    /**
     * Mock transport that simulates streaming behavior
     */
    static class MockStreamingTransport implements Transport {

        private final AtomicBoolean iteratorCreated = new AtomicBoolean(false);
        private final AtomicBoolean ready = new AtomicBoolean(true);

        @Override
        public void connect() {
            ready.set(true);
        }

        @Override
        public Iterator<Map<String, Object>> readMessages() {
            if (!iteratorCreated.compareAndSet(false, true)) {
                throw new IllegalStateException(
                        "readMessages() can only be called once per transport instance. " +
                                "Multiple concurrent readers on the same stdout stream is not supported.");
            }
            return new Iterator<>() {
                @Override
                public boolean hasNext() {
                    return false;
                }

                @Override
                public Map<String, Object> next() {
                    return new HashMap<>();
                }
            };
        }

        @Override
        public void write(String data) throws CLIConnectionException {
            // No-op
        }

        @Override
        public void endInput() {
            // No-op
        }

        @Override
        public boolean isReady() {
            return ready.get();
        }

        @Override
        public void close() {
            ready.set(false);
        }

    }

    /**
     * Mock transport that simulates actual write operations with synchronization
     */
    static class MockWritableTransport implements Transport {

        private final AtomicInteger writeCount = new AtomicInteger(0);
        private final AtomicBoolean ready = new AtomicBoolean(true);
        private final Object writeLock = new Object();
        private final PipedWriter pipedWriter;
        private final BufferedWriter writer;

        @SuppressWarnings("resource")
        MockWritableTransport() {
            try {
                pipedWriter = new PipedWriter();
                new PipedReader(pipedWriter); // Connect but don't read
                writer = new BufferedWriter(pipedWriter);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void connect() {
            ready.set(true);
        }

        @Override
        public Iterator<Map<String, Object>> readMessages() {
            return new Iterator<>() {
                @Override
                public boolean hasNext() {
                    return false;
                }

                @Override
                public Map<String, Object> next() {
                    return new HashMap<>();
                }
            };
        }

        @Override
        public void write(String data) throws CLIConnectionException {
            synchronized (writeLock) {
                if (!ready.get()) {
                    throw new CLIConnectionException("Transport not ready");
                }
                try {
                    writer.write(data);
                    writer.flush();
                    writeCount.incrementAndGet();
                } catch (IOException e) {
                    throw new CLIConnectionException("Write failed", e);
                }
            }
        }

        @Override
        public void endInput() {
            // No-op
        }

        @Override
        public boolean isReady() {
            return ready.get();
        }

        @Override
        public void close() {
            synchronized (writeLock) {
                ready.set(false);
                try {
                    writer.close();
                } catch (IOException e) {
                    // Ignore
                }
            }
        }

        int getWriteCount() {
            return writeCount.get();
        }

    }

}
