# Sistema de hooks

Intercepta eventos del ciclo de vida de las conversaciones de Claude y responde a ellos.

> **Sobre esta traducción**: la documentación en inglés es la única autoritativa. Esta traducción puede estar desactualizada respecto al [original en inglés](../feature-hooks.md); si hay discrepancias, prevalece el inglés. Los bloques de código se mantienen idénticos al original y no se han traducido.

## Visión general

Los hooks ejecutan tu código en puntos concretos del bucle del agente. Registras un
callback para un `HookEvent`; cuando el CLI llega a ese punto, envía al SDK una
solicitud de hook, el SDK llama a tu callback con un `HookInput` tipado y tu
callback responde con un `HookOutput` que puede permitir, bloquear o añadir contexto.

Hay 10 eventos de hook para los que puedes registrar callbacks.

## Eventos de hook

`HookEvent` (paquete `in.vidyalai.claude.sdk.types.hook`):

| Constante | Nombre del evento | Cuándo se dispara | Tipo de entrada |
|----------|------------|---------------|------------|
| `PRE_TOOL_USE` | `PreToolUse` | Antes de que se ejecute una herramienta | `PreToolUseHookInput` |
| `POST_TOOL_USE` | `PostToolUse` | Después de que una herramienta se ejecute con éxito | `PostToolUseHookInput` |
| `POST_TOOL_USE_FAILURE` | `PostToolUseFailure` | Después de que una herramienta falle | `PostToolUseFailureHookInput` |
| `USER_PROMPT_SUBMIT` | `UserPromptSubmit` | Cuando se envía un prompt | `UserPromptSubmitHookInput` |
| `STOP` | `Stop` | Cuando la sesión está a punto de detenerse | `StopHookInput` |
| `SUBAGENT_START` | `SubagentStart` | Cuando arranca un subagente | `SubagentStartHookInput` |
| `SUBAGENT_STOP` | `SubagentStop` | Cuando un subagente se detiene | `SubagentStopHookInput` |
| `PRE_COMPACT` | `PreCompact` | Antes de la compactación del contexto | `PreCompactHookInput` |
| `NOTIFICATION` | `Notification` | Ante una notificación | `NotificationHookInput` |
| `PERMISSION_REQUEST` | `PermissionRequest` | Cuando se solicita un permiso | `PermissionRequestHookInput` |

## Uso básico

```java
HookMatcher.HookCallback logReads = (input, context) -> {
    if (input instanceof PreToolUseHookInput preToolUse) {
        System.out.println("About to read: " + preToolUse.toolInput().get("file_path"));
    }
    return CompletableFuture.completedFuture(HookOutput.empty());
};

var options = ClaudeAgentOptions.builder()
    .hooks(Map.of(
        HookEvent.PRE_TOOL_USE, List.of(new HookMatcher("Read", List.of(logReads)))
    ))
    .build();
```

## HookMatcher

```java
record HookMatcher(
    @Nullable String matcher,        // which tools/events to match; null matches all
    List<HookCallback> hooks,        // callbacks to run
    @Nullable Double timeoutSeconds  // timeout for all hooks in this matcher (CLI default: 60)
)

new HookMatcher(List<HookCallback> hooks)                  // matcher = null
new HookMatcher(String matcher, List<HookCallback> hooks)  // timeoutSeconds = null
```

En los eventos de herramienta, `matcher` es un nombre de herramienta (`"Bash"`), varios nombres unidos
con `|` (`"Write|MultiEdit|Edit"`) o `"*"`. Consulta la
[referencia de hooks de Claude Code](https://docs.anthropic.com/en/docs/claude-code/hooks#structure)
para ver la sintaxis del matcher de cada evento.

Un callback es un `HookMatcher.HookCallback`:

```java
@FunctionalInterface
interface HookCallback extends BiFunction<HookInput, HookContext, CompletableFuture<HookOutput>> {}
```

Recibe la entrada tipada y un `HookContext`:

```java
record HookContext(
    @Nullable String toolUseId,  // the tool use ID, for tool events
    @Nullable Object signal      // reserved for future abort support; always null
)
```

El SDK espera al future devuelto antes de responder al CLI. Devuelve
`CompletableFuture.completedFuture(...)` para el trabajo síncrono.

> **Orden de despacho:** cuando hay varios matchers registrados en el mismo evento, el CLI despacha
> todos los callbacks de hook que encajan **de forma concurrente** (en paralelo), no secuencialmente.
> Diseña cada hook para que sea independiente: no des por hecho que uno termina antes de que empiece
> otro (por ejemplo, no encadenes hooks limitadores de tasa que presupongan un orden de bloqueo).

## Entradas de los hooks

`HookInput` es una interfaz sellada; ramifica sobre el record concreto con
`instanceof`. Todas las entradas llevan estos campos:

| Accesor | Tipo | Descripción |
|----------|------|-------------|
| `hookEventName()` | `String` | El nombre del evento, por ejemplo `"PreToolUse"` |
| `sessionId()` | `String` | ID de la sesión |
| `transcriptPath()` | `String` | Ruta a la transcripción de la sesión |
| `cwd()` | `String` | Directorio de trabajo |
| `permissionMode()` | `@Nullable String` | Modo de permiso actual |
| `promptId()` | `@Nullable String` | UUID del prompt de usuario que se está procesando. Coincide con el atributo `prompt.id` de los eventos de OpenTelemetry. Es nulo hasta la primera entrada del usuario |
| `effort()` | `@Nullable HookEffort` | Esfuerzo vigente en el turno actual, como `HookEffort(String level)` (por ejemplo, `"low"`, `"high"`, `"max"`, tras cualquier rebaja para el modelo seleccionado). Presente en los hooks que se disparan dentro de un contexto de uso de herramienta en modelos que admiten esfuerzo |

Campos específicos de cada evento:

| Entrada | Campos |
|-------|--------|
| `PreToolUseHookInput` | `toolName`, `toolInput`, `toolUseId`, `agentId`, `agentType` |
| `PostToolUseHookInput` | `toolName`, `toolInput`, `toolResponse`, `toolUseId`, `agentId`, `agentType`, `durationMs` |
| `PostToolUseFailureHookInput` | `toolName`, `toolInput`, `toolUseId`, `error`, `isInterrupt`, `agentId`, `agentType`, `durationMs` |
| `PermissionRequestHookInput` | `toolName`, `toolInput`, `permissionSuggestions`, `agentId`, `agentType` |
| `UserPromptSubmitHookInput` | `prompt` |
| `StopHookInput` | `stopHookActive`, `lastAssistantMessage`, `backgroundTasks`, `sessionCrons` |
| `SubagentStartHookInput` | `agentId`, `agentType` |
| `SubagentStopHookInput` | `stopHookActive`, `agentId`, `agentTranscriptPath`, `agentType`, `lastAssistantMessage`, `backgroundTasks`, `sessionCrons` |
| `PreCompactHookInput` | `trigger` (`CompactTriggerType`), `customInstructions` |
| `NotificationHookInput` | `message`, `title`, `notificationType` |

Los campos que el CLI puede omitir son `null` cuando faltan: `permissionMode`, `promptId`,
`effort`, `agentId`/`agentType` en las entradas de herramienta, `isInterrupt`,
`durationMs`, `lastAssistantMessage`, `backgroundTasks`, `sessionCrons`,
`permissionSuggestions`, `title` y `customInstructions`.

### Contexto del subagente

Las entradas relacionadas con herramientas (`PreToolUseHookInput`, `PostToolUseHookInput`,
`PostToolUseFailureHookInput`, `PermissionRequestHookInput`) incluyen dos campos
opcionales con el contexto del subagente:

| Campo | Tipo | Descripción |
|-------|------|-------------|
| `agentId` | `@Nullable String` | Identificador del subagente. Presente solo dentro de un subagente creado por una tarea; nulo en el hilo principal. |
| `agentType` | `@Nullable String` | Nombre del tipo de agente (por ejemplo, `"general-purpose"`). Presente dentro de un subagente o en el hilo principal cuando se arranca con `--agent`. |

```java
HookMatcher.HookCallback subagentAware = (input, context) -> {
    if (input instanceof PreToolUseHookInput preToolUse && preToolUse.agentId() != null) {
        System.out.println("Tool used inside sub-agent: " + preToolUse.agentId());
    }
    return CompletableFuture.completedFuture(HookOutput.empty());
};
```

### Tiempo de ejecución de las herramientas

`PostToolUseHookInput.durationMs()` y `PostToolUseFailureHookInput.durationMs()`
informan del tiempo de ejecución de la herramienta en milisegundos, sin contar el tiempo
de las solicitudes de permiso ni el de los hooks.

### Trabajo en segundo plano en Stop y SubagentStop

`StopHookInput` y `SubagentStopHookInput` informan de lo que sigue ejecutándose cuando
la sesión (o el subagente) se detiene:

- `lastAssistantMessage`: texto del último mensaje del asistente antes de detenerse.
- `backgroundTasks`: el trabajo en segundo plano en curso, como records
  `BackgroundTaskSummary`: `id`, `type` (por ejemplo, `"shell"`, `"subagent"`, `"monitor"`,
  `"workflow"`), `status`, `description`, además de `command` (tareas de shell),
  `agentType` (tareas de subagente), `server` y `tool` (tareas de monitor / MCP) y
  `name` (tareas de workflow). Vacía cuando no hay nada en curso.
- `sessionCrons`: despertares programados con ámbito de sesión que reanudarán la
  sesión más tarde, como records `SessionCronSummary`: `id`, `schedule` (una expresión
  cron), `recurring` (`false` para los despertares de una sola vez) y `prompt`.

Para un subagente, ambas listas tienen el ámbito de la sesión padre. `description`,
`command` y el `prompt` de un cron se limitan a 1000 caracteres; un valor recortado
termina con un marcador `"… [+N chars]"` dentro de la propia cadena.

```java
HookMatcher.HookCallback onStop = (input, context) -> {
    if (input instanceof StopHookInput stop && stop.backgroundTasks() != null) {
        for (BackgroundTaskSummary task : stop.backgroundTasks()) {
            System.out.printf("Still running: %s (%s, %s)%n", task.description(), task.type(), task.status());
        }
    }
    return CompletableFuture.completedFuture(HookOutput.empty());
};
```

## HookOutput

Un callback devuelve un `HookOutput` (paquete
`in.vidyalai.claude.sdk.types.hook.output`), construido con su builder:

```java
HookOutput.empty()                 // no fields: the CLI proceeds as normal
HookOutput.async(30_000)           // defer: {"async": true, "asyncTimeout": 30000}

HookOutput.builder()
    .shouldContinue(false)         // "continue": whether Claude proceeds after the hook
    .stopReason("Budget exhausted")// shown when continue is false
    .suppressOutput(true)          // hide stdout from transcript mode
    .decision("block")             // "block" to indicate blocking behavior
    .systemMessage("Heads up")     // warning displayed to the user
    .reason("Blocked by policy")   // feedback for Claude about the decision
    .hookSpecificOutput(specific)  // event-specific controls, see below
    .build();
```

Solo se envían los campos que estableces. Cuando se establece `async`, solo se envían `async` y
`asyncTimeout`.

## Salidas específicas de hook

`HookSpecificOutput` es una interfaz sellada con un record por evento. Cada uno
se serializa con su `hookEventName` más los campos que establezcas (los campos `null` se
omiten):

| Record | Campos |
|--------|--------|
| `PreToolUseHookSpecificOutput` | `permissionDecision` (`PermissionDecision`), `permissionDecisionReason`, `updatedInput`, `additionalContext` |
| `PostToolUseHookSpecificOutput` | `additionalContext`, `updatedToolOutput`, `updatedMCPToolOutput` |
| `PostToolUseFailureHookSpecificOutput` | `additionalContext` |
| `UserPromptSubmitHookSpecificOutput` | `additionalContext`, `sessionTitle`, `suppressOriginalPrompt` |
| `NotificationHookSpecificOutput` | `additionalContext` |
| `SubagentStartHookSpecificOutput` | `additionalContext` |
| `PermissionRequestHookSpecificOutput` | `decision` (un mapa) |
| `StopHookSpecificOutput` | `additionalContext` |
| `SubagentStopHookSpecificOutput` | `additionalContext` |
| `SessionStartHookSpecificOutput` | `additionalContext`, `initialUserMessage`, `sessionTitle`, `watchPaths`, `reloadSkills` |
| `UserPromptExpansionHookSpecificOutput` | `additionalContext`, `suppressOriginalPrompt` |
| `PostToolBatchHookSpecificOutput` | `additionalContext` |
| `PermissionDeniedHookSpecificOutput` | `retry` |
| `PreModelSwitchHookSpecificOutput` | `permissionDecision`, `permissionDecisionReason` |
| `PostModelSwitchHookSpecificOutput` | `additionalContext` |
| `ElicitationHookSpecificOutput` | `action` (`ElicitationAction`), `content` |
| `ElicitationResultHookSpecificOutput` | `action` (`ElicitationAction`), `content` |
| `CwdChangedHookSpecificOutput` | `watchPaths` |
| `FileChangedHookSpecificOutput` | `watchPaths` |
| `WorktreeCreateHookSpecificOutput` | `worktreePath` (obligatorio) |
| `MessageDisplayHookSpecificOutput` | `displayContent` |

> **Cuáles puede usar un callback.** Los callbacks del SDK solo se registran para los
> diez `HookEvent` anteriores, así que solo los nueve primeros records responden a un evento que un
> callback puede recibir. Los demás (de `SessionStart` a `MessageDisplay`)
> describen salidas para eventos que `HookEvent` todavía no incluye. Están tipados por
> paridad con los SDK de Python y TypeScript, que definen las mismas formas.

Notas sobre los campos:

- **`UserPromptSubmitHookSpecificOutput`**: `sessionTitle` establece el título de la
  sesión. `suppressOriginalPrompt` omite el prompt original del mensaje de bloqueo
  cuando el `decision` de la salida es `"block"`. Se conserva un
  constructor de un argumento `(additionalContext)`.
- **`StopHookSpecificOutput` / `SubagentStopHookSpecificOutput`**:
  `additionalContext` es retroalimentación que no es un error y se entrega al modelo (o al
  subagente); la conversación continúa para que pueda actuar en consecuencia.
- **`SessionStartHookSpecificOutput`**: `initialUserMessage` pasa a ser el
  primer mensaje de usuario de la sesión; `sessionTitle` se ignora cuando la sesión
  arranca desde `clear` o `compact`; `watchPaths` son rutas absolutas que se vigilan
  para los eventos FileChanged; `reloadSkills` vuelve a explorar los directorios de skills y comandos
  cuando terminan los hooks SessionStart.
- **`PermissionDeniedHookSpecificOutput`**: `retry` indica al modelo que puede
  reintentar la llamada a herramienta denegada. Se ignora en las denegaciones sin veredicto de un clasificador.
- **`PreModelSwitchHookSpecificOutput`**: `ALLOW` continúa y `DENY` cancela
  el cambio; `ASK` se trata como un rechazo fuera de una sesión interactiva. El
  constructor rechaza `PermissionDecision.DEFER`.
- **`PostModelSwitchHookSpecificOutput`**: `additionalContext` llega al
  modelo con la siguiente solicitud que atienda el nuevo modelo.
- **`ElicitationHookSpecificOutput`**: acepta, rechaza o cancela una solicitud de
  elicitación MCP; `content` contiene los valores de los campos del formulario y solo se usa
  con `ElicitationAction.ACCEPT`. `ElicitationResultHookSpecificOutput`
  sobrescribe la acción o el contenido antes de que la respuesta llegue al servidor MCP.
- **`CwdChangedHookSpecificOutput` / `FileChangedHookSpecificOutput`**:
  `watchPaths` sustituye la lista dinámica de vigilancia actual.
- **`MessageDisplayHookSpecificOutput`**: solo de visualización: `displayContent`
  sustituye el texto en pantalla sin cambiar el mensaje almacenado ni lo que
  ve el modelo. Omítelo para mostrar el original.

### Bloquear una llamada a herramienta

```java
HookMatcher.HookCallback blockRm = (input, context) -> {
    if (input instanceof PreToolUseHookInput preToolUse
            && String.valueOf(preToolUse.toolInput().get("command")).contains("rm -rf")) {
        return CompletableFuture.completedFuture(HookOutput.builder()
            .hookSpecificOutput(new PreToolUseHookSpecificOutput(
                PermissionDecision.DENY,
                "rm -rf is not allowed",
                null,   // updatedInput
                null))  // additionalContext
            .build());
    }
    return CompletableFuture.completedFuture(HookOutput.empty());
};

var options = ClaudeAgentOptions.builder()
    .hooks(Map.of(HookEvent.PRE_TOOL_USE, List.of(new HookMatcher("Bash", List.of(blockRm)))))
    .build();
```

### Mantener el modelo trabajando desde un hook Stop

```java
HookMatcher.HookCallback keepGoing = (input, context) -> {
    if (input instanceof StopHookInput stop && !stop.stopHookActive()) {
        return CompletableFuture.completedFuture(HookOutput.builder()
            .hookSpecificOutput(new StopHookSpecificOutput("The test suite still fails; fix it before stopping."))
            .build());
    }
    return CompletableFuture.completedFuture(HookOutput.empty());
};
```

Comprobar `stopHookActive()` evita mandar al modelo de vuelta al trabajo indefinidamente: es
`true` cuando la sesión ya está continuando a causa de un hook Stop.

## Sustitución de la salida en PostToolUse

`PostToolUseHookSpecificOutput` permite que un hook `PostToolUse` sustituya la salida de la
herramienta antes de que llegue al modelo.

```java
record PostToolUseHookSpecificOutput(
    @Nullable String additionalContext,    // extra context for the model
    @Nullable Object updatedToolOutput,    // replacement for any tool's output
    @Nullable Object updatedMCPToolOutput  // replacement for MCP tool output only
)
```

- **`updatedToolOutput`**: sustituye la salida de cualquier herramienta (incluidas las integradas).
  Para las herramientas integradas, el valor debe ajustarse al esquema de salida de la herramienta
  (por ejemplo, `{"stdout": ..., "stderr": ..., "interrupted": ...}` para `Bash`); una forma que no
  encaje se rechaza y se conserva la salida original.
- **`updatedMCPToolOutput`**: sustituye la salida solo de las herramientas MCP. Es preferible
  `updatedToolOutput`, que funciona con todas las herramientas.
- Se conserva un constructor retrocompatible de 2 argumentos
  `(additionalContext, updatedMCPToolOutput)` para el código escrito antes de que existiera
  `updatedToolOutput`.

```java
HookMatcher.HookCallback redact = (input, context) -> {
    // Redact secrets from Bash output before the model sees it.
    Map<String, Object> redacted = Map.of(
        "stdout", "[redacted]",
        "stderr", "",
        "interrupted", false
    );
    return CompletableFuture.completedFuture(
        HookOutput.builder()
            .hookSpecificOutput(new PostToolUseHookSpecificOutput(null, redacted, null))
            .build()
    );
};

var options = ClaudeAgentOptions.builder()
    .hooks(Map.of(HookEvent.POST_TOOL_USE, List.of(new HookMatcher("Bash", List.of(redact)))))
    .build();
```

## Decisión de permiso: `"defer"`

Un hook `PreToolUse` puede devolver `permissionDecision: "defer"` (mediante
`PermissionDecision.DEFER` / `PreToolUseHookSpecificOutput`) para detener la ejecución sin ejecutar
la herramienta. El CLI expone la llamada aplazada en `ResultMessage.deferredToolUse`, de modo que
quien consume el SDK pueda inspeccionarla y decidir si reanuda.

```java
HookMatcher.HookCallback deferRisky = (input, context) -> {
    if (input instanceof PreToolUseHookInput preToolUse && looksDangerous(preToolUse.toolInput())) {
        return CompletableFuture.completedFuture(
            HookOutput.builder()
                .hookSpecificOutput(new PreToolUseHookSpecificOutput(
                    PermissionDecision.DEFER,
                    "Needs operator review",
                    null,
                    null))
                .build()
        );
    }
    return CompletableFuture.completedFuture(HookOutput.empty());
};

// Caller side — inspect the deferred call from the result message.
for (Message msg : ClaudeSDK.query(prompt, options)) {
    if (msg instanceof ResultMessage r && r.deferredToolUse() != null) {
        DeferredToolUse d = r.deferredToolUse();
        System.out.printf("Deferred %s (id=%s) input=%s%n", d.name(), d.id(), d.input());
    }
}
```

`DeferredToolUse` lleva `id`, `name` e `input`. Consulta [Tipos de
mensaje](./feature-message-types.md#resultmessage) para la forma completa de `ResultMessage`.

## Eventos del ciclo de vida de los hooks en el flujo

Pon `includeHookEvents(true)` en `ClaudeAgentOptions` para recibir además los eventos del ciclo de
vida de los hooks como objetos `HookEventMessage` en el flujo de mensajes. Resulta útil para la
observabilidad (registrar cada disparo de hook) sin tener que registrar un hook por cada evento que
quieras vigilar. El CLI emite los eventos de hook `SessionStart` y `Setup` sea cual sea este ajuste.

```java
var options = ClaudeAgentOptions.builder()
    .includeHookEvents(true)
    .hooks(Map.of(/* still register hooks normally */))
    .build();

for (Message msg : ClaudeSDK.query(prompt, options)) {
    if (msg instanceof HookEventMessage hook) {
        // subtype is "hook_started" or "hook_response"
        System.out.printf("[%s] %s session=%s%n",
            hook.subtype(), hook.hookEventName(), hook.sessionId());
        // Full raw payload (output, exit_code, outcome on hook_response)
        Object outcome = hook.get("outcome");
        if (outcome != null) {
            System.out.println("  outcome: " + outcome);
        }
    }
}
```

Campos de `HookEventMessage`:

| Campo | Tipo | Descripción |
|-------|------|-------------|
| `subtype` | `String` | `"hook_started"` cuando un hook empieza, `"hook_response"` cuando termina |
| `data` | `Map<String, Object>` | Diccionario bruto completo del evento que envía el CLI (`output`, `exit_code`, `outcome` en `hook_response`) |
| `hookEventName` | `String` | Nombre del evento de hook (por ejemplo, `"PreToolUse"`, `"PostToolUse"`, `"Stop"`) |
| `sessionId` | `@Nullable String` | ID de la sesión a la que pertenece este evento |
| `uuid` | `@Nullable String` | ID único del evento |

`HookEventMessage.type()` devuelve `"system"`, pero **no** encaja con `instanceof SystemMessage`:
ramifica directamente sobre `HookEventMessage`.

## Ejemplo completo

```java
public class HooksExample {
    public static void main(String[] args) {
        // Log every tool call before it runs
        HookMatcher.HookCallback logPre = (input, context) -> {
            if (input instanceof PreToolUseHookInput pre) {
                System.out.println("Tool: " + pre.toolName());
            }
            return CompletableFuture.completedFuture(HookOutput.empty());
        };

        // Track tool results and timing
        HookMatcher.HookCallback logPost = (input, context) -> {
            if (input instanceof PostToolUseHookInput post) {
                System.out.println("Result: " + post.toolResponse() + " in " + post.durationMs() + "ms");
            }
            return CompletableFuture.completedFuture(HookOutput.empty());
        };

        // Handle errors
        HookMatcher.HookCallback logFailure = (input, context) -> {
            if (input instanceof PostToolUseFailureHookInput failure) {
                System.err.println("Error: " + failure.error());
            }
            return CompletableFuture.completedFuture(HookOutput.empty());
        };

        var options = ClaudeAgentOptions.builder()
            .hooks(Map.of(
                HookEvent.PRE_TOOL_USE, List.of(new HookMatcher("*", List.of(logPre))),
                HookEvent.POST_TOOL_USE, List.of(new HookMatcher("*", List.of(logPost))),
                HookEvent.POST_TOOL_USE_FAILURE, List.of(new HookMatcher("*", List.of(logFailure)))
            ))
            .build();

        try (var client = ClaudeSDK.createClient(options)) {
            client.connect("List files in current directory");
            for (var msg : client.receiveResponse()) {
                // Process
            }
        }
    }
}
```

## Hooks tras la finalización de un subagente en segundo plano

Con un `ClaudeSDK.query(...)` de un solo uso, un hook del turno que despierta la finalización de un
subagente en segundo plano solo se ejecuta si el stdin sigue abierto cuando el CLI lo llama. El SDK
mantiene el stdin abierto hasta que termina la ejecución, y no solo hasta el primer resultado;
consulta [Agentes → Subagentes en segundo plano y callbacks](./feature-agents.md#subagentes-en-segundo-plano-y-callbacks)
para ver las reglas y una advertencia sobre la versión del CLI, y `BackgroundAgentHooksExample` para
una demostración ejecutable.

## Véase también
- [Opciones de configuración](./feature-configuration-options.md#hooks)
- [Permisos](./feature-permissions.md)
- [Ejemplo de Hooks](../../examples/src/main/java/examples/Hooks.java)
