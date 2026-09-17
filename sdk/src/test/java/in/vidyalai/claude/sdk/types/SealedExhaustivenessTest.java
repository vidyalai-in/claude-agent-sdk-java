package in.vidyalai.claude.sdk.types;

import static org.assertj.core.api.Assertions.assertThat;

import in.vidyalai.claude.sdk.types.config.ThinkingConfig;
import in.vidyalai.claude.sdk.types.control.request.SDKControlRequestData;
import in.vidyalai.claude.sdk.types.control.response.ControlResponseData;
import in.vidyalai.claude.sdk.types.message.ContentBlock;
import in.vidyalai.claude.sdk.types.message.Message;
import in.vidyalai.claude.sdk.types.permission.PermissionResult;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Tripwire for the sealed hierarchies the SDK dispatches over.
 *
 * <p>The SDK targets Java 17, so its dispatch sites are {@code instanceof}
 * chains rather than pattern switches. A pattern switch over a sealed type with
 * no {@code default} fails to compile the moment a new subtype is permitted;
 * an {@code instanceof} chain silently falls through to its {@code else} branch
 * instead. These tests restore that signal: adding a permitted subtype breaks
 * this test, and the failure message names every chain to update.
 *
 * <p>This is deliberately a list of names rather than a behavioural check. It
 * cannot verify that a chain handles a subtype correctly — only that nobody
 * adds one without being told where to look.
 */
class SealedExhaustivenessTest {

    private static List<String> permitted(Class<?> sealedType) {
        Class<?>[] subclasses = sealedType.getPermittedSubclasses();
        assertThat(subclasses)
                .as("%s must stay sealed", sealedType.getSimpleName())
                .isNotNull();
        return Arrays.stream(subclasses)
                // Lambda, not Class::getSimpleName: a receiver-style method
                // reference makes the stream element the method-descriptor's
                // 'this', which JDT's null analysis reports as an unchecked
                // conversion to @NonNull.
                .map(subclass -> subclass.getSimpleName())
                .sorted()
                .collect(Collectors.toList());
    }

    @Test
    void messageSubtypesAreDispatchedEverywhere() {
        assertThat(permitted(Message.class))
                .as("A new Message subtype was added. Update every dispatch site: "
                        + "MessageParser.parseMessage, and the describe(Message) helper in TypesTest. "
                        + "Consumers on JDK 21+ who pattern-switch on Message will also see a "
                        + "compile error, which is the intended signal.")
                .containsExactly(
                        "AssistantMessage",
                        "ConversationResetMessage",
                        "HookEventMessage",
                        "MirrorErrorMessage",
                        "RateLimitEvent",
                        "ResultMessage",
                        "StreamEvent",
                        "SystemMessage",
                        "TaskNotificationMessage",
                        "TaskProgressMessage",
                        "TaskStartedMessage",
                        "TaskUpdatedMessage",
                        "UserMessage");
    }

    @Test
    void contentBlockSubtypesAreDispatchedEverywhere() {
        assertThat(permitted(ContentBlock.class))
                .as("A new ContentBlock subtype was added. Update MessageParser.parseContentBlock "
                        + "and the describe(ContentBlock) helper in TypesTest.")
                .containsExactly(
                        "DocumentBlock",
                        "ImageBlock",
                        "ServerToolResultBlock",
                        "ServerToolUseBlock",
                        "TextBlock",
                        "ThinkingBlock",
                        "ToolResultBlock",
                        "ToolUseBlock",
                        "UnknownBlock");
    }

    @Test
    void controlRequestSubtypesAreDispatchedEverywhere() {
        assertThat(permitted(SDKControlRequestData.class))
                .as("A new SDKControlRequestData subtype was added. Update the instanceof chain in "
                        + "QueryHandler.handleControlRequest — an unhandled subtype now reaches the "
                        + "trailing else and is answered with an error instead of failing to compile.")
                .containsExactly(
                        "SDKControlGetContextUsageRequest",
                        "SDKControlInitializeRequest",
                        "SDKControlInterruptRequest",
                        "SDKControlMCPStatusRequest",
                        "SDKControlMcpMessageRequest",
                        "SDKControlMcpReconnectRequest",
                        "SDKControlMcpToggleRequest",
                        "SDKControlPermissionRequest",
                        "SDKControlRewindFilesRequest",
                        "SDKControlSetModelRequest",
                        "SDKControlSetPermissionModeRequest",
                        "SDKControlStopTaskRequest",
                        "SDKHookCallbackRequest");
    }

    @Test
    void controlResponseSubtypesAreDispatchedEverywhere() {
        assertThat(permitted(ControlResponseData.class))
                .as("A new ControlResponseData subtype was added. Update the instanceof chain in "
                        + "QueryHandler.handleControlResponse.")
                .containsExactly("ControlErrorResponse", "ControlResponse");
    }

    @Test
    void permissionResultSubtypesAreDispatchedEverywhere() {
        assertThat(permitted(PermissionResult.class))
                .as("A new PermissionResult subtype was added. Update the instanceof chain in "
                        + "QueryHandler.handleControlRequest that serializes the permission result.")
                .containsExactly("PermissionResultAllow", "PermissionResultDeny");
    }

    @Test
    void thinkingConfigSubtypesAreDispatchedEverywhere() {
        assertThat(permitted(ThinkingConfig.class))
                .as("A new ThinkingConfig subtype was added. Update the instanceof chain in "
                        + "SubprocessCLITransport that builds the --thinking CLI flags.")
                .containsExactly(
                        "ThinkingConfigAdaptive", "ThinkingConfigDisabled", "ThinkingConfigEnabled");
    }
}
