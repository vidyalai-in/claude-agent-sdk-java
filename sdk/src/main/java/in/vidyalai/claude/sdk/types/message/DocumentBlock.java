package in.vidyalai.claude.sdk.types.message;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Document content block — a whole file, most often a PDF.
 *
 * <p>Like {@link ImageBlock}, this arrives unbidden: reading a PDF with the {@code Read} tool
 * can produce a user message carrying the entire file base64-encoded in one block, rather than
 * the per-page images {@link ImageBlock} documents. Both shapes have been observed from the same
 * CLI version against the same file, so an agent with {@code Read} must be able to parse either.
 *
 * <p>{@code source} is the raw map for the same reason as on {@link ImageBlock}: the API defines
 * {@code base64}, {@code text}, {@code url}, {@code file} and {@code content} source shapes, and
 * fixing them into fields here would make a new one a parse failure.
 *
 * <p><b>These blocks are very large.</b> Base64 is four bytes per three, so a block holding a
 * 1.5 MB PDF is a single ~2.1 MB line of CLI stdout, and a 32 MB PDF is a ~43 MB line. Reading
 * PDFs of any size means raising {@code ClaudeAgentOptions.maxBufferSize} above its 1 MB default.
 *
 * @param source the raw source map, e.g. {@code {type: "base64", media_type: "application/pdf",
 *               data: "..."}}
 */
public record DocumentBlock(
        @JsonProperty("source") Map<String, Object> source) implements ContentBlock {

    @Override
    public String type() {
        return "document";
    }

    /**
     * Returns how the document bytes are addressed — {@code "base64"}, {@code "text"},
     * {@code "url"}, {@code "file"} or {@code "content"}.
     *
     * @return the source type, or null if absent
     */
    public String sourceType() {
        return stringField("type");
    }

    /**
     * Returns the MIME type, e.g. {@code "application/pdf"}.
     *
     * @return the media type, or null if absent
     */
    public String mediaType() {
        return stringField("media_type");
    }

    /**
     * Returns the base64-encoded document bytes.
     *
     * @return the base64 payload, or null when the source is not base64-addressed
     */
    public String data() {
        return stringField("data");
    }

    private String stringField(String key) {
        if (source == null) {
            return null;
        }
        Object value = source.get(key);
        return (value instanceof String s) ? s : null;
    }

}
