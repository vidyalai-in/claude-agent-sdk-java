# Definições de agente

> **Sobre esta tradução**: a documentação em inglês é a única autoritativa. Esta tradução pode estar desatualizada em relação ao [original em inglês](../feature-agents.md); em caso de divergência, o inglês prevalece. Os blocos de código são mantidos idênticos ao original e não foram traduzidos.

Agentes personalizados permitem definir subagentes especializados com seus próprios prompts de
sistema, ferramentas e modelos. O Claude pode criar esses agentes durante as conversas para lidar
com tarefas específicas.

## Sumário
- [Visão geral](#visão-geral)
- [O record AgentDefinition](#o-record-agentdefinition)
- [Definições de agente inline](#definições-de-agente-inline)
- [Agentes baseados no sistema de arquivos](#agentes-baseados-no-sistema-de-arquivos)
- [Definições de agente grandes](#definições-de-agente-grandes)
- [Observando a saída de um subagente](#observando-a-saída-de-um-subagente)
- [Subagentes em segundo plano e callbacks](#subagentes-em-segundo-plano-e-callbacks)
- [Exemplos](#exemplos)

## Visão geral

Agentes são subagentes nomeados que o Claude pode usar durante uma conversa. Cada agente tem:

- Uma **description** — o que o agente faz (mostrada ao Claude quando ele decide qual agente usar)
- Um **prompt de sistema** — instruções de comportamento para o agente
- **Ferramentas** — a lista de ferramentas que o agente pode usar (null herda do pai)
- Um **modelo** — a variante do modelo Claude em que o agente roda (null herda do pai)
- **Skills** — a lista de nomes de skill disponíveis ao agente (null herda do pai)
- **Memory** — o escopo de memória do agente (null herda do pai)
- **Servidores MCP** — referências a servidores MCP que o agente pode usar (null herda do pai)

Os agentes são registrados via `ClaudeAgentOptions.agents()` como um
`Map<String, AgentDefinition>`, em que a chave é o nome do agente.

## O record AgentDefinition

```java
import in.vidyalai.claude.sdk.types.config.AgentDefinition;
import in.vidyalai.claude.sdk.types.config.AIModel;
import in.vidyalai.claude.sdk.types.config.MemoryScope;

// Full constructor
AgentDefinition agent = new AgentDefinition(
    "Reviews code for quality and bugs",   // description
    "You are a code review expert...",     // system prompt
    List.of("Read", "Grep"),               // tools (null = inherit)
    "sonnet",                              // model (null = inherit)
    List.of("commit", "review"),           // skills (null = inherit)
    MemoryScope.PROJECT,                   // memory scope (null = inherit)
    List.of("my-mcp-server")              // MCP servers (null = inherit)
);

// Shorthand: description + prompt only (all other fields inherit from parent)
AgentDefinition simple = new AgentDefinition(
    "Summarizes text",
    "You are a concise summarizer."
);

// Backwards-compatible: description, prompt, tools, model
AgentDefinition compat = new AgentDefinition(
    "Reviews code",
    "You are a code reviewer.",
    List.of("Read", "Grep"),
    "sonnet"   // model can be "sonnet", "opus", "haiku", or full model ID
);
```

**Campos:**

| Campo | Tipo | Descrição |
|-------|------|-------------|
| `description` | `String` | Descrição legível mostrada ao Claude |
| `prompt` | `String` | Prompt de sistema que define o comportamento do agente |
| `tools` | `List<String>` (nulo permitido) | Nomes de ferramentas permitidas; null herda as do pai |
| `disallowedTools` | `List<String>` (nulo permitido) | Ferramentas que o agente não pode usar; null significa nenhuma |
| `model` | `String` (nulo permitido) | Apelido do modelo ("sonnet", "opus", "haiku", "inherit") ou ID completo |
| `skills` | `List<String>` (nulo permitido) | Nomes de skill disponíveis ao agente; null herda |
| `memory` | `MemoryScope` (nulo permitido) | Escopo de memória; null herda do pai |
| `mcpServers` | `List<Object>` (nulo permitido) | Referências a servidores MCP (nomes ou configurações inline); null herda |
| `initialPrompt` | `String` (nulo permitido) | Prompt inicial enviado quando o agente começa |
| `maxTurns` | `Integer` (nulo permitido) | Máximo de turnos do agente; null significa ilimitado |
| `background` | `Boolean` (nulo permitido) | Roda o agente em segundo plano |
| `effort` | `String` (nulo permitido) | Nível de esforço: `"low"`, `"medium"`, `"high"`, `"xhigh"`, `"max"`. `"xhigh"` é específico do Opus 4.7 e recai em `"high"` nos demais modelos. Veja também o enum [`EffortLevel`](feature-configuration-options.md#enum-effortlevel). |
| `permissionMode` | `String` (nulo permitido) | Modo de permissão do agente |

**Campo model:** o campo `model` aceita apelidos curtos (`"sonnet"`, `"opus"`, `"haiku"`,
`"inherit"`) ou IDs completos de modelo (por exemplo, `"claude-sonnet-4-5"`).

### Enum MemoryScope

Controla em qual escopo de memória um agente opera:

```java
import in.vidyalai.claude.sdk.types.config.MemoryScope;

MemoryScope.USER     // "user" — user-level memory
MemoryScope.PROJECT  // "project" — project-scoped memory
MemoryScope.LOCAL    // "local" — local/session-scoped memory
```

## Definições de agente inline

Registre agentes programaticamente via `ClaudeAgentOptions`:

```java
import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.types.config.AgentDefinition;

AgentDefinition codeReviewer = new AgentDefinition(
    "Reviews code for best practices and potential issues",
    """
    You are a code reviewer. Analyze code for bugs, performance issues,
    security vulnerabilities, and adherence to best practices.
    Provide constructive feedback.
    """,
    List.of("Read", "Grep"),
    "sonnet"   // model can be "sonnet", "opus", "haiku", or full model ID
);

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .agents(Map.of("code-reviewer", codeReviewer))
    .build();

try (var client = ClaudeSDK.createClient(options)) {
    client.connect();
    client.sendMessage("Use the code-reviewer agent to review MyClass.java");

    for (Message msg : client.receiveResponse()) {
        if (msg instanceof AssistantMessage assistant) {
            System.out.println(assistant.getTextContent());
        }
    }
}
```

### Vários agentes

Você pode definir vários agentes numa mesma sessão:

```java
AgentDefinition analyzer = new AgentDefinition(
    "Analyzes code structure and patterns",
    "You are a code analyzer. Examine code structure, patterns, and architecture.",
    List.of("Read", "Grep", "Glob"),
    null  // inherit model from parent
);

AgentDefinition tester = new AgentDefinition(
    "Creates and runs tests",
    "You are a testing expert. Write comprehensive tests and ensure code quality.",
    List.of("Read", "Write", "Bash"),
    "sonnet"
);

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .agents(Map.of(
        "analyzer", analyzer,
        "tester", tester
    ))
    .build();
```

## Agentes baseados no sistema de arquivos

Agentes também podem ser carregados de arquivos markdown no disco usando `settingSources`. Coloque
os arquivos de definição em `.claude/agents/` no diretório do seu projeto:

```
.claude/
  agents/
    code-reviewer.md
    test-writer.md
```

Depois habilite o carregamento de agentes do sistema de arquivos:

```java
import in.vidyalai.claude.sdk.types.config.SettingSource;

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .settingSources(List.of(SettingSource.PROJECT))
    .cwd(Path.of("/path/to/project"))
    .build();

try (ClaudeSDKClient client = new ClaudeSDKClient(options)) {
    client.connect();
    // Agents defined in .claude/agents/*.md are now available
}
```

Você pode verificar quais agentes foram carregados olhando o evento init da `SystemMessage`:

```java
for (Message msg : client.receiveResponse()) {
    if (msg instanceof SystemMessage system && "init".equals(system.subtype())) {
        List<String> agents = system.get("agents");
        System.out.println("Loaded agents: " + agents);
    }
}
```

## Definições de agente grandes

Os agentes são enviados pela requisição initialize do protocolo de controle do SDK (via stdin), e
não como argumentos de linha de comando. Isso significa que **não há limite de tamanho** para as
definições de agente — você pode passar com segurança mais de 260KB de dados de agente.

Esse comportamento é igual ao das implementações dos SDKs TypeScript e Python e evita os limites de
comprimento de argumentos de linha de comando específicos de cada plataforma (ARG_MAX).

```java
// Large agents work reliably via stdin
Map<String, AgentDefinition> agents = new HashMap<>();
for (int i = 0; i < 20; i++) {
    String largePrompt = "You are agent #" + i + ". " + "x".repeat(13 * 1024);
    agents.put("agent-" + i, new AgentDefinition("Agent " + i, largePrompt));
}

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .agents(agents)
    .maxTurns(1)
    .build();

// Works for both query() and createClient()
for (Message msg : ClaudeSDK.query("List available agents", options)) {
    // ...
}
```

## Observando a saída de um subagente

Um subagente conduz a própria conversa, e só parte dela chega ao fluxo de mensagens do pai. O que
chega vem como objetos `AssistantMessage` / `UserMessage` comuns cujo `parentToolUseId` é o id do
bloco `tool_use` do Agent que criou o subagente — esse campo é como você distingue as mensagens de um
subagente das da conversa principal, e a qual subagente elas pertencem quando há vários rodando.

Por padrão, só os blocos `tool_use` e `tool_result` do subagente são encaminhados: o suficiente para
mostrar que ele está progredindo, mas não para exibir o que ele disse. Defina
`forwardSubagentText(true)` para que os blocos de texto e de raciocínio sejam encaminhados do mesmo
jeito:

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .forwardSubagentText(true)
    .agents(Map.of("greeter", greeter))
    .build();

for (Message msg : ClaudeSDK.query(prompt, options)) {
    if (msg instanceof AssistantMessage assistant && assistant.parentToolUseId() != null) {
        // From a subagent — attribute it to the spawning Agent tool_use id.
        System.out.println("[" + assistant.parentToolUseId() + "] " + assistant.getTextContent());
    }
}
```

A opção é enviada na requisição de controle `initialize` em vez de como flag do CLI, e só quando
está habilitada, então CLIs mais antigos não são afetados.

Ler a transcrição completa de um subagente *já concluído* é outro caminho — veja [Histórico de
sessões](./feature-session-history.md) para `listSubagents()` e `getSubagentMessages()`, cujos
resultados trazem o mesmo `parentToolUseId` mais um `parentAgentId` para subagentes aninhados.

## Subagentes em segundo plano e callbacks

Um subagente iniciado com `run_in_background` continua rodando depois que o turno do pai termina
e, quando conclui, sua conclusão acorda o pai para um turno de continuação. Com um
`ClaudeSDK.query(...)` pontual que tenha hooks, um callback `canUseTool` ou servidores MCP do SDK,
esses callbacks no turno de continuação só funcionam enquanto o stdin ainda estiver aberto. Por
isso, o SDK não fecha o stdin no primeiro `result`:

- Enquanto uma tarefa `local_agent` ou `local_workflow` ainda estiver em andamento, o stdin
  permanece aberto.
- Quando o CLI informa o estado da sessão, o stdin é fechado em `idle` após um resultado, de modo
  que um turno de continuação devido a um subagente que terminou *logo antes* do resultado ainda é
  atendido.
- A espera entre turnos é limitada por `CLAUDE_CODE_PRINT_BG_WAIT_CEILING_MS` (10 minutos por
  padrão, `0` para sem limite).

O SDK pede o estado da sessão ao CLI com `CLAUDE_CODE_SDK_READS_SESSION_STATE`. O Claude Code
2.1.283 ainda não respeita essa variável; com um CLI assim, o stdin é fechado no primeiro resultado
sem nenhuma tarefa em andamento, e se os callbacks do turno de continuação vão rodar depende do
momento em que as coisas acontecem. Definir `CLAUDE_CODE_EMIT_SESSION_STATE_EVENTS=1` em `env()`
faz o CLI informar o estado já hoje, ao custo de os quadros `session_state_changed` também chegarem
ao seu iterador:

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .agents(Map.of("worker", worker))
    .hooks(hooks)
    .env(Map.of("CLAUDE_CODE_EMIT_SESSION_STATE_EVENTS", "1"))
    .build();
```

O `ClaudeSDKClient` não é afetado: ele mantém o stdin aberto até você desconectar. As regras
completas estão em [Arquitetura → Ciclo de vida do stdin](./architecture.md#ciclo-de-vida-do-stdin-e-o-fim-de-uma-execução).

## Exemplos

Veja os arquivos de exemplo para demonstrações completas e executáveis:

- [`AgentsExample.java`](../../examples/src/main/java/examples/AgentsExample.java) — revisor de código, redator de documentação e vários agentes
- [`FilesystemAgentsExample.java`](../../examples/src/main/java/examples/FilesystemAgentsExample.java) — carregando agentes de arquivos em `.claude/agents/`
- [`LargeAgentsExample.java`](../../examples/src/main/java/examples/LargeAgentsExample.java) — teste de estresse com cargas de agente acima de 260KB
- [`ForwardSubagentTextExample.java`](../../examples/src/main/java/examples/ForwardSubagentTextExample.java) — a mesma execução com o encaminhamento de texto do subagente desligado e ligado
- [`BackgroundAgentHooksExample.java`](../../examples/src/main/java/examples/BackgroundAgentHooksExample.java) — um hook `PreToolUse` atendido no turno de continuação que a conclusão de um subagente em segundo plano desperta

## Veja também

- [Opções de configuração](./feature-configuration-options.md) — as opções `agents` e `settingSources`
- [Conversas interativas](./feature-interactive-conversations.md) — usando agentes em sessões de múltiplos turnos
