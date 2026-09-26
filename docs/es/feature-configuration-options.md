# Opciones de configuración

Guía completa para configurar el comportamiento del SDK de Claude con `ClaudeAgentOptions`.

> **Sobre esta traducción**: la documentación en inglés es la única autoritativa. Esta traducción puede estar desactualizada respecto al [original en inglés](../feature-configuration-options.md); si hay discrepancias, prevalece el inglés. Los bloques de código se mantienen idénticos al original y no se traducen.

## Contenido
- [Descripción general](#descripción-general)
- [Patrón builder](#patrón-builder)
- [Configuración de herramientas](#configuración-de-herramientas)
- [Prompt de sistema](#prompt-de-sistema)
- [Servidores MCP](#servidores-mcp)
- [Ajustes de permisos](#ajustes-de-permisos)
- [Gestión de sesiones](#gestión-de-sesiones)
- [Límites](#límites)
- [Configuración del modelo](#configuración-del-modelo)
- [Directorio de trabajo y CLI](#directorio-de-trabajo-y-cli)
- [Variables de entorno](#variables-de-entorno)
- [Callbacks](#callbacks)
- [Hooks](#hooks)
- [Funciones avanzadas](#funciones-avanzadas)
- [Ejemplos completos](#ejemplos-completos)

## Descripción general

`ClaudeAgentOptions` ofrece más de 30 opciones de configuración para controlar el comportamiento del SDK de Claude. Usa un patrón builder inmutable para una configuración con seguridad de tipos.

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .model("claude-sonnet-5")
    .maxTurns(10)
    .permissionMode(PermissionMode.BYPASS_PERMISSIONS)
    .build();
```

## Patrón builder

### Crear las opciones

```java
// Start with builder
ClaudeAgentOptions.Builder builder = ClaudeAgentOptions.builder();

// Configure
builder.model("claude-sonnet-5")
       .maxTurns(10);

// Build immutable instance
ClaudeAgentOptions options = builder.build();
```

### Opciones predeterminadas

```java
// Use defaults
ClaudeAgentOptions options = ClaudeAgentOptions.defaults();
```

### Modificar opciones existentes

```java
// Create from existing
ClaudeAgentOptions modified = options.toBuilder()
    .maxTurns(20)
    .model("claude-opus-4-6")
    .build();
```

## Configuración de herramientas

### tools()

Indica qué herramientas puede usar Claude.

```java
// Use all available tools (default)
.tools(null)

// Specify list of tool names
.tools(List.of("Read", "Write", "Bash"))

// Use preset
.tools(new ToolsPreset("code-editing"))
```

**Nombres de herramientas**:
- `Read` — leer archivos
- `Write` — escribir/crear archivos
- `Edit` — editar archivos existentes
- `Bash` — ejecutar comandos bash
- `Grep` — buscar en el contenido de archivos
- `Glob` — encontrar archivos por patrón
- `Task` — lanzar subagentes
- `WebFetch` — obtener contenido web
- `WebSearch` — buscar en la web
- Herramientas MCP: `mcp__<server>__<tool>`

### allowedTools()

Pone herramientas concretas en la lista de permitidas.

```java
.allowedTools(List.of(
    "Read",
    "Grep",
    "Glob",
    "mcp__calc__add"
))
```

### disallowedTools()

Pone herramientas concretas en la lista de bloqueadas.

```java
.disallowedTools(List.of(
    "Bash",      // Block shell access
    "Write",     // Block file writing
    "WebFetch"   // Block web access
))
```

**Prioridad**: `disallowedTools` tiene precedencia sobre `allowedTools`.

## Prompt de sistema

### systemPrompt()

Define un prompt de sistema personalizado para guiar el comportamiento de Claude.

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

| Forma | Se envía a la CLI como |
|------|--------------------|
| `String` | `--system-prompt <text>` |
| `SystemPromptCustom` | `--system-prompt <prompt>`, más `snapshot` en la petición `initialize` |
| `SystemPromptPreset` | `--append-system-prompt <append>` cuando `append` está definido; `excludeDynamicSections` y `snapshot` en la petición `initialize` |
| `SystemPromptFile` | `--system-prompt-file <path>` |
| sin definir (`null`) | `--system-prompt ""` (sin prompt de sistema) |

#### Snapshot

De forma predeterminada, Claude Code construye el prompt de sistema en la primera solicitud de una
sesión, lo registra y lo reutiliza en todas las solicitudes posteriores, incluso después de reanudar
la sesión. Por eso, un prompt personalizado modificado, o un texto `append` modificado sobre el preset,
no tiene efecto hasta que la sesión se compacta o se inicia una sesión nueva. Para reconstruir el
prompt en cada solicitud, por ejemplo mientras iteras sobre su redacción, establece `snapshot` en
`false`:

```java
.systemPrompt(SystemPromptCustom.of("You are a release bot.", false))
.systemPrompt(SystemPromptPreset.claudeCode("Be concise.").withSnapshot(false))
```

`snapshot` viaja en la petición de control `initialize` como `systemPromptSnapshot`. Se envía siempre
que esté definido, incluido `false`, y se omite cuando es `null`, de modo que se aplica el valor por
defecto de la CLI: `true`, salvo en modo bare (`--bare`), donde actúa como `false`. Solo lo llevan las
formas preset y personalizada. Requiere Claude Code CLI 2.1.257 o posterior; antes de la 2.1.265, una
sesión con un prompt `append` o personalizado solo lo registraba cuando `snapshot` era `true`.

## Servidores MCP

### mcpServers()

Configura servidores Model Context Protocol para herramientas personalizadas.

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

Un mapa se serializa como `{"mcpServers": {...}}` antes de llegar a `--mcp-config`; una ruta o una
cadena JSON se pasan sin tocar, así que una cadena JSON debe usar esa misma clave de nivel superior
`mcpServers`.

## Ajustes de permisos

### permissionMode()

Controla cómo se gestionan los permisos de las herramientas.

```java
.permissionMode(PermissionMode.BYPASS_PERMISSIONS)
```

**Modos**:
- `DEFAULT` (el valor por defecto de la CLI) — comportamiento de permisos estándar
- `ACCEPT_EDITS` — acepta las ediciones de archivos automáticamente, pregunta en el resto
- `PLAN` — modo de planificación; no se ejecuta ninguna herramienta
- `BYPASS_PERMISSIONS` — omite por completo las comprobaciones de permisos
- `DONT_ASK` — no pregunta; deniega todo lo que no esté aprobado de antemano por reglas allow
- `AUTO` — un clasificador basado en un modelo aprueba o deniega cada llamada a herramienta

### permissionPromptToolName()

Indica la herramienta usada para las peticiones de permiso (avanzado, normalmente se establece de forma automática).

```java
.permissionPromptToolName("stdio")
```

## Gestión de sesiones

### continueConversation()

Continúa la conversación anterior.

```java
.continueConversation(true)  // Continue from last session
.continueConversation(false) // Start fresh (default)
```

### resume()

Reanuda una sesión concreta por su ID.

```java
.resume("session-12345")
```

### sessionId()

Indica un ID de sesión para la nueva sesión.

```java
.sessionId("my-custom-session-id")
```

### forkSession()

Bifurca la sesión reanudada en una sesión nueva (mantiene el contexto, ID nuevo).

```java
.resume("session-12345")
.forkSession(true)
```

### sessionStore()

Replica las transcripciones de la sesión en un almacén externo (S3, Postgres, Redis, backend propio). Al establecerlo, el SDK añade `--session-mirror` a la invocación de la CLI y reenvía cada línea de la transcripción a `store.appendAsync(...)`. Reanudar con `sessionStore` materializa el contenido del almacén en un `CLAUDE_CONFIG_DIR` temporal para que la CLI retome la conversación localmente. Consulta la [guía del Session Store](./feature-session-store.md) para la función completa.

```java
import in.vidyalai.claude.sdk.types.session.InMemorySessionStore;

InMemorySessionStore store = new InMemorySessionStore();

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .sessionStore(store)
    .build();
```

**Comprobaciones de validación** (rechazadas con `IllegalArgumentException` antes de lanzar el subproceso):
- `continueConversation + sessionStore` requiere `store.implementsListSessions()`.
- `sessionStore + enableFileCheckpointing` se rechaza: los checkpoints solo existen en disco local.

### sessionStoreFlush()

Controla cuándo se vuelcan al `sessionStore` configurado las entradas replicadas de la transcripción. El valor predeterminado es `SessionStoreFlushMode.BATCHED` (un volcado por turno o al desbordarse el búfer). Usa `SessionStoreFlushMode.EAGER` para programar un volcado en segundo plano tras cada trama y conseguir una entrega casi en tiempo real: los añadidos siguen serializados en orden de encolado, pero un adaptador lento no bloquea el bucle de lectura. Se ignora si `sessionStore` no está definido.

```java
import in.vidyalai.claude.sdk.types.session.SessionStoreFlushMode;

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .sessionStore(store)
    .sessionStoreFlush(SessionStoreFlushMode.EAGER)
    .build();
```

Consulta [Modo de volcado (batched frente a eager)](./feature-session-store.md#modo-de-volcado-batched-frente-a-eager) para ver las contrapartidas.

### loadTimeoutMs()

Tiempo límite por llamada a `store.loadAsync()` y `listSubkeysAsync()` durante la materialización de la reanudación, en milisegundos. El valor predeterminado es `60_000`. Si un adaptador no responde dentro de esa ventana, la consulta falla con un error claro en lugar de dejar colgado el iterador.

```java
.loadTimeoutMs(30_000)  // 30 seconds
```

## Límites

### maxTurns()

Número máximo de turnos de la conversación.

```java
.maxTurns(10)  // Limit to 10 turns
```

**Casos de uso**:
- Control del presupuesto
- Evitar conversaciones descontroladas
- Consultas rápidas: `.maxTurns(1)`

### maxBudgetUsd()

Coste máximo en dólares estadounidenses.

```java
.maxBudgetUsd(1.0)  // Limit to $1.00
```

Detiene la ejecución al superar el presupuesto.

### taskBudget()

Presupuesto de tarea en tokens, del lado de la API. Al establecerlo, el modelo conoce el presupuesto de tokens que le queda.

```java
.taskBudget(new TaskBudget(100000))  // 100K token budget
```

### maxBufferSize()

Bytes máximos para almacenar en búfer la salida estándar de la CLI.

```java
.maxBufferSize(10 * 1024 * 1024)  // 10MB
```

Predeterminado: 100MB. Auméntalo si la salida es grande.

### thinking()

**NUEVO**: controla el comportamiento del razonamiento extendido con una configuración detallada.

```java
// Adaptive thinking (32K token default)
.thinking(new ThinkingConfigAdaptive())

// Fixed token budget
.thinking(new ThinkingConfigEnabled(10000))

// Disable thinking
.thinking(new ThinkingConfigDisabled())
```

**Tipos**:
- `ThinkingConfigAdaptive` — razonamiento adaptativo, 32.000 tokens por defecto
- `ThinkingConfigEnabled(int budgetTokens)` — presupuesto fijo de tokens (debe ser > 0)
- `ThinkingConfigDisabled` — sin tokens de razonamiento

**Nota**: esta opción tiene precedencia sobre el obsoleto `maxThinkingTokens()`.

Consulta [Configuración del razonamiento extendido](./feature-thinking-config.md) para la guía completa.

### effort()

Establece el nivel de profundidad/intensidad del razonamiento. Hay dos sobrecargas: puedes pasar
la cadena en bruto o el enum [`EffortLevel`](#enum-effortlevel), con seguridad de tipos.

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

`"xhigh"` es específico de Opus 4.7 y cae a `"high"` en otros modelos.

Funciona junto con `thinking()` para controlar la profundidad del razonamiento.

Consulta [Configuración del razonamiento extendido](./feature-thinking-config.md) para ver ejemplos.

### Enum EffortLevel

Enum público en `in.vidyalai.claude.sdk.types.config.EffortLevel`, que refleja el alias de tipo
`EffortLevel` de Python. Se expone para que los wrappers de SDK derivados puedan referenciar el
tipo directamente.

| Constante | Valor en el protocolo | Descripción |
|----------|------------|-------------|
| `EffortLevel.LOW` | `"low"` | Razonamiento mínimo, respuestas más rápidas |
| `EffortLevel.MEDIUM` | `"medium"` | Razonamiento moderado |
| `EffortLevel.HIGH` | `"high"` | Razonamiento profundo (predeterminado) |
| `EffortLevel.XHIGH` | `"xhigh"` | Razonamiento extendido (solo Opus 4.7; cae a `HIGH`) |
| `EffortLevel.MAX` | `"max"` | Máximo esfuerzo |

Utilidades:
- `EffortLevel.getValue()` devuelve el valor en minúsculas usado en el protocolo (también es el serializador `@JsonValue`).
- `EffortLevel.fromValue(String)` convierte un valor del protocolo de vuelta en una constante del enum; lanza `IllegalArgumentException` con valores desconocidos.

```java
EffortLevel level = EffortLevel.fromValue("xhigh");
String wire = level.getValue(); // "xhigh"
```

### maxThinkingTokens()

**OBSOLETO**: usa `thinking()` en su lugar: adaptativo, habilitado con un presupuesto de tokens, o
deshabilitado.

Tokens máximos para los bloques de razonamiento. En los modelos más recientes este valor se trata como
encendido/apagado (0 = deshabilitado, cualquier otro valor = adaptativo).

```java
.maxThinkingTokens(10000)  // Deprecated - use thinking() instead
```

### maxMsgQSize()

Tamaño máximo de la cola de mensajes.

```java
.maxMsgQSize(1000)
```

Auméntalo en escenarios de alto rendimiento.

## Configuración del modelo

### model()

Establece el modelo de IA.

```java
.model("claude-sonnet-5")
```

**Modelos disponibles**:
- `claude-opus-4-6` — el más capaz, caro
- `claude-sonnet-4-5` — equilibrado (predeterminado)
- `claude-haiku-4-5` — rápido y económico

### fallbackModel()

Modelo alternativo si el principal no está disponible.

```java
.model("claude-opus-4-6")
.fallbackModel("claude-sonnet-4-5")
```

### betas()

Habilita funciones beta.

```java
.betas(List.of(
    SdkBeta.PROMPT_CACHING,
    SdkBeta.EXTENDED_THINKING
))
```

Consulta [Anthropic API Beta Headers](https://docs.anthropic.com/en/api/beta-headers).

## Directorio de trabajo y CLI

### cwd()

Establece el directorio de trabajo para las operaciones de archivos.

```java
.cwd(Path.of("/path/to/project"))
```

**Importante**: defínelo siempre en operaciones de archivos para que las rutas sean correctas.

### cliPath()

Ruta personalizada a la CLI de Claude Code.

```java
.cliPath(Path.of("/custom/path/to/claude"))
```

Predeterminado: busca en el PATH del sistema.

**Windows:** se rechaza una ruta `.bat`/`.cmd` (el shim `claude.cmd` de npm): el sistema operativo la ejecutaría a través de `cmd.exe`, que vuelve a analizar la línea de comandos. Apunta a un `claude.exe` o consulta `allowUnsafeWindowsBatchCli()` más abajo.

### allowUnsafeWindowsBatchCli()

Exime del rechazo de scripts batch en Windows a despliegues que no pueden migrar a un `claude.exe` nativo. Predeterminado: `false`.

```java
.cliPath(Path.of("C:\\Users\\Administrator\\AppData\\Roaming\\npm\\claude.cmd"))
.allowUnsafeWindowsBatchCli(true)
```

Esto **no** es un simple bypass: una exención a secas devolvería íntegro el agujero de reinterpretación de `cmd.exe`. Al habilitarlo, además:

1. **Exige `-Djdk.lang.Process.allowAmbiguousCommands=false`** en la JVM. Esa propiedad vale `true` por defecto y, en ese caso, el JDK solo entrecomilla los espacios al lanzar un batch; con `false` entrecomilla `" < > & | ^` y rechaza los argumentos que contengan comillas. `connect()` lanza `CLIConnectionException` si falta la bandera.
2. **Rechaza `& | < > ^ % ! "` y CR/LF en todos los argumentos de la CLI**, lanzando `IllegalArgumentException` con el nombre de la opción problemática. `%` y `!` no están en el conjunto de escape del JDK y entrecomillar no evita la expansión de `%VAR%`.
3. **Registra un `WARNING`** que nombra el riesgo aceptado.

**Riesgo residual:** cmd.exe sigue expandiendo `%VAR%` desde el entorno. Úsalo solo donde la ruta de la CLI y todos los valores de los argumentos estén bajo control del administrador. Se ignora en POSIX. Consulta [Capa de transporte → habilitación del CLI en batch](./feature-transport-layer.md#windows-habilitación-del-cli-en-batch-0122).

### settings()

Ruta a un archivo JSON de ajustes adicional, o una cadena JSON en línea.

```java
.settings("/path/to/settings.json")
.settings("{\"permissions\": {\"allow\": [\"Read\"]}}")
```

Sin `sandbox()`, el valor se pasa tal cual a `--settings`. Si también se define `sandbox()`, ambos se
fusionan en una única cadena JSON: un valor que empieza por `{` y termina en `}` se analiza como JSON,
y cualquier otro se lee como ruta de archivo (un archivo inexistente o ilegible se registra en el log y
solo se pasan los ajustes del sandbox). Se cargan en la capa de «flag settings» de la CLI, la de mayor
prioridad entre los ajustes controlados por el usuario.

### addDirs()

Directorios adicionales que añadir al contexto.

```java
.addDirs(List.of(
    Path.of("/path/to/lib"),
    Path.of("/path/to/docs")
))
```

## Variables de entorno

### env()

Establece variables de entorno para el proceso de la CLI.

```java
.env(Map.of(
    "API_KEY", "secret-key",
    "DEBUG", "true",
    "NODE_ENV", "production"
))
```

El mapa se fusiona sobre el entorno del proceso padre: sus entradas sobrescriben los valores heredados,
y `CLAUDECODE` se elimina del conjunto heredado. El transporte también establece:

| Variable | Cuándo | Sobrescribible aquí |
|----------|------|------------------|
| `CLAUDE_CODE_ENTRYPOINT=sdk-java` | siempre | sí |
| `CLAUDE_AGENT_SDK_VERSION` | siempre | no |
| `CLAUDE_CODE_SDK_READS_SESSION_STATE=1` | salvo que este mapa o el entorno heredado ya la nombren (con cualquier combinación de mayúsculas y minúsculas) | sí |
| `CLAUDE_CODE_ENABLE_SDK_FILE_CHECKPOINTING=true` | `enableFileCheckpointing(true)` | — |
| `PWD` | `cwd(...)` está definido | — |
| `TRACEPARENT` / `TRACESTATE` | hay un span de OpenTelemetry activo (consulta [Contexto de trazas](./feature-trace-context.md)) | sí |

Dos variables ajustan cuánto tiempo mantiene abierto el stdin una consulta de un solo uso para los hooks
y las llamadas MCP del SDK (consulta [Arquitectura → Ciclo de vida del stdin](./architecture.md#ciclo-de-vida-del-stdin-y-el-final-de-una-ejecución)):
`CLAUDE_CODE_PRINT_BG_WAIT_CEILING_MS` acota la espera entre turnos (por defecto `600000`, `0` para no
poner límite), y `CLAUDE_CODE_EMIT_SESSION_STATE_EVENTS=1` activa los marcos `session_state_changed` de
la CLI. Define `CLAUDE_AGENT_SDK_CLIENT_APP` (por ejemplo, `"my-app/1.0.0"`) para identificar tu
aplicación en la cabecera User-Agent.

### extraArgs()

Pasa banderas arbitrarias a la CLI.

```java
.extraArgs(Map.of(
    "replay-user-messages", "",   // flag with no value: --replay-user-messages
    "debug", "api"                // flag with a value: --debug api
))
```

Las claves son nombres de bandera **sin** el `--` inicial; el transporte lo añade. Un valor `null` o en
blanco emite una bandera sin valor. Un valor que empieza por `-` se envía con la forma `--flag=value`
para que no pueda interpretarse como una bandera aparte; cualquier otro valor se envía como dos tokens.

## Callbacks

### canUseTool()

Callback de permisos personalizado para herramientas. Mutuamente excluyente con
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

**Firma**:
```java
BiFunction<String, Object, ToolPermissionContext, CompletableFuture<PermissionResult>>
```

### stderrCallback()

Recibe la salida de error de la CLI. Se invoca una vez por línea de stderr conforme la CLI la
emite (solo se canaliza cuando este callback está definido).

**Aislamiento de excepciones:** si tu callback lanza, la excepción se captura, se registra en
`FINE` (`java.util.logging`) y la lectura de stderr continúa. Un callback con errores ya no
puede terminar en silencio el bucle de lectura y descartar todas las líneas de stderr
posteriores durante el resto de la sesión.

```java
.stderrCallback(line -> {
    System.err.println("CLI stderr: " + line);
})
```

## Hooks

### hooks()

Registra callbacks de hook para eventos del ciclo de vida.

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

**Eventos disponibles**:
- `PRE_TOOL_USE` — antes de ejecutar la herramienta
- `POST_TOOL_USE` — tras el éxito de la herramienta
- `POST_TOOL_USE_FAILURE` — tras el fallo de la herramienta
- `USER_PROMPT_SUBMIT` — el usuario envía un mensaje
- `STOP` — la sesión se detiene
- `SUBAGENT_START` — el subagente arranca
- `SUBAGENT_STOP` — el subagente se detiene
- `PRE_COMPACT` — antes de la compactación de mensajes
- `NOTIFICATION` — eventos de notificación
- `PERMISSION_REQUEST` — permiso solicitado

## Funciones avanzadas

### user()

Establece la identidad del usuario para el seguimiento.

```java
.user("user-12345")
```

### includePartialMessages()

Habilita el streaming de mensajes parciales.

```java
.includePartialMessages(true)
```

Recibe mensajes `StreamEvent` con deltas conforme se genera el contenido, uno por cada evento del
stream de la API.

### verbatimPrompts()

Entrega todos los prompts a Claude tal como están escritos.

```java
.verbatimPrompts(true)
```

Claude Code normalmente expande un `@/absolute/path` en el texto del prompt al contenido de ese
archivo, fuera del directorio de trabajo y sin llamada a herramienta, y despacha un `/command` inicial
como comando slash. Eso encaja con texto que ha tecleado un usuario; no encaja con texto que tu
aplicación ha montado a partir de otras fuentes (turnos anteriores, resultados de herramientas,
contenido de terceros). Con esta opción activada, todos los mensajes de usuario que escribe el SDK se
marcan con `client_composed: true`, y Claude Code los entrega exactamente como se dieron. Eso abarca
los prompts de cadena y en streaming de `ClaudeSDK.query`, y `ClaudeSDKClient.connect(String)`,
`query(String)` y `query(Iterator)`.

- La marca se pone sobre una copia; tus mapas de mensajes nunca se modifican.
- Mientras la opción está activada, sobrescribe cualquier valor `client_composed` de un mensaje en
  streaming. Para controlarlo por turno, déjala desactivada y pon `"client_composed": true` en los
  mensajes en streaming concretos; el SDK lo transmite sin tocarlo.
- En las versiones actuales de Claude Code, un turno literal también se salta la fase de adjuntos del
  inicio del turno: las menciones MCP `@server:resource` no se expanden, y el prompt se envía sin el
  contexto que normalmente se adjunta con él (archivos `CLAUDE.md` anidados y de reglas, listados de
  skills y herramientas, otros recordatorios por turno). La mayor parte de ese contexto llega, en su
  lugar, después de la primera llamada a herramienta del turno.
- Requiere Claude Code 2.1.248 o posterior. Las versiones anteriores ignoran el campo, y el transporte
  registra un `WARNING` al conectar cuando detecta una.

Consulta `examples/VerbatimPromptsExample.java`.

### forwardSubagentText()

Reenvía los bloques de texto y de razonamiento de un subagente al flujo de mensajes.

```java
.forwardSubagentText(true)
```

Por defecto, al flujo del padre solo llegan los bloques `tool_use` / `tool_result` del
subagente, como objetos `AssistantMessage` / `UserMessage` cuyo `parentToolUseId` es el id del
bloque `tool_use` del Agent que lo creó: suficiente para indicar progreso, pero no para mostrar
lo que dijo el subagente. Con esta opción, sus bloques de texto y de razonamiento llegan de la
misma forma.

Se envía a la CLI en la petición de control `initialize` en lugar de como bandera, y solo
cuando está habilitado; las CLI antiguas lo ignoran. Consulta
[Agents → Observar la salida de un subagente](./feature-agents.md#observar-la-salida-de-un-subagente).

### agents()

Define configuraciones de agentes personalizados.

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

Controla qué archivos de ajustes se cargan.

```java
.settingSources(List.of(
    SettingSource.USER,     // ~/.claude/
    SettingSource.PROJECT,  // .claude/ in project
    SettingSource.LOCAL     // .claude.local/
))
```

**Una lista vacía desactiva todas las fuentes.** Pasa `List.of()` para enviar `--setting-sources=` (vacío) a la CLI, lo que suprime todas las fuentes de ajustes del sistema de archivos. Cuando la opción se **omite por completo** (lo predeterminado), no se añade ninguna bandera `--setting-sources` y la CLI aplica sus propios valores por defecto.

### skills() / skillsAll()

Lista de skills permitidas en el nivel principal de la sesión. El SDK inyecta automáticamente las entradas `Skill(name)` correspondientes en `allowedTools` y fija `settingSources` en user/project por defecto, de modo que la CLI descubra las skills instaladas sin configuración adicional. La lista también se propaga mediante la petición de control initialize, para que una CLI compatible pueda filtrar qué skills se cargan en el prompt de sistema (las CLI antiguas ignoran el campo).

```java
// Enable every discovered skill
.skillsAll()

// Enable only the listed skills
.skills(List.of("commit", "review"))

// Suppress every skill from the listing
.skills(List.of())
```

Tres modos:

| Llamada del builder | Inyección en `allowedTools` | Valor por defecto de `settingSources` | Campo en initialize |
|---|---|---|---|
| _omitido_ (null) | ninguna | ninguno | omitido |
| `.skillsAll()` | añade `Skill` sin especificador | `[user, project]` | omitido |
| `.skills(List.of("a", "b"))` | añade `Skill(a)`, `Skill(b)` | `[user, project]` | `["a", "b"]` |
| `.skills(List.of())` | ninguna | `[user, project]` | `[]` |

Detalles de comportamiento:
- **Inyección idempotente** — si `allowedTools` ya contiene `Skill` o `Skill(name)`, el SDK no lo duplica.
- **No muta** — aplicar los valores por defecto de skills construye una lista nueva; el `ClaudeAgentOptions` original nunca se modifica.
- **Un `settingSources` explícito gana** — si defines `.settingSources(...)` junto a `.skills(...)`, se conserva tu valor.
- **Los nombres se validan** (0.1.22) — cada nombre de la lista debe ser el `name` del SKILL.md de la skill / el nombre del directorio, o `plugin:skill`. Los delimitadores de regla (paréntesis, comas), los caracteres de control, los comodines (`"*"`, `"pdf:*"`), una `/` inicial y los espacios alrededor lanzan `IllegalArgumentException` en `connect()`. **Cambio incompatible:** `skills(List.of("*"))` y `skills(List.of("plugin:*"))` antes construían una regla con comodín y ahora lanzan excepción; usa `.skillsAll()`. Consulta [Skills → Validación de nombres](./feature-skills.md#validación-de-nombres-0122).
- **Es un filtro de contexto, no un sandbox** — las skills no listadas quedan ocultas en el listado del modelo y no pueden invocarse con la herramienta `Skill`, pero sus archivos siguen en disco; una sesión con `Read`/`Bash` aún puede acceder directamente a `.claude/skills/**`.

### sandbox()

Configura el sandbox de los comandos bash.

Cuando está habilitado, los comandos se ejecutan en un entorno aislado que restringe el acceso al
sistema de archivos y a la red. Las restricciones de sistema de archivos y de red a nivel de
herramienta se siguen configurando con reglas de permisos (`Read`/`Edit` para el sistema de archivos,
`WebFetch` para la red); el ajuste `network` de más abajo configura el aislamiento de red propio del
sandbox para los comandos bash aislados. Definir un sandbox también cambia cómo se pasa `settings()`;
consulta [settings()](#settings).

```java
// Minimal: just enable sandboxing.
.sandbox(new SandboxSettings(true))
```

Para un control más fino, proporciona el record completo:

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

- `allowedDomains` — dominios que pueden alcanzar los procesos del sandbox.
- `deniedDomains` — bloqueos que siempre prevalecen; la denegación gana a la autorización.
- `allowManagedDomainsOnly` — cuando vale `true` en los ajustes gestionados, solo se respetan los `allowedDomains` de esos ajustes.
- `allowMachLookup` — nombres de servicios XPC/Mach, solo en macOS; admite comodín final.
- `allowUnixSockets`, `allowAllUnixSockets`, `allowLocalBinding`, `httpProxyPort`, `socksProxyPort` — ya existentes.

Se conserva un constructor retrocompatible de 5 argumentos `(allowUnixSockets, allowAllUnixSockets, allowLocalBinding, httpProxyPort, socksProxyPort)` para quien no necesite la lista de dominios ni los campos de Mach lookup: esos quedan en `null`.

### plugins()

Carga plugins de Claude Code desde directorios locales.

```java
.plugins(List.of(
    ClaudeAgentOptions.SdkPluginConfig.local("/path/to/my-plugin")
))
```

Cada plugin `local` se convierte en `--plugin-dir <path>`. Consulta [Sistema de plugins](./feature-plugin-system.md).

### outputFormat()

Formato de salida estructurada (estilo Messages API).

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

### enableFileCheckpointing()

Habilita los checkpoints de archivos para poder revertir.

```java
.enableFileCheckpointing(true)
.extraArgs(Map.of("replay-user-messages", ""))  // so UserMessage carries a uuid
```

Establece `CLAUDE_CODE_ENABLE_SDK_FILE_CHECKPOINTING=true` en el proceso de la CLI y permite usar
`ClaudeSDKClient.rewindFiles(userMessageId)`. Reproducir los mensajes de usuario es lo que te da el
`uuid` al que volver. No se puede combinar con `sessionStore()`.

## Ejemplos completos

### Ejemplo 1: análisis de código de solo lectura

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

### Ejemplo 2: desarrollo interactivo

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

### Ejemplo 3: procesamiento por lotes con presupuesto ajustado

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

### Ejemplo 4: herramientas personalizadas con hooks

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

### Ejemplo 5: reanudar sesiones

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

### Ejemplo 6: streaming con callback de permisos

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

## Buenas prácticas

### 1. Define siempre el directorio de trabajo en operaciones de archivos

```java
// ✅ Good
.cwd(Path.of("/project/root"))

// ❌ Bad: Undefined behavior
// No cwd set, files relative to CLI process directory
```

### 2. Usa modelos adecuados

```java
// ✅ Good: Match model to task
.model("claude-haiku-4-5")  // Simple tasks
.model("claude-sonnet-5") // Balanced
.model("claude-opus-4-6")   // Complex reasoning

// ❌ Bad: Always using most expensive
.model("claude-opus-4-6")  // For everything!
```

### 3. Establece límites de presupuesto

```java
// ✅ Good: Protect against unexpected costs
.maxBudgetUsd(1.0)
.maxTurns(10)

// ❌ Bad: No limits
// Could get expensive!
```

### 4. Configura las herramientas de forma adecuada

```java
// ✅ Good: Explicit tool control
.allowedTools(List.of("Read", "Grep"))
.disallowedTools(List.of("Bash"))

// ❌ Bad: All tools allowed by default
// Potential security risk
```

### 5. Usa prompts de sistema

```java
// ✅ Good: Guide behavior
.systemPrompt("You are a code reviewer. Focus on security.")

// ❌ Bad: No guidance
// Claude may not understand context
```

### 6. Habilita los checkpoints en operaciones de archivos

```java
// ✅ Good: Enable for safety
.enableFileCheckpointing(true)
.extraArgs(Map.of("replay-user-messages", ""))

// Allows rewinding if mistakes: pass the uuid of a replayed UserMessage
client.rewindFiles(userMessageUuid);
```

### 7. Trata los datos sensibles con cuidado

```java
// ✅ Good: Don't pass secrets in env
.env(Map.of("CONFIG_PATH", "/path/to/config"))

// ❌ Bad: Secrets in environment
.env(Map.of("API_KEY", "secret-123"))  // Logged!
```

## Véase también

- [Consultas simples](./feature-simple-queries.md) — usar opciones en las consultas
- [Conversaciones interactivas](./feature-interactive-conversations.md) — usar opciones con el cliente
- [Servidores MCP](./feature-mcp-servers.md) — configurar servidores MCP
- [Hooks](./feature-hooks.md) — configuración de hooks
- [Permisos](./feature-permissions.md) — el sistema de permisos
