package in.vidyalai.claude.sdk.mcp;

import java.util.HashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Interface for providing MCP tool annotation hints.
 *
 * <p>
 * Implement this interface to supply semantic hints about tool behavior. Use
 * the implementation class in the {@link Tool#annotations()} attribute.
 *
 * <h2>Annotation Types</h2>
 * <ul>
 * <li><b>readOnlyHint:</b> Indicates the tool only reads data without modifying
 * state</li>
 * <li><b>destructiveHint:</b> Warns that the tool performs irreversible
 * operations</li>
 * <li><b>idempotentHint:</b> Signals that repeated calls with same inputs
 * produce same results</li>
 * <li><b>openWorldHint:</b> Indicates the tool queries external systems with
 * unbounded results</li>
 * </ul>
 *
 * <h2>Example Usage</h2>
 *
 * <pre>{@code
 * // Implement the interface to describe hints
 * public class ReadOnlyHints implements ToolAnnotations {
 *     public Boolean readOnlyHint() { return true; }
 * }
 *
 * // Use with @Tool annotation
 * @Tool(name = "read_data", description = "Read data", title = "Data Reader", annotations = ReadOnlyHints.class)
 * public ToolResult readData(Map<String, Object> args) { ... }
 *
 * // Or use the builder for SdkMcpTool
 * ToolAnnotations hints = ToolAnnotations.builder()
 *         .destructiveHint(true)
 *         .idempotentHint(true)
 *         .build();
 *
 * SdkMcpTool<?> tool = SdkMcpTool.builder("delete_file", "Delete a file")
 *         .title("File Deleter")
 *         .annotations(hints)
 *         .build();
 * }</pre>
 *
 * @see SdkMcpTool
 * @see Tool
 */
public interface ToolAnnotations {

    /**
     * Indicates this tool only reads data without modifying state.
     */
    @Nullable
    default Boolean readOnlyHint() {
        return null;
    }

    /**
     * Warns that this tool performs irreversible operations.
     */
    @Nullable
    default Boolean destructiveHint() {
        return null;
    }

    /**
     * Signals that repeated calls with same inputs produce same results.
     */
    @Nullable
    default Boolean idempotentHint() {
        return null;
    }

    /**
     * Indicates this tool queries external systems with unbounded results.
     */
    @Nullable
    default Boolean openWorldHint() {
        return null;
    }

    /**
     * Maximum result size in characters for this tool.
     *
     * <p>
     * Controls the CLI's layer-2 tool-result spill threshold. When set, the CLI
     * uses this value instead of its hardcoded 50K char default, allowing tools
     * that return large results to avoid being spilled to temp files.
     *
     * <p>
     * This value is forwarded via {@code _meta} with the key
     * {@code anthropic/maxResultSizeChars} in the tools/list JSONRPC response,
     * bypassing Zod annotation stripping.
     */
    @Nullable
    default Integer maxResultSizeChars() {
        return null;
    }

    /**
     * Checks if any annotations are set.
     *
     * @return true if at least one annotation is non-null
     */
    @JsonIgnore
    default boolean hasAnyAnnotation(@Nullable String title) {
        return (((title != null) && (!title.isBlank())) || (readOnlyHint() != null)
                || (destructiveHint() != null) || (idempotentHint() != null)
                || (openWorldHint() != null));
    }

    /**
     * Converts the tool annotations to a Map representation.
     *
     * @return a map containing non-null annotation properties
     */
    @Nullable
    @JsonIgnore
    default Map<String, Object> toMap(@Nullable String title) {
        if (!hasAnyAnnotation(title)) {
            return null;
        }

        Map<String, Object> map = new HashMap<>();
        if ((title != null) && (!title.isBlank())) {
            map.put("title", title.trim());
        }
        if (readOnlyHint() != null) {
            map.put("readOnlyHint", readOnlyHint());
        }
        if (destructiveHint() != null) {
            map.put("destructiveHint", destructiveHint());
        }
        if (idempotentHint() != null) {
            map.put("idempotentHint", idempotentHint());
        }
        if (openWorldHint() != null) {
            map.put("openWorldHint", openWorldHint());
        }
        return map;
    }

    /**
     * Creates a new builder for tool annotations.
     *
     * @return a new builder instance
     */
    static Builder builder() {
        return new Builder();
    }

    /**
     * The shared {@link None} instance.
     *
     * <p>
     * Lets a caller ask a tool that declared no annotations for its
     * annotation map — which is how a {@code title} set on its own still
     * reaches {@code tools/list}.
     */
    ToolAnnotations NONE = new None();

    /**
     * Sentinel implementation used as the default for {@link Tool#annotations()}.
     * Represents the absence of annotations.
     */
    final class None implements ToolAnnotations {
    }

    /**
     * Concrete implementation returned by {@link Builder}.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    final class Impl implements ToolAnnotations {

        @Nullable
        @JsonProperty("readOnlyHint")
        private final Boolean readOnlyHint;

        @Nullable
        @JsonProperty("destructiveHint")
        private final Boolean destructiveHint;

        @Nullable
        @JsonProperty("idempotentHint")
        private final Boolean idempotentHint;

        @Nullable
        @JsonProperty("openWorldHint")
        private final Boolean openWorldHint;

        @Nullable
        @JsonProperty("maxResultSizeChars")
        private final Integer maxResultSizeChars;

        Impl(@Nullable Boolean readOnlyHint, @Nullable Boolean destructiveHint,
                @Nullable Boolean idempotentHint, @Nullable Boolean openWorldHint,
                @Nullable Integer maxResultSizeChars) {
            this.readOnlyHint = readOnlyHint;
            this.destructiveHint = destructiveHint;
            this.idempotentHint = idempotentHint;
            this.openWorldHint = openWorldHint;
            this.maxResultSizeChars = maxResultSizeChars;
        }

        @Override
        public @Nullable Boolean readOnlyHint() {
            return readOnlyHint;
        }

        @Override
        public @Nullable Boolean destructiveHint() {
            return destructiveHint;
        }

        @Override
        public @Nullable Boolean idempotentHint() {
            return idempotentHint;
        }

        @Override
        public @Nullable Boolean openWorldHint() {
            return openWorldHint;
        }

        @Override
        public @Nullable Integer maxResultSizeChars() {
            return maxResultSizeChars;
        }

        @JsonIgnore
        @Override
        public boolean hasAnyAnnotation(@Nullable String title) {
            return ToolAnnotations.super.hasAnyAnnotation(title);
        }

        @Nullable
        @JsonIgnore
        @Override
        public Map<String, Object> toMap(@Nullable String title) {
            return ToolAnnotations.super.toMap(title);
        }

    }

    /**
     * Builder for creating {@link ToolAnnotations} instances.
     */
    final class Builder {

        @Nullable
        private Boolean readOnlyHint;

        @Nullable
        private Boolean destructiveHint;

        @Nullable
        private Boolean idempotentHint;

        @Nullable
        private Boolean openWorldHint;

        @Nullable
        private Integer maxResultSizeChars;

        private Builder() {
        }

        /**
         * Sets the read-only hint.
         *
         * @param readOnlyHint true if tool is read-only
         * @return this builder
         */
        public Builder readOnlyHint(boolean readOnlyHint) {
            this.readOnlyHint = readOnlyHint;
            return this;
        }

        /**
         * Sets the destructive hint.
         *
         * @param destructiveHint true if tool is destructive
         * @return this builder
         */
        public Builder destructiveHint(boolean destructiveHint) {
            this.destructiveHint = destructiveHint;
            return this;
        }

        /**
         * Sets the idempotent hint.
         *
         * @param idempotentHint true if tool is idempotent
         * @return this builder
         */
        public Builder idempotentHint(boolean idempotentHint) {
            this.idempotentHint = idempotentHint;
            return this;
        }

        /**
         * Sets the open-world hint.
         *
         * @param openWorldHint true if tool queries open-world systems
         * @return this builder
         */
        public Builder openWorldHint(boolean openWorldHint) {
            this.openWorldHint = openWorldHint;
            return this;
        }

        /**
         * Sets the maximum result size in characters.
         *
         * @param maxResultSizeChars max chars before CLI spills to temp file
         * @return this builder
         */
        public Builder maxResultSizeChars(int maxResultSizeChars) {
            this.maxResultSizeChars = maxResultSizeChars;
            return this;
        }

        /**
         * Builds the tool annotations.
         *
         * @return a new ToolAnnotations instance
         */
        public ToolAnnotations build() {
            return new Impl(readOnlyHint, destructiveHint, idempotentHint, openWorldHint, maxResultSizeChars);
        }

    }

}
