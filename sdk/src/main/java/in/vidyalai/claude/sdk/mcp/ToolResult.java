package in.vidyalai.claude.sdk.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jspecify.annotations.Nullable;

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

    private static final Logger logger = Logger.getLogger(ToolResult.class.getName());

    private static final String TYPE = "type";
    private static final String TEXT = "text";
    private static final String IMAGE = "image";
    private static final String DATA = "data";
    private static final String MIME_TYPE = "mimeType";
    private static final String CONTENT = "content";
    private static final String IS_ERROR = "isError";
    private static final String RESOURCE = "resource";
    private static final String RESOURCE_LINK = "resource_link";
    private static final String URI = "uri";
    private static final String NAME = "name";
    private static final String DESCRIPTION = "description";

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
     * Creates a result holding {@code value} serialized as JSON text.
     *
     * <p>
     * MCP content blocks are text; this is the shortcut for returning
     * structured data without reaching for your own {@code ObjectMapper}
     * inside a lambda.
     *
     * @param value the value to serialize
     * @return a new tool result carrying one text block
     * @throws IllegalArgumentException if the value cannot be serialized
     */
    public static ToolResult json(Object value) {
        return text(toJson(value));
    }

    /**
     * Creates a result from raw MCP content blocks.
     *
     * <p>
     * The blocks are normalized the way the CLI renders them, matching the
     * Python SDK: text and image pass through, a {@code resource_link} is
     * flattened to text, and a {@code resource} contributes its text. A binary
     * resource and any unrecognized block are dropped with a warning, because
     * there is nothing useful to show a model.
     *
     * @param content the content blocks
     * @return a new tool result
     */
    public static ToolResult ofContent(List<Map<String, Object>> content) {
        Builder builder = builder();
        for (Map<String, Object> block : content) {
            builder.addContent(block);
        }
        return builder.build();
    }

    /**
     * Creates a new builder for complex results.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    private static String toJson(Object value) {
        try {
            return McpJson.MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Cannot serialize a tool result to JSON: " + e.getMessage(), e);
        }
    }

    /**
     * Flattens a {@code resource_link} to the text the CLI would render.
     *
     * <p>
     * Name, URI and description joined by newlines, blanks skipped — and
     * {@code "Resource link"} when nothing at all was given, so the block is
     * never silently empty.
     */
    static String resourceLinkText(
            @Nullable String name, @Nullable String uri, @Nullable String description) {
        StringBuilder text = new StringBuilder();
        appendPart(text, name);
        appendPart(text, uri);
        appendPart(text, description);
        return (text.isEmpty() ? "Resource link" : text.toString());
    }

    /** Appends {@code part} on its own line, skipping it when there is nothing to say. */
    private static void appendPart(StringBuilder text, @Nullable String part) {
        if ((part == null) || part.isBlank()) {
            return;
        }
        if (!text.isEmpty()) {
            text.append('\n');
        }
        text.append(part);
    }

    @Nullable
    private static String asText(@Nullable Object value) {
        return (value instanceof String s) ? s : null;
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
         * Adds {@code value} serialized as a JSON text block.
         *
         * @param value the value to serialize
         * @return this builder
         * @throws IllegalArgumentException if the value cannot be serialized
         */
        public Builder addJson(Object value) {
            return addText(toJson(value));
        }

        /**
         * Adds a link to a resource, flattened to text.
         *
         * <p>
         * MCP's {@code resource_link} block is rendered by the CLI as its
         * name, URI and description on separate lines, so that is what this
         * contributes. All three are optional; with none of them the block
         * reads {@code "Resource link"}.
         *
         * @param name        the resource name
         * @param uri         the resource URI
         * @param description what the resource holds
         * @return this builder
         */
        public Builder addResourceLink(
                @Nullable String name, @Nullable String uri, @Nullable String description) {
            return addText(resourceLinkText(name, uri, description));
        }

        /**
         * Adds an embedded resource.
         *
         * <p>
         * A resource carrying {@code text} contributes that text. A binary one
         * is dropped with a warning: it cannot be turned into something a
         * model can read.
         *
         * @param resource the resource object, as MCP defines it
         * @return this builder
         */
        public Builder addResource(Map<String, Object> resource) {
            String text = asText(resource.get(TEXT));
            if (text != null) {
                return addText(text);
            }
            logger.log(Level.WARNING,
                    "Binary embedded resource cannot be converted to text, skipping");
            return this;
        }

        /**
         * Adds one raw MCP content block, normalized.
         *
         * <p>
         * See {@link ToolResult#ofContent(List)} for what each block type
         * becomes.
         *
         * @param block the content block
         * @return this builder
         */
        @SuppressWarnings("unchecked")
        public Builder addContent(Map<String, Object> block) {
            String type = asText(block.get(TYPE));
            if (type == null) {
                logger.log(Level.WARNING, "Content block with no type in tool result, skipping");
                return this;
            }
            switch (type) {
                case TEXT -> {
                    String text = asText(block.get(TEXT));
                    if (text != null) {
                        addText(text);
                    }
                }
                case IMAGE -> {
                    String data = asText(block.get(DATA));
                    String mimeType = asText(block.get(MIME_TYPE));
                    if ((data != null) && (mimeType != null)) {
                        addImage(data, mimeType);
                    }
                }
                case RESOURCE_LINK -> addResourceLink(
                        asText(block.get(NAME)), asText(block.get(URI)), asText(block.get(DESCRIPTION)));
                case RESOURCE -> {
                    if (block.get(RESOURCE) instanceof Map<?, ?> resource) {
                        addResource((Map<String, Object>) resource);
                    }
                }
                default -> logger.log(Level.WARNING,
                        "Unsupported content type ''{0}'' in tool result, skipping", type);
            }
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
