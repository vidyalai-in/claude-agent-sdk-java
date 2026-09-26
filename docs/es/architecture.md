# Visión general de la arquitectura

Este documento ofrece un panorama completo de la arquitectura, los patrones de diseño y la estructura
interna del Claude Agent SDK for Java.

> **Sobre esta traducción**: la documentación en inglés es la única autoritativa. Esta traducción puede estar desactualizada respecto al [original en inglés](../architecture.md); si hay discrepancias, prevalece el inglés. Los bloques de código y los diagramas se mantienen idénticos al original y no se han traducido.

## Índice
- [Arquitectura de alto nivel](#arquitectura-de-alto-nivel)
- [Componentes principales](#componentes-principales)
- [Patrones de diseño](#patrones-de-diseño)
- [Flujo de datos](#flujo-de-datos)
- [Modelo de concurrencia](#modelo-de-concurrencia)
- [Sistema de tipos](#sistema-de-tipos)
- [Dependencias](#dependencias)

## Arquitectura de alto nivel

El SDK sigue una arquitectura por capas con una separación clara de responsabilidades:

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

## Componentes principales

### 1. Capa de API pública

#### ClaudeSDK (fachada)
- **Propósito**: fachada estática para consultas sencillas y sin estado
- **Caso de uso**: preguntas puntuales, procesamiento por lotes, operaciones de «dispara y olvida»
- **Métodos clave**:
  - `query(String prompt)`: consulta sencilla con los valores por defecto
  - `query(String prompt, ClaudeAgentOptions options)`: consulta con opciones propias
  - `query(Iterator<Map> stream, ClaudeAgentOptions options)`: consulta por streaming
  - `queryForText()` / `queryForResult()`: métodos de conveniencia
  - `createClient()`: método de fábrica de ClaudeSDKClient
  - `createSdkMcpServer()`: fábrica de servidores MCP

**Patrón de diseño**: Facade + Factory

#### ClaudeSDKClient
- **Propósito**: client interactivo y con estado para conversaciones de varios turnos
- **Caso de uso**: interfaces de chat, interacciones tipo REPL, sesiones de larga duración
- **Métodos clave**:
  - `connect()`: establece la conexión
  - `sendMessage()` / `query()`: envía mensajes
  - `receiveMessages()` / `receiveResponse()`: recibe mensajes
  - Métodos de control: `interrupt()`, `setModel()`, `setPermissionMode()`, etc.
- **Seguridad entre hilos**: parcialmente segura, con garantías documentadas
- **Gestión de recursos**: implementa AutoCloseable para una limpieza correcta

**Patrón de diseño**: Builder + gestión de recursos (try-with-resources)

### 2. Capa de configuración

#### ClaudeAgentOptions
- **Propósito**: objeto de configuración inmutable que usa el patrón builder
- **Características**:
  - Más de 30 opciones de configuración
  - API con seguridad de tipos gracias a enums e interfaces selladas
  - Builder fluido, con `toBuilder()` para modificar
- **Áreas principales de configuración**:
  - Herramientas: `tools()`, `allowedTools()`, `disallowedTools()`
  - Permisos: `permissionMode()`, `canUseTool()`
  - Sesiones: `continueConversation()`, `resume()`, `forkSession()`, `sessionStore()`, `loadTimeoutMs()`
  - Límites: `maxTurns()`, `maxBudgetUsd()`, `maxThinkingTokens()`
  - Modelo: `model()`, `fallbackModel()`, `betas()`
  - Entorno: `cwd()`, `env()`, `cliPath()`
  - Hooks: `hooks()`
  - MCP: `mcpServers()`
  - Agentes: `agents()` (se envían en la petición initialize por stdin, sin límite de tamaño)
  - Prompt de sistema: `systemPrompt()`: cadena, `SystemPromptPreset`, `SystemPromptCustom` o `SystemPromptFile`
  - Avanzado: `sandbox()`, `outputFormat()`, `enableFileCheckpointing()`, `forwardSubagentText()`, `verbatimPrompts()`

**Patrón de diseño**: Builder + objeto inmutable

### 3. Capa de protocolo y control

#### QueryHandler
- **Propósito**: gestiona el protocolo de control bidireccional sobre el Transport
- **Responsabilidades**:
  - Enrutado de peticiones y respuestas de control
  - Callbacks de hook
  - Callbacks de permisos de herramientas
  - Streaming de mensajes
  - Handshake de inicialización (incluye hooks, definiciones de agente, `excludeDynamicSections`, `systemPromptSnapshot`, la lista de skills permitidas y `forwardSubagentText`)
  - **Marcado de prompts**: con `verbatimPrompts`, todos los mensajes de usuario que escribe `streamInput()` reciben `client_composed: true` (`stampUserMessage`, que copia en lugar de mutar); `ClaudeSDKClient` marca sus propias escrituras del mismo modo
  - **Seguimiento del final de la ejecución**: decide cuándo puede cerrarse el stdin a partir de los marcos `session_state_changed` del CLI, el libro de tareas en vuelo y un límite entre turnos, y descarta los marcos de estado `sdk_host_only` que pidió el SDK (consulta [Ciclo de vida del stdin](#ciclo-de-vida-del-stdin-y-el-final-de-una-ejecución))
  - Gestión del ciclo de vida de los servidores MCP
  - **Sustitución por un error accionable**: sigue la carga útil del resultado de error más reciente
    mientras lee el flujo; cuando una `ProcessException` viene después de un resultado con
    `is_error=true`, se sustituye por una `ResultException` que lleva esa carga útil y el mensaje
    `"Claude Code returned an error result: <text>"` (construido a partir del array `errors` del
    resultado, luego del texto `result`, luego de un `subtype` distinto de `success`, y por último del
    estado de error de la API) en lugar del genérico `"Command failed with exit code N"`. Se reinicia
    ante cualquier tráfico que no sea un resultado ni `session_state_changed`. El objeto de excepción
    viaja en el marco sintético `{"type":"error"}`, así que el iterador del consumidor lo relanza con
    su tipo y su carga útil intactos.
- **Seguridad entre hilos**: totalmente segura, con operaciones atómicas y sincronización
- **Características clave**:
  - Protocolo de control asíncrono con CompletableFuture
  - Generación y seguimiento de IDs de petición
  - Cola de mensajes de tamaño configurable
  - Hilo lector en segundo plano (virtual en Java 21+)
  - Ejecutor de control para los callbacks asíncronos

**Patrón de diseño**: petición/respuesta asíncrona + Observer (para los hooks)

#### MessageParser
- **Propósito**: analizar los mensajes JSON del CLI y convertirlos en objetos Message tipados
- **Características**:
  - Análisis JSON basado en Jackson
  - Soporte de todos los tipos de mensaje (user, assistant, system, result, stream_event)
  - Análisis de bloques de contenido (text, thinking, tool_use, tool_result)
  - Manejo de errores y validación

**Patrón de diseño**: Parser + Factory

### 4. Capa de transporte

#### Interfaz Transport
- **Propósito**: capa de E/S abstracta para comunicarse con Claude Code
- **Implementación por defecto**: SubprocessCLITransport
- **Implementaciones propias**: permiten conexiones remotas a Claude Code
- **Métodos clave**:
  - `connect()`: establece la conexión
  - `write(String data)`: envía datos
  - `readMessages()`: recibe mensajes como iterador
  - `endInput()`: cierra el flujo de entrada
  - `isReady()`: comprueba el estado de la conexión
  - `close()`: libera recursos

**Patrón de diseño**: Strategy + Template Method

#### SubprocessCLITransport
- **Propósito**: transporte por defecto que usa un subproceso para el CLI de Claude Code
- **Características**:
  - Gestiona el ciclo de vida del subproceso del CLI
  - Comunicación por stdin/stdout
  - Lectura con búfer y límites configurables
  - Soporte de callback de stderr con aislamiento de excepciones por línea (un callback de usuario que
    lanza ya no mata el bucle de lectura)
  - Limpieza automática del proceso
  - **Shutdown hook de la JVM**: un `ConcurrentHashMap.newKeySet()` estático registra cada `Process`
    creado; un `Runtime.addShutdownHook` registrado al inicializar la clase llama a `destroy()` en cada
    hijo vivo, para que no se filtren subprocesos `claude` sueltos cuando la JVM padre sale antes de
    `close()`. Refleja el manejador `atexit` del SDK de Python.
- **Detalles de implementación**:
  - Usa ProcessBuilder para gestionar el subproceso
  - Hilo dedicado para leer el stdout (virtual en Java 21+)
  - BufferedReader con análisis por líneas
  - Jackson para serializar/deserializar JSON

**Patrón de diseño**: gestión de subprocesos + E/S con búfer

### 5. Soporte de MCP (Model Context Protocol)

#### SdkMcpServer
- **Propósito**: servidor MCP in-process para herramientas propias
- **Ventajas frente a servidores externos**:
  - Sin sobrecarga de IPC (mismo proceso)
  - Despliegue más sencillo
  - Depuración más fácil
  - Acceso directo al estado de la aplicación
- **Características**:
  - Registro y ejecución de herramientas
  - Generación automática de esquemas a partir de anotaciones @Tool
  - Ejecución asíncrona basada en CompletableFuture
  - Información del servidor y negociación de versión en el `initialize` entre `2025-06-18` /
    `2024-11-05`
  - Mensajes del protocolo MCP (`initialize`, `ping`, `tools/list`, `tools/call`)
  - Validación de argumentos contra el `inputSchema` de cada herramienta, compilado una vez al
    construir el servidor
  - Cancelación: `notifications/cancelled` resuelve la llamada pendiente y avisa al handler
- **Clasificación de mensajes**: un `method` con `id` es una petición y se responde; un `method` sin
  `id` es una notificación y *nunca* se responde, como exige JSON-RPC: en su lugar se confirma la
  petición de control que la envuelve. Un mensaje sin `method` es una respuesta, o basura, y se ignora:
  este servidor no envía peticiones al CLI, así que nada que llegue por esa vía le corresponde
  emparejar.
- **Clasificación de fallos**: todo con lo que un `tools/call` se puede topar es un *error de ejecución
  de herramienta*, es decir, un resultado con `isError: true`, incluidos una herramienta desconocida,
  un argumento que no cumple el esquema y un handler que lanzó. Un error de JSON-RPC se reserva para lo
  que un *modelo* nunca provoca y nunca ve: un método no implementado (`-32601`), unos `params` mal
  formados (`-32602`) y una llamada que el CLI canceló (`-32800`). Un resultado con `isError` llega al
  modelo como salida de herramienta que puede leer y corregir; un error de JSON-RPC dice que la
  petición no se pudo procesar en absoluto.
- **Validación que falla cerrando**: cada `inputSchema` se comprueba, al construir, contra la
  meta-schema de su propio dialecto, y una herramienta cuyo esquema no pase se registra y se vuelve no
  invocable. De lo contrario, el validador aceptaría un esquema mal formado y validaría mal contra él
  —`{"type": "bogus"}` no encaja con nada, `"properties": "a string"` se ignora—, de modo que un
  handler se ejecutaría con argumentos que nadie comprobó, o toda llamada fallaría citando lo que no
  es.

#### McpMessageHandler
- **Propósito**: es la costura que `McpSdkServerConfig` guarda en realidad, para que una aplicación
  pueda servir MCP por su cuenta —recursos, prompts, completions, o un adaptador sobre una biblioteca
  MCP de terceros— en lugar de usar `SdkMcpServer`.
- **Contrato**: `handleMessage` devuelve la respuesta JSON-RPC de una petición y `null` para todo lo
  que no espera respuesta. `close()` significa «la conexión que te usaba se va», no «apágate»: un
  handler puede servir a más de un cliente, así que debe ser idempotente y seguir siendo utilizable.

#### ToolCallContext
- **Propósito**: permite que una herramienta en ejecución vea que su llamada se canceló.
- **Por qué debe existir**: `CompletableFuture.cancel(true)` no interrumpe una tarea en curso: completa
  el future y deja el trabajo andando. Sin una señal explícita, cancelar detendría la *espera* pero no
  el *trabajo*, y una herramienta con efectos secundarios seguiría aplicándolos después de que el CLI
  se diera por vencido.

#### SdkMcpTool
- **Propósito**: envoltorio de definición y ejecución de herramientas
- **Formas de creación**:
  - `SdkMcpTool.create()`: creación por código
  - Anotación `@Tool`: creación declarativa
- **Características**:
  - Parámetro de tipo genérico para la entrada
  - Ejecución asíncrona basada en CompletableFuture
  - JSON Schema para validar la entrada
  - Extracción automática de parámetros

**Patrón de diseño**: Command + Factory + procesamiento de anotaciones

### 6. Subsistema SessionStore

#### SessionStore (protocolo del adaptador)
- **Propósito**: replicar las transcripciones de sesión a almacenamiento externo (S3, Postgres, Redis,
  back-ends propios) para que las sesiones perduren más allá del disco local y se puedan reanudar entre
  hosts.
- **Métodos obligatorios**: `append(SessionKey, List<SessionStoreEntry>)`, `load(SessionKey)`.
- **Métodos opcionales** (con sondas de capacidad `implements*()`): `listSessions`,
  `listSessionSummaries`, `delete`, `listSubkeys`.
- **API síncrona + asíncrona**: cada método tiene una variante `*Async` (`CompletableFuture`). Los
  adaptadores con clientes no bloqueantes nativos (AWS SDK v2 async, R2DBC, Lettuce reactive)
  sobrescriben los `*Async` directamente para evitar un salto de hilo. El ejecutor por defecto se
  configura con `SessionStoreExecutor` (un hilo por tarea; virtual en Java 21+, hilos de plataforma
  daemon en el resto).

**Patrón de diseño**: Adapter + negociación de capacidades + API doble (síncrona/asíncrona)

#### TranscriptMirrorBatcher (interno)
- **Propósito**: almacenar los marcos `transcript_mirror` que el CLI emite por stdout y volcarlos a
  `store.appendAsync(...)`.
- **Comportamientos clave**:
  - Umbrales de volcado inmediato: `MAX_PENDING_ENTRIES=500`, `MAX_PENDING_BYTES=1 MiB`.
  - Volcado explícito antes de cada mensaje `result` y al final del flujo o al cerrar.
  - Agrupa marcos por `filePath`, de modo que cada archivo único reciba una llamada `append` por
    volcado.
  - Reintentos acotados: `MIRROR_APPEND_MAX_ATTEMPTS=3` intentos con espera `[200ms, 800ms]`. Los
    tiempos de espera no se reintentan (la llamada en vuelo todavía puede llegar).
  - Los marcos cuya ruta cae fuera del `projectsDir` configurado se descartan con un aviso.
  - Los fallos aparecen como `MirrorErrorMessage` en el flujo del consumidor: nunca bloquean la
    conversación.

**Patrón de diseño**: búfer productor-consumidor + reintento con espera exponencial

#### SessionResume (interno)
- **Propósito**: materializar una sesión almacenada en un `CLAUDE_CONFIG_DIR` temporal para que el
  subproceso del CLI pueda reanudar desde el disco local.
- **Flujo**:
  1. Carga las entradas con `store.loadAsync()` (o elige la sesión no lateral modificada más
     recientemente en el caso de `continueConversation`).
  2. Escribe el JSONL en un directorio temporal dispuesto como `~/.claude/`.
  3. Copia `.credentials.json` (con `refreshToken` eliminado para impedir el consumo de tokens desde el
     directorio temporal) y `.claude.json`.
  4. Materializa las transcripciones de subagente y los adjuntos `.meta.json` cuando el store
     implementa `listSubkeys`.
  5. Lanza el CLI con `CLAUDE_CONFIG_DIR=<temp dir>`.
  6. Limpia al desconectar, con reintentos ante bloqueos transitorios del antivirus o el indexador de
     Windows.

**Patrón de diseño**: vista materializada + limpieza con reintentos

#### SessionStoreValidation (interno)
- **Propósito**: comprobaciones previas de las opciones antes de crear el subproceso. Rechaza las
  combinaciones no válidas con `IllegalArgumentException`:
  - `continueConversation + sessionStore` requiere `store.implementsListSessions()`.
  - `sessionStore + enableFileCheckpointing` se rechaza (los checkpoints son solo locales).

**Patrón de diseño**: validación que falla pronto

#### SessionStoreConformance (auxiliar público de pruebas)
- **Ubicación**: `in.vidyalai.claude.sdk.testing.SessionStoreConformance`
- **Propósito**: conjunto de pruebas de comportamiento con 14 contratos, independiente del framework,
  para adaptadores `SessionStore`. Usa `AssertionError` a secas, así que funciona con cualquier
  framework de pruebas (JUnit, TestNG, Spock, un `main` normal).

**Patrón de diseño**: pruebas de contrato

## Patrones de diseño

### 1. Interfaces selladas (coincidencia de patrones)
Se usan ampliamente para un manejo de mensajes con seguridad de tipos:

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

**Ventajas**:
- Coincidencia de patrones exhaustiva en tiempo de compilación
- No hace falta un caso default
- Seguridad de tipos garantizada
- Jerarquía de tipos clara

### 2. Patrón builder
Se usa en los objetos de configuración:

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

**Ventajas**:
- Configuración legible
- Parámetros opcionales
- Objetos inmutables
- API encadenable

### 3. Patrón fachada
ClaudeSDK ofrece una interfaz simplificada:

```java
// Simple facade
List<Message> messages = ClaudeSDK.query("Hello");

// Hides complexity of:
// - Transport creation
// - QueryHandler setup
// - Message parsing
// - Resource cleanup
```

**Ventajas**:
- API sencilla para los casos comunes
- Oculta la complejidad interna
- Un único punto de entrada

### 4. Hilos virtuales (concurrencia)
Aprovecha Project Loom para lograr concurrencia ligera allí donde el runtime la ofrece.

El SDK compila contra Java 17, donde `Thread.ofVirtual()` no existe, así que todos los hilos y
ejecutores se crean a través de `internal.Threads`. Este resuelve por reflexión, una sola vez, los
puntos de entrada de Java 21 en method handles `static final`, y recae en hilos de plataforma daemon
con nombre cuando no están:

```java
// Background reader thread
Thread reader = Threads.start("ClaudeSDK-Reader-", () -> readLoop());

// Executor for control protocol
ExecutorService executor = Threads.newSingleThreadExecutor("ClaudeSDK-Reader-");
```

Los nombres de los hilos son idénticos en ambas rutas, así que los volcados de hilos se leen igual sea
cual sea el runtime. Pon `-Dclaude.sdk.virtualThreads=false` para forzar la ruta de plataforma en
cualquier JDK.

**Ventajas en Java 21+**:
- Hilos ligeros (miles son posibles)
- E/S bloqueante sin agotar el pool de hilos
- Código asíncrono más sencillo
- Mejor aprovechamiento de recursos

**En Java 17-20**: el mismo código corre sobre hilos de plataforma daemon. Los ejecutores siguen siendo
*sin límite* en vez de convertirse en pools fijos: el ejecutor de control de `QueryHandler` deja
aparcado un hilo durante toda la llamada a una herramienta MCP del SDK, y la cancelación que la termina
llega como una tarea aparte, así que un pool acotado entraría en interbloqueo. El coste es un hilo del
sistema operativo por petición de control en vuelo, en lugar de uno virtual.

### 5. CompletableFuture (operaciones asíncronas)
Se usa en los callbacks asíncronos y en el protocolo de control:

```java
// Permission callback
CompletableFuture<PermissionResult> future =
    canUseTool.apply(toolName, input, context);

// Control protocol request/response
CompletableFuture<ControlResponse> response =
    sendControlRequest(request);
```

**Ventajas**:
- Operaciones no bloqueantes
- Cadenas asíncronas componibles
- Manejo de errores
- Soporte de tiempos de espera

## Flujo de datos

### Flujo de ejecución de una consulta

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

### Flujo del client interactivo

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

### Flujo de invocación de hooks

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

### Manejo de fallos en las peticiones de control

Cada `control_request` entrante se atiende en su propio hilo, enviado con
`ExecutorService.submit(...)`, cuyo `Future` no lee nadie. Así que un `Throwable` que se escapara del
handler desaparecía sin dejar rastro y, como el CLI se bloquea hasta recibir la `control_response`
correspondiente, la ejecución se quedaba colgada sin diagnóstico por ninguno de los dos lados.

Por eso `handleControlRequest` captura `Throwable`, no `Exception`. Cualquier fallo se registra (en
`WARNING` si es una excepción corriente, `SEVERE` si es un `Error`) y se responde con una respuesta de
control de error, de modo que el CLI nunca se quede esperando; un `Error` genuino se vuelve a lanzar en
lugar de tragarse. Una petición que llega sin un `request_id` recuperable solo se puede registrar, ya
que no hay a quién responder.

Esto no es teórico. Una clase antigua compilada por el IDE que llevaba un `Error` de «unresolved
compilation problem» hacía que toda petición de control MCP del SDK se colgara en silencio hasta que se
amplió esta captura.

### Ciclo de vida del stdin y el final de una ejecución

> **El `controlExecutor` debe seguir siendo un hilo por tarea.** Una llamada a una herramienta MCP del
> SDK aparca su hilo de control hasta que la herramienta responde, y el `notifications/cancelled` que
> la termina llega como una petición de control *aparte*. Bajo cualquier pool acotado, esa cancelación
> se pondría en cola detrás justamente de la llamada que existe para cancelar, y se produciría un
> interbloqueo. Ninguna prueba lo detectaría: un pool fijo de dos pasa todo.

Cuando hay hooks, servidores MCP del SDK o un callback de permisos `canUseTool` registrados, el
protocolo de control necesita el stdin abierto mientras el CLI todavía pueda devolver llamadas, así que
`QueryHandler.streamInput()` espera **al final de la ejecución** antes de llamar a
`transport.endInput()`. Los tres se atienden igual —el CLI escribe una `control_request` y se bloquea
hasta que el SDK escribe la `control_response` correspondiente en el stdin—, así que los tres cuentan
como necesidades bidireccionales (`hasBidirectionalNeeds()`). Sin ninguno de ellos, el stdin se cierra
en cuanto se escriben los prompts. Cerrar demasiado pronto no es inocuo, y el cierre tampoco se puede
diferir sin más a `close()`: el CLI en modo stream-json sale **solo** con EOF en el stdin, así que eso
dejaría colgado para siempre un `query()` de un solo uso.

La sutileza está en que **un marco `result` termina un turno, no la ejecución**. Un subagente en
segundo plano sigue corriendo más allá de él y, cuando termina, su finalización despierta al padre para
un turno de seguimiento. Las peticiones de hooks, de permisos y de MCP del SDK de ese turno también
necesitan el stdin. Cerrar demasiado pronto hacía que esas peticiones fallaran con `"Stream closed"` y
—de forma más silenciosa— se saltaba los hooks `PreToolUse`, así que las herramientas integradas se
ejecutaban sin callback y los hooks de denegación dejaban de hacer de barrera.

`QueryHandler` decide cuándo ha terminado la ejecución a partir de dos fuentes.

**1. El estado de la sesión del CLI (principal).** El transporte establece
`CLAUDE_CODE_SDK_READS_SESSION_STATE=1` en el proceso del CLI (salvo que el `options.env` del llamante o
el entorno heredado ya la nombren, con cualquier combinación de mayúsculas y minúsculas). Un CLI que la
respeta envía marcos `system` / `session_state_changed` marcados con `sdk_host_only: true`: permanece en
`running` mientras hay un agente en segundo plano vivo o su finalización aún tiene pendiente un turno,
en `requires_action` mientras espera al host, e informa `idle` cuando ya no se debe ningún turno más. El
lector sigue el estado más reciente y **descarta los marcos marcados con `sdk_host_only`** antes de que
lleguen al consumidor. Los marcos sin marcar —enviados porque el llamante lo pidió con
`CLAUDE_CODE_EMIT_SESSION_STATE_EVENTS`— determinan el final de la ejecución de la misma forma y se
transmiten.

**2. El libro de tareas (alternativa y salvaguarda).** `QueryHandler` mantiene un libro de tareas en
vuelo, alimentado por marcos `system` de ciclo de vida de tarea:

```
system: task_started (task_type ∈ DEFERRING_TASK_TYPES)  ─►  add task_id
system: task_notification                                ─►  remove task_id
system: task_updated (patch.status ∈ TERMINAL_TASK_STATUSES) ─► remove task_id
```

Las reglas de fin de ejecución, todas bajo un único `runLock`:

```
result frame
    ├─ state is null (CLI sends none) or "idle", or no bidirectional needs
    │      └─ ledger empty      ─►  end the run  ─►  endInput()
    │      └─ ledger non-empty  ─►  keep stdin open (log at FINE)
    ├─ state is "requires_action" ─►  wait (a request is being answered)
    └─ state is "running"         ─►  wait, and arm the run-end ceiling

session_state_changed
    ├─ "idle" after a result  ─►  end the run, unless the ledger is non-empty
    ├─ "idle" before a result ─►  nothing (the prompt's run has not produced a result yet)
    ├─ "requires_action"      ─►  reopen the run if it had ended; stop the ceiling
    └─ "running" (or other)   ─►  reopen the run if it had ended; restart the ceiling if past a result
```

Algunos hosts envían `idle` justo *antes* del resultado; entonces es el resultado el que termina la
ejecución. Un CLI que no envía ningún estado (CLI más antiguos, y Claude Code 2.1.283, que todavía no
respeta `CLAUDE_CODE_SDK_READS_SESSION_STATE`) deja el estado en `null`, así que el primer resultado con
el libro vacío termina la ejecución: el comportamiento anterior a la 0.2.3.

**El límite de fin de ejecución.** El propio límite de espera en segundo plano del CLI solo empieza a
contar cuando el stdin está cerrado, así que, sin un límite propio, un trabajo que nunca termina
mantendría `running` —y el stdin— abiertos para siempre. Tras un resultado con el CLI todavía informando
`running`, `QueryHandler` arranca un temporizador en un hilo de `Threads`; si no empieza ningún turno
nuevo antes de que despierte, termina la ejecución. La duración es
`CLAUDE_CODE_PRINT_BG_WAIT_CEILING_MS`, leída tal como la verá el CLI —primero `options.env`, luego el
entorno del proceso—, donde `0` significa sin límite, cualquier valor que no sea un entero no negativo
recae en el valor por defecto del CLI de 600000 ms (10 minutos), y los valores por encima de
`Integer.MAX_VALUE` ms (~24,8 días) se recortan a ese máximo. El límite solo cuenta la espera **entre**
turnos:

- Un marco `assistant` o `stream_event` del hilo principal (`parent_tool_use_id == null`) indica que hay
  un turno en marcha: el límite se detiene, y la ejecución se reabre aunque el límite ya la hubiera
  terminado. Los mensajes propios de un subagente **no** lo detienen: son precisamente el trabajo que
  acota.
- Un resultado lo reinicia; también lo reinicia un `running` que llega después de un resultado.
- `requires_action` lo detiene hasta el `running` siguiente.
- Un agente rastreado que sigue en vuelo cuando el límite salta no se corta; el límite vuelve a empezar
  cuando el libro se vacía.

Cada temporizador lleva un número de generación, y limpiar o volver a armar el límite lo incrementa e
interrumpe el temporizador, de modo que un temporizador que despierta tarde se retira.

**Reapertura.** Terminar la ejecución completa un `CompletableFuture`; el trabajo que el CLI emprende
después (una tarea en segundo plano terminada que lo despierta) lo sustituye por uno nuevo, de modo que
quien todavía no ha empezado a esperar —`streamInput`, aún escribiendo prompts— también espera al
trabajo nuevo. Cada prompt que escribe `streamInput` tiene derecho a su propia ejecución: antes de
escribirlo, la ejecución se reabre y se reinicia el indicador de «resultado recibido», de modo que un
prompt de varios mensajes espera a la ejecución de su *último* prompt y no a la del primero. Una vez
cerrado el stdin, o cuando el lector ha salido, la ejecución es definitiva y permanece terminada, y no
se arma ningún límite para los marcos que llegan mientras el CLI se detiene. Tanto `close()` como el
bloque `finally` del lector terminan la ejecución, así que quien espera nunca puede quedarse colgado.

No hay ningún otro tiempo de espera sobre esta espera. Antes, Java también la limitaba con
`CLAUDE_CODE_STREAM_CLOSE_TIMEOUT` (60 s), lo que cortaba cualquier agente en segundo plano que durara
más de un minuto; ese límite ha desaparecido, y `CLAUDE_CODE_STREAM_CLOSE_TIMEOUT` ahora solo fija el
tiempo de espera de `initialize`.

`DEFERRING_TASK_TYPES` es `{"local_agent", "local_workflow"}`. Las exclusiones son deliberadas, no
descuidos: los shells en segundo plano (`local_bash`) y los monitores corren indefinidamente por
diseño, y los teammates siguen en `running` toda su vida, así que ninguno alcanza de forma fiable un
estado terminal. Rastrear uno retendría el cierre *para siempre* en vez de brevemente, y, al no haber
salida del proceso, ni siquiera se ejecutaría el `finally` del lector. Todo lo que se añada a este
conjunto debe ser un tipo que termine de forma fiable.

Los marcos `background_tasks_changed` se ignoran en ambos sentidos. Esa carga útil es el conjunto vivo
de tareas *en segundo plano*, pero un subagente se registra en primer plano y solo después pasa a
segundo plano sin un segundo `task_started`, así que estrechar a partir de ella descartaría justo al
agente que este libro existe para proteger, y ampliar a partir de ella podría admitir un id que ningún
marco posterior limpia.

Limitación conocida: con un CLI que no informa del estado de la sesión, el resultado de un prompt
anterior de un iterador de prompts de varios mensajes sigue terminando la ejecución aunque ya haya un
prompt posterior en cola en el lado del CLI, así que las peticiones de control de ese turno posterior
pueden encontrarse el stdin cerrado. Los prompts de un solo mensaje y los de cadena, las formas
habituales de un solo uso, están totalmente cubiertos.

## Modelo de concurrencia

### Arquitectura de hilos

El SDK usa una arquitectura multihilo (hilos virtuales en Java 21+, hilos de plataforma daemon en
17-20):

1. **Hilo principal**: el hilo de la aplicación del usuario
2. **Hilo lector**: lee del stdout del CLI
3. **Ejecutor de control**: pool de hilos para las operaciones asíncronas del protocolo de control
4. **Ejecutor de streaming**: hilo opcional para transmitir mensajes de entrada
5. **Ejecutores de hook**: un hilo por hook o llamada a herramienta en vuelo

### Seguridad entre hilos

- **AtomicBoolean**: se usa para el estado de conexión y de cierre
- **volatile**: se usa para la visibilidad de QueryHandler y Transport
- **Sincronización**: se usa en connect() para evitar condiciones de carrera
- **BlockingQueue**: cola de mensajes segura entre hilos
- **ConcurrentHashMap**: seguimiento seguro de las peticiones de control

### Gestión de recursos

Todos los recursos implementan AutoCloseable:

```java
try (ClaudeSDKClient client = ClaudeSDK.createClient()) {
    // Use client
} // Automatic cleanup: QueryHandler, Transport, Executors
```

## Sistema de tipos

### Jerarquía de tipos de mensaje

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

Ambas jerarquías selladas se llevan bien con el `switch` exhaustivo, lo que significa que añadir un
miembro es una ruptura de compatibilidad de código fuente deliberada para quien llama. `ContentBlock`
ganó tres miembros en la 0.1.20; `UnknownBlock` existe para que los tipos *no modelados* ya no
requieran ningún cambio en el SDK: el analizador los conserva enteros y registra una vez por tipo en
lugar de lanzar.

### Tipos de configuración

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

### Tipos de permisos

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

## Dependencias

### Dependencias de ejecución

1. **Jackson** (2.21.0)
   - `jackson-databind`: serialización/deserialización JSON
   - `jackson-annotations`: anotaciones JSON
   - Propósito: analizar los mensajes JSON del CLI, serializar el protocolo de control

2. **JSpecify** (1.0.0)
   - Anotaciones de nulidad (`@Nullable`, `@NonNull`)
   - Propósito: mejor seguridad frente a nulos y soporte del IDE

3. **networknt json-schema-validator** (2.0.4)
   - Propósito: validar los argumentos de las herramientas MCP del SDK contra el `inputSchema`
     declarado por la herramienta antes de que se ejecute el handler, como la especificación de MCP
     exige a los servidores
   - Fijado a la línea 2.x de forma deliberada: la 3.x está construida sobre Jackson 3
     (`tools.jackson`) y pondría una segunda pila JSON completa junto al Jackson 2 anterior. La 2.0.4
     es la versión más reciente que reutiliza nuestro databind.
   - Su lector de esquemas YAML queda excluido (los esquemas de herramienta llegan como mapas ya
     analizados), igual que un formateador de informes de Surefire que declara en ámbito compile por
     error
   - Arrastra `slf4j-api` (2.0.17) de forma transitiva. El SDK registra por `java.util.logging` y
     **no** incluye ningún binding de SLF4J: elegir uno es decisión de la aplicación. Una aplicación
     sin proveedor ve un aviso único `No SLF4J providers were found` en stderr la primera vez que
     construye un servidor MCP del SDK; añadir cualquier binding lo elimina.

### Dependencias de pruebas

1. **JUnit 5** (6.0.2)
   - Framework de pruebas
   - Propósito: pruebas unitarias y de integración

2. **AssertJ** (3.27.7)
   - Biblioteca de aserciones fluidas
   - Propósito: aserciones de prueba legibles

3. **Mockito** (5.21.0)
   - Framework de mocks
   - Propósito: simular dependencias en las pruebas

### Dependencias de compilación

1. **Maven Compiler Plugin** (3.14.1)
   - Compilación para Java 17 (`<release>17</release>`) con la bandera `-parameters`
   - Propósito: conservar los nombres de los parámetros para la anotación @Tool

2. **Flatten Maven Plugin** (1.7.3)
   - Resuelve la propiedad `${revision}`
   - Propósito: versionado amigable con la CI

3. **Templating Maven Plugin** (3.1.0)
   - Genera SdkVersion.java a partir de una plantilla
   - Propósito: inyectar la versión en tiempo de compilación

## Principios de diseño

1. **Seguridad de tipos**: aprovechar el sistema de tipos de Java (interfaces selladas, records, enums)
2. **Inmutabilidad**: los objetos de configuración son inmutables
3. **Seguridad entre hilos**: documentar y hacer cumplir las garantías
4. **Gestión de recursos**: AutoCloseable para una limpieza correcta
5. **Patrón builder**: configuración fluida y legible
6. **Fallar pronto**: validar temprano y lanzar excepciones con sentido
7. **Coincidencia de patrones**: usar características modernas de Java para un código más limpio
8. **Hilos virtuales**: concurrencia ligera en Java 21+, de forma transparente
9. **Separación de responsabilidades**: fronteras claras entre capas
10. **Extensibilidad**: sistema de plugins y transportes propios

## Consideraciones de rendimiento

1. **Hilos virtuales**: miles de operaciones concurrentes son posibles en Java 21+
2. **E/S con búfer**: reduce las llamadas al sistema en la comunicación con el subproceso
3. **Cola de mensajes**: tamaño configurable para equilibrar memoria y rendimiento
4. **Inicialización perezosa**: el QueryHandler se crea solo cuando hace falta
5. **Reutilización de recursos**: el ExecutorService se reutiliza entre operaciones
6. **Memoria directa**: Jackson hace un manejo eficiente de búferes
7. **Copia mínima**: los objetos de mensaje son records (sin copia defensiva)

## Extensibilidad futura

La arquitectura admite mejoras futuras:

1. **Transportes propios**: implementar la interfaz Transport para un Claude Code remoto
2. **Más tipos de mensaje**: añadir a la jerarquía de la interfaz sellada
3. **Nuevos eventos de hook**: añadir al enum HookEvent
4. **Sistema de plugins**: SdkPluginConfig para extensiones propias
5. **Protocolos alternativos**: sustituir la implementación del protocolo de control
6. **Mejoras de streaming**: soporte ampliado de mensajes parciales
7. **Caché**: añadir una capa de caché entre el SDK y el CLI
8. **Métricas**: añadir telemetría y monitorización de rendimiento
