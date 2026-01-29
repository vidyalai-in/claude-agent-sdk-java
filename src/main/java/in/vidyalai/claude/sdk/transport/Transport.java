package in.vidyalai.claude.sdk.transport;

import java.util.Iterator;
import java.util.Map;

import in.vidyalai.claude.sdk.exceptions.CLIConnectionException;

/**
 * Transport interface for communication with the Claude Code CLI.
 *
 * <p>
 * WARNING: This internal API is exposed for custom transport implementations
 * (e.g., remote Claude Code connections). The Claude Code team may change or
 * or remove this abstract class in any future release. Custom implementations
 * must be updated to match interface changes.
 *
 * This is a low-level transport interface that handles raw I/O with the Claude
 * process or service. The Query class builds on top of this to implement the
 * control protocol and message routing.
 */
public interface Transport extends AutoCloseable {

    /**
     * Connect the transport and prepare for communication.
     * For subprocess transports, this starts the process.
     * For network transports, this establishes the connection.
     *
     * @throws CLIConnectionException if the connection cannot be established
     */
    void connect() throws CLIConnectionException;

    /**
     * Write raw data to the transport.
     *
     * @param data the data to write (typically JSON + newline)
     * @throws CLIConnectionException if the write fails
     */
    void write(String data) throws CLIConnectionException;

    /**
     * Returns an iterator over messages from the CLI's stdout.
     *
     * <p>
     * Each message is a parsed JSON object. The iterator blocks until
     * a message is available or the stream ends.
     *
     * @return an iterator over messages
     */
    Iterator<Map<String, Object>> readMessages();

    /**
     * End the input stream (close stdin for process transports).
     *
     * <p>
     * Call this when no more input will be sent to allow the CLI
     * to finish processing and exit cleanly.
     */
    void endInput();

    /**
     * Checks if the transport is ready for communication.
     *
     * @return true if connected and ready
     */
    boolean isReady();

    /**
     * Closes the transport and releases all resources.
     */
    @Override
    void close();

}
