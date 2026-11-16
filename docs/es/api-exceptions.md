# Referencia de la API de tipos de excepción

Manejo de errores y jerarquía de excepciones.

> **Sobre esta traducción**: la documentación en inglés es la única autoritativa. Esta traducción puede estar desactualizada respecto al [original en inglés](../api-exceptions.md); si hay discrepancias, prevalece el inglés. Los bloques de código se mantienen idénticos al original y no se han traducido.

## Jerarquía de excepciones

```
ClaudeSDKException (RuntimeException)
├── CLIConnectionException
│   └── CLINotFoundException
├── ProcessException
│   └── ResultException
├── CLIJSONDecodeException
├── MessageParseException
└── QueryFailedException
```

## ClaudeSDKException

Excepción base de todos los errores del SDK.

```java
public class ClaudeSDKException extends RuntimeException {
    public ClaudeSDKException(String message);
    public ClaudeSDKException(String message, Throwable cause);
}
```

Se lanza tal cual (con causa `InterruptedException`) desde los iteradores de mensajes de
`ClaudeSDKClient.receiveMessages()` y `receiveResponse()` cuando se interrumpe el hilo que espera
en `hasNext()`. El indicador de interrupción se restablece. Desde la 0.2.6; antes, una interrupción
terminaba el flujo como si se hubiera completado.

## CLIConnectionException

Falló la conexión con el CLI de Claude Code.

```java
public class CLIConnectionException extends ClaudeSDKException {
    public CLIConnectionException(String message);
    public CLIConnectionException(String message, Throwable cause);
}
```

**Causas**:
- No se encuentra el CLI (como la subclase `CLINotFoundException`)
- El proceso no arrancó por cualquier motivo distinto de un ejecutable ausente, por ejemplo `EACCES` (no es ejecutable) o `ENOEXEC` (no es un binario válido): `"Failed to start Claude Code: ..."`, con la `IOException` del lanzamiento como causa. Antes de la 0.2.6 se notificaban como `CLINotFoundException`
- El directorio de trabajo `cwd` no existe
- `user(...)` está definido: el SDK de Java no puede ejecutar el CLI como otro usuario del sistema operativo (consulta [user()](./feature-configuration-options.md#user))
- Un mapa `mcpServers` o un JSON schema de `outputFormat` que no se puede serializar (`"Failed to serialize ..."`); antes de la 0.2.6 el flag se descartaba con un aviso
- Tiempo de espera de conexión agotado
- Problemas de red (transporte remoto)
- `settings` que no se pueden cargar para fusionarlos con `sandbox` (`"Failed to load settings: ..."`): un archivo inexistente o ilegible, JSON no válido o JSON que no es un objeto. Consulta [settings()](./feature-configuration-options.md#settings)
- Un matcher `HookOnFailure.BLOCK` con un CLI que no informa de `hook_on_failure_supported`, o que informa de `hooks_applied: false`. Consulta [Hooks → Política de fallo](./feature-hooks.md#política-de-fallo-onfailure)

## CLINotFoundException

No se encuentra el ejecutable del CLI de Claude Code.

```java
public class CLINotFoundException extends CLIConnectionException {
    public CLINotFoundException();                                         // default install hint
    public CLINotFoundException(String message);
    public CLINotFoundException(String message, String cliPath);
    public CLINotFoundException(String message, String cliPath, Throwable cause);

    @Nullable public String getCliPath();   // the path that was searched
}
```

Es una subclase de `CLIConnectionException`, así que captúrala primero cuando manejes ambas.

Se lanza al construir el transporte, antes de lanzar ningún proceso, si no se encuentra ningún CLI,
y desde `connect()` cuando el lanzamiento falla porque el ejecutable no existe (`ENOENT`; la
excepción del lanzamiento es la causa). Desde la 0.2.6 solo significa eso: un CLI que existe pero no
se puede ejecutar lanza una `CLIConnectionException` normal, de modo que un problema de permisos ya
no se notifica como "no instalado". En Windows solo se encuentra un `claude.exe` nativo en una entrada absoluta del `PATH`, o
`~/.local/bin/claude.exe`; el shim `claude.cmd` de npm solo se encuentra con
`allowUnsafeWindowsBatchCli(true)`, y el mensaje lo indica.

**Solución**:
- Instala el CLI de Claude Code (en Windows, con el instalador nativo)
- Indica una ruta propia con `.cliPath()`

## ProcessException

El proceso del CLI falló o se cayó.

```java
public class ProcessException extends ClaudeSDKException {
    public ProcessException(String message);
    public ProcessException(String message, Throwable cause);
}
```

**Causas**:
- El CLI se cayó
- Argumentos no válidos
- Agotamiento de recursos

**Error accionable tras salidas por resultado de error**: cuando el CLI emite un `ResultMessage` con
`isError=true` (por ejemplo `error_max_turns`, `error_during_execution`, o un subtipo `success` con
`apiErrorStatus` definido), a continuación sale con un código distinto de cero a propósito. La
`ProcessException` que vendría después solo llevaría `"Command failed with exit code N"`, lo cual no
sirve de mucho, así que el lector la sustituye por una `ResultException` (véase más abajo). El
reemplazo es por turno: una caída nueva más adelante en la ejecución conserva su mensaje original de
`ProcessException`.

## ResultException

El CLI informó de un resultado de error terminal y salió. Es una subclase de `ProcessException`, así
que los manejadores `catch (ProcessException e)` existentes siguen funcionando.

```java
public class ResultException extends ProcessException {
    public ResultException(String message, @Nullable Map<String, Object> data,
                           @Nullable Integer exitCode);

    @Nullable public String subtype();          // "error_max_turns", "error_during_execution",
                                                // ... or "success" for a mid-turn API failure
    public List<String> errors();               // never null; empty for API failures
    @Nullable public String result();           // result text; the "API Error: ..." prose
    @Nullable public Integer apiErrorStatus();  // HTTP status of the failing API call
    @Nullable public String terminalReason();   // e.g. "api_error", "max_turns"
    @Nullable public String sessionId();
    public Map<String, Object> data();          // raw result payload, unmodifiable
}
```

El mensaje es `"Claude Code returned an error result: <text>"` más el sufijo `" (exit code: N)"` de
`ProcessException`. `<text>` es el array `errors` del resultado unido por `"; "`, y si no, el texto
del resultado, luego un `subtype` distinto de `success` y, por último,
`"API error (HTTP <status>)"`. La `ProcessException` original de la salida distinta de cero es el
`getCause()`.

Ramifica según la carga útil, no según el texto:

```java
} catch (ResultException e) {
    if ("api_error".equals(e.terminalReason())) {
        retry();
    } else if ("error_max_turns".equals(e.subtype())) {
        // ...
    }
}
```

**Dónde aparece:**

- La familia `ClaudeSDK.query(...)` que recopila mensajes la envuelve en una
  `QueryFailedException` para que no se pierdan los mensajes recibidos antes del fallo; la
  `ResultException` es el `getCause()` de esa excepción. Es la forma habitual de verla.
- Directamente, desde una petición de control fallida: sobre todo un `initialize` que el CLI rechaza
  al arrancar (una reanudación rechazada por `resumeDropsTurn`). Eso ocurre antes de recopilar
  ningún mensaje, así que no se envuelve.
- **No** desde `ClaudeSDKClient.receiveResponse()`: ese método termina en el `ResultMessage`
  (exactamente igual que el `receive_response()` del SDK de Python) y por tanto nunca observa la
  salida del CLI. Comprueba allí `ResultMessage.isError()` en su lugar. `receiveMessages()` corre
  hasta el final del flujo y sí lanza, pero en un client vivo el stdin sigue abierto, así que un
  resultado de error a mitad de sesión no termina el flujo.

## CLIJSONDecodeException

Falló el análisis del JSON procedente del CLI.

```java
public class CLIJSONDecodeException extends ClaudeSDKException {
    public CLIJSONDecodeException(String line, Throwable cause);

    public String getLine();   // the raw line that failed to parse
}
```

**Causas**:
- Una línea completa de stdout que empieza por `{` pero no se puede analizar, o que tiene contenido después del objeto JSON (`{"a":1} junk`). Desde la 0.2.6 esto termina el flujo de mensajes con esta excepción (el `ClaudeSDK.query(...)` que recopila la envuelve en una `QueryFailedException`); antes, una línea así se tragaba en silencio todos los mensajes que venían después. Las líneas que no empiezan por `{` se omiten, no se notifican
- Una línea de stdout más larga que `maxBufferSize`
- Desajuste de versión del CLI

Consulta [Capa de transporte → Delimitación de líneas y contrapresión](./feature-transport-layer.md#delimitación-de-líneas-y-contrapresión-026).

## MessageParseException

Falló la conversión del mensaje en un objeto tipado.

```java
public class MessageParseException extends ClaudeSDKException {
    public MessageParseException(String message, Throwable cause);
}
```

**Causas**:
- Un mensaje sin campo `type`
- Faltan campos obligatorios
- Un tipo de mensaje conocido con una estructura no válida (por ejemplo, un contenido de `assistant` que no es una lista)

Un tipo de mensaje desconocido se omite, no se notifica. Desde la 0.2.6, un `null` explícito o un
campo *opcional* de tipo incorrecto tampoco la lanzan ya; consulta
[Tipos de mensaje](./feature-message-types.md).

## QueryFailedException

Una consulta que recopila mensajes terminó en un resultado de error, o su hilo se interrumpió. Lleva
consigo los mensajes que ya habían llegado.

```java
public class QueryFailedException extends ClaudeSDKException {
    public QueryFailedException(String message, Throwable cause, List<Message> partialMessages);

    public List<Message> partialMessages();   // never null; unmodifiable
    public ResultMessage resultMessage();     // last ResultMessage received, or null
}
```

**Causas**:
- `error_max_turns`: se alcanzó `maxTurns`
- `error_max_budget_usd`: se alcanzó `maxBudgetUsd`
- `error_during_execution`: incluida una reanudación rechazada por `resumeDropsTurn`
- El hilo que llama se interrumpió mientras esperaba mensajes (desde la 0.2.6). La causa es una
  `ClaudeSDKException` causada por la `InterruptedException`, `resultMessage()` suele ser `null` y
  el indicador de interrupción se restablece. El CLI se sigue cerrando de forma ordenada antes de
  lanzar la excepción.

**Por qué existe**: el CLI informa de estas situaciones emitiendo un turno *completo* —mensajes del
asistente más un `ResultMessage` final con el subtipo, el coste y el uso— y solo entonces sale con
un código distinto de cero, a propósito, pensando en quien lo usa desde el shell. Las API de
streaming (`ClaudeSDKClient.receiveMessages()` y `receiveResponse()`) entregan cada uno de esos
mensajes al consumidor según llegan y solo lanzan al final, así que allí no se pierde nada. Una
llamada que recopila tiene que devolver una lista o lanzar; lanzar esta excepción lleva tanto el
error como los mensajes, de modo que `ClaudeSDK.query(...)` resulta tan informativo como la ruta de
streaming.

Solo la lanza la familia `ClaudeSDK.query(...)` que recopila mensajes (incluidos `queryForText` y
`queryForResult`, que delegan en ella). Como extiende `ClaudeSDKException`, los bloques
`catch (ClaudeSDKException e)` existentes siguen funcionando sin cambios.

```java
try {
    List<Message> messages = ClaudeSDK.query("Summarize the README", options);
    // ... normal path
} catch (QueryFailedException e) {
    // The turn is usually complete — inspect what actually happened.
    ResultMessage result = e.resultMessage();
    if (result != null && "error_max_budget_usd".equals(result.subtype())) {
        System.out.printf("Stopped by the budget cap after $%.4f%n", result.totalCostUsd());
    }
    for (Message msg : e.partialMessages()) {
        if (msg instanceof AssistantMessage a) {
            System.out.println(a.getTextContent());
        }
    }
}
```

`partialMessages()` está vacío cuando la ejecución falló antes de producir nada (un CLI que no pudo
arrancar, por ejemplo). No se serializa: una instancia deserializada informa de una lista vacía en
lugar de null, porque `Message` no está declarado `Serializable`.

Captura esta excepción siempre que fijes `maxTurns` o `maxBudgetUsd`: alcanzar un límite que tú
mismo configuraste es un desenlace esperado, no un fallo.

## Ejemplos de manejo de errores

### try-catch básico

```java
try {
    List<Message> messages = ClaudeSDK.query(prompt, options);
} catch (CLINotFoundException e) {
    System.err.println("Claude CLI not installed");
} catch (CLIConnectionException e) {
    System.err.println("Connection failed: " + e.getMessage());
} catch (ProcessException e) {
    System.err.println("CLI crashed: " + e.getMessage());
} catch (QueryFailedException e) {
    // Run stopped at a limit; the messages so far are still available.
    System.err.println("Run ended early: " + e.getMessage());
} catch (ClaudeSDKException e) {
    System.err.println("SDK error: " + e.getMessage());
}
```

El orden importa: `QueryFailedException` debe capturarse antes que `ClaudeSDKException`, ya que es
una subclase.

### Con gestión de recursos

```java
try (ClaudeSDKClient client = ClaudeSDK.createClient()) {
    client.connect();
    // Use client
} catch (CLIConnectionException e) {
    log.error("Failed to connect", e);
    throw new ApplicationException("Service unavailable", e);
} catch (ClaudeSDKException e) {
    log.error("SDK error", e);
    throw new ApplicationException("Internal error", e);
}
```

### Lógica de reintentos

```java
int maxRetries = 3;
for (int i = 0; i < maxRetries; i++) {
    try {
        return ClaudeSDK.query(prompt, options);
    } catch (CLIConnectionException e) {
        if (i == maxRetries - 1) throw e;
        Thread.sleep(1000 * (i + 1));  // Exponential backoff
    }
}
```

## Véase también
- [Ejemplo de manejo de errores](../../examples/src/main/java/examples/ErrorHandling.java)
