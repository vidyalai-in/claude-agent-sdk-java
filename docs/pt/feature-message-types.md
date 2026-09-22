# Tipos de mensagem

Entendendo o sistema de tipos de mensagem para processar conversas do Claude.

> **Sobre esta tradução**: a documentação em inglês é a única autoritativa. Esta tradução pode estar desatualizada em relação ao [original em inglês](../feature-message-types.md); em caso de divergência, o inglês prevalece. Os blocos de código são mantidos idênticos ao original e não são traduzidos.

## Sumário
- [Visão geral](#visão-geral)
- [Compatibilidade futura](#compatibilidade-futura)
- [Hierarquia dos tipos de mensagem](#hierarquia-dos-tipos-de-mensagem)
- [UserMessage](#usermessage)
- [AssistantMessage](#assistantmessage)
- [SystemMessage](#systemmessage)
- [Mensagens de tarefa](#mensagens-de-tarefa)
- [MirrorErrorMessage](#mirrorerrormessage)
- [HookEventMessage](#hookeventmessage)
- [ResultMessage](#resultmessage)
- [StreamEvent](#streamevent)
- [RateLimitEvent](#ratelimitevent)
- [Blocos de conteúdo](#blocos-de-conteúdo)
- [Pattern matching](#pattern-matching)
- [Exemplos](#exemplos)

## Visão geral

O SDK usa uma hierarquia de interfaces seladas para um tratamento de mensagens com segurança de tipos. Todas as mensagens implementam a interface selada `Message`, o que permite pattern matching exaustivo.

```java
sealed interface Message permits UserMessage, AssistantMessage,
    SystemMessage, TaskStartedMessage, TaskProgressMessage,
    TaskNotificationMessage, TaskUpdatedMessage, MirrorErrorMessage,
    HookEventMessage, ResultMessage, StreamEvent, RateLimitEvent {}
```

## Compatibilidade futura

O `MessageParser` foi projetado para ser compatível com versões mais novas do CLI. Quando o CLI emite um tipo de mensagem que o SDK não reconhece, o parser devolve `null` em vez de lançar uma exceção. O iterador de mensagens pula automaticamente as mensagens `null`, então seu código continua funcionando mesmo conectado a um CLI mais novo que emite tipos novos.

```java
// MessageParser.parse() returns @Nullable Message
// Unknown types return null and are silently skipped by the iterator
for (Message msg : ClaudeSDK.query(prompt)) {
    // Only known message types arrive here; unknown types are silently skipped
    switch (msg) { ... }
}
```

**Tipo desconhecido não é o mesmo que conteúdo malformado.** Devolver `null` vale para um *tipo de mensagem não reconhecido*. Um tipo *conhecido* (`user` / `assistant`) com estrutura inválida é outro caso — o parser levanta `MessageParseException` em vez de descartar em silêncio, para que uma violação real do protocolo não fique escondida. Desde a 0.1.18 isso é aplicado explicitamente: uma mensagem `assistant` cujo `content` não seja uma lista (por exemplo, uma string pura) levanta `"Invalid assistant content (expected list, got …)"`, e um elemento da lista `content` que não seja um objeto (tanto em mensagens `user` quanto `assistant`) levanta `"Invalid content block (expected dict, got …)"`. Antes esses casos apareciam como um `ClassCastException` cru; agora são reportados de forma consistente como `MessageParseException` (igual às demais validações estruturais do parser).

## Hierarquia dos tipos de mensagem

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

> **Nota de compatibilidade (v0.1.23).** `ConversationResetMessage` ampliou esta união
> selada, então um `switch` exaustivo sem ramo `default` deixa de compilar até que um
> case seja adicionado. Veja [Pattern matching](#pattern-matching).

## UserMessage

Representa mensagens do usuário. O conteúdo pode ser uma string simples ou uma lista de blocos de conteúdo estruturados (por exemplo, quando há resultados de ferramentas).

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

| Método | Descrição |
|--------|-------------|
| `type()` | Devolve `"user"` |
| `contentAsString()` | Devolve o conteúdo como String, ou null se for estruturado |
| `contentAsBlocks()` | Devolve o conteúdo como `List<ContentBlock>`, ou null se for string |

### Exemplo

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

Representa mensagens do Claude, contendo um ou mais blocos de conteúdo.

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

O mapa `usage` traz, quando disponível, os dados de consumo de tokens da API por turno, incluindo chaves como `input_tokens`, `output_tokens` e campos relacionados a cache. Os campos `messageId`, `stopReason`, `sessionId` e `uuid` guardam identificadores no nível da API. Eles aceitam null e estão ausentes em mensagens parciais/de streaming. Também existem construtores retrocompatíveis sem os campos mais recentes.

### Métodos

| Método | Descrição |
|--------|-------------|
| `type()` | Devolve `"assistant"` |
| `getTextContent()` | Concatena o texto de todos os blocos `TextBlock` |
| `hasToolUse()` | Devolve `true` se houver ao menos um `ToolUseBlock` |

### Valores de AssistantMessageError

| Constante do enum | Valor string | Descrição |
|---------------|-------------|-------------|
| `AUTHENTICATION_FAILED` | `"authentication_failed"` | Chave de API inválida ou ausente |
| `BILLING_ERROR` | `"billing_error"` | Problema de cobrança |
| `RATE_LIMIT` | `"rate_limit"` | Limite de requisições excedido |
| `INVALID_REQUEST` | `"invalid_request"` | Requisição malformada |
| `SERVER_ERROR` | `"server_error"` | Erro interno do servidor |
| `UNKNOWN` | `"unknown"` | Erro desconhecido ou não reconhecido |

### Exemplo

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

Notificações e eventos de nível de sistema vindos do CLI.

### Campos

```java
record SystemMessage(
    String subtype,           // Message subtype (e.g., "init")
    Map<String, Object> data  // Full raw message data
) implements Message
```

### Exemplo

```java
if (msg instanceof SystemMessage system) {
    System.out.println("System event: " + system.subtype());
    System.out.println("Data: " + system.data());
}
```

## Mensagens de tarefa

Mensagens de tarefa são subtipos tipados de mensagens de sistema emitidos durante os eventos do ciclo de vida das tarefas de subagentes. Elas implementam `Message` diretamente e `type()` devolve `"system"`. Verificações `instanceof SystemMessage` já existentes **não** correspondem a elas — use o tipo específico.

### TaskStartedMessage

Emitida quando uma tarefa (subagente) começa.

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

| Método | Descrição |
|--------|-------------|
| `type()` | Devolve `"system"` |
| `get(String key)` | Obtém um valor do mapa de dados bruto |

### TaskProgressMessage

Emitida periodicamente enquanto uma tarefa está em execução.

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

| Método | Descrição |
|--------|-------------|
| `type()` | Devolve `"system"` |
| `get(String key)` | Obtém um valor do mapa de dados bruto |

### TaskNotificationMessage

Emitida quando uma tarefa termina, falha ou é interrompida.

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

Emitida em eventos `system`/`task_updated` conforme uma tarefa em segundo plano avança pelo seu ciclo de vida. `patch` traz os campos que mudaram (por exemplo, `status`, `end_time`).

O estado terminal de uma tarefa às vezes chega **apenas** como um `TaskUpdatedMessage`, sem o `TaskNotificationMessage` correspondente — por exemplo, uma tarefa interrompida via `TaskStop` reporta `status="killed"` aqui, e a notificação equivalente às vezes é suprimida. Quem acompanha IDs de tarefas ativas deve limpá-los ao ver um status terminal em **qualquer uma** das mensagens.

A análise é defensiva — um `patch` ausente ou que não seja um mapa vira um mapa vazio, e um status desconhecido/ausente vira `null`, de modo que um evento de ciclo de vida nunca quebra a análise.

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

| Método | Descrição |
|--------|-------------|
| `type()` | Devolve `"system"` |
| `isTerminal()` | `true` se `status` existir e estiver em `TERMINAL_TASK_STATUSES` |
| `get(String key)` | Obtém um valor do mapa de dados bruto |
| `TaskUpdatedMessage.TERMINAL_TASK_STATUSES` | `Set.of("completed", "failed", "stopped", "killed")` — status terminais abrangendo os dois vocabulários de ciclo de vida |

### TaskUpdatedStatus

`task_updated` reporta o `KILLED` bruto; o CLI só o mapeia para `STOPPED` (`TaskNotificationStatus`) quando emite um `task_notification`.

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

`TaskUpdatedStatus.fromValueOrNull(String)` resolve um status bruto sem lançar exceção com um valor desconhecido ou `null` (usado na análise defensiva); `fromValue(String)` lança com um valor desconhecido.

### TaskUsage

Estatísticas de uso de uma tarefa:

```java
record TaskUsage(
    int totalTokens,   // total tokens used
    int toolUses,      // number of tool invocations
    int durationMs     // task duration in milliseconds
)
```

### Exemplo: tratando mensagens de tarefa

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

Mensagem de sistema não fatal emitida quando uma chamada a `SessionStore.append()` falha depois de esgotadas as tentativas (`MIRROR_APPEND_MAX_ATTEMPTS=3`). A transcrição em disco local já está durável, então a sessão segue sem impacto — apenas a cópia espelhada no store externo ficará sem o lote que falhou.

```java
record MirrorErrorMessage(
    String subtype,                    // always "mirror_error"
    Map<String, Object> data,          // raw payload
    @Nullable SessionKey key,          // store key the failed append targeted (null if pre-resolution)
    String error                       // failure description
) implements Message
```

Modelada como um membro de topo da interface selada `Message` (records Java não podem estender records). `subtype` é sempre `"mirror_error"`; o campo base `data` carrega o payload bruto para downcasts no estilo `SystemMessage`.

### Exemplo: recuperando-se de um erro de espelhamento

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

Veja [Session Store](./feature-session-store.md) para o fluxo completo de espelhamento e a semântica de retentativas.

## HookEventMessage

Evento de ciclo de vida de hook exposto no fluxo de mensagens. Só é emitido quando `includeHookEvents(true)` está definido em `ClaudeAgentOptions`. Veja [Hooks → Eventos de ciclo de vida dos hooks no fluxo](./feature-hooks.md#eventos-de-ciclo-de-vida-dos-hooks-no-fluxo).

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

`type()` devolve `"system"` por simetria com `SystemMessage`, mas `HookEventMessage` é um membro separado da interface selada — `instanceof SystemMessage` **não** corresponde. Faça o branch diretamente em `HookEventMessage`.

Valores de `subtype`:

- `"hook_started"` — quando um hook começa a executar.
- `"hook_response"` — quando um hook termina; `data` inclui as chaves `output`, `exit_code` e `outcome`.

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

Resultado final enviado ao fim de cada turno da conversa, com informações de tempo, custo e uso.

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

O campo `errors` contém uma lista de mensagens de erro do CLI, útil para diagnosticar códigos de saída diferentes de zero. O campo `modelUsage` fornece o detalhamento de tokens por modelo, tipado como `ModelUsage` — veja [Referência da API → ModelUsage](./api-message-types.md#modelusage). O campo `deferredToolUse` é preenchido quando um hook `PreToolUse` devolveu `permissionDecision: "defer"` — veja [Hooks → Decisão de permissão: `"defer"`](./feature-hooks.md#decisão-de-permissão-defer). O campo `apiErrorStatus` traz o código HTTP (por exemplo, `429`, `500`, `529`) da chamada de API que falhou quando `isError=true` e `subtype="success"` (a API falhou, mas a própria sessão terminou); é seguro registrá-lo em log, pois não carrega conteúdo de mensagem. Também existem construtores retrocompatíveis sem os campos mais recentes.

O campo `terminalReason` informa por que o laço da consulta terminou — `"completed"`, `"max_turns"`, `"aborted_streaming"`, `"aborted_tools"`. Os dois valores `aborted_*` significam que o turno foi cancelado via `ClaudeSDKClient.interrupt()`, o que dá a quem chama um marcador explícito de cancelamento sem um subtipo de resultado à parte:

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

É `null` quando o CLI não reportou um motivo terminal — versões antigas do CLI, ou um resultado que contornou o laço da consulta, como um comando de barra local.

### DeferredToolUse

```java
record DeferredToolUse(
    String id,                       // unique identifier of the deferred tool call
    String name,                     // tool name
    Map<String, Object> input        // tool input arguments
)
```

### Subtipos comuns

- `"success"` — a conversa terminou com sucesso
- `"error_max_budget_usd"` — limite de orçamento atingido
- `"error_max_turns"` — limite de turnos atingido

### Exemplo

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

Atualizações parciais de mensagem durante o streaming. Só é emitido quando `includePartialMessages` está habilitado.

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

| Método | Descrição |
|--------|-------------|
| `type()` | Devolve `"stream_event"` |
| `eventType()` | Devolve o tipo do evento a partir do mapa interno, ou null |

### Tipos de evento comuns (de `event.get("type")`)

- `"content_block_start"` — novo bloco de conteúdo iniciado
- `"content_block_delta"` — atualização incremental de conteúdo
- `"content_block_stop"` — bloco de conteúdo concluído
- `"message_start"` — mensagem iniciada
- `"message_delta"` — atualização da mensagem
- `"message_stop"` — mensagem concluída

### Exemplo

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

Emitido pelo CLI sempre que o status de limite de requisições muda. Use-o para avisar as pessoas antes de baterem num limite rígido, ou para recuar de forma elegante quando o limite for excedido.

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

| Constante do enum | Valor string | Descrição |
|---------------|-------------|-------------|
| `ALLOWED` | `"allowed"` | Dentro dos limites, nada a fazer |
| `ALLOWED_WARNING` | `"allowed_warning"` | Aproximando-se do limite — avise o usuário |
| `REJECTED` | `"rejected"` | Limite atingido — as requisições serão recusadas |

### RateLimitType

| Constante do enum | Valor string | Descrição |
|---------------|-------------|-------------|
| `FIVE_HOUR` | `"five_hour"` | Janela móvel de 5 horas |
| `SEVEN_DAY` | `"seven_day"` | Janela móvel de 7 dias |
| `SEVEN_DAY_OPUS` | `"seven_day_opus"` | Janela móvel de 7 dias para modelos Opus |
| `SEVEN_DAY_SONNET` | `"seven_day_sonnet"` | Janela móvel de 7 dias para modelos Sonnet |
| `OVERAGE` | `"overage"` | Limite de excedente / pagamento por uso |

### Exemplo

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

Emitida quando a conversa da sessão é substituída sem encerrar a conexão — depois de `/clear`, ou de qualquer outro fluxo que descarte a transcrição no meio da sessão. Antes da v0.1.23 o parser descartava este quadro em silêncio, então as aplicações nunca viam resets, inclusive os que não haviam iniciado.

### Campos

```java
record ConversationResetMessage(
    String newConversationId,  // Opaque id for the fresh conversation
    String uuid,               // Unique identifier of this message
    String sessionId           // The session that was reset (the outgoing one)
) implements Message
```

### Por que isso importa

Um reset limpa o histórico da conversa *e* zera os totais acumulados reportados nos `ResultMessage` seguintes. Se você acumula custo ou uso de tokens ao longo de uma sessão de streaming de vida longa, este quadro é o seu único sinal para tirar uma foto deles antes que recomecem do zero.

Ele também marca uma fronteira de session id: `newConversationId` é uma chave para a UI pendurar uma transcrição vazia, e **não** o `sessionId` do que vem depois. As mensagens após o reset trazem um novo `sessionId` — leia-o na mensagem seguinte.

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

## Origem da mensagem

`UserMessage.origin()` e `ResultMessage.origin()` expõem *por que* um turno foi iniciado. No modo de entrada por streaming, uma conexão intercala os turnos que a sua aplicação envia com turnos que a própria sessão injeta — notificações de tarefas em segundo plano, prompts de tarefas agendadas que dispararam, mensagens de canal MCP, mensagens repassadas de sessões pares. Em um `ResultMessage`, o campo reporta a origem da mensagem que *disparou* aquele turno, e é isso que permite distinguir "isto responde ao meu prompt" de "isto responde a uma tarefa em segundo plano".

```java
MessageOrigin origin = result.origin();
if (origin == null || origin.isHuman()) {
    // a turn this application submitted
} else if (origin.kind() == MessageOriginKind.TASK_NOTIFICATION) {
    // follow-up turn driven by a background task
    render(origin.subkind());   // scheduled-trigger / peer-send-message, or null
}
```

`origin` é null quando o CLI não atribuiu a mensagem — o que é o caso normal para os prompts que você envia. Para que os seus próprios turnos sejam atribuídos, carimbe você mesmo o mapa da mensagem e envie-a por `ClaudeSDKClient.query(Iterator)`:

```java
Map<String, Object> message = new HashMap<>();
message.put("type", "user");
message.put("message", Map.of("role", "user", "content", prompt));
message.put("parent_tool_use_id", null);
message.put("origin", Map.of("kind", "human"));

client.query(List.of(message).iterator());
```

Apenas o kind `human` é aceito vindo de um host do SDK, e ele exige Claude Code >= 2.1.210. Mensagens de usuário com resultados de ferramenta nunca carregam origem.

Kinds não reconhecidos permanecem visíveis em vez de virarem erros: `kind()` é null enquanto `kindValue()` guarda a string do protocolo, e `isHuman()` é false — ou seja, uma atribuição desconhecida sempre se lê como "não humana". O objeto completo do CLI fica em `raw()`. Referência campo a campo: [MessageOrigin](./api-message-types.md#messageorigin).

Campos como `from`, `name` e `fromSession` são **afirmados pelo remetente** — use-os para roteamento de respostas e exibição, nunca como prova de identidade.

## Blocos de conteúdo

Mensagens do assistente (e mensagens de usuário estruturadas) contêm blocos de conteúdo.

### Hierarquia de ContentBlock

```java
sealed interface ContentBlock permits TextBlock, ThinkingBlock,
    ToolUseBlock, ToolResultBlock,
    ServerToolUseBlock, ServerToolResultBlock,
    ImageBlock, DocumentBlock, UnknownBlock {}
```

Como a interface é selada, um `switch` exaustivo sobre ela deixa de compilar sempre que a cláusula `permits` cresce. Prefira tratar `UnknownBlock` explicitamente em vez de adicionar um ramo `default` — assim o próximo tipo de bloco novo vira um erro de compilação sobre o qual você pode agir, e não uma passagem silenciosa.

### TextBlock

Conteúdo de texto simples vindo do Claude.

```java
record TextBlock(
    String text  // Text content
) implements ContentBlock
```

### ThinkingBlock

O raciocínio interno do Claude quando o raciocínio estendido está habilitado. Inclui uma assinatura criptográfica.

```java
record ThinkingBlock(
    String thinking,   // Thinking content
    String signature   // Cryptographic signature for the thinking block
) implements ContentBlock
```

### ToolUseBlock

Invocação de ferramenta pelo Claude.

```java
record ToolUseBlock(
    String id,                          // Tool use ID (matches ToolResultBlock.toolUseId)
    String name,                        // Tool name (e.g., "Bash", "Read")
    @Nullable Map<String, Object> input // Tool input parameters
) implements ContentBlock
```

### ToolResultBlock

Resultados da execução de ferramentas.

```java
record ToolResultBlock(
    String toolUseId,          // Corresponding ToolUseBlock.id
    @Nullable Object content,  // Result content (String or structured)
    @Nullable Boolean isError  // Whether this is an error result
) implements ContentBlock
```

### ServerToolUseBlock

Invocação de ferramenta no lado do servidor que a API executa em nome do modelo — quem chama nunca devolve um resultado. Usado para `advisor`, `web_search`, `web_fetch`, `code_execution`, `bash_code_execution`, `text_editor_code_execution`, `tool_search_tool_regex` e `tool_search_tool_bm25`.

```java
record ServerToolUseBlock(
    String id,                  // Server tool use ID
    String name,                // One of ServerToolName values (kept as raw String for forward compat)
    Map<String, Object> input   // Tool input parameters
) implements ContentBlock
```

O campo `name` é um discriminador — faça o branch nele para saber qual ferramenta de servidor foi invocada. O enum `ServerToolName` lista os valores reconhecidos:

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

Bloco de resultado devolvido para uma chamada de ferramenta do lado do servidor. Espelha o formato de `ToolResultBlock`; `content` é o mapa bruto da API, opaco para esta camada — quem se importa com o esquema de resultado de uma ferramenta específica pode inspecionar `content.get("type")`.

O CLI emite esses blocos como conteúdo `advisor_tool_result` (o único tipo de resultado de ferramenta de servidor disponível hoje); eles são convertidos em `ServerToolResultBlock`.

```java
record ServerToolResultBlock(
    String toolUseId,            // Matches the corresponding ServerToolUseBlock.id
    Map<String, Object> content  // Raw result content (e.g. {"type":"advisor_result", ...})
) implements ContentBlock
```

No caso específico da ferramenta advisor, o campo `type` do mapa `content` será um de:
- `"advisor_result"` — resultado em texto, com um campo `text`
- `"advisor_redacted_result"` — blob criptografado, com um campo `encrypted_content`
- `"advisor_tool_result_error"` — payload de erro

### ImageBlock e DocumentBlock

Ambos chegam pelo mesmo fluxo: ler um PDF com a ferramenta `Read`. O `tool_result` em si só anuncia a contagem de páginas, e o arquivo vem em uma **mensagem de usuário separada**. O CLI usa um de dois formatos para essa mensagem, e ambos foram observados no CLI 2.1.218 com o mesmo arquivo de 1,5 MB em execuções diferentes:

- um bloco `image` por página renderizada (`image/jpeg`, base64), ou
- um único bloco `document` com o PDF inteiro (`application/pdf`, base64).

```java
record ImageBlock(Map<String, Object> source) implements ContentBlock
record DocumentBlock(Map<String, Object> source) implements ContentBlock
```

`source` é mantido como mapa bruto porque a API define os formatos de origem `base64`, `url`, `file`, `text` e `content`, e pode acrescentar outros. Três acessores cobrem o caso base64, cada um devolvendo `null` se a chave estiver ausente ou não for uma string:

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

| Método | Devolve |
|---|---|
| `sourceType()` | `source["type"]`, por exemplo `"base64"` |
| `mediaType()` | `source["media_type"]`, por exemplo `"image/jpeg"` ou `"application/pdf"` |
| `data()` | `source["data"]`, o payload em base64 |

> **Ler PDFs exige aumentar o `maxBufferSize`.** Estes blocos são base64 — quatro bytes para cada três — em uma única linha da saída padrão do CLI, então um PDF de 1,5 MB vira uma linha de aproximadamente 2,1 MB contra o padrão de 1 MB. O padrão não mudou (ele acompanha o SDK Python), então quem for ler arquivos de qualquer tamanho precisa defini-lo explicitamente:
>
> ```java
> ClaudeAgentOptions options = ClaudeAgentOptions.builder()
>     .allowedTools(List.of("Read"))
>     .maxBufferSize(16 * 1024 * 1024)
>     .build();
> ```
>
> Sem isso, qualquer agente com `Read` sobre um diretório de PDFs falha no meio da execução.

### UnknownBlock

Compatibilidade futura para tipos de bloco que esta versão do SDK não modela.

```java
record UnknownBlock(
    String type,             // The unrecognised discriminator
    Map<String, Object> raw  // The block, preserved whole
) implements ContentBlock
```

O `MessageParser` já devolvia `null` para um *tipo de mensagem* não reconhecido, de modo que um CLI mais novo não derruba um SDK mais antigo; agora os blocos de conteúdo se comportam da mesma forma em vez de lançar exceção. O bloco não reconhecido é preservado inteiro e registrado uma vez por tipo em `WARNING` por `in.vidyalai.claude.sdk.internal.MessageParser`.

É exatamente disso que `image` e `document` precisavam e não tinham. O comportamento anterior lançava `MessageParseException`, o que matava a thread de leitura, descartava todos os outros blocos da mensagem — inclusive texto que o modelo já havia produzido — e aparecia como uma falha de decodificação JSON citando um tipo que quem chamou nunca pediu.

## Pattern matching

O pattern matching do Java torna o tratamento de mensagens elegante e seguro quanto a tipos.

### Expressão switch

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

Como `Message` é selada, um `switch` como este — sem `default` — precisa listar todos os tipos permitidos, e o compilador exige isso. Esse é justamente o ponto: quando um novo tipo de mensagem é adicionado, você recebe um erro de compilação em cada switch exaustivo, em vez de descartar quadros silenciosamente em tempo de execução.

O custo é que adicionar um tipo passa a ser uma mudança que quebra o código-fonte. `ConversationResetMessage` fez exatamente isso na v0.1.23. Se você preferir absorver adições futuras em silêncio, acrescente um ramo `default`:

```java
String result = switch (message) {
    case AssistantMessage a -> "Claude: " + a.getTextContent();
    case ResultMessage r -> "Cost: $" + r.totalCostUsd();
    default -> "(other)";        // future message types land here
};
```

### Pattern matching aninhado

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

### instanceof com variáveis de padrão

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

## Exemplos

### Exemplo 1: processando todas as mensagens

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

### Exemplo 2: extraindo informações específicas

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

### Exemplo 3: tratando erros

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

### Exemplo 4: acompanhando o uso de ferramentas

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

### Exemplo 5: análise de custo e tokens

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

### Exemplo 6: filtrando mensagens de subagentes

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

## Veja também

- [Consultas simples](./feature-simple-queries.md) — processando mensagens de consultas
- [Conversas interativas](./feature-interactive-conversations.md) — processando mensagens do cliente
- [Eventos de streaming](./feature-streaming-events.md) — detalhes do StreamEvent
- [Referência da API: tipos de mensagem](./api-message-types.md) — documentação completa da API
