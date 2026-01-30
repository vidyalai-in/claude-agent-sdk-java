package in.vidyalai.claude.sdk.types.message;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Text content block containing plain text.
 *
 * @param text the text content
 */
public record TextBlock(
        @JsonProperty("text") String text) implements ContentBlock {

    @Override
    public String type() {
        return "text";
    }

}
