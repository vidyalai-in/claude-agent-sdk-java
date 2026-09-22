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
    .model("claude-sonnet-4-5")
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
builder.model("claude-sonnet-4-5")
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

// Use prompt from file
.systemPrompt(new SystemPromptFile("/path/to/prompt.md"))
```

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

// From file path
.mcpServers(Path.of("~/.claude/mcp_servers.json"))

// From JSON string
.mcpServers("""
    {
        "server1": {"type": "stdio", "command": "node", "args": ["server.js"]}
    }
    """)
```

## Configurações de permissão

### permissionMode()

Controla como as permissões de ferramentas são tratadas.

```java
.permissionMode(PermissionMode.BYPASS_PERMISSIONS)
```

**Modos**:
- `PROMPT` (padrão) — pergunta a cada permissão
- `ACCEPT_ALL` — aceita todas as permissões automaticamente
- `ACCEPT_EDITS` — aceita edições de arquivo automaticamente, pergunta nas demais
- `BYPASS_PERMISSIONS` — ignora completamente as verificações de permissão
- `DONT_ASK` — permite todas as ferramentas sem perguntar
- `AUTO` — determina automaticamente o modo de permissão adequado

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

### sessionStoreFlush()

Controla quando as entradas espelhadas da transcrição são gravadas no `sessionStore` configurado. O padrão é `SessionStoreFlushMode.BATCHED` (uma gravação por turno ou quando o buffer transborda). Use `SessionStoreFlushMode.EAGER` para agendar uma gravação em segundo plano após cada quadro, com entrega quase em tempo real — os appends continuam serializados na ordem de enfileiramento, mas um adaptador lento não trava o laço de leitura. Ignorado quando `sessionStore` não está definido.

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
- `ThinkingConfigEnabled(int budgetTokens)` — orçamento fixo de tokens (deve ser > 0)
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

**OBSOLETO**: use `thinking()` em vez disso.

Número máximo de tokens para os blocos de raciocínio.

```java
.maxThinkingTokens(10000)  // Deprecated - use thinking() instead
```

### maxMsgQSize()

Tamanho máximo da fila de mensagens.

```java
.maxMsgQSize(1000)
```

Aumente em cenários de alta vazão.

## Configuração do modelo

### model()

Define o modelo de IA.

```java
.model("claude-sonnet-4-5")
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

Padrão: procura no PATH do sistema.

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

**Risco residual:** o cmd.exe ainda expande `%VAR%` a partir do ambiente. Use apenas onde o caminho do CLI e todos os valores de argumento sejam controlados pelo administrador. Ignorado em POSIX. Veja [Camada de transporte → habilitação de CLI em batch](./feature-transport-layer.md#windows-habilitação-de-cli-em-batch-0122).

### settings()

Caminho para um arquivo JSON de configurações.

```java
.settings("/path/to/settings.json")
```

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

### extraArgs()

Passa flags arbitrárias para o CLI.

```java
.extraArgs(Map.of(
    "--verbose", "",
    "--config", "custom.json"
))
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

Recebe a saída de erro do CLI. Invocado uma vez por linha de stderr conforme o CLI a emite
(só é redirecionado quando esse callback está definido).

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
.hooks(Map.of(
    HookEvent.PRE_TOOL_USE, List.of(
        new HookMatcher(null, "Read", (context) -> {
            System.out.println("About to read file");
            return CompletableFuture.completedFuture(
                HookOutput.empty()
            );
        })
    )
))
```

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

Define a identidade do usuário para rastreamento.

```java
.user("user-12345")
```

### includePartialMessages()

Habilita o streaming de mensagens parciais.

```java
.includePartialMessages(true)
```

Recebe mensagens `StreamEvent` com deltas conforme o conteúdo é gerado.

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

**Uma lista vazia desativa todas as fontes.** Passe `List.of()` para enviar `--setting-sources=` (vazio) ao CLI, suprimindo todas as fontes de configuração do sistema de arquivos. Quando a opção é **omitida por completo** (o padrão), nenhuma flag `--setting-sources` é adicionada e o CLI aplica seus próprios padrões.

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

Campos de `SandboxNetworkConfig`:

- `allowedDomains` — domínios que os processos no sandbox podem alcançar.
- `deniedDomains` — bloqueios que sempre prevalecem; a negação vence a permissão.
- `allowManagedDomainsOnly` — quando `true` nas configurações gerenciadas, só os `allowedDomains` dessas configurações são respeitados.
- `allowMachLookup` — nomes de serviços XPC/Mach, exclusivos do macOS; aceita curinga no fim.
- `allowUnixSockets`, `allowAllUnixSockets`, `allowLocalBinding`, `httpProxyPort`, `socksProxyPort` — já existentes.

Um construtor retrocompatível de 5 argumentos `(allowUnixSockets, allowAllUnixSockets, allowLocalBinding, httpProxyPort, socksProxyPort)` foi preservado para quem não precisa da lista de domínios nem dos campos de Mach lookup — esses ficam como `null`.

### plugins()

Adiciona plugins personalizados.

```java
.plugins(List.of(
    new SdkPluginConfig("my-plugin", config)
))
```

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

### checkpointFiles()

Habilita checkpoints de arquivo para permitir reverter.

```java
.checkpointFiles(true)
```

Permite usar `ClaudeSDKClient.rewindFiles()`.

## Exemplos completos

### Exemplo 1: análise de código somente leitura

```java
var options = ClaudeAgentOptions.builder()
    .model("claude-sonnet-4-5")
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
    .model("claude-sonnet-4-5")
    .cwd(Path.of("/project"))
    .allowedTools(List.of(
        "Read", "Write", "Edit", "Grep", "Glob",
        "mcp__calc__add", "mcp__calc__multiply"
    ))
    .mcpServers(Map.of("calc", calcServer))
    .permissionMode(PermissionMode.ACCEPT_EDITS)
    .maxTurns(50)
    .checkpointFiles(true)
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
            new HookMatcher(null, "mcp__tools__process", context -> {
                log("Processing: " + context.input());
                return CompletableFuture.completedFuture(
                    HookOutput.logs(List.of("Started processing"))
                );
            })
        ),
        HookEvent.POST_TOOL_USE, List.of(
            new HookMatcher(null, "mcp__tools__process", context -> {
                log("Completed processing");
                return CompletableFuture.completedFuture(
                    HookOutput.empty()
                );
            })
        )
    ))
    .build();
```

### Exemplo 5: retomando sessões

```java
// First session
var options1 = ClaudeAgentOptions.builder()
    .model("claude-sonnet-4-5")
    .build();

try (var client = ClaudeSDK.createClient(options1)) {
    client.connect("What is Java?");
    // ... conversation
    // Note session ID from messages
}

// Resume later with context
var options2 = ClaudeAgentOptions.builder()
    .model("claude-sonnet-4-5")
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
.model("claude-sonnet-4-5") // Balanced
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
.checkpointFiles(true)

// Allows rewinding if mistakes
client.rewindFiles(checkpointId);
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
