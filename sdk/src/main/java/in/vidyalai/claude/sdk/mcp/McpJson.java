package in.vidyalai.claude.sdk.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The one {@link ObjectMapper} the {@code mcp} package uses.
 *
 * <p>
 * An {@code ObjectMapper} is thread-safe once configured and not cheap to
 * build, so the server and the tool-result helpers share this instead of each
 * holding their own.
 */
final class McpJson {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private McpJson() {
    }

}
