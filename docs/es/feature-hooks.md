# Sistema de hooks

Intercepta eventos del ciclo de vida de las conversaciones de Claude y responde a ellos.

> **Sobre esta traducción**: la documentación en inglés es la única autoritativa. Esta traducción puede estar desactualizada respecto al [original en inglés](../feature-hooks.md); si hay discrepancias, prevalece el inglés. Los bloques de código se mantienen idénticos al original y no se han traducido.

## Visión general

Los hooks te permiten ejecutar código propio en puntos concretos del ciclo de vida de la
conversación. Hay 10 eventos de hook a los que puedes suscribirte.

## Eventos de hook

- **PRE_TOOL_USE**: antes de ejecutar la herramienta
- **POST_TOOL_USE**: después de que la herramienta se ejecuta con éxito
- **POST_TOOL_USE_FAILURE**: después de que la ejecución de la herramienta falla
- **USER_PROMPT_SUBMIT**: cuando el usuario envía un mensaje
- **STOP**: cuando la sesión se detiene
- **SUBAGENT_START**: cuando arranca un subagente
- **SUBAGENT_STOP**: cuando un subagente se detiene
- **PRE_COMPACT**: antes de la compactación de mensajes
- **NOTIFICATION**: en eventos de notificación
- **PERMISSION_REQUEST**: cuando se solicita un permiso

## Uso básico

```java
var options = ClaudeAgentOptions.builder()
    .hooks(Map.of(
        HookEvent.PRE_TOOL_USE, List.of(
            new HookMatcher(null, "Read", context -> {
                System.out.println("About to read: " + context.input());
                return CompletableFuture.completedFuture(
                    HookOutput.logs(List.of("Pre-tool log"))
                );
            })
        )
    ))
    .build();
```

## HookMatcher

```java
new HookMatcher(
    String toolName,           // null for all tools
    String matchPattern,       // Tool name pattern
    Function<HookContext, CompletableFuture<HookOutput>> handler
)
```

> **Orden de despacho:** cuando hay varios matchers registrados en el mismo evento, el CLI despacha
> todos los callbacks de hook que encajan **de forma concurrente** (en paralelo), no secuencialmente.
> Diseña cada hook para que sea independiente: no des por hecho que uno termina antes de que empiece
> otro (por ejemplo, no encadenes hooks limitadores de tasa que presupongan un orden de bloqueo).

## Campos de entrada de los hooks

Todas las entradas de hook relacionadas con herramientas (`PreToolUseHookInput`,
`PostToolUseHookInput`, `PostToolUseFailureHookInput`, `PermissionRequestHookInput`) incluyen dos
campos opcionales con el contexto del subagente:

| Campo | Tipo | Descripción |
|-------|------|-------------|
| `agentId` | `@Nullable String` | Identificador del subagente. Presente solo dentro de un subagente creado por una tarea; nulo en el hilo principal. |
| `agentType` | `@Nullable String` | Nombre del tipo de agente (por ejemplo, `"general-purpose"`). Presente dentro de un subagente o en el hilo principal cuando se arranca con `--agent`. |

```java
new HookMatcher(null, null, context -> {
    PreToolUseHookInput input = (PreToolUseHookInput) context.input();
    if (input.agentId() != null) {
        System.out.println("Tool used inside sub-agent: " + input.agentId());
    }
    return CompletableFuture.completedFuture(HookOutput.empty());
})
```

## HookOutput

```java
// Empty output
HookOutput.empty()

// With logs
HookOutput.logs(List.of("Log message"))

// With messages
HookOutput.messages(List.of(
    Map.of("role", "user", "content", "Message")
))

// With permission updates
HookOutput.permissionUpdates(List.of(update))

// Combined
HookOutput.builder()
    .logs(List.of("Log"))
    .messages(List.of(message))
    .build()
```

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
HookEvent.POST_TOOL_USE, List.of(
    new HookMatcher(null, "Bash", context -> {
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
    })
)
```

## Decisión de permiso: `"defer"`

Un hook `PreToolUse` puede devolver `permissionDecision: "defer"` (mediante
`PermissionDecision.DEFER` / `PreToolUseHookSpecificOutput`) para detener la ejecución sin ejecutar
la herramienta. El CLI expone la llamada aplazada en `ResultMessage.deferredToolUse`, de modo que
quien consume el SDK pueda inspeccionarla y decidir si reanuda.

```java
HookEvent.PRE_TOOL_USE, List.of(
    new HookMatcher(null, "Bash", context -> {
        PreToolUseHookInput input = (PreToolUseHookInput) context.input();
        if (looksDangerous(input.toolInput())) {
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
    })
)

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
quieras vigilar.

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
        var options = ClaudeAgentOptions.builder()
            .hooks(Map.of(
                // Log all tool uses
                HookEvent.PRE_TOOL_USE, List.of(
                    new HookMatcher(null, null, context -> {
                        PreToolUseHookInput input = (PreToolUseHookInput) context.input();
                        System.out.println("Tool: " + input.toolName());
                        return CompletableFuture.completedFuture(
                            HookOutput.logs(List.of(
                                "Executing: " + input.toolName()
                            ))
                        );
                    })
                ),
                
                // Track tool results
                HookEvent.POST_TOOL_USE, List.of(
                    new HookMatcher(null, null, context -> {
                        PostToolUseHookInput input = (PostToolUseHookInput) context.input();
                        System.out.println("Result: " + input.result());
                        return CompletableFuture.completedFuture(
                            HookOutput.empty()
                        );
                    })
                ),
                
                // Handle errors
                HookEvent.POST_TOOL_USE_FAILURE, List.of(
                    new HookMatcher(null, null, context -> {
                        PostToolUseFailureHookInput input = 
                            (PostToolUseFailureHookInput) context.input();
                        System.err.println("Error: " + input.error());
                        return CompletableFuture.completedFuture(
                            HookOutput.logs(List.of("Tool failed"))
                        );
                    })
                )
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
- [Ejemplo de Hooks](../../examples/src/main/java/examples/Hooks.java)
