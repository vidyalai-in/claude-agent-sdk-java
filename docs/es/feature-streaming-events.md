# Eventos de streaming

Actualizaciones parciales de mensaje en tiempo real durante las respuestas de Claude.

> **Sobre esta traducción**: la documentación en inglés es la única autoritativa. Esta traducción puede estar desactualizada respecto al [original en inglés](../feature-streaming-events.md); si hay discrepancias, prevalece el inglés. Los bloques de código se mantienen idénticos al original y no se han traducido.

## Visión general

Los eventos de stream entregan actualizaciones incrementales mientras Claude genera las
respuestas, lo que permite refrescar la interfaz en tiempo real e indicar el progreso.

## Habilitar el streaming

```java
var options = ClaudeAgentOptions.builder()
    .includePartialMessages(true)
    .build();
```

## El tipo StreamEvent

```java
record StreamEvent(
    String eventType,
    @Nullable Object delta,
    @Nullable Object data
) implements Message
```

## Tipos de evento

### Eventos de contenido
- `"content_block_start"`: comienza un nuevo bloque de contenido
- `"content_block_delta"`: actualización incremental del contenido
- `"content_block_stop"`: bloque de contenido completado

### Eventos de mensaje
- `"message_start"`: comienza la generación del mensaje
- `"message_delta"`: actualización de los metadatos del mensaje
- `"message_stop"`: generación del mensaje completada

## Procesar eventos de stream

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

## Ejemplo completo

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

## Integración con la interfaz

### Ejemplo con Swing

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

### Ejemplo con JavaFX

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

## Véase también
- [Opciones de configuración](./feature-configuration-options.md#funciones-avanzadas): includePartialMessages
- [Tipos de mensaje](./feature-message-types.md#streamevent): detalles de StreamEvent
- [Ejemplo de eventos de streaming](../../examples/src/main/java/examples/StreamingEvents.java)
