# Historial de sesiones

Lee y explora sesiones de conversación anteriores de Claude Code sin ejecutar el CLI.

> **Sobre esta traducción**: la documentación en inglés es la única autoritativa. Esta traducción puede estar desactualizada respecto al [original en inglés](../feature-session-history.md); si hay discrepancias, prevalece el inglés. Los bloques de código se mantienen idénticos al original y no se han traducido.

## Índice
- [Visión general](#visión-general)
- [Metadatos de la sesión](#metadatos-de-la-sesión)
- [Mensajes de la sesión](#mensajes-de-la-sesión)
- [Listar sesiones](#listar-sesiones)
- [Consultar una sola sesión](#consultar-una-sola-sesión)
- [Leer los mensajes de una sesión](#leer-los-mensajes-de-una-sesión)
- [Renombrar sesiones](#renombrar-sesiones)
- [Etiquetar sesiones](#etiquetar-sesiones)
- [Eliminar sesiones](#eliminar-sesiones)
- [Bifurcar sesiones](#bifurcar-sesiones)
- [Ejemplos](#ejemplos)
- [Buenas prácticas](#buenas-prácticas)

## Visión general

Claude Code guarda cada conversación como un archivo JSONL en `~/.claude/projects/`. La API de
historial de sesiones te permite:

- **Listar sesiones** de todos los proyectos o filtradas por un directorio de trabajo concreto
- **Leer mensajes** de cualquier sesión pasada: la transcripción completa de la conversación

Toda la lectura se hace directamente del disco, con independencia del CLI. No se crea ningún proceso.

**Rendimiento:** al listar solo se leen los primeros y los últimos 64 KB de cada archivo de sesión
(sin analizar todo el JSONL). El análisis completo solo se hace al recuperar mensajes con
`getSessionMessages`.

> **¿Buscas un back-end remoto o multihost?** Consulta [Session
> Store](./feature-session-store.md). Cada método de esta página tiene un equivalente `*FromStore`
> (lectura) o `*ViaStore` (mutación) en `ClaudeSDK` que opera contra un adaptador `SessionStore` (S3,
> Postgres, Redis, propio). Las API de disco local que se documentan aquí siguen siendo el camino
> canónico; las de SessionStore son aditivas y apuntan al mismo diseño en disco por portabilidad.

## Metadatos de la sesión

`SDKSessionInfo` contiene los metadatos de una sesión:

```java
record SDKSessionInfo(
    String sessionId,              // UUID identifying the session
    String summary,                // display title (custom title, AI title, lastPrompt, summary, or first prompt)
    long lastModified,             // last-modified time in milliseconds since epoch
    @Nullable Long fileSize,       // transcript size in bytes (null when read from a store's summary sidecar)
    @Nullable String customTitle,  // user-set custom title or AI-generated title (may be null)
    @Nullable String firstPrompt,  // first meaningful user prompt (may be null)
    @Nullable String gitBranch,    // git branch at end of session (may be null)
    @Nullable String cwd,          // working directory for the session (may be null)
    @Nullable String tag,          // user-set session tag (may be null)
    @Nullable Long createdAt       // creation time in ms since epoch from first entry's ISO timestamp (may be null)
)
```

El campo `summary` se resuelve por orden de prioridad: título personalizado > título de IA >
lastPrompt > resumen generado automáticamente > primer prompt. Un `customTitle`, `gitBranch` o `cwd`
vacío cuenta como ausente y se pasa a la siguiente fuente, igual que en el SDK de Python.

### Cómo se encuentra el primer prompt

`firstPrompt`, el resumen de respaldo, el título por defecto de una bifurcación y el pliegue de
`SessionSummary` usan todos una misma extracción, que sigue las reglas del SDK de Python desde la
0.2.6. Lee las entradas de usuario desde el principio de la transcripción y omite:

- los resultados de herramienta, las entradas `isMeta` y los resúmenes de compactación
  (`isCompactSummary`);
- el texto generado automáticamente: `<local-command-stdout>`, `<session-start-hook>`, `<tick>`,
  `<goal>`, `[Request interrupted by user…]`, y el texto que es solo una etiqueta
  `<ide_opened_file>` o `<ide_selection>`.

Se prueba cada bloque de texto de un mensaje, en orden, con los saltos de línea convertidos en
espacios. Un texto que lleva una etiqueta `<command-name>` también se omite, pero el primer nombre de
comando encontrado pasa a ser el respaldo cuando no se encuentra ningún otro prompt. El resultado se
corta en 200 caracteres más unos puntos suspensivos.

También hay un constructor retrocompatible sin `tag` ni `createdAt`.

## Mensajes de la sesión

`SessionMessage` contiene un único mensaje de la transcripción de una sesión:

```java
record SessionMessage(
    String type,                         // "user" or "assistant"
    String uuid,                         // unique message UUID
    String sessionId,                    // session ID this message belongs to
    Object message,                      // raw Anthropic API message (Map with role/content)
    @Nullable String parentToolUseId,    // spawning Agent tool_use id (subagent reads only)
    @Nullable String parentAgentId       // spawning subagent id (nested subagents only)
)
```

Solo se devuelven los mensajes de conversación de nivel superior: los mensajes laterales de uso de
herramientas, los mensajes meta y los de subagentes se filtran.

### Cómo se reconstruye la conversación

Una transcripción es un árbol: cada entrada nombra a su padre en `parentUuid`, y una conversación
reanudada o editada deja ramas. `getSessionMessages()` y `getSessionMessagesFromStore()` reconstruyen
la conversación de la misma forma, igual que el SDK de Python:

1. **Elegir la hoja.** Desde cada entrada a la que no apunta ninguna otra se retrocede hasta su
   entrada de usuario o de asistente más cercana, que pasa a ser una hoja candidata. Gana la candidata
   más reciente de la cadena principal (que no sea una entrada lateral, meta ni de equipo). Solo cuando
   no hay ninguna gana la candidata más reciente de cualquier tipo. Los indicadores `isSidechain`,
   `isMeta` y `teamName` se leen como Python evalúa la veracidad, así que un `teamName` vacío no es
   ningún equipo.
2. **Retroceder** desde la hoja a través de `parentUuid` hasta la raíz. Se omite una entrada cuyo
   `uuid` no es una cadena, y un `parentUuid` que no es una cadena no vacía cuenta como ausencia de
   padre.
3. **Recolocar las llamadas a herramientas en paralelo.** El CLI escribe una respuesta con varias
   llamadas a herramientas en paralelo como una entrada de asistente por llamada, todas con el mismo
   `message.id`, y escribe cada resultado bajo la entrada de su propia llamada. La transcripción se
   bifurca ahí, y el recorrido solo conserva la rama desde la que continuó el siguiente turno. Las
   llamadas y los resultados que omitió se recolocan justo después de la última entrada de la
   respuesta en la cadena: las llamadas por orden de marca de tiempo y después los resultados en el
   orden en que terminaron. Un resultado se asocia a su llamada a través de su padre,
   `sourceToolAssistantUUID` o `tool_use_id`, nunca entre agentes distintos, y nunca por segunda vez
   para una llamada a la que la cadena ya responde.

Así, un turno con llamadas a herramientas en paralelo se relee completo: cada llamada, y cada
resultado después de su llamada. `ParallelToolSessionReadsExample` ejecuta un turno así contra el CLI
real y comprueba ambos lectores. Las transcripciones de subagentes son lineales y no necesitan nada de
esto.

`parentToolUseId` y `parentAgentId` son siempre nulos en los resultados de `getSessionMessages()` /
`getSessionMessagesFromStore()`. Se rellenan en `getSubagentMessages()` /
`getSubagentMessagesFromStore()`: `parentToolUseId` es el id del bloque `tool_use` del Agent en la
sesión padre que creó el subagente, y `parentAgentId` nombra al subagente creador cuando un subagente
creó otro. Ambos proceden del archivo adjunto `agent-<agentId>.meta.json` del subagente (o, en
lecturas de store, de la entrada `agent_metadata` que hace sus veces), así que ambos son nulos cuando
esos metadatos faltan o no son utilizables. Todos los mensajes de una misma transcripción de
subagente llevan el mismo par.

### Acceder al contenido del mensaje

El campo `message` es un `Map<String, Object>` en bruto con el formato de cable de la Anthropic API:

```java
SessionMessage msg = ...;
if (msg.message() instanceof Map<?, ?> m) {
    Object content = m.get("content");
    if (content instanceof String text) {
        System.out.println(text);
    } else if (content instanceof List<?> blocks) {
        for (Object block : blocks) {
            if (block instanceof Map<?, ?> b && b.get("text") instanceof String t) {
                System.out.println(t);
            }
        }
    }
}
```

## Listar sesiones

### Todas las sesiones

```java
List<SDKSessionInfo> sessions = ClaudeSDK.listSessions();
```

Devuelve todas las sesiones de todos los proyectos, de la más reciente a la más antigua.

### Sesiones de un proyecto

```java
Path projectDir = Path.of("/my/project");
List<SDKSessionInfo> sessions = ClaudeSDK.listSessions(projectDir);
```

Filtra las sesiones cuyo directorio de trabajo coincide con `projectDir`. Primero se canonicaliza el
directorio (las rutas relativas se hacen absolutas, se resuelven los enlaces simbólicos y se
normaliza a Unicode NFC), así que una ruta con enlace simbólico, relativa o escrita en NFD encuentra
las mismas sesiones que la ruta real.

Cuando `projectDir` está dentro de un repositorio git con más de un worktree, se incluyen las
sesiones de todos los worktrees, igual que `listSessions(projectDir, null, true)`. Antes de la 0.2.6
esta sobrecarga solo leía el propio `projectDir`. Para conservar ese comportamiento, pasa `false`
como `includeWorktrees`:

```java
List<SDKSessionInfo> thisDirOnly = ClaudeSDK.listSessions(projectDir, null, false);
```

### Con límite y worktrees

```java
List<SDKSessionInfo> recent = ClaudeSDK.listSessions(
    Path.of("/my/project"),   // null for all projects
    10,                        // max 10 results
    true                       // include git worktrees
);
```

`includeWorktrees = true` ejecuta `git worktree list` e incluye las sesiones de todos los worktrees
del repositorio. Un directorio de proyecto se asocia a un worktree por su nombre exacto, así que
`/root/project` no recoge las sesiones de `/root/project-foo`. La coincidencia por prefijo solo se usa
para rutas tan largas que el CLI trunca el nombre del directorio y le añade un hash.

### Con paginación por offset

```java
// Page 1: first 50 sessions
List<SDKSessionInfo> page1 = ClaudeSDK.listSessions(
    Path.of("/my/project"), 50, 0, true);

// Page 2: next 50 sessions
List<SDKSessionInfo> page2 = ClaudeSDK.listSessions(
    Path.of("/my/project"), 50, 50, true);
```

## Consultar una sola sesión

Usa `getSessionInfo` para consultar una sesión por su ID sin recorrer todos los archivos de sesión.
Es más eficiente que `listSessions` cuando ya conoces el UUID de la sesión.

### Por ID de sesión (busca en todos los proyectos)

```java
SDKSessionInfo info = ClaudeSDK.getSessionInfo("550e8400-e29b-41d4-a716-446655440000");
if (info != null) {
    System.out.println("Session: " + info.summary());
    if (info.tag() != null) {
        System.out.println("Tag: " + info.tag());
    }
    if (info.createdAt() != null) {
        String created = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(info.createdAt()));
        System.out.println("Created: " + created);
    }
}
```

### Limitado a un directorio de proyecto

```java
SDKSessionInfo info = ClaudeSDK.getSessionInfo(
    "550e8400-e29b-41d4-a716-446655440000",
    Path.of("/my/project")
);
```

Devuelve `null` si no se encuentra la sesión, si es una sesión lateral o si no tiene un resumen
extraíble.

Con un directorio, la búsqueda prueba primero el proyecto de ese directorio y luego cada uno de sus
git worktrees. Una transcripción ausente o un archivo vacío de 0 bytes cuenta como «no está aquí»,
así que un archivo vacío que quede en un worktree no oculta la sesión real en otro.
`getSessionMessages`, `renameSession` y `tagSession` buscan del mismo modo.

## Leer los mensajes de una sesión

### Transcripción completa

```java
List<SessionMessage> messages = ClaudeSDK.getSessionMessages(sessionId);
```

Busca el UUID de la sesión en todos los directorios de proyecto.

### Limitado a un proyecto

```java
List<SessionMessage> messages = ClaudeSDK.getSessionMessages(
    sessionId,
    Path.of("/my/project")
);
```

### Con paginación

```java
// Skip first 20 messages, return next 10
List<SessionMessage> page = ClaudeSDK.getSessionMessages(
    sessionId,
    null,    // all projects
    10,      // limit
    20       // offset
);
```

La paginación se aplica a la conversación reconstruida, primero el offset: el resultado son los
mensajes de `offset` a `offset + limit - 1`. Un `limit` `null`, `0` o negativo significa sin límite.
Todos los lectores de sesiones y de subagentes, en disco y desde un `SessionStore`, paginan así, y los
mensajes recolocados de un turno con herramientas en paralelo cuentan para el límite.

## Renombrar sesiones

Renombra una sesión añadiendo una entrada de título personalizado a su archivo JSONL. El último
cambio de nombre siempre gana, así que es seguro llamarlo varias veces.

```java
// Rename by session ID (searches all projects)
ClaudeSDK.renameSession("550e8400-e29b-41d4-a716-446655440000", "My Feature Branch Session");

// Rename scoped to a specific project directory
ClaudeSDK.renameSession(
    "550e8400-e29b-41d4-a716-446655440000",
    "My Feature Branch Session",
    Path.of("/my/project")
);
```

**Restricciones:**
- `sessionId` debe ser un UUID válido (hexadecimal con guiones, en mayúsculas o minúsculas).
- `title` no puede quedar vacío tras quitar los espacios de los extremos.
- Lanza `FileNotFoundException` si no se encuentra el archivo JSONL de la sesión.
- Lanza `IOException` si falla la escritura del archivo.

**Dónde se escribe la entrada.** Con un directorio, el SDK prueba primero el proyecto de ese
directorio y luego el proyecto de cada uno de sus git worktrees, igual que `listSessions` y
`getSessionMessages`. Un archivo ausente o un archivo vacío de 0 bytes significa «no está aquí, sigue
buscando». El archivo se abre en modo de anexado y nunca se crea, así que la entrada siempre cae al
final actual del archivo, incluso mientras un CLI en ejecución anexa a la misma transcripción.
Cualquier otro error de E/S (permiso denegado, disco lleno) se lanza en lugar de tratarse como «no
encontrado».

Tras renombrar, `listSessions()` devuelve el nuevo título en los campos `summary` y `customTitle` de
`SDKSessionInfo`.

## Etiquetar sesiones

Etiqueta una sesión para filtrarla y organizarla. Pasa `null` para borrar una etiqueta existente. Las
etiquetas se sanean en Unicode antes de guardarse, por compatibilidad con el filtro del CLI.

```java
// Tag a session (searches all projects)
ClaudeSDK.tagSession("550e8400-e29b-41d4-a716-446655440000", "production");

// Clear a tag
ClaudeSDK.tagSession("550e8400-e29b-41d4-a716-446655440000", null);

// Tag scoped to a specific project directory
ClaudeSDK.tagSession(
    "550e8400-e29b-41d4-a716-446655440000",
    "staging",
    Path.of("/my/project")
);
```

**Restricciones:**
- `sessionId` debe ser un UUID válido.
- `tag` no puede quedar vacía tras el saneado Unicode y la eliminación de espacios (o `null` para
  borrarla).
- Las etiquetas con caracteres Unicode peligrosos (anchura cero, marcas direccionales, uso privado)
  se sanean automáticamente.
- Lanza `FileNotFoundException` si no se encuentra el archivo JSONL de la sesión.
- Lanza `IOException` si falla la escritura del archivo.
- La entrada se escribe igual que al renombrar: recurso a los worktrees y luego un anexado atómico
  que nunca crea el archivo.

**Seguridad en concurrencia:** si la sesión está abierta en el proceso del CLI, este absorbe en su
caché las entradas escritas por el SDK en el siguiente reanexado de metadatos. Gana la escritura más
reciente.

## Eliminar sesiones

Elimina una sesión de forma permanente borrando su archivo JSONL. El directorio hermano
`<sessionId>/` que contiene las transcripciones de subagentes también se borra de forma recursiva
(con el mejor esfuerzo; si no existe, no pasa nada).

```java
// Delete by session ID (searches all projects)
ClaudeSDK.deleteSession("550e8400-e29b-41d4-a716-446655440000");

// Delete scoped to a specific project directory
ClaudeSDK.deleteSession("550e8400-e29b-41d4-a716-446655440000", Path.of("/my/project"));
```

**Restricciones:**
- `sessionId` debe ser un UUID válido.
- Lanza `FileNotFoundException` si no se encuentra el archivo de la sesión.
- Para un borrado lógico, usa `tagSession(id, "__hidden")` y filtra al listar.

## Leer transcripciones de subagentes

Cuando una sesión crea subagentes (mediante la herramienta `Task` o definiciones de agente por
código), cada subagente escribe su propia transcripción en
`~/.claude/projects/<project>/<sessionId>/subagents/agent-<agentId>.jsonl`. Las transcripciones de
subagentes también pueden estar en directorios anidados como `subagents/workflows/<runId>/`.

```java
// Enumerate subagent IDs for a session
List<String> agentIds = ClaudeSDK.listSubagents(
    "550e8400-e29b-41d4-a716-446655440000");

// Or scoped to a specific project
List<String> agentIds = ClaudeSDK.listSubagents(
    "550e8400-e29b-41d4-a716-446655440000",
    Path.of("/my/project"));

// Read a subagent's full conversation
List<SessionMessage> messages = ClaudeSDK.getSubagentMessages(
    "550e8400-e29b-41d4-a716-446655440000",
    "abc123");

// With limit and offset
List<SessionMessage> page = ClaudeSDK.getSubagentMessages(
    "550e8400-e29b-41d4-a716-446655440000",
    "abc123",
    Path.of("/my/project"),
    50,    // limit (null or 0 = no limit)
    0);    // offset
```

**Comportamiento:**
- `listSubagents` recorre recursivamente el árbol `subagents/` buscando archivos que encajen con
  `agent-<id>.jsonl` y devuelve los IDs en el orden de iteración de los directorios.
- `getSubagentMessages` sigue los enlaces `parentUuid` desde la hoja para reconstruir la cadena. Las
  transcripciones de subagente son lineales (sin compactación ni ramas laterales), así que la lista
  devuelta es la conversación completa en orden cronológico.
- Las líneas JSONL corruptas se omiten en silencio.
- Los UUID no válidos, las sesiones inexistentes, los agentes inexistentes y los IDs de agente vacíos
  devuelven una lista vacía (nunca lanzan).

## Bifurcar sesiones

Bifurca una sesión en una rama nueva con UUID nuevos. Copia los mensajes de la transcripción de la
sesión de origen, reasignando cada UUID de mensaje y preservando la cadena `parentUuid`. Las sesiones
bifurcadas empiezan sin historial de deshacer.

```java
// Fork a session (searches all projects)
ForkSessionResult result = ClaudeSDK.forkSession("550e8400-e29b-41d4-a716-446655440000");
System.out.println("New session: " + result.sessionId());

// Fork scoped to a project directory
ForkSessionResult result = ClaudeSDK.forkSession(
    "550e8400-e29b-41d4-a716-446655440000",
    Path.of("/my/project")
);

// Fork from a specific message (truncate transcript)
ForkSessionResult result = ClaudeSDK.forkSession(
    "550e8400-e29b-41d4-a716-446655440000",
    null,                                       // search all projects
    "660e8400-e29b-41d4-a716-446655440001",    // slice transcript at this message
    "My Fork Title"                            // custom title (null = original + " (fork)")
);
```

**`ForkSessionResult`** contiene:
- `sessionId`: el UUID de la sesión bifurcada recién creada

**Restricciones:**
- `sessionId` y el opcional `upToMessageId` deben ser UUID válidos. Un `upToMessageId` vacío equivale
  a `null`: se copia toda la transcripción.
- Lanza `FileNotFoundException` si no se encuentra la sesión de origen.
- Lanza `IllegalArgumentException` si la sesión no tiene mensajes o si no se encuentra
  `upToMessageId`.

Sin `title`, la bifurcación toma el nombre del título personalizado del origen; si no, de su título
de IA y, si no, de su [primer prompt](#cómo-se-encuentra-el-primer-prompt), con `" (fork)"` añadido
(`"Forked session (fork)"` cuando no existe ninguno). La nueva transcripción se crea de forma
exclusiva y con modo `0600` (lectura y escritura solo para el propietario) en los sistemas de
archivos con permisos POSIX.

`forkSession()` produce una copia sin conexión, sin ejecutar el CLI. Para *reanudar* desde un punto
anterior y seguir hablando, usa la reanudación con truncado.

## Reanudación con truncado

`resumeSessionAt` carga la conversación reanudada solo hasta un UUID de entrada de la transcripción
dado (incluido), descartando todo lo posterior. Junto con `forkSession(true)`, ramifica en una sesión
nueva y deja intacta la original.

`resumeDropsTurn` hace que ese truncado sea **seguro**. Dale el UUID del prompt de usuario cuyo turno
pretendes descartar, y el CLI valida al cargar que toda entrada posterior al punto de bifurcación
pertenece a ese turno, negándose en caso contrario. Sin él, un mensaje de usuario en cola o una
notificación de tarea en segundo plano que la sesión absorbió a mitad de turno, y que nunca viste, se
descartaría en silencio.

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .cwd(projectDir)
    .resume(sessionId)
    .forkSession(true)               // branch; leave the source session intact
    .resumeSessionAt(keepAtUuid)     // last transcript entry of the turn to keep
    .resumeDropsTurn(nextPromptUuid) // prompt UUID of the turn being discarded
    .build();

for (Message msg : ClaudeSDK.query("Reply with exactly: three", options)) {
    if (msg instanceof ResultMessage r) {
        System.out.println("Forked session: " + r.sessionId());
    }
}
```

**Cómo elegir los dos UUID.** Pon en `resumeSessionAt` la *última* entrada de la transcripción del
turno que vas a conservar —sea del tipo que sea— y en `resumeDropsTurn` el UUID del prompt del turno
inmediatamente posterior. Ambos se pueden leer de
`ClaudeSDK.getSessionMessages(sessionId, cwd)`: localiza la entrada que quieres conservar y toma el
`uuid()` del siguiente `SessionMessage` cuyo `type()` sea `"user"`. Un `AssistantMessage.uuid()`
observado en vivo también sirve como punto de bifurcación.

Ten en cuenta que, con salida estructurada (`outputFormat`) o herramientas MCP que terminan el turno,
un turno conservado termina en entradas *posteriores* a su último mensaje del asistente, así que en
esos casos bifurcar en el UUID del asistente se rechaza por diseño.

**Gestionar un rechazo.** El rechazo llega como una excepción cuyo mensaje contiene
`Resume rejected by --resume-drops-turn:`:

```java
try {
    ClaudeSDK.query(prompt, options);
} catch (ClaudeSDKException e) {
    if (String.valueOf(e.getMessage()).contains("Resume rejected by --resume-drops-turn:")) {
        // Deterministic — the transcript is not what we assumed.
        // Clear the fork target and resume plainly; do not retry as-is.
    }
}
```

Trátalo como determinista: repetir la misma petición fallará igual. Deja `resumeDropsTurn` sin
definir para conservar el comportamiento de truncado antiguo, sin validación.

Ambas opciones también se reenvían al reanudar desde un `SessionStore`. Consulta
[`TruncatingResumeExample`](../../examples/src/main/java/examples/TruncatingResumeExample.java) para
una demostración completa y ejecutable.

## Ejemplos

### Ejemplo 1: listar sesiones recientes

```java
List<SDKSessionInfo> sessions = ClaudeSDK.listSessions(null, 5, true);

DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(ZoneId.systemDefault());

for (SDKSessionInfo session : sessions) {
    String time = fmt.format(Instant.ofEpochMilli(session.lastModified()));
    System.out.printf("[%s] %s%n", time, session.summary());
    System.out.printf("  id:  %s%n", session.sessionId());
    if (session.cwd() != null) {
        System.out.printf("  cwd: %s%n", session.cwd());
    }
    if (session.gitBranch() != null) {
        System.out.printf("  git: %s%n", session.gitBranch());
    }
}
```

### Ejemplo 2: sesiones del proyecto actual

```java
Path cwd = Path.of(System.getProperty("user.dir"));
List<SDKSessionInfo> sessions = ClaudeSDK.listSessions(cwd);

System.out.printf("Found %d session(s) for: %s%n", sessions.size(), cwd);
for (SDKSessionInfo session : sessions) {
    String sizeStr = (session.fileSize() != null)
            ? String.format("%.1f KB", session.fileSize() / 1024.0) : "N/A";
    System.out.printf("  %s (%s)%n", session.summary(), sizeStr);
}
```

### Ejemplo 3: leer los mensajes de la sesión más reciente

```java
List<SDKSessionInfo> sessions = ClaudeSDK.listSessions(null, 1, false);
if (sessions.isEmpty()) {
    System.out.println("No sessions found.");
    return;
}

SDKSessionInfo recent = sessions.get(0);
List<SessionMessage> messages = ClaudeSDK.getSessionMessages(recent.sessionId());

System.out.printf("Session: %s (%d messages)%n",
    recent.summary(), messages.size());

for (SessionMessage msg : messages) {
    System.out.printf("%n[%s]%n", msg.type().toUpperCase());
    if (msg.message() instanceof Map<?, ?> m) {
        Object content = m.get("content");
        if (content instanceof String text) {
            System.out.println(text);
        } else if (content instanceof List<?> blocks && !blocks.isEmpty()) {
            Object first = ((List<?>) blocks).get(0);
            if (first instanceof Map<?, ?> b && b.get("text") instanceof String t) {
                System.out.println(t);
            }
        }
    }
}
```

### Ejemplo 4: encontrar una sesión por palabra clave del prompt

```java
List<SDKSessionInfo> all = ClaudeSDK.listSessions();

List<SDKSessionInfo> matching = all.stream()
    .filter(s -> s.summary().toLowerCase().contains("refactor"))
    .toList();

System.out.println("Found " + matching.size() + " sessions about refactoring");
```

### Ejemplo 5: renombrar la sesión más reciente

```java
List<SDKSessionInfo> sessions = ClaudeSDK.listSessions(null, 1, false);
if (!sessions.isEmpty()) {
    String sessionId = sessions.get(0).sessionId();
    ClaudeSDK.renameSession(sessionId, "Important: Production Bug Fix");
    System.out.println("Renamed session " + sessionId);
}
```

### Ejemplo 6: etiquetar sesiones por fase del proyecto

```java
// Tag a session after a query completes, using the result's session ID
List<Message> messages = ClaudeSDK.query(prompt, options);
for (Message msg : messages) {
    if (msg instanceof ResultMessage result) {
        ClaudeSDK.tagSession(result.sessionId(), "sprint-42");
        break;
    }
}
```

### Ejemplo 7: borrar una etiqueta

```java
// Retrieve a session and clear its tag
List<SDKSessionInfo> sessions = ClaudeSDK.listSessions();
for (SDKSessionInfo session : sessions) {
    if ("old-tag".equals(session.customTitle())) {
        ClaudeSDK.tagSession(session.sessionId(), null);
    }
}
```

## Buenas prácticas

### Filtra por directorio siempre que puedas

```java
// Efficient: scoped to one project
ClaudeSDK.listSessions(Path.of("/my/project"));

// Less efficient: scans all projects
ClaudeSDK.listSessions();
```

### Comprueba si el resultado está vacío

```java
List<SDKSessionInfo> sessions = ClaudeSDK.listSessions(dir);
if (sessions.isEmpty()) {
    // No sessions yet — run Claude Code in this directory first
}
```

### Qué pasa si falta CLAUDE_CONFIG_DIR

Por defecto, las sesiones se guardan en `~/.claude/projects/`. Si la variable de entorno
`CLAUDE_CONFIG_DIR` está definida, tiene prioridad sobre esa ubicación. El SDK respeta esa variable
automáticamente, y la normaliza a NFC igual que hace con los directorios de proyecto.

### Usa limit para evitar conjuntos de resultados enormes

```java
// Return only the 20 most recent
List<SDKSessionInfo> recent = ClaudeSDK.listSessions(null, 20, false);
```

## Véase también

- [Referencia de la API: ClaudeSDK](./api-claude-sdk.md#métodos-de-historial-de-sesiones): firmas de los métodos
- [Session Store](./feature-session-store.md): equivalentes respaldados por un store externo (`*FromStore`/`*ViaStore`) y la integración de réplica en escritura
- [Ejemplo de listado de sesiones](../../examples/src/main/java/examples/SessionListingExample.java): ejemplo completo y ejecutable
- [Conversaciones interactivas](./feature-interactive-conversations.md): gestionar sesiones en curso
