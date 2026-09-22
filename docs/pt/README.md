# Claude Agent SDK for Java

[English](../../README.md) · [简体中文](../zh/README.md) · [日本語](../ja/README.md) · [한국어](../ko/README.md) · **Português** · [Español](../es/README.md)

> **Sobre esta tradução**: a documentação em inglês é a única autoritativa. Esta tradução pode estar desatualizada em relação ao [original em inglês](../../README.md); em caso de divergência, o inglês prevalece. Os blocos de código são mantidos idênticos ao original e não foram traduzidos. Veja [docs/TRANSLATIONS.md](../TRANSLATIONS.md) para mais detalhes.

SDK Java para o Claude Agent. Este SDK oferece uma API Java abrangente para interagir com o Claude Code, permitindo que você construa aplicações com IA usando os recursos do Claude.

## Requisitos

- Java 17 ou mais recente (usa interfaces seladas e records)
  - No Java 21+, o SDK executa seu trabalho em segundo plano em threads virtuais; no 17-20
    ele usa threads de plataforma daemon nomeadas. Nenhuma configuração é necessária.
- Maven 3.6+

**Observação:** o Claude Code CLI precisa ser instalado separadamente:
```bash
curl -fsSL https://claude.ai/install.sh | bash
```

Ou informe um caminho personalizado:
```java
ClaudeAgentOptions.builder()
    .cliPath(Path.of("/path/to/claude"))
    .build();
```

## Instalação

Publicado no Maven Central, portanto não é preciso configurar repositório nem autenticação.

### Maven

```xml
<dependency>
    <groupId>in.vidyalai</groupId>
    <artifactId>claude-agent-sdk-java</artifactId>
    <version>0.2.2</version>
</dependency>
```

### Gradle

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("in.vidyalai:claude-agent-sdk-java:0.2.2")
}
```

### Alternativa: GitHub Packages

As versões também são espelhadas no GitHub Packages para quem já depende desse canal. Esse
caminho exige um token de acesso pessoal do GitHub mesmo que os artefatos sejam públicos, então
prefira o Maven Central a menos que tenha um motivo para não usá-lo.

<details>
<summary>Configuração do GitHub Packages</summary>

Adicione o repositório ao seu `pom.xml`:

```xml
<repositories>
    <repository>
        <id>github</id>
        <url>https://maven.pkg.github.com/vidyalai-in/claude-agent-sdk-java</url>
    </repository>
</repositories>
```

Ou ao seu `build.gradle.kts`:

```kotlin
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/vidyalai-in/claude-agent-sdk-java")
        credentials {
            username = project.findProperty("gpr.user") as String? ?: System.getenv("USERNAME")
            password = project.findProperty("gpr.key") as String? ?: System.getenv("TOKEN")
        }
    }
}
```

Depois configure a autenticação. No Maven, adicione isto ao seu `~/.m2/settings.xml`:

```xml
<settings>
  <servers>
    <server>
      <id>github</id>
      <username>YOUR_GITHUB_USERNAME</username>
      <password>YOUR_GITHUB_PERSONAL_ACCESS_TOKEN</password>
    </server>
  </servers>
</settings>
```

No Gradle, crie ou atualize `~/.gradle/gradle.properties`:

```properties
gpr.user=YOUR_GITHUB_USERNAME
gpr.key=YOUR_GITHUB_PERSONAL_ACCESS_TOKEN
```

Gere um token de acesso pessoal com o escopo `read:packages` em: https://github.com/settings/tokens

</details>

## Início rápido

```java
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.types.Message;
import in.vidyalai.claude.sdk.types.AssistantMessage;

public class QuickStart {
    public static void main(String[] args) {
        // Simple one-shot query
        for (Message message : ClaudeSDK.query("What is 2 + 2?")) {
            if (message instanceof AssistantMessage assistant) {
                System.out.println(assistant.getTextContent());
            }
        }
    }
}
```

## Uso básico: ClaudeSDK.query()

`ClaudeSDK.query()` serve para consultas simples e pontuais. Retorna um `List<Message>` com
todas as mensagens da resposta.

```java
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.types.*;

// Simple query
List<Message> messages = ClaudeSDK.query("Hello Claude");
for (Message msg : messages) {
    if (msg instanceof AssistantMessage assistant) {
        for (ContentBlock block : assistant.content()) {
            if (block instanceof TextBlock text) {
                System.out.println(text.text());
            }
        }
    }
}

// With options
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .systemPrompt("You are a helpful assistant")
    .maxTurns(1)
    .build();

List<Message> response = ClaudeSDK.query("Tell me a joke", options);
```

### Métodos de conveniência

```java
// Get just the text response
String text = ClaudeSDK.queryForText("What is the capital of France?");
System.out.println(text);  // "Paris"

// Get just the result message
ResultMessage result = ClaudeSDK.queryForResult("Do something", options);
System.out.println("Cost: $" + result.totalCostUsd());
```

### Usando ferramentas

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .allowedTools(List.of("Read", "Write", "Bash"))
    .permissionMode(PermissionMode.ACCEPT_EDITS)  // Auto-accept file edits
    .build();

List<Message> messages = ClaudeSDK.query("Create a hello.py file", options);

for (Message msg : messages) {
    if (msg instanceof AssistantMessage assistant) {
        if (assistant.hasToolUse()) {
            for (ContentBlock block : assistant.content()) {
                if (block instanceof ToolUseBlock toolUse) {
                    System.out.println("Tool: " + toolUse.name());
                    System.out.println("Input: " + toolUse.input());
                }
            }
        }
    }
}
```

### Diretório de trabalho

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .cwd(Path.of("/path/to/project"))
    .build();
```

### Entrada por streaming (várias mensagens)

```java
// Send multiple messages in sequence
var messages = List.of(
    Map.of("type", "user", "session_id", "default",
           "message", Map.of("role", "user", "content", "First message")),
    Map.of("type", "user", "session_id", "default",
           "message", Map.of("role", "user", "content", "Follow-up question"))
);

List<Message> responses = ClaudeSDK.query(messages.iterator(), options);
```

## ClaudeSDKClient

`ClaudeSDKClient` dá suporte a conversas bidirecionais e interativas com o Claude Code. Ao
contrário de `query()`, ele habilita **conversas de múltiplos turnos**, **ferramentas
personalizadas**, **hooks** e **interação em tempo real**.

### Uso básico

```java
import in.vidyalai.claude.sdk.ClaudeSDKClient;
import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.types.*;

// Using try-with-resources (recommended)
try (var client = ClaudeSDK.createClient()) {
    client.connect("Hello, Claude!");

    for (Message msg : client.receiveResponse()) {
        if (msg instanceof AssistantMessage assistant) {
            System.out.println(assistant.getTextContent());
        }
    }
}

// Multi-turn conversation
try (var client = ClaudeSDK.createClient()) {
    // First turn
    client.connect("What is machine learning?");
    for (Message msg : client.receiveResponse()) {
        System.out.println(msg);
    }

    // Follow-up
    client.sendMessage("Can you give me an example?");
    for (Message msg : client.receiveResponse()) {
        System.out.println(msg);
    }
}
```

### Gerenciamento manual da conexão

```java
ClaudeSDKClient client = new ClaudeSDKClient();
try {
    client.connect("Start a conversation");

    // Receive messages
    for (Message msg : client.receiveResponse()) {
        process(msg);
    }

    // Send follow-up
    client.sendMessage("Continue...");
    for (Message msg : client.receiveResponse()) {
        process(msg);
    }
} finally {
    client.disconnect();
}
```

### Interromper a execução

```java
try (var client = ClaudeSDK.createClient()) {
    client.connect("Do a long task");

    // In another thread or after some condition
    client.interrupt();  // Sends interrupt signal
}
```

### Troca dinâmica de modelo

```java
try (var client = ClaudeSDK.createClient()) {
    client.connect("Start with default model");

    // Switch to a different model mid-conversation
    client.setModel("claude-sonnet-4-5");

    client.sendMessage("Continue with new model");
}
```

### Mudanças no modo de permissão

```java
try (var client = ClaudeSDK.createClient()) {
    client.connect("Start in default mode");

    // Change permission mode during conversation
    client.setPermissionMode(PermissionMode.BYPASS_PERMISSIONS);

    client.sendMessage("Now run dangerous commands");
}
```

### Status do servidor MCP

```java
try (var client = ClaudeSDK.createClient(options)) {
    client.connect();

    // Query MCP server connection status
    Map<String, Object> status = client.getMcpStatus();
    List<?> servers = (List<?>) status.get("mcpServers");

    for (Object server : servers) {
        Map<?, ?> serverInfo = (Map<?, ?>) server;
        System.out.println("Server: " + serverInfo.get("name") +
                          " Status: " + serverInfo.get("status"));
    }
}
```

### Utilitários do SDK

```java
// Get SDK version
String version = ClaudeSDK.getVersion();
System.out.println("Claude SDK Version: " + version);

// Check if client is connected
try (var client = ClaudeSDK.createClient()) {
    System.out.println("Connected: " + client.isConnected());  // false

    client.connect();
    System.out.println("Connected: " + client.isConnected());  // true
}
```

## Ferramentas personalizadas (servidores MCP do SDK)

Crie servidores MCP in-process que rodam diretamente dentro da sua aplicação Java.

### Usando a anotação @Tool

```java
import in.vidyalai.claude.sdk.mcp.Tool;
import in.vidyalai.claude.sdk.mcp.ToolResult;

public class MyTools {

    @Tool(name = "greet", description = "Greet a user by name")
    public CompletableFuture<ToolResult> greet(Map<String, Object> args) {
        String name = (String) args.get("name");
        return CompletableFuture.completedFuture(
            ToolResult.text("Hello, " + name + "!")
        );
    }

    @Tool(name = "calculate", description = "Perform a calculation")
    public CompletableFuture<ToolResult> calculate(Map<String, Object> args) {
        int a = ((Number) args.get("a")).intValue();
        int b = ((Number) args.get("b")).intValue();
        String op = (String) args.get("operation");

        int result = switch (op) {
            case "add" -> a + b;
            case "subtract" -> a - b;
            case "multiply" -> a * b;
            case "divide" -> a / b;
            default -> throw new IllegalArgumentException("Unknown operation: " + op);
        };

        return CompletableFuture.completedFuture(
            ToolResult.text(String.valueOf(result))
        );
    }
}

// Create SDK MCP server from annotated methods
McpSdkServerConfig serverConfig = ClaudeSDK.createSdkMcpServer(
    "my-tools",
    new MyTools()
);

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .mcpServers(Map.of("tools", serverConfig))
    .allowedTools(List.of("mcp__tools__greet", "mcp__tools__calculate"))
    .build();

try (var client = ClaudeSDK.createClient(options)) {
    client.connect("Greet Alice and calculate 5 + 3");
    for (Message msg : client.receiveResponse()) {
        System.out.println(msg);
    }
}
```

### Usando SdkMcpTool diretamente

```java
import in.vidyalai.claude.sdk.mcp.*;

// Create tools programmatically
SdkMcpTool<Map<String, Object>> greetTool = SdkMcpTool.create(
    "greet",
    "Greet a user",
    Map.of(
        "type", "object",
        "properties", Map.of(
            "name", Map.of("type", "string", "description", "Name to greet")
        ),
        "required", List.of("name")
    ),
    args -> {
        String name = (String) args.get("name");
        return CompletableFuture.completedFuture(
            ToolResult.text("Hello, " + name + "!")
        );
    }
);

// Create server
SdkMcpServer server = SdkMcpServer.create("my-server", "1.0.0", List.of(greetTool));
McpSdkServerConfig config = server.toConfig();

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .mcpServers(Map.of("my-server", config))
    .allowedTools(List.of("mcp__my-server__greet"))
    .build();
```

### Tipos de resultado de ferramenta

```java
// Text result
ToolResult.text("Operation completed successfully")

// Error result
ToolResult.error("File not found: /path/to/file")

// Image result (base64 encoded)
ToolResult.image(base64Data, "image/png")

// Structured data, serialized into a text block
ToolResult.json(Map.of("status", "ok", "rows", 42))

// Several blocks, including a link the CLI renders as text
ToolResult.builder()
    .addText("Summary:")
    .addResourceLink("Full report", "file:///tmp/report.md", "Every row")
    .build()
```

### Cancelando uma ferramenta de longa duração

O CLI desiste de uma ferramenta que ultrapassa o tempo limite do MCP e envia
`notifications/cancelled`. A chamada é respondida sem espera, mas o handler continua rodando a
menos que ele próprio verifique — um `CompletableFuture` não pode ser interrompido de fora.
Receba um `ToolCallContext` junto com os argumentos para perceber isso:

```java
SdkMcpTool<Map<String, Object>> crawl = SdkMcpTool.create(
    "crawl", "Fetch every page under a URL", schema,
    (args, context) -> CompletableFuture.supplyAsync(() -> {
        List<String> pages = new ArrayList<>();
        for (String url : urlsFrom(args)) {
            if (context.isCancelled()) {
                break;                       // nobody is waiting any more
            }
            pages.add(fetch(url));
        }
        return ToolResult.text(String.join("\n", pages));
    }));
```

Handlers que recebem apenas os argumentos não são afetados. `context.onCancel(...)` cobre
trabalhos que não podem fazer polling, como uma leitura bloqueante.

### Trazendo seu próprio servidor MCP

`McpSdkServerConfig` guarda um `McpMessageHandler`, então uma aplicação que precise de partes
do MCP que o servidor embutido não atende — recursos, prompts, completions — pode implementar
a interface por conta própria e registrá-la da mesma forma.
Veja [docs/feature-mcp-servers.md](../feature-mcp-servers.md#custom-mcp-handlers).

### Vantagens sobre servidores MCP externos

- **Sem gerenciamento de subprocessos** — roda na mesma JVM da sua aplicação
- **Melhor desempenho** — sem sobrecarga de IPC nas chamadas de ferramenta
- **Implantação mais simples** — um único processo Java em vez de vários
- **Depuração mais fácil** — todo o código roda no mesmo processo
- **Segurança de tipos** — chamadas diretas a métodos Java

## Hooks

Hooks são callbacks que o Claude Code invoca em pontos específicos do laço do agente. Eles
permitem processamento determinístico e feedback automatizado.

### Eventos de hook

| Evento | Descrição |
|-------|-------------|
| `PreToolUse` | Antes de uma ferramenta ser executada |
| `PostToolUse` | Depois que uma ferramenta termina |
| `UserPromptSubmit` | Quando o usuário envia um prompt |
| `Stop` | Quando a sessão para |
| `SubagentStop` | Quando um subagente para |
| `PreCompact` | Antes da compactação de contexto |

### Exemplo: bloqueando comandos perigosos

```java
import in.vidyalai.claude.sdk.types.*;
import java.util.concurrent.CompletableFuture;

HookMatcher.HookCallback checkBashCommand = (input, context) -> {
    if (input instanceof PreToolUseHookInput preToolUse) {
        if ("Bash".equals(preToolUse.toolName())) {
            String command = (String) preToolUse.toolInput().get("command");

            // Block dangerous patterns
            List<String> blockedPatterns = List.of("rm -rf", "sudo", "chmod 777");
            for (String pattern : blockedPatterns) {
                if (command.contains(pattern)) {
                    return CompletableFuture.completedFuture(
                        HookOutput.builder()
                            .hookSpecificOutput(
                                HookSpecificOutput.preToolUse()
                                    .permissionDecision("deny")
                                    .permissionDecisionReason("Command contains blocked pattern: " + pattern)
                                    .build()
                            )
                            .build()
                    );
                }
            }
        }
    }
    return CompletableFuture.completedFuture(HookOutput.empty());
};

HookMatcher bashMatcher = new HookMatcher("Bash", List.of(checkBashCommand));

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .allowedTools(List.of("Bash"))
    .hooks(Map.of(HookEvent.PRE_TOOL_USE, List.of(bashMatcher)))
    .build();

try (var client = ClaudeSDK.createClient(options)) {
    // This will be blocked
    client.connect("Run: rm -rf /");
    for (Message msg : client.receiveResponse()) {
        System.out.println(msg);
    }
}
```

### Exemplo: registrando todos os usos de ferramenta

```java
HookMatcher.HookCallback logToolUse = (input, context) -> {
    if (input instanceof PostToolUseHookInput postToolUse) {
        System.out.printf("Tool '%s' completed with response: %s%n",
            postToolUse.toolName(),
            postToolUse.toolResponse());
    }
    return CompletableFuture.completedFuture(HookOutput.empty());
};

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .hooks(Map.of(
        HookEvent.POST_TOOL_USE, List.of(new HookMatcher("*", List.of(logToolUse)))
    ))
    .build();
```

## Callbacks de permissão

Controle a execução de ferramentas com lógica de permissão personalizada.

```java
import in.vidyalai.claude.sdk.types.*;
import java.util.concurrent.CompletableFuture;

ClaudeAgentOptions.CanUseTool permissionCallback = (toolName, input, context) -> {
    // Check tool name
    if ("Bash".equals(toolName)) {
        String command = (String) input.get("command");

        // Allow safe commands
        if (command.startsWith("ls") || command.startsWith("cat") || command.startsWith("echo")) {
            return CompletableFuture.completedFuture(new PermissionResultAllow());
        }

        // Deny dangerous commands
        return CompletableFuture.completedFuture(
            new PermissionResultDeny("Bash command not allowed: " + command, false)
        );
    }

    // Allow all other tools
    return CompletableFuture.completedFuture(new PermissionResultAllow());
};

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .canUseTool(permissionCallback)
    .allowedTools(List.of("Bash", "Read", "Write"))
    .build();
```

### Modificando a entrada da ferramenta

```java
ClaudeAgentOptions.CanUseTool sanitizeInput = (toolName, input, context) -> {
    if ("Bash".equals(toolName)) {
        // Modify the input
        Map<String, Object> modifiedInput = new HashMap<>(input);
        modifiedInput.put("timeout", 30);  // Add timeout

        return CompletableFuture.completedFuture(
            new PermissionResultAllow(modifiedInput, null)
        );
    }
    return CompletableFuture.completedFuture(new PermissionResultAllow());
};
```

### Atualizações de permissão

```java
ClaudeAgentOptions.CanUseTool upgradePermissions = (toolName, input, context) -> {
    // Grant bypass permissions for this session
    List<PermissionUpdate> updates = List.of(
        PermissionUpdate.setMode(
            PermissionMode.BYPASS_PERMISSIONS,
            PermissionUpdateDestination.SESSION
        )
    );

    return CompletableFuture.completedFuture(
        new PermissionResultAllow(null, updates)
    );
};
```

## Opções de configuração

### ClaudeAgentOptions

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    // Model configuration
    .model("claude-sonnet-4-5")
    .fallbackModel("claude-haiku-3-5")

    // Thinking configuration (new in v0.1.36 - takes precedence over maxThinkingTokens)
    .thinking(new ThinkingConfigEnabled(10_000))  // Enable with 10k token budget
    // Or: .thinking(new ThinkingConfigAdaptive())  // Adaptive (32k default)
    // Or: .thinking(new ThinkingConfigDisabled())  // Disable thinking
    .effort("medium")  // Thinking depth: "low", "medium", "high", "max"

    // Legacy thinking config (deprecated - use .thinking() instead)
    .maxThinkingTokens(8000)

    // Tool configuration
    .tools(List.of("Bash", "Read", "Write", "Edit"))
    .allowedTools(List.of("Read"))
    .disallowedTools(List.of("Execute"))

    // Permission configuration
    .permissionMode(PermissionMode.ACCEPT_EDITS)
    .canUseTool(permissionCallback)

    // System prompt
    .systemPrompt("You are a helpful coding assistant")
    // Or use preset
    .systemPrompt(SystemPromptPreset.claudeCode("Be concise."))

    // Session configuration
    .continueConversation(true)
    .resume("session-id")
    .forkSession(false)
    // Truncating resume: branch from an earlier transcript entry, and let the
    // CLI refuse the fork if anything other than the named turn would be lost
    .resumeSessionAt("transcript-entry-uuid")
    .resumeDropsTurn("discarded-prompt-uuid")

    // Limits
    .maxTurns(10)
    .maxBudgetUsd(1.0)

    // Working directory
    .cwd(Path.of("/path/to/project"))
    .addDirs(List.of(Path.of("/other/path")))

    // MCP servers
    .mcpServers(Map.of("server", serverConfig))

    // Hooks
    .hooks(Map.of(HookEvent.PRE_TOOL_USE, List.of(matcher)))

    // Sandbox
    .sandbox(new SandboxSettings(true))

    // Environment
    .env(Map.of("MY_VAR", "value"))

    // Beta features
    .betas(List.of("context-1m-2025-08-07"))

    // Streaming
    .includePartialMessages(true)

    // File checkpointing
    .enableFileCheckpointing(true)

    // Output format (structured output)
    .outputFormat(Map.of(
        "type", "json_schema",
        "schema", schemaMap
    ))

    .build();
```

### Modos de permissão

```java
PermissionMode.DEFAULT           // CLI prompts for dangerous tools
PermissionMode.ACCEPT_EDITS      // Auto-accept file edits
PermissionMode.PLAN              // Show plans before execution
PermissionMode.BYPASS_PERMISSIONS // Allow all tools (use with caution)
```

### Configuração de raciocínio (thinking)

Controle o comportamento do raciocínio estendido com os tipos `ThinkingConfig` (adicionados na
v0.1.36):

```java
import in.vidyalai.claude.sdk.types.config.*;

// Adaptive thinking - uses 32,000 token budget by default
ThinkingConfig adaptive = new ThinkingConfigAdaptive();

// Enabled thinking - specify exact token budget
ThinkingConfig enabled = new ThinkingConfigEnabled(10_000);

// Disabled thinking - no extended thinking
ThinkingConfig disabled = new ThinkingConfigDisabled();

// Use with ClaudeAgentOptions
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .thinking(enabled)
    .effort("medium")  // Control thinking depth: "low", "medium", "high", "max"
    .build();
```

**Observação:** o campo `thinking` tem precedência sobre o campo obsoleto `maxThinkingTokens`.

### Configurações de servidor MCP

```java
// Stdio server (subprocess)
StdioMcpServerConfig stdio = new StdioMcpServerConfig(
    "node",
    List.of("server.js", "--port", "3000"),
    Map.of("NODE_ENV", "production")
);

// SSE server
SseMcpServerConfig sse = new SseMcpServerConfig(
    "https://api.example.com/sse",
    Map.of("Authorization", "Bearer token")
);

// HTTP server
HttpMcpServerConfig http = new HttpMcpServerConfig(
    "https://api.example.com/mcp",
    Map.of("X-API-Key", "key123")
);

// SDK server (in-process)
McpSdkServerConfig sdk = ClaudeSDK.createSdkMcpServer("name", toolInstance);

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .mcpServers(Map.of(
        "stdio-server", stdio,
        "sse-server", sse,
        "http-server", http,
        "sdk-server", sdk
    ))
    .build();
```

### Configuração de sandbox

```java
SandboxSettings sandbox = new SandboxSettings(
    true,   // enabled
    true,   // autoAllowBashIfSandboxed
    List.of("git", "docker"),  // excludedCommands
    Path.of("/sandbox"),       // sandboxDir
    new SandboxNetworkConfig(
        List.of("/tmp/ssh-agent.sock"),  // allowUnixSockets
        false,  // allowAllUnixSockets
        true,   // allowLocalBinding
        8080,   // httpProxyPort
        8081    // socksProxyPort
    ),
    new SandboxIgnoreViolations(
        List.of("/tmp"),  // file paths to ignore
        List.of("localhost")  // network hosts to ignore
    ),
    false  // allowNestedSandbox
);

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .sandbox(sandbox)
    .build();
```

## Tipos de mensagem

O SDK usa interfaces seladas para um tratamento de mensagens com segurança de tipos e
casamento de padrões.

```java
import in.vidyalai.claude.sdk.types.*;

for (Message msg : messages) {
    switch (msg) {
        case UserMessage user -> {
            System.out.println("User: " + user.contentAsString());
        }
        case AssistantMessage assistant -> {
            System.out.println("Assistant: " + assistant.getTextContent());
            if (assistant.hasToolUse()) {
                System.out.println("(used tools)");
            }
        }
        case SystemMessage system -> {
            System.out.println("System: " + system.subtype());
        }
        case ResultMessage result -> {
            System.out.println("Result: " + result.subtype());
            System.out.println("Cost: $" + result.totalCostUsd());
            System.out.println("Turns: " + result.numTurns());
        }
        case StreamEvent event -> {
            System.out.println("Stream event: " + event.eventType());
        }
        case ConversationResetMessage reset -> {
            // /clear (or another transcript-discarding flow) replaced the
            // conversation; the CLI's running totals restart from zero.
            System.out.println("Conversation reset: " + reset.newConversationId());
        }
    }
}
```

`Message` é uma interface selada, então um `switch` exaustivo sem `default` deixa de compilar
quando um novo tipo de mensagem é adicionado. Acrescente um ramo `default ->` se preferir
absorver silenciosamente as adições futuras.

### Origem da mensagem

No modo de entrada por streaming, uma única conexão intercala os turnos que você envia com os
turnos que a sessão injeta por conta própria — notificações de tarefas em segundo plano,
prompts de tarefas agendadas que dispararam, mensagens de canais MCP, mensagens repassadas de
sessões pares. O método `origin()` em `UserMessage` e `ResultMessage` os diferencia:

```java
MessageOrigin origin = result.origin();
if (origin == null || origin.isHuman()) {
    // a turn this application submitted
} else if (origin.kind() == MessageOriginKind.TASK_NOTIFICATION) {
    // follow-up turn driven by a background task
}
```

`origin()` é null quando o CLI não atribuiu origem à mensagem. Prompts enviados via `query()`
chegam assim, a menos que você mesmo marque `"origin": {"kind": "human"}` no map da mensagem
transmitida — apenas o tipo `human` é aceito vindo de um host do SDK. Um tipo mais novo do que
este SDK modela deixa `kind()` como null, com a string do protocolo ainda legível em
`kindValue()`, e nunca conta como humano.

### Blocos de conteúdo

```java
for (ContentBlock block : assistant.content()) {
    switch (block) {
        case TextBlock text -> System.out.println(text.text());
        case ThinkingBlock thinking -> System.out.println("Thinking: " + thinking.thinking());
        case ToolUseBlock toolUse -> {
            System.out.println("Tool: " + toolUse.name());
            System.out.println("Input: " + toolUse.input());
        }
        case ToolResultBlock result -> {
            System.out.println("Result: " + result.content());
            if (result.isError()) {
                System.out.println("(error)");
            }
        }
    }
}
```

## Tratamento de erros

```java
import in.vidyalai.claude.sdk.exceptions.*;

try {
    List<Message> messages = ClaudeSDK.query("Hello");
} catch (CLINotFoundException e) {
    System.err.println("Claude Code CLI not found. Install with:");
    System.err.println("  curl -fsSL https://claude.ai/install.sh | bash");
} catch (CLIConnectionException e) {
    System.err.println("Failed to connect to CLI: " + e.getMessage());
} catch (ResultException e) {
    // A control request failed on a terminal error result — in practice an
    // `initialize` the CLI refused during startup (e.g. a resume rejected by
    // resumeDropsTurn). An error result *during* the run arrives as a
    // QueryFailedException instead; see below.
    System.err.println("Startup failed: " + e.subtype() + " / " + e.terminalReason());
} catch (ProcessException e) {
    System.err.println("Process failed with exit code: " + e.getExitCode());
    System.err.println("Stderr: " + e.getStderr());
} catch (CLIJSONDecodeException e) {
    System.err.println("Failed to parse JSON response: " + e.getMessage());
    System.err.println("Raw line: " + e.getLine());
} catch (MessageParseException e) {
    System.err.println("Failed to parse message: " + e.getMessage());
    System.err.println("Data: " + e.getData());
} catch (QueryFailedException e) {
    // The run ended in an error result (max turns, max budget, an API
    // failure). The messages that arrived before it are still available...
    System.err.println("Run ended early: " + e.getMessage());
    ResultMessage result = e.resultMessage();
    if (result != null) {
        System.err.println("Stopped by: " + result.subtype());
        System.err.println("Spent: $" + result.totalCostUsd());
    }
    // ...and the typed payload is on the cause.
    if (e.getCause() instanceof ResultException cause) {
        System.err.println("Why: " + cause.terminalReason()
                + " (HTTP " + cause.apiErrorStatus() + ")");
    }
} catch (ClaudeSDKException e) {
    System.err.println("SDK error: " + e.getMessage());
}
```

### Hierarquia de exceções

```
ClaudeSDKException (base)
├── CLIConnectionException
│   └── CLINotFoundException
├── ProcessException
│   └── ResultException
├── CLIJSONDecodeException
├── MessageParseException
└── QueryFailedException
```

`ResultException` é lançada quando o CLI encerra uma execução malsucedida emitindo uma mensagem
`result` com `is_error: true` e então sai com código diferente de zero. Ela substitui, nesse
caso, a `ProcessException` genérica de "exit code 1" e carrega o payload do resultado —
`subtype()`, `errors()`, `result()`, `apiErrorStatus()`, `terminalReason()`, `sessionId()` e o
`data()` bruto — para que quem chama possa ramificar conforme o *motivo* da falha. Note que uma
execução encerrada por uma falha de API chega com `subtype() == "success"` e
`terminalReason() == "api_error"`, com o texto explicativo em `result()`. Handlers existentes
com `catch (ProcessException e)` continuam funcionando.

Normalmente você a encontra como `QueryFailedException.getCause()`, e não sozinha: o `query(...)`
que coleta mensagens a envolve para que as mensagens já recebidas não se percam. Ela é lançada
diretamente apenas por uma requisição de controle que falhou, como um `initialize` recusado
pelo CLI na inicialização. `ClaudeSDKClient.receiveResponse()` não a lança de forma alguma —
esse iterador para no `ResultMessage`, então verifique `ResultMessage.isError()` ali.

`QueryFailedException` é específica da família `ClaudeSDK.query(...)` que coleta mensagens. O
CLI reporta condições como `error_max_turns` e `error_max_budget_usd` emitindo um turno completo
— incluindo um `ResultMessage` final com o subtipo e o custo — e *depois* saindo com código
diferente de zero. Um consumidor de streaming (`ClaudeSDKClient.receiveMessages()` /
`receiveResponse()`) vê cada uma dessas mensagens antes do lançamento; uma chamada que coleta
precisa ou retornar uma lista ou lançar, então ela lança esta exceção e devolve as mensagens
coletadas via `partialMessages()` e o acessor de conveniência `resultMessage()`; o
`getCause()` dela é a `ResultException` descrita acima. Capture-a sempre que definir `maxTurns`
ou `maxBudgetUsd`: atingir um limite que você mesmo configurou é um desfecho esperado, não uma
falha.

## Eventos de streaming

Habilite o streaming de mensagens parciais para atualizações em tempo real.

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .includePartialMessages(true)
    .build();

try (var client = ClaudeSDK.createClient(options)) {
    client.connect("Write a long story");

    for (Message msg : client.receiveMessages()) {
        if (msg instanceof StreamEvent event) {
            // Process streaming delta
            System.out.print(event.event().get("delta"));
        } else if (msg instanceof AssistantMessage assistant) {
            // Complete message
            System.out.println("\n--- Complete ---");
            System.out.println(assistant.getTextContent());
        } else if (msg instanceof ResultMessage result) {
            break;  // Done
        }
    }
}
```

## Checkpoints de arquivo

Acompanhe alterações em arquivos e volte a estados anteriores.

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .enableFileCheckpointing(true)
    .build();

try (var client = ClaudeSDK.createClient(options)) {
    client.connect("Modify some files");

    String checkpointId = null;
    for (Message msg : client.receiveResponse()) {
        if (msg instanceof UserMessage user && user.uuid() != null) {
            checkpointId = user.uuid();  // Save checkpoint
        }
    }

    // Later, rewind to checkpoint
    if (checkpointId != null) {
        client.rewindFiles(checkpointId);
    }
}
```

## Agentes personalizados

Defina agentes personalizados com capacidades específicas.

```java
AgentDefinition codeReviewer = new AgentDefinition(
    "Code Review Agent",
    "You are an expert code reviewer. Focus on security, performance, and best practices.",
    List.of("Read", "Grep", "Glob"),
    "sonnet"
);

AgentDefinition testWriter = new AgentDefinition(
    "Test Writer Agent",
    "You write comprehensive unit tests.",
    List.of("Read", "Write", "Bash"),
    "sonnet"
);

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .agents(Map.of(
        "code-reviewer", codeReviewer,
        "test-writer", testWriter
    ))
    .build();
```

## Exemplos

Veja o módulo `examples/` para exemplos completos e funcionais:

- `QuickStart.java` — uso básico
- `MultiTurnConversation.java` — conversas interativas
- `ToolUsage.java` — usando ferramentas embutidas
- `McpServer.java` — criando ferramentas MCP personalizadas
- `AutoSchemaGeneration.java` — geração automática de schema para ferramentas
- `Hooks.java` — callbacks de hook (incluindo os novos eventos: Notification, SubagentStart, PermissionRequest)
- `PermissionCallbacks.java` — lógica de permissão personalizada
- `StreamingEvents.java` — streaming em tempo real
- `StructuredOutputExample.java` — saída estruturada com validação por JSON Schema (simples, aninhada, enum, com ferramentas)
- `DynamicControlExample.java` — recursos de controle dinâmico (setPermissionMode, setModel, interrupt)
- `ErrorHandling.java` — tratamento de exceções
- `AdvancedFeatures.java` — checkpoints, sandbox, saída estruturada
- `ToolsConfigurationExample.java` — configuração de ferramentas (array, preset, vazio)
- `MaxBudgetExample.java` — limite de orçamento e controle de custo
- `SettingSourcesExample.java` — fontes de configuração (user, project, local)
- `StderrCallbackExample.java` — capturando a saída stderr do CLI
- `PluginsExample.java` — uso do sistema de plugins
- `AgentsExample.java` — definições programáticas de subagentes
- `FilesystemAgentsExample.java` — configuração de agentes baseada no sistema de arquivos
- `LargeAgentsExample.java` — definições grandes de agente (260KB+) via requisição initialize
- `SystemPromptExample.java` — uso de prompt de sistema personalizado
- `IncludePartialMessagesExample.java` — streaming com atualizações de mensagens parciais

### Executando os exemplos

O módulo de exemplos é um módulo Maven separado que depende do SDK. Compilar a partir da raiz do
repositório satisfaz essa dependência pelo reactor; compilar `examples/` sozinho a resolve pelo
Maven Central, o que não exige configuração de repositório nem de autenticação.

#### Opção 1: executar os exemplos a partir do diretório raiz (recomendado)

Compile todos os módulos e execute um exemplo:

```bash
# Build all modules (SDK + examples)
mvn clean package -DskipTests

# Run an example using Maven exec plugin
mvn exec:java -Dexec.mainClass="examples.QuickStart" -pl examples

# Run different examples
mvn exec:java -Dexec.mainClass="examples.MultiTurnConversation" -pl examples
mvn exec:java -Dexec.mainClass="examples.McpServer" -pl examples
```

#### Opção 2: executar os exemplos a partir do diretório examples

```bash
# Navigate to examples directory
cd examples

# Build examples (resolves the published SDK from Maven Central)
mvn clean package -DskipTests

# Run an example using Maven exec plugin
mvn exec:java -Dexec.mainClass="examples.QuickStart"

# Or use java -cp
java -cp target/classes:target/dependency/* examples.QuickStart
```

#### Opção 3: executar os exemplos com o SDK de desenvolvimento local

Para testar os exemplos contra sua versão local de desenvolvimento do SDK (não a publicada):

1. Instale o SDK localmente:
   ```bash
   cd sdk
   mvn clean install -DskipTests
   cd ..
   ```

2. Atualize `examples/pom.xml` para usar a versão SNAPSHOT:
   ```xml
   <dependency>
       <groupId>in.vidyalai</groupId>
       <artifactId>claude-agent-sdk-java</artifactId>
       <version>0.1.1-SNAPSHOT</version>
   </dependency>
   ```

3. Execute os exemplos como descrito na Opção 1 ou 2.

**Observação:** você também pode fixar o módulo de exemplos em uma versão publicada do SDK
definindo o `<version>` da dependência em `examples/pom.xml` para essa versão (por exemplo,
`0.2.2`). Ela é resolvida pelo Maven Central, então não é preciso configurar repositório nem
autenticação.

## Segurança em relação a threads

- `ClaudeSDKClient` **não é thread-safe**. Use um client por thread ou sincronize o acesso.
- Os métodos `ClaudeSDK.query()` criam novas conexões e podem ser chamados com segurança a
  partir de várias threads.
- Callbacks (hooks, permissões) podem ser chamados a partir de threads diferentes; garanta que
  suas implementações de callback sejam thread-safe.

## Modelo de concorrência: Python vs Java

O SDK Java usa um modelo de concorrência fundamentalmente diferente do SDK Python. Entender
essas diferenças ajuda ao portar código ou comparar exemplos.

### SDK Python: modelo async/await

O SDK Python usa a sintaxe `async`/`await` do Python com asyncio ou trio:

```python
# Python SDK - async/await with asyncio
async def example():
    async with ClaudeSDKClient() as client:
        await client.connect("Hello")
        async for message in client.receive_response():
            print(message)

# Python SDK - streaming with async iterables
async def message_stream():
    yield {"type": "user", "message": {"role": "user", "content": "Hi"}}
    yield {"type": "user", "message": {"role": "user", "content": "Bye"}}

await client.connect(message_stream())
```

**Principais recursos do Python:**
- Palavras-chave `async`/`await` para operações não bloqueantes
- `async for` para iterar sobre iteráveis assíncronos
- `async with` para gerenciadores de contexto assíncronos
- Iteráveis/geradores assíncronos (`async def` com `yield`)
- Bibliotecas: asyncio, trio

### SDK Java: modelo síncrono + CompletableFuture

O SDK Java usa APIs síncronas com CompletableFuture para operações assíncronas:

```java
// Java SDK - synchronous with try-with-resources
try (var client = ClaudeSDK.createClient()) {
    client.connect("Hello");
    for (Message message : client.receiveResponse()) {
        System.out.println(message);
    }
}

// Java SDK - streaming with Iterator
List<Map<String, Object>> messages = List.of(
    Map.of("type", "user", "message", Map.of("role", "user", "content", "Hi")),
    Map.of("type", "user", "message", Map.of("role", "user", "content", "Bye"))
);
client.query(messages.iterator());
```

**Principais recursos do Java:**
- **Iteradores síncronos** (`Iterator<Message>`) em vez de iteráveis assíncronos
- **Try-with-resources** (`try (...)`) em vez de gerenciadores de contexto assíncronos
- **CompletableFuture** para callbacks assíncronos (hooks, permissões)
- **Threads virtuais** para I/O bloqueante eficiente no Java 21+, com fallback para threads de plataforma no 17-20
- **ExecutorService** para gerenciamento de tarefas em segundo plano

### Por que os exemplos assíncronos do Python não se traduzem diretamente

Alguns exemplos do SDK Python não têm equivalente direto em Java porque demonstram padrões
específicos de código assíncrono:

| Exemplo em Python | Por que não existe em Java | Equivalente em Java |
|----------------|-----------------|-----------------|
| `streaming_mode_ipython.py` | Integração com o REPL assíncrono específico do IPython | Use o REPL do Java (jshell) com as APIs síncronas |
| `streaming_mode_trio.py` | Biblioteca de concorrência específica do Trio | Use a concorrência padrão do Java (ExecutorService, threads virtuais) |
| `test_connect_with_async_iterable` | Padrão de gerador assíncrono | `client.query(Iterator<Map>)` com um Iterator comum |
| `test_concurrent_send_receive` | Operações concorrentes assíncronas | Use `Thread.startVirtualThread()` ou ExecutorService |

### Operações concorrentes em Java

Para operações que realmente precisam de concorrência em Java:

```java
// Concurrent send and receive using virtual threads
try (var client = ClaudeSDK.createClient()) {
    client.connect();

    // Receive in background thread
    Thread receiveThread = Thread.startVirtualThread(() -> {
        for (Message msg : client.receiveMessages()) {
            if (msg instanceof ResultMessage) break;
            System.out.println("Received: " + msg);
        }
    });

    // Send from main thread
    client.sendMessage("First message");
    Thread.sleep(1000);
    client.sendMessage("Second message");

    receiveThread.join();
}
```

### Considerações de desempenho

- **Python**: I/O assíncrono é eficiente para operações limitadas por I/O, mas exige pontos explícitos de `await`
- **Java**: threads virtuais (Project Loom) tornam o I/O bloqueante tão eficiente quanto o assíncrono, sem mudanças de sintaxe
- **Java**: APIs síncronas são mais simples de usar e depurar do que código assíncrono
- **Python**: o Trio oferece concorrência estruturada; o Java alcança algo semelhante com try-with-resources e ExecutorService

### Guia de migração: de Python para Java

| Padrão em Python | Equivalente em Java |
|----------------|-----------------|
| `async with client:` | `try (var client = ...) {` |
| `await client.connect()` | `client.connect()` (síncrono) |
| `async for msg in client.receive():` | `for (Message msg : client.receiveResponse())` |
| `await asyncio.sleep(1)` | `Thread.sleep(1000)` |
| `asyncio.create_task()` | `Thread.startVirtualThread(() -> ...)` |
| `async def generator():` + `yield` | Implementação de `Iterator<T>` |
| `CompletableFuture.completed()` | `CompletableFuture.completedFuture()` |

**Conclusão:** o SDK Java prioriza a simplicidade e usa APIs síncronas com threads virtuais para
concorrência eficiente, enquanto o SDK Python usa async/await para operações não bloqueantes.
Ambos alcançam funcionalidade semelhante com os idiomas de suas respectivas linguagens.

## Boas práticas

1. **Sempre feche os clients** — use try-with-resources ou chame `disconnect()` em um bloco finally.
2. **Trate os erros com elegância** — capture exceções específicas para obter mensagens de erro melhores.
3. **Defina limites apropriados** — use `maxTurns` e `maxBudgetUsd` para limitar a execução.
4. **Use callbacks de permissão para segurança** — não dependa apenas do `permissionMode`.
5. **Prefira servidores MCP do SDK** — são mais rápidos e mais fáceis de depurar do que processos externos.

## Documentação

- **[Análise de paridade com o SDK Python](../PYTHON_SDK_PARITY.md)** — comparação abrangente
  entre os SDKs Python e Java, incluindo o estado da paridade de recursos, comparação dos
  sistemas de tipos, cobertura de exemplos e detalhes de implementação. (em inglês)
- **[Índice da documentação técnica](./index.md)** — arquitetura, guias de recursos e referência
  da API (nesta língua).
- **[Sobre as traduções](../TRANSLATIONS.md)** — escopo das traduções, política de sincronização
  e como contribuir. (em inglês)

## Licença

[MIT](../../LICENSE)
