package in.vidyalai.claude.sdk.types.message;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Image content block.
 *
 * <p>The CLI emits these on the user side, not only when a caller supplies an image: reading a
 * PDF with the {@code Read} tool produces a short {@code tool_result} announcing the page count,
 * followed by a separate user message whose content is one image block per rendered page. An
 * agent given {@code Read} over a directory of PDFs will therefore see image blocks without
 * anything in its own configuration mentioning images.
 *
 * <p>{@code source} is kept as the raw map, as {@link ServerToolResultBlock#content()} is. The
 * API defines several source shapes — {@code base64}, {@code url}, {@code file} — and more can
 * be added server-side, so decoding into fixed fields here would turn a new source type into a
 * parse failure. {@link #mediaType()} and {@link #data()} cover the base64 case, which is what
 * the CLI sends today.
 *
 * <p><b>These blocks are large.</b> A base64 page image runs to a few hundred KB and a message
 * carries one per page, so a single line of CLI stdout can reach several MB. That line has to
 * fit within {@code ClaudeAgentOptions.maxBufferSize}.
 *
 * @param source the raw source map, e.g. {@code {type: "base64", media_type: "image/jpeg",
 *               data: "..."}}
 */
public record ImageBlock(
        @JsonProperty("source") Map<String, Object> source) implements ContentBlock {

    @Override
    public String type() {
        return "image";
    }

    /**
     * Returns how the image bytes are addressed — {@code "base64"}, {@code "url"} or
     * {@code "file"}.
     *
     * @return the source type, or null if absent
     */
    public String sourceType() {
        return stringField("type");
    }

    /**
     * Returns the MIME type, e.g. {@code "image/jpeg"}.
     *
     * @return the media type, or null if absent
     */
    public String mediaType() {
        return stringField("media_type");
    }

    /**
     * Returns the base64-encoded image bytes.
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
