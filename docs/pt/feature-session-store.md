# Session Store (espelhamento externo de transcrições)

Espelhe as transcrições de sessão do Claude Code para um store externo (S3, Postgres, Redis, seu
próprio back-end) para que as sessões durem além do disco local e possam ser retomadas de qualquer
lugar.

> **Sobre esta tradução**: a documentação em inglês é a única autoritativa. Esta tradução pode estar desatualizada em relação ao [original em inglês](../feature-session-store.md); em caso de divergência, o inglês prevalece. Os blocos de código são mantidos idênticos ao original e não foram traduzidos.

## Sumário
- [Visão geral](#visão-geral)
- [Quando usar um SessionStore](#quando-usar-um-sessionstore)
- [Início rápido](#início-rápido)
- [A interface SessionStore](#a-interface-sessionstore)
- [API síncrona vs. assíncrona](#api-síncrona-vs-assíncrona)
- [Configurando o executor assíncrono](#configurando-o-executor-assíncrono)
- [Adaptadores de referência inclusos](#adaptadores-de-referência-inclusos)
- [APIs de leitura via SessionStore](#apis-de-leitura-via-sessionstore)
- [Mutações via SessionStore](#mutações-via-sessionstore)
- [Retomar a partir de um store](#retomar-a-partir-de-um-store)
- [Erros de espelhamento](#erros-de-espelhamento)
- [Modo de gravação (batched vs eager)](#modo-de-gravação-batched-vs-eager)
- [Importando sessões locais para um store](#importando-sessões-locais-para-um-store)
- [Harness de testes de conformidade](#harness-de-testes-de-conformidade)
- [Peças internas de runtime](#peças-internas-de-runtime)
- [Boas práticas](#boas-práticas)
- [Referência da API](#referência-da-api)

## Visão geral

Por padrão, o CLI do Claude Code grava cada sessão como um arquivo JSONL em `~/.claude/projects/`. O
SDK pode, além disso, espelhar cada linha da transcrição para um store externo à sua escolha — útil
para:

- **Sessões duradouras de longa execução** — o disco local é efêmero em plataformas serverless / com
  autoescalonamento.
- **Retomada em vários hosts** — comece uma sessão no host A e retome no host B.
- **Retenção para auditoria / conformidade** — aplique suas próprias políticas de TTL (ciclo de vida
  do S3, partições do Postgres, TTL do Redis).
- **Implantações multi-inquilino** — delimite transcrições por `project_key` para isolar inquilinos.

O SDK traz:

- A interface `SessionStore` (variantes síncrona + assíncrona)
- O adaptador de referência `InMemorySessionStore`
- Integração de espelhamento no runtime (transparente — defina `sessionStore` nas options e o SDK
  cuida do resto)
- `importSessionToStore()` para migrar sessões já existentes em disco

A transcrição em disco local é sempre escrita primeiro; o espelhamento é um caminho secundário de
durabilidade. Falhas de espelhamento nunca bloqueiam uma sessão — elas aparecem como um
`MirrorErrorMessage` não fatal.

## Quando usar um SessionStore

| Cenário | Recomendação |
|---|---|
| CLI de usuário único numa estação de trabalho | Não se incomode — o JSONL local basta |
| Servidor de longa execução, sessões atravessam requisições | Use um `SessionStore` |
| Conformidade / retenção regulada | Use um `SessionStore` com políticas nativas de ciclo de vida |
| Frota com vários hosts / autoescalonamento na nuvem | Use um `SessionStore` para que qualquer host possa retomar |
| Auditoria / replay em muitas sessões | Use um `SessionStore` para consultas centralizadas |

## Início rápido

```java
import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.types.session.InMemorySessionStore;

InMemorySessionStore store = new InMemorySessionStore();

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .sessionStore(store)            // mirror every transcript line here
    .build();

ClaudeSDK.query("Hello!", options);
// All transcript entries from this turn are now in `store`
```

O SDK acrescenta `--session-mirror` à invocação do CLI, separa os quadros `transcript_mirror` do
stdout do CLI e os encaminha em lotes para `store.append(...)`.

Para retomar a partir do store em outro host:

```java
ClaudeAgentOptions resumeOptions = ClaudeAgentOptions.builder()
    .sessionStore(store)
    .resume("previous-session-uuid")
    .build();

ClaudeSDK.query("Continue where we left off", resumeOptions);
```

O SDK carrega a transcrição armazenada em um `CLAUDE_CONFIG_DIR` temporário para que o subprocesso do
CLI retome a conversa.

## A interface SessionStore

`in.vidyalai.claude.sdk.types.session.SessionStore` é uma interface Java. Dois métodos são
obrigatórios; os demais são opcionais, com sinalizadores `implements*()` para que quem chama detecte
o que há suporte sem `instanceof`.

### Métodos obrigatórios

```java
void append(SessionKey key, List<SessionStoreEntry> entries);

@Nullable
List<SessionStoreEntry> load(SessionKey key);
```

- `append` — espelha um lote de entradas da transcrição. É chamado DEPOIS que a escrita no disco
  local tem sucesso, então a durabilidade já está garantida localmente. Adaptadores devem tratar
  `entry.uuid()` como chave de idempotência (entradas sem `uuid`, como título personalizado ou tag,
  devem ser anexadas sem desduplicação).
- `load` — devolve todas as entradas de uma chave (profundamente iguais ao que foi anexado; não é
  preciso igualdade byte a byte). Devolve `null` para uma chave nunca escrita.

### Métodos opcionais (por padrão lançam `UnsupportedOperationException`)

```java
default List<SessionStoreListEntry> listSessions(String projectKey);
default List<SessionSummaryEntry> listSessionSummaries(String projectKey);
default void delete(SessionKey key);
default List<String> listSubkeys(SessionListSubkeysKey key);
```

### Sondagens de capacidade

```java
default boolean implementsListSessions() { return false; }
default boolean implementsListSessionSummaries() { return false; }
default boolean implementsDelete() { return false; }
default boolean implementsListSubkeys() { return false; }
```

Sobrescreva-os para devolver `true` quando implementar o método opcional correspondente. O SDK usa
essas sondagens (e não `try/catch`) para decidir se chama o método opcional.

### Tipos de chave

```java
public record SessionKey(
    String projectKey,             // caller-defined scope (default: sanitized cwd)
    String sessionId,              // session UUID
    @Nullable String subpath        // null for main; "subagents/agent-x" for subagent
);

public record SessionListSubkeysKey(String projectKey, String sessionId);

public record SessionStoreListEntry(String sessionId, long mtime);

public record SessionSummaryEntry(String sessionId, long mtime, Map<String, Object> data);
```

`SessionStoreEntry` é um invólucro fino sobre `Map<String, Object>` que exige um campo `type`; todo o
resto passa adiante de forma opaca:

```java
SessionStoreEntry entry = SessionStoreEntry.of(Map.of(
    "type", "user",
    "uuid", "u1",
    "message", Map.of(
        "content", List.of(Map.of("type", "text", "text", "Hello"))),
    "timestamp", "2026-04-27T00:00:00Z"
));

entry.type();      // "user"
entry.uuid();      // "u1"
entry.timestamp(); // "2026-04-27T00:00:00Z"
entry.<String>get("custom_field"); // typed convenience accessor
entry.asMap();     // unmodifiable map view
```

## API síncrona vs. assíncrona

Todo método de `SessionStore` tem variantes síncrona e `*Async` (que devolvem `CompletableFuture`):

```java
// Sync (required to implement; or default to *Async().join() if you only override async)
void append(SessionKey key, List<SessionStoreEntry> entries);

// Async with default executor
default CompletableFuture<Void> appendAsync(SessionKey key, List<SessionStoreEntry> entries);

// Async with explicit executor
default CompletableFuture<Void> appendAsync(SessionKey key, List<SessionStoreEntry> entries, Executor executor);
```

O batcher interno de espelhamento e o materializador de retomada chamam as variantes `*Async` — então
adaptadores com clientes não bloqueantes nativos (AWS SDK v2 async, R2DBC, Lettuce reactive) podem
sobrescrever os métodos `*Async` e preservar o paralelismo de ponta a ponta.

### Delegação padrão

- Se você sobrescrever apenas os métodos **síncronos** (o caso típico de JDBC, Jedis, S3 SDK v1
  bloqueante), os padrões `*Async` envolvem suas chamadas síncronas em
  `CompletableFuture.supplyAsync(...)` no executor configurado (uma thread por tarefa; virtual no
  Java 21+).
- Se você sobrescrever apenas os métodos **assíncronos** (recomendado para AWS SDK v2 async / Lettuce
  reactive / R2DBC), implemente o método síncrono como `appendAsync(key, entries).join()` para que
  os dois pontos de chamada funcionem.

```java
public class S3AsyncStore implements SessionStore {
    private final S3AsyncClient s3;

    @Override
    public void append(SessionKey key, List<SessionStoreEntry> entries) {
        appendAsync(key, entries).join();
    }

    @Override
    public CompletableFuture<Void> appendAsync(SessionKey key, List<SessionStoreEntry> entries) {
        // Native async — no thread hop
        return s3.putObject(/* ... */).thenApply(r -> null);
    }

    @Override
    public List<SessionStoreEntry> load(SessionKey key) { /* ... */ }
}
```

## Configurando o executor assíncrono

Por padrão, os invólucros assíncronos rodam **uma tarefa por thread**, em threads chamadas
`session-store-<n>`. No Java 21+ são threads virtuais; no Java 17-20 o SDK recai em threads de
plataforma daemon de um pool em cache ilimitado. O SDK mira o Java 17 e escolhe a melhor opção em
tempo de execução, então você ganha threads virtuais sem exigi-las.

Você pode substituir isso uma vez na inicialização, via `SessionStoreExecutor`:

```java
import in.vidyalai.claude.sdk.types.session.SessionStoreExecutor;

// Your own virtual-thread executor (needs Java 21+ in *your* project)
ExecutorService mine = Executors.newThreadPerTaskExecutor(
    Thread.ofVirtual().name("my-store-", 0).factory());
SessionStoreExecutor.setDefault(mine);

// Or a bounded platform-thread pool
SessionStoreExecutor.setDefault(Executors.newFixedThreadPool(8));

// Reset to the built-in default
SessionStoreExecutor.reset();
```

Também é possível passar um executor por chamada:

```java
store.appendAsync(key, entries, customExecutor)
```

O executor configurado é usado por todo padrão `*Async` que não receba um executor explícito.
Adaptadores que sobrescrevem `*Async` diretamente ignoram isso por completo — o executor só se aplica
ao caminho de conversão de síncrono para assíncrono.

## Adaptadores de referência inclusos

### `InMemorySessionStore`

Uma implementação em memória, segura entre threads, adequada a testes e protótipos:

```java
import in.vidyalai.claude.sdk.types.session.InMemorySessionStore;

InMemorySessionStore store = new InMemorySessionStore();

// All optional methods implemented
store.implementsListSessions();         // true
store.implementsListSessionSummaries(); // true
store.implementsDelete();               // true
store.implementsListSubkeys();          // true

// Test helpers
store.size();             // count of main-transcript sessions
store.snapshotSummaries();// LinkedHashMap snapshot of summary sidecars
store.clear();            // wipe everything
```

Mantém um arquivo lateral incremental de `SessionSummaryEntry` dentro de `append()`, de modo que
`listSessionSummaries()` roda em O(1) — nunca relê transcrições.

### Helper de caminho → chave

`InMemorySessionStore.filePathToSessionKey(filePath, projectsDir)` é um helper estático que mapeia um
caminho de transcrição em disco de volta para uma `SessionKey`:

```java
SessionKey k = InMemorySessionStore.filePathToSessionKey(
    "/home/u/.claude/projects/myproj/abc-123.jsonl",
    "/home/u/.claude/projects");
// k = SessionKey("myproj", "abc-123", null)

SessionKey sub = InMemorySessionStore.filePathToSessionKey(
    "/home/u/.claude/projects/myproj/abc-123/subagents/agent-x.jsonl",
    "/home/u/.claude/projects");
// sub = SessionKey("myproj", "abc-123", "subagents/agent-x")
```

Devolve `null` para caminhos fora de `projectsDir` ou com layouts desconhecidos. É usado
internamente pelo batcher de espelhamento e exposto para implementações de adaptador que precisem do
mesmo mapeamento.

### Adaptadores de produção (S3, Redis, Postgres, …)

O SDK não traz adaptadores de produção — eles dependem de bibliotecas cliente pesadas (AWS SDK,
Lettuce, JDBC, R2DBC) que não queremos como dependências transitivas. Implemente o seu e valide com
`SessionStoreConformance` (veja abaixo). O protocolo é pequeno e estável.

## APIs de leitura via SessionStore

Leia sessões direto de um store, sem envolver o CLI:

```java
import in.vidyalai.claude.sdk.ClaudeSDK;

// List all sessions in the store for the current cwd
List<SDKSessionInfo> sessions =
    ClaudeSDK.listSessionsFromStore(store, /* directory */ null, /* limit */ 50, /* offset */ 0);

// Single-session metadata
SDKSessionInfo info = ClaudeSDK.getSessionInfoFromStore(store, sessionId, null);

// Full transcript
List<SessionMessage> messages =
    ClaudeSDK.getSessionMessagesFromStore(store, sessionId, null, null, 0);

// Subagent transcript discovery + reading
List<String> agentIds = ClaudeSDK.listSubagentsFromStore(store, sessionId, null);
List<SessionMessage> subAgent =
    ClaudeSDK.getSubagentMessagesFromStore(store, sessionId, agentIds.get(0), null, null, 0);

// Each message is attributed to the Agent tool_use that spawned the subagent,
// read from the mirrored `agent_metadata` entry (null if it is absent).
String spawnedBy = subAgent.get(0).parentToolUseId();
```

`listSessionsFromStore` tem um caminho rápido quando o store implementa `listSessionSummaries`: uma
chamada em lote de resumos mais uma enumeração barata de `listSessions` para preencher as lacunas de
sessões com arquivos laterais ausentes ou desatualizados. Quando `listSessionSummaries` não está
implementado, recai em um `loadAsync()` por sessão, **limitado a 16 chamadas concorrentes** (igual ao
SDK Python), para que listagens de projetos grandes não esgotem os pools de conexão do adaptador.

Se `listSessions` e `listSessionSummaries` estiverem ambos sem implementação, a chamada lança
`IllegalStateException`. Falhas de `loadAsync` do adaptador rebaixam linhas individuais a entradas de
resumo vazio em vez de derrubar a lista inteira.

## Mutações via SessionStore

Mesmo formato das APIs de mutação em disco, mas escrevendo no store:

```java
ClaudeSDK.renameSessionViaStore(store, sessionId, "My New Title", null);
ClaudeSDK.tagSessionViaStore(store, sessionId, "important", null);
ClaudeSDK.tagSessionViaStore(store, sessionId, null, null);   // clear tag
ClaudeSDK.deleteSessionViaStore(store, sessionId, null);

ForkSessionResult fork = ClaudeSDK.forkSessionViaStore(
    store, sessionId, /* directory */ null,
    /* upToMessageId */ null,                  // null copies full transcript
    /* title */ "My Fork");
```

Por dentro:

- `renameSessionViaStore` acrescenta uma entrada `custom-title`.
- `tagSessionViaStore` acrescenta uma entrada `tag`; `null` limpa via string vazia.
- `deleteSessionViaStore` não faz nada se o store não implementar `delete()` (apropriado para
  back-ends WORM/somente-anexação, como S3 puro).
- `forkSessionViaStore` executa a mesma transformação de remapeamento de UUID do fork em disco
  (`SessionMutations.buildForkLines` compartilhado) — uma cópia na camada de armazenamento NÃO basta.

`listSubagentsFromStore` exige `listSubkeys()` e lança `IllegalStateException` caso contrário.

## Retomar a partir de um store

Quando `options.sessionStore` é definido junto com `options.resume` (ou
`options.continueConversation`), o SDK:

1. Chama `store.load()` para o ID de sessão pedido (ou, no caso de `continueConversation`, escolhe a
   sessão não lateral modificada mais recentemente via `store.listSessions()`).
2. Escreve as entradas em um diretório temporário organizado exatamente como `~/.claude/`.
3. Semeia o diretório temporário a partir do seu diretório de configuração real, para que o
   subprocesso consiga autenticar e se comportar como de costume — `.credentials.json` (com
   `refreshToken` removido), `.claude.json` e seus `settings.json` / `cowork_settings.json` de
   usuário. Veja [O que é semeado](#o-que-é-semeado).
4. Materializa quaisquer transcrições de subagente e arquivos laterais `.meta.json` a partir do store
   (quando `listSubkeys` está implementado).
5. Cria o CLI com `CLAUDE_CONFIG_DIR=<temp dir>` para que ele retome do disco local como sempre.
6. Limpa o diretório temporário ao desconectar (com nova tentativa em travas transitórias de
   antivírus/indexador do Windows).

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .sessionStore(store)
    .resume(previousSessionId)
    .loadTimeoutMs(60_000)        // per-call timeout for store.load() / listSubkeys()
    .build();
```

A opção `loadTimeoutMs` (padrão 60 000) limita cada chamada individual ao store durante a
materialização; se um adaptador não responder dentro dessa janela, a consulta falha rápido com um
erro claro em vez de deixar o iterador pendurado.

### O que é semeado

Como o subprocesso roda sob um `CLAUDE_CONFIG_DIR` redirecionado, de outra forma ele não veria
nenhuma das suas configurações. O SDK copia quatro arquivos do diretório de configuração de quem
chama — resolvido como `options.env["CLAUDE_CONFIG_DIR"]` → ambiente do processo → `~/.claude` (o
`.claude.json` fica em `$CLAUDE_CONFIG_DIR/.claude.json` quando definido, senão em `~/.claude.json`,
*não* em `~/.claude/.claude.json`):

| Arquivo | Por que importa |
|------|----------------|
| `.credentials.json` | Credenciais OAuth, com `claudeAiOauth.refreshToken` removido |
| `.claude.json` | Estado do CLI no nível do usuário |
| `settings.json` | `apiKeyHelper`, além dos seus `env`, `hooks` e `permissions` |
| `cowork_settings.json` | O nome alternativo de arquivo de configurações lido no modo cowork-plugins |

Semear `settings.json` importa mais do que parece: `apiKeyHelper` é um quarto mecanismo de
autenticação, ao lado do arquivo de credenciais, do Keychain do macOS e das variáveis de ambiente.
Antes da v0.1.23 ele não era copiado, então um host que se autenticava só pelo `apiKeyHelper` falhava
com **"Not logged in"** no instante em que retomava de um store.

Os dois arquivos de configurações passam por uma transformação que remove apenas as chaves que se
comportam mal sob um diretório de configuração redirecionado:

- `enabledPlugins` e `extraKnownMarketplaces` — eles se reconciliam com o cache temporário de plugins,
  sempre vazio, e instalariam pela rede todos os marketplaces declarados a cada retomada.
- `env.CLAUDE_CONFIG_DIR` — apontaria as leituras de configuração do subprocesso de volta para fora do
  diretório temporário.

Todo o resto é preservado. Um BOM UTF-8 (o PowerShell escreve um) é tolerado, e conteúdo que não seja
UTF-8 válido, ou que não se analise como objeto JSON, é copiado byte a byte para que o subprocesso veja
exatamente o que o CLI teria lido. Os arquivos são escritos apenas para o dono (`0600`) dentro do
diretório temporário também restrito ao dono (`0700`).

A semeadura é de melhor esforço: um arquivo que não possa ser lido por qualquer motivo que não seja
"inexistente" — um erro de permissão, ou um diretório ou FIFO onde se esperava um arquivo — é
registrado e ignorado, em vez de abortar uma retomada que de outra forma teria sucesso. Uma cópia que
falha no meio remove o destino parcial para que o subprocesso não interprete mal um arquivo truncado.

### Guardas de validação

Antes de qualquer trabalho com subprocessos, o SDK rejeita combinações inválidas:

- `continueConversation + sessionStore` exige `store.implementsListSessions()`.
- `sessionStore + enableFileCheckpointing` é rejeitado — checkpoints são só de disco local e
  divergiriam da transcrição espelhada.

Esses casos lançam `IllegalArgumentException` de imediato.

## Erros de espelhamento

Falhas ao anexar no espelho não são fatais — a transcrição em disco local já é durável, então a sessão
continua sem ser afetada. O SDK repete cada lote até 3 vezes com recuo de `[200ms, 800ms]`, depois o
descarta e apresenta um `MirrorErrorMessage` no seu fluxo de mensagens:

```java
import in.vidyalai.claude.sdk.types.message.MirrorErrorMessage;

for (Message msg : ClaudeSDK.query("Hello", options)) {
    switch (msg) {
        case MirrorErrorMessage err -> {
            // Non-fatal — log and consider importing the local file later
            System.err.println("Mirror error for " + (err.key() != null
                    ? err.key().sessionId() : "<unknown>")
                    + ": " + err.error());
        }
        case AssistantMessage a -> System.out.println(a.getTextContent());
        // ... other cases
        default -> { /* ignore */ }
    }
}
```

`MirrorErrorMessage` é membro da interface selada `Message` (ao lado de `AssistantMessage`,
`SystemMessage` etc.) — `subtype` é sempre `"mirror_error"`, `error` é a mensagem da falha e `key`
(anulável) é a `SessionKey` que o lote falho estava mirando.

Tempos limite NÃO são repetidos (a chamada em voo ainda pode chegar — uma nova tentativa lançaria uma
duplicata concorrente). Adaptadores devem desduplicar por `entry.uuid()` para que uma nova tentativa
após sucesso parcial seja segura quanto a duplicatas.

## Modo de gravação (batched vs eager)

Por padrão, o `TranscriptMirrorBatcher` acumula todos os quadros `transcript_mirror` e grava uma vez
por turno (na mensagem `result`) ou quando o buffer pendente ultrapassa `MAX_PENDING_ENTRIES=500`
entradas / `MAX_PENDING_BYTES=1 MiB`. Isso mantém a latência do adaptador fora do caminho quente de
streaming e é a escolha certa para quase toda implantação.

A opção `sessionStoreFlush` permite mudar para espelhamento imediato quando você precisa que as
entradas cheguem ao store com latência abaixo de um segundo:

```java
import in.vidyalai.claude.sdk.types.session.SessionStoreFlushMode;

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .sessionStore(store)
    .sessionStoreFlush(SessionStoreFlushMode.EAGER)
    .build();
```

| Modo | Quando as entradas são gravadas | Use quando |
|---|---|---|
| `BATCHED` (padrão) | Uma vez por mensagem `result` OU quando o pendente passa de 500 entradas / 1 MiB | Quase todas as cargas de produção — mantém a latência do adaptador fora do caminho quente |
| `EAGER` | Drenagem em segundo plano agendada após cada quadro enfileirado | Streaming ao vivo de transcrições para clientes, pipelines de auditoria em tempo real, turnos muito grandes em que não dá para esperar o `result` |

`EAGER` zera os limiares de pendência do batcher — cada quadro enfileirado agenda uma gravação em
segundo plano via o `SessionStoreExecutor` configurado (uma thread nomeada por tarefa; virtual no
Java 21+). As anexações continuam serializadas na ordem de enfileiramento; um adaptador lento não
trava o laço de leitura, mas verá quadros agrupados enquanto estiver ocupado. A opção é ignorada
quando `sessionStore` não está definido.

## Importando sessões locais para um store

Migre sessões já existentes em disco para um store, ou coloque o store em dia depois que um
`MirrorErrorMessage` expôs uma lacuna:

```java
ClaudeSDK.importSessionToStore(sessionId, store, /* directory */ null);
// or with explicit options:
ClaudeSDK.importSessionToStore(
    sessionId, store, /* directory */ null,
    /* includeSubagents */ true,
    /* batchSize */ 500);
```

O helper:

- Percorre o JSONL local linha a linha (pula linhas em branco).
- Chama `store.append(key, batch)` a cada `batchSize` entradas (padrão 500) ou a cada 1 MiB de bytes
  de linha, o que vier primeiro.
- Com `includeSubagents=true`, importa recursivamente `<sessionDir>/subagents/**/*.jsonl` e os
  arquivos laterais `.meta.json` (o `.meta.json` vira uma entrada `agent_metadata`).
- Lança `IllegalArgumentException` para UUID inválido e `NoSuchFileException` se o arquivo da sessão
  não for encontrado.

O `project_key` de destino é o nome do diretório de projeto em disco — a mesma chave que
`filePathToSessionKey` produz — então uma sessão importada é indistinguível de uma espelhada ao vivo e
pode ser retomada a partir do `cwd` original.

Adaptadores devem tratar `entry.uuid()` como chave de idempotência, de modo que reimportar seja seguro
quanto a duplicatas.

## Harness de testes de conformidade

`in.vidyalai.claude.sdk.testing.SessionStoreConformance` é um harness de testes público e independente
de framework que exercita os 14 contratos de comportamento que todo adaptador precisa satisfazer. Use-o
para validar suas próprias implementações:

```java
import in.vidyalai.claude.sdk.testing.SessionStoreConformance;
import org.junit.jupiter.api.Test;

class MyRedisStoreConformanceTest {
    @Test
    void satisfiesContract() {
        SessionStoreConformance.run(MyRedisStore::new);
    }
}
```

Para pular métodos opcionais que você não implementa:

```java
SessionStoreConformance.run(WormStore::new,
    EnumSet.of(SessionStoreConformance.OptionalMethod.DELETE));
```

O harness usa `AssertionError` puro (sem dependência de framework de teste), então funciona com JUnit,
TestNG, Spock ou até num simples teste de fumaça com `main()`.

Os 14 contratos cobrem:

| # | Contrato |
|---|---|
| 1 | `append` seguido de `load` devolve as mesmas entradas na mesma ordem |
| 2 | `load` de uma chave desconhecida devolve `null` |
| 3 | Várias chamadas de `append` preservam a ordem |
| 4 | `append([])` não faz nada |
| 5 | Chaves com subpath são guardadas de forma independente da principal |
| 6 | Isolamento por `project_key` |
| 7 | `listSessions` devolve os IDs de sessão do projeto, com mtime em ms desde a época |
| 8 | `listSessions` exclui subpaths de subagente |
| 9 | `delete` seguido de `load` devolve `null` |
| 10 | `delete` da chave principal cascateia para as subchaves |
| 11 | `delete` com subpath remove apenas aquela subchave |
| 12 | `listSubkeys` devolve os subpaths |
| 13 | `listSubkeys` exclui a transcrição principal |
| 14 | `listSessionSummaries` faz ida e volta por `foldSessionSummary` |

## Peças internas de runtime

Estas vivem em `in.vidyalai.claude.sdk.internal` e não fazem parte da API pública, mas entendê-las
ajuda a depurar o comportamento do espelhamento.

### `TranscriptMirrorBatcher`

Acumula os quadros `transcript_mirror` que o CLI emite no stdout e os grava em
`store.appendAsync(...)`:

- Limiares de gravação imediata: `MAX_PENDING_ENTRIES=500`, `MAX_PENDING_BYTES=1 MiB`. Com
  `sessionStoreFlush(EAGER)` os dois limiares são zerados, de modo que cada quadro enfileirado agenda
  uma drenagem em segundo plano (veja [Modo de gravação](#modo-de-gravação-batched-vs-eager)).
- Gravação explícita antes de cada mensagem `result` e novamente no fim do fluxo / ao fechar.
- Agrupa quadros por `filePath`, de modo que cada arquivo único receba uma chamada `append` por
  gravação.
- Quadros cujo caminho cai fora de `projectsDir` são descartados com um aviso (aconteceria se o
  `CLAUDE_CONFIG_DIR` diferisse entre o processo pai e o subprocesso).
- `MIRROR_APPEND_MAX_ATTEMPTS=3` tentativas com recuo de `[200ms, 800ms]`; tempos limite não são
  repetidos.
- Os acessores de teste `maxPendingEntries()` / `maxPendingBytes()` expõem os limiares configurados
  (espelhando os atributos públicos do Python).

### `SessionResume`

Materializa uma sessão armazenada em um `CLAUDE_CONFIG_DIR` temporário para que o CLI possa retomar:

- `materializeResumeSession(options)` — ponto de entrada principal.
- `applyMaterializedOptions(options, materialized)` — copia as options com `CLAUDE_CONFIG_DIR`
  injetado, `resume` definido e `continueConversation` limpo.
- `buildMirrorBatcher(store, materialized, env, onError)` — constrói o batcher com o `projectsDir`
  certo (padrão: modo de gravação `BATCHED`). A sobrecarga de 5 argumentos
  `buildMirrorBatcher(store, materialized, env, onError, flushMode)` zera os limiares do batcher quando
  `flushMode == EAGER`.
- `MaterializedResume.cleanup()` — remoção recursiva de melhor esforço, com nova tentativa em travas
  transitórias de antivírus/indexador do Windows.

### `SessionStoreValidation`

Verificações prévias das options, chamadas antes de criar o subprocesso (rejeitam configurações
inválidas com `IllegalArgumentException`).

### `SessionSummary`

Helpers puros que adaptadores podem usar dentro de `append()` para manter arquivos laterais de resumo
incrementais sem reler a transcrição:

```java
SessionSummaryEntry folded = SessionSummary.foldSessionSummary(
    /* prev */ existing, key, entries);
// stamp folded.mtime() with the adapter's storage write time, then persist.
```

`SessionSummary.summaryEntryToSdkInfo(entry, projectPath)` converte um arquivo lateral de volta em um
`SDKSessionInfo` para listagem.

## Boas práticas

### Implementação do adaptador

- **Implemente sempre `append` + `load`.** São obrigatórios.
- **Mantenha um arquivo lateral de resumo** via `SessionSummary.foldSessionSummary` dentro de
  `append()` se o seu back-end suportar operações de listagem; isso faz `listSessionsFromStore` ser
  O(1) em vez de O(N) carregamentos. Pule o fold para chaves com `subpath` — transcrições de subagente
  não devem contribuir para o resumo da sessão principal.
- **Trate `entry.uuid()` como chave de idempotência.** Use semântica de upsert ou pule se já existir.
  O SDK repete lotes que falharam e pode ter sucesso parcial.
- **Carimbe o `mtime` do resumo com a hora de escrita do seu armazenamento**, não com os timestamps das
  entradas. A checagem de frescor do caminho rápido compara o mtime do resumo com o
  `listSessions().mtime` da mesma sessão — usar timestamps de entrada faria todo arquivo lateral
  parecer desatualizado.
- **Cascateie exclusões** da chave da transcrição principal para todas as subchaves (transcrições de
  subagente).
- **Rode o harness de conformidade** na CI.

### Quando sobrescrever os métodos `*Async`

- Seu cliente é nativamente assíncrono (AWS SDK v2 async, R2DBC, Lettuce reactive) — sobrescreva
  `*Async` para evitar um salto de thread.
- Seu cliente é síncrono (JDBC, Jedis, AWS SDK v1) — implemente só os métodos síncronos; os invólucros
  `*Async` padrão bastam.

### Quando usar `importSessionToStore`

- Migração única de sessões locais preexistentes para um store.
- Colocar em dia após um `MirrorErrorMessage` (reimporte o arquivo local; a idempotência por `uuid`
  torna isso seguro).

### Evite

- Combinar `sessionStore` com `enableFileCheckpointing` (rejeitado na validação de qualquer forma —
  checkpoints são só locais).
- Guardar segredos ou dados pessoais sem controles de retenção. O SDK não apaga automaticamente;
  configure o ciclo de vida do seu store.
- Contar com serialização byte a byte igual em `load()`. O contrato é de igualdade profunda; o `jsonb`
  do Postgres reordena chaves, por exemplo.

## Referência da API

### A interface `SessionStore`

`in.vidyalai.claude.sdk.types.session.SessionStore`

| Método | Obrigatório | Padrão | Observações |
|---|---|---|---|
| `void append(SessionKey, List<SessionStoreEntry>)` | ✅ | — | Espelha o lote; chamado após a escrita local |
| `List<SessionStoreEntry> load(SessionKey)` | ✅ | — | Devolve entradas ou `null` |
| `List<SessionStoreListEntry> listSessions(String)` | opcional | lança | Exclui entradas com subpath |
| `List<SessionSummaryEntry> listSessionSummaries(String)` | opcional | lança | Caminho rápido para `listSessionsFromStore` |
| `void delete(SessionKey)` | opcional | lança | A chave principal cascateia para as subchaves |
| `List<String> listSubkeys(SessionListSubkeysKey)` | opcional | lança | Usado na materialização da retomada |
| `boolean implementsListSessions()` | — | `false` | Sobrescreva para declarar suporte |
| `boolean implementsListSessionSummaries()` | — | `false` | Sobrescreva para declarar suporte |
| `boolean implementsDelete()` | — | `false` | Sobrescreva para declarar suporte |
| `boolean implementsListSubkeys()` | — | `false` | Sobrescreva para declarar suporte |
| `CompletableFuture<Void> appendAsync(...)` | opcional | envolve o síncrono | Sobrescreva para clientes assíncronos nativos |
| `CompletableFuture<List<SessionStoreEntry>> loadAsync(...)` | opcional | envolve o síncrono | Sobrescreva para clientes assíncronos nativos |
| `CompletableFuture<List<SessionStoreListEntry>> listSessionsAsync(...)` | opcional | envolve o síncrono | — |
| `CompletableFuture<List<SessionSummaryEntry>> listSessionSummariesAsync(...)` | opcional | envolve o síncrono | — |
| `CompletableFuture<Void> deleteAsync(...)` | opcional | envolve o síncrono | — |
| `CompletableFuture<List<String>> listSubkeysAsync(...)` | opcional | envolve o síncrono | — |

Cada método `*Async` tem tanto uma sobrecarga sem argumentos (usa o executor padrão configurado)
quanto uma que recebe um `Executor` (controle por chamada).

### Métodos estáticos de `ClaudeSDK`

| Método | Descrição |
|---|---|
| `String projectKeyForDirectory(@Nullable Path)` | Normaliza um diretório em um `project_key` |
| `List<SDKSessionInfo> listSessionsFromStore(SessionStore, @Nullable Path, @Nullable Integer, int)` | Lista sessões em um store |
| `SDKSessionInfo getSessionInfoFromStore(SessionStore, String, @Nullable Path)` | Lê os metadados de uma sessão |
| `List<SessionMessage> getSessionMessagesFromStore(SessionStore, String, @Nullable Path, @Nullable Integer, int)` | Lê a transcrição completa |
| `List<String> listSubagentsFromStore(SessionStore, String, @Nullable Path)` | Descobre os IDs de subagente |
| `List<SessionMessage> getSubagentMessagesFromStore(SessionStore, String, String, @Nullable Path, @Nullable Integer, int)` | Lê a transcrição de um subagente |
| `void renameSessionViaStore(SessionStore, String, String, @Nullable Path)` | Acrescenta entrada `custom-title` |
| `void tagSessionViaStore(SessionStore, String, @Nullable String, @Nullable Path)` | Acrescenta entrada `tag`; `null` limpa |
| `void deleteSessionViaStore(SessionStore, String, @Nullable Path)` | Exclui (não faz nada se `delete` não estiver implementado) |
| `ForkSessionResult forkSessionViaStore(SessionStore, String, @Nullable Path, @Nullable String, @Nullable String)` | Fork com remapeamento de UUID |
| `void importSessionToStore(String, SessionStore, @Nullable Path)` | Replay local→store (opções padrão) |
| `void importSessionToStore(String, SessionStore, @Nullable Path, boolean, int)` | Replay com `includeSubagents` e `batchSize` explícitos |

### Métodos do builder de `ClaudeAgentOptions`

| Método | Padrão | Descrição |
|---|---|---|
| `Builder sessionStore(@Nullable SessionStore)` | `null` | Espelha as transcrições para este store |
| `Builder loadTimeoutMs(long)` | `60_000` | Tempo limite por chamada durante a materialização da retomada |

### `SessionStoreExecutor`

`in.vidyalai.claude.sdk.types.session.SessionStoreExecutor`

| Método | Descrição |
|---|---|
| `Executor getDefault()` | Executor padrão atual |
| `void setDefault(Executor)` | Substitui; `null` volta ao embutido |
| `void reset()` | Volta ao embutido `Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("session-store-", 0).factory())` |

### `SessionStoreConformance`

`in.vidyalai.claude.sdk.testing.SessionStoreConformance`

| Método | Descrição |
|---|---|
| `void run(Supplier<SessionStore>)` | Roda os 14 contratos |
| `void run(Supplier<SessionStore>, Set<OptionalMethod>)` | Pula os métodos opcionais listados |

Enum `OptionalMethod`: `LIST_SESSIONS`, `LIST_SESSION_SUMMARIES`, `DELETE`, `LIST_SUBKEYS`.

## Veja também

- [Histórico de sessões](./feature-session-history.md) — equivalentes em disco local (`listSessions`, `getSessionMessages` etc.)
- [Tipos de mensagem](./feature-message-types.md) — integração com `MirrorErrorMessage`
- [ClaudeAgentOptions](./api-claude-agent-options.md) — `sessionStore` e `loadTimeoutMs`
- [ClaudeSDK](./api-claude-sdk.md) — pontos de entrada da API pública
- `SessionStoreExample.java` no módulo `examples/`
