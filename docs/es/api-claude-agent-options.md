# Referencia de la API ClaudeAgentOptions

Builder de las opciones de configuración.

> **Sobre esta traducción**: la documentación en inglés es la única autoritativa. Esta traducción puede estar desactualizada respecto al [original en inglés](../api-claude-agent-options.md); si hay discrepancias, prevalece el inglés. Los bloques de código se mantienen idénticos al original y no se han traducido.

## Visión general de la clase

```java
public final class ClaudeAgentOptions
```

Objeto de configuración inmutable que usa el patrón builder.

## Crear las opciones

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

## Todas las opciones de configuración

### Configuración de herramientas
- `tools(Object)`: lista de herramientas o preajuste
- `allowedTools(List<String>)`: lista de permitidas
- `disallowedTools(List<String>)`: lista de bloqueadas

### Prompt de sistema
- `systemPrompt(Object)`: String, `SystemPromptPreset` o `SystemPromptFile`

### Servidores MCP
- `mcpServers(Object)`: Map, Path o String
- `strictMcpConfig(boolean)`: cuando es `true`, el CLI ignora el `.mcp.json` del proyecto, los ajustes de usuario/globales y los servidores MCP aportados por plugins; solo se cargan los servidores pasados con `mcpServers(...)`. Corresponde a `--strict-mcp-config`.

### Permisos
- `permissionMode(PermissionMode)`: modo de permisos
- `permissionPromptToolName(String)`: herramienta usada para las peticiones
- `canUseTool(CanUseTool)`: callback personalizado. **Solo se dispara en decisiones `"ask"`**, no en llamadas a herramientas ya permitidas por `allowedTools`, `permissionMode` o reglas `permissions.allow`. Usa un hook `PreToolUse` para controlar todas las llamadas con independencia de la decisión. El SDK registra un `WARNING` informativo al conectar si este callback queda visiblemente ensombrecido por entradas de `allowedTools` que cubren la herramienta entera o por `BYPASS_PERMISSIONS`; consulta [Aviso de sombreado](feature-permissions.md#aviso-de-sombreado).

### Sesiones
- `continueConversation(boolean)`: continuar la última sesión
- `resume(String)`: reanudar una sesión concreta
- `forkSession(boolean)`: bifurcar la sesión reanudada
- `resumeSessionAt(String)`: reanudación con truncado: carga la conversación reanudada solo hasta este UUID de entrada de la transcripción (incluido), ramificando desde un punto anterior. Úsalo con `resume` y, normalmente, con `forkSession`. Acepta cualquier UUID de entrada de la transcripción, habitualmente un `AssistantMessage.uuid()` observado en vivo o un `SessionMessage.uuid()` obtenido de `ClaudeSDK.getSessionMessages(...)`. Se emite como `--resume-session-at=<value>`. Consulta [Reanudación con truncado](./feature-session-history.md#reanudación-con-truncado).
- `resumeDropsTurn(String)`: junto con `resumeSessionAt`, el UUID del prompt de usuario cuyo turno pretende descartar el truncado. El CLI valida entonces, al cargar, que *todas* las entradas posteriores al punto de bifurcación pertenecen a ese turno y se niega en caso contrario, de modo que un mensaje de usuario en cola o una notificación de tarea que la sesión absorbió a mitad de turno nunca se descarta en silencio. La negativa aparece como una excepción cuyo mensaje contiene `Resume rejected by --resume-drops-turn:`; trátala como determinista y reanuda de forma normal en vez de reintentar. Se reenvía siempre que no sea nulo, así que una cadena vacía llega al CLI y allí se rechaza por estar mal formada, en lugar de desactivar la protección en silencio. Se emite como `--resume-drops-turn=<value>`.
- `sessionStore(SessionStore)`: replica las transcripciones a un store externo y reanuda desde él (consulta [Session Store](./feature-session-store.md)). Cuando se define, el SDK pasa `--session-mirror` al CLI y enruta los marcos `transcript_mirror` a `store.appendAsync(...)`. La validación previa rechaza `continueConversation + sessionStore` sin soporte de `listSessions()` y `sessionStore + enableFileCheckpointing`.
- `sessionStoreFlush(SessionStoreFlushMode)`: cuándo se vuelcan al `sessionStore` las entradas del espejo de transcripción. `BATCHED` (por defecto) agrupa entradas y vuelca una vez por turno o cuando el búfer supera 500 entradas / 1 MiB; `EAGER` programa un volcado en segundo plano tras cada marco, para una entrega casi en tiempo real. Se ignora si `sessionStore` no está definido. Consulta [Modo de volcado](./feature-session-store.md#modo-de-volcado-batched-frente-a-eager).
- `loadTimeoutMs(long)`: tiempo de espera por llamada de `store.loadAsync()` / `listSubkeysAsync()` durante la materialización de la reanudación, en milisegundos (por defecto `60_000`). Un valor de `0` significa expiración inmediata; valores muy grandes lo desactivan en la práctica.

### Límites
- `maxTurns(Integer)`: máximo de turnos de conversación
- `maxBudgetUsd(Double)`: coste máximo en USD
- `maxBufferSize(Integer)`: bytes máximos del búfer de stdout
- `thinking(ThinkingConfig)`: configuración del razonamiento extendido
- `effort(String)`: nivel de profundidad del razonamiento (`"low"`, `"medium"`, `"high"`, `"xhigh"`, `"max"`). `"xhigh"` es propio de Opus 4.7 y recae en `"high"` en los demás modelos.
- `effort(EffortLevel)`: igual que el anterior, pero con seguridad de tipos usando el enum [`EffortLevel`](feature-configuration-options.md#enum-effortlevel) (`LOW`, `MEDIUM`, `HIGH`, `XHIGH`, `MAX`). Pasa `null` para limpiarlo.
- `maxThinkingTokens(Integer)`: **OBSOLETO**. Usa `thinking()`
- `maxMsgQSize(Integer)`: tamaño máximo de la cola de mensajes

### Modelo
- `model(String)`: nombre del modelo de IA
- `fallbackModel(String)`: modelo de reserva
- `betas(List<SdkBeta>)`: funciones beta

### Entorno
- `cwd(Path)`: directorio de trabajo
- `cliPath(Path)`: ruta personalizada del CLI (una ruta `.bat`/`.cmd` de Windows se rechaza; véase más abajo)
- `allowUnsafeWindowsBatchCli(boolean)`: renuncia al rechazo de scripts batch en Windows; además exige `-Djdk.lang.Process.allowAmbiguousCommands=false` y rechaza los metacaracteres de cmd.exe en todos los argumentos (por defecto `false`)
- `settings(String)`: ruta del archivo de ajustes
- `addDirs(List<Path>)`: directorios de contexto adicionales
- `env(Map<String, String>)`: variables de entorno
- `extraArgs(Map<String, String>)`: flags adicionales del CLI

### Callbacks
- `stderrCallback(Consumer<String>)`: callback de stderr

### Hooks
- `hooks(Map<HookEvent, List<HookMatcher>>)`: callbacks de hook
- `includeHookEvents(boolean)`: cuando es `true`, el CLI envía los eventos del ciclo de vida de los hooks (`PreToolUse`, `PostToolUse`, `Stop`, …) al flujo de mensajes como objetos `HookEventMessage`. Corresponde a `--include-hook-events`. Consulta [Hooks → Eventos del ciclo de vida en el flujo](./feature-hooks.md#eventos-del-ciclo-de-vida-de-los-hooks-en-el-flujo).

### Avanzado
- `user(String)`: identidad del usuario
- `includePartialMessages(boolean)`: habilita el streaming
- `forwardSubagentText(boolean)`: cuando es `true`, los bloques de texto y de razonamiento de los subagentes se reenvían al flujo de mensajes junto con los bloques `tool_use` / `tool_result`, que siempre se reenvían. Se envía en la petición de control `initialize` (sin flag de CLI). Consulta [Agentes → Observar la salida de un subagente](./feature-agents.md#observar-la-salida-de-un-subagente).
- `agents(Map<String, AgentDefinition>)`: agentes personalizados
- `settingSources(List<SettingSource>)`: fuentes de configuración (una lista vacía desactiva todas las fuentes con `--setting-sources=`; omitirlo mantiene los valores por defecto del CLI)
- `skills(List<String>)`: lista de skills permitidas (inyecta automáticamente `Skill(name)` en `allowedTools` y fija `settingSources` en user/project). Los nombres deben ser exactos: los comodines, los delimitadores de reglas y los espacios alrededor lanzan `IllegalArgumentException` en `connect()`
- `skillsAll()`: habilita todas las skills detectadas (inyecta automáticamente la herramienta `Skill` sin argumentos)
- `sandbox(SandboxSettings)`: configuración del sandbox
- `plugins(List<SdkPluginConfig>)`: configuración de plugins
- `outputFormat(Map<String, Object>)`: formato de salida
- `checkpointFiles(boolean)`: habilita los checkpoints

## Véase también
- [Guía de opciones de configuración](./feature-configuration-options.md)
