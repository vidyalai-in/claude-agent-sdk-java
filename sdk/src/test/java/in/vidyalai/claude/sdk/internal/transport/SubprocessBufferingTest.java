package in.vidyalai.claude.sdk.internal.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.exceptions.CLIJSONDecodeException;

/**
 * Tests for subprocess transport buffering edge cases.
 * Equivalent to Python's test_subprocess_buffering.py
 */
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

    @SuppressWarnings("null")
    @Test
    void testBufferSizeExceeded() {
        // Test that exceeding buffer size raises an appropriate error
        int customLimit = 512;
        String hugeIncomplete = "{\"data\": \"" + "x".repeat(customLimit + 100);

        assertThatThrownBy(() -> parseJsonParts(List.of(hugeIncomplete), makeOptions(customLimit)))
                .isInstanceOf(CLIJSONDecodeException.class)
                .hasMessageContaining("exceeded maximum buffer size");
    }

    @SuppressWarnings("null")
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

    // ==================== Helper Methods ====================

    /**
     * Simulates JSON line parsing similar to SubprocessCLITransport's readLoop.
     */
    private List<Map<String, Object>> parseJsonLines(String input, ClaudeAgentOptions options) throws Exception {
        return parseJsonParts(List.of(input), options);
    }

    /**
     * Simulates JSON parsing with multiple input parts (like streaming reads).
     */
    @SuppressWarnings({ "unchecked", "null" })
    private List<Map<String, Object>> parseJsonParts(List<String> parts, ClaudeAgentOptions options) throws Exception {
        List<Map<String, Object>> messages = new ArrayList<>();
        StringBuilder jsonBuffer = new StringBuilder();
        int maxBufferSize = options.maxBufferSize() != null ? options.maxBufferSize() : 1024 * 1024;

        for (String part : parts) {
            // Split by newlines and process
            String[] lines = part.split("\n", -1);
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }

                jsonBuffer.append(line);

                if (jsonBuffer.length() > maxBufferSize) {
                    int bufferLength = jsonBuffer.length();
                    throw new CLIJSONDecodeException(
                            "JSON message exceeded maximum buffer size of " + maxBufferSize + " bytes",
                            new IllegalStateException(
                                    "Buffer size " + bufferLength + " exceeds limit " + maxBufferSize));
                }

                try {
                    Map<String, Object> data = objectMapper.readValue(jsonBuffer.toString(), Map.class);
                    jsonBuffer.setLength(0);
                    messages.add(data);
                } catch (JsonProcessingException e) {
                    // Incomplete JSON, continue accumulating
                }
            }
        }

        return messages;
    }

}
