package in.vidyalai.claude.sdk.types.message;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Thinking content block containing Claude's reasoning.
 *
 * @param thinking  the thinking/reasoning content
 * @param signature cryptographic signature for the thinking block
 */
public record ThinkingBlock(
        @JsonProperty("thinking") String thinking,
        @JsonProperty("signature") String signature) implements ContentBlock {

    @Override
    public String type() {
        return "thinking";
    }

}
