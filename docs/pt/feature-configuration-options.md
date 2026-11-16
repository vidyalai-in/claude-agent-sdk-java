# Opções de configuração

Guia completo para configurar o comportamento do Claude SDK com `ClaudeAgentOptions`.

> **Sobre esta tradução**: a documentação em inglês é a única autoritativa. Esta tradução pode estar desatualizada em relação ao [original em inglês](../feature-configuration-options.md); em caso de divergência, o inglês prevalece. Os blocos de código são mantidos idênticos ao original e não são traduzidos.

## Sumário
- [Visão geral](#visão-geral)
- [Padrão builder](#padrão-builder)
- [Configuração de ferramentas](#configuração-de-ferramentas)
- [Prompt de sistema](#prompt-de-sistema)
- [Servidores MCP](#servidores-mcp)
- [Configurações de permissão](#configurações-de-permissão)
- [Gerenciamento de sessões](#gerenciamento-de-sessões)
- [Limites](#limites)
- [Configuração do modelo](#configuração-do-modelo)
- [Diretório de trabalho e CLI](#diretório-de-trabalho-e-cli)
- [Variáveis de ambiente](#variáveis-de-ambiente)
- [Callbacks](#callbacks)
- [Hooks](#hooks)
- [Recursos avançados](#recursos-avançados)
- [Exemplos completos](#exemplos-completos)

## Visão geral

`ClaudeAgentOptions` oferece mais de 30 opções de configuração para controlar o comportamento do Claude SDK. Usa um padrão builder imutável para uma configuração com segurança de tipos.

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .model("claude-sonnet-5")
    .maxTurns(10)
    .permissionMode(PermissionMode.BYPASS_PERMISSIONS)
    .build();
```

## Padrão builder

### Criar as opções

```java
// Start with builder
ClaudeAgentOptions.Builder builder = ClaudeAgentOptions.builder();

// Configure
builder.model("claude-sonnet-5")
       .maxTurns(10);

// Build immutable instance
ClaudeAgentOptions options = builder.build();
```

### Opções padrão

```java
// Use defaults
ClaudeAgentOptions options = ClaudeAgentOptions.defaults();
```

### Modificar opções existentes

```java
// Create from existing
ClaudeAgentOptions modified = options.toBuilder()
    .maxTurns(20)
    .model("claude-opus-4-6")
    .build();
```

## Configuração de ferramentas

### tools()

Especifica quais ferramentas o Claude pode usar.

```java
// Use all available tools (default)
.tools(null)

// Specify list of tool names
.tools(List.of("Read", "Write", "Bash"))

// Use preset
.tools(new ToolsPreset("code-editing"))
```

**Nomes das ferramentas**:
- `Read` — ler arquivos
- `Write` — escrever/criar arquivos
- `Edit` — editar arquivos existentes
- `Bash` — executar comandos bash
- `Grep` — buscar no conteúdo dos arquivos
- `Glob` — encontrar arquivos por padrão
- `Task` — iniciar subagentes
- `WebFetch` — buscar conteúdo da web
- `WebSearch` — pesquisar na web
- Ferramentas MCP: `mcp__<server>__<tool>`

### allowedTools()

Coloca ferramentas específicas na lista de permitidas.

```java
.allowedTools(List.of(
    "Read",
    "Grep",
    "Glob",
    "mcp__calc__add"
))
```

### disallowedTools()

Coloca ferramentas específicas na lista de bloqueadas.

```java
.disallowedTools(List.of(
    "Bash",      // Block shell access
    "Write",     // Block file writing
    "WebFetch"   // Block web access
))
```

**Prioridade**: `disallowedTools` tem precedência sobre `allowedTools`.

## Prompt de sistema

### systemPrompt()

Define um prompt de sistema personalizado para guiar o comportamento do Claude.

```java
// String prompt
.systemPrompt("You are a code reviewer. Focus on security and performance.")

// Multi-line prompt
.systemPrompt("""
    You are a helpful coding assistant.
    - Be concise
    - Provide working code examples
    - Explain your reasoning
    """)

// Use Claude Code preset
.systemPrompt(SystemPromptPreset.claudeCode())

// Use Claude Code preset with additional instructions
.systemPrompt(SystemPromptPreset.claudeCode("Always respond in JSON format."))

// Use Claude Code preset with exclude_dynamic_sections for cross-user caching
.systemPrompt(SystemPromptPreset.claudeCode("Custom instructions", true))

// Custom prompt in the form that can also set snapshot (see below)
.systemPrompt(SystemPromptCustom.of("You are a release bot.", false))

// Use prompt from file
.systemPrompt(new SystemPromptFile("/path/to/prompt.md"))
```

| Forma | Enviado ao CLI como |
|-------|---------------------|
| `String` | `--system-prompt <text>` |
| `SystemPromptCustom` | `--system-prompt <prompt>`, mais `snapshot` na requisição `initialize` |
| `SystemPromptPreset` | `--append-system-prompt <append>` quando `append` está definido; `excludeDynamicSections` e `snapshot` na requisição `initialize` |
| `SystemPromptFile` | `--system-prompt-file <path>` |
| não definido (`null`) | `--system-prompt ""` (sem prompt de sistema) |

#### Snapshot

Por padrão, o Claude Code monta o prompt de sistema na primeira requisição de uma sessão,
registra-o e o reutiliza em todas as requisições seguintes, inclusive depois que você retoma
a sessão. Um prompt personalizado alterado, ou um texto `append` alterado no preset, então não
tem efeito até que a sessão seja compactada ou que você inicie uma nova sessão. Para, em vez
disso, remontar o prompt a cada requisição — por exemplo, enquanto você itera sobre a redação
dele —, defina `snapshot` como `false`:

```java
.systemPrompt(SystemPromptCustom.of("You are a release bot.", false))
.systemPrompt(SystemPromptPreset.claudeCode("Be concise.").withSnapshot(false))
```

`snapshot` viaja na requisição de controle `initialize` como `systemPromptSnapshot`. Ele é
enviado sempre que definido, inclusive `false`, e omitido quando `null`, caso em que vale o
padrão do CLI: `true`, exceto no modo bare (`--bare`), em que se comporta como `false`. Só as
formas preset e personalizada o carregam. Requer o Claude Code CLI 2.1.257 ou posterior; antes
da 2.1.265, uma sessão com um prompt `append` ou personalizado só o registrava quando
`snapshot` era `true`.

## Servidores MCP

### mcpServers()

Configura servidores Model Context Protocol para ferramentas personalizadas.

```java
// SDK MCP server (in-process)
McpSdkServerConfig sdkServer = ClaudeSDK.createSdkMcpServer(
    "my-tools",
    new MyTools()
);

.mcpServers(Map.of("tools", sdkServer))

// External stdio server
McpStdioServerConfig externalServer = new McpStdioServerConfig(
    "node",
    List.of("server.js"),
    Map.of("NODE_ENV", "production")
);

.mcpServers(Map.of(
    "sdk", sdkServer,
    "external", externalServer
))

// From file path (passed as-is to --mcp-config; Java does not expand "~")
.mcpServers(Path.of(System.getProperty("user.home"), ".claude", "mcp_servers.json"))

// From an inline JSON string (also passed as-is to --mcp-config)
.mcpServersJson("""
    {"mcpServers": {
        "server1": {"type": "stdio", "command": "node", "args": ["server.js"]}
    }}
    """)
```

Um map é serializado como `{"mcpServers": {...}}` antes de chegar a `--mcp-config`; um caminho
ou uma string JSON é repassado sem alterações, então uma string JSON precisa usar essa mesma
chave `mcpServers` de nível superior. Um map que não pode ser serializado faz `connect()` falhar com
`CLIConnectionException` (desde a 0.2.6; antes, a flag era descartada com um aviso). Campos não
definidos de uma configuração de servidor ficam de fora do JSON em vez de serem enviados como `null`,
então `new McpStdioServerConfig("node")` ou `new McpHttpServerConfig(url)` carrega como esperado.

## Configurações de permissão

### permissionMode()

Controla como as permissões de ferramentas são tratadas.

```java
.permissionMode(PermissionMode.BYPASS_PERMISSIONS)
```

**Modos**:
- `DEFAULT` (o padrão do CLI) — comportamento de permissão padrão
- `ACCEPT_EDITS` — aceita edições de arquivo automaticamente, pergunta nas demais
- `PLAN` — modo de planejamento; nenhuma ferramenta é executada
- `BYPASS_PERMISSIONS` — ignora completamente as verificações de permissão
- `DONT_ASK` — não pergunta; nega tudo o que não estiver pré-aprovado por regras de permissão (allow)
- `AUTO` — um classificador baseado em modelo aprova ou nega cada chamada de ferramenta

### permissionPromptToolName()

Especifica a ferramenta usada nos pedidos de permissão (avançado, normalmente definido automaticamente).

```java
.permissionPromptToolName("stdio")
```

## Gerenciamento de sessões

### continueConversation()

Continua a conversa anterior.

```java
.continueConversation(true)  // Continue from last session
.continueConversation(false) // Start fresh (default)
```

### resume()

Retoma uma sessão específica pelo ID.

```java
.resume("session-12345")
```

### sessionId()

Especifica um ID de sessão para a nova sessão.

```java
.sessionId("my-custom-session-id")
```

### forkSession()

Bifurca a sessão retomada em uma nova sessão (mantém o contexto, com novo ID).

```java
.resume("session-12345")
.forkSession(true)
```

### sessionStore()

Espelha as transcrições da sessão em um store externo (S3, Postgres, Redis, backend próprio). Quando definido, o SDK adiciona `--session-mirror` à invocação do CLI e encaminha cada linha da transcrição para `store.appendAsync(...)`. Retomar com `sessionStore` materializa o conteúdo do store em um `CLAUDE_CONFIG_DIR` temporário para que o CLI retome a conversa localmente. Veja o [guia do Session Store](./feature-session-store.md) para o recurso completo.

```java
import in.vidyalai.claude.sdk.types.session.InMemorySessionStore;

InMemorySessionStore store = new InMemorySessionStore();

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .sessionStore(store)
    .build();
```

**Guardas de validação** (rejeitadas com `IllegalArgumentException` antes de iniciar o subprocesso):
- `continueConversation + sessionStore` exige `store.implementsListSessions()`.
- `sessionStore + enableFileCheckpointing` é rejeitado — checkpoints existem apenas em disco local.

**Os resultados esperam o store; as chamadas de controle, não (desde a 0.2.7).** Cada `ResultMessage`, e toda mensagem depois dele, fica retido até o store anexar o turno, então você nunca vê um resultado que o store não tem. O leitor continua lendo nesse meio-tempo, então uma chamada de controle como `interrupt()` é respondida na hora, e as requisições `canUseTool` e de hook do CLI são atendidas. Um turno que começa antes de o resultado anterior ser entregue (prompts de entrada em streaming enviados adiantados, um turno de continuação de um agente em segundo plano) pode, portanto, ter seu callback executado antes de você receber seu `tool_use`; enviar o próximo prompt depois que `receiveResponse()` retorna não é afetado. O `close()` espera a gravação final. Cada espera é limitada pelo tempo limite de gravação do batcher, 181 s por padrão. Veja o [guia do Session Store](./feature-session-store.md#modo-de-gravação-batched-vs-eager).

### sessionStoreFlush()

Controla quando as entradas espelhadas da transcrição são gravadas no `sessionStore` configurado. O padrão é `SessionStoreFlushMode.BATCHED` (uma gravação por turno ou quando o buffer transborda). Use `SessionStoreFlushMode.EAGER` para agendar uma gravação em segundo plano após cada quadro, com entrega quase em tempo real — os appends continuam serializados na ordem de enfileiramento, mas um adaptador lento não trava o laço de leitura entre resultados (a gravação antes de cada `ResultMessage` ainda espera por ele, nos dois modos). Ignorado quando `sessionStore` não está definido.

```java
import in.vidyalai.claude.sdk.types.session.SessionStoreFlushMode;

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .sessionStore(store)
    .sessionStoreFlush(SessionStoreFlushMode.EAGER)
    .build();
```

Veja [Modo de gravação (batched vs eager)](./feature-session-store.md#modo-de-gravação-batched-vs-eager) para os trade-offs.

### loadTimeoutMs()

Tempo limite por chamada de `store.loadAsync()` e `listSubkeysAsync()` durante a materialização da retomada, em milissegundos. O padrão é `60_000`. Se um adaptador não responder nesse intervalo, a consulta falha com um erro claro em vez de travar o iterador.

```java
.loadTimeoutMs(30_000)  // 30 seconds
```

## Limites

### maxTurns()

Número máximo de turnos da conversa.

```java
.maxTurns(10)  // Limit to 10 turns
```

`0` conta como não definido: como o SDK Python, o SDK omite `--max-turns` nesse caso, então vale o padrão do CLI.
O mesmo vale para uma string vazia em `model`, `fallbackModel`,
`permissionPromptToolName`, `resume`, `sessionId` e `settings`, e para um
`mcpServersJson` vazio: desde a 0.2.6, a flag é omitida em vez de ser enviada vazia.

**Casos de uso**:
- Controle de orçamento
- Evitar conversas fora de controle
- Consultas rápidas: `.maxTurns(1)`

### maxBudgetUsd()

Custo máximo em dólares americanos.

```java
.maxBudgetUsd(1.0)  // Limit to $1.00
```

Interrompe a execução quando o orçamento é ultrapassado.

### taskBudget()

Orçamento de tarefa em tokens, do lado da API. Quando definido, o modelo fica ciente do orçamento de tokens restante.

```java
.taskBudget(new TaskBudget(100000))  // 100K token budget
```

### maxBufferSize()

Número máximo de bytes usados no buffer da saída padrão do CLI.

```java
.maxBufferSize(10 * 1024 * 1024)  // 10MB
```

Padrão: 100MB. Aumente para saídas grandes.

### thinking()

**NOVO**: controla o comportamento do raciocínio estendido com configuração detalhada.

```java
// Adaptive thinking (32K token default)
.thinking(new ThinkingConfigAdaptive())

// Fixed token budget
.thinking(new ThinkingConfigEnabled(10000))

// Disable thinking
.thinking(new ThinkingConfigDisabled())
```

**Tipos**:
- `ThinkingConfigAdaptive` — raciocínio adaptativo, com 32.000 tokens por padrão
- `ThinkingConfigEnabled(int budgetTokens)` — orçamento fixo de tokens (zero ou mais; `0` é enviado como `--max-thinking-tokens 0`)
- `ThinkingConfigDisabled` — sem tokens de raciocínio

**Nota**: esta opção tem precedência sobre o obsoleto `maxThinkingTokens()`.

Veja [Configuração do raciocínio estendido](./feature-thinking-config.md) para o guia completo.

### effort()

Define o nível de profundidade/intensidade do raciocínio. Há duas sobrecargas — passe a string
bruta ou o enum [`EffortLevel`](#enum-effortlevel), com segurança de tipos.

```java
// String overload
.effort("low")     // Minimal thinking, fastest responses
.effort("medium")  // Moderate thinking
.effort("high")    // Deep reasoning (default)
.effort("xhigh")   // Extended depth (Opus 4.7 only; falls back to "high")
.effort("max")     // Maximum reasoning

// Enum overload (recommended for type safety)
.effort(EffortLevel.HIGH)
.effort(EffortLevel.XHIGH)
.effort((EffortLevel) null)  // clear
```

**Valores válidos**: `"low"`, `"medium"`, `"high"`, `"xhigh"`, `"max"`

`"xhigh"` é específico do Opus 4.7 e cai para `"high"` em outros modelos.

Funciona em conjunto com `thinking()` para controlar a profundidade do raciocínio.

Veja [Configuração do raciocínio estendido](./feature-thinking-config.md) para exemplos.

### Enum EffortLevel

Enum público em `in.vidyalai.claude.sdk.types.config.EffortLevel`, espelhando o alias de tipo
`EffortLevel` do Python. Exposto para que wrappers de SDK derivados possam referenciar o tipo
diretamente.

| Constante | Valor no protocolo | Descrição |
|----------|------------|-------------|
| `EffortLevel.LOW` | `"low"` | Raciocínio mínimo, respostas mais rápidas |
| `EffortLevel.MEDIUM` | `"medium"` | Raciocínio moderado |
| `EffortLevel.HIGH` | `"high"` | Raciocínio profundo (padrão) |
| `EffortLevel.XHIGH` | `"xhigh"` | Raciocínio estendido (só Opus 4.7; cai para `HIGH`) |
| `EffortLevel.MAX` | `"max"` | Esforço máximo |

Auxiliares:
- `EffortLevel.getValue()` devolve o valor em minúsculas usado no protocolo (também é o serializador `@JsonValue`).
- `EffortLevel.fromValue(String)` converte um valor do protocolo de volta em constante do enum; lança `IllegalArgumentException` para valores desconhecidos.

```java
EffortLevel level = EffortLevel.fromValue("xhigh");
String wire = level.getValue(); // "xhigh"
```

### maxThinkingTokens()

**OBSOLETO**: use `thinking()` em vez disso: adaptativo, habilitado com um orçamento de
tokens ou desabilitado.

Número máximo de tokens para os blocos de raciocínio. Nos modelos mais novos, esse valor é
tratado como liga/desliga (0 = desabilitado, qualquer outro valor = adaptativo).

```java
.maxThinkingTokens(10000)  // Deprecated - use thinking() instead
```

### maxMsgQSize()

Tamanho máximo da fila de mensagens.

```java
.maxMsgQSize(1000)
```

Padrão: 1000. Quando a fila está cheia, o leitor espera até o seu código retirar uma mensagem
(contrapressão, como o envio com await do SDK Python), então um consumidor lento desacelera o CLI
em vez de perder saída. Antes da 0.2.6, uma mensagem que encontrasse a fila cheia por 5 segundos era
descartada. O fim do fluxo e qualquer erro são sempre entregues, mesmo a um iterador criado depois.
Aumente o tamanho em cenários de alta vazão.

É um limite flexível (desde a 0.2.6). Enquanto uma requisição de controle que o SDK enviou
(`interrupt()`, `setModel()`, `getMcpStatus()`, ...) espera sua resposta, o leitor continua lendo
além do limite, porque essa resposta chega pelo mesmo stdout que as mensagens. Uma chamada de
controle feita antes de alguém drenar a fila, portanto, não expira mais atrás de uma fila cheia.
Nada é descartado e a ordem é mantida. As mensagens retidas por um `sessionStore` lento contam para o
limite e, enquanto houver alguma retida, o excesso tem um teto rígido de quatro vezes
`maxMsgQSize` (desde a 0.2.7): você não consegue drenar mensagens retidas, então, além dele, o
leitor espera mesmo com uma requisição de controle pendente e, nesse caso extremo, a chamada de
controle pode expirar. Sem nada retido não há teto. O valor precisa ser pelo menos 1: `Builder.maxMsgQSize`
rejeita um menor com `IllegalArgumentException`.

## Configuração do modelo

### model()

Define o modelo de IA.

```java
.model("claude-sonnet-5")
```

**Modelos disponíveis**:
- `claude-opus-4-6` — o mais capaz, caro
- `claude-sonnet-4-5` — equilibrado (padrão)
- `claude-haiku-4-5` — rápido e econômico

### fallbackModel()

Modelo alternativo caso o principal não esteja disponível.

```java
.model("claude-opus-4-6")
.fallbackModel("claude-sonnet-4-5")
```

### betas()

Habilita recursos beta.

```java
.betas(List.of(
    SdkBeta.PROMPT_CACHING,
    SdkBeta.EXTENDED_THINKING
))
```

Veja [Anthropic API Beta Headers](https://docs.anthropic.com/en/api/beta-headers).

## Diretório de trabalho e CLI

### cwd()

Define o diretório de trabalho para operações de arquivo.

```java
.cwd(Path.of("/path/to/project"))
```

**Importante**: sempre defina em operações de arquivo para garantir caminhos corretos.

### cliPath()

Caminho personalizado para o CLI do Claude Code.

```java
.cliPath(Path.of("/custom/path/to/claude"))
```

Padrão: procura o CLI. Em POSIX, isso significa o `PATH` e depois alguns locais de instalação
comuns. No Windows, só é encontrado um `claude.exe` nativo, e apenas nas entradas absolutas do
`PATH`, e depois em `~/.local/bin/claude.exe`; veja [Camada de transporte →
Descoberta do CLI no Windows](./feature-transport-layer.md#windows-ordem-de-descoberta-do-cli-0121).

**Windows:** um caminho `.bat`/`.cmd` (o shim `claude.cmd` do npm) é recusado — o sistema operacional o executaria via `cmd.exe`, que reinterpreta a linha de comando. Aponte para um `claude.exe` ou veja `allowUnsafeWindowsBatchCli()` abaixo.

### allowUnsafeWindowsBatchCli()

Dispensa a recusa de scripts batch no Windows para implantações que não conseguem migrar para um `claude.exe` nativo. Padrão `false`.

```java
.cliPath(Path.of("C:\\Users\\Administrator\\AppData\\Roaming\\npm\\claude.cmd"))
.allowUnsafeWindowsBatchCli(true)
```

Isto **não** é um simples bypass — uma dispensa pura restauraria toda a brecha de reinterpretação do `cmd.exe`. Ao habilitá-la, adicionalmente:

1. **Exige `-Djdk.lang.Process.allowAmbiguousCommands=false`** na JVM. Essa propriedade vale `true` por padrão, e nesse caso o JDK só coloca aspas em torno de espaços ao iniciar um batch; com `false` ele passa a citar `" < > & | ^` e rejeita argumentos que contenham aspas. `connect()` lança `CLIConnectionException` se a flag estiver ausente.
2. **Rejeita `& | < > ^ % ! "` e CR/LF em todo argumento do CLI**, lançando `IllegalArgumentException` com o nome da opção problemática. `%` e `!` não estão no conjunto de escape do JDK, e as aspas não impedem a expansão de `%VAR%`.
3. **Registra um `WARNING`** nomeando o risco aceito.

A habilitação também permite que a descoberta do CLI retorne o shim `claude.cmd` do npm (ou um
`claude.bat`) de uma entrada absoluta do `PATH` quando não existe nenhum `claude.exe`, de modo que
`cliPath()` pode ficar sem definir. Sem ela, a descoberta nunca retorna um shim.

**Risco residual:** o cmd.exe ainda expande `%VAR%` a partir do ambiente. Use apenas onde o caminho do CLI e todos os valores de argumento sejam controlados pelo administrador. Ignorado em POSIX. Veja [Camada de transporte → habilitação de CLI em batch](./feature-transport-layer.md#windows-habilitação-de-cli-em-batch-0122).

### settings()

Caminho para um arquivo JSON de configurações adicional, ou uma string JSON inline.

```java
.settings("/path/to/settings.json")
.settings("{\"permissions\": {\"allow\": [\"Read\"]}}")
```

Sem `sandbox()`, o valor é repassado como está para `--settings`. Com `sandbox()` também
definido, os dois são mesclados em uma única string JSON: um valor que começa com `{` e termina
com `}` é interpretado como JSON, e qualquer outro é lido como caminho de arquivo. Um caminho
relativo é resolvido em relação a `cwd()` quando ele está definido, como o CLI o resolve, e uma
marca de ordem de bytes (BOM) UTF-8 é removida. Elas são carregadas na camada de "flag settings" do
CLI, a de maior prioridade entre as configurações controladas pelo usuário.

Quando as configurações não podem ser mescladas, `connect()` falha com
`CLIConnectionException("Failed to load settings: ...")` em vez de iniciar o CLI apenas com as
configurações do sandbox, o que descartaria em silêncio suas regras de permissão e seus hooks. Isso
cobre um arquivo ausente ou ilegível, um diretório, JSON inválido (inline ou em arquivo), JSON que
não é um objeto e conteúdo depois do objeto. A mensagem informa a linha e a coluna de um erro de
JSON, mas nunca cita o texto das configurações, que pode conter segredos.

### addDirs()

Diretórios adicionais para acrescentar ao contexto.

```java
.addDirs(List.of(
    Path.of("/path/to/lib"),
    Path.of("/path/to/docs")
))
```

## Variáveis de ambiente

### env()

Define variáveis de ambiente para o processo do CLI.

```java
.env(Map.of(
    "API_KEY", "secret-key",
    "DEBUG", "true",
    "NODE_ENV", "production"
))
```

O map é mesclado sobre o ambiente do processo pai: as entradas aqui sobrescrevem os valores
herdados. `CLAUDECODE` é removida do conjunto herdado, e `CLAUDE_AGENT_SDK_DERIVED_CLIENT_APP` é
sempre removida, já que uma herdada de uma sessão externa do SDK nomeia o app daquela sessão. O
transporte também define:

| Variável | Quando | Pode ser sobrescrita aqui |
|----------|--------|---------------------------|
| `CLAUDE_CODE_ENTRYPOINT=sdk-java` | sempre | sim |
| `CLAUDE_AGENT_SDK_VERSION` | sempre | não |
| `CLAUDE_AGENT_SDK_CLIENT_APP` | `clientApp(...)` está definido (veja [clientApp()](#clientapp)) | não: `clientApp()` prevalece |
| `CLAUDE_CODE_SDK_READS_SESSION_STATE=1` | a menos que este map ou o ambiente herdado já a nomeie (com qualquer combinação de maiúsculas e minúsculas) | sim |
| `CLAUDE_CODE_ENABLE_SDK_FILE_CHECKPOINTING=true` | `enableFileCheckpointing(true)` | — |
| `PWD` | `cwd(...)` está definido | — |
| `TRACEPARENT` / `TRACESTATE` | há um span do OpenTelemetry ativo (veja [Trace Context](./feature-trace-context.md)) | sim |

Duas variáveis definidas aqui ajustam por quanto tempo uma consulta pontual mantém o stdin
aberto para hooks e chamadas a servidores MCP do SDK (veja [Arquitetura → Ciclo de vida do stdin](./architecture.md#ciclo-de-vida-do-stdin-e-o-fim-de-uma-execução)):
`CLAUDE_CODE_PRINT_BG_WAIT_CEILING_MS` limita a espera entre turnos (padrão `600000`, `0` para
sem limite), e `CLAUDE_CODE_EMIT_SESSION_STATE_EVENTS=1` habilita os quadros
`session_state_changed` do CLI.

### clientApp()

O identificador do seu app ou biblioteca, enviado nas requisições à API.

```java
.clientApp("my-app/1.0.0")
```

Define `CLAUDE_AGENT_SDK_CLIENT_APP` para o subprocesso do CLI. Prevalece sobre um valor para essa
variável em `env()` ou no ambiente herdado. Passe `""` para não enviar identificador; `null` (o
padrão) deixa o ambiente como está. O valor precisa ser válido em um cabeçalho HTTP: um caractere
acima de U+00FF faz toda requisição parar.

Ao contrário do SDK Python, este SDK nunca deriva um identificador padrão. O Python só deriva um
para o CLI empacotado em seu wheel, e este SDK não traz um CLI empacotado.

### extraArgs()

Passa flags arbitrárias para o CLI.

```java
.extraArgs(Map.of(
    "replay-user-messages", "",   // flag with no value: --replay-user-messages
    "debug", "api"                // flag with a value: --debug api
))
```

As chaves são nomes de flags **sem** o `--` inicial; o transporte o acrescenta. Um valor `null`
ou em branco gera uma flag sem valor. Um valor que começa com `-` é enviado na forma
`--flag=value`, para que não seja interpretado como uma flag separada; qualquer outro valor é
enviado como dois tokens.

Desde a 0.2.6, o mapa pode conter valores `null` (a forma do SDK Python de escrever uma flag sem
valor), e as flags são passadas na ordem de iteração do mapa. `Map.of` não aceita nulos e não tem
ordem definida, então use um `LinkedHashMap` quando qualquer um dos dois importar:

```java
Map<String, String> flags = new LinkedHashMap<>();
flags.put("replay-user-messages", null);   // --replay-user-messages
flags.put("debug", "api");                  // --debug api, always after it
.extraArgs(flags)
```

## Callbacks

### canUseTool()

Callback de permissão personalizado para ferramentas. Mutuamente exclusivo com
`permissionPromptToolName`.

```java
.canUseTool((toolName, input, context) -> {
    // Check permission
    if (isAllowed(toolName)) {
        return CompletableFuture.completedFuture(
            new PermissionResultAllow()
        );
    } else {
        return CompletableFuture.completedFuture(
            new PermissionResultDeny("Tool not allowed")
        );
    }
})
```

**Assinatura**:
```java
BiFunction<String, Object, ToolPermissionContext, CompletableFuture<PermissionResult>>
```

### stderrCallback()

Recebe a saída de erro do CLI. Invocado uma vez por linha de stderr conforme o CLI a emite.
O stderr só é redirecionado quando esse callback está definido. Sem ele, o CLI herda o stderr da
JVM desde a 0.2.6, como no SDK Python, então a saída aparece no stderr do seu processo. (Antes ele
era redirecionado e nunca lido, o que podia bloquear o CLI depois que ele escrevesse mais que um
buffer de pipe.)

As linhas são delimitadas como o SDK Python as delimita: divididas apenas em `\n` (um `\r` isolado,
como o de uma linha de progresso, permanece na linha), com os espaços em branco finais removidos e as
linhas em branco ignoradas. Uma linha mais longa que `maxBufferSize` é repassada em pedaços, e uma
última linha sem quebra de linha ainda é entregue.

```java
.stderrCallback(line -> {
    System.err.println("CLI stderr: " + line);
})
```

**Isolamento de exceções:** se o seu callback lançar, a exceção é capturada, registrada em
`FINE` (`java.util.logging`) e a leitura do stderr continua. Um callback com defeito não
consegue mais encerrar silenciosamente o laço de leitura e descartar todas as linhas de stderr
seguintes pelo resto da sessão.

## Hooks

### hooks()

Registra callbacks de hook para eventos do ciclo de vida.

```java
HookMatcher.HookCallback beforeRead = (input, context) -> {
    System.out.println("About to read file");
    return CompletableFuture.completedFuture(HookOutput.empty());
};

.hooks(Map.of(
    HookEvent.PRE_TOOL_USE, List.of(new HookMatcher("Read", List.of(beforeRead)))
))
```

Um callback recebe o `HookInput` tipado e um `HookContext`, e retorna um
`CompletableFuture<HookOutput>`. Veja [Hooks](./feature-hooks.md) para as
entradas, as saídas e as saídas específicas de hook.

Um matcher também pode definir um tempo limite e uma política `onFailure`. Com
`HookOnFailure.BLOCK`, um callback que falha bloqueia a ação em vez de deixá-la ser executada:

```java
new HookMatcher("Bash", List.of(guard), null, HookOnFailure.BLOCK)
```

Veja [Hooks → Política de falha](./feature-hooks.md#política-de-falha-onfailure).

**Eventos disponíveis**:
- `PRE_TOOL_USE` — antes de executar a ferramenta
- `POST_TOOL_USE` — após o sucesso da ferramenta
- `POST_TOOL_USE_FAILURE` — após a falha da ferramenta
- `USER_PROMPT_SUBMIT` — o usuário envia uma mensagem
- `STOP` — a sessão para
- `SUBAGENT_START` — o subagente inicia
- `SUBAGENT_STOP` — o subagente para
- `PRE_COMPACT` — antes da compactação de mensagens
- `NOTIFICATION` — eventos de notificação
- `PERMISSION_REQUEST` — permissão solicitada

## Recursos avançados

### user()

**Obsoleto e sem suporte no SDK Java.** O SDK Python executa o subprocesso do CLI como esse usuário
do sistema operacional; o `ProcessBuilder` do Java não consegue trocar de usuário. Até a 0.2.5, a
opção era aceita e ignorada em silêncio, então o CLI rodava como o usuário da JVM. Desde a 0.2.6,
`connect()` (e `ClaudeSDK.query(...)`) falha com `CLIConnectionException` quando ela está definida.
Remova-a das suas opções. Para executar o CLI como outro usuário, aponte [`cliPath()`](#clipath) para
um script wrapper que troque de usuário (por exemplo, um que execute `sudo -u <user> claude "$@"`).

### includePartialMessages()

Habilita o streaming de mensagens parciais.

```java
.includePartialMessages(true)
```

Recebe mensagens `StreamEvent` com deltas conforme o conteúdo é gerado, uma por evento de
stream da API.

### verbatimPrompts()

Entrega cada prompt ao Claude exatamente como foi escrito.

```java
.verbatimPrompts(true)
```

Normalmente, o Claude Code expande um `@/absolute/path` no texto do prompt para o conteúdo
desse arquivo, fora do diretório de trabalho e sem uma chamada de ferramenta, e despacha um
`/command` inicial como comando de barra. Isso é adequado para texto digitado por um usuário;
não é adequado para texto que sua aplicação montou a partir de outras fontes (turnos
anteriores, resultados de ferramentas, conteúdo de terceiros). Com esta opção ativada, toda
mensagem de usuário que o SDK escreve é marcada com `client_composed: true`, e o Claude Code a
entrega exatamente como recebida. Isso cobre os prompts em string e por streaming de
`ClaudeSDK.query`, além de `ClaudeSDKClient.connect(String)`, `query(String)` e
`query(Iterator)`.

- A marcação é aplicada a uma cópia; seus maps de mensagem nunca são modificados.
- Enquanto a opção está ativada, ela sobrescreve qualquer valor de `client_composed` em uma
  mensagem enviada por streaming. Para controle por turno, deixe-a desativada e coloque
  `"client_composed": true` em mensagens individuais do streaming; o SDK repassa esse valor sem
  alterações.
- Nas versões atuais do Claude Code, um turno literal também pula a etapa de anexos do início
  do turno: menções MCP `@server:resource` não são expandidas, e o prompt é enviado sem o
  contexto normalmente anexado a ele (arquivos `CLAUDE.md` aninhados e de regras, listagens de
  skills e ferramentas, outros lembretes por turno). A maior parte desse contexto chega, em vez
  disso, após a primeira chamada de ferramenta do turno.
- Requer o Claude Code 2.1.248 ou posterior. Versões mais antigas ignoram o campo, e o
  transporte registra um `WARNING` no momento da conexão quando detecta uma delas.

Veja `examples/VerbatimPromptsExample.java`.

### forwardSubagentText()

Encaminha os blocos de texto e de raciocínio de um subagente para o fluxo de mensagens.

```java
.forwardSubagentText(true)
```

Por padrão, apenas os blocos `tool_use` / `tool_result` do subagente chegam ao fluxo do pai,
como objetos `AssistantMessage` / `UserMessage` cujo `parentToolUseId` é o id do bloco
`tool_use` do Agent que criou o subagente — suficiente para indicar progresso, mas não para
mostrar o que o subagente disse. Com esta opção, os blocos de texto e de raciocínio chegam
da mesma forma.

Enviado ao CLI na requisição de controle `initialize` em vez de como flag, e apenas quando
habilitado; CLIs antigos ignoram. Veja
[Agents → Observando a saída de um subagente](./feature-agents.md#observando-a-saída-de-um-subagente).

### agents()

Define configurações de agentes personalizados.

```java
.agents(Map.of(
    "my-agent", new AgentDefinition(
        "Custom agent",
        "claude-sonnet-4-5",
        List.of("Read", "Write"),
        "You are a specialized agent"
    )
))
```

### settingSources()

Controla quais arquivos de configuração são carregados.

```java
.settingSources(List.of(
    SettingSource.USER,     // ~/.claude/
    SettingSource.PROJECT,  // .claude/ in project
    SettingSource.LOCAL     // .claude.local/
))
```

**Uma lista vazia desativa todas as fontes.** Passe `List.of()` para enviar `--setting-sources=` (vazio) ao CLI, suprimindo todas as fontes de configuração do sistema de arquivos. Quando a opção é **omitida por completo** (o padrão), nenhuma flag `--setting-sources` é adicionada e o CLI aplica seus próprios padrões, que carregam todas as fontes.

Use `List.of()` para isolar uma sessão das configurações do sistema de arquivos, por exemplo quando o `cwd` pode não ser confiável: as configurações de projeto podem executar hooks. Inclua `SettingSource.PROJECT` para carregar os arquivos `CLAUDE.md`.

### skills() / skillsAll()

Lista de skills permitidas no nível principal da sessão. O SDK injeta automaticamente as entradas `Skill(name)` correspondentes em `allowedTools` e define `settingSources` como user/project por padrão, para que o CLI descubra as skills instaladas sem configuração extra. A lista também é propagada pela requisição de controle initialize, para que um CLI compatível possa filtrar quais skills são carregadas no prompt de sistema (CLIs antigos ignoram o campo).

```java
// Enable every discovered skill
.skillsAll()

// Enable only the listed skills
.skills(List.of("commit", "review"))

// Suppress every skill from the listing
.skills(List.of())
```

Três modos:

| Chamada no builder | Injeção em `allowedTools` | Padrão de `settingSources` | Campo no initialize |
|---|---|---|---|
| _omitido_ (null) | nenhuma | nenhum | omitido |
| `.skillsAll()` | adiciona `Skill` puro | `[user, project]` | omitido |
| `.skills(List.of("a", "b"))` | adiciona `Skill(a)`, `Skill(b)` | `[user, project]` | `["a", "b"]` |
| `.skills(List.of())` | nenhuma | `[user, project]` | `[]` |

Detalhes de comportamento:
- **Injeção idempotente** — se `allowedTools` já contém `Skill` ou `Skill(name)`, o SDK não duplica.
- **Não muta** — aplicar os padrões de skills cria uma nova lista; o `ClaudeAgentOptions` original nunca é modificado.
- **Um `settingSources` explícito vence** — se você definir `.settingSources(...)` junto de `.skills(...)`, seu valor é preservado.
- **Os nomes são validados** (0.1.22) — cada nome listado deve ser o `name` do SKILL.md da skill / o nome do diretório, ou `plugin:skill`. Delimitadores de regra (parênteses, vírgulas), caracteres de controle, curingas (`"*"`, `"pdf:*"`), uma `/` inicial e espaços ao redor lançam `IllegalArgumentException` no `connect()`. **Quebra de compatibilidade:** `skills(List.of("*"))` e `skills(List.of("plugin:*"))` antes criavam uma regra com curinga e agora lançam exceção — use `.skillsAll()`. Veja [Skills → Validação de nomes](./feature-skills.md#validação-de-nomes-0122).
- **É um filtro de contexto, não um sandbox** — skills não listadas ficam ocultas na listagem do modelo e não podem ser invocadas pela ferramenta `Skill`, mas seus arquivos continuam em disco; uma sessão com `Read`/`Bash` ainda acessa `.claude/skills/**` diretamente.

### sandbox()

Configura o sandbox de comandos bash.

Quando habilitado, os comandos são executados em um ambiente isolado que restringe o acesso ao
sistema de arquivos e à rede. As restrições de sistema de arquivos e de rede no nível das
ferramentas continuam sendo configuradas com regras de permissão (`Read`/`Edit` para o sistema
de arquivos, `WebFetch` para a rede); a configuração `network` abaixo configura o isolamento de
rede do próprio sandbox para comandos bash executados nele. Definir um sandbox também muda a
forma como `settings()` é repassado; veja [settings()](#settings).

```java
// Minimal: just enable sandboxing.
.sandbox(new SandboxSettings(true))
```

Para um controle mais fino, forneça o record completo:

```java
SandboxNetworkConfig network = new SandboxNetworkConfig(
    List.of("api.example.com", "*.npmjs.org"),  // allowedDomains
    List.of("malicious.example.com"),           // deniedDomains (always blocked)
    /* allowManagedDomainsOnly */ false,
    List.of("/tmp/ssh-agent.sock"),             // allowUnixSockets
    /* allowAllUnixSockets */ false,
    /* allowLocalBinding */ true,
    List.of("com.apple.PowerManagement.control"),  // allowMachLookup (macOS only)
    /* httpProxyPort */ null,
    /* socksProxyPort */ null);

SandboxSettings sandbox = new SandboxSettings(
    /* enabled */ true,
    /* autoAllowBashIfSandboxed */ true,
    /* excludedCommands */ List.of("git"),
    /* allowUnsandboxedCommands */ null,
    network,
    /* ignoreViolations */ null,
    /* enableWeakerNestedSandbox */ false);

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .sandbox(sandbox)
    .build();
```

`ignoreViolations` recebe um `SandboxIgnoreViolations`: um map de um padrão de comando (`"*"` para
todos os comandos) para substrings do texto da violação. Uma violação deixa de ser reportada quando o
texto do comando contém o padrão e o texto da violação contém uma das strings. As duas verificações são
simples correspondências de substring, não globs, e a configuração muda quais violações são
reportadas, não o que o sandbox bloqueia. Ela é serializada como o map puro:

```java
SandboxIgnoreViolations.forAllCommands(List.of("/etc/hosts"))   // {"*": ["/etc/hosts"]}

SandboxIgnoreViolations.of(Map.of(
    SandboxIgnoreViolations.ALL_COMMANDS, List.of("/etc/hosts"),
    "npm test", List.of("registry.npmjs.org")))
```

O construtor obsoleto `(file, network)` de antes da 0.2.4 coloca as duas listas em `"*"`.

Campos de `SandboxNetworkConfig`:

- `allowedDomains` — domínios que os processos no sandbox podem alcançar.
- `deniedDomains` — bloqueios que sempre prevalecem; a negação vence a permissão.
- `allowManagedDomainsOnly` — quando `true` nas configurações gerenciadas, só os `allowedDomains` dessas configurações são respeitados.
- `allowMachLookup` — nomes de serviços XPC/Mach, exclusivos do macOS; aceita curinga no fim.
- `allowUnixSockets`, `allowAllUnixSockets`, `allowLocalBinding`, `httpProxyPort`, `socksProxyPort` — já existentes.

Um construtor retrocompatível de 5 argumentos `(allowUnixSockets, allowAllUnixSockets, allowLocalBinding, httpProxyPort, socksProxyPort)` foi preservado para quem não precisa da lista de domínios nem dos campos de Mach lookup — esses ficam como `null`.

### plugins()

Carrega plugins do Claude Code a partir de diretórios locais.

```java
.plugins(List.of(
    ClaudeAgentOptions.SdkPluginConfig.local("/path/to/my-plugin")
))
```

Cada plugin `local` vira `--plugin-dir <path>`. Qualquer outro `type` faz `connect()` lançar `IllegalArgumentException` (desde a 0.2.6; antes, era ignorado). Veja [Sistema de plugins](./feature-plugin-system.md).

### outputFormat()

Formato de saída estruturada (estilo Messages API).

```java
.outputFormat(Map.of(
    "type", "json_schema",
    "schema", Map.of(
        "type", "object",
        "properties", Map.of(
            "name", Map.of("type", "string"),
            "age", Map.of("type", "integer")
        ),
        "required", List.of("name")
    )
))
```

O schema é enviado como `--json-schema`. Um que não pode ser serializado faz `connect()` falhar com
`CLIConnectionException` em vez de ser descartado.

### enableFileCheckpointing()

Habilita checkpoints de arquivo para permitir reverter.

```java
.enableFileCheckpointing(true)
.extraArgs(Map.of("replay-user-messages", ""))  // so UserMessage carries a uuid
```

Define `CLAUDE_CODE_ENABLE_SDK_FILE_CHECKPOINTING=true` no processo do CLI e permite usar
`ClaudeSDKClient.rewindFiles(userMessageId)`. É a reprodução das mensagens de usuário que
fornece o `uuid` para o qual reverter. Não pode ser combinado com `sessionStore()`.

## Exemplos completos

### Exemplo 1: análise de código somente leitura

```java
var options = ClaudeAgentOptions.builder()
    .model("claude-sonnet-5")
    .cwd(Path.of("/path/to/codebase"))
    .allowedTools(List.of("Read", "Grep", "Glob"))
    .disallowedTools(List.of("Write", "Edit", "Bash"))
    .permissionMode(PermissionMode.BYPASS_PERMISSIONS)
    .maxTurns(10)
    .maxBudgetUsd(0.50)
    .systemPrompt("You are a code analyzer. Only read and analyze code.")
    .build();
```

### Exemplo 2: desenvolvimento interativo

```java
var calcServer = ClaudeSDK.createSdkMcpServer("calc", new Calculator());

var options = ClaudeAgentOptions.builder()
    .model("claude-sonnet-5")
    .cwd(Path.of("/project"))
    .allowedTools(List.of(
        "Read", "Write", "Edit", "Grep", "Glob",
        "mcp__calc__add", "mcp__calc__multiply"
    ))
    .mcpServers(Map.of("calc", calcServer))
    .permissionMode(PermissionMode.ACCEPT_EDITS)
    .maxTurns(50)
    .enableFileCheckpointing(true)
    .systemPrompt("""
        You are a development assistant.
        - Write clean, tested code
        - Follow project conventions
        - Ask before major changes
        """)
    .build();
```

### Exemplo 3: processamento em lote com orçamento controlado

```java
var options = ClaudeAgentOptions.builder()
    .model("claude-haiku-4-5")  // Cheapest model
    .maxTurns(1)                // Single turn only
    .maxBudgetUsd(0.10)         // 10 cent limit
    .permissionMode(PermissionMode.BYPASS_PERMISSIONS)
    .systemPrompt("Be extremely concise.")
    .build();

for (String item : batchItems) {
    String result = ClaudeSDK.queryForText(item, options);
    processResult(result);
}
```

### Exemplo 4: ferramentas personalizadas com hooks

```java
var tools = new MyCustomTools();
var server = ClaudeSDK.createSdkMcpServer("tools", tools);

var options = ClaudeAgentOptions.builder()
    .mcpServers(Map.of("tools", server))
    .allowedTools(List.of("mcp__tools__process"))
    .hooks(Map.of(
        HookEvent.PRE_TOOL_USE, List.of(
            new HookMatcher("mcp__tools__process", List.of((input, context) -> {
                if (input instanceof PreToolUseHookInput pre) {
                    log("Processing: " + pre.toolInput());
                }
                return CompletableFuture.completedFuture(HookOutput.empty());
            }))
        ),
        HookEvent.POST_TOOL_USE, List.of(
            new HookMatcher("mcp__tools__process", List.of((input, context) -> {
                log("Completed processing");
                return CompletableFuture.completedFuture(HookOutput.empty());
            }))
        )
    ))
    .build();
```

### Exemplo 5: retomando sessões

```java
// First session
var options1 = ClaudeAgentOptions.builder()
    .model("claude-sonnet-5")
    .build();

try (var client = ClaudeSDK.createClient(options1)) {
    client.connect("What is Java?");
    // ... conversation
    // Note session ID from messages
}

// Resume later with context
var options2 = ClaudeAgentOptions.builder()
    .model("claude-sonnet-5")
    .resume("previous-session-id")
    .build();

try (var client = ClaudeSDK.createClient(options2)) {
    client.connect("Tell me more about lambdas");
    // Has context from previous session
}
```

### Exemplo 6: streaming com callback de permissão

```java
var options = ClaudeAgentOptions.builder()
    .canUseTool((toolName, input, context) -> {
        // Custom permission logic
        boolean allowed = checkPermission(toolName, context.path());

        if (allowed) {
            return CompletableFuture.completedFuture(
                new PermissionResultAllow()
            );
        } else {
            return CompletableFuture.completedFuture(
                new PermissionResultDeny("Access denied to " + context.path())
            );
        }
    })
    .build();

// Must use streaming mode
var messages = List.of(
    Map.of("type", "user", "session_id", "default",
           "message", Map.of("role", "user", "content", "Read sensitive.txt"))
);

List<Message> responses = ClaudeSDK.query(messages.iterator(), options);
```

## Boas práticas

### 1. Sempre defina o diretório de trabalho em operações de arquivo

```java
// ✅ Good
.cwd(Path.of("/project/root"))

// ❌ Bad: Undefined behavior
// No cwd set, files relative to CLI process directory
```

### 2. Use modelos adequados

```java
// ✅ Good: Match model to task
.model("claude-haiku-4-5")  // Simple tasks
.model("claude-sonnet-5") // Balanced
.model("claude-opus-4-6")   // Complex reasoning

// ❌ Bad: Always using most expensive
.model("claude-opus-4-6")  // For everything!
```

### 3. Defina limites de orçamento

```java
// ✅ Good: Protect against unexpected costs
.maxBudgetUsd(1.0)
.maxTurns(10)

// ❌ Bad: No limits
// Could get expensive!
```

### 4. Configure as ferramentas adequadamente

```java
// ✅ Good: Explicit tool control
.allowedTools(List.of("Read", "Grep"))
.disallowedTools(List.of("Bash"))

// ❌ Bad: All tools allowed by default
// Potential security risk
```

### 5. Use prompts de sistema

```java
// ✅ Good: Guide behavior
.systemPrompt("You are a code reviewer. Focus on security.")

// ❌ Bad: No guidance
// Claude may not understand context
```

### 6. Habilite checkpoints em operações de arquivo

```java
// ✅ Good: Enable for safety
.enableFileCheckpointing(true)
.extraArgs(Map.of("replay-user-messages", ""))

// Allows rewinding if mistakes: pass the uuid of a replayed UserMessage
client.rewindFiles(userMessageUuid);
```

### 7. Trate dados sensíveis com cuidado

```java
// ✅ Good: Don't pass secrets in env
.env(Map.of("CONFIG_PATH", "/path/to/config"))

// ❌ Bad: Secrets in environment
.env(Map.of("API_KEY", "secret-123"))  // Logged!
```

## Veja também

- [Consultas simples](./feature-simple-queries.md) — usando opções em consultas
- [Conversas interativas](./feature-interactive-conversations.md) — usando opções com o cliente
- [Servidores MCP](./feature-mcp-servers.md) — configurando servidores MCP
- [Hooks](./feature-hooks.md) — configuração de hooks
- [Permissões](./feature-permissions.md) — o sistema de permissões
