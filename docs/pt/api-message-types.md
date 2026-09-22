# Referência da API de tipos de mensagem

Hierarquia de tipos das mensagens do Claude.

> **Sobre esta tradução**: a documentação em inglês é a única autoritativa. Esta tradução pode estar desatualizada em relação ao [original em inglês](../api-message-types.md); em caso de divergência, o inglês prevalece. Os blocos de código são mantidos idênticos ao original e não foram traduzidos.

## MessageParser

A classe `MessageParser` converte os mapas JSON brutos vindos do CLI em objetos `Message` tipados.

```java
public final class MessageParser {
    // Returns null for unrecognized message types (forward compatibility)
    @Nullable
    public static Message parse(Map<String, Object> data) throws MessageParseException;
}
```

Tipos de mensagem desconhecidos devolvem `null` em vez de lançar uma exceção, o que permite ao SDK
continuar compatível com versões mais novas do CLI que possam emitir novos tipos de mensagem.

## A interface Message

```java
sealed interface Message permits UserMessage, AssistantMessage,
    SystemMessage, TaskStartedMessage, TaskProgressMessage,
    TaskNotificationMessage, TaskUpdatedMessage, MirrorErrorMessage,
    HookEventMessage, ResultMessage, StreamEvent, RateLimitEvent,
    ConversationResetMessage {
    String type();
}
```

> **Nota de compatibilidade (v0.1.23).** `ConversationResetMessage` ampliou essa união. Como
> `Message` é selada, um `switch` exaustivo sem ramo `default` deixa de compilar até que um caso
> `ConversationResetMessage` seja adicionado. Prefira um ramo `default ->` se quiser absorver as
> adições futuras em silêncio.

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

Um construtor compatível de 4 parâmetros, sem `origin`, continua disponível. `origin` é preenchido em
turnos injetados (notificações de tarefa, mensagens de canal/peer) e nas mensagens de usuário que o
CLI reproduz; mensagens de resultado de ferramenta nunca o carregam. Veja
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

Também há construtores compatíveis para código que não precisa dos campos mais novos:

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

Quando uma execução termina em um resultado de erro terminal, o CLI também sai com código diferente
de zero, e o SDK reporta isso como uma
[`ResultException`](./api-exceptions.md#resultexception) com a mesma carga útil desta mensagem — veja
naquela página quais superfícies de API a expõem e quais não.

**Nota de parsing sobre `errors`:** o CLI envia aqui uma lista de strings. Uma string solta é
tolerada e mantida como lista de um elemento, e qualquer outro formato é ignorado em vez de rejeitar
todo o quadro de resultado; o SDK Python não faz verificação de tipo alguma nesse campo, então um
valor malformado não pode custar ao chamador o resultado inteiro.

Também há construtores compatíveis para código que não precisa dos campos mais novos. O construtor
original de 11 parâmetros (sem `modelUsage`, `permissionDenials`, `deferredToolUse`, `errors`,
`apiErrorStatus`, `uuid`, `terminalReason` e `origin`) continua funcionando; sobrecargas de 15
parâmetros (sem `deferredToolUse`, `apiErrorStatus`, `terminalReason`, `origin`), 17 parâmetros (sem
`terminalReason`, `origin`) e 18 parâmetros (sem `origin`) também estão disponíveis para quem escreveu
contra formatos anteriores.

### terminalReason

Por que o laço da consulta terminou. Valores observados do CLI incluem `"completed"`, `"max_turns"`,
`"aborted_streaming"` e `"aborted_tools"`.

`"aborted_streaming"` e `"aborted_tools"` significam que o turno foi cancelado — via
`ClaudeSDKClient.interrupt()` ou uma requisição de controle `interrupt` — dando a quem chama um marcador
explícito de cancelamento sem exigir um subtipo de resultado à parte:

```java
ResultMessage result = ClaudeSDK.queryForResult("Long task", options);
if ("aborted_streaming".equals(result.terminalReason())
        || "aborted_tools".equals(result.terminalReason())) {
    System.out.println("Turn was interrupted");
}
```

É `null` quando o CLI não reportou um motivo terminal — versões antigas do CLI, ou um resultado que
contornou o laço da consulta, como um comando de barra local. Espelha o
`SDKResultMessage.terminal_reason` do SDK TypeScript.

### ModelUsage

Detalhamento de tokens e custo por modelo, indexado pela string do modelo em
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

**Nomenclatura JSON:** de modo incomum para dados recebidos do CLI, este tipo usa chaves JSON em
camelCase. O CLI repassa o valor de `modelUsage` sem alterações, então suas chaves seguem o formato do
`ModelUsage` do SDK TypeScript, e não o snake_case usado em outras partes de `ResultMessage`. O
acessor Java é `costUsd()`; a chave JSON é `costUSD`.

`canonicalModel` dá uma chave estável para consultas a tabelas de preço do lado do cliente através de
ids e apelidos específicos de provedor (um ARN do Bedrock mapeia para `claude-opus-4-7`), de modo que
desvios de custo sejam detectáveis sem analisar a string bruta do modelo. `provider` é um de
`firstParty`, `bedrock`, `vertex`, `foundry`, `anthropicAws`, `anthropicGoogleCloud`, `mantle`,
`gateway`. Ambos são `null` em versões do CLI que não os emitem.

```java
ResultMessage result = ClaudeSDK.queryForResult("Analyze this", options);
if (result.modelUsage() != null) {
    result.modelUsage().forEach((model, usage) ->
        System.out.printf("%s: %d in / %d out, $%.4f%n",
            model, usage.inputTokens(), usage.outputTokens(), usage.costUsd()));
}
```

O parsing é tolerante por projeto: um contador que o CLI não emitiu é lido como `0` em vez de derrubar
o quadro, e uma entrada cujo valor não é um objeto é ignorada em vez de rejeitar toda a mensagem de
resultado. `raw()` mantém o mapa verbatim, de modo que campos adicionados por um CLI mais novo
continuem acessíveis sem atualizar o SDK.

### DeferredToolUse

Uma chamada de ferramenta adiada por um hook `PreToolUse` que devolveu
`permissionDecision: "defer"`. O CLI interrompe a execução e expõe aqui a chamada adiada, para que
quem consome o SDK decida se retoma.

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

## Blocos de conteúdo

### A interface ContentBlock

```java
sealed interface ContentBlock permits TextBlock,
    ThinkingBlock, ToolUseBlock, ToolResultBlock,
    ServerToolUseBlock, ServerToolResultBlock,
    ImageBlock, DocumentBlock, UnknownBlock
```

Todo bloco expõe `type()`, a string discriminadora bruta do CLI.

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

Invocação de ferramenta do lado do servidor (advisor, web_search, web_fetch, code_execution etc.). A
API as executa em nome do modelo — quem chama nunca devolve um resultado.

```java
record ServerToolUseBlock(
    String id,                  // Server tool use ID
    String name,                // ServerToolName value (raw String for forward compat)
    Map<String, Object> input   // Tool input parameters
) implements ContentBlock
```

Valores do enum `ServerToolName`: `ADVISOR`, `WEB_SEARCH`, `WEB_FETCH`, `CODE_EXECUTION`,
`BASH_CODE_EXECUTION`, `TEXT_EDITOR_CODE_EXECUTION`, `TOOL_SEARCH_TOOL_REGEX`,
`TOOL_SEARCH_TOOL_BM25`.

### ServerToolResultBlock

Bloco de resultado devolvido para uma chamada de ferramenta do lado do servidor. O CLI os emite como
blocos de conteúdo `advisor_tool_result`; `content` é opaco (os tipos de resultado do advisor incluem
`advisor_result`, `advisor_redacted_result`, `advisor_tool_result_error`).

```java
record ServerToolResultBlock(
    String toolUseId,            // Matches the corresponding ServerToolUseBlock.id
    Map<String, Object> content  // Raw result content
) implements ContentBlock
```

### ImageBlock

Emitido quando a ferramenta `Read` renderiza uma página de PDF. O resultado da ferramenta apenas
anuncia a contagem de páginas; o arquivo chega em uma mensagem de usuário *separada*, com um bloco
`image` por página renderizada.

```java
record ImageBlock(
    Map<String, Object> source   // Raw source map, kept verbatim
) implements ContentBlock
```

`source` é deixado deliberadamente como o mapa bruto porque a API define os formatos de fonte
`base64`, `url`, `file`, `text` e `content`, e pode acrescentar mais. Três acessores de conveniência
cobrem o caso base64, cada um devolvendo `null` quando a chave está ausente ou não é uma string:

| Método | Devolve |
|---|---|
| `sourceType()` | `source["type"]`, por exemplo `"base64"` |
| `mediaType()` | `source["media_type"]`, por exemplo `"image/jpeg"` |
| `data()` | `source["data"]`, a carga em base64 |

### DocumentBlock

O outro formato que o CLI usa para o mesmo fluxo de ler um PDF: um único bloco `document` com o
arquivo inteiro, em vez de uma imagem por página. Os dois formatos foram observados no CLI 2.1.218
para o mesmo arquivo em execuções diferentes, então os dois são modelados.

```java
record DocumentBlock(
    Map<String, Object> source   // Raw source map, kept verbatim
) implements ContentBlock
```

Expõe os mesmos acessores `sourceType()` / `mediaType()` / `data()` de `ImageBlock`; `mediaType()`
costuma ser `"application/pdf"`.

### UnknownBlock

Alternativa de compatibilidade futura para um tipo de bloco que esta versão do SDK não modela. O
`MessageParser.parse()` já devolve `null` para um tipo de *mensagem* desconhecido, de modo que um CLI
mais novo não derrube um SDK mais antigo; os blocos de conteúdo recebem o mesmo tratamento em vez de
lançar exceção.

```java
record UnknownBlock(
    String type,               // The unrecognised discriminator
    Map<String, Object> raw    // The block, preserved whole
) implements ContentBlock
```

O parser registra uma vez por tipo desconhecido, em `WARNING`, no logger
`in.vidyalai.claude.sdk.internal.MessageParser`. Antes disso, um bloco não modelado lançava
`MessageParseException`, o que matava a thread de leitura e descartava todos os outros blocos da
mensagem — inclusive texto que o modelo já havia produzido.

> **Nota:** `ContentBlock` é selada. Um `switch` exaustivo sobre ela no código de quem chama deixa de
> compilar quando a cláusula `permits` cresce. Trate `UnknownBlock` explicitamente em vez de adicionar
> um ramo `default`, para que a próxima adição continue sendo um erro de compilação e não um
> escoamento silencioso.

## MirrorErrorMessage

Falha não fatal de anexação no SessionStore. Aparece depois que o orçamento de novas tentativas do
batcher se esgota; a transcrição em disco local já é durável.

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

Evento de ciclo de vida de hook. Só é emitido quando `includeHookEvents(true)` está definido em
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

`HookEventMessage` é um membro de nível superior da interface selada (não casa com
`instanceof SystemMessage`). Em um `hook_response`, o mapa `data` carrega as chaves `output`,
`exit_code` e `outcome`.

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

Emitida quando a conversa da sessão é substituída sem encerrar a conexão — depois de `/clear`, ou de
qualquer outro fluxo que descarte a transcrição no meio da sessão.

```java
record ConversationResetMessage(
    String newConversationId,  // Opaque id for the fresh conversation
    String uuid,               // Unique identifier of this message
    String sessionId           // The session that was reset (the outgoing one)
) implements Message {
    String type();  // Returns "conversation_reset"
}
```

No modo de entrada por streaming, uma única conexão carrega muitos turnos de usuário, e uma
reinicialização limpa o histórico da conversa *e* zera os totais acumulados reportados nos
`ResultMessage` seguintes (o `totalCostUsd`, por exemplo). Se você acumula esses totais ao longo de uma
sessão de vida longa, tire um retrato deles quando esta mensagem chegar.

`newConversationId` é uma chave para a interface pendurar uma transcrição vazia (e descartar qualquer
título de sessão em cache). Ele **não** é o `sessionId` das mensagens seguintes — leia esse da próxima
mensagem, que traz um novo.

```java
case ConversationResetMessage reset -> {
    System.out.printf("session %s reset -> new conversation %s%n",
            reset.sessionId(), reset.newConversationId());
    snapshotTotals();   // subsequent results restart their counters at zero
}
```

A ausência de qualquer um dos três campos obrigatórios levanta `MessageParseException`.

## MessageOrigin

Procedência de uma mensagem com papel de usuário e — em um `ResultMessage` — da mensagem que
desencadeou aquele turno.

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

No modo de entrada por streaming, uma conexão intercala os turnos que sua aplicação envia com os
turnos que a sessão injeta por conta própria — notificações de tarefas em segundo plano, prompts de
tarefas agendadas que dispararam, mensagens de canais MCP, mensagens repassadas de sessões pares. O
`origin` os diferencia:

```java
MessageOrigin origin = result.origin();
if (origin == null || origin.isHuman()) {
    // a turn this application submitted
} else if (origin.kind() == MessageOriginKind.TASK_NOTIFICATION) {
    // follow-up turn driven by a background task
}
```

Um `origin` nulo significa que o CLI não atribuiu a mensagem. Prompts enviados por
`ClaudeSDK.query()` ou `ClaudeSDKClient.query(String)` chegam assim, a menos que você mesmo marque
`"origin": {"kind": "human"}` no mapa da mensagem via `ClaudeSDKClient.query(Iterator)` — apenas o
tipo `human` é aceito vindo de um host do SDK, e fazer isso exige Claude Code >= 2.1.210.

**Compatibilidade futura.** Um `kind` mais novo do que este SDK modela deixa `kind()` nulo enquanto
`kindValue()` ainda carrega a string do protocolo, e `isHuman()` é falso para ele — trate qualquer
coisa não reconhecida como "não humano". O mesmo vale para `subkind()`. O objeto completo do CLI é
mantido em `raw()`, então chaves que esta versão não modela continuam acessíveis.

`from`, `name` e `fromSession` são **afirmados pelo remetente**. Use-os para roteamento de resposta e
exibição, nunca como prova de identidade.

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

Presente quando `kind == TASK_NOTIFICATION`, ausente nas notificações comuns de tarefa em segundo
plano.

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

## Tipos de mensagem de tarefa

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

Emitida em eventos `system`/`task_updated` conforme uma tarefa em segundo plano avança pelo seu ciclo
de vida. O estado terminal de uma tarefa às vezes chega **apenas** como um patch `task_updated`, sem
um `TaskNotificationMessage` acompanhando (por exemplo, uma tarefa interrompida via `TaskStop`
reporta `status="killed"` aqui). É interpretada de forma defensiva — um `patch` ausente ou que não seja
um mapa recai em um mapa vazio, e um status desconhecido/ausente em `null`, de modo que um evento de
ciclo de vida nunca derruba o parsing.

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

## Veja também
- [Guia de tipos de mensagem](./feature-message-types.md)
