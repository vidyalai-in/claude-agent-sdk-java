# Referência da API ClaudeAgentOptions

Builder das opções de configuração.

> **Sobre esta tradução**: a documentação em inglês é a única autoritativa. Esta tradução pode estar desatualizada em relação ao [original em inglês](../api-claude-agent-options.md); em caso de divergência, o inglês prevalece. Os blocos de código são mantidos idênticos ao original e não foram traduzidos.

## Visão geral da classe

```java
public final class ClaudeAgentOptions
```

Objeto de configuração imutável que usa o padrão builder.

## Criando as opções

```java
// Builder
ClaudeAgentOptions.builder()
    .option1(value)
    .build()

// Defaults
ClaudeAgentOptions.defaults()

// From existing
options.toBuilder()
    .modifiedOption(newValue)
    .build()
```

## Todas as opções de configuração

### Configuração de ferramentas
- `tools(Object)` — lista de ferramentas ou preset
- `allowedTools(List<String>)` — lista de permissão
- `disallowedTools(List<String>)` — lista de bloqueio

### Prompt de sistema
- `systemPrompt(String)` — prompt de sistema personalizado (`--system-prompt`)
- `systemPrompt(SystemPromptPreset)` — preset do Claude Code, opcionalmente com `append`, `excludeDynamicSections` e `snapshot`
- `systemPrompt(SystemPromptCustom)` — prompt personalizado que também pode definir `snapshot`; `SystemPromptCustom.of(prompt, snapshot)`
- `systemPrompt(SystemPromptFile)` — prompt carregado de um arquivo (`--system-prompt-file`)

`snapshot` (apenas nas formas preset e personalizada) controla se a sessão mantém o prompt que registrou em sua primeira requisição (`true`) ou o remonta a cada requisição (`false`); é enviado na requisição `initialize` como `systemPromptSnapshot` e omitido quando `null`. Requer o Claude Code 2.1.257+. Veja [Opções de configuração → Snapshot](./feature-configuration-options.md#snapshot).

### Servidores MCP
- `mcpServers(Map<String, McpServerConfig>)` — configurações de servidores, serializadas como `{"mcpServers": {...}}` para `--mcp-config`
- `mcpServers(Path)` / `mcpServersPath(Path)` — arquivo de configuração MCP, repassado como está para `--mcp-config`
- `mcpServersJson(String)` — JSON de configuração MCP inline, repassado como está para `--mcp-config`
- `strictMcpConfig(boolean)` — quando `true`, o CLI ignora o `.mcp.json` do projeto, as configurações de usuário/globais e os servidores MCP fornecidos por plugins; só são carregados os servidores passados via `mcpServers(...)`. Corresponde a `--strict-mcp-config`.

### Permissões
- `permissionMode(PermissionMode)` — modo de permissão
- `permissionPromptToolName(String)` — ferramenta usada nos prompts
- `canUseTool(CanUseTool)` — callback personalizado. **Dispara apenas em decisões `"ask"`** — não para chamadas de ferramenta já permitidas por `allowedTools`, `permissionMode` ou regras `permissions.allow`. Use um hook `PreToolUse` para controlar toda chamada, independentemente da decisão. O SDK registra um `WARNING` informativo no momento da conexão se esse callback estiver visivelmente sombreado por entradas de `allowedTools` que cobrem a ferramenta inteira ou por `BYPASS_PERMISSIONS`; veja [Aviso de sombreamento](feature-permissions.md#aviso-de-sombreamento).

### Sessões
- `continueConversation(boolean)` — continuar a última sessão
- `resume(String)` — retomar uma sessão específica
- `forkSession(boolean)` — bifurcar a sessão retomada
- `resumeSessionAt(String)` — retomada com truncamento: carrega a conversa retomada apenas até este UUID de entrada da transcrição (inclusive), ramificando a partir de um ponto anterior. Use com `resume` e, normalmente, com `forkSession`. Aceita qualquer UUID de entrada da transcrição — em geral um `AssistantMessage.uuid()` observado ao vivo ou um `SessionMessage.uuid()` vindo de `ClaudeSDK.getSessionMessages(...)`. Emitido como `--resume-session-at=<value>`. Veja [Retomada com truncamento](./feature-session-history.md#retomada-com-truncamento).
- `resumeDropsTurn(String)` — junto com `resumeSessionAt`: o UUID do prompt de usuário cujo turno o truncamento pretende descartar. O CLI então valida, no carregamento, que *toda* entrada após o ponto de bifurcação pertence a esse turno e recusa caso contrário — assim, uma mensagem de usuário enfileirada ou uma notificação de tarefa que a sessão absorveu no meio do turno nunca é descartada em silêncio. A recusa aparece como uma exceção cuja mensagem contém `Resume rejected by --resume-drops-turn:`; trate-a como determinística e retome normalmente em vez de tentar de novo. É encaminhado sempre que não for nulo, então uma string vazia chega ao CLI e é rejeitada lá como malformada, em vez de desarmar a proteção silenciosamente. Emitido como `--resume-drops-turn=<value>`.
- `sessionStore(SessionStore)` — espelha transcrições para um store externo e retoma a partir dele (veja [Session Store](./feature-session-store.md)). Quando definido, o SDK passa `--session-mirror` ao CLI e roteia os quadros `transcript_mirror` para `store.appendAsync(...)`. A validação prévia rejeita `continueConversation + sessionStore` sem suporte a `listSessions()` e `sessionStore + enableFileCheckpointing`.
- `sessionStoreFlush(SessionStoreFlushMode)` — quando as entradas do espelho de transcrição são gravadas no `sessionStore`. `BATCHED` (padrão) agrupa entradas e grava uma vez por turno ou quando o buffer passa de 500 entradas / 1 MiB; `EAGER` agenda uma gravação em segundo plano após cada quadro, para entrega quase em tempo real. Ignorado quando `sessionStore` não está definido. Veja [Modo de gravação](./feature-session-store.md#modo-de-gravação-batched-vs-eager).
- `loadTimeoutMs(long)` — tempo limite por chamada de `store.loadAsync()` / `listSubkeysAsync()` durante a materialização da retomada, em milissegundos (padrão `60_000`). O valor `0` significa tempo limite imediato; valores grandes o desativam na prática.

### Limites
- `maxTurns(Integer)` — máximo de turnos da conversa
- `maxBudgetUsd(Double)` — custo máximo em USD
- `maxBufferSize(Integer)` — máximo de bytes do buffer de stdout
- `thinking(ThinkingConfig)` — configuração do raciocínio estendido
- `effort(String)` — nível de profundidade do raciocínio (`"low"`, `"medium"`, `"high"`, `"xhigh"`, `"max"`). `"xhigh"` é específico do Opus 4.7 e recai em `"high"` nos demais modelos.
- `effort(EffortLevel)` — igual ao anterior, mas com segurança de tipos usando o enum [`EffortLevel`](feature-configuration-options.md#enum-effortlevel) (`LOW`, `MEDIUM`, `HIGH`, `XHIGH`, `MAX`). Passe `null` para limpar.
- `maxThinkingTokens(Integer)` — **OBSOLETO**. Use `thinking()`. Nos modelos mais novos, o valor é tratado como liga/desliga (0 = desabilitado, qualquer outro valor = adaptativo)
- `maxMsgQSize(Integer)` — tamanho máximo da fila de mensagens

### Modelo
- `model(String)` — nome do modelo de IA
- `fallbackModel(String)` — modelo de fallback
- `betas(List<SdkBeta>)` — recursos beta

### Ambiente
- `cwd(Path)` — diretório de trabalho
- `cliPath(Path)` — caminho personalizado do CLI (um caminho `.bat`/`.cmd` do Windows é recusado; veja abaixo)
- `allowUnsafeWindowsBatchCli(boolean)` — dispensa a recusa de scripts batch no Windows; também exige `-Djdk.lang.Process.allowAmbiguousCommands=false` e rejeita metacaracteres do cmd.exe em todos os argumentos (padrão `false`)
- `settings(String)` — caminho do arquivo de configurações ou string JSON inline; repassado como está para `--settings`, ou mesclado com `sandbox` em uma única string JSON quando há um sandbox definido
- `addDirs(List<Path>)` — diretórios de contexto adicionais
- `env(Map<String, String>)` — variáveis de ambiente, mescladas sobre o ambiente herdado. O SDK também define `CLAUDE_CODE_ENTRYPOINT` (sobrescrevível), `CLAUDE_AGENT_SDK_VERSION` e `CLAUDE_CODE_SDK_READS_SESSION_STATE=1`, a menos que já estejam nomeadas; veja [env()](./feature-configuration-options.md#env)
- `extraArgs(Map<String, String>)` — flags extras do CLI; chaves sem o `--` inicial, valor em branco para uma flag sem valor

### Callbacks
- `stderrCallback(Consumer<String>)` — callback de stderr

### Hooks
- `hooks(Map<HookEvent, List<HookMatcher>>)` — callbacks de hook
- `includeHookEvents(boolean)` — quando `true`, o CLI envia os eventos de ciclo de vida dos hooks (`PreToolUse`, `PostToolUse`, `Stop`, …) para o fluxo de mensagens como objetos `HookEventMessage`. Corresponde a `--include-hook-events`. Veja [Hooks → Eventos de ciclo de vida no fluxo](./feature-hooks.md#eventos-de-ciclo-de-vida-dos-hooks-no-fluxo).

### Avançado
- `user(String)` — identidade do usuário
- `includePartialMessages(boolean)` — emite um `StreamEvent` por evento de stream da API (`--include-partial-messages`)
- `verbatimPrompts(boolean)` — marca toda mensagem de usuário que o SDK envia como `client_composed`, para que o Claude Code a entregue como foi escrita: sem expansão de `@path` nem despacho de comandos de barra. Enquanto ativada, sobrescreve qualquer valor por mensagem. Requer o Claude Code 2.1.248+ (o SDK emite um aviso com CLIs mais antigos). Veja [verbatimPrompts()](./feature-configuration-options.md#verbatimprompts)
- `forwardSubagentText(boolean)` — quando `true`, os blocos de texto e de raciocínio dos subagentes são encaminhados ao fluxo de mensagens junto com os blocos `tool_use` / `tool_result`, que são sempre encaminhados. Enviado na requisição de controle `initialize` (sem flag de CLI). Veja [Agentes → Observando a saída de um subagente](./feature-agents.md#observando-a-saída-de-um-subagente).
- `agents(Map<String, AgentDefinition>)` — agentes personalizados
- `settingSources(List<SettingSource>)` — fontes de configuração (uma lista vazia desativa todas as fontes via `--setting-sources=`; omitir mantém os padrões do CLI)
- `skills(List<String>)` — lista de skills permitidas (injeta automaticamente `Skill(name)` em `allowedTools` e define `settingSources` como user/project). Os nomes devem ser exatos — curingas, delimitadores de regra e espaços ao redor lançam `IllegalArgumentException` no `connect()`
- `skillsAll()` — habilita todas as skills descobertas (injeta automaticamente a ferramenta `Skill` simples)
- `sandbox(SandboxSettings)` — configuração do sandbox para comandos bash; sua chave `network` configura o isolamento de rede do próprio sandbox (as restrições no nível das ferramentas continuam nas regras de permissão)
- `plugins(List<SdkPluginConfig>)` — diretórios de plugins locais, `SdkPluginConfig.local(path)` → `--plugin-dir`
- `outputFormat(Map<String, Object>)` — formato de saída
- `enableFileCheckpointing(boolean)` — habilita os checkpoints de arquivo para `rewindFiles()`; incompatível com `sessionStore`

## Veja também
- [Guia de opções de configuração](./feature-configuration-options.md)
