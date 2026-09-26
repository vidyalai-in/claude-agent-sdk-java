# Sistema de hooks

Intercepte e responda a eventos do ciclo de vida das conversas do Claude.

> **Sobre esta tradução**: a documentação em inglês é a única autoritativa. Esta tradução pode estar desatualizada em relação ao [original em inglês](../feature-hooks.md); em caso de divergência, o inglês prevalece. Os blocos de código são mantidos idênticos ao original e não foram traduzidos.

## Visão geral

Os hooks permitem executar código próprio em pontos específicos do ciclo de vida da conversa. São
10 os eventos de hook que você pode escutar.

## Eventos de hook

- **PRE_TOOL_USE** — antes da execução da ferramenta
- **POST_TOOL_USE** — depois da execução bem-sucedida da ferramenta
- **POST_TOOL_USE_FAILURE** — depois que a execução da ferramenta falha
- **USER_PROMPT_SUBMIT** — quando o usuário envia uma mensagem
- **STOP** — quando a sessão para
- **SUBAGENT_START** — quando um subagente inicia
- **SUBAGENT_STOP** — quando um subagente para
- **PRE_COMPACT** — antes da compactação de mensagens
- **NOTIFICATION** — em eventos de notificação
- **PERMISSION_REQUEST** — quando uma permissão é solicitada

## Uso básico

```java
var options = ClaudeAgentOptions.builder()
    .hooks(Map.of(
        HookEvent.PRE_TOOL_USE, List.of(
            new HookMatcher(null, "Read", context -> {
                System.out.println("About to read: " + context.input());
                return CompletableFuture.completedFuture(
                    HookOutput.logs(List.of("Pre-tool log"))
                );
            })
        )
    ))
    .build();
```

## HookMatcher

```java
new HookMatcher(
    String toolName,           // null for all tools
    String matchPattern,       // Tool name pattern
    Function<HookContext, CompletableFuture<HookOutput>> handler
)
```

> **Ordem de despacho:** quando há vários matchers registrados no mesmo evento, o CLI despacha todos
> os callbacks de hook correspondentes **concorrentemente** (em paralelo), não em sequência. Projete
> cada hook para ser independente — não conte com um terminar antes de outro começar (por exemplo,
> não encadeie hooks de limitação de taxa que pressuponham uma ordem de bloqueio).

## Campos de entrada dos hooks

Todas as entradas de hook relacionadas a ferramentas (`PreToolUseHookInput`,
`PostToolUseHookInput`, `PostToolUseFailureHookInput`, `PermissionRequestHookInput`) incluem dois
campos opcionais com o contexto do subagente:

| Campo | Tipo | Descrição |
|-------|------|-------------|
| `agentId` | `@Nullable String` | Identificador do subagente. Presente apenas dentro de um subagente criado por uma tarefa; nulo na thread principal. |
| `agentType` | `@Nullable String` | Nome do tipo de agente (por exemplo, `"general-purpose"`). Presente dentro de um subagente ou na thread principal quando iniciada com `--agent`. |

```java
new HookMatcher(null, null, context -> {
    PreToolUseHookInput input = (PreToolUseHookInput) context.input();
    if (input.agentId() != null) {
        System.out.println("Tool used inside sub-agent: " + input.agentId());
    }
    return CompletableFuture.completedFuture(HookOutput.empty());
})
```

## HookOutput

```java
// Empty output
HookOutput.empty()

// With logs
HookOutput.logs(List.of("Log message"))

// With messages
HookOutput.messages(List.of(
    Map.of("role", "user", "content", "Message")
))

// With permission updates
HookOutput.permissionUpdates(List.of(update))

// Combined
HookOutput.builder()
    .logs(List.of("Log"))
    .messages(List.of(message))
    .build()
```

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
HookEvent.POST_TOOL_USE, List.of(
    new HookMatcher(null, "Bash", context -> {
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
    })
)
```

## Decisão de permissão: `"defer"`

Um hook `PreToolUse` pode retornar `permissionDecision: "defer"` (via `PermissionDecision.DEFER` /
`PreToolUseHookSpecificOutput`) para interromper a execução sem rodar a ferramenta. O CLI expõe a
chamada adiada em `ResultMessage.deferredToolUse`, para que quem consome o SDK possa inspecioná-la e
decidir se retoma.

```java
HookEvent.PRE_TOOL_USE, List.of(
    new HookMatcher(null, "Bash", context -> {
        PreToolUseHookInput input = (PreToolUseHookInput) context.input();
        if (looksDangerous(input.toolInput())) {
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
    })
)

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
você queira acompanhar.

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
        var options = ClaudeAgentOptions.builder()
            .hooks(Map.of(
                // Log all tool uses
                HookEvent.PRE_TOOL_USE, List.of(
                    new HookMatcher(null, null, context -> {
                        PreToolUseHookInput input = (PreToolUseHookInput) context.input();
                        System.out.println("Tool: " + input.toolName());
                        return CompletableFuture.completedFuture(
                            HookOutput.logs(List.of(
                                "Executing: " + input.toolName()
                            ))
                        );
                    })
                ),
                
                // Track tool results
                HookEvent.POST_TOOL_USE, List.of(
                    new HookMatcher(null, null, context -> {
                        PostToolUseHookInput input = (PostToolUseHookInput) context.input();
                        System.out.println("Result: " + input.result());
                        return CompletableFuture.completedFuture(
                            HookOutput.empty()
                        );
                    })
                ),
                
                // Handle errors
                HookEvent.POST_TOOL_USE_FAILURE, List.of(
                    new HookMatcher(null, null, context -> {
                        PostToolUseFailureHookInput input = 
                            (PostToolUseFailureHookInput) context.input();
                        System.err.println("Error: " + input.error());
                        return CompletableFuture.completedFuture(
                            HookOutput.logs(List.of("Tool failed"))
                        );
                    })
                )
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
- [Exemplo de Hooks](../../examples/src/main/java/examples/Hooks.java)
