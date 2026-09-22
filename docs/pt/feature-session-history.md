# Histórico de sessões

Leia e navegue por sessões antigas de conversa do Claude Code sem executar o CLI.

> **Sobre esta tradução**: a documentação em inglês é a única autoritativa. Esta tradução pode estar desatualizada em relação ao [original em inglês](../feature-session-history.md); em caso de divergência, o inglês prevalece. Os blocos de código são mantidos idênticos ao original e não foram traduzidos.

## Sumário
- [Visão geral](#visão-geral)
- [Metadados da sessão](#metadados-da-sessão)
- [Mensagens da sessão](#mensagens-da-sessão)
- [Listando sessões](#listando-sessões)
- [Consultando uma única sessão](#consultando-uma-única-sessão)
- [Lendo as mensagens de uma sessão](#lendo-as-mensagens-de-uma-sessão)
- [Renomeando sessões](#renomeando-sessões)
- [Marcando sessões com tags](#marcando-sessões-com-tags)
- [Excluindo sessões](#excluindo-sessões)
- [Bifurcando sessões](#bifurcando-sessões)
- [Exemplos](#exemplos)
- [Boas práticas](#boas-práticas)

## Visão geral

O Claude Code guarda cada conversa como um arquivo JSONL em `~/.claude/projects/`. A API de
histórico de sessões permite:

- **Listar sessões** de todos os projetos ou filtradas por um diretório de trabalho específico
- **Ler mensagens** de qualquer sessão passada — a transcrição completa da conversa

Toda a leitura é feita direto do disco, independentemente do CLI. Nenhum processo é criado.

**Desempenho:** na listagem, só os primeiros e os últimos 64 KB de cada arquivo de sessão são lidos
(sem interpretar o JSONL inteiro). A interpretação completa só acontece ao buscar mensagens com
`getSessionMessages`.

> **Procurando um back-end remoto/multi-host?** Veja [Session
> Store](./feature-session-store.md). Todo método desta página tem um equivalente `*FromStore`
> (leitura) ou `*ViaStore` (mutação) em `ClaudeSDK` que opera sobre um adaptador `SessionStore` (S3,
> Postgres, Redis, próprio). As APIs de disco local documentadas aqui continuam sendo o caminho
> canônico; as APIs de SessionStore são aditivas e miram o mesmo layout em disco por portabilidade.

## Metadados da sessão

`SDKSessionInfo` guarda os metadados de uma sessão:

```java
record SDKSessionInfo(
    String sessionId,              // UUID identifying the session
    String summary,                // display title (custom title, AI title, lastPrompt, summary, or first prompt)
    long lastModified,             // last-modified time in milliseconds since epoch
    @Nullable Long fileSize,       // session file size in bytes (null for remote storage backends)
    @Nullable String customTitle,  // user-set custom title or AI-generated title (may be null)
    @Nullable String firstPrompt,  // first meaningful user prompt (may be null)
    @Nullable String gitBranch,    // git branch at end of session (may be null)
    @Nullable String cwd,          // working directory for the session (may be null)
    @Nullable String tag,          // user-set session tag (may be null)
    @Nullable Long createdAt       // creation time in ms since epoch from first entry's ISO timestamp (may be null)
)
```

O campo `summary` é resolvido em ordem de prioridade: título personalizado > título de IA >
lastPrompt > resumo gerado automaticamente > primeiro prompt.

Também há um construtor compatível com versões anteriores, sem `tag` e `createdAt`.

## Mensagens da sessão

`SessionMessage` guarda uma única mensagem da transcrição de uma sessão:

```java
record SessionMessage(
    String type,                         // "user" or "assistant"
    String uuid,                         // unique message UUID
    String sessionId,                    // session ID this message belongs to
    Object message,                      // raw Anthropic API message (Map with role/content)
    @Nullable String parentToolUseId,    // spawning Agent tool_use id (subagent reads only)
    @Nullable String parentAgentId       // spawning subagent id (nested subagents only)
)
```

Só são devolvidas as mensagens de conversa de nível superior — mensagens laterais de uso de
ferramenta, mensagens meta e mensagens de subagentes são filtradas.

`parentToolUseId` e `parentAgentId` são sempre nulos nos resultados de `getSessionMessages()` /
`getSessionMessagesFromStore()`. Eles são preenchidos em `getSubagentMessages()` /
`getSubagentMessagesFromStore()`: `parentToolUseId` é o id do bloco `tool_use` do Agent na sessão pai
que criou o subagente, e `parentAgentId` nomeia o subagente criador quando um subagente criou outro.
Ambos vêm do arquivo lateral `agent-<agentId>.meta.json` do subagente (ou, em leituras de store, da
entrada `agent_metadata` que faz esse papel), então ambos são nulos quando esses metadados estão
ausentes ou inutilizáveis. Todas as mensagens de uma dada transcrição de subagente carregam o mesmo
par.

### Acessando o conteúdo da mensagem

O campo `message` é um `Map<String, Object>` bruto no formato de fio da Anthropic API:

```java
SessionMessage msg = ...;
if (msg.message() instanceof Map<?, ?> m) {
    Object content = m.get("content");
    if (content instanceof String text) {
        System.out.println(text);
    } else if (content instanceof List<?> blocks) {
        for (Object block : blocks) {
            if (block instanceof Map<?, ?> b && b.get("text") instanceof String t) {
                System.out.println(t);
            }
        }
    }
}
```

## Listando sessões

### Todas as sessões

```java
List<SDKSessionInfo> sessions = ClaudeSDK.listSessions();
```

Devolve todas as sessões de todos os projetos, das mais recentes para as mais antigas.

### Sessões de um projeto

```java
Path projectDir = Path.of("/my/project");
List<SDKSessionInfo> sessions = ClaudeSDK.listSessions(projectDir);
```

Filtra as sessões cujo diretório de trabalho corresponde a `projectDir`.

### Com limite e worktrees

```java
List<SDKSessionInfo> recent = ClaudeSDK.listSessions(
    Path.of("/my/project"),   // null for all projects
    10,                        // max 10 results
    true                       // include git worktrees
);
```

`includeWorktrees = true` executa `git worktree list` e inclui as sessões de todos os worktrees do
repositório.

### Com paginação por offset

```java
// Page 1: first 50 sessions
List<SDKSessionInfo> page1 = ClaudeSDK.listSessions(
    Path.of("/my/project"), 50, 0, true);

// Page 2: next 50 sessions
List<SDKSessionInfo> page2 = ClaudeSDK.listSessions(
    Path.of("/my/project"), 50, 50, true);
```

## Consultando uma única sessão

Use `getSessionInfo` para consultar uma sessão pelo ID sem varrer todos os arquivos de sessão. É mais
eficiente que `listSessions` quando você já conhece o UUID da sessão.

### Pelo ID da sessão (busca em todos os projetos)

```java
SDKSessionInfo info = ClaudeSDK.getSessionInfo("550e8400-e29b-41d4-a716-446655440000");
if (info != null) {
    System.out.println("Session: " + info.summary());
    if (info.tag() != null) {
        System.out.println("Tag: " + info.tag());
    }
    if (info.createdAt() != null) {
        String created = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(info.createdAt()));
        System.out.println("Created: " + created);
    }
}
```

### Limitado a um diretório de projeto

```java
SDKSessionInfo info = ClaudeSDK.getSessionInfo(
    "550e8400-e29b-41d4-a716-446655440000",
    Path.of("/my/project")
);
```

Devolve `null` se a sessão não for encontrada, for uma sessão lateral, ou não tiver resumo
extraível.

## Lendo as mensagens de uma sessão

### Transcrição completa

```java
List<SessionMessage> messages = ClaudeSDK.getSessionMessages(sessionId);
```

Busca o UUID da sessão em todos os diretórios de projeto.

### Limitado a um projeto

```java
List<SessionMessage> messages = ClaudeSDK.getSessionMessages(
    sessionId,
    Path.of("/my/project")
);
```

### Com paginação

```java
// Skip first 20 messages, return next 10
List<SessionMessage> page = ClaudeSDK.getSessionMessages(
    sessionId,
    null,    // all projects
    10,      // limit
    20       // offset
);
```

## Renomeando sessões

Renomeie uma sessão acrescentando uma entrada de título personalizado ao seu arquivo JSONL. A
renomeação mais recente sempre vence — é seguro chamar várias vezes.

```java
// Rename by session ID (searches all projects)
ClaudeSDK.renameSession("550e8400-e29b-41d4-a716-446655440000", "My Feature Branch Session");

// Rename scoped to a specific project directory
ClaudeSDK.renameSession(
    "550e8400-e29b-41d4-a716-446655440000",
    "My Feature Branch Session",
    Path.of("/my/project")
);
```

**Restrições:**
- `sessionId` precisa ser um UUID válido (hexadecimal minúsculo com hifens).
- `title` não pode ficar vazio depois de remover os espaços das pontas.
- Lança `FileNotFoundException` se o arquivo JSONL da sessão não for encontrado.
- Lança `IOException` se a escrita falhar.

Depois de renomear, `listSessions()` devolve o novo título nos campos `summary` e `customTitle` de
`SDKSessionInfo`.

## Marcando sessões com tags

Marque uma sessão com uma tag para filtrar e organizar. Passe `null` para limpar uma tag existente.
As tags passam por saneamento Unicode antes de serem gravadas, por compatibilidade com o filtro do
CLI.

```java
// Tag a session (searches all projects)
ClaudeSDK.tagSession("550e8400-e29b-41d4-a716-446655440000", "production");

// Clear a tag
ClaudeSDK.tagSession("550e8400-e29b-41d4-a716-446655440000", null);

// Tag scoped to a specific project directory
ClaudeSDK.tagSession(
    "550e8400-e29b-41d4-a716-446655440000",
    "staging",
    Path.of("/my/project")
);
```

**Restrições:**
- `sessionId` precisa ser um UUID válido.
- `tag` não pode ficar vazia depois do saneamento Unicode e da remoção de espaços (ou `null` para
  limpar).
- Tags com caracteres Unicode perigosos (largura zero, marcas direcionais, uso privado) são saneadas
  automaticamente.
- Lança `FileNotFoundException` se o arquivo JSONL da sessão não for encontrado.
- Lança `IOException` se a escrita falhar.

**Segurança em concorrência:** se a sessão estiver aberta no processo do CLI, o CLI absorve as
entradas escritas pelo SDK no seu cache no próximo reanexo de metadados. A escrita mais recente
vence.

## Excluindo sessões

Exclua uma sessão permanentemente removendo seu arquivo JSONL. O diretório irmão `<sessionId>/` que
guarda as transcrições de subagentes também é removido recursivamente (melhor esforço; se não
existir, tudo bem).

```java
// Delete by session ID (searches all projects)
ClaudeSDK.deleteSession("550e8400-e29b-41d4-a716-446655440000");

// Delete scoped to a specific project directory
ClaudeSDK.deleteSession("550e8400-e29b-41d4-a716-446655440000", Path.of("/my/project"));
```

**Restrições:**
- `sessionId` precisa ser um UUID válido.
- Lança `FileNotFoundException` se o arquivo da sessão não for encontrado.
- Para semântica de exclusão lógica, use `tagSession(id, "__hidden")` e filtre na listagem.

## Lendo transcrições de subagentes

Quando uma sessão cria subagentes (pela ferramenta `Task` ou por definições programáticas de agente),
cada subagente escreve a própria transcrição em
`~/.claude/projects/<project>/<sessionId>/subagents/agent-<agentId>.jsonl`. As transcrições de
subagentes também podem ficar em diretórios aninhados, como `subagents/workflows/<runId>/`.

```java
// Enumerate subagent IDs for a session
List<String> agentIds = ClaudeSDK.listSubagents(
    "550e8400-e29b-41d4-a716-446655440000");

// Or scoped to a specific project
List<String> agentIds = ClaudeSDK.listSubagents(
    "550e8400-e29b-41d4-a716-446655440000",
    Path.of("/my/project"));

// Read a subagent's full conversation
List<SessionMessage> messages = ClaudeSDK.getSubagentMessages(
    "550e8400-e29b-41d4-a716-446655440000",
    "abc123");

// With limit and offset
List<SessionMessage> page = ClaudeSDK.getSubagentMessages(
    "550e8400-e29b-41d4-a716-446655440000",
    "abc123",
    Path.of("/my/project"),
    50,    // limit (null or 0 = no limit)
    0);    // offset
```

**Comportamento:**
- `listSubagents` varre recursivamente a árvore `subagents/` em busca de arquivos que casem com
  `agent-<id>.jsonl` e devolve os IDs na ordem de iteração dos diretórios.
- `getSubagentMessages` percorre os elos `parentUuid` a partir da folha para reconstruir a cadeia. As
  transcrições de subagente são lineares (sem compactação, sem ramos laterais), então a lista
  devolvida é a conversa completa em ordem cronológica.
- Linhas JSONL corrompidas são ignoradas em silêncio.
- UUIDs inválidos, sessões ausentes, agentes ausentes e IDs de agente vazios devolvem lista vazia
  (nunca lançam).

## Bifurcando sessões

Bifurque uma sessão em um novo ramo com UUIDs novos. Copia as mensagens da transcrição da sessão de
origem, remapeando cada UUID de mensagem e preservando a cadeia `parentUuid`. Sessões bifurcadas
começam sem histórico de desfazer.

```java
// Fork a session (searches all projects)
ForkSessionResult result = ClaudeSDK.forkSession("550e8400-e29b-41d4-a716-446655440000");
System.out.println("New session: " + result.sessionId());

// Fork scoped to a project directory
ForkSessionResult result = ClaudeSDK.forkSession(
    "550e8400-e29b-41d4-a716-446655440000",
    Path.of("/my/project")
);

// Fork from a specific message (truncate transcript)
ForkSessionResult result = ClaudeSDK.forkSession(
    "550e8400-e29b-41d4-a716-446655440000",
    null,                                       // search all projects
    "660e8400-e29b-41d4-a716-446655440001",    // slice transcript at this message
    "My Fork Title"                            // custom title (null = original + " (fork)")
);
```

**`ForkSessionResult`** contém:
- `sessionId` — o UUID da sessão bifurcada recém-criada

**Restrições:**
- `sessionId` e o opcional `upToMessageId` precisam ser UUIDs válidos.
- Lança `FileNotFoundException` se a sessão de origem não for encontrada.
- Lança `IllegalArgumentException` se a sessão não tiver mensagens ou se `upToMessageId` não for
  encontrado.

`forkSession()` produz uma cópia offline sem executar o CLI. Para *retomar* de um ponto anterior e
continuar conversando, use a retomada com truncamento.

## Retomada com truncamento

`resumeSessionAt` carrega a conversa retomada apenas até um dado UUID de entrada da transcrição
(inclusive), descartando tudo depois dele. Junto com `forkSession(true)`, ele ramifica em uma nova
sessão e deixa a original intacta.

`resumeDropsTurn` torna esse truncamento **seguro**. Dê a ele o UUID do prompt de usuário cujo turno
você pretende descartar, e o CLI valida, no carregamento, que toda entrada após o ponto de bifurcação
pertence àquele turno — recusando em caso contrário. Sem isso, uma mensagem de usuário enfileirada ou
uma notificação de tarefa em segundo plano que a sessão absorveu no meio do turno, e que você nunca
viu, seria descartada em silêncio.

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .cwd(projectDir)
    .resume(sessionId)
    .forkSession(true)               // branch; leave the source session intact
    .resumeSessionAt(keepAtUuid)     // last transcript entry of the turn to keep
    .resumeDropsTurn(nextPromptUuid) // prompt UUID of the turn being discarded
    .build();

for (Message msg : ClaudeSDK.query("Reply with exactly: three", options)) {
    if (msg instanceof ResultMessage r) {
        System.out.println("Forked session: " + r.sessionId());
    }
}
```

**Escolhendo os dois UUIDs.** Defina `resumeSessionAt` como a *última* entrada da transcrição do
turno que você está mantendo — qualquer que seja o tipo dela — e `resumeDropsTurn` como o UUID do
prompt do turno imediatamente seguinte. Ambos podem ser lidos de
`ClaudeSDK.getSessionMessages(sessionId, cwd)`: encontre a entrada que quer manter e pegue o `uuid()`
do próximo `SessionMessage` cujo `type()` seja `"user"`. Um `AssistantMessage.uuid()` observado ao
vivo também serve como ponto de bifurcação.

Note que, com saída estruturada (`outputFormat`) ou ferramentas MCP que encerram o turno, um turno
mantido termina em entradas *depois* da sua última mensagem do assistente — então, nesses casos,
bifurcar no UUID do assistente é recusado por projeto.

**Lidando com uma recusa.** A recusa chega como uma exceção cuja mensagem contém
`Resume rejected by --resume-drops-turn:`:

```java
try {
    ClaudeSDK.query(prompt, options);
} catch (ClaudeSDKException e) {
    if (String.valueOf(e.getMessage()).contains("Resume rejected by --resume-drops-turn:")) {
        // Deterministic — the transcript is not what we assumed.
        // Clear the fork target and resume plainly; do not retry as-is.
    }
}
```

Trate-a como determinística: repetir a requisição idêntica vai falhar de forma idêntica. Deixe
`resumeDropsTurn` sem definir para manter o comportamento de truncamento antigo, sem validação.

As duas opções também são encaminhadas ao retomar de um `SessionStore`. Veja
[`TruncatingResumeExample`](../../examples/src/main/java/examples/TruncatingResumeExample.java) para
uma demonstração completa e executável.

## Exemplos

### Exemplo 1: listar sessões recentes

```java
List<SDKSessionInfo> sessions = ClaudeSDK.listSessions(null, 5, true);

DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(ZoneId.systemDefault());

for (SDKSessionInfo session : sessions) {
    String time = fmt.format(Instant.ofEpochMilli(session.lastModified()));
    System.out.printf("[%s] %s%n", time, session.summary());
    System.out.printf("  id:  %s%n", session.sessionId());
    if (session.cwd() != null) {
        System.out.printf("  cwd: %s%n", session.cwd());
    }
    if (session.gitBranch() != null) {
        System.out.printf("  git: %s%n", session.gitBranch());
    }
}
```

### Exemplo 2: sessões do projeto atual

```java
Path cwd = Path.of(System.getProperty("user.dir"));
List<SDKSessionInfo> sessions = ClaudeSDK.listSessions(cwd);

System.out.printf("Found %d session(s) for: %s%n", sessions.size(), cwd);
for (SDKSessionInfo session : sessions) {
    String sizeStr = (session.fileSize() != null)
            ? String.format("%.1f KB", session.fileSize() / 1024.0) : "N/A";
    System.out.printf("  %s (%s)%n", session.summary(), sizeStr);
}
```

### Exemplo 3: ler as mensagens da sessão mais recente

```java
List<SDKSessionInfo> sessions = ClaudeSDK.listSessions(null, 1, false);
if (sessions.isEmpty()) {
    System.out.println("No sessions found.");
    return;
}

SDKSessionInfo recent = sessions.get(0);
List<SessionMessage> messages = ClaudeSDK.getSessionMessages(recent.sessionId());

System.out.printf("Session: %s (%d messages)%n",
    recent.summary(), messages.size());

for (SessionMessage msg : messages) {
    System.out.printf("%n[%s]%n", msg.type().toUpperCase());
    if (msg.message() instanceof Map<?, ?> m) {
        Object content = m.get("content");
        if (content instanceof String text) {
            System.out.println(text);
        } else if (content instanceof List<?> blocks && !blocks.isEmpty()) {
            Object first = ((List<?>) blocks).get(0);
            if (first instanceof Map<?, ?> b && b.get("text") instanceof String t) {
                System.out.println(t);
            }
        }
    }
}
```

### Exemplo 4: encontrar uma sessão por palavra-chave do prompt

```java
List<SDKSessionInfo> all = ClaudeSDK.listSessions();

List<SDKSessionInfo> matching = all.stream()
    .filter(s -> s.summary().toLowerCase().contains("refactor"))
    .toList();

System.out.println("Found " + matching.size() + " sessions about refactoring");
```

### Exemplo 5: renomear a sessão mais recente

```java
List<SDKSessionInfo> sessions = ClaudeSDK.listSessions(null, 1, false);
if (!sessions.isEmpty()) {
    String sessionId = sessions.get(0).sessionId();
    ClaudeSDK.renameSession(sessionId, "Important: Production Bug Fix");
    System.out.println("Renamed session " + sessionId);
}
```

### Exemplo 6: marcar sessões por fase do projeto

```java
// Tag a session after a query completes, using the result's session ID
List<Message> messages = ClaudeSDK.query(prompt, options);
for (Message msg : messages) {
    if (msg instanceof ResultMessage result) {
        ClaudeSDK.tagSession(result.sessionId(), "sprint-42");
        break;
    }
}
```

### Exemplo 7: limpar uma tag

```java
// Retrieve a session and clear its tag
List<SDKSessionInfo> sessions = ClaudeSDK.listSessions();
for (SDKSessionInfo session : sessions) {
    if ("old-tag".equals(session.customTitle())) {
        ClaudeSDK.tagSession(session.sessionId(), null);
    }
}
```

## Boas práticas

### Filtre por diretório sempre que possível

```java
// Efficient: scoped to one project
ClaudeSDK.listSessions(Path.of("/my/project"));

// Less efficient: scans all projects
ClaudeSDK.listSessions();
```

### Verifique resultados vazios

```java
List<SDKSessionInfo> sessions = ClaudeSDK.listSessions(dir);
if (sessions.isEmpty()) {
    // No sessions yet — run Claude Code in this directory first
}
```

### Lidando com CLAUDE_CONFIG_DIR ausente

Por padrão, as sessões ficam em `~/.claude/projects/`. Se a variável de ambiente
`CLAUDE_CONFIG_DIR` estiver definida, ela substitui esse local. O SDK respeita essa variável
automaticamente.

### Use limit para evitar conjuntos de resultados enormes

```java
// Return only the 20 most recent
List<SDKSessionInfo> recent = ClaudeSDK.listSessions(null, 20, false);
```

## Veja também

- [Referência da API: ClaudeSDK](./api-claude-sdk.md#métodos-de-histórico-de-sessões) — assinaturas dos métodos
- [Session Store](./feature-session-store.md) — equivalentes apoiados em store externo (`*FromStore`/`*ViaStore`) e a integração de espelhamento na escrita
- [Exemplo de listagem de sessões](../../examples/src/main/java/examples/SessionListingExample.java) — exemplo completo e executável
- [Conversas interativas](./feature-interactive-conversations.md) — gerenciando sessões ativas
