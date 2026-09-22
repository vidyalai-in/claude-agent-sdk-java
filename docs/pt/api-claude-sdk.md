# Referência da API ClaudeSDK

Fachada estática para consultas simples e criação de clients.

> **Sobre esta tradução**: a documentação em inglês é a única autoritativa. Esta tradução pode estar desatualizada em relação ao [original em inglês](../api-claude-sdk.md); em caso de divergência, o inglês prevalece. Os blocos de código são mantidos idênticos ao original e não foram traduzidos.

## Visão geral da classe

```java
public final class ClaudeSDK
```

Classe utilitária que oferece métodos estáticos para as operações comuns do SDK.

## Métodos de consulta

### query(String prompt)

```java
public static List<Message> query(String prompt)
```

Executa uma consulta com as opções padrão.

**Retorna**: `List<Message>`

### query(String prompt, ClaudeAgentOptions options)

```java
public static List<Message> query(
    String prompt,
    ClaudeAgentOptions options
)
```

Executa uma consulta com opções próprias.

**Parâmetros**:
- `prompt` — o prompt
- `options` — opções de configuração

**Retorna**: `List<Message>`

**Lança**:
- `IllegalArgumentException` — se canUseTool e permissionPromptToolName forem ambos definidos
- `CLIConnectionException` — falha de conexão
- `ProcessException` — falha do processo do CLI
- `QueryFailedException` — a execução terminou em um resultado de erro (`error_max_turns`, `error_max_budget_usd`, uma retomada recusada por `resumeDropsTurn`). Carrega as mensagens coletadas antes disso — veja abaixo.

### query(Iterator<Map<String, Object>> messageStream, ClaudeAgentOptions options)

```java
public static List<Message> query(
    Iterator<Map<String, Object>> messageStream,
    ClaudeAgentOptions options
)
```

Executa uma consulta por streaming com várias mensagens.

**Parâmetros**:
- `messageStream` — iterador de dicionários de mensagem
- `options` — opções de configuração

**Retorna**: `List<Message>`

**Lança**: o mesmo que acima, incluindo `QueryFailedException`.

### Resultados de erro e mensagens parciais

O CLI reporta `error_max_turns` e `error_max_budget_usd` emitindo um turno *completo* — mensagens do
assistente mais um `ResultMessage` final com o subtipo, o custo e o uso — e só então saindo com
código diferente de zero, de propósito, para quem o usa no shell.

Esses métodos que coletam precisam ou devolver uma lista ou lançar, então, quando isso acontece, eles
lançam `QueryFailedException` e devolvem nela as mensagens coletadas. Nada se perde:

```java
try {
    List<Message> messages = ClaudeSDK.query(prompt, options);
} catch (QueryFailedException e) {
    ResultMessage result = e.resultMessage();       // the final result, or null
    List<Message> partial = e.partialMessages();    // everything received first
    if (result != null && "error_max_budget_usd".equals(result.subtype())) {
        System.out.printf("Stopped after $%.4f%n", result.totalCostUsd());
    }
}
```

Capture-a sempre que definir `maxTurns` ou `maxBudgetUsd` — atingir um limite que você mesmo
configurou é um desfecho esperado, não uma falha. Quando você quiser a carga útil do resultado
(`subtype()`, `terminalReason()`, `apiErrorStatus()`, …) em vez das mensagens coletadas, leia-a da
causa, que é uma [`ResultException`](./api-exceptions.md#resultexception).

As APIs de streaming em [`ClaudeSDKClient`](./api-claude-sdk-client.md) nunca precisaram dessa
exceção: elas entregam cada mensagem conforme chega, e `receiveResponse()` para no `ResultMessage` —
verifique o `isError()` e o `subtype()` dele diretamente. Veja
[Exceções](./api-exceptions.md#queryfailedexception).

## Métodos de conveniência

### queryForText(String prompt, ClaudeAgentOptions options)

```java
public static String queryForText(
    String prompt,
    ClaudeAgentOptions options
)
```

Obtém apenas o conteúdo de texto das mensagens do assistente.

**Retorna**: `String` — o texto combinado

### queryForResult(String prompt, ClaudeAgentOptions options)

```java
public static ResultMessage queryForResult(
    String prompt,
    ClaudeAgentOptions options
)
```

Obtém apenas a mensagem de resultado.

**Retorna**: `ResultMessage` ou null

## Métodos de fábrica de client

### createClient()

```java
public static ClaudeSDKClient createClient()
```

Cria um client com as opções padrão.

**Retorna**: `ClaudeSDKClient`

### createClient(ClaudeAgentOptions options)

```java
public static ClaudeSDKClient createClient(
    ClaudeAgentOptions options
)
```

Cria um client com opções próprias.

**Retorna**: `ClaudeSDKClient`

## Métodos de fábrica de servidor MCP

### createSdkMcpServer(String name, List<SdkMcpTool<?>> tools)

```java
public static McpSdkServerConfig createSdkMcpServer(
    String name,
    List<SdkMcpTool<?>> tools
)
```

Cria um servidor MCP do SDK a partir de uma lista de ferramentas.

**Parâmetros**:
- `name` — nome do servidor
- `tools` — lista de ferramentas

**Retorna**: `McpSdkServerConfig`

### createSdkMcpServer(String name, String version, List<SdkMcpTool<?>> tools)

```java
public static McpSdkServerConfig createSdkMcpServer(
    String name,
    String version,
    List<SdkMcpTool<?>> tools
)
```

Cria um servidor MCP do SDK com versão.

### createSdkMcpServer(String name, Object instance)

```java
public static McpSdkMcpServer createSdkMcpServer(
    String name,
    Object instance
)
```

Cria um servidor MCP do SDK a partir de métodos anotados com @Tool.

**Parâmetros**:
- `name` — nome do servidor
- `instance` — objeto com métodos @Tool

**Retorna**: `McpSdkServerConfig`

## Métodos de histórico de sessões

### listSessions()

```java
public static List<SDKSessionInfo> listSessions()
```

Lista todas as sessões de todos os projetos, das mais recentemente modificadas para as mais antigas.
Lê de `~/.claude/projects/` sem interpretar os arquivos JSONL por completo — apenas os primeiros e os
últimos 64 KB de cada um.

**Retorna**: `List<SDKSessionInfo>` ordenada por data de modificação decrescente

### listSessions(Path directory)

```java
public static List<SDKSessionInfo> listSessions(Path directory)
```

Lista as sessões de um diretório de projeto específico.

**Parâmetros**:
- `directory` — o diretório de trabalho do projeto usado como filtro

**Retorna**: `List<SDKSessionInfo>`

### listSessions(Path directory, Integer limit, boolean includeWorktrees)

```java
public static List<SDKSessionInfo> listSessions(
    Path directory,
    Integer limit,
    boolean includeWorktrees
)
```

Lista sessões com controle total.

**Parâmetros**:
- `directory` — diretório de projeto usado como filtro (null = todos os projetos)
- `limit` — máximo de sessões a devolver (null = sem limite)
- `includeWorktrees` — se inclui os diretórios de worktree do git

**Retorna**: `List<SDKSessionInfo>`

### getSessionInfo(String sessionId)

```java
@Nullable
public static SDKSessionInfo getSessionInfo(String sessionId)
```

Consulta uma sessão pelo ID. Busca em todos os diretórios de projeto sob `~/.claude/projects/`. Sem
varredura O(n) de diretórios — lê apenas o arquivo da sessão procurada.

**Parâmetros**:
- `sessionId` — UUID da sessão a consultar

**Retorna**: o `SDKSessionInfo` da sessão, ou `null` se não for encontrada, for uma sessão lateral ou
não tiver resumo extraível

### getSessionInfo(String sessionId, Path directory)

```java
@Nullable
public static SDKSessionInfo getSessionInfo(
    String sessionId,
    Path directory
)
```

Consulta uma sessão pelo ID dentro de um diretório de projeto específico.

**Parâmetros**:
- `sessionId` — UUID da sessão a consultar
- `directory` — diretório de trabalho do projeto onde buscar

**Retorna**: `SDKSessionInfo` ou `null`

### getSessionMessages(String sessionId)

```java
public static List<SessionMessage> getSessionMessages(String sessionId)
```

Devolve todas as mensagens da conversa de uma sessão. Busca em todos os diretórios de projeto.

**Parâmetros**:
- `sessionId` — UUID da sessão

**Retorna**: `List<SessionMessage>` na ordem da conversa

### getSessionMessages(String sessionId, Path directory)

```java
public static List<SessionMessage> getSessionMessages(
    String sessionId,
    Path directory
)
```

Devolve as mensagens de uma sessão em um projeto específico.

**Parâmetros**:
- `sessionId` — UUID da sessão
- `directory` — diretório de trabalho do projeto onde buscar

**Retorna**: `List<SessionMessage>`

### getSessionMessages(String sessionId, Path directory, Integer limit, int offset)

```java
public static List<SessionMessage> getSessionMessages(
    String sessionId,
    Path directory,
    Integer limit,
    int offset
)
```

Devolve mensagens com controle total sobre a filtragem.

**Parâmetros**:
- `sessionId` — UUID da sessão
- `directory` — diretório de projeto onde buscar (null = todos os projetos)
- `limit` — máximo de mensagens a devolver (null = sem limite)
- `offset` — número de mensagens a pular do início

**Retorna**: `List<SessionMessage>`

## Métodos de transcrição de subagentes

Quando uma sessão cria subagentes (pela ferramenta `Task` ou por definições programáticas de agente),
a transcrição de cada subagente é escrita em
`~/.claude/projects/<project>/<sessionId>/subagents/agent-<agentId>.jsonl`. Esses arquivos também
podem ficar em diretórios aninhados, como `subagents/workflows/<runId>/`.

### listSubagents(String sessionId)

```java
public static List<String> listSubagents(String sessionId)
```

Lista os IDs de subagente de uma sessão varrendo o diretório `subagents/` dela em todos os diretórios
de projeto.

**Parâmetros**:
- `sessionId` — UUID da sessão pai

**Retorna**: `List<String>` com os IDs de subagente. Vazia quando a sessão não é encontrada, o
`sessionId` não é um UUID válido ou a sessão não tem subagentes.

### listSubagents(String sessionId, Path directory)

```java
public static List<String> listSubagents(String sessionId, Path directory)
```

Lista os IDs de subagente limitando a busca a um diretório de projeto.

**Parâmetros**:
- `sessionId` — UUID da sessão pai
- `directory` — diretório de trabalho do projeto onde encontrar a sessão

### getSubagentMessages(String sessionId, String agentId)

```java
public static List<SessionMessage> getSubagentMessages(
    String sessionId,
    String agentId
)
```

Lê as mensagens user/assistant de um subagente a partir da sua transcrição JSONL. Percorre os elos
`parentUuid` para reconstruir a cadeia. O `parentToolUseId` de cada mensagem é o `tool_use` do Agent
na sessão pai que criou esse subagente, e `parentAgentId` nomeia o subagente criador nos casos
aninhados; ambos vêm do arquivo lateral `agent-<agentId>.meta.json` ao lado da transcrição, já que as
linhas da própria transcrição não os registram, e ambos são nulos quando esse arquivo está ausente ou
inutilizável.

**Parâmetros**:
- `sessionId` — UUID da sessão pai
- `agentId` — ID do subagente (como devolvido por `listSubagents`)

**Retorna**: `List<SessionMessage>` em ordem cronológica. Vazia quando a sessão ou o subagente não são
encontrados, o `sessionId` não é um UUID válido, ou a transcrição não contém mensagens
user/assistant.

### getSubagentMessages(String sessionId, String agentId, Path directory)

```java
public static List<SessionMessage> getSubagentMessages(
    String sessionId,
    String agentId,
    Path directory
)
```

Lê as mensagens de um subagente limitando a busca a um diretório de projeto.

### getSubagentMessages(String sessionId, String agentId, Path directory, Integer limit, int offset)

```java
public static List<SessionMessage> getSubagentMessages(
    String sessionId,
    String agentId,
    @Nullable Path directory,
    @Nullable Integer limit,
    int offset
)
```

Lê as mensagens de subagente com controle total sobre filtragem e paginação.

**Parâmetros**:
- `sessionId` — UUID da sessão pai
- `agentId` — ID do subagente
- `directory` — diretório de projeto onde buscar (null = todos os projetos)
- `limit` — máximo de mensagens a devolver (null ou `0` = sem limite)
- `offset` — número de mensagens a pular do início

## Métodos de mutação de sessão

### renameSession(String sessionId, String title)

```java
public static void renameSession(
    String sessionId,
    String title
) throws IOException
```

Renomeia uma sessão acrescentando uma entrada de título personalizado. A renomeação mais recente
vence. Busca em todos os diretórios de projeto.

**Parâmetros**:
- `sessionId` — UUID da sessão a renomear
- `title` — novo título da sessão (com os espaços das pontas removidos)

**Lança**:
- `IllegalArgumentException` — se `sessionId` não for um UUID válido ou `title` estiver vazio
- `FileNotFoundException` — se o arquivo da sessão não for encontrado
- `IOException` — se a escrita falhar

### renameSession(String sessionId, String title, Path directory)

```java
public static void renameSession(
    String sessionId,
    String title,
    Path directory
) throws IOException
```

Renomeia uma sessão limitando a busca a um diretório de projeto.

**Parâmetros**:
- `sessionId` — UUID da sessão a renomear
- `title` — novo título da sessão
- `directory` — diretório de trabalho do projeto onde buscar

### tagSession(String sessionId, String tag)

```java
public static void tagSession(
    String sessionId,
    @Nullable String tag
) throws IOException
```

Marca uma sessão com uma tag. Passe `null` para limpar uma tag existente. As tags passam por saneamento
Unicode antes de serem gravadas. Busca em todos os diretórios de projeto.

**Parâmetros**:
- `sessionId` — UUID da sessão a marcar
- `tag` — texto da tag, ou `null` para limpar. Não pode ficar vazio após o saneamento (a não ser que
  seja `null`).

**Lança**:
- `IllegalArgumentException` — se `sessionId` for inválido ou `tag` ficar vazia após o saneamento
- `FileNotFoundException` — se o arquivo da sessão não for encontrado
- `IOException` — se a escrita falhar

### tagSession(String sessionId, String tag, Path directory)

```java
public static void tagSession(
    String sessionId,
    @Nullable String tag,
    Path directory
) throws IOException
```

Marca uma sessão limitando a busca a um diretório de projeto.

**Parâmetros**:
- `sessionId` — UUID da sessão a marcar
- `tag` — texto da tag, ou `null` para limpar
- `directory` — diretório de trabalho do projeto onde buscar

### deleteSession(String sessionId)

```java
public static void deleteSession(String sessionId) throws IOException
```

Exclui uma sessão permanentemente removendo seu arquivo JSONL. Também remove recursivamente o
diretório irmão `<sessionId>/` com as transcrições de subagentes (se existir). Quem quiser exclusão
lógica deve usar `tagSession(id, "__hidden")` e filtrar na listagem.

**Parâmetros**:
- `sessionId` — UUID da sessão a excluir

**Lança**:
- `IllegalArgumentException` — se `sessionId` não for um UUID válido
- `FileNotFoundException` — se o arquivo da sessão não for encontrado
- `IOException` — se a exclusão falhar (a limpeza do diretório de subagentes é de melhor esforço e
  nunca derruba a chamada)

### deleteSession(String sessionId, Path directory)

```java
public static void deleteSession(
    String sessionId,
    Path directory
) throws IOException
```

Exclui uma sessão limitando a busca a um diretório de projeto.

### forkSession(String sessionId)

```java
public static ForkSessionResult forkSession(String sessionId) throws IOException
```

Bifurca uma sessão em um novo ramo com UUIDs novos.

**Retorna**: `ForkSessionResult` com o UUID da nova sessão

**Lança**:
- `IllegalArgumentException` — se `sessionId` não for um UUID válido
- `FileNotFoundException` — se o arquivo da sessão não for encontrado
- `IOException` — se a bifurcação falhar

### forkSession(String sessionId, Path directory)

```java
public static ForkSessionResult forkSession(
    String sessionId,
    Path directory
) throws IOException
```

Bifurca uma sessão limitando a busca a um diretório de projeto.

### forkSession(String sessionId, Path directory, String upToMessageId, String title)

```java
public static ForkSessionResult forkSession(
    String sessionId,
    @Nullable Path directory,
    @Nullable String upToMessageId,
    @Nullable String title
) throws IOException
```

Bifurca uma sessão com ponto de truncamento opcional e título personalizado.

**Parâmetros**:
- `sessionId` — UUID da sessão de origem
- `directory` — diretório de projeto (null busca em todos)
- `upToMessageId` — corta a transcrição neste UUID de mensagem (inclusive); null copia tudo
- `title` — título personalizado da bifurcação; null deriva do original + " (fork)"

### listSessions(Path directory, Integer limit, int offset, boolean includeWorktrees)

```java
public static List<SDKSessionInfo> listSessions(
    Path directory,
    Integer limit,
    int offset,
    boolean includeWorktrees
)
```

Lista sessões com suporte a paginação por offset.

**Parâmetros**:
- `directory` — diretório de projeto (null para todos os projetos)
- `limit` — número máximo de sessões a devolver
- `offset` — número de sessões a pular (para paginação)
- `includeWorktrees` — incluir sessões de worktrees do git

## Métodos apoiados em SessionStore

Estes métodos leem/escrevem sessões através de um adaptador `SessionStore` em vez do sistema de
arquivos local `~/.claude/projects/`. Veja o [guia do Session Store](./feature-session-store.md) para
a documentação completa do recurso.

### projectKeyForDirectory(Path directory)

```java
public static String projectKeyForDirectory(@Nullable Path directory)
```

Calcula o `project_key` do `SessionStore` para um diretório usando o mesmo realpath + normalização NFC
+ saneamento com hash djb2 que o CLI usa. Assume o diretório de trabalho atual quando
`directory == null`.

**Retorna**: a string de chave de projeto saneada, adequada para `SessionKey.projectKey()`.

### listSessionsFromStore(SessionStore, Path, Integer, int)

```java
public static List<SDKSessionInfo> listSessionsFromStore(
    SessionStore sessionStore,
    @Nullable Path directory,
    @Nullable Integer limit,
    int offset)
```

Lista sessões de um `SessionStore`. Usa o caminho rápido quando
`store.implementsListSessionSummaries()` devolve `true`; caso contrário, recai em carregamentos por
sessão com concorrência limitada (16).

**Lança**: `IllegalStateException` se o store não implementar nem `listSessionSummaries()` nem
`listSessions()`.

### getSessionInfoFromStore(SessionStore, String, Path)

```java
public static @Nullable SDKSessionInfo getSessionInfoFromStore(
    SessionStore sessionStore, String sessionId, @Nullable Path directory)
```

Lê os metadados de uma sessão a partir de um store. Devolve `null` para UUIDs inválidos, sessões
ausentes, sessões laterais ou sessões sem resumo extraível.

### getSessionMessagesFromStore(SessionStore, String, Path, Integer, int)

```java
public static List<SessionMessage> getSessionMessagesFromStore(
    SessionStore sessionStore, String sessionId,
    @Nullable Path directory, @Nullable Integer limit, int offset)
```

Lê a transcrição completa da conversa de uma sessão a partir de um store. Devolve uma lista vazia para
UUIDs inválidos ou sessões ausentes.

### listSubagentsFromStore(SessionStore, String, Path)

```java
public static List<String> listSubagentsFromStore(
    SessionStore sessionStore, String sessionId, @Nullable Path directory)
```

Lista os IDs de subagente de uma sessão enumerando as subchaves do store sob
`subagents/agent-<id>`.

**Lança**: `IllegalStateException` se o store não implementar `listSubkeys()`.

### getSubagentMessagesFromStore(SessionStore, String, String, Path, Integer, int)

```java
public static List<SessionMessage> getSubagentMessagesFromStore(
    SessionStore sessionStore, String sessionId, String agentId,
    @Nullable Path directory, @Nullable Integer limit, int offset)
```

Lê a transcrição de um subagente a partir de um store. A entrada sintética `agent_metadata` não é
devolvida como mensagem; ela é lida para preencher o `parentToolUseId` de cada mensagem (o `tool_use`
do Agent que criou o subagente) e o `parentAgentId` (o subagente criador, no caso de subagentes
aninhados). Ambos são nulos quando essa entrada está ausente ou traz ids que não são strings.

### renameSessionViaStore(SessionStore, String, String, Path)

```java
public static void renameSessionViaStore(
    SessionStore sessionStore, String sessionId, String title,
    @Nullable Path directory)
```

Acrescenta uma entrada `custom-title` à sessão no store.

**Lança**: `IllegalArgumentException` se `sessionId` não for um UUID válido ou se `title` estiver
vazio ou só com espaços.

### tagSessionViaStore(SessionStore, String, String, Path)

```java
public static void tagSessionViaStore(
    SessionStore sessionStore, String sessionId, @Nullable String tag,
    @Nullable Path directory)
```

Acrescenta uma entrada `tag`. Passe `null` em `tag` para limpar; as tags são saneadas em Unicode antes
de serem gravadas.

**Lança**: `IllegalArgumentException` para UUID inválido ou tag que fica vazia após o saneamento.

### deleteSessionViaStore(SessionStore, String, Path)

```java
public static void deleteSessionViaStore(
    SessionStore sessionStore, String sessionId, @Nullable Path directory)
```

Exclui uma sessão do store. Não faz nada se o store não implementar `delete()` (apropriado para
back-ends WORM/somente-anexação).

### forkSessionViaStore(SessionStore, String, Path, String, String)

```java
public static ForkSessionResult forkSessionViaStore(
    SessionStore sessionStore, String sessionId, @Nullable Path directory,
    @Nullable String upToMessageId, @Nullable String title) throws java.io.IOException
```

Bifurca uma sessão em um novo ramo com UUIDs novos, através do store. Executa a mesma transformação de
remapeamento de UUID do fork em disco — uma cópia na camada de armazenamento NÃO basta.

**Lança**: `IllegalArgumentException` para UUIDs inválidos; `FileNotFoundException` se a sessão de
origem não for encontrada no store.

### importSessionToStore(String, SessionStore, Path)

```java
public static void importSessionToStore(
    String sessionId, SessionStore sessionStore, @Nullable Path directory)
    throws java.io.IOException
```

Reproduz uma transcrição de sessão do disco local em um `SessionStore`. Sobrecarga de conveniência que
usa `includeSubagents=true` e o tamanho de lote padrão
(`TranscriptMirrorBatcher.MAX_PENDING_ENTRIES = 500`).

### importSessionToStore(String, SessionStore, Path, boolean, int)

```java
public static void importSessionToStore(
    String sessionId, SessionStore sessionStore, @Nullable Path directory,
    boolean includeSubagents, int batchSize) throws java.io.IOException
```

Versão completa com opções explícitas.

**Parâmetros**:
- `includeSubagents` — importa recursivamente `<sessionDir>/subagents/**/*.jsonl` e os arquivos
  laterais `.meta.json`
- `batchSize` — entradas por chamada de `store.append()`; valores `≤ 0` usam o padrão

**Lança**: `IllegalArgumentException` para UUID inválido; `NoSuchFileException` se o arquivo da sessão
não for encontrado.

## Método de versão

### getVersion()

```java
public static String getVersion()
```

Obtém a string de versão do SDK.

**Retorna**: a versão (por exemplo, "0.1.3-SNAPSHOT")

## Veja também
- [Guia de consultas simples](./feature-simple-queries.md)
- [Guia de servidores MCP](./feature-mcp-servers.md)
- [Guia de histórico de sessões](./feature-session-history.md)
- [Guia do Session Store](./feature-session-store.md)
