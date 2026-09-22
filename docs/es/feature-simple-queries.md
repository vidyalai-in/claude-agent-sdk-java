# Consultas simples

Las consultas simples son la forma más directa de interactuar con Claude en operaciones puntuales y
sin estado, usando la fachada `ClaudeSDK`.

> **Sobre esta traducción**: la documentación en inglés es la única autoritativa. Esta traducción puede estar desactualizada respecto al [original en inglés](../feature-simple-queries.md); si hay discrepancias, prevalece el inglés. Los bloques de código se mantienen idénticos al original y no se han traducido.

## Índice
- [Visión general](#visión-general)
- [Cuándo usar consultas simples](#cuándo-usar-consultas-simples)
- [Uso básico](#uso-básico)
- [Métodos de consulta](#métodos-de-consulta)
- [Opciones de configuración](#opciones-de-configuración)
- [Manejo de mensajes](#manejo-de-mensajes)
- [Ejemplos](#ejemplos)
- [Buenas prácticas](#buenas-prácticas)

## Visión general

La clase `ClaudeSDK` ofrece métodos estáticos para consultas simples y sin estado. Se encarga de toda
la complejidad de:
- Crear y gestionar el transporte
- Preparar el QueryHandler
- Analizar los mensajes
- Liberar los recursos

**Características clave:**
- **Unidireccional**: envía todos los mensajes de una vez y recibe todas las respuestas
- **Sin estado**: cada consulta es independiente
- **Sencillo**: al estilo «dispara y olvida»
- **Sin interrupciones**: no se puede interrumpir ni enviar mensajes de seguimiento
- **Limpieza automática**: los recursos se gestionan internamente

## Cuándo usar consultas simples

### ✅ Buenos casos de uso

1. **Preguntas puntuales**
   ```java
   ClaudeSDK.query("What is the capital of France?");
   ```

2. **Procesamiento por lotes**
   ```java
   for (String prompt : prompts) {
       List<Message> result = ClaudeSDK.query(prompt, options);
       processResult(result);
   }
   ```

3. **Generación de código**
   ```java
   String code = ClaudeSDK.queryForText(
       "Generate a Java function to reverse a string",
       options);
   ```

4. **Pipelines de CI/CD**
   ```java
   String review = ClaudeSDK.queryForText(
       "Review this code for security issues: " + code,
       options);
   ```

5. **Scripts automatizados**
   ```java
   ResultMessage result = ClaudeSDK.queryForResult(
       "Analyze this log file",
       options);
   System.out.println("Cost: $" + result.totalCostUsd());
   ```

### ❌ No sirve para

1. **Conversaciones interactivas**: usa `ClaudeSDKClient`
2. **Interfaces de chat**: usa `ClaudeSDKClient` para varios turnos
3. **Preguntas de seguimiento**: usa `ClaudeSDKClient` para mantener el contexto
4. **Capacidad de interrumpir**: usa `ClaudeSDKClient` para el control
5. **Sesiones largas**: usa `ClaudeSDKClient` para mantener el estado

## Uso básico

### La consulta más sencilla (opciones por defecto)

```java
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.types.message.Message;

List<Message> messages = ClaudeSDK.query("What is 2 + 2?");
```

### Consulta con opciones

```java
import in.vidyalai.claude.sdk.ClaudeAgentOptions;

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .model("claude-sonnet-4-5")
    .maxTurns(1)
    .build();

List<Message> messages = ClaudeSDK.query("What is 2 + 2?", options);
```

### Obtener solo el texto

```java
String answer = ClaudeSDK.queryForText(
    "What is 2 + 2?",
    ClaudeAgentOptions.defaults()
);
System.out.println(answer); // "4"
```

### Obtener el mensaje de resultado

```java
ResultMessage result = ClaudeSDK.queryForResult(
    "What is 2 + 2?",
    ClaudeAgentOptions.defaults()
);
System.out.println("Cost: $" + result.totalCostUsd());
System.out.println("Stop reason: " + result.stopReason());
```

## Métodos de consulta

### 1. query(String prompt)

Ejecuta una consulta con las opciones por defecto.

```java
List<Message> messages = ClaudeSDK.query("Hello, Claude!");
```

**Devuelve**: `List<Message>`, todos los mensajes de la conversación

### 2. query(String prompt, ClaudeAgentOptions options)

Ejecuta una consulta con opciones propias.

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .permissionMode(PermissionMode.BYPASS_PERMISSIONS)
    .maxTurns(1)
    .build();

List<Message> messages = ClaudeSDK.query("Hello!", options);
```

**Parámetros**:
- `prompt`: la pregunta o instrucción
- `options`: opciones de configuración

**Devuelve**: `List<Message>`, todos los mensajes de la conversación

**Lanza**:
- `IllegalArgumentException`: si se definen a la vez `canUseTool` y `permissionPromptToolName`
- `CLIConnectionException`: si falla la conexión
- `ProcessException`: si falla el proceso del CLI

### 3. query(Iterator<Map<String, Object>> messageStream, ClaudeAgentOptions options)

Ejecuta una consulta por streaming con varios mensajes.

```java
var messages = List.of(
    Map.of("type", "user", "session_id", "default",
           "message", Map.of("role", "user", "content", "First message")),
    Map.of("type", "user", "session_id", "default",
           "message", Map.of("role", "user", "content", "Follow-up"))
);

List<Message> responses = ClaudeSDK.query(messages.iterator(), options);
```

**Parámetros**:
- `messageStream`: iterador de diccionarios de mensaje
- `options`: opciones de configuración

**Devuelve**: `List<Message>`, todos los mensajes de la conversación

**Formato del mensaje**:
```java
{
    "type": "user",
    "session_id": "default",
    "message": {
        "role": "user",
        "content": "Your message here"
    }
}
```

### 4. queryForText(String prompt, ClaudeAgentOptions options)

Método de conveniencia que devuelve solo el contenido de texto de los mensajes del asistente.

```java
String text = ClaudeSDK.queryForText(
    "What is the capital of France?",
    ClaudeAgentOptions.defaults()
);
```

**Devuelve**: `String`, el contenido de texto de todos los mensajes del asistente, concatenado

### 5. queryForResult(String prompt, ClaudeAgentOptions options)

Método de conveniencia que devuelve solo el mensaje de resultado.

```java
ResultMessage result = ClaudeSDK.queryForResult(
    "Analyze this code",
    options
);

System.out.println("Cost: $" + result.totalCostUsd());
System.out.println("Input tokens: " + result.usageInput());
System.out.println("Output tokens: " + result.usageOutput());
```

**Devuelve**: `ResultMessage`, el resultado final, o null si no se encuentra

## Opciones de configuración

### Opciones esenciales para consultas simples

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    // Model selection
    .model("claude-sonnet-4-5")
    .fallbackModel("claude-haiku-4-5")

    // Limits
    .maxTurns(5)
    .maxBudgetUsd(1.0)
    .maxThinkingTokens(10000)

    // System prompt
    .systemPrompt("You are a helpful assistant. Be concise.")

    // Tools
    .allowedTools(List.of("Read", "Grep"))
    .disallowedTools(List.of("Write", "Edit"))

    // Permissions
    .permissionMode(PermissionMode.BYPASS_PERMISSIONS)

    // Working directory
    .cwd(Path.of("/path/to/project"))

    // Environment
    .env(Map.of("KEY", "value"))

    .build();
```

### Patrones habituales

#### Consulta rápida de solo lectura
```java
var options = ClaudeAgentOptions.builder()
    .permissionMode(PermissionMode.BYPASS_PERMISSIONS)
    .allowedTools(List.of("Read", "Grep", "Glob"))
    .disallowedTools(List.of("Write", "Edit", "Bash"))
    .maxTurns(5)
    .build();
```

#### Consulta con presupuesto limitado
```java
var options = ClaudeAgentOptions.builder()
    .maxBudgetUsd(0.10)  // Limit to 10 cents
    .maxTurns(3)
    .model("claude-haiku-4-5")  // Use cheaper model
    .build();
```

#### Consulta rápida de un solo turno
```java
var options = ClaudeAgentOptions.builder()
    .maxTurns(1)
    .model("claude-haiku-4-5")
    .systemPrompt("Be extremely concise.")
    .build();
```

## Manejo de mensajes

### Procesar todos los mensajes

```java
List<Message> messages = ClaudeSDK.query("Hello!", options);

for (Message msg : messages) {
    switch (msg) {
        case UserMessage user ->
            System.out.println("User: " + user.content());

        case AssistantMessage assistant -> {
            System.out.println("Claude: " + assistant.getTextContent());
            // Process tool uses
            for (ContentBlock block : assistant.content()) {
                if (block instanceof ToolUseBlock tool) {
                    System.out.println("Used tool: " + tool.name());
                }
            }
        }

        case ResultMessage result ->
            System.out.println("Cost: $" + result.totalCostUsd());

        case SystemMessage system ->
            System.out.println("System: " + system.subtype());

        case StreamEvent event ->
            // Usually not present in simple queries
            System.out.println("Stream event: " + event);
    }
}
```

### Extraer información concreta

```java
List<Message> messages = ClaudeSDK.query(prompt, options);

// Get last assistant message
AssistantMessage lastAssistant = messages.stream()
    .filter(m -> m instanceof AssistantMessage)
    .map(m -> (AssistantMessage) m)
    .reduce((first, second) -> second)
    .orElse(null);

// Get all text content
String allText = messages.stream()
    .filter(m -> m instanceof AssistantMessage)
    .map(m -> ((AssistantMessage) m).getTextContent())
    .collect(Collectors.joining("\n"));

// Get result
ResultMessage result = messages.stream()
    .filter(m -> m instanceof ResultMessage)
    .map(m -> (ResultMessage) m)
    .findFirst()
    .orElse(null);
```

## Ejemplos

### Ejemplo 1: revisión de código

```java
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.ClaudeAgentOptions;

public class CodeReview {
    public static void main(String[] args) {
        String code = """
            public void processUser(User user) {
                db.save(user);  // No null check!
            }
            """;

        var options = ClaudeAgentOptions.builder()
            .systemPrompt("You are a code reviewer. Focus on bugs and security.")
            .maxTurns(1)
            .build();

        String review = ClaudeSDK.queryForText(
            "Review this code for issues:\n" + code,
            options
        );

        System.out.println(review);
    }
}
```

### Ejemplo 2: traducción por lotes

```java
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.ClaudeAgentOptions;

public class Translator {
    public static void main(String[] args) {
        var options = ClaudeAgentOptions.builder()
            .model("claude-haiku-4-5")  // Fast and cheap
            .maxTurns(1)
            .systemPrompt("Translate to French. Return only the translation.")
            .build();

        List<String> phrases = List.of(
            "Hello, how are you?",
            "The weather is nice today.",
            "I love programming."
        );

        for (String phrase : phrases) {
            String translation = ClaudeSDK.queryForText(phrase, options);
            System.out.println(phrase + " -> " + translation);
        }
    }
}
```

### Ejemplo 3: análisis de registros

```java
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import java.nio.file.Path;

public class LogAnalyzer {
    public static void main(String[] args) {
        var options = ClaudeAgentOptions.builder()
            .cwd(Path.of("/path/to/logs"))
            .allowedTools(List.of("Read", "Grep"))
            .permissionMode(PermissionMode.BYPASS_PERMISSIONS)
            .maxTurns(10)
            .build();

        String analysis = ClaudeSDK.queryForText(
            "Analyze error.log and summarize all ERROR level messages",
            options
        );

        System.out.println(analysis);
    }
}
```

### Ejemplo 4: consulta atenta al coste

```java
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.types.message.ResultMessage;

public class CostAwareQuery {
    public static void main(String[] args) {
        var options = ClaudeAgentOptions.builder()
            .maxBudgetUsd(0.05)  // 5 cent limit
            .build();

        ResultMessage result = ClaudeSDK.queryForResult(
            "Explain quantum computing",
            options
        );

        if (result != null) {
            System.out.println("Cost: $" + result.totalCostUsd());
            System.out.println("Input tokens: " + result.usageInput());
            System.out.println("Output tokens: " + result.usageOutput());
            System.out.println("Stop reason: " + result.stopReason());
        }
    }
}
```

## Buenas prácticas

### 1. Usa las opciones adecuadas

```java
// ✅ Good: Configure limits
var options = ClaudeAgentOptions.builder()
    .maxTurns(5)
    .maxBudgetUsd(1.0)
    .build();

// ❌ Bad: No limits
ClaudeSDK.query(longComplexTask);  // Could be expensive!
```

### 2. Trata todos los tipos de mensaje

```java
// ✅ Good: Pattern matching handles all types
switch (message) {
    case AssistantMessage a -> process(a);
    case ResultMessage r -> logCost(r);
    case UserMessage u -> log(u);
    case SystemMessage s -> log(s);
    case StreamEvent e -> log(e);
}

// ❌ Bad: Only handling one type
if (message instanceof AssistantMessage) {
    // Missing other types!
}
```

### 3. Usa los métodos de conveniencia cuando toque

```java
// ✅ Good: Simple use case
String answer = ClaudeSDK.queryForText(prompt, options);

// ❌ Overkill: Manual extraction
List<Message> messages = ClaudeSDK.query(prompt, options);
String answer = messages.stream()...  // Complex extraction
```

### 4. Fija el directorio de trabajo para operaciones con archivos

```java
// ✅ Good: Explicit working directory
var options = ClaudeAgentOptions.builder()
    .cwd(Path.of("/project/root"))
    .allowedTools(List.of("Read", "Write"))
    .build();

// ❌ Bad: Using current directory (unpredictable)
ClaudeSDK.query("Read config.json", options);
```

### 5. Elige el modelo adecuado

```java
// ✅ Good: Match model to task
var fastOptions = ClaudeAgentOptions.builder()
    .model("claude-haiku-4-5")  // Quick, simple tasks
    .build();

var complexOptions = ClaudeAgentOptions.builder()
    .model("claude-opus-4-6")  // Complex reasoning
    .build();

// ❌ Bad: Using opus for simple tasks (expensive)
ClaudeAgentOptions.builder()
    .model("claude-opus-4-6")
    .build();
ClaudeSDK.query("What is 2+2?", options);  // Overkill!
```

### 6. Usa streaming en escenarios de varios turnos

```java
// ✅ Good: Streaming for multiple messages
var messages = List.of(
    Map.of("type", "user", ...),
    Map.of("type", "user", ...)
);
ClaudeSDK.query(messages.iterator(), options);

// ❌ Bad: Multiple separate queries (loses context)
ClaudeSDK.query("First question", options);
ClaudeSDK.query("Follow-up", options);  // No context!
```

### 7. Maneja los errores

```java
// ✅ Good: Handle exceptions
try {
    List<Message> messages = ClaudeSDK.query(prompt, options);
    // Process messages
} catch (CLIConnectionException e) {
    System.err.println("Failed to connect: " + e.getMessage());
} catch (ProcessException e) {
    System.err.println("CLI process failed: " + e.getMessage());
}

// ❌ Bad: No error handling
ClaudeSDK.query(prompt, options);  // Could throw!
```

## Véase también

- [Conversaciones interactivas](./feature-interactive-conversations.md): para conversaciones de varios turnos
- [Opciones de configuración](./feature-configuration-options.md): la guía completa de opciones
- [Tipos de mensaje](./feature-message-types.md): entender los mensajes
- [Referencia de la API ClaudeSDK](./api-claude-sdk.md): documentación detallada de la API
