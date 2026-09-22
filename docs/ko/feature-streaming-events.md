# 스트리밍 이벤트

Claude가 응답을 생성하는 동안 전달되는 실시간 부분 메시지 업데이트입니다.

> **번역 안내**: 공식 문서는 영어판뿐입니다. 이 번역은 [영어 원문](../feature-streaming-events.md)보다 뒤처져 있을 수 있으며, 내용이 어긋날 경우 영어판이 기준입니다. 코드 블록은 영어 원문과 완전히 동일하게 유지되며 번역하지 않습니다.

## 개요

스트림 이벤트는 Claude가 응답을 생성해 가는 동안 증분 업데이트를 제공하여, 실시간 UI 갱신과
진행 상황 표시를 가능하게 합니다.

## 스트리밍 활성화

```java
var options = ClaudeAgentOptions.builder()
    .includePartialMessages(true)
    .build();
```

## StreamEvent 타입

```java
record StreamEvent(
    String eventType,
    @Nullable Object delta,
    @Nullable Object data
) implements Message
```

## 이벤트 종류

### 콘텐츠 이벤트
- `"content_block_start"` — 새 콘텐츠 블록 시작
- `"content_block_delta"` — 콘텐츠의 증분 업데이트
- `"content_block_stop"` — 콘텐츠 블록 완료

### 메시지 이벤트
- `"message_start"` — 메시지 생성 시작
- `"message_delta"` — 메시지 메타데이터 업데이트
- `"message_stop"` — 메시지 생성 완료

## 스트림 이벤트 처리

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

## 전체 예제

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

## UI 통합

### Swing 예제

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

### JavaFX 예제

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

## 관련 항목
- [구성 옵션](./feature-configuration-options.md#고급-기능) — includePartialMessages
- [메시지 타입](./feature-message-types.md#streamevent) — StreamEvent 세부 정보
- [스트리밍 이벤트 예제](../../examples/src/main/java/examples/StreamingEvents.java)
