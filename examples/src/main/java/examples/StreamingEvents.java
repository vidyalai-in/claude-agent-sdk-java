package examples;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.types.message.AssistantMessage;
import in.vidyalai.claude.sdk.types.message.ContentBlock;
import in.vidyalai.claude.sdk.types.message.Message;
import in.vidyalai.claude.sdk.types.message.ResultMessage;
import in.vidyalai.claude.sdk.types.message.StreamEvent;
import in.vidyalai.claude.sdk.types.message.ToolResultBlock;
import in.vidyalai.claude.sdk.types.message.UserMessage;
import in.vidyalai.claude.sdk.types.permission.PermissionMode;

/**
 * Example showing how to use streaming events for real-time updates.
 */
public class StreamingEvents {

    public static void main(String[] args) {
        // Example 1: Basic streaming
        System.out.println("=== Basic Streaming ===");
        basicStreaming();

        // Example 2: Processing different event types
        System.out.println("\n=== Event Types ===");
        eventTypes();

        // Example 3: Building response incrementally
        System.out.println("\n=== Incremental Response ===");
        incrementalResponse();

        // Example 4: Streaming with tools
        System.out.println("\n=== Streaming with Tools ===");
        streamingWithTools();
    }

    /**
     * Basic streaming with partial messages.
     */
    static void basicStreaming() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .includePartialMessages(true)
                .maxTurns(1)
                .build();

        try (var client = ClaudeSDK.createClient(options)) {
            client.connect();
            client.sendMessage("Count from 1 to 5, saying each number on a new line");

            System.out.println("Streaming response:");
            Iterator<Message> iter = client.receiveMessages();
            while (iter.hasNext()) {
                Message msg = iter.next();
                if (msg instanceof StreamEvent event) {
                    // Print streaming delta
                    String eventType = event.eventType();
                    if ("content_block_delta".equals(eventType)) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> delta = (Map<String, Object>) event.event().get("delta");
                        if ((delta != null) && delta.containsKey("text")) {
                            System.out.print(delta.get("text"));
                        }
                    }
                } else if (msg instanceof AssistantMessage assistant) {
                    // Complete message
                    System.out.println("\n--- Complete message received ---: " + assistant);
                } else if (msg instanceof ResultMessage result) {
                    System.out.println("\nDone! Cost: $" + result.totalCostUsd());
                    break;
                }
            }
        }
    }

    /**
     * Processing different types of stream events.
     */
    @SuppressWarnings("null")
    static void eventTypes() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .includePartialMessages(true)
                .maxTurns(1)
                .build();

        try (var client = ClaudeSDK.createClient(options)) {
            client.connect();
            client.sendMessage("Say 'Hello World'");

            Iterator<Message> iter = client.receiveMessages();
            while (iter.hasNext()) {
                Message msg = iter.next();
                if (msg instanceof StreamEvent event) {
                    String eventType = event.eventType();

                    switch (eventType) {
                        case "message_start" -> {
                            System.out.println("[EVENT] Message started");
                            System.out.println("  Session: " + event.sessionId());
                        }
                        case "content_block_start" -> {
                            System.out.println("[EVENT] Content block started");
                            @SuppressWarnings("unchecked")
                            Map<String, Object> contentBlock = (Map<String, Object>) event.event().get("content_block");
                            if (contentBlock != null) {
                                System.out.println("  Type: " + contentBlock.get("type"));
                            }
                        }
                        case "content_block_delta" -> {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> delta = (Map<String, Object>) event.event().get("delta");
                            if (delta != null) {
                                String deltaType = (String) delta.get("type");
                                if ("text_delta".equals(deltaType)) {
                                    System.out.print(delta.get("text"));
                                } else if ("thinking_delta".equals(deltaType)) {
                                    System.out.print("[thinking: " + delta.get("thinking") + "]");
                                }
                            }
                        }
                        case "content_block_stop" -> {
                            System.out.println("\n[EVENT] Content block stopped");
                        }
                        case "message_delta" -> {
                            System.out.println("[EVENT] Message delta");
                        }
                        case "message_stop" -> {
                            System.out.println("[EVENT] Message stopped");
                        }
                        default -> {
                            System.out.println("[EVENT] " + eventType);
                        }
                    }
                } else if (msg instanceof ResultMessage r) {
                    System.out.println("[RESULT] Conversation complete: " + r);
                    break;
                }
            }
        }
    }

    /**
     * Building response incrementally from stream events.
     */
    static void incrementalResponse() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .includePartialMessages(true)
                .maxTurns(1)
                .build();

        StringBuilder fullResponse = new StringBuilder();
        int deltaCount = 0;

        try (var client = ClaudeSDK.createClient(options)) {
            client.connect();
            client.sendMessage("Write a haiku about Java programming");

            System.out.println("Building response incrementally:\n");

            Iterator<Message> iter = client.receiveMessages();
            while (iter.hasNext()) {
                Message msg = iter.next();
                if (msg instanceof StreamEvent event) {
                    if ("content_block_delta".equals(event.eventType())) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> delta = (Map<String, Object>) event.event().get("delta");
                        if ((delta != null) && delta.containsKey("text")) {
                            String text = (String) delta.get("text");
                            fullResponse.append(text);
                            deltaCount++;

                            // Show progress
                            System.out.print(text);
                            System.out.flush();
                        }
                    }
                } else if (msg instanceof ResultMessage r) {
                    System.out.println("\n[RESULT] Conversation complete: " + r);
                    break;
                }
            }

            System.out.println("\n\n--- Statistics ---");
            System.out.println("Total deltas received: " + deltaCount);
            System.out.println("Final response length: " + fullResponse.length() + " chars");
            System.out.println("\nFull response:\n" + fullResponse);
        }
    }

    /**
     * Streaming with tool usage.
     */
    @SuppressWarnings("null")
    static void streamingWithTools() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .includePartialMessages(true)
                .allowedTools(List.of("Bash"))
                .permissionMode(PermissionMode.ACCEPT_EDITS)
                .maxTurns(3)
                .build();

        try (var client = ClaudeSDK.createClient(options)) {
            client.connect();
            client.sendMessage("Run 'echo Hello' and tell me what happened");

            System.out.println("Streaming with tools:\n");

            Iterator<Message> iter = client.receiveMessages();
            while (iter.hasNext()) {
                Message msg = iter.next();
                if (msg instanceof StreamEvent event) {
                    String eventType = event.eventType();

                    if ("content_block_start".equals(eventType)) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> contentBlock = (Map<String, Object>) event.event().get("content_block");
                        if (contentBlock != null) {
                            String type = (String) contentBlock.get("type");
                            if ("tool_use".equals(type)) {
                                System.out.println("\n[TOOL] Starting: " + contentBlock.get("name"));
                            }
                        }
                    } else if ("content_block_delta".equals(eventType)) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> delta = (Map<String, Object>) event.event().get("delta");
                        if (delta != null) {
                            if (delta.containsKey("text")) {
                                System.out.print(delta.get("text"));
                            } else if (delta.containsKey("partial_json")) {
                                // Tool input being streamed
                                System.out.print(".");
                            }
                        }
                    }
                } else if (msg instanceof AssistantMessage assistant) {
                    if (assistant.hasToolUse()) {
                        System.out.println("\n[TOOL] Executing...");
                    }
                } else if (msg instanceof UserMessage user) {
                    // Tool results
                    for (ContentBlock block : user.contentAsBlocks()) {
                        if (block instanceof ToolResultBlock result) {
                            System.out.println("[TOOL RESULT] " + truncate(String.valueOf(result.content()), 50));
                        }
                    }
                } else if (msg instanceof ResultMessage r) {
                    System.out.println("\n[RESULT] Conversation complete: " + r);
                    break;
                }
            }
        }
    }

    private static String truncate(String text, int maxLength) {
        if (text == null)
            return "";
        if (text.length() <= maxLength)
            return text;
        return text.substring(0, maxLength) + "...";
    }

}
