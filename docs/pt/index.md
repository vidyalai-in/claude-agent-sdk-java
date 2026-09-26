# Claude Agent SDK for Java - Documentação técnica

[English](../README.md) · [简体中文](../zh/index.md) · [日本語](../ja/index.md) · [한국어](../ko/index.md) · **Português** · [Español](../es/index.md)

> **Sobre esta tradução**: a documentação em inglês é a única autoritativa. Esta tradução pode estar desatualizada em relação ao [original em inglês](../README.md); em caso de divergência, o inglês prevalece. Os blocos de código são mantidos idênticos ao original e não foram traduzidos. Veja [docs/TRANSLATIONS.md](../TRANSLATIONS.md) para mais detalhes.

Bem-vindo à documentação técnica do Claude Agent SDK for Java. Esta documentação traz
informações abrangentes sobre a arquitetura, os recursos e o uso do SDK.

## Visão geral

O Claude Agent SDK for Java é uma biblioteca completa para integrar os recursos de IA do Claude
a aplicações Java. Ele oferece uma API Java moderna e com segurança de tipos para interagir com
o CLI do Claude Code, dando suporte tanto a consultas pontuais simples quanto a conversas
complexas de múltiplos turnos.

**Destaques:**
- 🎯 **API com segurança de tipos**: interfaces seladas e records, de modo que a correspondência exaustiva de padrões funciona no código consumidor a partir do Java 21
- ⚡ **Threads virtuais**: o trabalho em segundo plano roda em threads virtuais do Project Loom no Java 21+, e em threads de plataforma daemon no 17-20
- 🔧 **Arquitetura flexível**: suporte tanto a consultas sem estado quanto a conversas com estado
- 🛠️ **Ferramentas personalizadas**: crie suas próprias ferramentas usando MCP (Model Context Protocol)
- 🔌 **Plugins**: carregue plugins do Claude Code (comandos, agentes, skills, hooks) a partir de diretórios locais
- 🎨 **Padrão builder**: API fluente para configuração

## Índice da documentação

### Arquitetura e design
- **[Visão geral da arquitetura](./architecture.md)** — arquitetura do sistema, padrões de design e estrutura interna
  - Diagrama de arquitetura de alto nível
  - Componentes centrais (camada de API, configuração, protocolo, transporte)
  - Padrões de design (interfaces seladas, builder, fachada, threads virtuais)
  - Diagramas de fluxo de dados e modelo de concorrência
  - Ciclo de vida do stdin: quando uma execução termina (estado da sessão, registro de tarefas, teto entre turnos)
  - Hierarquia do sistema de tipos e dependências

### Recursos principais
- **[Consultas simples](./feature-simple-queries.md)** — consultas pontuais usando a fachada ClaudeSDK
  - Exemplos de uso básico
  - Visão geral dos métodos de consulta
  - Opções de configuração
  - Padrões de tratamento de mensagens
  - Boas práticas

- **[Conversas interativas](./feature-interactive-conversations.md)** — conversas de múltiplos turnos usando ClaudeSDKClient
  - Gerenciamento de conexão
  - Envio e recebimento de mensagens
  - Métodos de controle
  - Gerenciamento de sessão
  - Segurança em relação a threads
  - Exemplos completos

- **[Opções de configuração](./feature-configuration-options.md)** — guia completo do builder ClaudeAgentOptions
  - Todas as 30+ opções de configuração
  - Configuração de ferramentas
  - Formas do prompt de sistema e `snapshot`
  - Configurações de permissão
  - Configuração de modelo
  - Variáveis de ambiente, e as que o próprio SDK define
  - `verbatimPrompts` — entregar prompts sem expansão de `@path` nem comandos de barra
  - Hooks e callbacks
  - Exemplos completos para padrões comuns

- **[Tipos de mensagem](./feature-message-types.md)** — entendendo o sistema de tipos de mensagem
  - UserMessage, AssistantMessage, SystemMessage, ResultMessage, StreamEvent, RateLimitEvent
  - Mensagens do ciclo de vida de tarefas (TaskStartedMessage, TaskProgressMessage, TaskNotificationMessage, TaskUpdatedMessage)
  - HookEventMessage (quando `includeHookEvents` está habilitado)
  - DeferredToolUse em ResultMessage; campo de status HTTP `apiErrorStatus`
  - ConversationResetMessage — a conversa foi substituída no meio da sessão (por exemplo, `/clear`)
  - Origem da mensagem — distinguindo seus próprios turnos dos injetados pela sessão
  - Blocos de conteúdo (Text, Thinking, ToolUse, ToolResult)
  - Casamento de padrões
  - Exemplos e boas práticas

- **[Servidores MCP](./feature-mcp-servers.md)** — criando ferramentas personalizadas com o Model Context Protocol
  - Servidores MCP do SDK (in-process)
  - Servidores MCP externos (stdio/SSE/HTTP)
  - Uso da anotação @Tool
  - Criação programática de ferramentas
  - Schemas de ferramenta e validação de argumentos contra o `inputSchema` da ferramenta
  - Semântica de falhas (resultados `isError` para tudo o que uma chamada de ferramenta encontra)
  - Cancelando uma ferramenta em execução com `ToolCallContext`
  - Detalhes do protocolo (negociação de versão, notificações, métodos)
  - Handlers MCP personalizados via `McpMessageHandler`
  - Padrões de execução assíncrona
  - Exemplos completos (calculadora, banco de dados, integração com API)

- **[Definições de agente](./feature-agents.md)** — subagentes personalizados com prompts, ferramentas e modelos específicos
  - Definições de agente inline
  - Agentes baseados no sistema de arquivos
  - Suporte a agentes grandes (260KB+ via stdin)
  - API AgentDefinition (com campos de skills, escopo de memória e servidores MCP)
  - Observando a saída de um subagente e `forwardSubagentText`

- **[Histórico de sessões](./feature-session-history.md)** — leia e gerencie sessões antigas de conversa do Claude Code a partir do disco
  - Listagem de sessões de todos os projetos ou filtradas por diretório
  - Consulta de uma sessão específica pelo ID (`getSessionInfo`)
  - Leitura de transcrições completas de conversa
  - Leitura de transcrições de subagentes (`listSubagents`, `getSubagentMessages`),
    atribuídas ao `tool_use` do Agent que os criou
  - Renomeação de sessões (`renameSession`)
  - Marcação de sessões com tags para organização (`tagSession`)
  - Exclusão de sessões (`deleteSession`) — remove em cascata o diretório de transcrições de subagentes
  - Fork de sessões com remapeamento de UUID (`forkSession`)
  - Retomada com truncamento (`resumeSessionAt` / `resumeDropsTurn`) — volte com segurança a um ponto anterior
  - Tipos SDKSessionInfo (com tag, createdAt e fileSize anulável) e SessionMessage
  - Paginação com offset e suporte a worktree

- **[Session Store](./feature-session-store.md)** — espelhe transcrições para S3 / Postgres / Redis / back-ends próprios
  - Protocolo do adaptador `SessionStore` com variantes síncrona e assíncrona (`CompletableFuture`)
  - Executor de threads virtuais configurável via `SessionStoreExecutor`
  - Adaptador de referência `InMemorySessionStore` incluído + helper `filePathToSessionKey`
  - APIs de leitura: `listSessionsFromStore`, `getSessionInfoFromStore`, `getSessionMessagesFromStore`, `listSubagentsFromStore`, `getSubagentMessagesFromStore`
  - APIs de mutação: `renameSessionViaStore`, `tagSessionViaStore`, `deleteSessionViaStore`, `forkSessionViaStore`
  - `importSessionToStore` para replay local→store; `MirrorErrorMessage` para falhas de anexação não fatais
  - Suíte pública de testes `SessionStoreConformance` (14 contratos, independente de framework)
  - Retomada a partir de um store (o subprocesso recebe um `CLAUDE_CONFIG_DIR` temporário); batcher do espelho de transcrições

- **[Skills](./feature-skills.md)** — opção `skills` de nível superior para a sessão principal
  - Três modos: `skillsAll()`, `skills(List)`, `skills(List.of())`
  - Injeta automaticamente `Skill(name)` em `allowedTools` e define o padrão de `settingSources`
  - Propagação pelo protocolo via requisição de controle initialize
  - Injeção idempotente; configurações explícitas sempre prevalecem
  - Validação do nome da skill — bloqueia injeção de regras em `--allowedTools`, rejeita nomes que nunca poderiam casar

- **[Propagação do W3C Trace Context](./feature-trace-context.md)** — rastreamento distribuído entre SDK e CLI
  - Injeção best-effort de `TRACEPARENT`/`TRACESTATE` no subprocesso do CLI
  - Zero dependência em tempo de execução do OpenTelemetry (baseado em reflexão)
  - Limpeza de variáveis de ambiente obsoletas, contextos só com baggage, erros de propagadores

### Recursos avançados
- **[Configuração do raciocínio estendido](./feature-thinking-config.md)** — controle a profundidade do raciocínio do Claude
  - Tipos ThinkingConfig (Adaptive, Enabled, Disabled)
  - Níveis de esforço (low, medium, high, max)
  - Controle e otimização de orçamento
  - Exemplos de uso completos

- **[Sistema de hooks](./feature-hooks.md)** — interceptando e respondendo a eventos do ciclo de vida
  - 10 eventos de hook
  - HookMatcher e HookOutput
  - `updatedToolOutput` do PostToolUse (substitui a saída de qualquer ferramenta) e `updatedMCPToolOutput`
  - `PermissionDecision.DEFER` + `DeferredToolUse` em ResultMessage
  - `includeHookEvents` + fluxo de HookEventMessage
  - Exemplos para casos de uso comuns

- **[Sistema de permissões](./feature-permissions.md)** — callbacks e modos de permissão personalizados
  - Modos de permissão
  - Callbacks de permissão personalizados (disparam apenas em decisões `"ask"`)
  - Sombreamento e como manter um callback determinístico com `settingSources(List.of())`
  - `ToolPermissionContext` enriquecido (`title`, `displayName`, `description`, `decisionReason`, `blockedPath`)
  - Exemplos baseados em caminho, em tempo e com confirmação do usuário

- **[Eventos de streaming](./feature-streaming-events.md)** — atualizações parciais de mensagem em tempo real
  - Habilitando o streaming
  - Processando eventos de stream
  - Exemplos de integração com UI

- **[Camada de transporte](./feature-transport-layer.md)** — implementações de transporte personalizadas
  - Interface Transport
  - Implementação padrão
  - Exemplo de transporte personalizado
  - Recusa de scripts batch no Windows e a habilitação explícita para instalações npm com `claude.cmd`

- **[Sistema de plugins](./feature-plugin-system.md)** — carregando plugins do Claude Code
  - `SdkPluginConfig.local(path)` → `--plugin-dir`
  - Estrutura de um plugin e como verificar que ele foi carregado

### Referência da API
- **[ClaudeSDK](./api-claude-sdk.md)** — fachada estática para consultas simples
  - Métodos de consulta
  - Métodos de fábrica de client
  - Métodos de fábrica de servidor MCP
  - Métodos de conveniência

- **[ClaudeSDKClient](./api-claude-sdk-client.md)** — client interativo para conversas
  - Métodos de conexão
  - Envio/recebimento de mensagens
  - Métodos de controle
  - Notas sobre segurança em relação a threads

- **[ClaudeAgentOptions](./api-claude-agent-options.md)** — builder de configuração
  - Todas as opções de configuração
  - Métodos do builder

- **[Tipos de mensagem](./api-message-types.md)** — hierarquia completa de tipos de mensagem
  - Todos os tipos de mensagem e blocos de conteúdo
  - Documentação dos campos

- **[Tipos de exceção](./api-exceptions.md)** — tratamento de erros e exceções
  - Hierarquia de exceções
  - `ResultException` e a carga útil de um resultado de erro terminal
  - Onde cada exceção realmente aparece
  - Exemplos de tratamento de erros

### Recursos do projeto
- **[CHANGELOG](../CHANGELOG.md)** — histórico de versões e notas de lançamento (só em inglês)
- **[Paridade com o SDK Python](../PYTHON_SDK_PARITY.md)** — comparação de recursos com o SDK Python (só em inglês)
- **[Sobre as traduções](../TRANSLATIONS.md)** — escopo das traduções, política de sincronização e como contribuir (em inglês)

## Estrutura do projeto

Este é um projeto Maven com múltiplos módulos:

```
claude-agent-sdk-java/
├── sdk/              # Core SDK library (published to Maven Central; mirrored to GitHub Packages)
│   ├── src/main/java/in/vidyalai/claude/sdk/
│   │   ├── ClaudeSDK.java              # Main facade
│   │   ├── ClaudeSDKClient.java        # Interactive client
│   │   ├── ClaudeAgentOptions.java     # Configuration builder
│   │   ├── exceptions/                 # Exception types
│   │   ├── transport/                  # Transport layer
│   │   ├── internal/                   # Internal implementation
│   │   ├── mcp/                        # MCP server support
│   │   └── types/                      # Type definitions
│   └── src/test/java/                  # SDK tests
└── examples/         # Usage examples (separate module)
    └── src/main/java/examples/
        ├── QuickStart.java
        ├── MultiTurnConversation.java
        ├── McpServer.java
        └── ... (15+ examples)
```

## Primeiros passos

### Pré-requisitos
- Java 17 ou mais recente (threads virtuais são usadas automaticamente a partir do 21)
- Maven 3.6+
- CLI do Claude Code instalado separadamente

### Instalação

Adicione ao seu `pom.xml` — nenhuma configuração de repositório ou autenticação é necessária:

```xml
<dependency>
    <groupId>in.vidyalai</groupId>
    <artifactId>claude-agent-sdk-java</artifactId>
    <version>0.2.2</version>
</dependency>
```

As versões também são espelhadas no GitHub Packages para quem já aponta para lá. Essa rota exige
um token de acesso pessoal mesmo com artefatos públicos, então prefira o Maven Central a menos
que tenha um motivo para não usá-lo — veja o [README raiz](./README.md#alternativa-github-packages)
para a configuração de repositório e autenticação.

### Hello World

```java
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.types.message.Message;
import in.vidyalai.claude.sdk.types.message.AssistantMessage;

// Simple query
List<Message> messages = ClaudeSDK.query("What is 2 + 2?");
for (Message msg : messages) {
    if (msg instanceof AssistantMessage assistant) {
        System.out.println(assistant.getTextContent());
    }
}
```

### Conversa interativa

```java
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.ClaudeAgentOptions;

var options = ClaudeAgentOptions.builder()
    .maxTurns(5)
    .build();

try (var client = ClaudeSDK.createClient(options)) {
    client.connect();
    client.sendMessage("Hello!");

    for (var msg : client.receiveResponse()) {
        // Process messages
    }

    client.sendMessage("Tell me more");
    for (var msg : client.receiveResponse()) {
        // Process follow-up
    }
}
```

## Exemplos

O SDK inclui mais de 30 exemplos executáveis, cobrindo:
- Consultas e conversas básicas
- Ferramentas MCP personalizadas
- Callbacks de permissão
- Sistema de hooks, incluindo hooks atendidos no turno de continuação de um subagente em segundo plano (`BackgroundAgentHooksExample`)
- Eventos de streaming
- Tratamento de erros
- Prompts de sistema, incluindo `snapshot` (`SystemPromptExample`)
- Entrega de prompts literalmente (`VerbatimPromptsExample`)
- Recursos avançados (checkpoints, sandbox, formato de saída)
- E muito mais...

Veja o diretório `examples/` no repositório.

## Como usar esta documentação

1. **Novos usuários**: comece por este README para instalação e início rápido
2. **Entender a arquitetura**: leia a [Visão geral da arquitetura](./architecture.md)
3. **Casos de uso simples**: siga [Consultas simples](./feature-simple-queries.md)
4. **Ferramentas personalizadas**: aprenda sobre [Servidores MCP](./feature-mcp-servers.md)
5. **Recursos avançados**: explore os demais guias conforme a necessidade

## Status da documentação

### ✅ Concluído — toda a documentação principal
- README principal com início rápido e visão geral
- Visão geral da arquitetura (abrangente, com diagramas; subsistema SessionStore;
  tratamento de falhas em requisições de controle; ciclo de vida do stdin e detecção do fim da execução)
- Todos os guias de recursos:
  - Consultas simples
  - Conversas interativas
  - Opções de configuração (incluindo `sessionStore`, `loadTimeoutMs`,
    `verbatimPrompts` e o `snapshot` do prompt de sistema)
  - Tipos de mensagem (mensagens de tarefa, blocos de ferramenta do servidor, MirrorErrorMessage)
  - Servidores MCP (com ToolAnnotations, títulos de ferramenta, tipos de status, validação de
    entrada, semântica de falhas, cancelamento e handlers personalizados)
  - Definições de agente
  - Configuração do raciocínio estendido (com `ThinkingDisplay`)
  - Sistema de hooks (com os campos agentId/agentType)
  - Sistema de permissões
  - Eventos de streaming
  - Camada de transporte (com `--session-mirror`, `--thinking-display`, remoção de `--debug-to-stderr`)
  - Sistema de plugins
  - Histórico de sessões (listSessions / getSessionMessages)
  - Session Store (espelhar transcrições para S3/Postgres/Redis/back-ends próprios; executores
    limitados)
- Referência completa da API (5 documentos):
  - ClaudeSDK (incluindo os métodos de histórico de sessões)
  - ClaudeSDKClient
  - ClaudeAgentOptions
  - Tipos de mensagem
  - Tipos de exceção
- Exemplos de código (mais de 20 no diretório examples/)
- Documentação de paridade com o SDK Python

## Contribuindo com a documentação

Ao adicionar nova documentação:
1. Siga a estrutura e o formato existentes
2. Inclua exemplos de código que funcionem
3. Verifique todos os exemplos de código contra a implementação real
4. Adicione referências cruzadas para a documentação relacionada
5. Atualize o índice da documentação com os novos documentos
6. Siga os padrões da documentação:
   - Sumário claro
   - Exemplos práticos
   - Seção de boas práticas
   - Seção "Veja também" com links

## Princípios da documentação

Toda a documentação deste projeto segue estes princípios:
1. **Exatidão**: todos os exemplos de código devem funcionar e corresponder à API real
2. **Completude**: cobrir todos os principais casos de uso e cenários
3. **Clareza**: usar linguagem clara e explicar conceitos complexos
4. **Exemplos**: incluir exemplos de código práticos e executáveis
5. **Referências cruzadas**: apontar para a documentação relacionada
6. **Boas práticas**: incluir padrões recomendados e antipadrões
7. **Atualização**: manter em sincronia com as mudanças no código

## Suporte e recursos

- **Repositório no GitHub**: https://github.com/vidyalai-in/claude-agent-sdk-java
- **Issues**: relate bugs e peça recursos nas Issues do GitHub
- **Código de exemplo**: veja o diretório `examples/` no repositório
- **Especificação do MCP**: https://spec.modelcontextprotocol.io/
- **Licença**: licença MIT
- **SDK Python**: para comparação, veja https://github.com/anthropics/anthropic-sdk-python
- **Documentação do Claude Agent Python SDK**: https://platform.claude.com/docs/en/agent-sdk/python

## Contribuindo

Contribuições são bem-vindas! Consulte o repositório para as diretrizes de contribuição.

## Versão

A versão atual é a que o [Maven Central](https://central.sonatype.com/artifact/in.vidyalai/claude-agent-sdk-java)
lista — deliberadamente não repetida aqui, já que uma cópia mantida à mão ficou quatro versões
desatualizada.

Veja o [CHANGELOG.md](../CHANGELOG.md) (só em inglês) para o histórico de versões e as notas de lançamento.
