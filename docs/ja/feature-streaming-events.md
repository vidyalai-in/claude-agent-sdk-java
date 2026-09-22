# ストリーミングイベント

Claude の応答生成中に届く、部分メッセージのリアルタイム更新です。

> **この翻訳について**：正式なドキュメントは英語版のみです。この翻訳は[英語の原文](../feature-streaming-events.md)より古い場合があります。内容が食い違う場合は英語版が正となります。コードブロックは英語の原文と完全に同一で、翻訳していません。

## 概要

ストリームイベントは、Claude が応答を生成していく過程で増分的な更新を届けます。これにより
リアルタイムな UI 更新や進捗表示が可能になります。

## ストリーミングの有効化

```java
var options = ClaudeAgentOptions.builder()
    .includePartialMessages(true)
    .build();
```

## StreamEvent 型

```java
record StreamEvent(
    String eventType,
    @Nullable Object delta,
    @Nullable Object data
) implements Message
```

## イベントの種類

### コンテンツ関連のイベント
- `"content_block_start"` —— 新しいコンテンツブロックの開始
- `"content_block_delta"` —— コンテンツの増分更新
- `"content_block_stop"` —— コンテンツブロックの完了

### メッセージ関連のイベント
- `"message_start"` —— メッセージ生成の開始
- `"message_delta"` —— メッセージのメタデータ更新
- `"message_stop"` —— メッセージ生成の完了

## ストリームイベントの処理

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

## 完全なサンプル

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

## UI との統合

### Swing のサンプル

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

### JavaFX のサンプル

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

## 関連項目
- [設定オプション](./feature-configuration-options.md#高度な機能) —— includePartialMessages
- [メッセージ型](./feature-message-types.md#streamevent) —— StreamEvent の詳細
- [ストリーミングイベントのサンプル](../../examples/src/main/java/examples/StreamingEvents.java)
