# Referencia de la API ClaudeSDK

Fachada estática para consultas sencillas y creación de clients.

> **Sobre esta traducción**: la documentación en inglés es la única autoritativa. Esta traducción puede estar desactualizada respecto al [original en inglés](../api-claude-sdk.md); si hay discrepancias, prevalece el inglés. Los bloques de código se mantienen idénticos al original y no se han traducido.

## Visión general de la clase

```java
public final class ClaudeSDK
```

Clase de utilidad que ofrece métodos estáticos para las operaciones habituales del SDK.

## Métodos de consulta

### query(String prompt)

```java
public static List<Message> query(String prompt)
```

Ejecuta una consulta con las opciones por defecto.

**Devuelve**: `List<Message>`

### query(String prompt, ClaudeAgentOptions options)

```java
public static List<Message> query(
    String prompt,
    ClaudeAgentOptions options
)
```

Ejecuta una consulta con opciones propias.

**Parámetros**:
- `prompt`: el prompt
- `options`: opciones de configuración

**Devuelve**: `List<Message>`

**Lanza**:
- `IllegalArgumentException`: si se definen a la vez canUseTool y permissionPromptToolName
- `CLIConnectionException`: fallo de conexión
- `ProcessException`: fallo del proceso del CLI
- `QueryFailedException`: la ejecución terminó en un resultado de error (`error_max_turns`, `error_max_budget_usd`, una reanudación rechazada por `resumeDropsTurn`). Lleva los mensajes recopilados antes de eso; véase más abajo.

### query(Iterator<Map<String, Object>> messageStream, ClaudeAgentOptions options)

```java
public static List<Message> query(
    Iterator<Map<String, Object>> messageStream,
    ClaudeAgentOptions options
)
```

Ejecuta una consulta por streaming con varios mensajes.

**Parámetros**:
- `messageStream`: iterador de diccionarios de mensaje
- `options`: opciones de configuración

**Devuelve**: `List<Message>`

**Lanza**: lo mismo que arriba, incluida `QueryFailedException`.

### Resultados de error y mensajes parciales

El CLI informa de `error_max_turns` y `error_max_budget_usd` emitiendo un turno *completo* —mensajes
del asistente más un `ResultMessage` final con el subtipo, el coste y el uso— y solo entonces sale con
un código distinto de cero, a propósito, pensando en quien lo usa desde el shell.

Estos métodos que recopilan tienen que devolver una lista o lanzar, así que, cuando eso ocurre, lanzan
`QueryFailedException` y devuelven en ella los mensajes recopilados. No se pierde nada:

```java
try {
    List<Message> messages = ClaudeSDK.query(prompt, options);
} catch (QueryFailedException e) {
    ResultMessage result = e.resultMessage();       // the final result, or null
    List<Message> partial = e.partialMessages();    // everything received first
    if (result != null && "error_max_budget_usd".equals(result.subtype())) {
        System.out.printf("Stopped after $%.4f%n", result.totalCostUsd());
    }
}
```

Captúrala siempre que fijes `maxTurns` o `maxBudgetUsd`: alcanzar un límite que tú mismo configuraste
es un desenlace esperado, no un fallo. Cuando quieras la carga útil del resultado (`subtype()`,
`terminalReason()`, `apiErrorStatus()`, …) en lugar de los mensajes recopilados, léela de la causa,
que es una [`ResultException`](./api-exceptions.md#resultexception).

Las API de streaming de [`ClaudeSDKClient`](./api-claude-sdk-client.md) nunca necesitaron esta
excepción: entregan cada mensaje según llega, y `receiveResponse()` se detiene en el
`ResultMessage`; comprueba directamente su `isError()` y su `subtype()`. Consulta
[Excepciones](./api-exceptions.md#queryfailedexception).

## Métodos de conveniencia

### queryForText(String prompt, ClaudeAgentOptions options)

```java
public static String queryForText(
    String prompt,
    ClaudeAgentOptions options
)
```

Obtiene solo el contenido de texto de los mensajes del asistente.

**Devuelve**: `String`, el texto combinado

### queryForResult(String prompt, ClaudeAgentOptions options)

```java
public static ResultMessage queryForResult(
    String prompt,
    ClaudeAgentOptions options
)
```

Obtiene solo el mensaje de resultado.

**Devuelve**: `ResultMessage` o null

## Métodos de fábrica de client

### createClient()

```java
public static ClaudeSDKClient createClient()
```

Crea un client con las opciones por defecto.

**Devuelve**: `ClaudeSDKClient`

### createClient(ClaudeAgentOptions options)

```java
public static ClaudeSDKClient createClient(
    ClaudeAgentOptions options
)
```

Crea un client con opciones propias.

**Devuelve**: `ClaudeSDKClient`

## Métodos de fábrica de servidores MCP

### createSdkMcpServer(String name, List<SdkMcpTool<?>> tools)

```java
public static McpSdkServerConfig createSdkMcpServer(
    String name,
    List<SdkMcpTool<?>> tools
)
```

Crea un servidor MCP del SDK a partir de una lista de herramientas.

**Parámetros**:
- `name`: nombre del servidor
- `tools`: lista de herramientas

**Devuelve**: `McpSdkServerConfig`

### createSdkMcpServer(String name, String version, List<SdkMcpTool<?>> tools)

```java
public static McpSdkServerConfig createSdkMcpServer(
    String name,
    String version,
    List<SdkMcpTool<?>> tools
)
```

Crea un servidor MCP del SDK con versión.

### createSdkMcpServer(String name, Object instance)

```java
public static McpSdkMcpServer createSdkMcpServer(
    String name,
    Object instance
)
```

Crea un servidor MCP del SDK a partir de métodos anotados con @Tool.

**Parámetros**:
- `name`: nombre del servidor
- `instance`: objeto con métodos @Tool

**Devuelve**: `McpSdkServerConfig`

## Métodos de historial de sesiones

### listSessions()

```java
public static List<SDKSessionInfo> listSessions()
```

Lista todas las sesiones de todos los proyectos, de la modificada más recientemente a la más antigua.
Lee de `~/.claude/projects/` sin analizar por completo los archivos JSONL: solo los primeros y los
últimos 64 KB de cada uno.

**Devuelve**: `List<SDKSessionInfo>` ordenada por fecha de modificación descendente

### listSessions(Path directory)

```java
public static List<SDKSessionInfo> listSessions(Path directory)
```

Lista las sesiones de un directorio de proyecto concreto.

**Parámetros**:
- `directory`: el directorio de trabajo del proyecto por el que filtrar

**Devuelve**: `List<SDKSessionInfo>`

### listSessions(Path directory, Integer limit, boolean includeWorktrees)

```java
public static List<SDKSessionInfo> listSessions(
    Path directory,
    Integer limit,
    boolean includeWorktrees
)
```

Lista sesiones con control total.

**Parámetros**:
- `directory`: directorio de proyecto por el que filtrar (null = todos los proyectos)
- `limit`: máximo de sesiones a devolver (null = sin límite)
- `includeWorktrees`: si se incluyen los directorios de worktree de git

**Devuelve**: `List<SDKSessionInfo>`

### getSessionInfo(String sessionId)

```java
@Nullable
public static SDKSessionInfo getSessionInfo(String sessionId)
```

Consulta una sola sesión por su ID. Busca en todos los directorios de proyecto bajo
`~/.claude/projects/`. Sin recorrido O(n) de directorios: lee solo el archivo de la sesión buscada.

**Parámetros**:
- `sessionId`: UUID de la sesión a consultar

**Devuelve**: el `SDKSessionInfo` de la sesión, o `null` si no se encuentra, es una sesión lateral o no
tiene un resumen extraíble

### getSessionInfo(String sessionId, Path directory)

```java
@Nullable
public static SDKSessionInfo getSessionInfo(
    String sessionId,
    Path directory
)
```

Consulta una sola sesión por su ID dentro de un directorio de proyecto concreto.

**Parámetros**:
- `sessionId`: UUID de la sesión a consultar
- `directory`: directorio de trabajo del proyecto donde buscar

**Devuelve**: `SDKSessionInfo` o `null`

### getSessionMessages(String sessionId)

```java
public static List<SessionMessage> getSessionMessages(String sessionId)
```

Devuelve todos los mensajes de la conversación de una sesión. Busca en todos los directorios de
proyecto.

**Parámetros**:
- `sessionId`: UUID de la sesión

**Devuelve**: `List<SessionMessage>` en el orden de la conversación

### getSessionMessages(String sessionId, Path directory)

```java
public static List<SessionMessage> getSessionMessages(
    String sessionId,
    Path directory
)
```

Devuelve los mensajes de una sesión de un proyecto concreto.

**Parámetros**:
- `sessionId`: UUID de la sesión
- `directory`: directorio de trabajo del proyecto donde buscar

**Devuelve**: `List<SessionMessage>`

### getSessionMessages(String sessionId, Path directory, Integer limit, int offset)

```java
public static List<SessionMessage> getSessionMessages(
    String sessionId,
    Path directory,
    Integer limit,
    int offset
)
```

Devuelve mensajes con control total sobre el filtrado.

**Parámetros**:
- `sessionId`: UUID de la sesión
- `directory`: directorio de proyecto donde buscar (null = todos los proyectos)
- `limit`: máximo de mensajes a devolver (null = sin límite)
- `offset`: número de mensajes a omitir desde el principio

**Devuelve**: `List<SessionMessage>`

## Métodos de transcripción de subagentes

Cuando una sesión crea subagentes (con la herramienta `Task` o con definiciones de agente por código),
la transcripción de cada subagente se escribe en
`~/.claude/projects/<project>/<sessionId>/subagents/agent-<agentId>.jsonl`. Estos archivos también
pueden estar en directorios anidados como `subagents/workflows/<runId>/`.

### listSubagents(String sessionId)

```java
public static List<String> listSubagents(String sessionId)
```

Lista los IDs de subagente de una sesión recorriendo su directorio `subagents/` en todos los
directorios de proyecto.

**Parámetros**:
- `sessionId`: UUID de la sesión padre

**Devuelve**: `List<String>` con los IDs de subagente. Vacía cuando no se encuentra la sesión, el
`sessionId` no es un UUID válido o la sesión no tiene subagentes.

### listSubagents(String sessionId, Path directory)

```java
public static List<String> listSubagents(String sessionId, Path directory)
```

Lista los IDs de subagente limitándose a un directorio de proyecto concreto.

**Parámetros**:
- `sessionId`: UUID de la sesión padre
- `directory`: directorio de trabajo del proyecto donde encontrar la sesión

### getSubagentMessages(String sessionId, String agentId)

```java
public static List<SessionMessage> getSubagentMessages(
    String sessionId,
    String agentId
)
```

Lee los mensajes user/assistant de un subagente desde su transcripción JSONL. Sigue los enlaces
`parentUuid` para reconstruir la cadena. El `parentToolUseId` de cada mensaje es el `tool_use` del
Agent en la sesión padre que creó este subagente, y `parentAgentId` nombra al subagente creador en los
casos anidados; ambos proceden del archivo adjunto `agent-<agentId>.meta.json` que acompaña a la
transcripción, ya que las líneas de la propia transcripción no los registran, y ambos son nulos cuando
ese adjunto falta o no es utilizable.

**Parámetros**:
- `sessionId`: UUID de la sesión padre
- `agentId`: ID del subagente (el que devuelve `listSubagents`)

**Devuelve**: `List<SessionMessage>` en orden cronológico. Vacía cuando no se encuentran la sesión o el
subagente, el `sessionId` no es un UUID válido, o la transcripción no contiene mensajes
user/assistant.

### getSubagentMessages(String sessionId, String agentId, Path directory)

```java
public static List<SessionMessage> getSubagentMessages(
    String sessionId,
    String agentId,
    Path directory
)
```

Lee los mensajes de un subagente limitándose a un directorio de proyecto concreto.

### getSubagentMessages(String sessionId, String agentId, Path directory, Integer limit, int offset)

```java
public static List<SessionMessage> getSubagentMessages(
    String sessionId,
    String agentId,
    @Nullable Path directory,
    @Nullable Integer limit,
    int offset
)
```

Lee los mensajes de un subagente con control total sobre el filtrado y la paginación.

**Parámetros**:
- `sessionId`: UUID de la sesión padre
- `agentId`: ID del subagente
- `directory`: directorio de proyecto donde buscar (null = todos los proyectos)
- `limit`: máximo de mensajes a devolver (null o `0` = sin límite)
- `offset`: número de mensajes a omitir desde el principio

## Métodos de mutación de sesiones

### renameSession(String sessionId, String title)

```java
public static void renameSession(
    String sessionId,
    String title
) throws IOException
```

Renombra una sesión añadiendo una entrada de título personalizado. Gana el último cambio de nombre.
Busca en todos los directorios de proyecto.

**Parámetros**:
- `sessionId`: UUID de la sesión a renombrar
- `title`: nuevo título de la sesión (sin los espacios de los extremos)

**Lanza**:
- `IllegalArgumentException`: si `sessionId` no es un UUID válido o `title` está vacío
- `FileNotFoundException`: si no se encuentra el archivo de la sesión
- `IOException`: si falla la escritura

### renameSession(String sessionId, String title, Path directory)

```java
public static void renameSession(
    String sessionId,
    String title,
    Path directory
) throws IOException
```

Renombra una sesión limitándose a un directorio de proyecto concreto.

**Parámetros**:
- `sessionId`: UUID de la sesión a renombrar
- `title`: nuevo título de la sesión
- `directory`: directorio de trabajo del proyecto donde buscar

### tagSession(String sessionId, String tag)

```java
public static void tagSession(
    String sessionId,
    @Nullable String tag
) throws IOException
```

Etiqueta una sesión. Pasa `null` para borrar una etiqueta existente. Las etiquetas se sanean en Unicode
antes de guardarse. Busca en todos los directorios de proyecto.

**Parámetros**:
- `sessionId`: UUID de la sesión a etiquetar
- `tag`: texto de la etiqueta, o `null` para borrarla. No puede quedar vacío tras el saneado (salvo que
  sea `null`).

**Lanza**:
- `IllegalArgumentException`: si `sessionId` no es válido o `tag` queda vacía tras el saneado
- `FileNotFoundException`: si no se encuentra el archivo de la sesión
- `IOException`: si falla la escritura

### tagSession(String sessionId, String tag, Path directory)

```java
public static void tagSession(
    String sessionId,
    @Nullable String tag,
    Path directory
) throws IOException
```

Etiqueta una sesión limitándose a un directorio de proyecto concreto.

**Parámetros**:
- `sessionId`: UUID de la sesión a etiquetar
- `tag`: texto de la etiqueta, o `null` para borrarla
- `directory`: directorio de trabajo del proyecto donde buscar

### deleteSession(String sessionId)

```java
public static void deleteSession(String sessionId) throws IOException
```

Elimina una sesión de forma permanente borrando su archivo JSONL. También borra de forma recursiva el
directorio hermano `<sessionId>/` con las transcripciones de subagentes (si existe). Quien quiera un
borrado lógico debe usar `tagSession(id, "__hidden")` y filtrar al listar.

**Parámetros**:
- `sessionId`: UUID de la sesión a eliminar

**Lanza**:
- `IllegalArgumentException`: si `sessionId` no es un UUID válido
- `FileNotFoundException`: si no se encuentra el archivo de la sesión
- `IOException`: si falla el borrado (la limpieza del directorio de subagentes es de mejor esfuerzo y
  nunca hace fallar la llamada)

### deleteSession(String sessionId, Path directory)

```java
public static void deleteSession(
    String sessionId,
    Path directory
) throws IOException
```

Elimina una sesión limitándose a un directorio de proyecto concreto.

### forkSession(String sessionId)

```java
public static ForkSessionResult forkSession(String sessionId) throws IOException
```

Bifurca una sesión en una rama nueva con UUID nuevos.

**Devuelve**: `ForkSessionResult` con el UUID de la nueva sesión

**Lanza**:
- `IllegalArgumentException`: si `sessionId` no es un UUID válido
- `FileNotFoundException`: si no se encuentra el archivo de la sesión
- `IOException`: si falla la bifurcación

### forkSession(String sessionId, Path directory)

```java
public static ForkSessionResult forkSession(
    String sessionId,
    Path directory
) throws IOException
```

Bifurca una sesión limitándose a un directorio de proyecto concreto.

### forkSession(String sessionId, Path directory, String upToMessageId, String title)

```java
public static ForkSessionResult forkSession(
    String sessionId,
    @Nullable Path directory,
    @Nullable String upToMessageId,
    @Nullable String title
) throws IOException
```

Bifurca una sesión con un punto de truncado opcional y un título personalizado.

**Parámetros**:
- `sessionId`: UUID de la sesión de origen
- `directory`: directorio de proyecto (null busca en todos)
- `upToMessageId`: corta la transcripción en este UUID de mensaje (incluido); null lo copia todo
- `title`: título personalizado de la bifurcación; null lo deriva del original + " (fork)"

### listSessions(Path directory, Integer limit, int offset, boolean includeWorktrees)

```java
public static List<SDKSessionInfo> listSessions(
    Path directory,
    Integer limit,
    int offset,
    boolean includeWorktrees
)
```

Lista sesiones con soporte de paginación por offset.

**Parámetros**:
- `directory`: directorio de proyecto (null para todos los proyectos)
- `limit`: número máximo de sesiones a devolver
- `offset`: número de sesiones a omitir (para paginar)
- `includeWorktrees`: incluir sesiones de los worktrees de git

## Métodos respaldados por SessionStore

Estos métodos leen y escriben sesiones a través de un adaptador `SessionStore` en lugar del sistema de
archivos local `~/.claude/projects/`. Consulta la [guía del Session Store](./feature-session-store.md)
para la documentación completa de la función.

### projectKeyForDirectory(Path directory)

```java
public static String projectKeyForDirectory(@Nullable Path directory)
```

Calcula el `project_key` de `SessionStore` para un directorio usando el mismo realpath + normalización
NFC + saneado con hash djb2 que usa el CLI. Por defecto usa el directorio de trabajo actual cuando
`directory == null`.

**Devuelve**: la cadena de clave de proyecto saneada, apta para `SessionKey.projectKey()`.

### listSessionsFromStore(SessionStore, Path, Integer, int)

```java
public static List<SDKSessionInfo> listSessionsFromStore(
    SessionStore sessionStore,
    @Nullable Path directory,
    @Nullable Integer limit,
    int offset)
```

Lista las sesiones de un `SessionStore`. Usa la ruta rápida cuando
`store.implementsListSessionSummaries()` devuelve `true`; si no, recae en cargas por sesión con
concurrencia limitada (16).

**Lanza**: `IllegalStateException` si el store no implementa ni `listSessionSummaries()` ni
`listSessions()`.

### getSessionInfoFromStore(SessionStore, String, Path)

```java
public static @Nullable SDKSessionInfo getSessionInfoFromStore(
    SessionStore sessionStore, String sessionId, @Nullable Path directory)
```

Lee los metadatos de una sesión desde un store. Devuelve `null` para UUID no válidos, sesiones
inexistentes, sesiones laterales o sesiones sin resumen extraíble.

### getSessionMessagesFromStore(SessionStore, String, Path, Integer, int)

```java
public static List<SessionMessage> getSessionMessagesFromStore(
    SessionStore sessionStore, String sessionId,
    @Nullable Path directory, @Nullable Integer limit, int offset)
```

Lee la transcripción completa de la conversación de una sesión desde un store. Devuelve una lista
vacía para UUID no válidos o sesiones inexistentes.

### listSubagentsFromStore(SessionStore, String, Path)

```java
public static List<String> listSubagentsFromStore(
    SessionStore sessionStore, String sessionId, @Nullable Path directory)
```

Lista los IDs de subagente de una sesión enumerando las subclaves del store bajo
`subagents/agent-<id>`.

**Lanza**: `IllegalStateException` si el store no implementa `listSubkeys()`.

### getSubagentMessagesFromStore(SessionStore, String, String, Path, Integer, int)

```java
public static List<SessionMessage> getSubagentMessagesFromStore(
    SessionStore sessionStore, String sessionId, String agentId,
    @Nullable Path directory, @Nullable Integer limit, int offset)
```

Lee la transcripción de un subagente desde un store. La entrada sintética `agent_metadata` no se
devuelve como mensaje; se lee para rellenar el `parentToolUseId` de cada mensaje (el `tool_use` del
Agent que creó el subagente) y el `parentAgentId` (el subagente creador, en subagentes anidados).
Ambos son nulos cuando esa entrada falta o lleva ids que no son cadenas.

### renameSessionViaStore(SessionStore, String, String, Path)

```java
public static void renameSessionViaStore(
    SessionStore sessionStore, String sessionId, String title,
    @Nullable Path directory)
```

Añade una entrada `custom-title` a la sesión en el store.

**Lanza**: `IllegalArgumentException` si `sessionId` no es un UUID válido o si `title` está vacío o
solo contiene espacios.

### tagSessionViaStore(SessionStore, String, String, Path)

```java
public static void tagSessionViaStore(
    SessionStore sessionStore, String sessionId, @Nullable String tag,
    @Nullable Path directory)
```

Añade una entrada `tag`. Pasa `null` en `tag` para borrarla; las etiquetas se sanean en Unicode antes
de guardarse.

**Lanza**: `IllegalArgumentException` para UUID no válido o etiqueta que queda vacía tras el saneado.

### deleteSessionViaStore(SessionStore, String, Path)

```java
public static void deleteSessionViaStore(
    SessionStore sessionStore, String sessionId, @Nullable Path directory)
```

Elimina una sesión del store. No hace nada si el store no implementa `delete()` (apropiado para
back-ends WORM o de solo anexado).

### forkSessionViaStore(SessionStore, String, Path, String, String)

```java
public static ForkSessionResult forkSessionViaStore(
    SessionStore sessionStore, String sessionId, @Nullable Path directory,
    @Nullable String upToMessageId, @Nullable String title) throws java.io.IOException
```

Bifurca una sesión en una rama nueva con UUID nuevos a través del store. Ejecuta la misma
transformación de reasignación de UUID que la bifurcación en disco: una copia a nivel de almacenamiento
NO basta.

**Lanza**: `IllegalArgumentException` para UUID no válidos; `FileNotFoundException` si la sesión de
origen no se encuentra en el store.

### importSessionToStore(String, SessionStore, Path)

```java
public static void importSessionToStore(
    String sessionId, SessionStore sessionStore, @Nullable Path directory)
    throws java.io.IOException
```

Reproduce en un `SessionStore` la transcripción de una sesión guardada en el disco local. Sobrecarga de
conveniencia que usa `includeSubagents=true` y el tamaño de lote por defecto
(`TranscriptMirrorBatcher.MAX_PENDING_ENTRIES = 500`).

### importSessionToStore(String, SessionStore, Path, boolean, int)

```java
public static void importSessionToStore(
    String sessionId, SessionStore sessionStore, @Nullable Path directory,
    boolean includeSubagents, int batchSize) throws java.io.IOException
```

Versión completa con opciones explícitas.

**Parámetros**:
- `includeSubagents`: importa de forma recursiva `<sessionDir>/subagents/**/*.jsonl` y los adjuntos
  `.meta.json`
- `batchSize`: entradas por llamada a `store.append()`; los valores `≤ 0` usan el valor por defecto

**Lanza**: `IllegalArgumentException` para UUID no válido; `NoSuchFileException` si no se encuentra el
archivo de la sesión.

## Método de versión

### getVersion()

```java
public static String getVersion()
```

Obtiene la cadena de versión del SDK.

**Devuelve**: la versión (por ejemplo, "0.1.3-SNAPSHOT")

## Véase también
- [Guía de consultas simples](./feature-simple-queries.md)
- [Guía de servidores MCP](./feature-mcp-servers.md)
- [Guía de historial de sesiones](./feature-session-history.md)
- [Guía del Session Store](./feature-session-store.md)
