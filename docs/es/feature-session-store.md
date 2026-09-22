# Session Store (réplica externa de transcripciones)

Replica las transcripciones de sesión de Claude Code a un store externo (S3, Postgres, Redis, tu
propio back-end) para que las sesiones perduren más allá del disco local y puedan reanudarse desde
cualquier sitio.

> **Sobre esta traducción**: la documentación en inglés es la única autoritativa. Esta traducción puede estar desactualizada respecto al [original en inglés](../feature-session-store.md); si hay discrepancias, prevalece el inglés. Los bloques de código se mantienen idénticos al original y no se han traducido.

## Índice
- [Visión general](#visión-general)
- [Cuándo usar un SessionStore](#cuándo-usar-un-sessionstore)
- [Inicio rápido](#inicio-rápido)
- [La interfaz SessionStore](#la-interfaz-sessionstore)
- [API síncrona frente a asíncrona](#api-síncrona-frente-a-asíncrona)
- [Configurar el ejecutor asíncrono](#configurar-el-ejecutor-asíncrono)
- [Adaptadores de referencia incluidos](#adaptadores-de-referencia-incluidos)
- [API de lectura respaldadas por SessionStore](#api-de-lectura-respaldadas-por-sessionstore)
- [Mutaciones respaldadas por SessionStore](#mutaciones-respaldadas-por-sessionstore)
- [Reanudar desde un store](#reanudar-desde-un-store)
- [Errores de réplica](#errores-de-réplica)
- [Modo de volcado (batched frente a eager)](#modo-de-volcado-batched-frente-a-eager)
- [Importar sesiones locales a un store](#importar-sesiones-locales-a-un-store)
- [Conjunto de pruebas de conformidad](#conjunto-de-pruebas-de-conformidad)
- [Piezas internas de ejecución](#piezas-internas-de-ejecución)
- [Buenas prácticas](#buenas-prácticas)
- [Referencia de la API](#referencia-de-la-api)

## Visión general

Por defecto, el CLI de Claude Code escribe cada sesión como un archivo JSONL en
`~/.claude/projects/`. Además, el SDK puede replicar cada línea de la transcripción a un store
externo de tu elección, algo útil para:

- **Sesiones largas y duraderas**: el disco local es efímero en plataformas serverless o con
  autoescalado.
- **Reanudación entre hosts**: empieza una sesión en el host A y reanúdala en el host B.
- **Retención para auditoría o cumplimiento**: aplica tus propias políticas de TTL (ciclo de vida de
  S3, particiones de Postgres, TTL de Redis).
- **Despliegues multiinquilino**: delimita las transcripciones por `project_key` para aislar
  inquilinos.

El SDK incluye:

- La interfaz `SessionStore` (variantes síncrona y asíncrona)
- El adaptador de referencia `InMemorySessionStore`
- Integración de réplica en tiempo de ejecución (transparente: define `sessionStore` en las options y
  el SDK se encarga del resto)
- `importSessionToStore()` para migrar sesiones ya existentes en disco

La transcripción en disco local siempre se escribe primero; la réplica es una vía secundaria de
durabilidad. Los fallos de réplica nunca bloquean una sesión: aparecen como un `MirrorErrorMessage`
no fatal.

## Cuándo usar un SessionStore

| Escenario | Recomendación |
|---|---|
| CLI de un solo usuario en una estación de trabajo | No te compliques: el JSONL local basta |
| Servidor de larga duración, sesiones que abarcan varias peticiones | Usa un `SessionStore` |
| Cumplimiento / retención regulada | Usa un `SessionStore` con políticas nativas de ciclo de vida |
| Flota de varios hosts / autoescalado en la nube | Usa un `SessionStore` para que cualquier host pueda reanudar |
| Auditoría / replay sobre muchas sesiones | Usa un `SessionStore` para consultas centralizadas |

## Inicio rápido

```java
import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.types.session.InMemorySessionStore;

InMemorySessionStore store = new InMemorySessionStore();

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .sessionStore(store)            // mirror every transcript line here
    .build();

ClaudeSDK.query("Hello!", options);
// All transcript entries from this turn are now in `store`
```

El SDK añade `--session-mirror` a la invocación del CLI, separa los marcos `transcript_mirror` de la
salida estándar del CLI y los reenvía por lotes a `store.append(...)`.

Para reanudar desde el store en otro host:

```java
ClaudeAgentOptions resumeOptions = ClaudeAgentOptions.builder()
    .sessionStore(store)
    .resume("previous-session-uuid")
    .build();

ClaudeSDK.query("Continue where we left off", resumeOptions);
```

El SDK carga la transcripción almacenada en un `CLAUDE_CONFIG_DIR` temporal para que el subproceso del
CLI retome la conversación.

## La interfaz SessionStore

`in.vidyalai.claude.sdk.types.session.SessionStore` es una interfaz de Java. Dos métodos son
obligatorios; el resto son opcionales, con indicadores `implements*()` para que quien llama detecte
qué hay soportado sin `instanceof`.

### Métodos obligatorios

```java
void append(SessionKey key, List<SessionStoreEntry> entries);

@Nullable
List<SessionStoreEntry> load(SessionKey key);
```

- `append`: replica un lote de entradas de la transcripción. Se llama DESPUÉS de que la escritura en
  disco local tenga éxito, así que la durabilidad ya está garantizada localmente. Los adaptadores
  deben tratar `entry.uuid()` como clave de idempotencia (las entradas sin `uuid`, como el título
  personalizado o la etiqueta, deben añadirse sin deduplicar).
- `load`: devuelve todas las entradas de una clave (profundamente iguales a lo que se añadió; no hace
  falta igualdad byte a byte). Devuelve `null` para una clave nunca escrita.

### Métodos opcionales (por defecto lanzan `UnsupportedOperationException`)

```java
default List<SessionStoreListEntry> listSessions(String projectKey);
default List<SessionSummaryEntry> listSessionSummaries(String projectKey);
default void delete(SessionKey key);
default List<String> listSubkeys(SessionListSubkeysKey key);
```

### Sondas de capacidades

```java
default boolean implementsListSessions() { return false; }
default boolean implementsListSessionSummaries() { return false; }
default boolean implementsDelete() { return false; }
default boolean implementsListSubkeys() { return false; }
```

Sobrescríbelos para devolver `true` cuando implementes el método opcional correspondiente. El SDK usa
estas sondas (y no `try/catch`) para decidir si llama al método opcional.

### Tipos de clave

```java
public record SessionKey(
    String projectKey,             // caller-defined scope (default: sanitized cwd)
    String sessionId,              // session UUID
    @Nullable String subpath        // null for main; "subagents/agent-x" for subagent
);

public record SessionListSubkeysKey(String projectKey, String sessionId);

public record SessionStoreListEntry(String sessionId, long mtime);

public record SessionSummaryEntry(String sessionId, long mtime, Map<String, Object> data);
```

`SessionStoreEntry` es una envoltura fina sobre `Map<String, Object>` que exige un campo `type`; todo
lo demás pasa de forma opaca:

```java
SessionStoreEntry entry = SessionStoreEntry.of(Map.of(
    "type", "user",
    "uuid", "u1",
    "message", Map.of(
        "content", List.of(Map.of("type", "text", "text", "Hello"))),
    "timestamp", "2026-04-27T00:00:00Z"
));

entry.type();      // "user"
entry.uuid();      // "u1"
entry.timestamp(); // "2026-04-27T00:00:00Z"
entry.<String>get("custom_field"); // typed convenience accessor
entry.asMap();     // unmodifiable map view
```

## API síncrona frente a asíncrona

Todos los métodos de `SessionStore` tienen variantes síncrona y `*Async` (que devuelven
`CompletableFuture`):

```java
// Sync (required to implement; or default to *Async().join() if you only override async)
void append(SessionKey key, List<SessionStoreEntry> entries);

// Async with default executor
default CompletableFuture<Void> appendAsync(SessionKey key, List<SessionStoreEntry> entries);

// Async with explicit executor
default CompletableFuture<Void> appendAsync(SessionKey key, List<SessionStoreEntry> entries, Executor executor);
```

El batcher interno de réplica y el materializador de reanudación llaman a las variantes `*Async`, de
modo que los adaptadores con clientes no bloqueantes nativos (AWS SDK v2 async, R2DBC, Lettuce
reactive) pueden sobrescribir los métodos `*Async` y conservar el paralelismo de extremo a extremo.

### Delegación por defecto

- Si solo sobrescribes los métodos **síncronos** (el caso típico de JDBC, Jedis, S3 SDK v1
  bloqueante), los `*Async` por defecto envuelven tus llamadas síncronas en
  `CompletableFuture.supplyAsync(...)` sobre el ejecutor configurado (un hilo por tarea; virtual en
  Java 21+).
- Si solo sobrescribes los métodos **asíncronos** (recomendado para AWS SDK v2 async / Lettuce
  reactive / R2DBC), implementa el método síncrono como `appendAsync(key, entries).join()` para que
  ambos puntos de llamada funcionen.

```java
public class S3AsyncStore implements SessionStore {
    private final S3AsyncClient s3;

    @Override
    public void append(SessionKey key, List<SessionStoreEntry> entries) {
        appendAsync(key, entries).join();
    }

    @Override
    public CompletableFuture<Void> appendAsync(SessionKey key, List<SessionStoreEntry> entries) {
        // Native async — no thread hop
        return s3.putObject(/* ... */).thenApply(r -> null);
    }

    @Override
    public List<SessionStoreEntry> load(SessionKey key) { /* ... */ }
}
```

## Configurar el ejecutor asíncrono

Por defecto, las envolturas asíncronas ejecutan **una tarea por hilo**, en hilos llamados
`session-store-<n>`. En Java 21+ son hilos virtuales; en Java 17-20 el SDK recae en hilos de
plataforma daemon de un pool en caché sin límite. El SDK apunta a Java 17 y elige la mejor opción en
tiempo de ejecución, así que obtienes hilos virtuales sin exigirlos.

Puedes sustituirlo una vez al arrancar, con `SessionStoreExecutor`:

```java
import in.vidyalai.claude.sdk.types.session.SessionStoreExecutor;

// Your own virtual-thread executor (needs Java 21+ in *your* project)
ExecutorService mine = Executors.newThreadPerTaskExecutor(
    Thread.ofVirtual().name("my-store-", 0).factory());
SessionStoreExecutor.setDefault(mine);

// Or a bounded platform-thread pool
SessionStoreExecutor.setDefault(Executors.newFixedThreadPool(8));

// Reset to the built-in default
SessionStoreExecutor.reset();
```

También puedes pasar un ejecutor por llamada:

```java
store.appendAsync(key, entries, customExecutor)
```

El ejecutor configurado lo usan todos los `*Async` por defecto que no reciben un ejecutor explícito.
Los adaptadores que sobrescriben `*Async` directamente lo eluden por completo: el ejecutor solo se
aplica a la ruta que convierte de síncrono a asíncrono.

## Adaptadores de referencia incluidos

### `InMemorySessionStore`

Una implementación en memoria, segura entre hilos, adecuada para pruebas y prototipos:

```java
import in.vidyalai.claude.sdk.types.session.InMemorySessionStore;

InMemorySessionStore store = new InMemorySessionStore();

// All optional methods implemented
store.implementsListSessions();         // true
store.implementsListSessionSummaries(); // true
store.implementsDelete();               // true
store.implementsListSubkeys();          // true

// Test helpers
store.size();             // count of main-transcript sessions
store.snapshotSummaries();// LinkedHashMap snapshot of summary sidecars
store.clear();            // wipe everything
```

Mantiene un archivo adjunto incremental de `SessionSummaryEntry` dentro de `append()`, así que
`listSessionSummaries()` se ejecuta en O(1) y nunca relee transcripciones.

### Helper de ruta → clave

`InMemorySessionStore.filePathToSessionKey(filePath, projectsDir)` es un helper estático que asigna
una ruta de transcripción en disco de vuelta a una `SessionKey`:

```java
SessionKey k = InMemorySessionStore.filePathToSessionKey(
    "/home/u/.claude/projects/myproj/abc-123.jsonl",
    "/home/u/.claude/projects");
// k = SessionKey("myproj", "abc-123", null)

SessionKey sub = InMemorySessionStore.filePathToSessionKey(
    "/home/u/.claude/projects/myproj/abc-123/subagents/agent-x.jsonl",
    "/home/u/.claude/projects");
// sub = SessionKey("myproj", "abc-123", "subagents/agent-x")
```

Devuelve `null` para rutas fuera de `projectsDir` o con diseños desconocidos. Lo usa internamente el
batcher de réplica y se expone para implementaciones de adaptador que necesiten la misma
correspondencia.

### Adaptadores de producción (S3, Redis, Postgres, …)

El SDK no incluye adaptadores de producción: dependen de bibliotecas cliente pesadas (AWS SDK,
Lettuce, JDBC, R2DBC) que no queremos como dependencias transitivas. Implementa el tuyo y valídalo con
`SessionStoreConformance` (véase más abajo). El protocolo es pequeño y estable.

## API de lectura respaldadas por SessionStore

Lee sesiones directamente de un store sin involucrar al CLI:

```java
import in.vidyalai.claude.sdk.ClaudeSDK;

// List all sessions in the store for the current cwd
List<SDKSessionInfo> sessions =
    ClaudeSDK.listSessionsFromStore(store, /* directory */ null, /* limit */ 50, /* offset */ 0);

// Single-session metadata
SDKSessionInfo info = ClaudeSDK.getSessionInfoFromStore(store, sessionId, null);

// Full transcript
List<SessionMessage> messages =
    ClaudeSDK.getSessionMessagesFromStore(store, sessionId, null, null, 0);

// Subagent transcript discovery + reading
List<String> agentIds = ClaudeSDK.listSubagentsFromStore(store, sessionId, null);
List<SessionMessage> subAgent =
    ClaudeSDK.getSubagentMessagesFromStore(store, sessionId, agentIds.get(0), null, null, 0);

// Each message is attributed to the Agent tool_use that spawned the subagent,
// read from the mirrored `agent_metadata` entry (null if it is absent).
String spawnedBy = subAgent.get(0).parentToolUseId();
```

`listSessionsFromStore` tiene una ruta rápida cuando el store implementa `listSessionSummaries`: una
llamada de resúmenes por lotes más una enumeración barata de `listSessions` para rellenar los huecos
de las sesiones con adjuntos ausentes o desactualizados. Cuando `listSessionSummaries` no está
implementado, recae en un `loadAsync()` por sesión, **limitado a 16 llamadas concurrentes** (igual que
el SDK de Python), para que los listados de proyectos grandes no agoten los pools de conexiones del
adaptador.

Si `listSessions` y `listSessionSummaries` están ambos sin implementar, la llamada lanza
`IllegalStateException`. Los fallos de `loadAsync` del adaptador degradan filas individuales a
entradas de resumen vacío en lugar de tumbar toda la lista.

## Mutaciones respaldadas por SessionStore

La misma forma que las API de mutación en disco, pero escribiendo en el store:

```java
ClaudeSDK.renameSessionViaStore(store, sessionId, "My New Title", null);
ClaudeSDK.tagSessionViaStore(store, sessionId, "important", null);
ClaudeSDK.tagSessionViaStore(store, sessionId, null, null);   // clear tag
ClaudeSDK.deleteSessionViaStore(store, sessionId, null);

ForkSessionResult fork = ClaudeSDK.forkSessionViaStore(
    store, sessionId, /* directory */ null,
    /* upToMessageId */ null,                  // null copies full transcript
    /* title */ "My Fork");
```

Por dentro:

- `renameSessionViaStore` añade una entrada `custom-title`.
- `tagSessionViaStore` añade una entrada `tag`; `null` la borra mediante una cadena vacía.
- `deleteSessionViaStore` no hace nada si el store no implementa `delete()` (apropiado para back-ends
  WORM o de solo anexado, como S3 puro).
- `forkSessionViaStore` ejecuta la misma transformación de reasignación de UUID que la bifurcación en
  disco (el `SessionMutations.buildForkLines` compartido): una copia a nivel de almacenamiento NO
  basta.

`listSubagentsFromStore` requiere `listSubkeys()` y, si no, lanza `IllegalStateException`.

## Reanudar desde un store

Cuando `options.sessionStore` se define junto con `options.resume` (o
`options.continueConversation`), el SDK:

1. Llama a `store.load()` para el ID de sesión solicitado (o, en el caso de `continueConversation`,
   elige la sesión no lateral modificada más recientemente mediante `store.listSessions()`).
2. Escribe las entradas en un directorio temporal dispuesto exactamente como `~/.claude/`.
3. Siembra el directorio temporal desde tu directorio de configuración real para que el subproceso
   pueda autenticarse y comportarse como de costumbre: `.credentials.json` (con `refreshToken`
   eliminado), `.claude.json` y tus `settings.json` / `cowork_settings.json` de usuario. Consulta
   [Qué se siembra](#qué-se-siembra).
4. Materializa desde el store las transcripciones de subagente y los adjuntos `.meta.json` (cuando
   `listSubkeys` está implementado).
5. Lanza el CLI con `CLAUDE_CONFIG_DIR=<temp dir>` para que reanude desde el disco local como
   siempre.
6. Limpia el directorio temporal al desconectar (con reintentos ante bloqueos transitorios del
   antivirus o el indexador de Windows).

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .sessionStore(store)
    .resume(previousSessionId)
    .loadTimeoutMs(60_000)        // per-call timeout for store.load() / listSubkeys()
    .build();
```

La opción `loadTimeoutMs` (por defecto 60 000) acota cada llamada individual al store durante la
materialización; si un adaptador no responde dentro de esa ventana, la consulta falla rápido con un
error claro en lugar de dejar colgado el iterador.

### Qué se siembra

Como el subproceso se ejecuta bajo un `CLAUDE_CONFIG_DIR` redirigido, de otro modo no vería ninguna de
tus configuraciones. El SDK copia cuatro archivos desde el directorio de configuración de quien llama
—resuelto como `options.env["CLAUDE_CONFIG_DIR"]` → el entorno del proceso → `~/.claude` (el
`.claude.json` está en `$CLAUDE_CONFIG_DIR/.claude.json` cuando se define, y si no en
`~/.claude.json`, *no* en `~/.claude/.claude.json`)—:

| Archivo | Por qué importa |
|------|----------------|
| `.credentials.json` | Credenciales OAuth, con `claudeAiOauth.refreshToken` eliminado |
| `.claude.json` | Estado del CLI a nivel de usuario |
| `settings.json` | `apiKeyHelper`, además de tus `env`, `hooks` y `permissions` |
| `cowork_settings.json` | El nombre alternativo de archivo de ajustes que se lee en modo cowork-plugins |

Sembrar `settings.json` importa más de lo que parece: `apiKeyHelper` es un cuarto mecanismo de
autenticación, junto al archivo de credenciales, el Llavero de macOS y las variables de entorno. Antes
de la v0.1.23 no se copiaba, así que un host que se autenticaba únicamente con `apiKeyHelper` fallaba
con **«Not logged in»** en cuanto reanudaba desde un store.

Ambos archivos de ajustes pasan por una transformación que descarta solo las claves que se comportan
mal bajo un directorio de configuración redirigido:

- `enabledPlugins` y `extraKnownMarketplaces`: se reconcilian con la caché temporal de plugins, siempre
  vacía, e instalarían por red todos los marketplaces declarados en cada reanudación.
- `env.CLAUDE_CONFIG_DIR`: apuntaría las lecturas de configuración del subproceso de vuelta fuera del
  directorio temporal.

Todo lo demás se conserva. Se tolera un BOM UTF-8 (PowerShell escribe uno), y el contenido que no sea
UTF-8 válido, o que no se analice como objeto JSON, se copia byte a byte para que el subproceso vea
exactamente lo que habría leído el CLI. Los archivos se escriben solo para el propietario (`0600`)
dentro del directorio temporal, también restringido al propietario (`0700`).

La siembra es de mejor esfuerzo: un archivo que no se pueda leer por cualquier motivo que no sea «no
existe» —un error de permisos, o un directorio o FIFO donde se esperaba un archivo— se registra y se
omite, en vez de abortar una reanudación que de otro modo habría funcionado. Una copia que falla a
medias elimina el destino parcial para que el subproceso no interprete mal un archivo truncado.

### Comprobaciones de validación

Antes de cualquier trabajo con subprocesos, el SDK rechaza las combinaciones no válidas:

- `continueConversation + sessionStore` requiere `store.implementsListSessions()`.
- `sessionStore + enableFileCheckpointing` se rechaza: los checkpoints son solo de disco local y
  divergirían de la transcripción replicada.

Estos casos lanzan `IllegalArgumentException` de inmediato.

## Errores de réplica

Los fallos al añadir en la réplica no son fatales: la transcripción en disco local ya es duradera, así
que la sesión continúa sin verse afectada. El SDK reintenta cada lote hasta 3 veces con una espera de
`[200ms, 800ms]`, luego lo descarta y expone un `MirrorErrorMessage` en tu flujo de mensajes:

```java
import in.vidyalai.claude.sdk.types.message.MirrorErrorMessage;

for (Message msg : ClaudeSDK.query("Hello", options)) {
    switch (msg) {
        case MirrorErrorMessage err -> {
            // Non-fatal — log and consider importing the local file later
            System.err.println("Mirror error for " + (err.key() != null
                    ? err.key().sessionId() : "<unknown>")
                    + ": " + err.error());
        }
        case AssistantMessage a -> System.out.println(a.getTextContent());
        // ... other cases
        default -> { /* ignore */ }
    }
}
```

`MirrorErrorMessage` es miembro de la interfaz sellada `Message` (junto a `AssistantMessage`,
`SystemMessage`, etc.): `subtype` es siempre `"mirror_error"`, `error` es el mensaje del fallo y `key`
(que admite null) es la `SessionKey` a la que apuntaba el lote fallido.

Los tiempos de espera NO se reintentan (la llamada en vuelo todavía puede llegar; un reintento lanzaría
un duplicado concurrente). Los adaptadores deben deduplicar por `entry.uuid()` para que un reintento
tras un éxito parcial sea seguro frente a duplicados.

## Modo de volcado (batched frente a eager)

Por defecto, el `TranscriptMirrorBatcher` almacena todos los marcos `transcript_mirror` y los vuelca
una vez por turno (en el mensaje `result`) o cuando el búfer pendiente supera
`MAX_PENDING_ENTRIES=500` entradas / `MAX_PENDING_BYTES=1 MiB`. Así la latencia del adaptador queda
fuera de la ruta caliente del streaming, y es la elección correcta para casi cualquier despliegue.

La opción `sessionStoreFlush` te permite pasar a réplica inmediata cuando necesitas que las entradas
lleguen al store con latencia por debajo del segundo:

```java
import in.vidyalai.claude.sdk.types.session.SessionStoreFlushMode;

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .sessionStore(store)
    .sessionStoreFlush(SessionStoreFlushMode.EAGER)
    .build();
```

| Modo | Cuándo se vuelcan las entradas | Úsalo cuando |
|---|---|---|
| `BATCHED` (por defecto) | Una vez por mensaje `result` O cuando lo pendiente supera 500 entradas / 1 MiB | Casi todas las cargas de producción: mantiene la latencia del adaptador fuera de la ruta caliente |
| `EAGER` | Drenaje en segundo plano programado tras cada marco encolado | Streaming de transcripciones en vivo hacia clientes, canalizaciones de auditoría en tiempo real, turnos muy grandes en los que no puedes esperar al `result` |

`EAGER` pone a cero los umbrales de pendiente del batcher: cada marco encolado programa un volcado en
segundo plano mediante el `SessionStoreExecutor` configurado (un hilo con nombre por tarea; virtual en
Java 21+). Las adiciones siguen serializadas en el orden de encolado; un adaptador lento no detendrá el
bucle de lectura, pero verá marcos agrupados mientras esté ocupado. La opción se ignora cuando
`sessionStore` no está definido.

## Importar sesiones locales a un store

Migra sesiones ya existentes en disco a un store, o pon el store al día después de que un
`MirrorErrorMessage` haya revelado un hueco:

```java
ClaudeSDK.importSessionToStore(sessionId, store, /* directory */ null);
// or with explicit options:
ClaudeSDK.importSessionToStore(
    sessionId, store, /* directory */ null,
    /* includeSubagents */ true,
    /* batchSize */ 500);
```

El helper:

- Lee el JSONL local línea a línea (omite las líneas en blanco).
- Llama a `store.append(key, batch)` cada `batchSize` entradas (por defecto 500) o cada 1 MiB de bytes
  de línea, lo que ocurra antes.
- Con `includeSubagents=true`, importa recursivamente `<sessionDir>/subagents/**/*.jsonl` y los
  adjuntos `.meta.json` (el `.meta.json` se convierte en una entrada `agent_metadata`).
- Lanza `IllegalArgumentException` si el UUID no es válido y `NoSuchFileException` si no encuentra el
  archivo de la sesión.

El `project_key` de destino es el nombre del directorio de proyecto en disco —la misma clave que
produce `filePathToSessionKey`—, así que una sesión importada es indistinguible de una replicada en
vivo y se puede reanudar desde el `cwd` original.

Los adaptadores deben tratar `entry.uuid()` como clave de idempotencia para que reimportar sea seguro
frente a duplicados.

## Conjunto de pruebas de conformidad

`in.vidyalai.claude.sdk.testing.SessionStoreConformance` es un conjunto de pruebas público e
independiente del framework que ejercita los 14 contratos de comportamiento que todo adaptador debe
satisfacer. Úsalo para validar tus propias implementaciones:

```java
import in.vidyalai.claude.sdk.testing.SessionStoreConformance;
import org.junit.jupiter.api.Test;

class MyRedisStoreConformanceTest {
    @Test
    void satisfiesContract() {
        SessionStoreConformance.run(MyRedisStore::new);
    }
}
```

Para saltarte los métodos opcionales que no implementas:

```java
SessionStoreConformance.run(WormStore::new,
    EnumSet.of(SessionStoreConformance.OptionalMethod.DELETE));
```

El conjunto usa `AssertionError` a secas (sin dependencia de ningún framework de pruebas), así que
funciona con JUnit, TestNG, Spock o incluso con una prueba de humo en un `main()` normal.

Los 14 contratos cubren:

| # | Contrato |
|---|---|
| 1 | `append` y luego `load` devuelve las mismas entradas en el mismo orden |
| 2 | `load` de una clave desconocida devuelve `null` |
| 3 | Varias llamadas a `append` conservan el orden |
| 4 | `append([])` no hace nada |
| 5 | Las claves con subpath se guardan de forma independiente de la principal |
| 6 | Aislamiento por `project_key` |
| 7 | `listSessions` devuelve los IDs de sesión del proyecto, con mtime en ms desde la época |
| 8 | `listSessions` excluye los subpath de subagente |
| 9 | `delete` y luego `load` devuelve `null` |
| 10 | `delete` de la clave principal se propaga a las subclaves |
| 11 | `delete` con subpath elimina solo esa subclave |
| 12 | `listSubkeys` devuelve los subpath |
| 13 | `listSubkeys` excluye la transcripción principal |
| 14 | `listSessionSummaries` hace ida y vuelta por `foldSessionSummary` |

## Piezas internas de ejecución

Viven en `in.vidyalai.claude.sdk.internal` y no forman parte de la API pública, pero entenderlas ayuda
a depurar el comportamiento de la réplica.

### `TranscriptMirrorBatcher`

Almacena los marcos `transcript_mirror` que el CLI emite por la salida estándar y los vuelca a
`store.appendAsync(...)`:

- Umbrales de volcado inmediato: `MAX_PENDING_ENTRIES=500`, `MAX_PENDING_BYTES=1 MiB`. Con
  `sessionStoreFlush(EAGER)` ambos umbrales se ponen a cero, de modo que cada marco encolado programa
  un drenaje en segundo plano (consulta [Modo de volcado](#modo-de-volcado-batched-frente-a-eager)).
- Volcado explícito antes de cada mensaje `result` y otra vez al final del flujo o al cerrar.
- Agrupa los marcos por `filePath`, de modo que cada archivo único reciba una llamada `append` por
  volcado.
- Los marcos cuya ruta cae fuera de `projectsDir` se descartan con un aviso (ocurriría si el
  `CLAUDE_CONFIG_DIR` difiere entre el proceso padre y el subproceso).
- `MIRROR_APPEND_MAX_ATTEMPTS=3` reintentos con espera de `[200ms, 800ms]`; los tiempos de espera no se
  reintentan.
- Los accesores de prueba `maxPendingEntries()` / `maxPendingBytes()` exponen los umbrales configurados
  (reflejan los atributos públicos de Python).

### `SessionResume`

Materializa una sesión almacenada en un `CLAUDE_CONFIG_DIR` temporal para que el CLI pueda reanudar:

- `materializeResumeSession(options)`: punto de entrada principal.
- `applyMaterializedOptions(options, materialized)`: copia las options con `CLAUDE_CONFIG_DIR`
  inyectado, `resume` definido y `continueConversation` limpiado.
- `buildMirrorBatcher(store, materialized, env, onError)`: construye el batcher con el `projectsDir`
  correcto (por defecto, modo de volcado `BATCHED`). La sobrecarga de 5 argumentos
  `buildMirrorBatcher(store, materialized, env, onError, flushMode)` pone a cero los umbrales del
  batcher cuando `flushMode == EAGER`.
- `MaterializedResume.cleanup()`: eliminación recursiva de mejor esfuerzo, con reintentos ante bloqueos
  transitorios del antivirus o el indexador de Windows.

### `SessionStoreValidation`

Comprobaciones previas de las options, invocadas antes de crear el subproceso (rechazan una mala
configuración con `IllegalArgumentException`).

### `SessionSummary`

Helpers puros que los adaptadores pueden usar dentro de `append()` para mantener adjuntos de resumen
incrementales sin releer la transcripción:

```java
SessionSummaryEntry folded = SessionSummary.foldSessionSummary(
    /* prev */ existing, key, entries);
// stamp folded.mtime() with the adapter's storage write time, then persist.
```

`SessionSummary.summaryEntryToSdkInfo(entry, projectPath)` convierte un adjunto de nuevo en un
`SDKSessionInfo` para el listado.

## Buenas prácticas

### Implementación del adaptador

- **Implementa siempre `append` y `load`.** Son obligatorios.
- **Mantén un adjunto de resumen** con `SessionSummary.foldSessionSummary` dentro de `append()` si tu
  back-end admite operaciones de listado; eso hace que `listSessionsFromStore` sea O(1) en lugar de
  O(N) cargas. Omite el plegado para claves con `subpath`: las transcripciones de subagente no deben
  contribuir al resumen de la sesión principal.
- **Trata `entry.uuid()` como clave de idempotencia.** Usa semántica de upsert o «omitir si existe». El
  SDK reintenta los lotes fallidos y puede tener éxito parcial.
- **Sella el `mtime` del resumen con la hora de escritura de tu almacenamiento**, no con las marcas de
  tiempo de las entradas. La comprobación de frescura de la ruta rápida compara el mtime del resumen
  con el `listSessions().mtime` de la misma sesión: usar marcas de tiempo de entrada haría que todos
  los adjuntos parecieran obsoletos.
- **Propaga los borrados** de la clave de la transcripción principal a todas las subclaves
  (transcripciones de subagente).
- **Ejecuta el conjunto de conformidad** en la CI.

### Cuándo sobrescribir los métodos `*Async`

- Tu cliente es asíncrono de forma nativa (AWS SDK v2 async, R2DBC, Lettuce reactive): sobrescribe
  `*Async` para evitar un salto de hilo.
- Tu cliente es síncrono (JDBC, Jedis, AWS SDK v1): implementa solo los métodos síncronos; las
  envolturas `*Async` por defecto bastan.

### Cuándo usar `importSessionToStore`

- Migración puntual de sesiones locales preexistentes a un store.
- Ponerse al día tras un `MirrorErrorMessage` (reimporta el archivo local; la idempotencia por `uuid`
  lo hace seguro).

### Evita

- Combinar `sessionStore` con `enableFileCheckpointing` (se rechaza igualmente en la validación: los
  checkpoints son solo locales).
- Guardar secretos o datos personales sin controles de retención. El SDK no borra automáticamente;
  configura el ciclo de vida de tu store.
- Dar por hecha una serialización byte a byte idéntica en `load()`. El contrato es de igualdad
  profunda; el `jsonb` de Postgres, por ejemplo, reordena las claves.

## Referencia de la API

### La interfaz `SessionStore`

`in.vidyalai.claude.sdk.types.session.SessionStore`

| Método | Obligatorio | Por defecto | Notas |
|---|---|---|---|
| `void append(SessionKey, List<SessionStoreEntry>)` | ✅ | — | Replica el lote; se llama tras la escritura local |
| `List<SessionStoreEntry> load(SessionKey)` | ✅ | — | Devuelve entradas o `null` |
| `List<SessionStoreListEntry> listSessions(String)` | opcional | lanza | Excluye entradas con subpath |
| `List<SessionSummaryEntry> listSessionSummaries(String)` | opcional | lanza | Ruta rápida para `listSessionsFromStore` |
| `void delete(SessionKey)` | opcional | lanza | La clave principal se propaga a las subclaves |
| `List<String> listSubkeys(SessionListSubkeysKey)` | opcional | lanza | Se usa en la materialización de la reanudación |
| `boolean implementsListSessions()` | — | `false` | Sobrescribe para declarar soporte |
| `boolean implementsListSessionSummaries()` | — | `false` | Sobrescribe para declarar soporte |
| `boolean implementsDelete()` | — | `false` | Sobrescribe para declarar soporte |
| `boolean implementsListSubkeys()` | — | `false` | Sobrescribe para declarar soporte |
| `CompletableFuture<Void> appendAsync(...)` | opcional | envuelve el síncrono | Sobrescribe para clientes asíncronos nativos |
| `CompletableFuture<List<SessionStoreEntry>> loadAsync(...)` | opcional | envuelve el síncrono | Sobrescribe para clientes asíncronos nativos |
| `CompletableFuture<List<SessionStoreListEntry>> listSessionsAsync(...)` | opcional | envuelve el síncrono | — |
| `CompletableFuture<List<SessionSummaryEntry>> listSessionSummariesAsync(...)` | opcional | envuelve el síncrono | — |
| `CompletableFuture<Void> deleteAsync(...)` | opcional | envuelve el síncrono | — |
| `CompletableFuture<List<String>> listSubkeysAsync(...)` | opcional | envuelve el síncrono | — |

Cada método `*Async` tiene tanto una sobrecarga sin argumentos (usa el ejecutor por defecto
configurado) como otra que recibe un `Executor` (control por llamada).

### Métodos estáticos de `ClaudeSDK`

| Método | Descripción |
|---|---|
| `String projectKeyForDirectory(@Nullable Path)` | Normaliza un directorio en un `project_key` |
| `List<SDKSessionInfo> listSessionsFromStore(SessionStore, @Nullable Path, @Nullable Integer, int)` | Lista las sesiones de un store |
| `SDKSessionInfo getSessionInfoFromStore(SessionStore, String, @Nullable Path)` | Lee los metadatos de una sesión |
| `List<SessionMessage> getSessionMessagesFromStore(SessionStore, String, @Nullable Path, @Nullable Integer, int)` | Lee la transcripción completa |
| `List<String> listSubagentsFromStore(SessionStore, String, @Nullable Path)` | Descubre los IDs de subagente |
| `List<SessionMessage> getSubagentMessagesFromStore(SessionStore, String, String, @Nullable Path, @Nullable Integer, int)` | Lee la transcripción de un subagente |
| `void renameSessionViaStore(SessionStore, String, String, @Nullable Path)` | Añade una entrada `custom-title` |
| `void tagSessionViaStore(SessionStore, String, @Nullable String, @Nullable Path)` | Añade una entrada `tag`; `null` la borra |
| `void deleteSessionViaStore(SessionStore, String, @Nullable Path)` | Borra (no hace nada si `delete` no está implementado) |
| `ForkSessionResult forkSessionViaStore(SessionStore, String, @Nullable Path, @Nullable String, @Nullable String)` | Bifurcación con reasignación de UUID |
| `void importSessionToStore(String, SessionStore, @Nullable Path)` | Replay de local→store (opciones por defecto) |
| `void importSessionToStore(String, SessionStore, @Nullable Path, boolean, int)` | Replay con `includeSubagents` y `batchSize` explícitos |

### Métodos del builder de `ClaudeAgentOptions`

| Método | Por defecto | Descripción |
|---|---|---|
| `Builder sessionStore(@Nullable SessionStore)` | `null` | Replica las transcripciones a este store |
| `Builder loadTimeoutMs(long)` | `60_000` | Tiempo de espera por llamada durante la materialización de la reanudación |

### `SessionStoreExecutor`

`in.vidyalai.claude.sdk.types.session.SessionStoreExecutor`

| Método | Descripción |
|---|---|
| `Executor getDefault()` | Ejecutor por defecto actual |
| `void setDefault(Executor)` | Lo sustituye; `null` vuelve al integrado |
| `void reset()` | Vuelve al integrado `Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("session-store-", 0).factory())` |

### `SessionStoreConformance`

`in.vidyalai.claude.sdk.testing.SessionStoreConformance`

| Método | Descripción |
|---|---|
| `void run(Supplier<SessionStore>)` | Ejecuta los 14 contratos |
| `void run(Supplier<SessionStore>, Set<OptionalMethod>)` | Omite los métodos opcionales indicados |

Enum `OptionalMethod`: `LIST_SESSIONS`, `LIST_SESSION_SUMMARIES`, `DELETE`, `LIST_SUBKEYS`.

## Véase también

- [Historial de sesiones](./feature-session-history.md): equivalentes en disco local (`listSessions`, `getSessionMessages`, etc.)
- [Tipos de mensaje](./feature-message-types.md): integración con `MirrorErrorMessage`
- [ClaudeAgentOptions](./api-claude-agent-options.md): `sessionStore` y `loadTimeoutMs`
- [ClaudeSDK](./api-claude-sdk.md): puntos de entrada de la API pública
- `SessionStoreExample.java` en el módulo `examples/`
