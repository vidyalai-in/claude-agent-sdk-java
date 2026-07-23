package in.vidyalai.claude.sdk.types;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import in.vidyalai.claude.sdk.internal.MessageParser;
import in.vidyalai.claude.sdk.types.message.DocumentBlock;
import in.vidyalai.claude.sdk.types.message.ImageBlock;
import in.vidyalai.claude.sdk.types.message.Message;
import in.vidyalai.claude.sdk.types.message.UnknownBlock;
import in.vidyalai.claude.sdk.types.message.UserMessage;

/**
 * The content blocks an agent sees when it reads a PDF.
 *
 * <p>Nothing in the caller's configuration mentions images or documents — the tool grant is just
 * {@code Read}. The CLI answers a PDF read with a short {@code tool_result} announcing the page
 * count, then a <em>separate</em> user message carrying the pages. Before these types existed
 * that second message aborted the run with "Unknown content block type", after the model had
 * already done the work of the turn.
 *
 * <p>The payloads below are the shapes observed from CLI 2.1.218 reading the same 1.5 MB PDF —
 * both of them, on different runs, which is why both are modelled.
 */
class PdfContentBlockTest {

    @SuppressWarnings("null")
    @Test
    void readingAPdfCanYieldOneImageBlockPerPage() {
        Map<String, Object> data = Map.of(
                "type", "user",
                "message", Map.of(
                        "role", "user",
                        "content", List.of(
                                Map.of("type", "image", "source", Map.of(
                                        "type", "base64",
                                        "media_type", "image/jpeg",
                                        "data", "/9j/4AAQSkZJRg==")),
                                Map.of("type", "image", "source", Map.of(
                                        "type", "base64",
                                        "media_type", "image/jpeg",
                                        "data", "/9j/4AAQSkZJRh==")))));

        Message message = MessageParser.parse(data);

        assertThat(message).isInstanceOf(UserMessage.class);
        List<?> blocks = (List<?>) ((UserMessage) message).content();
        assertThat(blocks).hasSize(2).allSatisfy(b -> assertThat(b).isInstanceOf(ImageBlock.class));

        ImageBlock first = (ImageBlock) blocks.get(0);
        assertThat(first.type()).isEqualTo("image");
        assertThat(first.sourceType()).isEqualTo("base64");
        assertThat(first.mediaType()).isEqualTo("image/jpeg");
        assertThat(first.data()).isEqualTo("/9j/4AAQSkZJRg==");
    }

    @SuppressWarnings("null")
    @Test
    void readingAPdfCanInsteadYieldOneDocumentBlock() {
        // Same CLI, same file, different run. An SDK that handled only one of the two shapes
        // would fail intermittently, which is worse than failing always.
        Map<String, Object> data = Map.of(
                "type", "user",
                "message", Map.of(
                        "role", "user",
                        "content", List.of(
                                Map.of("type", "document", "source", Map.of(
                                        "type", "base64",
                                        "media_type", "application/pdf",
                                        "data", "JVBERi0xLjQ=")))));

        Message message = MessageParser.parse(data);

        List<?> blocks = (List<?>) ((UserMessage) message).content();
        assertThat(blocks).hasSize(1);

        DocumentBlock document = (DocumentBlock) blocks.get(0);
        assertThat(document.type()).isEqualTo("document");
        assertThat(document.sourceType()).isEqualTo("base64");
        assertThat(document.mediaType()).isEqualTo("application/pdf");
        assertThat(document.data()).isEqualTo("JVBERi0xLjQ=");
    }

    @SuppressWarnings("null")
    @Test
    void aSourceShapeThisSdkDoesNotDecodeStillParses() {
        // The API addresses images by url and file id too. Those source shapes are kept as the
        // raw map rather than decoded into fields, so a new one is not a parse failure.
        Map<String, Object> data = Map.of(
                "type", "user",
                "message", Map.of(
                        "role", "user",
                        "content", List.of(
                                Map.of("type", "image", "source", Map.of(
                                        "type", "url",
                                        "url", "https://example.invalid/page-1.png")))));

        ImageBlock image = (ImageBlock) ((List<?>) ((UserMessage) MessageParser.parse(data)).content()).get(0);

        assertThat(image.sourceType()).isEqualTo("url");
        assertThat(image.data()).isNull();
        assertThat(image.mediaType()).isNull();
        assertThat(image.source()).containsEntry("url", "https://example.invalid/page-1.png");
    }

    @SuppressWarnings("null")
    @Test
    void anUnmodelledBlockTypeIsPreservedRatherThanFatal() {
        // The point of the change. parse() already returns null for an unrecognised *message*
        // type for forward compatibility; a block type the CLI adds later gets the same
        // treatment instead of killing the reader thread mid-run.
        Map<String, Object> data = Map.of(
                "type", "user",
                "message", Map.of(
                        "role", "user",
                        "content", List.of(
                                Map.of("type", "text", "text", "Here it is:"),
                                Map.of("type", "some_block_from_a_later_cli",
                                        "payload", Map.of("k", "v")))));

        List<?> blocks = (List<?>) ((UserMessage) MessageParser.parse(data)).content();

        assertThat(blocks).hasSize(2);
        UnknownBlock unknown = (UnknownBlock) blocks.get(1);
        assertThat(unknown.type()).isEqualTo("some_block_from_a_later_cli");
        // The raw block is kept whole — a caller that knows the new type can still use it.
        assertThat(unknown.raw()).containsEntry("payload", Map.of("k", "v"));
    }

    @SuppressWarnings("null")
    @Test
    void anUnmodelledBlockDoesNotHideTheRestOfTheTurn() {
        // The failure this replaces lost the entire message, including the text the model had
        // already produced. Everything around the unknown block must survive.
        Map<String, Object> data = Map.of(
                "type", "assistant",
                "message", Map.of(
                        "model", "claude-opus-4-8",
                        "content", List.of(
                                Map.of("type", "unheard_of"),
                                Map.of("type", "text", "text", "The answer is 42."))));

        Message message = MessageParser.parse(data);
        var assistant = (in.vidyalai.claude.sdk.types.message.AssistantMessage) message;

        assertThat(assistant.content()).hasSize(2);
        assertThat(assistant.getTextContent()).isEqualTo("The answer is 42.");
    }
}
