# Conversaciones interactivas

Las conversaciones interactivas permiten intercambios con estado y de varios turnos con Claude mediante la clase `ClaudeSDKClient`.

> **Sobre esta traducción**: la documentación en inglés es la única autoritativa. Esta traducción puede estar desactualizada respecto al [original en inglés](../feature-interactive-conversations.md); si hay discrepancias, prevalece el inglés. Los bloques de código se mantienen idénticos al original y no se traducen.

## Contenido
- [Descripción general](#descripción-general)
- [Cuándo usar ClaudeSDKClient](#cuándo-usar-claudesdkclient)
- [Uso básico](#uso-básico)
- [Gestión de la conexión](#gestión-de-la-conexión)
- [Enviar mensajes](#enviar-mensajes)
- [Recibir mensajes](#recibir-mensajes)
- [Métodos de control](#métodos-de-control)
- [Gestión de sesiones](#gestión-de-sesiones)
- [Seguridad entre hilos](#seguridad-entre-hilos)
- [Gestión de recursos](#gestión-de-recursos)
- [Ejemplos](#ejemplos)
- [Buenas prácticas](#buenas-prácticas)

## Descripción general

`ClaudeSDKClient` ofrece control total sobre una conversación bidireccional con Claude. A diferencia
de la fachada simple `ClaudeSDK.query()`, este cliente es:

- **Con estado**: el contexto de la conversación se conserva entre mensajes
- **Bidireccional**: envía y recibe mensajes en cualquier momento
- **Interactivo**: envía preguntas de seguimiento según las respuestas
- **Controlable**: interrumpe, cambia de modelo o ajusta permisos a mitad de ejecución
- **Consciente de sesiones**: admite reanudar y bifurcar conversaciones

## Cuándo usar ClaudeSDKClient

### ✅ Ideal para

1. **Interfaces de chat**
   ```java
   try (var client = ClaudeSDK.createClient()) {
       client.connect();
       while (userInput = getUserInput()) {
           client.sendMessage(userInput);
           for (var msg : client.receiveResponse()) {
               display(msg);
           }
       }
   }
   ```

2. **Interfaces tipo REPL**
   ```java
   while (true) {
       String command = console.readLine();
       client.sendMessage(command);
       processResponse(client.receiveResponse());
   }
   ```

3. **Conversaciones de varios turnos**
   ```java
   client.sendMessage("What is Python?");
   // ... process response
   client.sendMessage("Show me a code example");
   // ... context preserved
   ```

4. **Depuración interactiva**
   ```java
   client.sendMessage("Analyze this error");
   var response = client.receiveResponse();
   if (needsMoreInfo) {
       client.sendMessage("Here's more context...");
   }
   ```

5. **Sesiones de larga duración**
   ```java
   try (var client = ClaudeSDK.createClient(options)) {
       client.connect();
       // Hours-long session with state
   }
   ```

### ❌ No es lo ideal para

- Preguntas simples y aisladas → usa `ClaudeSDK.query()`
- Procesamiento por lotes → usa `ClaudeSDK.query()`
- Scripts de una sola ejecución → usa `ClaudeSDK.query()`

## Uso básico

### Crear y conectar

```java
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.ClaudeSDKClient;
import in.vidyalai.claude.sdk.ClaudeAgentOptions;

// Create with default options
ClaudeSDKClient client = ClaudeSDK.createClient();

// Or with custom options
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .model("claude-sonnet-4-5")
    .maxTurns(20)
    .build();
ClaudeSDKClient client = ClaudeSDK.createClient(options);

// Connect (establishes subprocess)
client.connect();
```

### Conversación sencilla

```java
try (var client = ClaudeSDK.createClient()) {
    client.connect();

    // First message
    client.sendMessage("What is 2 + 2?");
    for (var msg : client.receiveResponse()) {
        if (msg instanceof AssistantMessage assistant) {
            System.out.println(assistant.getTextContent());
        }
    }

    // Follow-up (context preserved)
    client.sendMessage("What about 3 + 3?");
    for (var msg : client.receiveResponse()) {
        if (msg instanceof AssistantMessage assistant) {
            System.out.println(assistant.getTextContent());
        }
    }
}
```

### Conectar con un mensaje inicial

```java
// Connect and send initial message in one call
client.connect("Hello, Claude!");

for (var msg : client.receiveResponse()) {
    // Process initial response
}
```

## Gestión de la conexión

### connect()

Establece la conexión con la CLI de Claude Code.

```java
client.connect();  // No initial message
client.connect("Initial prompt");  // With initial message
```

**Seguridad entre hilos**: seguro entre hilos e idempotente. Las llamadas concurrentes están protegidas.

**Lanza**:
- `IllegalStateException` — si se llama después de `close()`
- `CLIConnectionException` — si falla la conexión

### isConnected()

Comprueba si el cliente está conectado.

```java
if (client.isConnected()) {
    client.sendMessage("Hello");
}
```

### disconnect() / close()

Cierra la conexión y libera los recursos.

```java
client.disconnect();  // Explicit disconnect
// or
client.close();  // AutoCloseable

// Best practice: use try-with-resources
try (var client = ClaudeSDK.createClient()) {
    // Use client
}  // Automatically closed
```

**Seguridad entre hilos**: seguro entre hilos e idempotente. Se puede llamar varias veces sin problema.

## Enviar mensajes

### sendMessage(String prompt)

Envía un mensaje y sigue recibiendo.

```java
client.sendMessage("Hello, Claude!");
```

**Cuándo usarlo**: cuando quieres enviar un mensaje y seguir escuchando todos los eventos.

### sendMessage(String prompt, String sessionId)

Envía un mensaje a una sesión concreta.

```java
client.sendMessage("Hello!", "session-1");
```

### query(String prompt)

Envía un mensaje y recibe solo su respuesta (bloquea hasta el ResultMessage).

```java
List<Message> response = client.query("What is 2 + 2?");
```

**Cuándo usarlo**: cuando quieres el patrón petición/respuesta (enviar y esperar la respuesta completa).

### query(String prompt, String sessionId)

Consulta con un ID de sesión concreto.

```java
List<Message> response = client.query("Question", "session-1");
```

### query(Iterator<Map<String, Object>> messageStream)

Envía varios mensajes como un flujo.

```java
var messages = List.of(
    Map.of("type", "user", "session_id", "default",
           "message", Map.of("role", "user", "content", "First")),
    Map.of("type", "user", "session_id", "default",
           "message", Map.of("role", "user", "content", "Second"))
);

List<Message> responses = client.query(messages.iterator());
```

## Recibir mensajes

### receiveMessages()

Devuelve un iterador sobre **todos** los mensajes (flujo continuo).

```java
Iterator<Message> messages = client.receiveMessages();

while (messages.hasNext()) {
    Message msg = messages.next();
    // Process each message as it arrives

    if (shouldStop(msg)) {
        break;
    }
}
```

**Cuándo usarlo**:
- Quieres procesar mensajes de forma continua
- Estás manejando varias sesiones
- Necesitas ver todos los eventos, incluidos los mensajes de sistema

**Características**:
- El iterador bloquea hasta que haya mensajes disponibles
- Sigue devolviendo mensajes hasta que termina el flujo
- Varios iteradores comparten la misma cola (los mensajes se reparten)

### receiveResponse()

Devuelve un iterador que se detiene en el siguiente ResultMessage.

```java
Iterable<Message> response = client.receiveResponse();

for (Message msg : response) {
    // Process messages until ResultMessage
}
// Iterator auto-closes when ResultMessage received
```

**Cuándo usarlo**:
- Quieres el patrón petición/respuesta
- Estás esperando a que termine una consulta
- Quieres detenerte automáticamente en el ResultMessage

**Características**:
- Bloquea hasta que haya mensajes disponibles
- Se detiene en el ResultMessage y se cierra automáticamente
- Devuelve todos los mensajes de una respuesta

### Procesar mensajes

```java
for (Message msg : client.receiveResponse()) {
    switch (msg) {
        case UserMessage user ->
            System.out.println("User: " + user.content());

        case AssistantMessage assistant -> {
            System.out.println("Claude: " + assistant.getTextContent());

            // Process tool uses
            for (ContentBlock block : assistant.content()) {
                if (block instanceof ToolUseBlock tool) {
                    System.out.println("Tool: " + tool.name());
                }
            }
        }

        case ResultMessage result -> {
            System.out.println("Done! Cost: $" + result.totalCostUsd());
            System.out.println("Stop reason: " + result.stopReason());
        }

        case SystemMessage system ->
            System.out.println("System: " + system.subtype());

        case StreamEvent event ->
            System.out.println("Partial: " + event.delta());
    }
}
```

## Métodos de control

### interrupt()

Interrumpe la ejecución actual.

```java
// In another thread
client.interrupt();
```

**Casos de uso**:
- El usuario cancela la operación
- Se alcanzó un tiempo límite
- Detener operaciones costosas

### setModel(String model)

Cambia el modelo de IA a mitad de la conversación.

```java
client.setModel("claude-opus-4-6");
```

**Casos de uso**:
- Pasar a un modelo más capaz en tareas complejas
- Pasar a un modelo más barato en preguntas simples

### setPermissionMode(PermissionMode mode)

Cambia el modo de permisos durante la conversación.

```java
client.setPermissionMode(PermissionMode.ACCEPT_EDITS);
```

**Modos disponibles**:
- `DEFAULT` — comportamiento de permisos estándar (el valor por defecto del CLI)
- `ACCEPT_EDITS` — acepta las ediciones automáticamente, pregunta en el resto
- `PLAN` — modo de planificación; no se ejecuta ninguna herramienta
- `BYPASS_PERMISSIONS` — omite todas las comprobaciones de permisos
- `DONT_ASK` — deniega todo lo que no esté aprobado de antemano por reglas allow
- `AUTO` — un clasificador basado en un modelo aprueba o deniega cada llamada a herramienta

### rewindFiles(String userMessageId)

Revierte los archivos al estado de un mensaje de usuario (requiere el checkpointing activado).

```java
// Enable checkpointing in options
var options = ClaudeAgentOptions.builder()
    .enableFileCheckpointing(true)
    .extraArgs(Map.of("replay-user-messages", ""))  // so UserMessage carries a uuid
    .build();

try (var client = ClaudeSDK.createClient(options)) {
    client.connect();

    client.sendMessage("Create file.txt");
    for (var msg : client.receiveResponse()) {
        if (msg instanceof UserMessage user) {
            String messageId = user.uuid();
            // Save ID for later
        }
    }

    // Later: rewind to that message
    client.rewindFiles(messageId);
}
```

### getMcpStatus()

Obtiene el estado de las conexiones con servidores MCP.

```java
McpStatusResponse status = client.getMcpStatus();
for (McpServerStatus server : status.mcpServers()) {
    System.out.println(server.name() + ": " + server.status());
}
```

### getServerInfo()

Obtiene la información de inicialización del servidor.

```java
Map<String, Object> info = client.getServerInfo();
System.out.println("Commands: " + info.get("commands"));
```

## Gestión de sesiones

### Sesión predeterminada

Por defecto, todos los mensajes usan la sesión "default".

```java
client.sendMessage("Hello");  // Uses "default" session
```

### Varias sesiones

Envía mensajes a distintas sesiones.

```java
// Session 1
client.sendMessage("Analyze code.java", "session-1");

// Session 2
client.sendMessage("Write tests", "session-2");

// Receive from all sessions
for (var msg : client.receiveMessages()) {
    // Process messages from any session
}
```

### Reanudar una sesión anterior

```java
// First conversation
var options1 = ClaudeAgentOptions.builder()
    .build();

try (var client = ClaudeSDK.createClient(options1)) {
    client.connect("What is Java?");
    // ... conversation
}

// Later: resume with context
var options2 = ClaudeAgentOptions.builder()
    .resume("previous-session-id")
    .build();

try (var client = ClaudeSDK.createClient(options2)) {
    client.connect("Tell me more");
    // Has context from previous session
}
```

### Bifurcar una sesión

Bifurcar crea una sesión nueva a partir de una existente.

```java
var options = ClaudeAgentOptions.builder()
    .resume("previous-session-id")
    .forkSession(true)  // Fork instead of continue
    .build();
```

**Diferencia**:
- `resume(id)` — continúa la misma sesión
- `resume(id) + forkSession(true)` — crea una sesión nueva con el mismo contexto

## Seguridad entre hilos

`ClaudeSDKClient` es **parcialmente seguro entre hilos**:

### Operaciones seguras entre hilos

```java
// ✅ Safe: Multiple threads can send
Thread t1 = Thread.startVirtualThread(() ->
    client.sendMessage("Query 1"));
Thread t2 = Thread.startVirtualThread(() ->
    client.sendMessage("Query 2"));

// ✅ Safe: Control methods
client.interrupt();
client.setModel("claude-sonnet-4-5");
client.setPermissionMode(PermissionMode.ACCEPT_ALL);

// ✅ Safe: connect() is synchronized
client.connect();  // Only one connection established
```

### Estado compartido

```java
// ⚠️ Warning: Multiple iterators share the queue
Iterator<Message> iter1 = client.receiveMessages();
Iterator<Message> iter2 = client.receiveMessages();

// Messages distributed across both iterators!
// Typically use only one iterator per client
```

### Buena práctica

```java
// ✅ Good: One receive loop per client
try (var client = ClaudeSDK.createClient()) {
    client.connect();

    // Dedicated receive thread
    Thread.ofVirtual().start(() -> {
        for (var msg : client.receiveMessages()) {
            processMessage(msg);
        }
    });

    // Main thread sends
    client.sendMessage("Question 1");
    client.sendMessage("Question 2");
}
```

## Gestión de recursos

### AutoCloseable

Usa siempre try-with-resources:

```java
try (ClaudeSDKClient client = ClaudeSDK.createClient()) {
    client.connect();
    // Use client
}  // Automatically cleaned up
```

### Limpieza manual

Si no usas try-with-resources:

```java
ClaudeSDKClient client = ClaudeSDK.createClient();
try {
    client.connect();
    // Use client
} finally {
    client.close();  // Important!
}
```

### Recursos liberados

En `close()`, el cliente libera:
- El QueryHandler y los pools de hilos
- Los ejecutores de streaming
- La capa de transporte y el subproceso de la CLI
- Colas de mensajes e iteradores

## Ejemplos

### Ejemplo 1: chat interactivo

```java
import java.util.Scanner;

public class Chat {
    public static void main(String[] args) {
        var options = ClaudeAgentOptions.builder()
            .model("claude-sonnet-4-5")
            .build();

        try (var client = ClaudeSDK.createClient(options);
             var scanner = new Scanner(System.in)) {

            client.connect();
            System.out.println("Chat started! Type 'exit' to quit.");

            while (true) {
                System.out.print("\nYou: ");
                String input = scanner.nextLine();

                if ("exit".equalsIgnoreCase(input)) {
                    break;
                }

                client.sendMessage(input);

                System.out.print("Claude: ");
                for (var msg : client.receiveResponse()) {
                    if (msg instanceof AssistantMessage assistant) {
                        System.out.print(assistant.getTextContent());
                    }
                }
                System.out.println();
            }
        }
    }
}
```

### Ejemplo 2: interrumpir una operación larga

```java
import java.util.concurrent.TimeUnit;

public class InterruptExample {
    public static void main(String[] args) {
        try (var client = ClaudeSDK.createClient()) {
            client.connect();

            // Start long operation in background
            Thread.ofVirtual().start(() -> {
                client.sendMessage("Analyze all files in this large codebase");
                for (var msg : client.receiveResponse()) {
                    System.out.println(msg);
                }
            });

            // Wait 5 seconds then interrupt
            TimeUnit.SECONDS.sleep(5);
            System.out.println("Interrupting...");
            client.interrupt();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

### Ejemplo 3: cambio dinámico de modelo

```java
public class ModelSwitching {
    public static void main(String[] args) {
        try (var client = ClaudeSDK.createClient()) {
            client.connect();

            // Simple question with Haiku
            client.setModel("claude-haiku-4-5");
            client.sendMessage("What is 2+2?");
            processResponse(client.receiveResponse());

            // Complex question with Opus
            client.setModel("claude-opus-4-6");
            client.sendMessage("Explain quantum entanglement");
            processResponse(client.receiveResponse());

            // Back to Sonnet for balanced tasks
            client.setModel("claude-sonnet-4-5");
            client.sendMessage("Write a Java function");
            processResponse(client.receiveResponse());
        }
    }
}
```

### Ejemplo 4: gestión de varias sesiones

```java
public class MultiSession {
    public static void main(String[] args) {
        try (var client = ClaudeSDK.createClient()) {
            client.connect();

            // Start multiple tasks in different sessions
            client.sendMessage("Review code.java for bugs", "review");
            client.sendMessage("Write tests for util.java", "testing");
            client.sendMessage("Document api.java", "docs");

            // Process responses from all sessions
            for (var msg : client.receiveMessages()) {
                switch (msg) {
                    case AssistantMessage a ->
                        System.out.println("[Session] " + a.getTextContent());
                    case ResultMessage r ->
                        System.out.println("[Done] Cost: $" + r.totalCostUsd());
                    default -> {}
                }

                // Stop when all three sessions complete
                if (allSessionsComplete()) {
                    break;
                }
            }
        }
    }
}
```

### Ejemplo 5: checkpoints de archivos

```java
public class Checkpointing {
    public static void main(String[] args) {
        var options = ClaudeAgentOptions.builder()
            .enableFileCheckpointing(true)
            .extraArgs(Map.of("replay-user-messages", ""))
            .build();

        try (var client = ClaudeSDK.createClient(options)) {
            client.connect();

            String checkpointId = null;

            // Create a file and save checkpoint
            client.sendMessage("Create test.txt with 'Hello'");
            for (var msg : client.receiveResponse()) {
                if (msg instanceof UserMessage user) {
                    checkpointId = user.uuid();
                }
            }

            // Modify the file
            client.sendMessage("Append 'World' to test.txt");
            for (var msg : client.receiveResponse()) {}

            // Rewind to original state
            if (checkpointId != null) {
                client.rewindFiles(checkpointId);
                System.out.println("Rewound to checkpoint");
            }
        }
    }
}
```

## Buenas prácticas

### 1. Usa try-with-resources

```java
// ✅ Good
try (var client = ClaudeSDK.createClient()) {
    client.connect();
}

// ❌ Bad: Resource leak
var client = ClaudeSDK.createClient();
client.connect();
// Forgot to close!
```

### 2. Gestiona los errores de conexión

```java
// ✅ Good
try (var client = ClaudeSDK.createClient()) {
    try {
        client.connect();
    } catch (CLIConnectionException e) {
        System.err.println("Failed to connect: " + e.getMessage());
        return;
    }
    // Use client
}
```

### 3. Usa receiveResponse() para petición/respuesta

```java
// ✅ Good: Clean request/response
client.sendMessage("Question");
for (var msg : client.receiveResponse()) {
    // Processes until ResultMessage
}

// ❌ Bad: Manual ResultMessage checking
for (var msg : client.receiveMessages()) {
    if (msg instanceof ResultMessage) break;
}
```

### 4. No crees varios iteradores de recepción

```java
// ✅ Good: Single iterator
Iterator<Message> messages = client.receiveMessages();

// ❌ Bad: Messages split across iterators
Iterator<Message> iter1 = client.receiveMessages();
Iterator<Message> iter2 = client.receiveMessages();
```

### 5. Establece límites adecuados

```java
// ✅ Good: Configure limits
var options = ClaudeAgentOptions.builder()
    .maxTurns(50)  // Long conversation
    .maxBudgetUsd(5.0)
    .build();

// ❌ Bad: No limits in interactive session
var client = ClaudeSDK.createClient();  // Could be expensive!
```

### 6. Gestiona todos los tipos de mensaje

```java
// ✅ Good: Exhaustive pattern matching
for (var msg : client.receiveResponse()) {
    switch (msg) {
        case UserMessage u -> handleUser(u);
        case AssistantMessage a -> handleAssistant(a);
        case ResultMessage r -> handleResult(r);
        case SystemMessage s -> handleSystem(s);
        case StreamEvent e -> handleStream(e);
    }
}
```

### 7. Usa los métodos de control con criterio

```java
// ✅ Good: Switch models based on task complexity
if (isComplexTask) {
    client.setModel("claude-opus-4-6");
}

client.sendMessage(task);

// ✅ Good: Interrupt on timeout
CompletableFuture.delayedExecutor(30, TimeUnit.SECONDS)
    .execute(() -> client.interrupt());
```

## Véase también

- [Consultas simples](./feature-simple-queries.md) — para consultas aisladas
- [Opciones de configuración](./feature-configuration-options.md) — todas las ClaudeAgentOptions
- [Tipos de mensaje](./feature-message-types.md) — entender los mensajes
- [Referencia de la API ClaudeSDKClient](./api-claude-sdk-client.md) — documentación completa de la API
