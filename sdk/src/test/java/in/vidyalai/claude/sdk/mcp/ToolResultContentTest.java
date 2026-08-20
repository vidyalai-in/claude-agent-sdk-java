package in.vidyalai.claude.sdk.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The content shapes a tool may return.
 *
 * <p>
 * The Python SDK accepts resource links and embedded resources from a handler
 * and flattens them to text, because text is what the CLI renders
 * ({@code _convert_tool_content}). Java could only produce text and image
 * blocks, so a resource link had nowhere to go. These pin the ported
 * conversion.
 */
class ToolResultContentTest {

    private static List<String> textsOf(ToolResult result) {
        return result.content().stream().map(block -> String.valueOf(block.get("text"))).toList();
    }

    @Test
    void jsonSerializesToASingleTextBlock() {
        ToolResult result = ToolResult.json(Map.of("status", "ok"));

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).singleElement()
                .satisfies(block -> assertThat(block).containsEntry("type", "text"));
        assertThat(textsOf(result)).containsExactly("{\"status\":\"ok\"}");
    }

    @Test
    void jsonOfSomethingUnserializableFailsLoudlyAtConstruction() {
        Object unserializable = new Object() {
            @SuppressWarnings("unused")
            public Object getSelf() {
                throw new IllegalStateException("nope");
            }
        };

        Throwable thrown = catchThrowable(() -> ToolResult.json(unserializable));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
        assertThat(thrown).hasMessageContaining("Cannot serialize");
    }

    @Test
    void aResourceLinkFlattensToNameUriAndDescription() {
        ToolResult result = ToolResult.builder()
                .addResourceLink("Report", "file:///report.pdf", "Last quarter")
                .build();

        assertThat(textsOf(result)).containsExactly("Report\nfile:///report.pdf\nLast quarter");
    }

    @Test
    void aResourceLinkSkipsTheBlankParts() {
        ToolResult result = ToolResult.builder()
                .addResourceLink(null, "file:///only.txt", "   ")
                .build();

        assertThat(textsOf(result)).containsExactly("file:///only.txt");
    }

    @Test
    void aResourceLinkWithNothingSetIsStillReadable() {
        ToolResult result = ToolResult.builder().addResourceLink(null, null, null).build();

        assertThat(textsOf(result)).containsExactly("Resource link");
    }

    @Test
    void aTextResourceContributesItsText() {
        ToolResult result = ToolResult.builder()
                .addResource(Map.of("uri", "file:///notes.txt", "text", "the notes"))
                .build();

        assertThat(textsOf(result)).containsExactly("the notes");
    }

    @Test
    void aBinaryResourceIsDropped() {
        // There is nothing a model can read in a base64 blob, so Python drops
        // it with a warning rather than inventing a rendering.
        ToolResult result = ToolResult.builder()
                .addResource(Map.of("uri", "file:///image.bin", "blob", "AAAA"))
                .build();

        assertThat(result.content()).isEmpty();
    }

    @Test
    void ofContentPassesTextAndImageThroughUnchanged() {
        ToolResult result = ToolResult.ofContent(List.of(
                Map.of("type", "text", "text", "hello"),
                Map.of("type", "image", "data", "AAAA", "mimeType", "image/png")));

        assertThat(result.content()).containsExactly(
                Map.of("type", "text", "text", "hello"),
                Map.of("type", "image", "data", "AAAA", "mimeType", "image/png"));
    }

    @Test
    void ofContentConvertsResourcesAndPreservesOrder() {
        ToolResult result = ToolResult.ofContent(List.of(
                Map.of("type", "text", "text", "before"),
                Map.of("type", "resource_link", "name", "Docs", "uri", "https://example.test"),
                Map.of("type", "resource", "resource", Map.of("text", "inline")),
                Map.of("type", "text", "text", "after")));

        assertThat(textsOf(result))
                .containsExactly("before", "Docs\nhttps://example.test", "inline", "after");
    }

    @Test
    void ofContentDropsBlocksItCannotRender() {
        ToolResult result = ToolResult.ofContent(List.of(
                Map.of("type", "audio", "data", "AAAA", "mimeType", "audio/wav"),
                Map.of("data", "no type at all"),
                Map.of("type", "text", "text", "kept")));

        assertThat(textsOf(result)).containsExactly("kept");
    }

    @Test
    void theExistingFactoriesAreUnchanged() {
        assertThat(ToolResult.text("hi").toMap())
                .isEqualTo(Map.of("content", List.of(Map.of("type", "text", "text", "hi"))));
        assertThat(ToolResult.error("bad").toMap()).containsEntry("isError", true);
    }

}
