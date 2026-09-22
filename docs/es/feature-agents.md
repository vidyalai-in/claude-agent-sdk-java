# Definiciones de agente

> **Sobre esta traducción**: la documentación en inglés es la única autoritativa. Esta traducción puede estar desactualizada respecto al [original en inglés](../feature-agents.md); si hay discrepancias, prevalece el inglés. Los bloques de código se mantienen idénticos al original y no se han traducido.

Los agentes personalizados te permiten definir subagentes especializados con sus propios prompts de
sistema, herramientas y modelos. Claude puede crear estos agentes durante las conversaciones para
encargarse de tareas concretas.

## Índice
- [Visión general](#visión-general)
- [El record AgentDefinition](#el-record-agentdefinition)
- [Definiciones de agente en línea](#definiciones-de-agente-en-línea)
- [Agentes basados en el sistema de archivos](#agentes-basados-en-el-sistema-de-archivos)
- [Definiciones de agente grandes](#definiciones-de-agente-grandes)
- [Observar la salida de un subagente](#observar-la-salida-de-un-subagente)
- [Ejemplos](#ejemplos)

## Visión general

Los agentes son subagentes con nombre que Claude puede usar durante una conversación. Cada agente
tiene:

- Una **description**: qué hace el agente (se muestra a Claude cuando decide qué agente usar)
- Un **prompt de sistema**: instrucciones de comportamiento para el agente
- **Herramientas**: la lista de herramientas que el agente puede usar (null hereda del padre)
- Un **modelo**: la variante del modelo Claude en la que se ejecuta el agente (null hereda del padre)
- **Skills**: la lista de nombres de skill disponibles para el agente (null hereda del padre)
- **Memory**: el ámbito de memoria del agente (null hereda del padre)
- **Servidores MCP**: referencias a servidores MCP que el agente puede usar (null hereda del padre)

Los agentes se registran mediante `ClaudeAgentOptions.agents()` como un
`Map<String, AgentDefinition>`, donde la clave es el nombre del agente.

## El record AgentDefinition

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

| Campo | Tipo | Descripción |
|-------|------|-------------|
| `description` | `String` | Descripción legible que se muestra a Claude |
| `prompt` | `String` | Prompt de sistema que define el comportamiento del agente |
| `tools` | `List<String>` (admite null) | Nombres de herramientas permitidas; null hereda las del padre |
| `disallowedTools` | `List<String>` (admite null) | Herramientas que el agente no puede usar; null significa ninguna |
| `model` | `String` (admite null) | Alias del modelo ("sonnet", "opus", "haiku", "inherit") o ID completo |
| `skills` | `List<String>` (admite null) | Nombres de skill disponibles para el agente; null hereda |
| `memory` | `MemoryScope` (admite null) | Ámbito de memoria; null hereda del padre |
| `mcpServers` | `List<Object>` (admite null) | Referencias a servidores MCP (nombres o configuraciones en línea); null hereda |
| `initialPrompt` | `String` (admite null) | Prompt inicial que se envía cuando el agente arranca |
| `maxTurns` | `Integer` (admite null) | Máximo de turnos del agente; null significa sin límite |
| `background` | `Boolean` (admite null) | Ejecuta el agente en segundo plano |
| `effort` | `String` (admite null) | Nivel de esfuerzo: `"low"`, `"medium"`, `"high"`, `"xhigh"`, `"max"`. `"xhigh"` es propio de Opus 4.7 y recae en `"high"` en los demás modelos. Consulta también el enum [`EffortLevel`](feature-configuration-options.md#enum-effortlevel). |
| `permissionMode` | `String` (admite null) | Modo de permisos del agente |

**Campo model:** el campo `model` acepta alias cortos (`"sonnet"`, `"opus"`, `"haiku"`,
`"inherit"`) o IDs completos de modelo (por ejemplo, `"claude-sonnet-4-5"`).

### Enum MemoryScope

Controla en qué ámbito de memoria opera un agente:

```java
import in.vidyalai.claude.sdk.types.config.MemoryScope;

MemoryScope.USER     // "user" — user-level memory
MemoryScope.PROJECT  // "project" — project-scoped memory
MemoryScope.LOCAL    // "local" — local/session-scoped memory
```

## Definiciones de agente en línea

Registra agentes mediante código con `ClaudeAgentOptions`:

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

### Varios agentes

Puedes definir varios agentes en una misma sesión:

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

## Agentes basados en el sistema de archivos

Los agentes también se pueden cargar desde archivos markdown en disco usando `settingSources`. Coloca
los archivos de definición en `.claude/agents/` dentro del directorio de tu proyecto:

```
.claude/
  agents/
    code-reviewer.md
    test-writer.md
```

Después habilita la carga de agentes desde el sistema de archivos:

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

Puedes comprobar qué agentes se han cargado revisando el evento init del `SystemMessage`:

```java
for (Message msg : client.receiveResponse()) {
    if (msg instanceof SystemMessage system && "init".equals(system.subtype())) {
        List<String> agents = system.get("agents");
        System.out.println("Loaded agents: " + agents);
    }
}
```

## Definiciones de agente grandes

Los agentes se envían mediante la petición initialize del protocolo de control del SDK (por stdin),
no como argumentos del CLI. Esto significa que **no hay límite de tamaño** en las definiciones de
agente: puedes pasar sin problema más de 260KB de datos de agente.

Este comportamiento coincide con las implementaciones de los SDK de TypeScript y Python, y evita los
límites de longitud de los argumentos de línea de comandos propios de cada plataforma (ARG_MAX).

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

## Observar la salida de un subagente

Un subagente lleva su propia conversación, y solo una parte de ella llega al flujo de mensajes del
padre. Lo que llega lo hace como objetos `AssistantMessage` / `UserMessage` normales cuyo
`parentToolUseId` es el id del bloque `tool_use` del Agent que creó el subagente: ese campo es lo que
te permite distinguir los mensajes de un subagente de los de la conversación principal, y saber a
qué subagente pertenecen cuando hay varios en marcha.

Por defecto solo se reenvían los bloques `tool_use` y `tool_result` del subagente: lo justo para ver
que avanza, pero no para mostrar lo que dijo. Pon `forwardSubagentText(true)` para que sus bloques de
texto y de razonamiento se reenvíen igual:

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

La opción se envía en la petición de control `initialize` en lugar de como flag del CLI, y solo
cuando está habilitada, así que los CLI antiguos no se ven afectados.

Leer la transcripción completa de un subagente *ya terminado* es otro camino: consulta [Historial de
sesiones](./feature-session-history.md) para `listSubagents()` y `getSubagentMessages()`, cuyos
resultados llevan el mismo `parentToolUseId` y, además, un `parentAgentId` para subagentes anidados.

## Ejemplos

Consulta los archivos de ejemplo para ver demostraciones completas y ejecutables:

- [`AgentsExample.java`](../../examples/src/main/java/examples/AgentsExample.java): revisor de código, redactor de documentación y varios agentes
- [`FilesystemAgentsExample.java`](../../examples/src/main/java/examples/FilesystemAgentsExample.java): cargar agentes desde archivos de `.claude/agents/`
- [`LargeAgentsExample.java`](../../examples/src/main/java/examples/LargeAgentsExample.java): prueba de esfuerzo con cargas de agente de más de 260KB
- [`ForwardSubagentTextExample.java`](../../examples/src/main/java/examples/ForwardSubagentTextExample.java): la misma ejecución con el reenvío de texto del subagente apagado y encendido

## Véase también

- [Opciones de configuración](./feature-configuration-options.md): las opciones `agents` y `settingSources`
- [Conversaciones interactivas](./feature-interactive-conversations.md): usar agentes en sesiones de varios turnos
