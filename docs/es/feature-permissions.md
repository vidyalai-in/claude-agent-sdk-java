# Sistema de permisos

Control de permisos a medida para el uso de herramientas.

> **Sobre esta traducción**: la documentación en inglés es la única autoritativa. Esta traducción puede estar desactualizada respecto al [original en inglés](../feature-permissions.md); si hay discrepancias, prevalece el inglés. Los bloques de código se mantienen idénticos al original y no se han traducido.

## Visión general

El sistema de permisos controla qué herramientas puede usar Claude y cómo se atienden las
peticiones de permiso.

## Modos de permiso

```java
.permissionMode(PermissionMode.BYPASS_PERMISSIONS)
```

### Modos disponibles

- **DEFAULT** (el valor por defecto del CLI): comportamiento de permisos estándar; las herramientas que necesitan aprobación pasan por `canUseTool` o `permissionPromptToolName`
- **ACCEPT_EDITS**: acepta automáticamente las ediciones de archivos y pregunta en el resto
- **PLAN**: modo de planificación; no se ejecuta ninguna herramienta
- **BYPASS_PERMISSIONS**: omite por completo las comprobaciones de permisos
- **DONT_ASK**: no pregunta; deniega todo lo que no esté aprobado de antemano por reglas allow
- **AUTO**: un clasificador basado en un modelo aprueba o deniega cada llamada a herramienta

## Callback de permisos personalizado

Para un control detallado, usa un callback `canUseTool`. Funciona con todos los puntos de entrada:
un prompt en forma de cadena se transmite internamente por stdin, así que el protocolo de control
que lleva las peticiones de permiso también está disponible allí. No se puede combinar con
`permissionPromptToolName`:

```java
.canUseTool((toolName, input, context) -> {
    // Custom logic
    if (shouldAllow(toolName, context.blockedPath())) {
        return CompletableFuture.completedFuture(
            new PermissionResultAllow()
        );
    } else {
        return CompletableFuture.completedFuture(
            new PermissionResultDeny("Access denied: " + context.blockedPath())
        );
    }
})
```

> **`canUseTool` solo se dispara en decisiones `"ask"`.** Este callback es el sustituto en el SDK
> del prompt interactivo de permisos: solo se ejecuta cuando las reglas de permisos del CLI dan
> `"ask"`. **No** se invoca para llamadas a herramientas ya permitidas por `allowedTools`,
> `permissionMode` (por ejemplo, `ACCEPT_EDITS`, `BYPASS_PERMISSIONS`) o reglas `permissions.allow`
> en los ajustes: esas nunca llegan a un prompt. Para observar o controlar **todas** las llamadas a
> herramientas al margen de las reglas de permisos, registra en su lugar un hook `PreToolUse` con
> `hooks(...)`.

### Aviso de sombreado

Como `canUseTool` nunca se dispara para llamadas ya permitidas por otras opciones, el SDK emite un
**aviso informativo** al conectar cuando detecta un callback visiblemente ensombrecido. La
comprobación se ejecuta una vez por construcción de consulta —cuando arranca
`ClaudeSDKClient.connect()` o cuando se prepara cualquier `ClaudeSDK.query(...)`— y registra un
`WARNING` mediante `java.util.logging` en el logger llamado
`in.vidyalai.claude.sdk.internal.CanUseToolShadow`.

Un callback `canUseTool` se reporta como ensombrecido cuando se define junto con alguno de estos:

- **`permissionMode(PermissionMode.BYPASS_PERMISSIONS)`**: toda llamada a herramienta se aprueba
  automáticamente (salvo reglas de denegación explícitas) antes de consultar el callback.
- **Una entrada de `allowedTools` que permite la herramienta *entera***: una entrada sin
  especificador (`"Read"`), con especificador vacío (`"Read()"`) o con un especificador que es solo
  un comodín (`"Read(*)"`). Un especificador restrictivo como `"Bash(ls:*)"` **no** ensombrece el
  callback, porque las invocaciones que no encajan siguen llegando hasta él. El aviso nombra cada
  herramienta ensombrecida.

`skills("all")` (mediante `Builder.skillsAll()`) se tiene en cuenta: hace que el transporte inyecte
una regla de permiso `Skill` a secas, así que ensombrece el callback igual que una entrada
`"Skill"` escrita a mano. Las skills con nombre (`skills(List.of("reviewer"))`) inyectan
especificadores `Skill(name)`, que no ensombrecen.

El aviso es **solo informativo: nunca lanza excepción**. El sombreado puede ser intencionado (por
ejemplo, un callback usado únicamente para herramientas que *no* están en `allowedTools`). Para
observar o controlar todas las llamadas a herramientas al margen de las reglas de permisos, usa un
hook `PreToolUse`, pero ten en cuenta que un hook `PreToolUse` que devuelve una decisión de
*permitir* también salta el `canUseTool`. Las reglas de permitir que viven en archivos de ajustes
también pueden ensombrecer el callback, pero esta comprobación no las ve.

Para silenciar el aviso, sube el nivel del logger
`in.vidyalai.claude.sdk.internal.CanUseToolShadow` por encima de `WARNING`:

```java
java.util.logging.Logger
    .getLogger("in.vidyalai.claude.sdk.internal.CanUseToolShadow")
    .setLevel(java.util.logging.Level.SEVERE);
```

### Mantener determinista un callback

Las reglas de los archivos de ajustes son el caso de sombreado que el aviso no puede ver, y el más
fácil de sufrir: un `Write(*)` a secas bajo `permissions.allow` en `~/.claude/settings.json` basta
para que un callback `canUseTool` deje de dispararse del todo en una máquina donde ayer funcionaba.
No falla nada: el callback simplemente nunca se consulta, y el código que cuenta los prompts que ha
atendido informa de cero.

Cuando el callback deba dispararse de forma predecible —una demostración, una prueba, un script
reproducible— no cargues ningún archivo de ajustes:

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
        .canUseTool(callback)
        // Load no settings files: an allow rule in the user's or the project's
        // settings.json shadows the callback exactly like an allowedTools
        // entry does, and the advisory above cannot see those rules.
        .settingSources(List.of())
        .permissionMode(PermissionMode.DEFAULT)
        .build();
```

Ten en cuenta también que una herramienta incluida en `allowedTools` nunca llega a un prompt, así
que un callback pensado para controlar esa herramienta debe dejarla fuera de la lista. El
`PermissionCallbacks.java` del módulo de ejemplos hace ambas cosas.

> **Nota sobre el idioma del SDK:** el SDK de Python reporta esta situación como
> `CanUseToolShadowedWarning` (una subclase de `UserWarning`) y el SDK de TypeScript como un aviso
> de proceso `CLAUDE_SDK_CAN_USE_TOOL_SHADOWED`. El SDK de Java usa `java.util.logging` —su canal
> idiomático de avisos— en lugar de un tipo de aviso específico.

## ToolPermissionContext

El CLI enriquece el contexto de permisos para que los callbacks puedan mostrar prompts con sentido
sin reconstruirlos a partir de la entrada bruta de la herramienta:

```java
record ToolPermissionContext(
    @Nullable Object signal,                 // reserved for future abort signal support (always null today)
    List<PermissionUpdate> suggestions,      // permission suggestions from the CLI
    @Nullable String toolUseId,              // unique tool call ID within the assistant message
    @Nullable String agentId,                // sub-agent's ID if running inside a sub-agent
    @Nullable String blockedPath,            // file path that triggered the request (e.g. Bash hitting a denied path)
    @Nullable String decisionReason,         // why this prompt was triggered (e.g. PreToolUse hook's permissionDecisionReason)
    @Nullable String title,                  // full prompt sentence ("Claude wants to read foo.txt") — use as primary prompt text
    @Nullable String displayName,            // short noun phrase ("Read file") for buttons / compact UI
    @Nullable String description             // human-readable subtitle for the permission UI
)
```

Se conservan constructores retrocompatibles para el código escrito antes de que existieran los
campos enriquecidos:

- `new ToolPermissionContext()`: contexto vacío
- `new ToolPermissionContext(suggestions)`: solo suggestions
- `new ToolPermissionContext(signal, suggestions)`: signal + suggestions
- `new ToolPermissionContext(signal, suggestions, toolUseId, agentId)`: la forma de 4 argumentos anterior al enriquecimiento

```java
.canUseTool((toolName, input, context) -> {
    // Prefer the CLI-supplied prompt text when present.
    String prompt = context.title() != null
        ? context.title()
        : "Allow " + toolName + "?";
    String why = context.decisionReason();
    if (why != null) prompt += " (" + why + ")";

    boolean ok = askUser(prompt);
    return CompletableFuture.completedFuture(
        ok ? new PermissionResultAllow()
           : new PermissionResultDeny("user declined"));
})
```

## PermissionDecision (en hooks PreToolUse)

`PermissionDecision` es el valor que un hook `PreToolUse` devuelve en
`PreToolUseHookSpecificOutput.permissionDecision`:

| Constante | Valor en el protocolo | Efecto |
|----------|------------|--------|
| `ALLOW` | `"allow"` | La herramienta se ejecuta sin preguntar. |
| `DENY` | `"deny"` | La herramienta queda bloqueada. |
| `ASK` | `"ask"` | Dispara el callback `canUseTool` del SDK (o el prompt del CLI). |
| `DEFER` | `"defer"` | Detiene la ejecución sin ejecutar la herramienta; la llamada aplazada aparece en `ResultMessage.deferredToolUse`. Consulta [Hooks → Decisión de permiso `"defer"`](./feature-hooks.md#decisión-de-permiso-defer). |

## PermissionResult

```java
// Allow
new PermissionResultAllow()

// Deny with reason
new PermissionResultDeny("Reason for denial")
```

## Ejemplos

### Permisos basados en rutas

```java
.canUseTool((toolName, input, context) -> {
    // blockedPath is set by the CLI when the request was triggered by a
    // path violation (e.g. a Bash command touching a denied directory).
    // For tools like Read / Write the path is in `input` instead.
    String path = context.blockedPath() != null
        ? context.blockedPath()
        : (String) input.get("file_path");

    // Allow read-only in /src
    if (toolName.equals("Read") && path != null && path.startsWith("/src")) {
        return CompletableFuture.completedFuture(
            new PermissionResultAllow()
        );
    }

    // Deny write to sensitive dirs
    if (toolName.equals("Write") && path != null && path.contains("/config")) {
        return CompletableFuture.completedFuture(
            new PermissionResultDeny("Cannot write to config")
        );
    }

    // Default allow
    return CompletableFuture.completedFuture(
        new PermissionResultAllow()
    );
})
```

### Permisos basados en la hora

```java
.canUseTool((toolName, input, context) -> {
    // Only allow during business hours
    int hour = LocalTime.now().getHour();
    if (hour < 9 || hour > 17) {
        return CompletableFuture.completedFuture(
            new PermissionResultDeny("Outside business hours")
        );
    }
    
    return CompletableFuture.completedFuture(
        new PermissionResultAllow()
    );
})
```

### Confirmación del usuario

```java
.canUseTool((toolName, input, context) -> {
    // Prompt user for dangerous operations
    if (toolName.equals("Bash")) {
        boolean approved = promptUser("Allow bash: " + input + "?");
        if (approved) {
            return CompletableFuture.completedFuture(
                new PermissionResultAllow()
            );
        } else {
            return CompletableFuture.completedFuture(
                new PermissionResultDeny("User rejected")
            );
        }
    }
    
    return CompletableFuture.completedFuture(
        new PermissionResultAllow()
    );
})
```

## Véase también
- [Opciones de configuración](./feature-configuration-options.md#ajustes-de-permisos)
- [Ejemplo de callbacks de permisos](../../examples/src/main/java/examples/PermissionCallbacks.java)
