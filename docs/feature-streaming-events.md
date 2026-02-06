# Streaming Events

Real-time partial message updates during Claude responses.

## Overview

Stream events provide incremental updates as Claude generates responses, enabling real-time UI updates and progress indication.

## Enabling Streaming

```java
var options = ClaudeAgentOptions.builder()
    .includePartialMessages(true)
    .build();
```

## StreamEvent Type

```java
record StreamEvent(
    String eventType,
    @Nullable Object delta,
    @Nullable Object data
) implements Message
```

## Event Types

### Content Events
- `"content_block_start"` - New content block begins
- `"content_block_delta"` - Incremental content update
- `"content_block_stop"` - Content block complete

### Message Events
- `"message_start"` - Message generation begins
- `"message_delta"` - Message metadata update
- `"message_stop"` - Message generation complete

## Processing Stream Events

```java
for (Message msg : client.receiveMessages()) {
    switch (msg) {
        case StreamEvent event -> {
            switch (event.eventType()) {
                case "content_block_delta" -> {
                    Map<String, Object> delta = (Map) event.delta();
                    String text = (String) delta.get("text");
                    if (text != null) {
                        System.out.print(text);  // Print as it arrives
                    }
                }
                
                case "content_block_start" -> 
                    System.out.println("\n[New block]");
                    
                case "message_stop" ->
                    System.out.println("\n[Complete]");
            }
        }
        
        case AssistantMessage assistant ->
            // Full message also received
            System.out.println("\nFull: " + assistant.getTextContent());
            
        default -> {}
    }
}
```

## Complete Example

```java
public class StreamingExample {
    public static void main(String[] args) {
        var options = ClaudeAgentOptions.builder()
            .includePartialMessages(true)
            .model("claude-sonnet-4-5")
            .build();

        try (var client = ClaudeSDK.createClient(options)) {
            client.connect();
            client.sendMessage("Explain quantum computing");

            StringBuilder current = new StringBuilder();

            for (Message msg : client.receiveMessages()) {
                switch (msg) {
                    case StreamEvent event -> {
                        if ("content_block_delta".equals(event.eventType())) {
                            Map<String, Object> delta = (Map) event.delta();
                            String text = (String) delta.get("text");
                            if (text != null) {
                                current.append(text);
                                System.out.print(text);
                                System.out.flush();
                            }
                        }
                    }
                    
                    case ResultMessage result -> {
                        System.out.println("\n\nComplete!");
                        System.out.println("Tokens: " + result.usageOutput());
                        return;  // Done
                    }
                    
                    default -> {}
                }
            }
        }
    }
}
```

## UI Integration

### Swing Example

```java
JTextArea textArea = new JTextArea();

for (Message msg : client.receiveMessages()) {
    if (msg instanceof StreamEvent event &&
        "content_block_delta".equals(event.eventType())) {
        
        Map<String, Object> delta = (Map) event.delta();
        String text = (String) delta.get("text");
        
        if (text != null) {
            SwingUtilities.invokeLater(() ->
                textArea.append(text)
            );
        }
    }
}
```

### JavaFX Example

```java
TextArea textArea = new TextArea();

for (Message msg : client.receiveMessages()) {
    if (msg instanceof StreamEvent event &&
        "content_block_delta".equals(event.eventType())) {
        
        Map<String, Object> delta = (Map) event.delta();
        String text = (String) delta.get("text");
        
        if (text != null) {
            Platform.runLater(() ->
                textArea.appendText(text)
            );
        }
    }
}
```

## See Also
- [Configuration Options](./feature-configuration-options.md#advanced-features) - includePartialMessages
- [Message Types](./feature-message-types.md#streamevent) - StreamEvent details
- [Streaming Events Example](../examples/src/main/java/examples/StreamingEvents.java)
