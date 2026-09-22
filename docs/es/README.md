# Claude Agent SDK for Java

[English](../../README.md) · [简体中文](../zh/README.md) · [日本語](../ja/README.md) · [한국어](../ko/README.md) · [Português](../pt/README.md) · **Español**

> **Sobre esta traducción**: la documentación en inglés es la única autoritativa. Esta traducción puede estar desactualizada respecto al [original en inglés](../../README.md); si hay discrepancias, prevalece el inglés. Los bloques de código se mantienen idénticos al original y no se han traducido. Consulta [docs/TRANSLATIONS.md](../TRANSLATIONS.md) para más detalles.

SDK de Java para Claude Agent. Este SDK ofrece una API de Java completa para interactuar con Claude Code, lo que te permite crear aplicaciones con IA aprovechando las capacidades de Claude.

## Requisitos

- Java 17 o posterior (usa interfaces selladas y records)
  - En Java 21+, el SDK ejecuta su trabajo en segundo plano sobre hilos virtuales; en 17-20
    usa hilos de plataforma daemon con nombre. No hace falta configurar nada.
- Maven 3.6+

**Nota:** el CLI de Claude Code debe instalarse por separado:
```bash
curl -fsSL https://claude.ai/install.sh | bash
```

O bien indica una ruta personalizada:
```java
ClaudeAgentOptions.builder()
    .cliPath(Path.of("/path/to/claude"))
    .build();
```

## Instalación

Publicado en Maven Central, así que no hace falta configurar repositorios ni autenticación.

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

Las versiones también se replican en GitHub Packages para quienes ya dependen de ese canal. Esa
vía exige un token de acceso personal de GitHub aunque los artefactos sean públicos, así que
usa Maven Central salvo que tengas un motivo para no hacerlo.

<details>
<summary>Configuración de GitHub Packages</summary>

Añade el repositorio a tu `pom.xml`:

```xml
<repositories>
    <repository>
        <id>github</id>
        <url>https://maven.pkg.github.com/vidyalai-in/claude-agent-sdk-java</url>
    </repository>
</repositories>
```

O a tu `build.gradle.kts`:

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

Después configura la autenticación. Para Maven, añade esto a tu `~/.m2/settings.xml`:

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

Para Gradle, crea o actualiza `~/.gradle/gradle.properties`:

```properties
gpr.user=YOUR_GITHUB_USERNAME
gpr.key=YOUR_GITHUB_PERSONAL_ACCESS_TOKEN
```

Genera un token de acceso personal con el alcance `read:packages` en: https://github.com/settings/tokens

</details>

## Inicio rápido

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

`ClaudeSDK.query()` sirve para consultas sencillas y puntuales. Devuelve un `List<Message>` con
todos los mensajes de respuesta.

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

### Métodos de conveniencia

```java
// Get just the text response
String text = ClaudeSDK.queryForText("What is the capital of France?");
System.out.println(text);  // "Paris"

// Get just the result message
ResultMessage result = ClaudeSDK.queryForResult("Do something", options);
System.out.println("Cost: $" + result.totalCostUsd());
```

### Usar herramientas

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

### Directorio de trabajo

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .cwd(Path.of("/path/to/project"))
    .build();
```

### Entrada por streaming (varios mensajes)

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

`ClaudeSDKClient` admite conversaciones bidireccionales e interactivas con Claude Code. A
diferencia de `query()`, habilita **conversaciones de varios turnos**, **herramientas
personalizadas**, **hooks** e **interacción en tiempo real**.

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

### Gestión manual de la conexión

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

### Interrumpir la ejecución

```java
try (var client = ClaudeSDK.createClient()) {
    client.connect("Do a long task");

    // In another thread or after some condition
    client.interrupt();  // Sends interrupt signal
}
```

### Cambio dinámico de modelo

```java
try (var client = ClaudeSDK.createClient()) {
    client.connect("Start with default model");

    // Switch to a different model mid-conversation
    client.setModel("claude-sonnet-4-5");

    client.sendMessage("Continue with new model");
}
```

### Cambios del modo de permisos

```java
try (var client = ClaudeSDK.createClient()) {
    client.connect("Start in default mode");

    // Change permission mode during conversation
    client.setPermissionMode(PermissionMode.BYPASS_PERMISSIONS);

    client.sendMessage("Now run dangerous commands");
}
```

### Estado del servidor MCP

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

### Utilidades del SDK

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

## Herramientas personalizadas (servidores MCP del SDK)

Crea servidores MCP in-process que se ejecutan directamente dentro de tu aplicación Java.

### Usar la anotación @Tool

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

### Usar SdkMcpTool directamente

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

### Tipos de resultado de herramienta

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

### Cancelar una herramienta de larga duración

El CLI abandona una herramienta que supera su tiempo límite de MCP y envía
`notifications/cancelled`. La llamada se responde sin esperar, pero el handler sigue
ejecutándose salvo que él mismo lo compruebe: un `CompletableFuture` no se puede interrumpir
desde fuera. Recibe un `ToolCallContext` junto con los argumentos para enterarte:

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

Los handlers que solo reciben sus argumentos no se ven afectados. `context.onCancel(...)` cubre
el trabajo que no puede hacer sondeo, como una lectura bloqueante.

### Usar tu propio servidor MCP

`McpSdkServerConfig` contiene un `McpMessageHandler`, así que una aplicación que necesite partes
de MCP que el servidor integrado no ofrece —recursos, prompts, completions— puede implementar la
interfaz por su cuenta y registrarla del mismo modo.
Consulta [docs/feature-mcp-servers.md](../feature-mcp-servers.md#custom-mcp-handlers).

### Ventajas frente a servidores MCP externos

- **Sin gestión de subprocesos**: se ejecuta en la misma JVM que tu aplicación
- **Mejor rendimiento**: sin sobrecarga de IPC en las llamadas a herramientas
- **Despliegue más sencillo**: un único proceso Java en lugar de varios
- **Depuración más fácil**: todo el código se ejecuta en el mismo proceso
- **Seguridad de tipos**: llamadas directas a métodos Java

## Hooks

Los hooks son callbacks que Claude Code invoca en puntos concretos del bucle del agente.
Permiten un procesamiento determinista y realimentación automatizada.

### Eventos de hook

| Evento | Descripción |
|-------|-------------|
| `PreToolUse` | Antes de ejecutar una herramienta |
| `PostToolUse` | Después de que una herramienta termina |
| `UserPromptSubmit` | Cuando el usuario envía un prompt |
| `Stop` | Cuando la sesión se detiene |
| `SubagentStop` | Cuando un subagente se detiene |
| `PreCompact` | Antes de la compactación del contexto |

### Ejemplo: bloquear comandos peligrosos

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

### Ejemplo: registrar todos los usos de herramientas

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

## Callbacks de permisos

Controla la ejecución de herramientas con lógica de permisos personalizada.

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

### Modificar la entrada de la herramienta

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

### Actualizaciones de permisos

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

## Opciones de configuración

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

### Modos de permisos

```java
PermissionMode.DEFAULT           // CLI prompts for dangerous tools
PermissionMode.ACCEPT_EDITS      // Auto-accept file edits
PermissionMode.PLAN              // Show plans before execution
PermissionMode.BYPASS_PERMISSIONS // Allow all tools (use with caution)
```

### Configuración del razonamiento (thinking)

Controla el comportamiento del razonamiento extendido con los tipos `ThinkingConfig` (añadidos
en la v0.1.36):

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

**Nota:** el campo `thinking` tiene prioridad sobre el campo obsoleto `maxThinkingTokens`.

### Configuraciones de servidor MCP

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

### Configuración del sandbox

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

## Tipos de mensaje

El SDK usa interfaces selladas para un manejo de mensajes con seguridad de tipos y coincidencia
de patrones.

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

`Message` es una interfaz sellada, así que un `switch` exhaustivo sin `default` deja de compilar
cuando se añade un nuevo tipo de mensaje. Añade una rama `default ->` si prefieres absorber en
silencio las incorporaciones futuras.

### Origen del mensaje

En el modo de entrada por streaming, una sola conexión intercala los turnos que envías con los
turnos que la sesión inyecta por su cuenta: notificaciones de tareas en segundo plano, prompts
de tareas programadas que se dispararon, mensajes de canales MCP, mensajes retransmitidos desde
sesiones pares. El método `origin()` de `UserMessage` y `ResultMessage` los distingue:

```java
MessageOrigin origin = result.origin();
if (origin == null || origin.isHuman()) {
    // a turn this application submitted
} else if (origin.kind() == MessageOriginKind.TASK_NOTIFICATION) {
    // follow-up turn driven by a background task
}
```

`origin()` es null cuando el CLI no atribuyó el mensaje. Los prompts enviados con `query()`
llegan así, salvo que tú mismo marques `"origin": {"kind": "human"}` en el map del mensaje
transmitido: desde un host del SDK solo se acepta el tipo `human`. Un tipo más reciente de lo que
este SDK modela deja `kind()` en null, con la cadena del protocolo aún legible en `kindValue()`,
y nunca cuenta como humano.

### Bloques de contenido

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

## Manejo de errores

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

### Jerarquía de excepciones

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

`ResultException` se lanza cuando el CLI termina una ejecución fallida emitiendo un mensaje
`result` con `is_error: true` y luego sale con un código distinto de cero. En ese caso sustituye
a la escueta `ProcessException` de "exit code 1" y transporta la carga útil del resultado:
`subtype()`, `errors()`, `result()`, `apiErrorStatus()`, `terminalReason()`, `sessionId()` y el
`data()` en bruto, de modo que quien llama pueda ramificar según el *motivo* del fallo. Ten en
cuenta que una ejecución que termina por un fallo de API llega con `subtype() == "success"` y
`terminalReason() == "api_error"`, con el texto explicativo en `result()`. Los manejadores
existentes con `catch (ProcessException e)` siguen funcionando.

Normalmente la encontrarás como `QueryFailedException.getCause()` y no por sí sola: el
`query(...)` que recopila mensajes la envuelve para que no se pierdan los mensajes ya recibidos.
Solo se lanza directamente cuando falla una petición de control, como un `initialize` que el CLI
rechaza al arrancar. `ClaudeSDKClient.receiveResponse()` no la lanza en absoluto: ese iterador se
detiene en el `ResultMessage`, así que allí comprueba `ResultMessage.isError()` en su lugar.

`QueryFailedException` es específica de la familia `ClaudeSDK.query(...)` que recopila mensajes.
El CLI informa de condiciones como `error_max_turns` y `error_max_budget_usd` emitiendo un turno
completo —incluido un `ResultMessage` final con el subtipo y el coste— y saliendo *después* con
un código distinto de cero. Un consumidor de streaming (`ClaudeSDKClient.receiveMessages()` /
`receiveResponse()`) ve todos esos mensajes antes del lanzamiento; una llamada que recopila tiene
que devolver una lista o lanzar, así que lanza esta excepción y devuelve los mensajes recopilados
mediante `partialMessages()` y el accesor de conveniencia `resultMessage()`; su `getCause()` es
la `ResultException` descrita arriba. Captúrala siempre que configures `maxTurns` o
`maxBudgetUsd`: alcanzar un límite que tú mismo fijaste es un desenlace esperado, no un fallo.

## Eventos de streaming

Habilita el streaming de mensajes parciales para recibir actualizaciones en tiempo real.

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

## Checkpoints de archivos

Sigue los cambios en archivos y vuelve a estados anteriores.

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

Define agentes personalizados con capacidades concretas.

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

## Ejemplos

Consulta el módulo `examples/` para ver ejemplos completos y funcionales:

- `QuickStart.java`: uso básico
- `MultiTurnConversation.java`: conversaciones interactivas
- `ToolUsage.java`: uso de herramientas integradas
- `McpServer.java`: creación de herramientas MCP personalizadas
- `AutoSchemaGeneration.java`: generación automática de esquemas para herramientas
- `Hooks.java`: callbacks de hook (incluidos los nuevos eventos: Notification, SubagentStart, PermissionRequest)
- `PermissionCallbacks.java`: lógica de permisos personalizada
- `StreamingEvents.java`: streaming en tiempo real
- `StructuredOutputExample.java`: salida estructurada con validación por JSON Schema (simple, anidada, enum, con herramientas)
- `DynamicControlExample.java`: funciones de control dinámico (setPermissionMode, setModel, interrupt)
- `ErrorHandling.java`: manejo de excepciones
- `AdvancedFeatures.java`: checkpoints, sandbox, salida estructurada
- `ToolsConfigurationExample.java`: configuración de herramientas (array, preset, vacío)
- `MaxBudgetExample.java`: límite de presupuesto y control de costes
- `SettingSourcesExample.java`: fuentes de configuración (user, project, local)
- `StderrCallbackExample.java`: captura de la salida stderr del CLI
- `PluginsExample.java`: uso del sistema de plugins
- `AgentsExample.java`: definiciones programáticas de subagentes
- `FilesystemAgentsExample.java`: configuración de agentes basada en el sistema de archivos
- `LargeAgentsExample.java`: definiciones de agente grandes (más de 260KB) mediante la petición initialize
- `SystemPromptExample.java`: uso de un prompt de sistema personalizado
- `IncludePartialMessagesExample.java`: streaming con actualizaciones de mensajes parciales

### Ejecutar los ejemplos

El módulo de ejemplos es un módulo Maven independiente que depende del SDK. Compilar desde la
raíz del repositorio satisface esa dependencia desde el reactor; compilar `examples/` por
separado la resuelve desde Maven Central, lo que no requiere configurar repositorios ni
autenticación.

#### Opción 1: ejecutar los ejemplos desde el directorio raíz (recomendado)

Compila todos los módulos y ejecuta un ejemplo:

```bash
# Build all modules (SDK + examples)
mvn clean package -DskipTests

# Run an example using Maven exec plugin
mvn exec:java -Dexec.mainClass="examples.QuickStart" -pl examples

# Run different examples
mvn exec:java -Dexec.mainClass="examples.MultiTurnConversation" -pl examples
mvn exec:java -Dexec.mainClass="examples.McpServer" -pl examples
```

#### Opción 2: ejecutar los ejemplos desde el directorio examples

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

#### Opción 3: ejecutar los ejemplos con el SDK de desarrollo local

Para probar los ejemplos con tu versión local de desarrollo del SDK (no la publicada):

1. Instala el SDK localmente:
   ```bash
   cd sdk
   mvn clean install -DskipTests
   cd ..
   ```

2. Actualiza `examples/pom.xml` para usar la versión SNAPSHOT:
   ```xml
   <dependency>
       <groupId>in.vidyalai</groupId>
       <artifactId>claude-agent-sdk-java</artifactId>
       <version>0.1.1-SNAPSHOT</version>
   </dependency>
   ```

3. Ejecuta los ejemplos como se describe en la Opción 1 o 2.

**Nota:** también puedes fijar el módulo de ejemplos a una versión publicada del SDK poniendo el
`<version>` de la dependencia en `examples/pom.xml` a esa versión (por ejemplo, `0.2.2`). Se
resuelve desde Maven Central, así que no hace falta configurar repositorios ni autenticación.

## Seguridad entre hilos

- `ClaudeSDKClient` **no es seguro entre hilos**. Usa un client por hilo o sincroniza el acceso.
- Los métodos `ClaudeSDK.query()` crean conexiones nuevas y se pueden llamar de forma segura
  desde varios hilos.
- Los callbacks (hooks, permisos) pueden invocarse desde hilos distintos; asegúrate de que tus
  implementaciones de callback sean seguras entre hilos.

## Modelo de concurrencia: Python frente a Java

El SDK de Java usa un modelo de concurrencia radicalmente distinto al del SDK de Python.
Entender estas diferencias ayuda al portar código o al comparar ejemplos.

### SDK de Python: modelo async/await

El SDK de Python usa la sintaxis `async`/`await` de Python con asyncio o trio:

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

**Características clave de Python:**
- Palabras clave `async`/`await` para operaciones no bloqueantes
- `async for` para iterar sobre iterables asíncronos
- `async with` para gestores de contexto asíncronos
- Iterables/generadores asíncronos (`async def` con `yield`)
- Bibliotecas: asyncio, trio

### SDK de Java: modelo síncrono + CompletableFuture

El SDK de Java usa API síncronas con CompletableFuture para las operaciones asíncronas:

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

**Características clave de Java:**
- **Iteradores síncronos** (`Iterator<Message>`) en lugar de iterables asíncronos
- **Try-with-resources** (`try (...)`) en lugar de gestores de contexto asíncronos
- **CompletableFuture** para callbacks asíncronos (hooks, permisos)
- **Hilos virtuales** para E/S bloqueante eficiente en Java 21+, con respaldo en hilos de plataforma en 17-20
- **ExecutorService** para gestionar tareas en segundo plano

### Por qué los ejemplos asíncronos de Python no se traducen directamente

Algunos ejemplos del SDK de Python no tienen un equivalente directo en Java porque muestran
patrones propios del código asíncrono:

| Ejemplo de Python | Por qué no está en Java | Equivalente en Java |
|----------------|-----------------|-----------------|
| `streaming_mode_ipython.py` | Integración con el REPL asíncrono propio de IPython | Usa el REPL de Java (jshell) con las API síncronas |
| `streaming_mode_trio.py` | Biblioteca de concurrencia propia de Trio | Usa la concurrencia estándar de Java (ExecutorService, hilos virtuales) |
| `test_connect_with_async_iterable` | Patrón de generador asíncrono | `client.query(Iterator<Map>)` con un Iterator normal |
| `test_concurrent_send_receive` | Operaciones concurrentes asíncronas | Usa `Thread.startVirtualThread()` o ExecutorService |

### Operaciones concurrentes en Java

Para operaciones que necesitan concurrencia real en Java:

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

### Consideraciones de rendimiento

- **Python**: la E/S asíncrona es eficiente para operaciones limitadas por E/S, pero exige puntos explícitos de `await`
- **Java**: los hilos virtuales (Project Loom) hacen que la E/S bloqueante sea tan eficiente como la asíncrona sin cambios de sintaxis
- **Java**: las API síncronas son más sencillas de usar y depurar que el código asíncrono
- **Python**: Trio ofrece concurrencia estructurada; Java logra algo similar con try-with-resources y ExecutorService

### Guía de migración: de Python a Java

| Patrón de Python | Equivalente en Java |
|----------------|-----------------|
| `async with client:` | `try (var client = ...) {` |
| `await client.connect()` | `client.connect()` (síncrono) |
| `async for msg in client.receive():` | `for (Message msg : client.receiveResponse())` |
| `await asyncio.sleep(1)` | `Thread.sleep(1000)` |
| `asyncio.create_task()` | `Thread.startVirtualThread(() -> ...)` |
| `async def generator():` + `yield` | Implementación de `Iterator<T>` |
| `CompletableFuture.completed()` | `CompletableFuture.completedFuture()` |

**En resumen:** el SDK de Java prioriza la sencillez y usa API síncronas con hilos virtuales para
lograr concurrencia eficiente, mientras que el SDK de Python usa async/await para operaciones no
bloqueantes. Ambos consiguen una funcionalidad similar con los idiomas propios de cada lenguaje.

## Buenas prácticas

1. **Cierra siempre los clients**: usa try-with-resources o llama a `disconnect()` en un bloque finally.
2. **Maneja los errores con elegancia**: captura excepciones concretas para obtener mejores mensajes de error.
3. **Fija límites adecuados**: usa `maxTurns` y `maxBudgetUsd` para acotar la ejecución.
4. **Usa callbacks de permisos por seguridad**: no dependas solo de `permissionMode`.
5. **Prefiere los servidores MCP del SDK**: son más rápidos y más fáciles de depurar que los procesos externos.

## Documentación

- **[Análisis de paridad con el SDK de Python](../PYTHON_SDK_PARITY.md)**: comparación exhaustiva
  entre los SDK de Python y Java, con el estado de la paridad de funciones, la comparación de los
  sistemas de tipos, la cobertura de ejemplos y los detalles de implementación. (en inglés)
- **[Índice de la documentación técnica](./index.md)**: arquitectura, guías de funciones y
  referencia de la API (en este idioma).
- **[Sobre las traducciones](../TRANSLATIONS.md)**: alcance de las traducciones, política de
  sincronización y cómo contribuir. (en inglés)

## Licencia

[MIT](../../LICENSE)
