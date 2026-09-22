# Visão geral da arquitetura

Este documento apresenta um panorama completo da arquitetura, dos padrões de design e da estrutura
interna do Claude Agent SDK for Java.

> **Sobre esta tradução**: a documentação em inglês é a única autoritativa. Esta tradução pode estar desatualizada em relação ao [original em inglês](../architecture.md); em caso de divergência, o inglês prevalece. Os blocos de código e os diagramas são mantidos idênticos ao original e não foram traduzidos.

## Sumário
- [Arquitetura de alto nível](#arquitetura-de-alto-nível)
- [Componentes centrais](#componentes-centrais)
- [Padrões de design](#padrões-de-design)
- [Fluxo de dados](#fluxo-de-dados)
- [Modelo de concorrência](#modelo-de-concorrência)
- [Sistema de tipos](#sistema-de-tipos)
- [Dependências](#dependências)

## Arquitetura de alto nível

O SDK segue uma arquitetura em camadas com separação clara de responsabilidades:

```
┌─────────────────────────────────────────────────────────┐
│           Public API Layer                              │
│  ┌──────────────────┐    ┌────────────────────────┐   │
│  │   ClaudeSDK      │    │  ClaudeSDKClient       │   │
│  │   (Facade)       │    │  (Interactive Client)  │   │
│  └──────────────────┘    └────────────────────────┘   │
│            │                        │                   │
│            └────────────────────────┘                   │
│                     │                                   │
├─────────────────────┼───────────────────────────────────┤
│                     ▼                                   │
│           Configuration Layer                           │
│  ┌─────────────────────────────────────────────────┐  │
│  │       ClaudeAgentOptions (Builder)              │  │
│  └─────────────────────────────────────────────────┘  │
├─────────────────────────────────────────────────────────┤
│           Protocol & Control Layer                      │
│  ┌──────────────────┐    ┌────────────────────────┐   │
│  │   QueryHandler   │◄───┤   MessageParser        │   │
│  │  (Control Proto) │    │   (JSON Parsing)       │   │
│  └──────────────────┘    └────────────────────────┘   │
│            │                                            │
├─────────────┼────────────────────────────────────────────┤
│            ▼                                            │
│           Transport Layer                               │
│  ┌─────────────────────────────────────────────────┐  │
│  │  Transport Interface                            │  │
│  │  └─ SubprocessCLITransport (default impl)      │  │
│  └─────────────────────────────────────────────────┘  │
│            │                                            │
├─────────────┼────────────────────────────────────────────┤
│            ▼                                            │
│           External Process                              │
│  ┌─────────────────────────────────────────────────┐  │
│  │         Claude Code CLI Process                 │  │
│  │         (stdin/stdout communication)            │  │
│  └─────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────┘
```

## Componentes centrais

### 1. Camada de API pública

#### ClaudeSDK (fachada)
- **Propósito**: fachada estática para consultas simples e sem estado
- **Caso de uso**: perguntas avulsas, processamento em lote, operações "dispare e esqueça"
- **Métodos principais**:
  - `query(String prompt)` — consulta simples com os padrões
  - `query(String prompt, ClaudeAgentOptions options)` — consulta com opções próprias
  - `query(Iterator<Map> stream, ClaudeAgentOptions options)` — consulta por streaming
  - `queryForText()` / `queryForResult()` — métodos de conveniência
  - `createClient()` — método de fábrica de ClaudeSDKClient
  - `createSdkMcpServer()` — fábrica de servidores MCP

**Padrão de design**: Facade + Factory

#### ClaudeSDKClient
- **Propósito**: client interativo e com estado para conversas de múltiplos turnos
- **Caso de uso**: interfaces de chat, interações estilo REPL, sessões de longa duração
- **Métodos principais**:
  - `connect()` — estabelece a conexão
  - `sendMessage()` / `query()` — envia mensagens
  - `receiveMessages()` / `receiveResponse()` — recebe mensagens
  - Métodos de controle: `interrupt()`, `setModel()`, `setPermissionMode()` etc.
- **Segurança entre threads**: parcialmente thread-safe, com garantias documentadas
- **Gerenciamento de recursos**: implementa AutoCloseable para a limpeza correta

**Padrão de design**: Builder + gerenciamento de recursos (try-with-resources)

### 2. Camada de configuração

#### ClaudeAgentOptions
- **Propósito**: objeto de configuração imutável usando o padrão builder
- **Recursos**:
  - Mais de 30 opções de configuração
  - API com segurança de tipos usando enums e interfaces seladas
  - Builder fluente, com `toBuilder()` para modificações
- **Principais áreas de configuração**:
  - Ferramentas: `tools()`, `allowedTools()`, `disallowedTools()`
  - Permissões: `permissionMode()`, `canUseTool()`
  - Sessões: `continueConversation()`, `resume()`, `forkSession()`, `sessionStore()`, `loadTimeoutMs()`
  - Limites: `maxTurns()`, `maxBudgetUsd()`, `maxThinkingTokens()`
  - Modelo: `model()`, `fallbackModel()`, `betas()`
  - Ambiente: `cwd()`, `env()`, `cliPath()`
  - Hooks: `hooks()`
  - MCP: `mcpServers()`
  - Agentes: `agents()` (enviados pela requisição initialize via stdin, sem limite de tamanho)
  - Avançado: `sandbox()`, `outputFormat()`, `checkpointFiles()`

**Padrão de design**: Builder + objeto imutável

### 3. Camada de protocolo e controle

#### QueryHandler
- **Propósito**: gerencia o protocolo de controle bidirecional sobre o Transport
- **Responsabilidades**:
  - Roteamento de requisições/respostas de controle
  - Callbacks de hook
  - Callbacks de permissão de ferramentas
  - Streaming de mensagens
  - Handshake de inicialização (inclui hooks, definições de agente e excludeDynamicSections)
  - Gerenciamento do ciclo de vida dos servidores MCP
  - **Substituição por um erro acionável**: acompanha a carga útil do resultado de erro mais recente
    enquanto lê o fluxo; quando um `ProcessException` vem depois de um resultado com `is_error=true`,
    ele é substituído por uma `ResultException` que carrega essa carga útil e a mensagem
    `"Claude Code returned an error result: <text>"` (montada a partir do array `errors` do resultado,
    depois do texto `result`, depois de um `subtype` diferente de `success`, depois do status de erro
    da API) em vez do genérico `"Command failed with exit code N"`. É reiniciada a qualquer tráfego
    que não seja resultado nem `session_state_changed`. O objeto de exceção viaja no quadro sintético
    `{"type":"error"}`, então o iterador do consumidor o relança com tipo e carga útil intactos.
- **Segurança entre threads**: totalmente thread-safe com operações atômicas e sincronização
- **Principais recursos**:
  - Protocolo de controle assíncrono com CompletableFuture
  - Geração e acompanhamento de IDs de requisição
  - Fila de mensagens com tamanho configurável
  - Thread leitora em segundo plano (virtual no Java 21+)
  - Executor de controle para callbacks assíncronos

**Padrão de design**: requisição/resposta assíncrona + Observer (para hooks)

#### MessageParser
- **Propósito**: interpretar as mensagens JSON do CLI e convertê-las em objetos Message tipados
- **Recursos**:
  - Análise JSON baseada em Jackson
  - Suporte a todos os tipos de mensagem (user, assistant, system, result, stream_event)
  - Análise de blocos de conteúdo (text, thinking, tool_use, tool_result)
  - Tratamento de erros e validação

**Padrão de design**: Parser + Factory

### 4. Camada de transporte

#### Interface Transport
- **Propósito**: camada de E/S abstrata para a comunicação com o Claude Code
- **Implementação padrão**: SubprocessCLITransport
- **Implementações próprias**: permitem conexões remotas ao Claude Code
- **Métodos principais**:
  - `connect()` — estabelece a conexão
  - `write(String data)` — envia dados
  - `readMessages()` — recebe mensagens como iterador
  - `endInput()` — fecha o fluxo de entrada
  - `isReady()` — verifica o estado da conexão
  - `close()` — libera recursos

**Padrão de design**: Strategy + Template Method

#### SubprocessCLITransport
- **Propósito**: transporte padrão que usa um subprocesso para o CLI do Claude Code
- **Recursos**:
  - Gerencia o ciclo de vida do subprocesso do CLI
  - Comunicação por stdin/stdout
  - Leitura com buffer e limites configuráveis
  - Suporte a callback de stderr com isolamento de exceções por linha (um callback que lança já não
    mata o laço de leitura)
  - Limpeza automática do processo
  - **Shutdown hook da JVM**: um `ConcurrentHashMap.newKeySet()` estático acompanha cada `Process`
    criado; um `Runtime.addShutdownHook` registrado na inicialização da classe chama `destroy()` em
    cada filho vivo, para que subprocessos `claude` perdidos não vazem quando a JVM pai sai antes de
    `close()`. Espelha o handler `atexit` do SDK Python.
- **Detalhes de implementação**:
  - Usa ProcessBuilder para gerenciar o subprocesso
  - Thread dedicada para ler o stdout (virtual no Java 21+)
  - BufferedReader com análise por linha
  - Jackson para serialização/desserialização JSON

**Padrão de design**: gerenciamento de subprocesso + E/S com buffer

### 5. Suporte a MCP (Model Context Protocol)

#### SdkMcpServer
- **Propósito**: servidor MCP in-process para ferramentas próprias
- **Vantagens sobre servidores externos**:
  - Sem sobrecarga de IPC (mesmo processo)
  - Implantação mais simples
  - Depuração mais fácil
  - Acesso direto ao estado da aplicação
- **Recursos**:
  - Registro e execução de ferramentas
  - Geração automática de schema a partir de anotações @Tool
  - Execução assíncrona baseada em CompletableFuture
  - Informações do servidor e negociação de versão no `initialize` entre `2025-06-18` / `2024-11-05`
  - Mensagens do protocolo MCP (`initialize`, `ping`, `tools/list`, `tools/call`)
  - Validação de argumentos contra o `inputSchema` de cada ferramenta, compilado uma vez quando o
    servidor é construído
  - Cancelamento: `notifications/cancelled` resolve a chamada pendente e sinaliza o handler
- **Classificação de mensagens**: um `method` com `id` é uma requisição e é respondido; um `method` sem
  `id` é uma notificação e *nunca* é respondido, como o JSON-RPC exige — em vez disso, a requisição de
  controle que a envolve é confirmada. Uma mensagem sem `method` é uma resposta, ou lixo, e é
  ignorada: este servidor não envia requisições ao CLI, então nada que chegue por esse caminho é dele
  para casar.
- **Classificação de falhas**: tudo o que um `tools/call` pode encontrar é um *erro de execução de
  ferramenta* — um resultado com `isError: true` — inclusive uma ferramenta desconhecida, um argumento
  inválido perante o schema e um handler que lançou. Um erro de JSON-RPC fica reservado ao que um
  *modelo* nunca causa e nunca vê: um método não implementado (`-32601`), `params` malformados
  (`-32602`) e uma chamada que o CLI cancelou (`-32800`). Um resultado com `isError` chega ao modelo
  como saída de ferramenta que ele pode ler e corrigir; um erro de JSON-RPC diz que a requisição não
  pôde sequer ser processada.
- **Validação que falha fechando**: cada `inputSchema` é conferido contra a meta-schema do seu próprio
  dialeto na construção, e uma ferramenta cujo schema não passe é registrada e fica impossível de
  chamar. Caso contrário, o validador aceitaria um schema malformado e validaria errado contra ele —
  `{"type": "bogus"}` não casa com nada, `"properties": "a string"` é ignorado — de modo que um
  handler rodaria com argumentos que ninguém conferiu, ou toda chamada falharia citando a coisa
  errada.

#### McpMessageHandler
- **Propósito**: é a costura que o `McpSdkServerConfig` de fato guarda, para que uma aplicação possa
  servir MCP por conta própria — recursos, prompts, completions, ou um adaptador sobre uma biblioteca
  MCP de terceiros — em vez de usar o `SdkMcpServer`.
- **Contrato**: `handleMessage` devolve a resposta JSON-RPC de uma requisição e `null` para tudo que
  não espera resposta. `close()` significa "a conexão que usava você vai embora", não "desligue": um
  handler pode servir mais de um cliente, então precisa ser idempotente e continuar utilizável.

#### ToolCallContext
- **Propósito**: deixa uma ferramenta em execução perceber que sua chamada foi cancelada.
- **Por que precisa existir**: `CompletableFuture.cancel(true)` não interrompe uma tarefa em execução —
  ele completa o future e deixa o trabalho seguir. Sem um sinal explícito, cancelar interromperia a
  *espera*, mas não o *trabalho*, e uma ferramenta com efeitos colaterais continuaria a aplicá-los
  depois de o CLI ter desistido.

#### SdkMcpTool
- **Propósito**: invólucro de definição e execução de ferramenta
- **Formas de criação**:
  - `SdkMcpTool.create()` — criação programática
  - Anotação `@Tool` — criação declarativa
- **Recursos**:
  - Parâmetro de tipo genérico para a entrada
  - Execução assíncrona baseada em CompletableFuture
  - JSON Schema para validação da entrada
  - Extração automática de parâmetros

**Padrão de design**: Command + Factory + processamento de anotações

### 6. Subsistema SessionStore

#### SessionStore (protocolo do adaptador)
- **Propósito**: espelhar transcrições de sessão para armazenamento externo (S3, Postgres, Redis,
  back-ends próprios) para que as sessões durem além do disco local e possam ser retomadas em
  qualquer host.
- **Métodos obrigatórios**: `append(SessionKey, List<SessionStoreEntry>)`, `load(SessionKey)`.
- **Métodos opcionais** (com sondas de capacidade `implements*()`): `listSessions`,
  `listSessionSummaries`, `delete`, `listSubkeys`.
- **API síncrona + assíncrona**: todo método tem uma variante `*Async` (`CompletableFuture`).
  Adaptadores com clientes não bloqueantes nativos (AWS SDK v2 async, R2DBC, Lettuce reactive)
  sobrescrevem os `*Async` diretamente para evitar um salto de thread. O executor padrão é
  configurado por `SessionStoreExecutor` (uma thread por tarefa; virtual no Java 21+, threads de
  plataforma daemon caso contrário).

**Padrão de design**: Adapter + negociação de capacidades + API dupla (síncrona/assíncrona)

#### TranscriptMirrorBatcher (interno)
- **Propósito**: acumular os quadros `transcript_mirror` que o CLI emite no stdout e gravá-los em
  `store.appendAsync(...)`.
- **Comportamentos principais**:
  - Limiares de gravação imediata: `MAX_PENDING_ENTRIES=500`, `MAX_PENDING_BYTES=1 MiB`.
  - Gravação explícita antes de cada mensagem `result` e no fim do fluxo / ao fechar.
  - Agrupa quadros por `filePath`, para que cada arquivo único receba uma chamada `append` por
    gravação.
  - Retentativa limitada: `MIRROR_APPEND_MAX_ATTEMPTS=3` tentativas com recuo `[200ms, 800ms]`. Tempos
    limite não são repetidos (a chamada em voo ainda pode chegar).
  - Quadros cujo caminho cai fora do `projectsDir` configurado são descartados com um aviso.
  - Falhas aparecem como `MirrorErrorMessage` no fluxo do consumidor — nunca bloqueiam a conversa.

**Padrão de design**: buffer produtor-consumidor + retentativa com recuo exponencial

#### SessionResume (interno)
- **Propósito**: materializar uma sessão armazenada em um `CLAUDE_CONFIG_DIR` temporário para que o
  subprocesso do CLI possa retomar a partir do disco local.
- **Fluxo**:
  1. Carrega as entradas via `store.loadAsync()` (ou escolhe a sessão não lateral modificada mais
     recentemente, no caso de `continueConversation`).
  2. Escreve o JSONL num diretório temporário organizado como `~/.claude/`.
  3. Copia `.credentials.json` (com `refreshToken` removido para impedir consumo de token a partir do
     diretório temporário) e `.claude.json`.
  4. Materializa transcrições de subagente e arquivos laterais `.meta.json` quando o store implementa
     `listSubkeys`.
  5. Cria o CLI com `CLAUDE_CONFIG_DIR=<temp dir>`.
  6. Limpa ao desconectar, com nova tentativa em travas transitórias de antivírus/indexador do
     Windows.

**Padrão de design**: visão materializada + limpeza com retentativa

#### SessionStoreValidation (interno)
- **Propósito**: verificações prévias das opções antes de criar o subprocesso. Rejeita combinações
  inválidas com `IllegalArgumentException`:
  - `continueConversation + sessionStore` exige `store.implementsListSessions()`.
  - `sessionStore + enableFileCheckpointing` é rejeitado (checkpoints são só locais).

**Padrão de design**: validação que falha cedo

#### SessionStoreConformance (auxiliar público de teste)
- **Localização**: `in.vidyalai.claude.sdk.testing.SessionStoreConformance`
- **Propósito**: suíte de testes de comportamento com 14 contratos, independente de framework, para
  adaptadores `SessionStore`. Usa `AssertionError` puro, então funciona em qualquer framework de teste
  (JUnit, TestNG, Spock, `main` comum).

**Padrão de design**: teste de contrato

## Padrões de design

### 1. Interfaces seladas (casamento de padrões)
Usadas amplamente para um tratamento de mensagens com segurança de tipos:

```java
sealed interface Message permits UserMessage, AssistantMessage,
    SystemMessage, TaskStartedMessage, TaskProgressMessage,
    TaskNotificationMessage, TaskUpdatedMessage, MirrorErrorMessage,
    HookEventMessage, ResultMessage, StreamEvent, RateLimitEvent {}

// Usage with pattern matching
switch (message) {
    case UserMessage u -> handleUser(u);
    case AssistantMessage a -> handleAssistant(a);
    case ResultMessage r -> handleResult(r);
    case MirrorErrorMessage m -> handleMirrorError(m);
    case HookEventMessage h -> handleHookEvent(h);
    case SystemMessage s -> handleSystem(s);
    case StreamEvent e -> handleStreamEvent(e);
    // ... task and rate-limit cases
}
```

**Benefícios**:
- Casamento de padrões exaustivo em tempo de compilação
- Não é preciso um caso default
- Segurança de tipos garantida
- Hierarquia de tipos clara

### 2. Padrão builder
Usado nos objetos de configuração:

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .model("claude-sonnet-4-5")
    .maxTurns(10)
    .permissionMode(PermissionMode.ACCEPT_EDITS)
    .build();

// Modify existing options
ClaudeAgentOptions modified = options.toBuilder()
    .maxTurns(20)
    .build();
```

**Benefícios**:
- Configuração legível
- Parâmetros opcionais
- Objetos imutáveis
- API encadeável

### 3. Padrão fachada
O ClaudeSDK oferece uma interface simplificada:

```java
// Simple facade
List<Message> messages = ClaudeSDK.query("Hello");

// Hides complexity of:
// - Transport creation
// - QueryHandler setup
// - Message parsing
// - Resource cleanup
```

**Benefícios**:
- API simples para os casos comuns
- Esconde a complexidade interna
- Ponto de entrada único

### 4. Threads virtuais (concorrência)
Aproveita o Project Loom para concorrência leve onde o runtime oferece isso.

O SDK compila para o Java 17, onde `Thread.ofVirtual()` não existe, então toda thread e todo executor
são criados por `internal.Threads`. Ele resolve os pontos de entrada do Java 21 por reflexão, uma
única vez, em method handles `static final`, e recai em threads de plataforma daemon nomeadas quando
eles não existem:

```java
// Background reader thread
Thread reader = Threads.start("ClaudeSDK-Reader-", () -> readLoop());

// Executor for control protocol
ExecutorService executor = Threads.newSingleThreadExecutor("ClaudeSDK-Reader-");
```

Os nomes das threads são idênticos nos dois caminhos, então os thread dumps se leem do mesmo jeito
independentemente do runtime. Defina `-Dclaude.sdk.virtualThreads=false` para forçar o caminho de
plataforma em qualquer JDK.

**Benefícios no Java 21+**:
- Threads leves (milhares são possíveis)
- E/S bloqueante sem esgotar o pool de threads
- Código assíncrono mais simples
- Melhor uso dos recursos

**No Java 17-20**: o mesmo código roda em threads de plataforma daemon. Os executores continuam
*ilimitados* em vez de virar pools fixos — o executor de controle do `QueryHandler` estaciona uma
thread durante toda a chamada de uma ferramenta MCP do SDK, e o cancelamento que a encerra chega como
uma tarefa separada, então um pool limitado entraria em deadlock. O custo é uma thread do sistema
operacional por requisição de controle em voo, em vez de uma virtual.

### 5. CompletableFuture (operações assíncronas)
Usado em callbacks assíncronos e no protocolo de controle:

```java
// Permission callback
CompletableFuture<PermissionResult> future =
    canUseTool.apply(toolName, input, context);

// Control protocol request/response
CompletableFuture<ControlResponse> response =
    sendControlRequest(request);
```

**Benefícios**:
- Operações não bloqueantes
- Cadeias assíncronas componíveis
- Tratamento de erros
- Suporte a tempo limite

## Fluxo de dados

### Fluxo de execução de uma consulta

```
User Code
    │
    ├─► ClaudeSDK.query(prompt, options)
    │       │
    │       ├─► Validate options
    │       ├─► Create Transport
    │       ├─► Create QueryHandler
    │       │       │
    │       │       ├─► Start reader thread
    │       │       └─► Process messages
    │       │               │
    │       │               ├─► Parse JSON
    │       │               ├─► Handle control protocol
    │       │               ├─► Invoke hooks
    │       │               ├─► Check permissions
    │       │               └─► Add to message queue
    │       │
    │       └─► Collect messages
    │               │
    └─────────────► Return List<Message>
```

### Fluxo do client interativo

```
User Code
    │
    ├─► ClaudeSDKClient.connect()
    │       │
    │       ├─► Create Transport
    │       ├─► Create QueryHandler
    │       ├─► Start reader thread
    │       └─► Initialize (control protocol handshake)
    │
    ├─► client.sendMessage("Hello")
    │       │
    │       └─► Write to transport stdin
    │
    ├─► client.receiveResponse()
    │       │
    │       └─► Iterator reads from message queue
    │               │
    │               ├─► Blocks until message available
    │               ├─► Returns messages until ResultMessage
    │               └─► Auto-closes iterator
    │
    └─► client.close()
            │
            ├─► Close QueryHandler
            ├─► Close Transport
            └─► Cleanup resources
```

### Fluxo de invocação de hooks

```
CLI Process
    │
    ├─► Sends hook request via stdout
    │       │
    │       └─► {"type": "control", "method": "sdk.hook_callback", ...}
    │
QueryHandler
    │
    ├─► Receives hook request
    │       │
    │       ├─► Parse hook event and input
    │       ├─► Match against registered hooks
    │       ├─► Invoke matching hooks in parallel
    │       │       │
    │       │       └─► CompletableFuture.allOf(...)
    │       │
    │       └─► Collect results
    │               │
    │               └─► Combine outputs (logs, messages, updates)
    │
    └─► Send hook response via stdin
            │
            └─► {"id": "...", "result": {...}}
```

### Tratamento de falhas em requisições de controle

Toda `control_request` que chega é tratada na própria thread, submetida com
`ExecutorService.submit(...)` — cujo `Future` ninguém lê. Assim, um `Throwable` que escapasse do
handler desaparecia sem deixar rastro e, como o CLI bloqueia até receber a `control_response`
correspondente, a execução travava sem diagnóstico de nenhum dos lados.

Por isso o `handleControlRequest` captura `Throwable`, não `Exception`. Qualquer falha é registrada
(em `WARNING` para uma exceção comum, `SEVERE` para um `Error`) e respondida com uma resposta de
controle de erro, de modo que o CLI nunca fique esperando; um `Error` genuíno é então relançado em vez
de engolido. Uma requisição que chega sem um `request_id` recuperável só pode ser registrada, já que
não há a quem responder.

Isso não é teórico. Uma classe antiga compilada pela IDE, carregando um `Error` de "unresolved
compilation problem", fazia toda requisição de controle MCP do SDK travar em silêncio até que essa
captura fosse ampliada.

### Ciclo de vida do stdin e tarefas em voo

> **O `controlExecutor` precisa continuar sendo uma thread por tarefa.** Uma chamada de ferramenta MCP
> do SDK estaciona sua thread de controle até a ferramenta responder, e o `notifications/cancelled`
> que a encerra chega como uma requisição de controle *separada*. Sob qualquer pool limitado, esse
> cancelamento ficaria na fila atrás justamente da chamada que ele existe para cancelar, e travaria.
> Nenhum teste pegaria isso — um pool fixo de dois passa em tudo.

Quando há hooks, servidores MCP do SDK ou um callback de permissão `canUseTool` registrados, o
protocolo de controle precisa do stdin aberto pela conversa inteira, então o
`QueryHandler.streamInput()` espera por um quadro `result` que encerre a execução antes de chamar
`transport.endInput()`. Os três são atendidos do mesmo jeito — o CLI escreve uma `control_request` e
bloqueia até o SDK escrever a `control_response` correspondente no stdin — então os três contam como
necessidades bidirecionais (`hasBidirectionalNeeds()`). Fechar cedo demais não é inofensivo: o CLI em
modo stream-json sai **somente** com EOF no stdin, então o fechamento também não pode simplesmente ser
adiado para `close()` — isso travaria um `query()` de uso único para sempre.

A sutileza é que **um quadro `result` encerra um turno, não a execução**. Uma tarefa em segundo plano
continua além dele e ainda precisa do stdin para as respostas de controle de hooks e de MCP do SDK.
Fechar no primeiro resultado fazia as chamadas de ferramenta MCP do SDK de um subagente ainda em
execução falharem com `"Stream closed"` e — de modo mais silencioso — seus hooks `PreToolUse` nunca
eram entregues, então ferramentas embutidas continuavam executando e hooks de bloqueio deixavam de
bloquear.

Por isso o `QueryHandler` mantém um livro-razão de tarefas em voo, alimentado por quadros `system` de
ciclo de vida de tarefa, e só trata um resultado como fim de execução quando o livro está vazio:

```
system: task_started (task_type ∈ DEFERRING_TASK_TYPES)  ─►  add task_id
system: task_notification                                ─►  remove task_id
system: task_updated (patch.status ∈ TERMINAL_TASK_STATUSES) ─► remove task_id

result frame
    ├─ ledger empty     ─►  complete firstResultEvent  ─►  endInput()
    └─ ledger non-empty ─►  keep stdin open, log at FINE
```

Cada conclusão de tarefa acorda o pai para um turno de acompanhamento que termina em outro quadro de
resultado, então o fechamento ainda acontece rápido — e tarefas em segundo plano encadeadas funcionam,
porque o livro só esvazia depois que a última se resolve.

`DEFERRING_TASK_TYPES` é `{"local_agent", "local_workflow"}`. As exclusões são deliberadas, não
descuidos: shells em segundo plano (`local_bash`) e monitores rodam indefinidamente por projeto, e
teammates ficam `running` por toda a sua vida, então nenhum deles chega de forma confiável a um estado
terminal. Rastrear um deles seguraria o fechamento *para sempre* em vez de brevemente — e, sem saída do
processo, nem o `finally` do leitor rodaria. Qualquer coisa acrescentada a esse conjunto precisa ser um
tipo que termina de forma confiável.

Quadros `background_tasks_changed` são ignorados nos dois sentidos. Essa carga útil é o conjunto vivo de
tarefas *em segundo plano*, mas um subagente é registrado em primeiro plano e só depois passa para
segundo plano, sem um segundo `task_started` — então estreitar com base nela derrubaria exatamente o
agente que este livro existe para proteger, e ampliar a partir dela poderia admitir um id que nenhum
quadro posterior limpa.

Isso é uma mitigação, não uma resposta completa: um livro vazio significa "nada que conheçamos está
rodando", o que não é o mesmo que "a execução acabou". Uma tarefa que se resolve *antes* do quadro de
resultado do seu turno deixa o livro vazio naquele resultado. Nenhum livro fecha essa lacuna — seria
preciso um sinal de fronteira de execução vindo do CLI —, mas a ordem comum, em que a tarefa sobrevive
ao turno que a criou, está corrigida.

## Modelo de concorrência

### Arquitetura de threads

O SDK usa uma arquitetura multithread (threads virtuais no Java 21+, threads de plataforma daemon no
17-20):

1. **Thread principal**: a thread da aplicação do usuário
2. **Thread leitora**: lê o stdout do CLI
3. **Executor de controle**: pool de threads para operações assíncronas do protocolo de controle
4. **Executor de streaming**: thread opcional para transmitir mensagens de entrada
5. **Executores de hook**: uma thread por hook ou chamada de ferramenta em voo

### Segurança entre threads

- **AtomicBoolean**: usado para o estado de conexão e de fechamento
- **volatile**: usado para a visibilidade de QueryHandler e Transport
- **Sincronização**: usada em connect() para evitar condições de corrida
- **BlockingQueue**: fila de mensagens thread-safe
- **ConcurrentHashMap**: acompanhamento thread-safe das requisições de controle

### Gerenciamento de recursos

Todos os recursos implementam AutoCloseable:

```java
try (ClaudeSDKClient client = ClaudeSDK.createClient()) {
    // Use client
} // Automatic cleanup: QueryHandler, Transport, Executors
```

## Sistema de tipos

### Hierarquia dos tipos de mensagem

```
Message (sealed interface)
    ├── UserMessage (record)
    ├── AssistantMessage (record)
    │       └── content: List<ContentBlock>   (sealed interface)
    │               ├── TextBlock
    │               ├── ThinkingBlock
    │               ├── ToolUseBlock
    │               ├── ToolResultBlock
    │               ├── ServerToolUseBlock
    │               ├── ServerToolResultBlock
    │               ├── ImageBlock        - PDF page render
    │               ├── DocumentBlock     - whole PDF
    │               └── UnknownBlock      - forward-compat fallback
    ├── SystemMessage (record)
    ├── ResultMessage (record)
    │       └── modelUsage: Map<String, ModelUsage>
    └── StreamEvent (record)
```

As duas hierarquias seladas são amigáveis ao `switch` exaustivo, o que significa que acrescentar um
membro é um evento deliberado de quebra de compatibilidade de código-fonte para quem chama. O
`ContentBlock` ganhou três membros na 0.1.20; o `UnknownBlock` existe para que tipos *não modelados*
não exijam mais nenhuma mudança no SDK — o parser os preserva inteiros e registra uma vez por tipo em
vez de lançar.

### Tipos de configuração

```
ClaudeAgentOptions
    ├── PermissionMode (enum)
    ├── ToolsPreset (record)
    ├── SystemPromptPreset (record)
    ├── SdkBeta (enum)
    ├── SettingSource (enum)
    ├── SandboxSettings (record)
    ├── ThinkingConfig (sealed interface)
    │       ├── ThinkingConfigAdaptive (record)
    │       ├── ThinkingConfigEnabled (record)
    │       └── ThinkingConfigDisabled (record)
    ├── McpServerConfig (sealed interface)
    │       ├── McpStdioServerConfig
    │       ├── McpSseServerConfig
    │       ├── McpHttpServerConfig
    │       └── McpSdkServerConfig
    ├── HookEvent (enum) - 10 events
    ├── HookMatcher (record)
    └── AgentDefinition (record)
```

### Tipos de permissão

```
PermissionResult (sealed interface)
    ├── PermissionResultAllow (record)
    └── PermissionResultDeny (record)
            └── reason: String
```

### Tipos de hook

```
HookInput (sealed interface)
    ├── PreToolUseHookInput
    ├── PostToolUseHookInput
    ├── PostToolUseFailureHookInput
    ├── UserPromptSubmitHookInput
    ├── StopHookInput
    ├── SubagentStopHookInput
    ├── SubagentStartHookInput
    ├── PreCompactHookInput
    ├── NotificationHookInput
    └── PermissionRequestHookInput
```

## Dependências

### Dependências de runtime

1. **Jackson** (2.21.0)
   - `jackson-databind` — serialização/desserialização JSON
   - `jackson-annotations` — anotações JSON
   - Propósito: interpretar as mensagens JSON do CLI, serializar o protocolo de controle

2. **JSpecify** (1.0.0)
   - Anotações de nulidade (`@Nullable`, `@NonNull`)
   - Propósito: melhor segurança contra nulos e suporte na IDE

3. **networknt json-schema-validator** (2.0.4)
   - Propósito: validar os argumentos das ferramentas MCP do SDK contra o `inputSchema` declarado pela
     ferramenta antes de o handler rodar, como a especificação do MCP exige dos servidores
   - Fixado deliberadamente na linha 2.x: a 3.x é construída sobre o Jackson 3 (`tools.jackson`) e
     colocaria uma segunda pilha JSON completa ao lado do Jackson 2 acima. A 2.0.4 é a versão mais
     recente que reaproveita o nosso databind.
   - O leitor de schema YAML dele é excluído (os schemas de ferramenta chegam como mapas já
     interpretados), assim como um formatador de relatório do Surefire que ele declara em escopo
     compile por engano
   - Traz `slf4j-api` (2.0.17) de forma transitiva. O SDK registra logs por `java.util.logging` e
     **não** inclui nenhum binding do SLF4J — escolher um é decisão da aplicação. Uma aplicação sem
     provedor vê um aviso único `No SLF4J providers were found` no stderr na primeira vez que constrói
     um servidor MCP do SDK; adicionar qualquer binding o remove.

### Dependências de teste

1. **JUnit 5** (6.0.2)
   - Framework de testes
   - Propósito: testes unitários e de integração

2. **AssertJ** (3.27.7)
   - Biblioteca de asserções fluentes
   - Propósito: asserções de teste legíveis

3. **Mockito** (5.21.0)
   - Framework de mocks
   - Propósito: simular dependências nos testes

### Dependências de build

1. **Maven Compiler Plugin** (3.14.1)
   - Compilação para Java 17 (`<release>17</release>`) com a flag `-parameters`
   - Propósito: preservar os nomes dos parâmetros para a anotação @Tool

2. **Flatten Maven Plugin** (1.7.3)
   - Resolve a propriedade `${revision}`
   - Propósito: versionamento amigável à CI

3. **Templating Maven Plugin** (3.1.0)
   - Gera SdkVersion.java a partir de um template
   - Propósito: injetar a versão em tempo de build

## Princípios de design

1. **Segurança de tipos**: aproveitar o sistema de tipos do Java (interfaces seladas, records, enums)
2. **Imutabilidade**: os objetos de configuração são imutáveis
3. **Segurança entre threads**: documentar e impor as garantias
4. **Gerenciamento de recursos**: AutoCloseable para a limpeza correta
5. **Padrão builder**: configuração fluente e legível
6. **Falhar cedo**: validar logo e lançar exceções significativas
7. **Casamento de padrões**: usar recursos modernos do Java para um código mais limpo
8. **Threads virtuais**: concorrência leve no Java 21+, de forma transparente
9. **Separação de responsabilidades**: fronteiras claras entre camadas
10. **Extensibilidade**: sistema de plugins e transportes próprios

## Considerações de desempenho

1. **Threads virtuais**: milhares de operações concorrentes são possíveis no Java 21+
2. **E/S com buffer**: reduz chamadas de sistema na comunicação com o subprocesso
3. **Fila de mensagens**: tamanho configurável para equilibrar memória e vazão
4. **Inicialização preguiçosa**: o QueryHandler é criado só quando necessário
5. **Reaproveitamento de recursos**: o ExecutorService é reutilizado entre operações
6. **Memória direta**: o Jackson faz um manejo eficiente de buffers
7. **Cópia mínima**: os objetos de mensagem são records (sem cópia defensiva)

## Extensibilidade futura

A arquitetura dá suporte a melhorias futuras:

1. **Transportes próprios**: implementar a interface Transport para um Claude Code remoto
2. **Novos tipos de mensagem**: acrescentar à hierarquia da interface selada
3. **Novos eventos de hook**: acrescentar ao enum HookEvent
4. **Sistema de plugins**: SdkPluginConfig para extensões próprias
5. **Protocolos alternativos**: substituir a implementação do protocolo de controle
6. **Melhorias de streaming**: suporte ampliado a mensagens parciais
7. **Cache**: acrescentar uma camada de cache entre o SDK e o CLI
8. **Métricas**: acrescentar telemetria e monitoramento de desempenho
