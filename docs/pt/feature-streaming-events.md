# Eventos de streaming

Atualizações parciais de mensagem em tempo real durante as respostas do Claude.

> **Sobre esta tradução**: a documentação em inglês é a única autoritativa. Esta tradução pode estar desatualizada em relação ao [original em inglês](../feature-streaming-events.md); em caso de divergência, o inglês prevalece. Os blocos de código são mantidos idênticos ao original e não foram traduzidos.

## Visão geral

Os eventos de stream entregam atualizações incrementais enquanto o Claude gera as respostas, o que
permite atualizar a interface em tempo real e indicar o progresso.

## Habilitando o streaming

```java
var options = ClaudeAgentOptions.builder()
    .includePartialMessages(true)
    .build();
```

## O tipo StreamEvent

```java
record StreamEvent(
    String eventType,
    @Nullable Object delta,
    @Nullable Object data
) implements Message
```

## Tipos de evento

### Eventos de conteúdo
- `"content_block_start"` — um novo bloco de conteúdo começa
- `"content_block_delta"` — atualização incremental do conteúdo
- `"content_block_stop"` — bloco de conteúdo concluído

### Eventos de mensagem
- `"message_start"` — começa a geração da mensagem
- `"message_delta"` — atualização dos metadados da mensagem
- `"message_stop"` — geração da mensagem concluída

## Processando eventos de stream

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

## Exemplo completo

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

## Integração com a interface

### Exemplo com Swing

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

### Exemplo com JavaFX

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

## Veja também
- [Opções de configuração](./feature-configuration-options.md#recursos-avançados) — includePartialMessages
- [Tipos de mensagem](./feature-message-types.md#streamevent) — detalhes de StreamEvent
- [Exemplo de eventos de streaming](../../examples/src/main/java/examples/StreamingEvents.java)
