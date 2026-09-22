# Tipos de mensaje

Entender el sistema de tipos de mensaje para procesar conversaciones de Claude.

> **Sobre esta traducción**: la documentación en inglés es la única autoritativa. Esta traducción puede estar desactualizada respecto al [original en inglés](../feature-message-types.md); si hay discrepancias, prevalece el inglés. Los bloques de código se mantienen idénticos al original y no se traducen.

## Contenido
- [Descripción general](#descripción-general)
- [Compatibilidad hacia adelante](#compatibilidad-hacia-adelante)
- [Jerarquía de tipos de mensaje](#jerarquía-de-tipos-de-mensaje)
- [UserMessage](#usermessage)
- [AssistantMessage](#assistantmessage)
- [SystemMessage](#systemmessage)
- [Mensajes de tarea](#mensajes-de-tarea)
- [MirrorErrorMessage](#mirrorerrormessage)
- [HookEventMessage](#hookeventmessage)
- [ResultMessage](#resultmessage)
- [StreamEvent](#streamevent)
- [RateLimitEvent](#ratelimitevent)
- [Bloques de contenido](#bloques-de-contenido)
- [Pattern matching](#pattern-matching)
- [Ejemplos](#ejemplos)

## Descripción general

El SDK usa una jerarquía de interfaces selladas para tratar los mensajes con seguridad de tipos. Todos los mensajes implementan la interfaz sellada `Message`, lo que permite un pattern matching exhaustivo.

```java
sealed interface Message permits UserMessage, AssistantMessage,
    SystemMessage, TaskStartedMessage, TaskProgressMessage,
    TaskNotificationMessage, TaskUpdatedMessage, MirrorErrorMessage,
    HookEventMessage, ResultMessage, StreamEvent, RateLimitEvent {}
```

## Compatibilidad hacia adelante

`MessageParser` está diseñado para ser compatible con versiones más nuevas de la CLI. Cuando la CLI emite un tipo de mensaje que el SDK no reconoce, el parser devuelve `null` en lugar de lanzar una excepción. El iterador de mensajes omite automáticamente los mensajes `null`, así que tu código sigue funcionando correctamente aunque estés conectado a una CLI más nueva que emite tipos nuevos.

```java
// MessageParser.parse() returns @Nullable Message
// Unknown types return null and are silently skipped by the iterator
for (Message msg : ClaudeSDK.query(prompt)) {
    // Only known message types arrive here; unknown types are silently skipped
    switch (msg) { ... }
}
```

**Tipo desconocido no es lo mismo que contenido malformado.** Devolver `null` se aplica a un *tipo de mensaje no reconocido*. Un tipo *conocido* (`user` / `assistant`) con una estructura inválida es otro caso: el parser lanza `MessageParseException` en lugar de descartarlo en silencio, para que una violación real del protocolo no quede oculta. Desde 0.1.18 esto se aplica de forma explícita: un mensaje `assistant` cuyo `content` no sea una lista (por ejemplo, una cadena suelta) lanza `"Invalid assistant content (expected list, got …)"`, y un elemento de la lista `content` que no sea un objeto (tanto en mensajes `user` como `assistant`) lanza `"Invalid content block (expected dict, got …)"`. Antes aparecían como un `ClassCastException` crudo; ahora se informan de forma coherente como `MessageParseException` (igual que el resto de validaciones estructurales del parser).

## Jerarquía de tipos de mensaje

```
Message (sealed interface)
├── UserMessage (record) - Messages from user or tool results
├── AssistantMessage (record) - Messages from Claude
│   └── content: List<ContentBlock>
│       ├── TextBlock - Plain text
│       ├── ThinkingBlock - Claude's reasoning (extended thinking)
│       ├── ToolUseBlock - Tool invocation (caller executes)
│       ├── ToolResultBlock - Tool results
│       ├── ServerToolUseBlock - Server-side tool invocation (advisor, web_search, etc.)
│       └── ServerToolResultBlock - Server-side tool result
├── SystemMessage (record) - System notifications
├── TaskStartedMessage (record) - Task lifecycle: task started
├── TaskProgressMessage (record) - Task lifecycle: task in progress
├── TaskNotificationMessage (record) - Task lifecycle: task completed/failed/stopped
├── TaskUpdatedMessage (record) - Task lifecycle: state-change patch (may be the only terminal signal)
├── MirrorErrorMessage (record) - Non-fatal SessionStore.append() failure
├── HookEventMessage (record) - Hook lifecycle events (when includeHookEvents enabled)
├── ResultMessage (record) - Final result with cost/usage/timing
├── StreamEvent (record) - Partial streaming updates
├── RateLimitEvent (record) - Rate limit status change notifications
└── ConversationResetMessage (record) - Conversation replaced mid-session (e.g. /clear)
```

> **Nota de compatibilidad (v0.1.23).** `ConversationResetMessage` amplió esta unión
> sellada, así que un `switch` exhaustivo sin rama `default` deja de compilar hasta
> que se añade un case. Consulta [Pattern matching](#pattern-matching).

## UserMessage

Representa mensajes del usuario. El contenido puede ser una cadena simple o una lista de bloques de contenido estructurados (por ejemplo, cuando se incluyen resultados de herramientas).

### Campos

```java
record UserMessage(
    Object content,                          // String or List<ContentBlock>
    @Nullable String uuid,                   // Unique message identifier
    @Nullable String parentToolUseId,        // Set when inside a subagent tool use
    @Nullable Map<String, Object> toolUseResult  // Tool execution metadata (file edits, etc.)
) implements Message
```

### Métodos

| Método | Descripción |
|--------|-------------|
| `type()` | Devuelve `"user"` |
| `contentAsString()` | Devuelve el contenido como String, o null si es estructurado |
| `contentAsBlocks()` | Devuelve el contenido como `List<ContentBlock>`, o null si es una cadena |

### Ejemplo

```java
if (msg instanceof UserMessage user) {
    // Simple string content
    String text = user.contentAsString();
    if (text != null) {
        System.out.println("User said: " + text);
    }

    // Structured content blocks
    List<ContentBlock> blocks = user.contentAsBlocks();
    if (blocks != null) {
        for (ContentBlock block : blocks) {
            if (block instanceof ToolResultBlock result) {
                System.out.println("Tool result for: " + result.toolUseId());
            }
        }
    }

    // Check if this message is from a subagent
    if (user.parentToolUseId() != null) {
        System.out.println("Subagent message, parent tool: " + user.parentToolUseId());
    }

    // Access tool execution metadata (e.g., file edit details)
    Map<String, Object> toolResult = user.toolUseResult();
    if (toolResult != null) {
        System.out.println("File edited: " + toolResult.get("filePath"));
    }
}
```

## AssistantMessage

Representa mensajes de Claude, que contienen uno o más bloques de contenido.

### Campos

```java
record AssistantMessage(
    List<ContentBlock> content,              // List of content blocks
    String model,                            // Model that generated this response
    @Nullable String parentToolUseId,        // Set when inside a subagent tool use
    @Nullable AssistantMessageError error,   // Error if the response contains an error
    @Nullable Map<String, Object> usage,     // Per-turn token usage (input_tokens, output_tokens, cache tokens, etc.)
    @Nullable String messageId,              // Unique message ID from the API (e.g. "msg_01HRq...")
    @Nullable String stopReason,             // Reason the model stopped (e.g. "end_turn")
    @Nullable String sessionId,              // Session ID this message belongs to
    @Nullable String uuid                    // Unique identifier in the session transcript
) implements Message
```

El mapa `usage` contiene, cuando está disponible, los datos de consumo de tokens de la API por turno, con claves como `input_tokens`, `output_tokens` y campos relacionados con la caché. Los campos `messageId`, `stopReason`, `sessionId` y `uuid` recogen identificadores a nivel de API. Admiten null y están ausentes en mensajes parciales o de streaming. También hay constructores retrocompatibles sin los campos más recientes.

### Métodos

| Método | Descripción |
|--------|-------------|
| `type()` | Devuelve `"assistant"` |
| `getTextContent()` | Concatena el texto de todos los bloques `TextBlock` |
| `hasToolUse()` | Devuelve `true` si hay al menos un `ToolUseBlock` |

### Valores de AssistantMessageError

| Constante del enum | Valor de cadena | Descripción |
|---------------|-------------|-------------|
| `AUTHENTICATION_FAILED` | `"authentication_failed"` | Clave de API inválida o ausente |
| `BILLING_ERROR` | `"billing_error"` | Problema de facturación |
| `RATE_LIMIT` | `"rate_limit"` | Límite de peticiones superado |
| `INVALID_REQUEST` | `"invalid_request"` | Petición malformada |
| `SERVER_ERROR` | `"server_error"` | Error interno del servidor |
| `UNKNOWN` | `"unknown"` | Error desconocido o no reconocido |

### Ejemplo

```java
if (msg instanceof AssistantMessage assistant) {
    // Get all text
    String text = assistant.getTextContent();
    System.out.println("Claude: " + text);

    // Which model responded
    System.out.println("Model: " + assistant.model());

    // Process content blocks
    for (ContentBlock block : assistant.content()) {
        switch (block) {
            case TextBlock text ->
                System.out.println("Text: " + text.text());
            case ThinkingBlock thinking ->
                System.out.println("Thinking: " + thinking.thinking());
            case ToolUseBlock tool ->
                System.out.println("Used tool: " + tool.name() + " with input: " + tool.input());
            case ToolResultBlock result ->
                System.out.println("Tool result: " + result.content());
        }
    }

    // Check for errors
    if (assistant.error() != null) {
        System.err.println("Error: " + assistant.error().getValue());
    }

    // Check if this is from a subagent
    if (assistant.parentToolUseId() != null) {
        System.out.println("Subagent response, parent: " + assistant.parentToolUseId());
    }
}
```

## SystemMessage

Notificaciones y eventos a nivel de sistema procedentes de la CLI.

### Campos

```java
record SystemMessage(
    String subtype,           // Message subtype (e.g., "init")
    Map<String, Object> data  // Full raw message data
) implements Message
```

### Ejemplo

```java
if (msg instanceof SystemMessage system) {
    System.out.println("System event: " + system.subtype());
    System.out.println("Data: " + system.data());
}
```

## Mensajes de tarea

Los mensajes de tarea son subtipos tipados de mensajes de sistema que se emiten durante los eventos del ciclo de vida de las tareas de subagentes. Implementan `Message` directamente y `type()` devuelve `"system"`. Las comprobaciones existentes de `instanceof SystemMessage` **no** coinciden con ellos: usa el tipo concreto.

### TaskStartedMessage

Se emite cuando arranca una tarea (subagente).

```java
record TaskStartedMessage(
    String subtype,               // always "task_started"
    Map<String, Object> data,     // raw message data
    String taskId,                // unique task identifier
    String description,           // human-readable description
    String uuid,                  // message UUID
    String sessionId,             // session identifier
    @Nullable String toolUseId,   // tool use ID (may be null)
    @Nullable String taskType     // task type (may be null)
) implements Message
```

| Método | Descripción |
|--------|-------------|
| `type()` | Devuelve `"system"` |
| `get(String key)` | Obtiene un valor del mapa de datos crudo |

### TaskProgressMessage

Se emite periódicamente mientras una tarea se ejecuta.

```java
record TaskProgressMessage(
    String subtype,                  // always "task_progress"
    Map<String, Object> data,        // raw message data
    String taskId,                   // unique task identifier
    String description,              // human-readable description
    TaskUsage usage,                 // token/tool usage so far
    String uuid,                     // message UUID
    String sessionId,                // session identifier
    @Nullable String toolUseId,      // tool use ID (may be null)
    @Nullable String lastToolName    // last tool used (may be null)
) implements Message
```

| Método | Descripción |
|--------|-------------|
| `type()` | Devuelve `"system"` |
| `get(String key)` | Obtiene un valor del mapa de datos crudo |

### TaskNotificationMessage

Se emite cuando una tarea termina, falla o se detiene.

```java
record TaskNotificationMessage(
    String subtype,                  // always "task_notification"
    Map<String, Object> data,        // raw message data
    String taskId,                   // unique task identifier
    TaskNotificationStatus status,   // COMPLETED, FAILED, or STOPPED
    String outputFile,               // path to task output file
    String summary,                  // human-readable summary
    String uuid,                     // message UUID
    String sessionId,                // session identifier
    @Nullable String toolUseId,      // tool use ID (may be null)
    @Nullable TaskUsage usage        // final usage statistics (may be null)
) implements Message
```

### TaskNotificationStatus

```java
enum TaskNotificationStatus {
    COMPLETED("completed"),
    FAILED("failed"),
    STOPPED("stopped")
}
```

### TaskUpdatedMessage

Se emite en eventos `system`/`task_updated` conforme una tarea en segundo plano avanza por su ciclo de vida. `patch` lleva los campos que cambiaron (por ejemplo, `status`, `end_time`).

El estado terminal de una tarea a veces llega **solo** como `TaskUpdatedMessage`, sin el `TaskNotificationMessage` correspondiente: por ejemplo, una tarea detenida con `TaskStop` informa aquí `status="killed"`, y la notificación equivalente a veces se suprime. Quien siga los IDs de tareas activas debería limpiarlos ante un estado terminal en **cualquiera** de los dos mensajes.

El análisis es defensivo: un `patch` ausente o que no sea un mapa se convierte en un mapa vacío, y un estado desconocido o ausente en `null`, de modo que un evento de ciclo de vida nunca rompe el análisis.

```java
record TaskUpdatedMessage(
    String subtype,                     // always "task_updated"
    Map<String, Object> data,           // raw message data
    String taskId,                      // unique task identifier ("" if absent)
    Map<String, Object> patch,          // changed fields; never null (empty if absent)
    @Nullable TaskUpdatedStatus status, // patch.status, or null if absent/unknown
    @Nullable String sessionId,         // session identifier (may be null)
    @Nullable String uuid               // message UUID (may be null)
) implements Message
```

| Método | Descripción |
|--------|-------------|
| `type()` | Devuelve `"system"` |
| `isTerminal()` | `true` si `status` está presente y pertenece a `TERMINAL_TASK_STATUSES` |
| `get(String key)` | Obtiene un valor del mapa de datos crudo |
| `TaskUpdatedMessage.TERMINAL_TASK_STATUSES` | `Set.of("completed", "failed", "stopped", "killed")` — estados terminales que abarcan ambos vocabularios de ciclo de vida |

### TaskUpdatedStatus

`task_updated` informa el `KILLED` crudo; la CLI solo lo traduce a `STOPPED` (`TaskNotificationStatus`) cuando emite un `task_notification`.

```java
enum TaskUpdatedStatus {
    PENDING("pending"),     // non-terminal
    RUNNING("running"),     // non-terminal
    PAUSED("paused"),       // non-terminal
    COMPLETED("completed"), // terminal
    FAILED("failed"),       // terminal
    KILLED("killed")        // terminal
}
```

`TaskUpdatedStatus.fromValueOrNull(String)` resuelve un estado crudo sin lanzar excepción ante un valor desconocido o `null` (se usa en el análisis defensivo); `fromValue(String)` lanza excepción ante un valor desconocido.

### TaskUsage

Estadísticas de uso de una tarea:

```java
record TaskUsage(
    int totalTokens,   // total tokens used
    int toolUses,      // number of tool invocations
    int durationMs     // task duration in milliseconds
)
```

### Ejemplo: gestionar mensajes de tarea

```java
for (Message msg : client.receiveMessages()) {
    switch (msg) {
        case TaskStartedMessage task ->
            System.out.println("Task started: " + task.taskId() + " - " + task.description());
        case TaskProgressMessage task -> {
            TaskUsage usage = task.usage();
            System.out.printf("Task progress: %s | tokens=%d tools=%d%n",
                task.taskId(), usage.totalTokens(), usage.toolUses());
        }
        case TaskNotificationMessage task -> {
            System.out.printf("Task %s: %s (%s)%n",
                task.status(), task.taskId(), task.summary());
            if (task.usage() != null) {
                System.out.println("Final tokens: " + task.usage().totalTokens());
            }
        }
        case TaskUpdatedMessage task -> {
            System.out.printf("Task updated: %s -> %s%n", task.taskId(), task.status());
            if (task.isTerminal()) {
                // Terminal state may arrive ONLY here (no TaskNotificationMessage),
                // e.g. status=KILLED for a task stopped via TaskStop.
                System.out.println("Task finished (terminal): " + task.taskId());
            }
        }
        default -> {}
    }
}
```

## MirrorErrorMessage

Mensaje de sistema no fatal que se emite cuando una llamada a `SessionStore.append()` falla tras agotar los reintentos (`MIRROR_APPEND_MAX_ATTEMPTS=3`). La transcripción en disco local ya es duradera, así que la sesión continúa sin verse afectada: solo a la copia replicada en el almacén externo le faltará el lote fallido.

```java
record MirrorErrorMessage(
    String subtype,                    // always "mirror_error"
    Map<String, Object> data,          // raw payload
    @Nullable SessionKey key,          // store key the failed append targeted (null if pre-resolution)
    String error                       // failure description
) implements Message
```

Está modelado como un miembro de primer nivel de la interfaz sellada `Message` (los records de Java no pueden extender records). `subtype` siempre es `"mirror_error"`; el campo base `data` lleva el payload crudo para downcasts al estilo `SystemMessage`.

### Ejemplo: recuperarse de un error de réplica

```java
import in.vidyalai.claude.sdk.types.message.MirrorErrorMessage;

for (Message msg : ClaudeSDK.query(prompt, options)) {
    switch (msg) {
        case MirrorErrorMessage err -> {
            String sid = err.key() != null ? err.key().sessionId() : "<unknown>";
            log.warn("Session {} mirror gap: {}. Will catch up via importSessionToStore.",
                     sid, err.error());
            // Optional: schedule a one-shot import to backfill from the local file
            //   ClaudeSDK.importSessionToStore(sid, store, null);
        }
        case AssistantMessage a -> System.out.println(a.getTextContent());
        // ... other cases
        default -> { }
    }
}
```

Consulta [Session Store](./feature-session-store.md) para el flujo completo de réplica y la semántica de reintentos.

## HookEventMessage

Evento del ciclo de vida de un hook expuesto en el flujo de mensajes. Solo se emite cuando `includeHookEvents(true)` está definido en `ClaudeAgentOptions`. Consulta [Hooks → Eventos del ciclo de vida de los hooks en el flujo](./feature-hooks.md#eventos-del-ciclo-de-vida-de-los-hooks-en-el-flujo).

```java
record HookEventMessage(
    String subtype,                       // "hook_started" or "hook_response"
    Map<String, Object> data,             // full raw event dict from the CLI
    String hookEventName,                 // e.g. "PreToolUse", "PostToolUse", "Stop"
    @Nullable String sessionId,           // session ID this event belongs to
    @Nullable String uuid                 // unique event ID
) implements Message {
    String type();                        // returns "system"
    <T> T get(String key);                // typed lookup into data
}
```

`type()` devuelve `"system"` por simetría con `SystemMessage`, pero `HookEventMessage` es un miembro aparte de la interfaz sellada: `instanceof SystemMessage` **no** coincide. Ramifica directamente sobre `HookEventMessage`.

Valores de `subtype`:

- `"hook_started"` — cuando un hook empieza a ejecutarse.
- `"hook_response"` — cuando un hook termina; `data` incluye las claves `output`, `exit_code` y `outcome`.

```java
for (Message msg : ClaudeSDK.query(prompt, options.toBuilder().includeHookEvents(true).build())) {
    if (msg instanceof HookEventMessage hook) {
        System.out.printf("[%s] %s%n", hook.subtype(), hook.hookEventName());
        if ("hook_response".equals(hook.subtype())) {
            System.out.println("  outcome: " + hook.get("outcome"));
        }
    }
}
```

## ResultMessage

Resultado final que se envía al terminar cada turno de la conversación, con información de tiempo, coste y uso.

### Campos

```java
record ResultMessage(
    String subtype,                               // "success", "error_during_execution", etc.
    int durationMs,                               // Total duration in milliseconds
    int durationApiMs,                            // API call duration in milliseconds
    boolean isError,                              // Whether the result is an error
    int numTurns,                                 // Number of conversation turns
    String sessionId,                             // Session identifier
    @Nullable String stopReason,                  // Reason the session stopped
    @Nullable Double totalCostUsd,                // Total cost in USD
    @Nullable Map<String, Object> usage,          // Token usage breakdown
    @Nullable String result,                      // Result text
    @Nullable Object structuredOutput,            // Structured output (when json_schema used)
    @Nullable Map<String, ModelUsage> modelUsage,  // Per-model usage breakdown
    @Nullable List<Object> permissionDenials,     // Permission denials during session
    @Nullable DeferredToolUse deferredToolUse,    // Tool call deferred by a PreToolUse hook
    @Nullable List<String> errors,                // Error messages from the CLI
    @Nullable Integer apiErrorStatus,             // HTTP status of failing API call when isError=true and subtype="success"
    @Nullable String uuid,                        // Unique message identifier in session
    @Nullable String terminalReason               // Why the query loop terminated
) implements Message
```

El campo `errors` contiene una lista de mensajes de error de la CLI, útil para diagnosticar códigos de salida distintos de cero. El campo `modelUsage` ofrece el desglose de tokens por modelo, tipado como `ModelUsage`: consulta [Referencia de la API → ModelUsage](./api-message-types.md#modelusage). El campo `deferredToolUse` se rellena cuando un hook `PreToolUse` devolvió `permissionDecision: "defer"`: consulta [Hooks → Decisión de permiso: `"defer"`](./feature-hooks.md#decisión-de-permiso-defer). El campo `apiErrorStatus` lleva el código HTTP (por ejemplo, `429`, `500`, `529`) de la llamada a la API que falló cuando `isError=true` y `subtype="success"` (la API falló pero la sesión sí se completó); es seguro registrarlo, porque no lleva contenido de mensajes. También hay constructores retrocompatibles sin los campos más recientes.

El campo `terminalReason` informa de por qué terminó el bucle de la consulta: `"completed"`, `"max_turns"`, `"aborted_streaming"`, `"aborted_tools"`. Los dos valores `aborted_*` significan que el turno se canceló mediante `ClaudeSDKClient.interrupt()`, lo que da a quien llama una marca explícita de cancelación sin necesidad de un subtipo de resultado aparte:

```java
try (var client = ClaudeSDK.createClient()) {
    client.connect("Long running task");
    client.interrupt();

    for (Message msg : client.receiveResponse()) {
        if (msg instanceof ResultMessage result) {
            // "aborted_streaming" or "aborted_tools" after an interrupt
            System.out.println("Ended because: " + result.terminalReason());
        }
    }
}
```

Es `null` cuando la CLI no informó de una razón terminal: versiones antiguas de la CLI, o un resultado que no pasó por el bucle de la consulta, como un comando de barra local.

### DeferredToolUse

```java
record DeferredToolUse(
    String id,                       // unique identifier of the deferred tool call
    String name,                     // tool name
    Map<String, Object> input        // tool input arguments
)
```

### Subtipos habituales

- `"success"` — la conversación terminó correctamente
- `"error_max_budget_usd"` — se alcanzó el límite de presupuesto
- `"error_max_turns"` — se alcanzó el límite de turnos

### Ejemplo

```java
if (msg instanceof ResultMessage result) {
    System.out.println("Conversation complete!");
    System.out.println("Subtype: " + result.subtype());
    System.out.println("Duration: " + result.durationMs() + "ms");
    System.out.println("API duration: " + result.durationApiMs() + "ms");
    System.out.println("Turns: " + result.numTurns());
    System.out.println("Session: " + result.sessionId());

    if (result.totalCostUsd() != null) {
        System.out.println("Cost: $" + result.totalCostUsd());
    }

    if (result.usage() != null) {
        System.out.println("Input tokens: " + result.usage().get("input_tokens"));
        System.out.println("Output tokens: " + result.usage().get("output_tokens"));
    }

    if (result.result() != null) {
        System.out.println("Result: " + result.result());
    }

    if (result.structuredOutput() != null) {
        System.out.println("Structured: " + result.structuredOutput());
    }
}
```

## StreamEvent

Actualizaciones parciales de mensajes durante el streaming. Solo se emiten cuando `includePartialMessages` está habilitado.

### Campos

```java
record StreamEvent(
    String uuid,                             // Unique event identifier
    String sessionId,                        // Session identifier
    Map<String, Object> event,               // Raw Anthropic API stream event data
    @Nullable String parentToolUseId         // Set when inside a subagent tool use
) implements Message
```

### Métodos

| Método | Descripción |
|--------|-------------|
| `type()` | Devuelve `"stream_event"` |
| `eventType()` | Devuelve el tipo de evento del mapa interno, o null |

### Tipos de evento habituales (de `event.get("type")`)

- `"content_block_start"` — nuevo bloque de contenido iniciado
- `"content_block_delta"` — actualización incremental del contenido
- `"content_block_stop"` — bloque de contenido completado
- `"message_start"` — mensaje iniciado
- `"message_delta"` — actualización del mensaje
- `"message_stop"` — mensaje completado

### Ejemplo

```java
if (msg instanceof StreamEvent event) {
    System.out.println("Stream event: " + event.eventType());
    System.out.println("UUID: " + event.uuid());
    System.out.println("Session: " + event.sessionId());
    // Access raw event data
    Object delta = event.event().get("delta");
    if (delta != null) {
        System.out.println("Delta: " + delta);
    }
}
```

## RateLimitEvent

Lo emite la CLI cada vez que cambia el estado del límite de peticiones. Úsalo para avisar a las personas antes de que lleguen a un límite duro, o para retroceder con elegancia cuando se supere.

### Campos

```java
record RateLimitEvent(
    RateLimitInfo rateLimitInfo,  // Detailed rate limit status information
    String uuid,                  // Unique identifier for this event
    String sessionId              // Session identifier
) implements Message
```

### RateLimitInfo

```java
record RateLimitInfo(
    RateLimitStatus status,                      // Current rate limit status
    @Nullable Long resetsAt,                     // Unix timestamp when the rate limit window resets
    @Nullable RateLimitType rateLimitType,       // Which rate limit window applies
    @Nullable Double utilization,               // Fraction of the rate limit consumed (0.0–1.0)
    @Nullable RateLimitStatus overageStatus,    // Status of overage/pay-as-you-go usage
    @Nullable Long overageResetsAt,             // Unix timestamp when overage window resets
    @Nullable String overageDisabledReason,     // Why overage is unavailable if rejected
    @Nullable Map<String, Object> raw           // Full raw map including any unmodeled fields
)
```

### RateLimitStatus

| Constante del enum | Valor de cadena | Descripción |
|---------------|-------------|-------------|
| `ALLOWED` | `"allowed"` | Dentro de los límites, no hay que hacer nada |
| `ALLOWED_WARNING` | `"allowed_warning"` | Acercándose al límite: avisa a la persona |
| `REJECTED` | `"rejected"` | Se alcanzó el límite: las peticiones se rechazarán |

### RateLimitType

| Constante del enum | Valor de cadena | Descripción |
|---------------|-------------|-------------|
| `FIVE_HOUR` | `"five_hour"` | Ventana móvil de 5 horas |
| `SEVEN_DAY` | `"seven_day"` | Ventana móvil de 7 días |
| `SEVEN_DAY_OPUS` | `"seven_day_opus"` | Ventana móvil de 7 días para modelos Opus |
| `SEVEN_DAY_SONNET` | `"seven_day_sonnet"` | Ventana móvil de 7 días para modelos Sonnet |
| `OVERAGE` | `"overage"` | Límite de excedente / pago por uso |

### Ejemplo

```java
for (Message msg : ClaudeSDK.query(prompt, options)) {
    if (msg instanceof RateLimitEvent rle) {
        RateLimitInfo info = rle.rateLimitInfo();
        switch (info.status()) {
            case ALLOWED -> {
                // No action needed
            }
            case ALLOWED_WARNING -> {
                System.out.printf("Rate limit warning: %.0f%% used%n",
                    info.utilization() != null ? info.utilization() * 100 : 0);
                if (info.resetsAt() != null) {
                    System.out.println("Resets at: " + Instant.ofEpochSecond(info.resetsAt()));
                }
            }
            case REJECTED -> {
                System.err.println("Rate limit exceeded! Limit type: " + info.rateLimitType());
                if (info.resetsAt() != null) {
                    System.err.println("Resets at: " + Instant.ofEpochSecond(info.resetsAt()));
                }
            }
        }
    }
}
```

## ConversationResetMessage

Se emite cuando la conversación de la sesión se reemplaza sin terminar la conexión: después de `/clear`, o de cualquier otro flujo que descarte la transcripción a mitad de sesión. Antes de la v0.1.23 el parser descartaba este marco en silencio, así que las aplicaciones nunca veían los reinicios, incluidos los que no habían iniciado ellas.

### Campos

```java
record ConversationResetMessage(
    String newConversationId,  // Opaque id for the fresh conversation
    String uuid,               // Unique identifier of this message
    String sessionId           // The session that was reset (the outgoing one)
) implements Message
```

### Por qué importa

Un reinicio borra el historial de la conversación *y* pone a cero los totales acumulados que informan los `ResultMessage` posteriores. Si acumulas coste o uso de tokens a lo largo de una sesión de streaming de larga duración, este marco es tu única señal para tomar una instantánea antes de que vuelvan a empezar en cero.

También marca una frontera de session id: `newConversationId` es una clave para que la interfaz cuelgue una transcripción vacía, **no** el `sessionId` de lo que viene después. Los mensajes posteriores al reinicio llevan un `sessionId` nuevo: léelo del siguiente mensaje.

```java
double runningCostUsd = 0.0;

for (Message msg : client.receiveResponse()) {
    switch (msg) {
        case ResultMessage r -> {
            if (r.totalCostUsd() != null) runningCostUsd += r.totalCostUsd();
        }
        case ConversationResetMessage reset -> {
            System.out.printf("Reset: %s -> new conversation %s%n",
                    reset.sessionId(), reset.newConversationId());
            archive(runningCostUsd);   // CLI counters restart from zero here
        }
        default -> { }
    }
}
```

## Origen del mensaje

`UserMessage.origin()` y `ResultMessage.origin()` exponen *por qué* se inició un turno. En el modo de entrada por streaming, una sola conexión entrelaza los turnos que envía tu aplicación con turnos que la propia sesión inyecta: notificaciones de tareas en segundo plano, prompts de tareas programadas que se dispararon, mensajes de canal MCP, mensajes retransmitidos desde sesiones pares. En un `ResultMessage`, el campo informa del origen del mensaje que *disparó* ese turno, que es lo que te permite distinguir «esto responde a mi prompt» de «esto responde a una tarea en segundo plano».

```java
MessageOrigin origin = result.origin();
if (origin == null || origin.isHuman()) {
    // a turn this application submitted
} else if (origin.kind() == MessageOriginKind.TASK_NOTIFICATION) {
    // follow-up turn driven by a background task
    render(origin.subkind());   // scheduled-trigger / peer-send-message, or null
}
```

`origin` es null cuando la CLI no atribuyó el mensaje, que es lo normal para los prompts que envías tú. Para que tus propios turnos queden atribuidos, marca tú mismo el mapa del mensaje y envíalo con `ClaudeSDKClient.query(Iterator)`:

```java
Map<String, Object> message = new HashMap<>();
message.put("type", "user");
message.put("message", Map.of("role", "user", "content", prompt));
message.put("parent_tool_use_id", null);
message.put("origin", Map.of("kind", "human"));

client.query(List.of(message).iterator());
```

Desde un host del SDK solo se acepta el kind `human`, y requiere Claude Code >= 2.1.210. Los mensajes de usuario con resultados de herramientas nunca llevan origen.

Los kinds no reconocidos siguen siendo visibles en lugar de convertirse en errores: `kind()` es null mientras `kindValue()` guarda la cadena del protocolo, y `isHuman()` es false, así que una atribución desconocida siempre se lee como «no humana». El objeto completo de la CLI se conserva en `raw()`. Referencia campo a campo: [MessageOrigin](./api-message-types.md#messageorigin).

Campos como `from`, `name` y `fromSession` son **afirmados por quien envía**: úsalos para enrutar respuestas y mostrar información, nunca como prueba de identidad.

## Bloques de contenido

Los mensajes del asistente (y los mensajes de usuario estructurados) contienen bloques de contenido.

### Jerarquía de ContentBlock

```java
sealed interface ContentBlock permits TextBlock, ThinkingBlock,
    ToolUseBlock, ToolResultBlock,
    ServerToolUseBlock, ServerToolResultBlock,
    ImageBlock, DocumentBlock, UnknownBlock {}
```

Como la interfaz está sellada, un `switch` exhaustivo sobre ella deja de compilar cada vez que crece la cláusula `permits`. Es preferible tratar `UnknownBlock` de forma explícita antes que añadir una rama `default`: así el próximo tipo de bloque nuevo será un error de compilación sobre el que puedes actuar, y no un paso silencioso.

### TextBlock

Contenido de texto plano procedente de Claude.

```java
record TextBlock(
    String text  // Text content
) implements ContentBlock
```

### ThinkingBlock

El razonamiento interno de Claude cuando el razonamiento extendido está habilitado. Incluye una firma criptográfica.

```java
record ThinkingBlock(
    String thinking,   // Thinking content
    String signature   // Cryptographic signature for the thinking block
) implements ContentBlock
```

### ToolUseBlock

Invocación de una herramienta por parte de Claude.

```java
record ToolUseBlock(
    String id,                          // Tool use ID (matches ToolResultBlock.toolUseId)
    String name,                        // Tool name (e.g., "Bash", "Read")
    @Nullable Map<String, Object> input // Tool input parameters
) implements ContentBlock
```

### ToolResultBlock

Resultados de la ejecución de herramientas.

```java
record ToolResultBlock(
    String toolUseId,          // Corresponding ToolUseBlock.id
    @Nullable Object content,  // Result content (String or structured)
    @Nullable Boolean isError  // Whether this is an error result
) implements ContentBlock
```

### ServerToolUseBlock

Invocación de una herramienta del lado del servidor que la API ejecuta en nombre del modelo: quien llama nunca devuelve un resultado. Se usa para `advisor`, `web_search`, `web_fetch`, `code_execution`, `bash_code_execution`, `text_editor_code_execution`, `tool_search_tool_regex` y `tool_search_tool_bm25`.

```java
record ServerToolUseBlock(
    String id,                  // Server tool use ID
    String name,                // One of ServerToolName values (kept as raw String for forward compat)
    Map<String, Object> input   // Tool input parameters
) implements ContentBlock
```

El campo `name` es un discriminador: ramifica sobre él para saber qué herramienta de servidor se invocó. El enum `ServerToolName` enumera los valores reconocidos:

```java
public enum ServerToolName {
    ADVISOR("advisor"),
    WEB_SEARCH("web_search"),
    WEB_FETCH("web_fetch"),
    CODE_EXECUTION("code_execution"),
    BASH_CODE_EXECUTION("bash_code_execution"),
    TEXT_EDITOR_CODE_EXECUTION("text_editor_code_execution"),
    TOOL_SEARCH_TOOL_REGEX("tool_search_tool_regex"),
    TOOL_SEARCH_TOOL_BM25("tool_search_tool_bm25");
}
```

### ServerToolResultBlock

Bloque de resultado devuelto para una llamada a una herramienta del lado del servidor. Replica la forma de `ToolResultBlock`; `content` es el mapa crudo de la API, opaco para esta capa: quien necesite el esquema de resultado de una herramienta concreta puede inspeccionar `content.get("type")`.

La CLI los emite como bloques de contenido `advisor_tool_result` (el único tipo de resultado de herramienta de servidor disponible hoy); se convierten en `ServerToolResultBlock`.

```java
record ServerToolResultBlock(
    String toolUseId,            // Matches the corresponding ServerToolUseBlock.id
    Map<String, Object> content  // Raw result content (e.g. {"type":"advisor_result", ...})
) implements ContentBlock
```

En el caso concreto de la herramienta advisor, el campo `type` del mapa `content` será uno de:
- `"advisor_result"` — resultado de texto con un campo `text`
- `"advisor_redacted_result"` — blob cifrado con un campo `encrypted_content`
- `"advisor_tool_result_error"` — payload de error

### ImageBlock y DocumentBlock

Ambos llegan por el mismo flujo: leer un PDF con la herramienta `Read`. El `tool_result` en sí solo anuncia el número de páginas, y el archivo llega en un **mensaje de usuario aparte**. La CLI usa una de dos formas para ese mensaje, y ambas se han observado en la CLI 2.1.218 con el mismo archivo de 1,5 MB en ejecuciones distintas:

- un bloque `image` por página renderizada (`image/jpeg`, base64), o
- un único bloque `document` con el PDF completo (`application/pdf`, base64).

```java
record ImageBlock(Map<String, Object> source) implements ContentBlock
record DocumentBlock(Map<String, Object> source) implements ContentBlock
```

`source` se mantiene como mapa crudo porque la API define las formas de origen `base64`, `url`, `file`, `text` y `content`, y puede añadir más. Tres accesores cubren el caso base64, y cada uno devuelve `null` si la clave falta o no es una cadena:

```java
for (ContentBlock block : userMessage.contentAsBlocks()) {
    switch (block) {
        case ImageBlock image -> System.out.printf("page image: %s, %d base64 chars%n",
                image.mediaType(), image.data() == null ? 0 : image.data().length());
        case DocumentBlock doc -> System.out.printf("document: %s%n", doc.mediaType());
        default -> { }
    }
}
```

| Método | Devuelve |
|---|---|
| `sourceType()` | `source["type"]`, por ejemplo `"base64"` |
| `mediaType()` | `source["media_type"]`, por ejemplo `"image/jpeg"` o `"application/pdf"` |
| `data()` | `source["data"]`, el payload en base64 |

> **Leer PDFs requiere subir `maxBufferSize`.** Estos bloques son base64 —cuatro bytes por cada tres— en una única línea de la salida estándar de la CLI, así que un PDF de 1,5 MB son unos 2,1 MB de línea frente al valor predeterminado de 1 MB. El valor predeterminado no ha cambiado (coincide con el SDK de Python), así que quien lea archivos de cualquier tamaño debe fijarlo explícitamente:
>
> ```java
> ClaudeAgentOptions options = ClaudeAgentOptions.builder()
>     .allowedTools(List.of("Read"))
>     .maxBufferSize(16 * 1024 * 1024)
>     .build();
> ```
>
> Sin esto, cualquier agente con `Read` sobre un directorio de PDFs falla a mitad de ejecución.

### UnknownBlock

Compatibilidad hacia adelante para tipos de bloque que esta versión del SDK no modela.

```java
record UnknownBlock(
    String type,             // The unrecognised discriminator
    Map<String, Object> raw  // The block, preserved whole
) implements ContentBlock
```

`MessageParser` ya devolvía `null` para un *tipo de mensaje* no reconocido, de modo que una CLI más nueva no puede tumbar un SDK más antiguo; ahora los bloques de contenido se comportan igual en lugar de lanzar excepción. El bloque no reconocido se conserva íntegro y se registra una vez por tipo en `WARNING` desde `in.vidyalai.claude.sdk.internal.MessageParser`.

Esto es exactamente lo que `image` y `document` necesitaban y no tenían. El comportamiento anterior lanzaba `MessageParseException`, lo que mataba el hilo lector, descartaba todos los demás bloques del mensaje —incluido el texto que el modelo ya había producido— y aparecía como un fallo de decodificación JSON nombrando un tipo que quien llamó nunca pidió.

## Pattern matching

El pattern matching de Java hace que tratar los mensajes sea elegante y seguro respecto a tipos.

### Expresión switch

```java
String result = switch (message) {
    case UserMessage u -> "User: " + u.contentAsString();
    case AssistantMessage a -> "Claude: " + a.getTextContent();
    case SystemMessage s -> "System: " + s.subtype();
    case TaskStartedMessage t -> "Task started: " + t.taskId();
    case TaskProgressMessage t -> "Task progress: " + t.taskId();
    case TaskNotificationMessage t -> "Task done: " + t.status();
    case TaskUpdatedMessage t -> "Task updated: " + t.status();
    case MirrorErrorMessage m -> "Mirror error: " + m.error();
    case HookEventMessage h -> "Hook " + h.subtype() + ": " + h.hookEventName();
    case ResultMessage r -> "Cost: $" + r.totalCostUsd();
    case StreamEvent e -> "Streaming: " + e.eventType();
    case RateLimitEvent rle -> "Rate limit: " + rle.rateLimitInfo().status();
    case ConversationResetMessage c -> "Conversation reset: " + c.newConversationId();
};
```

Como `Message` está sellada, un `switch` como este —sin `default`— debe enumerar todos los tipos permitidos, y el compilador lo exige. Ese es justamente el objetivo: cuando se añade un tipo de mensaje nuevo, obtienes un error de compilación en cada switch exhaustivo en lugar de descartar marcos en silencio en tiempo de ejecución.

La contrapartida es que añadir un tipo es un cambio que rompe el código fuente. `ConversationResetMessage` hizo exactamente eso en la v0.1.23. Si prefieres absorber las futuras incorporaciones en silencio, añade una rama `default`:

```java
String result = switch (message) {
    case AssistantMessage a -> "Claude: " + a.getTextContent();
    case ResultMessage r -> "Cost: $" + r.totalCostUsd();
    default -> "(other)";        // future message types land here
};
```

### Pattern matching anidado

```java
switch (message) {
    case AssistantMessage assistant -> {
        for (ContentBlock block : assistant.content()) {
            switch (block) {
                case TextBlock text ->
                    System.out.println(text.text());
                case ThinkingBlock thinking ->
                    System.out.println("[Thinking] " + thinking.thinking());
                case ToolUseBlock tool ->
                    System.out.println("[Tool] " + tool.name() + ": " + tool.input());
                case ToolResultBlock result ->
                    System.out.println("[Result] " + result.content());
                case ServerToolUseBlock stu ->
                    System.out.println("[Server Tool] " + stu.name() + ": " + stu.input());
                case ServerToolResultBlock str ->
                    System.out.println("[Server Tool Result] " + str.content());
            }
        }
    }
    case MirrorErrorMessage err ->
        System.err.println("Mirror error: " + err.error());
    case ResultMessage result ->
        System.out.println("Done in " + result.durationMs() + "ms, cost: $" + result.totalCostUsd());
    default -> {}
}
```

### instanceof con variables de patrón

```java
if (message instanceof AssistantMessage assistant) {
    // 'assistant' variable available here
    for (ContentBlock block : assistant.content()) {
        if (block instanceof ToolUseBlock tool) {
            // 'tool' variable available here
            processToolUse(tool.name(), tool.input());
        }
    }
}
```

## Ejemplos

### Ejemplo 1: procesar todos los mensajes

```java
for (Message msg : ClaudeSDK.query(prompt, options)) {
    switch (msg) {
        case UserMessage user ->
            log("User", user.contentAsString());

        case AssistantMessage assistant -> {
            log("Claude", assistant.getTextContent());
            log("Model", assistant.model());
            for (ContentBlock block : assistant.content()) {
                if (block instanceof ToolUseBlock tool) {
                    log("Tool Used", tool.name());
                }
            }
        }

        case ResultMessage result -> {
            log("Result", "Cost: $" + result.totalCostUsd());
            log("Result", "Duration: " + result.durationMs() + "ms");
            if (result.usage() != null) {
                log("Tokens", result.usage().get("input_tokens") + " in / " +
                             result.usage().get("output_tokens") + " out");
            }
        }

        case SystemMessage system ->
            log("System", system.subtype());

        case StreamEvent event ->
            log("Stream", event.eventType());

        case RateLimitEvent rle ->
            log("RateLimit", rle.rateLimitInfo().status().getValue());
    }
}
```

### Ejemplo 2: extraer información concreta

```java
List<Message> messages = ClaudeSDK.query(prompt, options);

// Find last assistant message
Optional<AssistantMessage> lastAssistant = messages.stream()
    .filter(m -> m instanceof AssistantMessage)
    .map(m -> (AssistantMessage) m)
    .reduce((first, second) -> second);

// Get all text content
String allText = messages.stream()
    .filter(m -> m instanceof AssistantMessage)
    .map(m -> ((AssistantMessage) m).getTextContent())
    .collect(Collectors.joining("\n\n"));

// Get result
Optional<ResultMessage> result = messages.stream()
    .filter(m -> m instanceof ResultMessage)
    .map(m -> (ResultMessage) m)
    .findFirst();

// Get all tool uses
List<ToolUseBlock> toolUses = messages.stream()
    .filter(m -> m instanceof AssistantMessage)
    .flatMap(m -> ((AssistantMessage) m).content().stream())
    .filter(b -> b instanceof ToolUseBlock)
    .map(b -> (ToolUseBlock) b)
    .toList();
```

### Ejemplo 3: gestionar errores

```java
for (Message msg : messages) {
    switch (msg) {
        case AssistantMessage assistant -> {
            if (assistant.error() != null) {
                System.err.println("Assistant error: " + assistant.error().getValue());
                handleAssistantError(assistant.error());
            }
        }

        case ResultMessage result -> {
            if (result.isError()) {
                System.err.println("Result error subtype: " + result.subtype());
                handleResultError(result);
            }
        }

        default -> {}
    }
}
```

### Ejemplo 4: seguimiento del uso de herramientas

```java
Map<String, Integer> toolUsage = new HashMap<>();

for (Message msg : messages) {
    if (msg instanceof AssistantMessage assistant) {
        for (ContentBlock block : assistant.content()) {
            if (block instanceof ToolUseBlock tool) {
                toolUsage.merge(tool.name(), 1, Integer::sum);
            }
        }
    }
}

System.out.println("Tool usage:");
toolUsage.forEach((tool, count) ->
    System.out.println(tool + ": " + count + " times"));
```

### Ejemplo 5: análisis de coste y tokens

```java
double totalCost = 0.0;
int totalDurationMs = 0;
int inputTokens = 0;
int outputTokens = 0;

for (Message msg : messages) {
    if (msg instanceof ResultMessage result) {
        totalDurationMs += result.durationMs();
        if (result.totalCostUsd() != null) {
            totalCost += result.totalCostUsd();
        }
        if (result.usage() != null) {
            Object in = result.usage().get("input_tokens");
            Object out = result.usage().get("output_tokens");
            if (in instanceof Number n) inputTokens += n.intValue();
            if (out instanceof Number n) outputTokens += n.intValue();
        }
    }
}

System.out.println("Total cost: $" + totalCost);
System.out.println("Total duration: " + totalDurationMs + "ms");
System.out.println("Input tokens: " + inputTokens);
System.out.println("Output tokens: " + outputTokens);
```

### Ejemplo 6: filtrar mensajes de subagentes

```java
// Separate top-level messages from subagent messages
List<AssistantMessage> topLevel = new ArrayList<>();
List<AssistantMessage> subagent = new ArrayList<>();

for (Message msg : messages) {
    if (msg instanceof AssistantMessage assistant) {
        if (assistant.parentToolUseId() != null) {
            subagent.add(assistant);
        } else {
            topLevel.add(assistant);
        }
    }
}
```

## Véase también

- [Consultas simples](./feature-simple-queries.md) — procesar mensajes de consultas
- [Conversaciones interactivas](./feature-interactive-conversations.md) — procesar mensajes del cliente
- [Eventos de streaming](./feature-streaming-events.md) — detalles de StreamEvent
- [Referencia de la API: tipos de mensaje](./api-message-types.md) — documentación completa de la API
