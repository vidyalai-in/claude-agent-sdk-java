# 流式事件

在 Claude 生成响应期间提供实时的部分消息更新。

> **关于本译文**：英文文档是唯一权威版本。本译文可能滞后于[英文原文](../feature-streaming-events.md)；若两者不一致，以英文为准。代码块保持与英文原文完全一致，未作翻译。

## 概览

流事件会在 Claude 生成响应的过程中提供增量更新，从而支持实时的 UI 刷新与进度提示。

## 启用流式传输

```java
var options = ClaudeAgentOptions.builder()
    .includePartialMessages(true)
    .build();
```

## StreamEvent 类型

```java
record StreamEvent(
    String eventType,
    @Nullable Object delta,
    @Nullable Object data
) implements Message
```

## 事件类型

### 内容事件
- `"content_block_start"` —— 新的内容块开始
- `"content_block_delta"` —— 内容的增量更新
- `"content_block_stop"` —— 内容块结束

### 消息事件
- `"message_start"` —— 消息开始生成
- `"message_delta"` —— 消息元数据更新
- `"message_stop"` —— 消息生成完成

## 处理流事件

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

## 完整示例

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

## UI 集成

### Swing 示例

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

### JavaFX 示例

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

## 另见
- [配置选项](./feature-configuration-options.md#高级功能) —— includePartialMessages
- [消息类型](./feature-message-types.md#streamevent) —— StreamEvent 详情
- [流式事件示例](../../examples/src/main/java/examples/StreamingEvents.java)
