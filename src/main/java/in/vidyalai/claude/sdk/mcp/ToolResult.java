package in.vidyalai.claude.sdk.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Result returned from an MCP tool execution.
 *
 * <p>
 * Tool results contain content blocks that can be text, images, or other types.
 * Use the static factory methods for convenience:
 *
 * <pre>{@code
 * // Simple text result
 * ToolResult result = ToolResult.text("Hello, world!");
 *
 * // Error result
 * ToolResult error = ToolResult.error("Something went wrong");
 *
 * // Complex result with multiple content items
 * ToolResult complex = ToolResult.builder()
 *         .addText("Result: 42")
 *         .addText("Computation complete")
 *         .build();
 * }</pre>
 */
public final class ToolResult {

    private static final String TYPE = "type";
    private static final String TEXT = "text";
    private static final String IMAGE = "image";
    private static final String DATA = "data";
    private static final String MIME_TYPE = "mimeType";
    private static final String CONTENT = "content";
    private static final String IS_ERROR = "is_error";

    private final List<Map<String, Object>> content;
    private final boolean isError;

    private ToolResult(List<Map<String, Object>> content, boolean isError) {
        this.content = List.copyOf(content);
        this.isError = isError;
    }

    /**
     * Creates a simple text result.
     *
     * @param text the text content
     * @return a new tool result
     */
    public static ToolResult text(String text) {
        return new ToolResult(
                List.of(Map.of(TYPE, TEXT, TEXT, text)),
                false);
    }

    /**
     * Creates an error result.
     *
     * @param errorMessage the error message
     * @return a new tool result marked as error
     */
    public static ToolResult error(String errorMessage) {
        return new ToolResult(
                List.of(Map.of(TYPE, TEXT, TEXT, errorMessage)),
                true);
    }

    /**
     * Creates an image result.
     *
     * @param data     base64-encoded image data
     * @param mimeType the MIME type (e.g., "image/png")
     * @return a new tool result
     */
    public static ToolResult image(String data, String mimeType) {
        return new ToolResult(
                List.of(Map.of(TYPE, IMAGE, DATA, data, MIME_TYPE, mimeType)),
                false);
    }

    /**
     * Creates a new builder for complex results.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    // Getters

    public List<Map<String, Object>> content() {
        return content;
    }

    public boolean isError() {
        return isError;
    }

    /**
     * Converts this result to a map for JSON serialization.
     *
     * @return a map representation
     */
    public Map<String, Object> toMap() {
        if (isError) {
            return Map.of(CONTENT, content, IS_ERROR, true);
        }
        return Map.of(CONTENT, content);
    }

    /**
     * Builder for complex tool results.
     */
    public static final class Builder {

        private final List<Map<String, Object>> content = new ArrayList<>();
        private boolean isError = false;

        private Builder() {
        }

        /**
         * Adds a text content item.
         *
         * @param text the text content
         */
        public Builder addText(String text) {
            content.add(Map.of(TYPE, TEXT, TEXT, text));
            return this;
        }

        /**
         * Adds an image content item.
         *
         * @param data     base64-encoded image data
         * @param mimeType the MIME type
         */
        public Builder addImage(String data, String mimeType) {
            content.add(Map.of(TYPE, IMAGE, DATA, data, MIME_TYPE, mimeType));
            return this;
        }

        /**
         * Marks this result as an error.
         */
        public Builder isError(boolean isError) {
            this.isError = isError;
            return this;
        }

        /**
         * Builds the result.
         *
         * @return a new ToolResult
         */
        public ToolResult build() {
            return new ToolResult(content, isError);
        }

    }

}
