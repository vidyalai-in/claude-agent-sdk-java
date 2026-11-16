package in.vidyalai.claude.sdk.internal.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.Reader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.ObjectMapper;

import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.exceptions.CLIJSONDecodeException;

/**
 * Tests for subprocess transport buffering edge cases, driving the
 * transport's own stdout framing ({@link SubprocessCLITransport.LineFramer}
 * and {@link SubprocessCLITransport#stdoutMessage}).
 * Equivalent to Python's test_subprocess_buffering.py
 */
@SuppressWarnings("null")
class SubprocessBufferingTest {

    private static final String DEFAULT_CLI_PATH = "/usr/bin/claude";
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static ClaudeAgentOptions makeOptions() {
        return makeOptions(null);
    }

    private static ClaudeAgentOptions makeOptions(Integer maxBufferSize) {
        var builder = ClaudeAgentOptions.builder().cliPath(Path.of(DEFAULT_CLI_PATH));
        if (maxBufferSize != null) {
            builder.maxBufferSize(maxBufferSize);
        }
        return builder.build();
    }

    // ==================== JSON Buffering Tests ====================

    @Test
    void testMultipleJsonObjectsOnSingleLine() throws Exception {
        // Test parsing when multiple JSON objects are concatenated on a single line
        Map<String, Object> jsonObj1 = Map.of("type", "message", "id", "msg1", "content", "First message");
        Map<String, Object> jsonObj2 = Map.of("type", "result", "id", "res1", "status", "completed");

        String bufferedLine = objectMapper.writeValueAsString(jsonObj1) + "\n"
                + objectMapper.writeValueAsString(jsonObj2);

        List<Map<String, Object>> messages = parseJsonLines(bufferedLine, makeOptions());

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0)).containsEntry("type", "message");
        assertThat(messages.get(0)).containsEntry("id", "msg1");
        assertThat(messages.get(0)).containsEntry("content", "First message");
        assertThat(messages.get(1)).containsEntry("type", "result");
        assertThat(messages.get(1)).containsEntry("id", "res1");
        assertThat(messages.get(1)).containsEntry("status", "completed");
    }

    @Test
    void testJsonWithEmbeddedNewlines() throws Exception {
        // Test parsing JSON objects that contain newline characters in string values
        Map<String, Object> jsonObj1 = Map.of("type", "message", "content", "Line 1\nLine 2\nLine 3");
        Map<String, Object> jsonObj2 = Map.of("type", "result", "data", "Some\nMultiline\nContent");

        String bufferedLine = objectMapper.writeValueAsString(jsonObj1) + "\n"
                + objectMapper.writeValueAsString(jsonObj2);

        List<Map<String, Object>> messages = parseJsonLines(bufferedLine, makeOptions());

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).get("content")).isEqualTo("Line 1\nLine 2\nLine 3");
        assertThat(messages.get(1).get("data")).isEqualTo("Some\nMultiline\nContent");
    }

    @Test
    void testMultipleNewlinesBetweenObjects() throws Exception {
        // Test parsing with multiple newlines between JSON objects
        Map<String, Object> jsonObj1 = Map.of("type", "message", "id", "msg1");
        Map<String, Object> jsonObj2 = Map.of("type", "result", "id", "res1");

        String bufferedLine = objectMapper.writeValueAsString(jsonObj1) + "\n\n\n"
                + objectMapper.writeValueAsString(jsonObj2);

        List<Map<String, Object>> messages = parseJsonLines(bufferedLine, makeOptions());

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).get("id")).isEqualTo("msg1");
        assertThat(messages.get(1).get("id")).isEqualTo("res1");
    }

    @Test
    void testSplitJsonAcrossMultipleReads() throws Exception {
        // Test parsing when a single JSON object is split across multiple stream reads
        Map<String, Object> content = Map.of(
                "type", "text",
                "text", "x".repeat(1000));
        Map<String, Object> toolUse = Map.of(
                "type", "tool_use",
                "id", "tool_123",
                "name", "Read",
                "input", Map.of("file_path", "/test.txt"));
        Map<String, Object> jsonObj = Map.of(
                "type", "assistant",
                "message", Map.of("content", List.of(content, toolUse)));

        String completeJson = objectMapper.writeValueAsString(jsonObj);

        // Split into parts
        String part1 = completeJson.substring(0, 100);
        String part2 = completeJson.substring(100, 250);
        String part3 = completeJson.substring(250);

        List<Map<String, Object>> messages = parseJsonParts(List.of(part1, part2, part3), makeOptions());

        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).get("type")).isEqualTo("assistant");
        @SuppressWarnings("unchecked")
        Map<String, Object> message = (Map<String, Object>) messages.get(0).get("message");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> contentList = (List<Map<String, Object>>) message.get("content");
        assertThat(contentList).hasSize(2);
    }

    @Test
    void testLargeMinifiedJson() throws Exception {
        // Test parsing a large minified JSON (simulating the reported issue)
        List<Map<String, Object>> largeData = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            largeData.add(Map.of("id", i, "value", "x".repeat(100)));
        }
        Map<String, Object> jsonObj = Map.of(
                "type", "user",
                "message", Map.of(
                        "role", "user",
                        "content", List.of(Map.of(
                                "tool_use_id", "toolu_016fed1NhiaMLqnEvrj5NUaj",
                                "type", "tool_result",
                                "content", objectMapper.writeValueAsString(Map.of("data", largeData))))));

        String completeJson = objectMapper.writeValueAsString(jsonObj);

        // Split into 64KB chunks
        int chunkSize = 64 * 1024;
        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < completeJson.length(); i += chunkSize) {
            chunks.add(completeJson.substring(i, Math.min(i + chunkSize, completeJson.length())));
        }

        List<Map<String, Object>> messages = parseJsonParts(chunks, makeOptions());

        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).get("type")).isEqualTo("user");
        @SuppressWarnings("unchecked")
        Map<String, Object> message = (Map<String, Object>) messages.get(0).get("message");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> contentList = (List<Map<String, Object>>) message.get("content");
        assertThat(contentList.get(0).get("tool_use_id")).isEqualTo("toolu_016fed1NhiaMLqnEvrj5NUaj");
    }

    @Test
    void testBufferSizeExceeded() {
        // Test that exceeding buffer size raises an appropriate error
        int customLimit = 512;
        String hugeIncomplete = "{\"data\": \"" + "x".repeat(customLimit + 100);

        assertThatThrownBy(() -> parseJsonParts(List.of(hugeIncomplete), makeOptions(customLimit)))
                .isInstanceOf(CLIJSONDecodeException.class)
                .hasMessageContaining("exceeded maximum buffer size");
    }

    @Test
    void testBufferSizeOption() {
        // Test that the configurable buffer size option is respected
        int customLimit = 512;
        String hugeIncomplete = "{\"data\": \"" + "x".repeat(customLimit + 10);

        assertThatThrownBy(() -> parseJsonParts(List.of(hugeIncomplete), makeOptions(customLimit)))
                .isInstanceOf(CLIJSONDecodeException.class)
                .hasMessageContaining("maximum buffer size of " + customLimit + " bytes");
    }

    @Test
    void testMixedCompleteAndSplitJson() throws Exception {
        // Test handling a mix of complete and split JSON messages
        String msg1 = objectMapper.writeValueAsString(Map.of("type", "system", "subtype", "start"));

        Map<String, Object> largeMsg = Map.of(
                "type", "assistant",
                "message", Map.of("content", List.of(Map.of("type", "text", "text", "y".repeat(5000)))));
        String largeJson = objectMapper.writeValueAsString(largeMsg);

        String msg3 = objectMapper.writeValueAsString(Map.of("type", "system", "subtype", "end"));

        List<String> lines = List.of(
                msg1 + "\n",
                largeJson.substring(0, 1000),
                largeJson.substring(1000, 3000),
                largeJson.substring(3000) + "\n" + msg3);

        List<Map<String, Object>> messages = parseJsonParts(lines, makeOptions());

        assertThat(messages).hasSize(3);
        assertThat(messages.get(0).get("type")).isEqualTo("system");
        assertThat(messages.get(0).get("subtype")).isEqualTo("start");
        assertThat(messages.get(1).get("type")).isEqualTo("assistant");
        @SuppressWarnings("unchecked")
        Map<String, Object> message = (Map<String, Object>) messages.get(1).get("message");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> contentList = (List<Map<String, Object>>) message.get("content");
        assertThat(((String) contentList.get(0).get("text")).length()).isEqualTo(5000);
        assertThat(messages.get(2).get("type")).isEqualTo("system");
        assertThat(messages.get(2).get("subtype")).isEqualTo("end");
    }

    @Test
    void testEmptyLines() throws Exception {
        // Test that empty lines are properly skipped
        String json1 = objectMapper.writeValueAsString(Map.of("type", "msg1"));
        String json2 = objectMapper.writeValueAsString(Map.of("type", "msg2"));

        String input = "\n\n" + json1 + "\n\n\n" + json2 + "\n\n";

        List<Map<String, Object>> messages = parseJsonLines(input, makeOptions());

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).get("type")).isEqualTo("msg1");
        assertThat(messages.get(1).get("type")).isEqualTo("msg2");
    }

    @Test
    void testWhitespaceOnlyLines() throws Exception {
        // Test that whitespace-only lines are skipped
        String json1 = objectMapper.writeValueAsString(Map.of("type", "msg1"));
        String json2 = objectMapper.writeValueAsString(Map.of("type", "msg2"));

        String input = "   \n  \t  \n" + json1 + "\n   \n" + json2;

        List<Map<String, Object>> messages = parseJsonLines(input, makeOptions());

        assertThat(messages).hasSize(2);
    }

    @Test
    void testNestedJsonObjects() throws Exception {
        // Test deeply nested JSON
        Map<String, Object> nested = Map.of(
                "level1", Map.of(
                        "level2", Map.of(
                                "level3", Map.of(
                                        "level4", Map.of("value", "deep")))));
        Map<String, Object> jsonObj = Map.of("type", "nested", "data", nested);

        String json = objectMapper.writeValueAsString(jsonObj);

        List<Map<String, Object>> messages = parseJsonLines(json, makeOptions());

        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).get("type")).isEqualTo("nested");
    }

    @Test
    void testJsonWithUnicode() throws Exception {
        // Test JSON with unicode characters
        Map<String, Object> jsonObj = Map.of(
                "type", "message",
                "content", "Hello, 世界! 🌍 Привет мир",
                "emoji", "👨‍👩‍👧‍👦");

        String json = objectMapper.writeValueAsString(jsonObj);

        List<Map<String, Object>> messages = parseJsonLines(json, makeOptions());

        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).get("content")).isEqualTo("Hello, 世界! 🌍 Привет мир");
        assertThat(messages.get(0).get("emoji")).isEqualTo("👨‍👩‍👧‍👦");
    }

    // ==================== Python parity: per-line framing ====================

    @Test
    void testNonJsonDebugLinesSkipped() throws Exception {
        String debug = "[SandboxDebug] Seccomp filtering not available";
        String msg1 = objectMapper.writeValueAsString(Map.of("type", "system", "subtype", "init"));
        String msg2 = objectMapper.writeValueAsString(Map.of("type", "result", "subtype", "success"));

        List<Map<String, Object>> messages = parseJsonParts(
                List.of(debug + "\n" + msg1 + "\n" + debug + "\n" + msg2 + "\n"), makeOptions());

        assertThat(messages).extracting(m -> m.get("type")).containsExactly("system", "result");
    }

    @Test
    void testInterleavedNonJsonLinesSkipped() throws Exception {
        List<Map<String, Object>> messages = parseJsonParts(List.of(
                "[SandboxDebug] line 1\n",
                "[SandboxDebug] line 2\n",
                "{\"type\":\"system\",\"subtype\":\"init\"}\n",
                "WARNING: something\n",
                "{\"type\":\"result\",\"subtype\":\"success\"}\n"), makeOptions());

        assertThat(messages).extracting(m -> m.get("type")).containsExactly("system", "result");
    }

    @ParameterizedTest
    @ValueSource(strings = { " ", "  ", "\u00a0", "\u2009", "\u3000" })
    void testWhitespaceAtChunkBoundaryPreserved(String ws) throws Exception {
        // Whitespace inside a JSON string that lands on a read boundary must
        // survive: only a complete line is ever stripped.
        String content = "A".repeat(1000) + ws + "B".repeat(1000);
        String line = "{\"type\":\"assistant\",\"data\":\"" + content + "\"}";
        int wsAt = line.indexOf(ws);
        List<String> chunks = List.of(line.substring(0, wsAt + ws.length()), line.substring(wsAt + ws.length()) + "\n");

        List<Map<String, Object>> messages = parseJsonParts(chunks, makeOptions());

        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).get("data")).isEqualTo(content);
    }

    @Test
    void testWhitespacePreservedAcrossRealistic64KibChunking() throws Exception {
        String content = "S" + " ".repeat(400_000) + "E";
        String line = "{\"type\":\"assistant\",\"data\":\"" + content + "\"}\n";
        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < line.length(); i += 65536) {
            chunks.add(line.substring(i, Math.min(i + 65536, line.length())));
        }
        assertThat(chunks.size()).isGreaterThan(6);

        List<Map<String, Object>> messages = parseJsonParts(chunks, makeOptions());

        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).get("data")).isEqualTo(content);
    }

    @Test
    void testCompleteOversizedLineStillRaises() {
        int limit = 512;
        String line = "{\"type\":\"x\",\"data\":\"" + "y".repeat(limit + 100) + "\"}\n";

        assertThatThrownBy(() -> parseJsonParts(List.of(line), makeOptions(limit)))
                .isInstanceOf(CLIJSONDecodeException.class)
                .hasMessageContaining("maximum buffer size of " + limit + " bytes");
    }

    @Test
    void testMalformedCompleteLineRaises() {
        // A complete line that looks like JSON but does not parse is corrupt.
        // It used to be held in the buffer, swallowing the next message.
        assertThatThrownBy(() -> parseJsonParts(
                List.of("{\"type\":\"assistant\",\"broken\n{\"type\":\"result\"}\n"), makeOptions()))
                .isInstanceOf(CLIJSONDecodeException.class)
                .hasMessageContaining("Failed to decode JSON: {\"type\":\"assistant\",\"broken");
    }

    @Test
    void testTrailingContentAfterObjectRaises() {
        // json.loads rejects "Extra data"; so does the Java parser now.
        assertThatThrownBy(() -> parseJsonParts(List.of("{\"a\":1} junk\n"), makeOptions()))
                .isInstanceOf(CLIJSONDecodeException.class);
        assertThatThrownBy(() -> SubprocessCLITransport.parseStdoutLine("{\"a\":1}{\"b\":2}"))
                .isInstanceOf(CLIJSONDecodeException.class);
    }

    @Test
    void testNonJsonLineStillSkipped() throws Exception {
        List<Map<String, Object>> messages = parseJsonParts(List.of(
                "[SandboxDebug] starting up\n{\"type\":\"result\"}\nplain text trailer\n"), makeOptions());

        assertThat(messages).containsExactly(Map.of("type", "result"));
    }

    @Test
    void testFinalMessageWithoutTrailingNewlineIsYielded() throws Exception {
        List<Map<String, Object>> messages = parseJsonParts(
                List.of("{\"type\":\"result\",\"subtype\":\"success\"}"), makeOptions());

        assertThat(messages).containsExactly(Map.of("type", "result", "subtype", "success"));
    }

    @Test
    void testTruncatedFinalLineIsDroppedNotRaised() throws Exception {
        List<Map<String, Object>> messages = parseJsonParts(
                List.of("{\"type\":\"result\"}\n{\"type\":\"assist"), makeOptions());

        assertThat(messages).containsExactly(Map.of("type", "result"));
    }

    @Test
    void testCrlfLineEndingsAreStripped() throws Exception {
        List<Map<String, Object>> messages = parseJsonParts(
                List.of("{\"type\":\"a\"}\r\n{\"type\":\"b\"}\r\n"), makeOptions());

        assertThat(messages).extracting(m -> m.get("type")).containsExactly("a", "b");
    }

    // ==================== Helper Methods ====================

    /**
     * Feeds the input to the transport's own stdout framing, as one read.
     */
    private List<Map<String, Object>> parseJsonLines(String input, ClaudeAgentOptions options) throws Exception {
        return parseJsonParts(List.of(input), options);
    }

    /**
     * Feeds each part to the transport's own stdout framing as one read of
     * the pipe, the way the CLI's output arrives in chunks.
     */
    private List<Map<String, Object>> parseJsonParts(List<String> parts, ClaudeAgentOptions options) throws Exception {
        List<Map<String, Object>> messages = new ArrayList<>();
        int maxBufferSize = options.maxBufferSize() != null ? options.maxBufferSize() : 1024 * 1024;
        SubprocessCLITransport.LineFramer framer = new SubprocessCLITransport.LineFramer(
                new ChunkedReader(parts), maxBufferSize);
        String line;
        while ((line = framer.readLine()) != null) {
            Map<String, Object> data = SubprocessCLITransport.stdoutMessage(
                    line, framer.lastLineTerminated(), maxBufferSize);
            if (data != null) {
                messages.add(data);
            }
        }
        return messages;
    }

    /** A Reader that returns each chunk from a separate read() call. */
    static final class ChunkedReader extends Reader {

        private final List<String> chunks;
        private int index;
        private int offset;

        ChunkedReader(List<String> chunks) {
            this.chunks = chunks;
        }

        @Override
        public int read(char[] cbuf, int off, int len) {
            while ((index < chunks.size()) && (offset >= chunks.get(index).length())) {
                index++;
                offset = 0;
            }
            if (index >= chunks.size()) {
                return -1;
            }
            String chunk = chunks.get(index);
            int n = Math.min(len, chunk.length() - offset);
            chunk.getChars(offset, offset + n, cbuf, off);
            offset += n;
            return n;
        }

        @Override
        public void close() {
        }

    }

}
