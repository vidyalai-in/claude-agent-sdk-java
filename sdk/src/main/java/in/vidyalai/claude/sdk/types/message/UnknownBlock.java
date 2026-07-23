package in.vidyalai.claude.sdk.types.message;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A content block whose {@code type} this SDK does not model, preserved rather than rejected.
 *
 * <p>The block vocabulary is set by the CLI and the API, which ship independently of this
 * library. Treating an unrecognised {@code type} as a parse error makes every such addition a
 * hard failure in the middle of a run — one that surfaces as a dead reader thread and an
 * exception naming a type the caller never asked for, long after the useful work of the turn was
 * done. {@code image} and {@code document} both arrived that way, from nothing more exotic than
 * an agent reading a PDF.
 *
 * <p>So an unknown block becomes one of these instead: the run continues, the raw map is kept
 * intact for a caller that wants to inspect it, and {@link in.vidyalai.claude.sdk.internal.MessageParser}
 * logs the type once at {@code WARNING} so it is visible rather than silently dropped. Callers
 * switching over {@link ContentBlock} should treat it as "ignore, but do not assume nothing was
 * there".
 *
 * @param type the unrecognised block type as sent
 * @param raw  the complete block, exactly as received
 */
public record UnknownBlock(
        @JsonProperty("type") String type,
        @JsonProperty("raw") Map<String, Object> raw) implements ContentBlock {

}
