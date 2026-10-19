# Sistema de hooks

Intercepte e responda a eventos do ciclo de vida das conversas do Claude.

> **Sobre esta tradução**: a documentação em inglês é a única autoritativa. Esta tradução pode estar desatualizada em relação ao [original em inglês](../feature-hooks.md); em caso de divergência, o inglês prevalece. Os blocos de código são mantidos idênticos ao original e não foram traduzidos.

## Visão geral

Os hooks executam o seu código em pontos específicos do loop do agente. Você registra um callback
para um `HookEvent`; quando o CLI chega a esse ponto, ele envia ao SDK uma requisição de hook, o SDK
chama o seu callback com um `HookInput` tipado, e o callback responde com um `HookOutput` que pode
permitir, bloquear ou acrescentar contexto.

São 10 os eventos de hook para os quais você pode registrar callbacks.

## Eventos de hook

`HookEvent` (pacote `in.vidyalai.claude.sdk.types.hook`):

| Constante | Nome do evento | Quando dispara | Tipo de entrada |
|----------|------------|---------------|------------|
| `PRE_TOOL_USE` | `PreToolUse` | Antes de uma ferramenta ser executada | `PreToolUseHookInput` |
| `POST_TOOL_USE` | `PostToolUse` | Depois que uma ferramenta é executada com sucesso | `PostToolUseHookInput` |
| `POST_TOOL_USE_FAILURE` | `PostToolUseFailure` | Depois que uma ferramenta falha | `PostToolUseFailureHookInput` |
| `USER_PROMPT_SUBMIT` | `UserPromptSubmit` | Quando um prompt é enviado | `UserPromptSubmitHookInput` |
| `STOP` | `Stop` | Quando a sessão está prestes a parar | `StopHookInput` |
| `SUBAGENT_START` | `SubagentStart` | Quando um subagente inicia | `SubagentStartHookInput` |
| `SUBAGENT_STOP` | `SubagentStop` | Quando um subagente para | `SubagentStopHookInput` |
| `PRE_COMPACT` | `PreCompact` | Antes da compactação do contexto | `PreCompactHookInput` |
| `NOTIFICATION` | `Notification` | Em uma notificação | `NotificationHookInput` |
| `PERMISSION_REQUEST` | `PermissionRequest` | Quando uma permissão é solicitada | `PermissionRequestHookInput` |

## Uso básico

```java
HookMatcher.HookCallback logReads = (input, context) -> {
    if (input instanceof PreToolUseHookInput preToolUse) {
        System.out.println("About to read: " + preToolUse.toolInput().get("file_path"));
    }
    return CompletableFuture.completedFuture(HookOutput.empty());
};

var options = ClaudeAgentOptions.builder()
    .hooks(Map.of(
        HookEvent.PRE_TOOL_USE, List.of(new HookMatcher("Read", List.of(logReads)))
    ))
    .build();
```

## HookMatcher

```java
record HookMatcher(
    @Nullable String matcher,        // which tools/events to match; null matches all
    List<HookCallback> hooks,        // callbacks to run
    @Nullable Double timeoutSeconds  // timeout for all hooks in this matcher (CLI default: 60)
)

new HookMatcher(List<HookCallback> hooks)                  // matcher = null
new HookMatcher(String matcher, List<HookCallback> hooks)  // timeoutSeconds = null
```

Para eventos de ferramenta, `matcher` é o nome de uma ferramenta (`"Bash"`), vários nomes unidos por
`|` (`"Write|MultiEdit|Edit"`) ou `"*"`. Veja a
[referência de hooks do Claude Code](https://docs.anthropic.com/en/docs/claude-code/hooks#structure)
para a sintaxe de matcher de cada evento.

Um callback é um `HookMatcher.HookCallback`:

```java
@FunctionalInterface
interface HookCallback extends BiFunction<HookInput, HookContext, CompletableFuture<HookOutput>> {}
```

Ele recebe a entrada tipada e um `HookContext`:

```java
record HookContext(
    @Nullable String toolUseId,  // the tool use ID, for tool events
    @Nullable Object signal      // reserved for future abort support; always null
)
```

O SDK aguarda o future devolvido antes de responder ao CLI. Devolva
`CompletableFuture.completedFuture(...)` para trabalho síncrono.

> **Ordem de despacho:** quando há vários matchers registrados no mesmo evento, o CLI despacha todos
> os callbacks de hook correspondentes **concorrentemente** (em paralelo), não em sequência. Projete
> cada hook para ser independente — não conte com um terminar antes de outro começar (por exemplo,
> não encadeie hooks de limitação de taxa que pressuponham uma ordem de bloqueio).

## Entradas de hook

`HookInput` é uma sealed interface; ramifique no record concreto com `instanceof`. Toda entrada traz
estes campos:

| Acessor | Tipo | Descrição |
|----------|------|-------------|
| `hookEventName()` | `String` | O nome do evento, por exemplo `"PreToolUse"` |
| `sessionId()` | `String` | ID da sessão |
| `transcriptPath()` | `String` | Caminho da transcrição da sessão |
| `cwd()` | `String` | Diretório de trabalho |
| `permissionMode()` | `@Nullable String` | Modo de permissão atual |
| `promptId()` | `@Nullable String` | UUID do prompt do usuário em processamento. Corresponde ao atributo `prompt.id` dos eventos do OpenTelemetry. Nulo até a primeira entrada do usuário |
| `effort()` | `@Nullable HookEffort` | Esforço em vigor no turno atual, como `HookEffort(String level)` (por exemplo, `"low"`, `"high"`, `"max"`, já depois de qualquer rebaixamento para o modelo selecionado). Presente nos hooks que disparam dentro de um contexto de uso de ferramenta, em modelos que suportam esforço |

Campos específicos de cada evento:

| Entrada | Campos |
|-------|--------|
| `PreToolUseHookInput` | `toolName`, `toolInput`, `toolUseId`, `agentId`, `agentType` |
| `PostToolUseHookInput` | `toolName`, `toolInput`, `toolResponse`, `toolUseId`, `agentId`, `agentType`, `durationMs` |
| `PostToolUseFailureHookInput` | `toolName`, `toolInput`, `toolUseId`, `error`, `isInterrupt`, `agentId`, `agentType`, `durationMs` |
| `PermissionRequestHookInput` | `toolName`, `toolInput`, `permissionSuggestions`, `agentId`, `agentType` |
| `UserPromptSubmitHookInput` | `prompt` |
| `StopHookInput` | `stopHookActive`, `lastAssistantMessage`, `backgroundTasks`, `sessionCrons` |
| `SubagentStartHookInput` | `agentId`, `agentType` |
| `SubagentStopHookInput` | `stopHookActive`, `agentId`, `agentTranscriptPath`, `agentType`, `lastAssistantMessage`, `backgroundTasks`, `sessionCrons` |
| `PreCompactHookInput` | `trigger` (`CompactTriggerType`), `customInstructions` |
| `NotificationHookInput` | `message`, `title`, `notificationType` |

Os campos que o CLI pode omitir ficam `null` quando ausentes: `permissionMode`, `promptId`,
`effort`, `agentId`/`agentType` nas entradas de ferramenta, `isInterrupt`, `durationMs`,
`lastAssistantMessage`, `backgroundTasks`, `sessionCrons`, `permissionSuggestions`, `title` e
`customInstructions`.

### Contexto de subagente

As entradas relacionadas a ferramentas (`PreToolUseHookInput`, `PostToolUseHookInput`,
`PostToolUseFailureHookInput`, `PermissionRequestHookInput`) incluem dois campos opcionais com o
contexto do subagente:

| Campo | Tipo | Descrição |
|-------|------|-------------|
| `agentId` | `@Nullable String` | Identificador do subagente. Presente apenas dentro de um subagente criado por uma tarefa; nulo na thread principal. |
| `agentType` | `@Nullable String` | Nome do tipo de agente (por exemplo, `"general-purpose"`). Presente dentro de um subagente ou na thread principal quando iniciada com `--agent`. |

```java
HookMatcher.HookCallback subagentAware = (input, context) -> {
    if (input instanceof PreToolUseHookInput preToolUse && preToolUse.agentId() != null) {
        System.out.println("Tool used inside sub-agent: " + preToolUse.agentId());
    }
    return CompletableFuture.completedFuture(HookOutput.empty());
};
```

### Tempo de execução da ferramenta

`PostToolUseHookInput.durationMs()` e `PostToolUseFailureHookInput.durationMs()` informam o tempo de
execução da ferramenta em milissegundos, sem contar o tempo do prompt de permissão nem o dos hooks.

### Trabalho em segundo plano no Stop e no SubagentStop

`StopHookInput` e `SubagentStopHookInput` informam o que ainda está em execução quando a sessão (ou o
subagente) para:

- `lastAssistantMessage` — texto da última mensagem do assistente antes de parar.
- `backgroundTasks` — trabalho em segundo plano em andamento, como records `BackgroundTaskSummary`:
  `id`, `type` (por exemplo, `"shell"`, `"subagent"`, `"monitor"`, `"workflow"`), `status`,
  `description`, além de `command` (tarefas de shell), `agentType` (tarefas de subagente), `server` e
  `tool` (tarefas de monitor / MCP) e `name` (tarefas de workflow). Vazio quando não há nada em
  andamento.
- `sessionCrons` — despertares agendados com escopo de sessão que vão retomar a sessão mais tarde,
  como records `SessionCronSummary`: `id`, `schedule` (uma expressão cron), `recurring` (`false` para
  despertares únicos) e `prompt`.

Para um subagente, as duas listas têm o escopo da sessão pai. `description`, `command` e o `prompt`
de um cron são limitados a 1000 caracteres; um valor cortado termina com o marcador `"… [+N chars]"`
dentro da própria string.

```java
HookMatcher.HookCallback onStop = (input, context) -> {
    if (input instanceof StopHookInput stop && stop.backgroundTasks() != null) {
        for (BackgroundTaskSummary task : stop.backgroundTasks()) {
            System.out.printf("Still running: %s (%s, %s)%n", task.description(), task.type(), task.status());
        }
    }
    return CompletableFuture.completedFuture(HookOutput.empty());
};
```

## HookOutput

Um callback devolve um `HookOutput` (pacote `in.vidyalai.claude.sdk.types.hook.output`), construído
com o seu builder:

```java
HookOutput.empty()                 // no fields: the CLI proceeds as normal
HookOutput.async(30_000)           // defer: {"async": true, "asyncTimeout": 30000}

HookOutput.builder()
    .shouldContinue(false)         // "continue": whether Claude proceeds after the hook
    .stopReason("Budget exhausted")// shown when continue is false
    .suppressOutput(true)          // hide stdout from transcript mode
    .decision("block")             // "block" to indicate blocking behavior
    .systemMessage("Heads up")     // warning displayed to the user
    .reason("Blocked by policy")   // feedback for Claude about the decision
    .hookSpecificOutput(specific)  // event-specific controls, see below
    .build();
```

Só os campos que você define são enviados. Quando `async` está definido, apenas `async` e
`asyncTimeout` são enviados.

## Saídas específicas de hook

`HookSpecificOutput` é uma sealed interface com um record por evento. Cada um é serializado com o seu
`hookEventName` mais os campos que você definir (campos `null` são omitidos):

| Record | Campos |
|--------|--------|
| `PreToolUseHookSpecificOutput` | `permissionDecision` (`PermissionDecision`), `permissionDecisionReason`, `updatedInput`, `additionalContext` |
| `PostToolUseHookSpecificOutput` | `additionalContext`, `updatedToolOutput`, `updatedMCPToolOutput` |
| `PostToolUseFailureHookSpecificOutput` | `additionalContext` |
| `UserPromptSubmitHookSpecificOutput` | `additionalContext`, `sessionTitle`, `suppressOriginalPrompt` |
| `NotificationHookSpecificOutput` | `additionalContext` |
| `SubagentStartHookSpecificOutput` | `additionalContext` |
| `PermissionRequestHookSpecificOutput` | `decision` (um map) |
| `StopHookSpecificOutput` | `additionalContext` |
| `SubagentStopHookSpecificOutput` | `additionalContext` |
| `SessionStartHookSpecificOutput` | `additionalContext`, `initialUserMessage`, `sessionTitle`, `watchPaths`, `reloadSkills` |
| `UserPromptExpansionHookSpecificOutput` | `additionalContext`, `suppressOriginalPrompt` |
| `PostToolBatchHookSpecificOutput` | `additionalContext` |
| `PermissionDeniedHookSpecificOutput` | `retry` |
| `PreModelSwitchHookSpecificOutput` | `permissionDecision`, `permissionDecisionReason` |
| `PostModelSwitchHookSpecificOutput` | `additionalContext` |
| `ElicitationHookSpecificOutput` | `action` (`ElicitationAction`), `content` |
| `ElicitationResultHookSpecificOutput` | `action` (`ElicitationAction`), `content` |
| `CwdChangedHookSpecificOutput` | `watchPaths` |
| `FileChangedHookSpecificOutput` | `watchPaths` |
| `WorktreeCreateHookSpecificOutput` | `worktreePath` (obrigatório) |
| `MessageDisplayHookSpecificOutput` | `displayContent` |

> **Quais delas um callback pode usar.** Os callbacks do SDK só se registram para os dez `HookEvent`s
> acima, então apenas os nove primeiros records respondem a um evento que um callback pode receber.
> Os demais (de `SessionStart` a `MessageDisplay`) descrevem saídas para eventos que `HookEvent` ainda
> não lista. Eles são tipados para manter paridade com os SDKs de Python e TypeScript, que definem os
> mesmos formatos.

Observações sobre os campos:

- **`UserPromptSubmitHookSpecificOutput`** — `sessionTitle` define o título da sessão.
  `suppressOriginalPrompt` omite o prompt original da mensagem de bloqueio quando o `decision` da
  saída é `"block"`. Um construtor de um argumento `(additionalContext)` continua disponível.
- **`StopHookSpecificOutput` / `SubagentStopHookSpecificOutput`** — `additionalContext` é um retorno
  que não é de erro, entregue ao modelo (ou ao subagente); a conversa continua para que ele possa agir
  com base nisso.
- **`SessionStartHookSpecificOutput`** — `initialUserMessage` se torna a primeira mensagem do usuário
  da sessão; `sessionTitle` é ignorado quando a sessão começa a partir de `clear` ou `compact`;
  `watchPaths` são caminhos absolutos a observar para eventos FileChanged; `reloadSkills` volta a
  varrer os diretórios de skills e de comandos depois que os hooks SessionStart terminam.
- **`PermissionDeniedHookSpecificOutput`** — `retry` informa ao modelo que ele pode tentar de novo a
  chamada de ferramenta negada. É ignorado em negações sem veredito de classificador.
- **`PreModelSwitchHookSpecificOutput`** — `ALLOW` prossegue e `DENY` cancela a troca; `ASK` é
  tratado como recusa fora de uma sessão interativa. O construtor rejeita `PermissionDecision.DEFER`.
- **`PostModelSwitchHookSpecificOutput`** — `additionalContext` chega ao modelo junto com a próxima
  requisição atendida pelo novo modelo.
- **`ElicitationHookSpecificOutput`** — aceita, recusa ou cancela uma requisição de elicitação MCP;
  `content` contém os valores dos campos do formulário e só é usado com `ElicitationAction.ACCEPT`.
  `ElicitationResultHookSpecificOutput` sobrescreve a ação ou o conteúdo antes que a resposta chegue
  ao servidor MCP.
- **`CwdChangedHookSpecificOutput` / `FileChangedHookSpecificOutput`** — `watchPaths` substitui a
  lista dinâmica de observação atual.
- **`MessageDisplayHookSpecificOutput`** — apenas de exibição: `displayContent` substitui o texto na
  tela sem alterar a mensagem armazenada nem o que o modelo vê. Omita-o para exibir o original.

### Bloqueando uma chamada de ferramenta

```java
HookMatcher.HookCallback blockRm = (input, context) -> {
    if (input instanceof PreToolUseHookInput preToolUse
            && String.valueOf(preToolUse.toolInput().get("command")).contains("rm -rf")) {
        return CompletableFuture.completedFuture(HookOutput.builder()
            .hookSpecificOutput(new PreToolUseHookSpecificOutput(
                PermissionDecision.DENY,
                "rm -rf is not allowed",
                null,   // updatedInput
                null))  // additionalContext
            .build());
    }
    return CompletableFuture.completedFuture(HookOutput.empty());
};

var options = ClaudeAgentOptions.builder()
    .hooks(Map.of(HookEvent.PRE_TOOL_USE, List.of(new HookMatcher("Bash", List.of(blockRm)))))
    .build();
```

### Mantendo o modelo trabalhando a partir de um hook Stop

```java
HookMatcher.HookCallback keepGoing = (input, context) -> {
    if (input instanceof StopHookInput stop && !stop.stopHookActive()) {
        return CompletableFuture.completedFuture(HookOutput.builder()
            .hookSpecificOutput(new StopHookSpecificOutput("The test suite still fails; fix it before stopping."))
            .build());
    }
    return CompletableFuture.completedFuture(HookOutput.empty());
};
```

Verificar `stopHookActive()` evita mandar o modelo de volta ao trabalho indefinidamente: ele é `true`
quando a sessão já está continuando por causa de um hook Stop.

## Substituição da saída em PostToolUse

`PostToolUseHookSpecificOutput` permite que um hook `PostToolUse` substitua a saída da ferramenta
antes que ela chegue ao modelo.

```java
record PostToolUseHookSpecificOutput(
    @Nullable String additionalContext,    // extra context for the model
    @Nullable Object updatedToolOutput,    // replacement for any tool's output
    @Nullable Object updatedMCPToolOutput  // replacement for MCP tool output only
)
```

- **`updatedToolOutput`** — substitui a saída de qualquer ferramenta (inclusive as embutidas). Para
  ferramentas embutidas, o valor precisa corresponder ao schema de saída da ferramenta (por
  exemplo, `{"stdout": ..., "stderr": ..., "interrupted": ...}` para o `Bash`); um formato
  divergente é rejeitado e a saída original é mantida.
- **`updatedMCPToolOutput`** — substitui a saída apenas de ferramentas MCP. Prefira
  `updatedToolOutput`, que funciona para todas as ferramentas.
- Um construtor compatível de 2 argumentos `(additionalContext, updatedMCPToolOutput)` é preservado
  para código escrito antes de `updatedToolOutput` existir.

```java
HookMatcher.HookCallback redact = (input, context) -> {
    // Redact secrets from Bash output before the model sees it.
    Map<String, Object> redacted = Map.of(
        "stdout", "[redacted]",
        "stderr", "",
        "interrupted", false
    );
    return CompletableFuture.completedFuture(
        HookOutput.builder()
            .hookSpecificOutput(new PostToolUseHookSpecificOutput(null, redacted, null))
            .build()
    );
};

var options = ClaudeAgentOptions.builder()
    .hooks(Map.of(HookEvent.POST_TOOL_USE, List.of(new HookMatcher("Bash", List.of(redact)))))
    .build();
```

## Decisão de permissão: `"defer"`

Um hook `PreToolUse` pode retornar `permissionDecision: "defer"` (via `PermissionDecision.DEFER` /
`PreToolUseHookSpecificOutput`) para interromper a execução sem rodar a ferramenta. O CLI expõe a
chamada adiada em `ResultMessage.deferredToolUse`, para que quem consome o SDK possa inspecioná-la e
decidir se retoma.

```java
HookMatcher.HookCallback deferRisky = (input, context) -> {
    if (input instanceof PreToolUseHookInput preToolUse && looksDangerous(preToolUse.toolInput())) {
        return CompletableFuture.completedFuture(
            HookOutput.builder()
                .hookSpecificOutput(new PreToolUseHookSpecificOutput(
                    PermissionDecision.DEFER,
                    "Needs operator review",
                    null,
                    null))
                .build()
        );
    }
    return CompletableFuture.completedFuture(HookOutput.empty());
};

// Caller side — inspect the deferred call from the result message.
for (Message msg : ClaudeSDK.query(prompt, options)) {
    if (msg instanceof ResultMessage r && r.deferredToolUse() != null) {
        DeferredToolUse d = r.deferredToolUse();
        System.out.printf("Deferred %s (id=%s) input=%s%n", d.name(), d.id(), d.input());
    }
}
```

`DeferredToolUse` carrega `id`, `name` e `input`. Veja [Tipos de
mensagem](./feature-message-types.md#resultmessage) para o formato completo de `ResultMessage`.

## Eventos de ciclo de vida dos hooks no fluxo

Defina `includeHookEvents(true)` em `ClaudeAgentOptions` para receber também os eventos de ciclo de
vida dos hooks como objetos `HookEventMessage` no fluxo de mensagens. Isso é útil para
observabilidade (registrar cada disparo de hook) sem precisar registrar um hook para cada evento que
você queira acompanhar. O CLI emite os eventos de hook `SessionStart` e `Setup` independentemente
dessa configuração.

```java
var options = ClaudeAgentOptions.builder()
    .includeHookEvents(true)
    .hooks(Map.of(/* still register hooks normally */))
    .build();

for (Message msg : ClaudeSDK.query(prompt, options)) {
    if (msg instanceof HookEventMessage hook) {
        // subtype is "hook_started" or "hook_response"
        System.out.printf("[%s] %s session=%s%n",
            hook.subtype(), hook.hookEventName(), hook.sessionId());
        // Full raw payload (output, exit_code, outcome on hook_response)
        Object outcome = hook.get("outcome");
        if (outcome != null) {
            System.out.println("  outcome: " + outcome);
        }
    }
}
```

Campos de `HookEventMessage`:

| Campo | Tipo | Descrição |
|-------|------|-------------|
| `subtype` | `String` | `"hook_started"` quando um hook começa, `"hook_response"` quando termina |
| `data` | `Map<String, Object>` | Dicionário bruto completo do evento vindo do CLI (`output`, `exit_code`, `outcome` em `hook_response`) |
| `hookEventName` | `String` | Nome do evento de hook (por exemplo, `"PreToolUse"`, `"PostToolUse"`, `"Stop"`) |
| `sessionId` | `@Nullable String` | ID da sessão a que este evento pertence |
| `uuid` | `@Nullable String` | ID único do evento |

`HookEventMessage.type()` retorna `"system"`, mas **não** casa com `instanceof SystemMessage` —
ramifique diretamente em `HookEventMessage`.

## Exemplo completo

```java
public class HooksExample {
    public static void main(String[] args) {
        // Log every tool call before it runs
        HookMatcher.HookCallback logPre = (input, context) -> {
            if (input instanceof PreToolUseHookInput pre) {
                System.out.println("Tool: " + pre.toolName());
            }
            return CompletableFuture.completedFuture(HookOutput.empty());
        };

        // Track tool results and timing
        HookMatcher.HookCallback logPost = (input, context) -> {
            if (input instanceof PostToolUseHookInput post) {
                System.out.println("Result: " + post.toolResponse() + " in " + post.durationMs() + "ms");
            }
            return CompletableFuture.completedFuture(HookOutput.empty());
        };

        // Handle errors
        HookMatcher.HookCallback logFailure = (input, context) -> {
            if (input instanceof PostToolUseFailureHookInput failure) {
                System.err.println("Error: " + failure.error());
            }
            return CompletableFuture.completedFuture(HookOutput.empty());
        };

        var options = ClaudeAgentOptions.builder()
            .hooks(Map.of(
                HookEvent.PRE_TOOL_USE, List.of(new HookMatcher("*", List.of(logPre))),
                HookEvent.POST_TOOL_USE, List.of(new HookMatcher("*", List.of(logPost))),
                HookEvent.POST_TOOL_USE_FAILURE, List.of(new HookMatcher("*", List.of(logFailure)))
            ))
            .build();

        try (var client = ClaudeSDK.createClient(options)) {
            client.connect("List files in current directory");
            for (var msg : client.receiveResponse()) {
                // Process
            }
        }
    }
}
```

## Hooks depois que um subagente em segundo plano termina

Com um `ClaudeSDK.query(...)` pontual, um hook no turno que a conclusão de um subagente em segundo
plano desperta só roda se o stdin ainda estiver aberto quando o CLI o chamar. O SDK mantém o stdin
aberto até o fim da execução, em vez de fechá-lo no primeiro resultado; veja
[Agentes → Subagentes em segundo plano e callbacks](./feature-agents.md#subagentes-em-segundo-plano-e-callbacks)
para as regras e uma ressalva sobre a versão do CLI, e `BackgroundAgentHooksExample` para uma
demonstração executável.

## Veja também
- [Opções de configuração](./feature-configuration-options.md#hooks)
- [Permissões](./feature-permissions.md)
- [Exemplo de Hooks](../../examples/src/main/java/examples/Hooks.java)
