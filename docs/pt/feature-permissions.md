# Sistema de permissões

Controle de permissões sob medida para o uso de ferramentas.

> **Sobre esta tradução**: a documentação em inglês é a única autoritativa. Esta tradução pode estar desatualizada em relação ao [original em inglês](../feature-permissions.md); em caso de divergência, o inglês prevalece. Os blocos de código são mantidos idênticos ao original e não foram traduzidos.

## Visão geral

O sistema de permissões controla quais ferramentas o Claude pode usar e como os pedidos de
permissão são tratados.

## Modos de permissão

```java
.permissionMode(PermissionMode.BYPASS_PERMISSIONS)
```

### Modos disponíveis

- **PROMPT** (padrão) — pergunta ao usuário a cada permissão
- **ACCEPT_ALL** — aceita automaticamente todas as permissões
- **ACCEPT_EDITS** — aceita automaticamente edições de arquivo e pergunta nos demais casos
- **BYPASS_PERMISSIONS** — pula completamente as verificações de permissão
- **DONT_ASK** — permite todas as ferramentas sem perguntar
- **AUTO** — determina automaticamente o modo de permissão apropriado

## Callback de permissão personalizado

Para um controle mais fino, use um callback `canUseTool`. Ele funciona com todos os pontos de
entrada — um prompt em string é transmitido internamente pelo stdin, então o protocolo de controle
que carrega os pedidos de permissão também está disponível ali. Não pode ser combinado com
`permissionPromptToolName`:

```java
.canUseTool((toolName, input, context) -> {
    // Custom logic
    if (shouldAllow(toolName, context.blockedPath())) {
        return CompletableFuture.completedFuture(
            new PermissionResultAllow()
        );
    } else {
        return CompletableFuture.completedFuture(
            new PermissionResultDeny("Access denied: " + context.blockedPath())
        );
    }
})
```

> **`canUseTool` só dispara em decisões `"ask"`.** Esse callback é o substituto, no SDK, do prompt
> interativo de permissão — ele roda apenas quando as regras de permissão do CLI resultam em
> `"ask"`. Ele **não** é invocado para chamadas de ferramenta já permitidas por `allowedTools`,
> `permissionMode` (por exemplo, `ACCEPT_EDITS`, `BYPASS_PERMISSIONS`) ou regras `permissions.allow`
> nas configurações — essas nunca chegam a um prompt. Para observar ou controlar **toda** chamada de
> ferramenta, independentemente das regras de permissão, registre um hook `PreToolUse` via
> `hooks(...)`.

### Aviso de sombreamento

Como `canUseTool` nunca dispara para chamadas já permitidas por outras opções, o SDK emite um
**aviso informativo** no momento da conexão quando detecta um callback visivelmente sombreado. A
verificação roda uma vez por construção de consulta — quando `ClaudeSDKClient.connect()` começa, ou
quando qualquer `ClaudeSDK.query(...)` é preparado — e registra um `WARNING` via
`java.util.logging` no logger chamado `in.vidyalai.claude.sdk.internal.CanUseToolShadow`.

Um callback `canUseTool` é reportado como sombreado quando é definido junto com um destes:

- **`permissionMode(PermissionMode.BYPASS_PERMISSIONS)`** — toda chamada de ferramenta é aprovada
  automaticamente (exceto por regras de negação explícitas) antes de o callback ser consultado.
- **Uma entrada de `allowedTools` que permite a ferramenta *inteira*** — uma entrada sem
  especificador (`"Read"`), com especificador vazio (`"Read()"`) ou com especificador só de curinga
  (`"Read(*)"`). Um especificador restritivo como `"Bash(ls:*)"` **não** sombreia o callback, porque
  invocações que não casam ainda chegam até ele. O aviso nomeia cada ferramenta sombreada.

`skills("all")` (via `Builder.skillsAll()`) é levado em conta: faz o transporte injetar uma regra de
permissão `Skill` simples, então sombreia o callback exatamente como uma entrada `"Skill"` escrita à
mão. Skills nomeadas (`skills(List.of("reviewer"))`) injetam especificadores `Skill(name)`, que não
sombreiam.

O aviso é **apenas informativo — nunca lança exceção**. O sombreamento pode ser intencional (por
exemplo, um callback usado exclusivamente para ferramentas que *não* estão em `allowedTools`). Para
observar ou controlar toda chamada de ferramenta independentemente das regras de permissão, use um
hook `PreToolUse` — mas note que um hook `PreToolUse` que retorna uma decisão de *permitir* também
pula o `canUseTool`. Regras de permissão que vivem em arquivos de configuração também podem
sombrear o callback, mas não são visíveis para essa verificação.

Para silenciar o aviso, eleve o nível do logger
`in.vidyalai.claude.sdk.internal.CanUseToolShadow` acima de `WARNING`:

```java
java.util.logging.Logger
    .getLogger("in.vidyalai.claude.sdk.internal.CanUseToolShadow")
    .setLevel(java.util.logging.Level.SEVERE);
```

### Mantendo um callback determinístico

As regras de arquivos de configuração são o caso de sombreamento que o aviso não enxerga, e o mais
fácil de esbarrar: um `Write(*)` puro sob `permissions.allow` em `~/.claude/settings.json` já basta
para o callback `canUseTool` parar de disparar por completo, numa máquina onde ontem funcionava.
Nada dá erro — o callback simplesmente nunca é consultado, e um código que conta os prompts que
tratou reporta zero.

Onde um callback precisa disparar de forma previsível — uma demonstração, um teste, um script
reproduzível — não carregue arquivo de configuração algum:

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
        .canUseTool(callback)
        // Load no settings files: an allow rule in the user's or the project's
        // settings.json shadows the callback exactly like an allowedTools
        // entry does, and the advisory above cannot see those rules.
        .settingSources(List.of())
        .permissionMode(PermissionMode.DEFAULT)
        .build();
```

Note também que uma ferramenta listada em `allowedTools` nunca chega a um prompt, então um callback
destinado a controlar essa ferramenta precisa deixá-la fora da lista. O `PermissionCallbacks.java`
no módulo de exemplos faz as duas coisas.

> **Nota de idiomática:** o SDK Python reporta essa condição como `CanUseToolShadowedWarning` (uma
> subclasse de `UserWarning`) e o SDK TypeScript como um aviso de processo
> `CLAUDE_SDK_CAN_USE_TOOL_SHADOWED`. O SDK Java usa `java.util.logging` — seu canal idiomático de
> avisos — no lugar de um tipo de aviso dedicado.

## ToolPermissionContext

O CLI enriquece o contexto de permissão para que os callbacks possam montar prompts significativos
sem reconstruí-los a partir da entrada bruta da ferramenta:

```java
record ToolPermissionContext(
    @Nullable Object signal,                 // reserved for future abort signal support (always null today)
    List<PermissionUpdate> suggestions,      // permission suggestions from the CLI
    @Nullable String toolUseId,              // unique tool call ID within the assistant message
    @Nullable String agentId,                // sub-agent's ID if running inside a sub-agent
    @Nullable String blockedPath,            // file path that triggered the request (e.g. Bash hitting a denied path)
    @Nullable String decisionReason,         // why this prompt was triggered (e.g. PreToolUse hook's permissionDecisionReason)
    @Nullable String title,                  // full prompt sentence ("Claude wants to read foo.txt") — use as primary prompt text
    @Nullable String displayName,            // short noun phrase ("Read file") for buttons / compact UI
    @Nullable String description             // human-readable subtitle for the permission UI
)
```

Construtores compatíveis com versões anteriores são preservados para código escrito antes de os
campos enriquecidos existirem:

- `new ToolPermissionContext()` — contexto vazio
- `new ToolPermissionContext(suggestions)` — apenas suggestions
- `new ToolPermissionContext(signal, suggestions)` — signal + suggestions
- `new ToolPermissionContext(signal, suggestions, toolUseId, agentId)` — a forma de 4 argumentos anterior ao enriquecimento

```java
.canUseTool((toolName, input, context) -> {
    // Prefer the CLI-supplied prompt text when present.
    String prompt = context.title() != null
        ? context.title()
        : "Allow " + toolName + "?";
    String why = context.decisionReason();
    if (why != null) prompt += " (" + why + ")";

    boolean ok = askUser(prompt);
    return CompletableFuture.completedFuture(
        ok ? new PermissionResultAllow()
           : new PermissionResultDeny("user declined"));
})
```

## PermissionDecision (em hooks PreToolUse)

`PermissionDecision` é o valor que um hook `PreToolUse` retorna em
`PreToolUseHookSpecificOutput.permissionDecision`:

| Constante | Valor no protocolo | Efeito |
|----------|------------|--------|
| `ALLOW` | `"allow"` | A ferramenta roda sem perguntar. |
| `DENY` | `"deny"` | A ferramenta é bloqueada. |
| `ASK` | `"ask"` | Dispara o callback `canUseTool` do SDK (ou o prompt do CLI). |
| `DEFER` | `"defer"` | Interrompe a execução sem rodar a ferramenta; a chamada adiada aparece em `ResultMessage.deferredToolUse`. Veja [Hooks → Decisão de permissão `"defer"`](./feature-hooks.md#decisão-de-permissão-defer). |

## PermissionResult

```java
// Allow
new PermissionResultAllow()

// Deny with reason
new PermissionResultDeny("Reason for denial")
```

## Exemplos

### Permissões baseadas em caminho

```java
.canUseTool((toolName, input, context) -> {
    // blockedPath is set by the CLI when the request was triggered by a
    // path violation (e.g. a Bash command touching a denied directory).
    // For tools like Read / Write the path is in `input` instead.
    String path = context.blockedPath() != null
        ? context.blockedPath()
        : (String) input.get("file_path");

    // Allow read-only in /src
    if (toolName.equals("Read") && path != null && path.startsWith("/src")) {
        return CompletableFuture.completedFuture(
            new PermissionResultAllow()
        );
    }

    // Deny write to sensitive dirs
    if (toolName.equals("Write") && path != null && path.contains("/config")) {
        return CompletableFuture.completedFuture(
            new PermissionResultDeny("Cannot write to config")
        );
    }

    // Default allow
    return CompletableFuture.completedFuture(
        new PermissionResultAllow()
    );
})
```

### Permissões baseadas em horário

```java
.canUseTool((toolName, input, context) -> {
    // Only allow during business hours
    int hour = LocalTime.now().getHour();
    if (hour < 9 || hour > 17) {
        return CompletableFuture.completedFuture(
            new PermissionResultDeny("Outside business hours")
        );
    }
    
    return CompletableFuture.completedFuture(
        new PermissionResultAllow()
    );
})
```

### Confirmação do usuário

```java
.canUseTool((toolName, input, context) -> {
    // Prompt user for dangerous operations
    if (toolName.equals("Bash")) {
        boolean approved = promptUser("Allow bash: " + input + "?");
        if (approved) {
            return CompletableFuture.completedFuture(
                new PermissionResultAllow()
            );
        } else {
            return CompletableFuture.completedFuture(
                new PermissionResultDeny("User rejected")
            );
        }
    }
    
    return CompletableFuture.completedFuture(
        new PermissionResultAllow()
    );
})
```

## Veja também
- [Opções de configuração](./feature-configuration-options.md#configurações-de-permissão)
- [Exemplo de callbacks de permissão](../../examples/src/main/java/examples/PermissionCallbacks.java)
