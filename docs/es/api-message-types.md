# Referencia de la API de tipos de mensaje

Jerarquía de tipos de los mensajes de Claude.

> **Sobre esta traducción**: la documentación en inglés es la única autoritativa. Esta traducción puede estar desactualizada respecto al [original en inglés](../api-message-types.md); si hay discrepancias, prevalece el inglés. Los bloques de código se mantienen idénticos al original y no se han traducido.

## MessageParser

La clase `MessageParser` convierte los mapas JSON en bruto que llegan del CLI en objetos `Message`
tipados.

```java
public final class MessageParser {
    // Returns null for unrecognized message types (forward compatibility)
    @Nullable
    public static Message parse(Map<String, Object> data) throws MessageParseException;
}
```

Los tipos de mensaje desconocidos devuelven `null` en lugar de lanzar una excepción, lo que permite al
SDK seguir siendo compatible con versiones más nuevas del CLI que puedan emitir tipos nuevos.

## La interfaz Message

```java
sealed interface Message permits UserMessage, AssistantMessage,
    SystemMessage, TaskStartedMessage, TaskProgressMessage,
    TaskNotificationMessage, TaskUpdatedMessage, MirrorErrorMessage,
    HookEventMessage, ResultMessage, StreamEvent, RateLimitEvent,
    ConversationResetMessage {
    String type();
}
```

> **Nota de compatibilidad (v0.1.23).** `ConversationResetMessage` amplió esta unión. Como `Message`
> está sellada, un `switch` exhaustivo sin rama `default` deja de compilar hasta que se añade un caso
> `ConversationResetMessage`. Añade una rama `default ->` si prefieres absorber en silencio las
> incorporaciones futuras.

## UserMessage

```java
record UserMessage(
    Object content,                               // String or List<ContentBlock>
    @Nullable String uuid,                        // Unique message identifier
    @Nullable String parentToolUseId,             // Set when inside a subagent tool use
    @Nullable Map<String, Object> toolUseResult,  // Tool execution metadata
    @Nullable MessageOrigin origin                // Provenance; null when unattributed
) implements Message {
    String type();                    // Returns "user"
    @Nullable String contentAsString();           // Content as String, or null if structured
    @Nullable List<ContentBlock> contentAsBlocks(); // Content as blocks, or null if string
}
```

Sigue disponible un constructor retrocompatible de 4 parámetros sin `origin`. `origin` se rellena en
los turnos inyectados (notificaciones de tarea, mensajes de canal o de peer) y en los mensajes de
usuario que el CLI reproduce; los mensajes de resultado de herramienta nunca lo llevan. Consulta
[MessageOrigin](#messageorigin).

## AssistantMessage

```java
record AssistantMessage(
    List<ContentBlock> content,                   // List of content blocks
    String model,                                 // Model that generated the response
    @Nullable String parentToolUseId,             // Set when inside a subagent tool use
    @Nullable AssistantMessageError error,        // Error information, if any
    @Nullable Map<String, Object> usage,          // Per-turn token usage (input_tokens, output_tokens, cache tokens, etc.)
    @Nullable String messageId,                   // Unique message ID from the API (e.g. "msg_01HRq...")
    @Nullable String stopReason,                  // Reason the model stopped (e.g. "end_turn")
    @Nullable String sessionId,                   // Session ID this message belongs to
    @Nullable String uuid                         // Unique identifier in the session transcript
) implements Message {
    String type();              // Returns "assistant"
    String getTextContent();    // Concatenates text from all TextBlock instances
    boolean hasToolUse();       // True if message contains at least one ToolUseBlock
}
```

También hay constructores retrocompatibles para el código que no necesita los campos más nuevos:

```java
new AssistantMessage(content, model, parentToolUseId, error)  // usage and all later fields default to null
new AssistantMessage(content, model, parentToolUseId, error, usage)  // messageId and later fields default to null
```

### AssistantMessageError

```java
enum AssistantMessageError {
    AUTHENTICATION_FAILED,  // "authentication_failed"
    BILLING_ERROR,          // "billing_error"
    RATE_LIMIT,             // "rate_limit"
    INVALID_REQUEST,        // "invalid_request"
    SERVER_ERROR,           // "server_error"
    UNKNOWN;                // "unknown" (also used for unrecognized error values)

    String getValue();      // Returns the JSON string value
    static AssistantMessageError fromValue(String value); // Parse from string
}
```

## SystemMessage

```java
record SystemMessage(
    String subtype,                 // Message subtype (e.g., "init")
    Map<String, Object> data        // Full raw message data
) implements Message {
    String type();  // Returns "system"
}
```

## ResultMessage

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
    @Nullable Object structuredOutput,            // Structured output if json_schema specified
    @Nullable Map<String, ModelUsage> modelUsage,  // Per-model usage breakdown
    @Nullable List<Object> permissionDenials,     // Permission denials during session
    @Nullable DeferredToolUse deferredToolUse,    // Tool call deferred by a PreToolUse "defer" decision
    @Nullable List<String> errors,                // Error messages from the CLI
    @Nullable Integer apiErrorStatus,             // HTTP status of failing API call when isError=true and subtype="success"
    @Nullable String uuid,                        // Unique message identifier in session
    @Nullable String terminalReason,              // Why the query loop terminated
    @Nullable MessageOrigin origin                // Origin of the triggering user message
) implements Message {
    String type();  // Returns "result"
}
```

Cuando una ejecución termina en un resultado de error terminal, el CLI también sale con un código
distinto de cero, y el SDK lo reporta como una
[`ResultException`](./api-exceptions.md#resultexception) con la misma carga útil que este mensaje;
consulta esa página para ver qué superficies de API la exponen y cuáles no.

**Nota de análisis sobre `errors`:** el CLI envía aquí una lista de cadenas. Una cadena suelta se
tolera y se conserva como lista de un elemento, y cualquier otra forma se ignora en lugar de rechazar
todo el marco de resultado; el SDK de Python no hace ninguna comprobación de tipo en este campo, así
que un valor mal formado no debe costarle a quien llama el resultado entero.

También hay constructores retrocompatibles para el código que no necesita los campos más nuevos. El
constructor original de 11 parámetros (sin `modelUsage`, `permissionDenials`, `deferredToolUse`,
`errors`, `apiErrorStatus`, `uuid`, `terminalReason` ni `origin`) sigue funcionando; también hay
sobrecargas de 15 parámetros (sin `deferredToolUse`, `apiErrorStatus`, `terminalReason`, `origin`),
17 parámetros (sin `terminalReason`, `origin`) y 18 parámetros (sin `origin`) para quien escribió
contra formas anteriores.

### terminalReason

Por qué terminó el bucle de la consulta. Entre los valores observados del CLI están `"completed"`,
`"max_turns"`, `"aborted_streaming"` y `"aborted_tools"`.

`"aborted_streaming"` y `"aborted_tools"` significan que el turno se canceló —con
`ClaudeSDKClient.interrupt()` o con una petición de control `interrupt`—, lo que da a quien llama un
marcador explícito de cancelación sin necesidad de un subtipo de resultado aparte:

```java
ResultMessage result = ClaudeSDK.queryForResult("Long task", options);
if ("aborted_streaming".equals(result.terminalReason())
        || "aborted_tools".equals(result.terminalReason())) {
    System.out.println("Turn was interrupted");
}
```

Es `null` cuando el CLI no informó de un motivo terminal: versiones antiguas del CLI, o un resultado
que se saltó el bucle de la consulta, como un comando de barra local. Refleja el
`SDKResultMessage.terminal_reason` del SDK de TypeScript.

### ModelUsage

Desglose de tokens y coste por modelo, indexado por la cadena del modelo en
`ResultMessage.modelUsage()`.

```java
record ModelUsage(
    long inputTokens,                 // Tokens sent to the model
    long outputTokens,                // Tokens generated by the model
    long cacheReadInputTokens,        // Tokens read from the prompt cache
    long cacheCreationInputTokens,    // Tokens written to the prompt cache
    long webSearchRequests,           // Server-side web search requests
    double costUsd,                   // Cost attributed to this model, in USD
    long contextWindow,               // Model's context window size in tokens
    long maxOutputTokens,             // Model's maximum output length in tokens
    @Nullable String canonicalModel,  // Canonical model id used for pricing lookup
    @Nullable String provider,        // API provider that served this model
    @Nullable Map<String, Object> raw // Verbatim CLI map, including unmodelled fields
)
```

**Nomenclatura JSON:** de forma inusual para datos que llegan del CLI, este tipo usa claves JSON en
camelCase. El CLI pasa el valor de `modelUsage` tal cual, así que sus claves coinciden con la forma de
`ModelUsage` del SDK de TypeScript y no con el snake_case que se usa en el resto de `ResultMessage`.
El accesor Java es `costUsd()`; la clave JSON es `costUSD`.

`canonicalModel` da una clave estable para consultar tablas de tarifas del lado del cliente a través
de ids y alias propios de cada proveedor (un ARN de Bedrock se corresponde con `claude-opus-4-7`), de
modo que las desviaciones de coste se detecten sin analizar la cadena del modelo en bruto. `provider`
es uno de `firstParty`, `bedrock`, `vertex`, `foundry`, `anthropicAws`, `anthropicGoogleCloud`,
`mantle`, `gateway`. Ambos son `null` en las versiones del CLI que no los emiten.

```java
ResultMessage result = ClaudeSDK.queryForResult("Analyze this", options);
if (result.modelUsage() != null) {
    result.modelUsage().forEach((model, usage) ->
        System.out.printf("%s: %d in / %d out, $%.4f%n",
            model, usage.inputTokens(), usage.outputTokens(), usage.costUsd()));
}
```

El análisis es tolerante por diseño: un contador que el CLI no emitió se lee como `0` en lugar de
hacer fallar el marco, y una entrada cuyo valor no es un objeto se omite en vez de rechazar todo el
mensaje de resultado. `raw()` conserva el mapa tal cual, así que los campos que añada un CLI más nuevo
siguen siendo accesibles sin actualizar el SDK.

### DeferredToolUse

Una llamada a herramienta aplazada por un hook `PreToolUse` que devolvió
`permissionDecision: "defer"`. El CLI detiene la ejecución y expone aquí la llamada aplazada, para que
quien consume el SDK decida si reanuda.

```java
record DeferredToolUse(
    String id,                       // Unique identifier of the deferred tool call
    String name,                     // Tool name
    Map<String, Object> input        // Tool input arguments
)
```

## StreamEvent

```java
record StreamEvent(
    String uuid,                             // Unique event identifier
    String sessionId,                        // Session identifier
    Map<String, Object> event,               // Raw Anthropic API stream event data
    @Nullable String parentToolUseId         // Set when inside a subagent tool use
) implements Message {
    String type();              // Returns "stream_event"
    @Nullable String eventType(); // Returns event.get("type"), or null
}
```

## Bloques de contenido

### La interfaz ContentBlock

```java
sealed interface ContentBlock permits TextBlock,
    ThinkingBlock, ToolUseBlock, ToolResultBlock,
    ServerToolUseBlock, ServerToolResultBlock,
    ImageBlock, DocumentBlock, UnknownBlock
```

Todos los bloques exponen `type()`, la cadena discriminadora en bruto del CLI.

### TextBlock

```java
record TextBlock(String text) implements ContentBlock
```

### ThinkingBlock

```java
record ThinkingBlock(
    String thinking,   // Internal reasoning content
    String signature   // Cryptographic signature
) implements ContentBlock
```

### ToolUseBlock

```java
record ToolUseBlock(
    String id,                           // Tool use ID
    String name,                         // Tool name (e.g., "Bash", "Read")
    @Nullable Map<String, Object> input  // Tool input parameters
) implements ContentBlock
```

### ToolResultBlock

```java
record ToolResultBlock(
    String toolUseId,          // Corresponding ToolUseBlock.id
    @Nullable Object content,  // Result content
    @Nullable Boolean isError  // Whether this is an error result
) implements ContentBlock
```

### ServerToolUseBlock

Invocación de herramienta del lado del servidor (advisor, web_search, web_fetch, code_execution,
etc.). La API las ejecuta en nombre del modelo: quien llama nunca devuelve un resultado.

```java
record ServerToolUseBlock(
    String id,                  // Server tool use ID
    String name,                // ServerToolName value (raw String for forward compat)
    Map<String, Object> input   // Tool input parameters
) implements ContentBlock
```

Valores del enum `ServerToolName`: `ADVISOR`, `WEB_SEARCH`, `WEB_FETCH`, `CODE_EXECUTION`,
`BASH_CODE_EXECUTION`, `TEXT_EDITOR_CODE_EXECUTION`, `TOOL_SEARCH_TOOL_REGEX`,
`TOOL_SEARCH_TOOL_BM25`.

### ServerToolResultBlock

Bloque de resultado devuelto para una llamada a herramienta del lado del servidor. El CLI los emite
como bloques de contenido `advisor_tool_result`; `content` es opaco (los tipos de resultado del
advisor incluyen `advisor_result`, `advisor_redacted_result`, `advisor_tool_result_error`).

```java
record ServerToolResultBlock(
    String toolUseId,            // Matches the corresponding ServerToolUseBlock.id
    Map<String, Object> content  // Raw result content
) implements ContentBlock
```

### ImageBlock

Se emite cuando la herramienta `Read` renderiza una página de un PDF. El resultado de la herramienta
solo anuncia el número de páginas; el archivo llega en un mensaje de usuario *aparte*, con un bloque
`image` por página renderizada.

```java
record ImageBlock(
    Map<String, Object> source   // Raw source map, kept verbatim
) implements ContentBlock
```

`source` se deja deliberadamente como el mapa en bruto porque la API define las formas de fuente
`base64`, `url`, `file`, `text` y `content`, y puede añadir más. Tres accesores de conveniencia
cubren el caso base64, y cada uno devuelve `null` cuando la clave falta o no es una cadena:

| Método | Devuelve |
|---|---|
| `sourceType()` | `source["type"]`, por ejemplo `"base64"` |
| `mediaType()` | `source["media_type"]`, por ejemplo `"image/jpeg"` |
| `data()` | `source["data"]`, la carga en base64 |

### DocumentBlock

La otra forma que usa el CLI para ese mismo flujo de leer un PDF: un único bloque `document` con el
archivo entero, en lugar de una imagen por página. Ambas formas se han observado en el CLI 2.1.218
con el mismo archivo en ejecuciones distintas, así que las dos están modeladas.

```java
record DocumentBlock(
    Map<String, Object> source   // Raw source map, kept verbatim
) implements ContentBlock
```

Expone los mismos accesores `sourceType()` / `mediaType()` / `data()` que `ImageBlock`;
`mediaType()` suele ser `"application/pdf"`.

### UnknownBlock

Alternativa de compatibilidad futura para un tipo de bloque que esta versión del SDK no modela.
`MessageParser.parse()` ya devuelve `null` para un tipo de *mensaje* desconocido, de modo que un CLI
más nuevo no pueda tumbar a un SDK más antiguo; los bloques de contenido reciben el mismo trato en
vez de lanzar excepción.

```java
record UnknownBlock(
    String type,               // The unrecognised discriminator
    Map<String, Object> raw    // The block, preserved whole
) implements ContentBlock
```

El analizador registra una vez por tipo desconocido, en nivel `WARNING`, desde el logger
`in.vidyalai.claude.sdk.internal.MessageParser`. Antes de que esto existiera, un bloque no modelado
lanzaba `MessageParseException`, lo que mataba el hilo lector y descartaba todos los demás bloques del
mensaje, incluido el texto que el modelo ya había producido.

> **Nota:** `ContentBlock` está sellada. Un `switch` exhaustivo sobre ella en el código de quien llama
> deja de compilar cuando crece la cláusula `permits`. Trata `UnknownBlock` de forma explícita en vez
> de añadir una rama `default`, para que la próxima incorporación siga siendo un error de compilación
> y no un paso silencioso.

## MirrorErrorMessage

Fallo no fatal al añadir en el SessionStore. Aparece cuando se agota el presupuesto de reintentos del
batcher; la transcripción en disco local ya es duradera.

```java
record MirrorErrorMessage(
    String subtype,                    // always "mirror_error"
    Map<String, Object> data,          // raw payload
    @Nullable SessionKey key,          // store key the failed append targeted
    String error                       // failure description
) implements Message {
    String type();                      // returns "system"
}
```

## HookEventMessage

Evento del ciclo de vida de un hook. Solo se emite cuando se define `includeHookEvents(true)` en
`ClaudeAgentOptions`.

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

`HookEventMessage` es un miembro de primer nivel de la interfaz sellada (no encaja con
`instanceof SystemMessage`). En un `hook_response`, el mapa `data` lleva las claves `output`,
`exit_code` y `outcome`.

## RateLimitEvent

```java
record RateLimitEvent(
    RateLimitInfo rateLimitInfo,  // Detailed rate limit status information
    String uuid,                  // Unique identifier for this event
    String sessionId              // Session identifier
) implements Message {
    String type();  // Returns "rate_limit_event"
}
```

## ConversationResetMessage

Se emite cuando la conversación de la sesión se sustituye sin cerrar la conexión: tras `/clear`, o
tras cualquier otro flujo que descarte la transcripción a mitad de sesión.

```java
record ConversationResetMessage(
    String newConversationId,  // Opaque id for the fresh conversation
    String uuid,               // Unique identifier of this message
    String sessionId           // The session that was reset (the outgoing one)
) implements Message {
    String type();  // Returns "conversation_reset"
}
```

En el modo de entrada por streaming, una sola conexión transporta muchos turnos de usuario, y un
reinicio borra el historial de la conversación *y* pone a cero los totales acumulados que se informan
en los `ResultMessage` siguientes (por ejemplo, `totalCostUsd`). Si acumulas esos totales en una
sesión de larga duración, guarda una instantánea cuando llegue este mensaje.

`newConversationId` es una clave para que la interfaz cuelgue de ella una transcripción vacía (y
descarte cualquier título de sesión en caché). **No** es el `sessionId` de los mensajes siguientes:
ese hay que leerlo del próximo mensaje, que trae uno nuevo.

```java
case ConversationResetMessage reset -> {
    System.out.printf("session %s reset -> new conversation %s%n",
            reset.sessionId(), reset.newConversationId());
    snapshotTotals();   // subsequent results restart their counters at zero
}
```

Que falte cualquiera de los tres campos obligatorios provoca `MessageParseException`.

## MessageOrigin

Procedencia de un mensaje con rol de usuario y —en un `ResultMessage`— del mensaje que desencadenó ese
turno.

```java
record MessageOrigin(
    @Nullable MessageOriginKind kind,   // null when the CLI sent a kind this version doesn't model
    String kindValue,                   // verbatim wire value of `kind`, never null
    @Nullable String server,            // kind == CHANNEL: MCP server the message arrived on
    @Nullable String from,              // kind == PEER/OBSERVER: sender address (sender-asserted)
    @Nullable String name,              // kind == PEER: sender display name, CLI-normalized
    @Nullable String fromSession,       // kind == PEER: sender's host-openable session id
    @Nullable String senderTaskId,      // kind == PEER/OBSERVER: in-process subagent task id
    @Nullable String body,              // kind == PEER: decoded body, envelope stripped
    @Nullable Integer verifiedPeerPid,  // kind == PEER: kernel-verified pid of the connecting process
    @Nullable TaskNotificationOriginSubkind subkind,  // kind == TASK_NOTIFICATION
    Map<String, Object> raw             // full origin object from the CLI, verbatim
) {
    boolean isHuman();  // true when kind == MessageOriginKind.HUMAN
}
```

En el modo de entrada por streaming, una conexión intercala los turnos que envía tu aplicación con los
turnos que la sesión inyecta por su cuenta: notificaciones de tareas en segundo plano, prompts de
tareas programadas que se dispararon, mensajes de canales MCP, mensajes retransmitidos desde sesiones
pares. `origin` los distingue:

```java
MessageOrigin origin = result.origin();
if (origin == null || origin.isHuman()) {
    // a turn this application submitted
} else if (origin.kind() == MessageOriginKind.TASK_NOTIFICATION) {
    // follow-up turn driven by a background task
}
```

Un `origin` nulo significa que el CLI no atribuyó el mensaje. Los prompts enviados con
`ClaudeSDK.query()` o `ClaudeSDKClient.query(String)` llegan así, salvo que tú mismo marques
`"origin": {"kind": "human"}` en el mapa del mensaje mediante `ClaudeSDKClient.query(Iterator)`: desde
un host del SDK solo se acepta el tipo `human`, y hacerlo requiere Claude Code >= 2.1.210.

**Compatibilidad futura.** Un `kind` más nuevo de lo que este SDK modela deja `kind()` en nulo
mientras `kindValue()` sigue llevando la cadena del protocolo, y `isHuman()` es falso para él: trata
como «no humano» todo lo que no reconozcas. Lo mismo vale para `subkind()`. El objeto completo del CLI
se conserva en `raw()`, así que las claves que esta versión no modela siguen siendo accesibles.

`from`, `name` y `fromSession` los **afirma el remitente**. Úsalos para enrutar respuestas y para
mostrarlos, nunca como prueba de identidad.

### MessageOriginKind

```java
enum MessageOriginKind {
    HUMAN,              // "human"              — submitted by the application/user
    CHANNEL,            // "channel"            — arrived on an MCP channel
    PEER,               // "peer"               — relayed from a peer session or subagent
    TASK_NOTIFICATION,  // "task-notification"  — background task, or a fired scheduled prompt
    COORDINATOR,        // "coordinator"        — injected by a multi-session coordinator
    UNCLASSIFIED,       // "unclassified"       — CLI could not attribute the turn
    OBSERVER,           // "observer"           — from an attached observer
    AUTO_CONTINUATION,  // "auto-continuation"  — session continued its own prior work
    OBSERVER_ACTIVITY;  // "observer-activity"  — activity report from an observer

    String getValue();
    static MessageOriginKind fromValue(String value);  // throws IllegalArgumentException if unknown
}
```

### TaskNotificationOriginSubkind

Presente cuando `kind == TASK_NOTIFICATION`, ausente en las notificaciones corrientes de tareas en
segundo plano.

```java
enum TaskNotificationOriginSubkind {
    SCHEDULED_TRIGGER,   // "scheduled-trigger"  — the fired prompt of a scheduled task
    PEER_SEND_MESSAGE;   // "peer-send-message"  — sent from another of the user's sessions

    String getValue();
    static TaskNotificationOriginSubkind fromValue(String value);
}
```

## RateLimitInfo

```java
record RateLimitInfo(
    RateLimitStatus status,                      // Current rate limit status
    @Nullable Long resetsAt,                     // Unix timestamp when the rate limit window resets
    @Nullable RateLimitType rateLimitType,       // Which rate limit window applies
    @Nullable Double utilization,               // Fraction of the rate limit consumed (0.0–1.0)
    @Nullable RateLimitStatus overageStatus,    // Status of overage/pay-as-you-go usage
    @Nullable Long overageResetsAt,             // Unix timestamp when overage window resets
    @Nullable String overageDisabledReason,     // Why overage is unavailable if rejected
    @Nullable Map<String, Object> raw           // Full raw map from the CLI
)
```

## RateLimitStatus

```java
enum RateLimitStatus {
    ALLOWED,           // "allowed" — within rate limits
    ALLOWED_WARNING,   // "allowed_warning" — approaching the rate limit
    REJECTED;          // "rejected" — rate limit has been hit

    String getValue();                           // Returns the JSON string value
    static RateLimitStatus fromValue(String);    // Parse from string
}
```

## RateLimitType

```java
enum RateLimitType {
    FIVE_HOUR,         // "five_hour" — 5-hour rolling window
    SEVEN_DAY,         // "seven_day" — 7-day rolling window
    SEVEN_DAY_OPUS,    // "seven_day_opus" — 7-day Opus-specific window
    SEVEN_DAY_SONNET,  // "seven_day_sonnet" — 7-day Sonnet-specific window
    OVERAGE;           // "overage" — overage/pay-as-you-go limit

    String getValue();                        // Returns the JSON string value
    static RateLimitType fromValue(String);   // Parse from string
}
```

## Tipos de mensaje de tarea

### TaskStartedMessage

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
) implements Message {
    String type();  // Returns "system"
}
```

### TaskProgressMessage

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
) implements Message {
    String type();  // Returns "system"
}
```

### TaskNotificationMessage

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
) implements Message {
    String type();  // Returns "system"
}
```

### TaskNotificationStatus

```java
enum TaskNotificationStatus {
    COMPLETED,  // "completed"
    FAILED,     // "failed"
    STOPPED;    // "stopped"

    String getValue();
    static TaskNotificationStatus fromValue(String);
}
```

### TaskUpdatedMessage

Se emite en eventos `system`/`task_updated` a medida que una tarea en segundo plano recorre su ciclo
de vida. El estado terminal de una tarea a veces llega **solo** como un parche `task_updated`, sin un
`TaskNotificationMessage` que lo acompañe (por ejemplo, una tarea detenida con `TaskStop` informa aquí
de `status="killed"`). Se analiza de forma defensiva: un `patch` ausente o que no sea un mapa recae en
un mapa vacío, y un estado desconocido o ausente en `null`, de modo que un evento de ciclo de vida
nunca tumbe el análisis.

```java
record TaskUpdatedMessage(
    String subtype,                    // always "task_updated"
    Map<String, Object> data,          // raw message data
    String taskId,                     // unique task identifier ("" if absent)
    Map<String, Object> patch,         // changed fields (e.g. status, end_time); never null
    @Nullable TaskUpdatedStatus status,// patch.status, or null if absent/unknown
    @Nullable String sessionId,        // session identifier (may be null)
    @Nullable String uuid              // message UUID (may be null)
) implements Message {
    String type();        // Returns "system"
    boolean isTerminal(); // true if status is present and in TERMINAL_TASK_STATUSES

    // Statuses that mean the task has finished, spanning both lifecycle
    // vocabularies (task_notification's "stopped" and task_updated's "killed").
    static final Set<String> TERMINAL_TASK_STATUSES =
        Set.of("completed", "failed", "stopped", "killed");
}
```

### TaskUpdatedStatus

```java
enum TaskUpdatedStatus {
    PENDING,    // "pending"   (non-terminal)
    RUNNING,    // "running"   (non-terminal)
    PAUSED,     // "paused"    (non-terminal)
    COMPLETED,  // "completed" (terminal)
    FAILED,     // "failed"    (terminal)
    KILLED;     // "killed"    (terminal — raw form; task_notification maps it to "stopped")

    String getValue();
    static TaskUpdatedStatus fromValue(String);              // throws on unknown
    static @Nullable TaskUpdatedStatus fromValueOrNull(String); // null on unknown/null
}
```

### TaskUsage

```java
record TaskUsage(
    int totalTokens,  // total tokens used
    int toolUses,     // number of tool invocations
    int durationMs    // task duration in milliseconds
)
```

## Véase también
- [Guía de tipos de mensaje](./feature-message-types.md)
