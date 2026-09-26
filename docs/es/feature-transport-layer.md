# Capa de transporte

Implementaciones de transporte propias para la comunicación con Claude Code.

> **Sobre esta traducción**: la documentación en inglés es la única autoritativa. Esta traducción puede estar desactualizada respecto al [original en inglés](../feature-transport-layer.md); si hay discrepancias, prevalece el inglés. Los bloques de código se mantienen idénticos al original y no se han traducido.

## Visión general

La capa de transporte gestiona la E/S con el CLI de Claude Code. Puedes implementar transportes
propios para conexiones remotas u otros medios de comunicación.

## La interfaz Transport

```java
public interface Transport extends AutoCloseable {
    void connect() throws CLIConnectionException;
    void write(String data) throws CLIConnectionException;
    Iterator<Map<String, Object>> readMessages();
    void endInput();
    boolean isReady();
    void close();
}
```

## Implementación por defecto

### SubprocessCLITransport

El transporte por defecto crea el CLI de Claude Code como subproceso. Se construye solo a partir de
las options: el prompt y el modo de streaming los llevan `ClaudeAgentOptions` y el flujo de mensajes,
no el transporte:

```java
Transport transport = new SubprocessCLITransport(options);
```

**Características**:
- Gestiona el ciclo de vida del subproceso
- Comunicación por stdin/stdout
- Lectura con búfer
- Soporte de callback de stderr
- Limpieza automática
- Apagado ordenado con periodo de gracia (espera a que el subproceso vuelque el archivo de sesión tras el EOF del stdin antes de enviar SIGTERM)
- Establece `CLAUDE_CODE_ENTRYPOINT=sdk-java` por defecto (se puede sobrescribir con `ClaudeAgentOptions.env()`)
- Establece `CLAUDE_CODE_SDK_READS_SESSION_STATE=1` salvo que el `env()` del llamante o el entorno heredado ya la nombren (con cualquier combinación de mayúsculas y minúsculas), de modo que el CLI informa con marcos `session_state_changed` marcados con `sdk_host_only`; `QueryHandler` los lee para decidir cuándo puede cerrarse el stdin y los descarta antes de que lleguen al consumidor. Consulta [Arquitectura → Ciclo de vida del stdin](./architecture.md#ciclo-de-vida-del-stdin-y-el-final-de-una-ejecución). La lista completa de variables que establece el transporte está en [Opciones de configuración → env()](./feature-configuration-options.md#env).
- Avisa al conectar (registro `WARNING`) cuando el CLI es anterior a la 2.0.0, y cuando `verbatimPrompts` está activo pero el CLI es anterior a la 2.1.248, que ignora `client_composed`. La comprobación de versión ejecuta `<cli> -v` y se omite cuando `CLAUDE_AGENT_SDK_SKIP_VERSION_CHECK` está definida.

### Reenvío de flags del CLI

`SubprocessCLITransport.buildCommand()` traduce `ClaudeAgentOptions` a flags del CLI. Flags
destacadas relacionadas con las opciones recientes:

| Opción | Flag(s) del CLI | Notas |
|---|---|---|
| `sessionStore(...)` | `--session-mirror` | Se añade cuando `sessionStore != null`. Indica al CLI que emita marcos `transcript_mirror` por stdout, que el SDK separa y reenvía al `SessionStore` configurado. |
| `thinking(ThinkingConfigAdaptive(SUMMARIZED))` | `--thinking adaptive --thinking-display summarized` | `--thinking-display` solo se reenvía para configuraciones `Adaptive` y `Enabled` (y únicamente cuando `display != null`); `Disabled` nunca la emite. |
| `thinking(ThinkingConfigEnabled(20000, OMITTED))` | `--max-thinking-tokens 20000 --thinking-display omitted` | Ambas flags salen juntas cuando `display` está definido. |
| `systemPrompt(SystemPromptCustom.of(p, …))` | `--system-prompt <p>` | Igual que una cadena simple; `snapshot` viaja en la petición `initialize`. |
| `plugins(List.of(SdkPluginConfig.local(dir)))` | `--plugin-dir <dir>` | Una por cada plugin `local`; los demás tipos se omiten. |

**Redirección de stderr**: el stderr solo se redirige cuando `options.stderrCallback() != null`. La
antigua detección del argumento extra `--debug-to-stderr` se eliminó en 0.1.13 (preparando la
obsolescencia de esa flag del CLI). Para capturar la salida de depuración detallada del CLI, pasa
`extraArgs(Map.of("debug-file", "/path/to/log"))` y lee ese archivo.

**Aislamiento del callback de stderr** (0.1.16): cada llamada `stderrCallback.accept(line)` se envuelve
en un `try/catch(Throwable)` por línea. Un callback que lanza se captura, se registra en `FINE` y el
bucle de lectura continúa con la línea siguiente. Antes, una excepción salía del bucle y descartaba en
silencio todas las líneas de stderr posteriores durante el resto de la sesión. Los fallos del bucle
externo (flujo cerrado de forma inesperada, errores de E/S) también se registran en `FINE` en vez de
tragarse en silencio.

**Limpieza de hijos huérfanos** (0.1.18): cada proceso del CLI que se crea se registra en un conjunto
estático `ACTIVE_CHILDREN`, y un shutdown hook de la JVM termina, con el mejor esfuerzo, los que
sigan vivos si el proceso sale antes de que se ejecute `close()`. `close()` escala la terminación
(periodo de gracia → `destroy()` / SIGTERM → `destroyForcibly()` / SIGKILL) y solo entonces quita el
proceso de `ACTIVE_CHILDREN`, **únicamente tras confirmar que ya no está vivo**
(`!process.isAlive()`). Un hijo que de algún modo sobreviva al escalado (un kill en carrera, o un
`waitFor` que expiró) sigue, por tanto, bajo seguimiento, de modo que el recolector del shutdown hook
todavía tenga una oportunidad con él en lugar de fugarse como un proceso `claude` huérfano.

**Endurecimiento frente a la inyección de flags en argv por `resume` / `sessionId`** (0.1.19):
`buildCommand()` emite `resume` y `sessionId` como un único token argv `--flag=value`
(`--resume=<value>`, `--session-id=<value>`) en lugar de dos tokens separados (`--resume`,
`<value>`). El CLI declara `--resume` con valor *opcional*, así que en la forma de dos tokens un valor
que empieza por guion no queda ligado a la flag y se interpreta como una flag independiente. Una
aplicación que encamine entrada no confiable hacia `resume`/`sessionId` (por ejemplo, un endpoint de
«reanudar mi sesión» que lee un ID de sesión de la petición) podría así sufrir la inyección de flags
arbitrarias: `resume("--version")` ejecutaba en silencio `claude --version` y devolvía cero mensajes.
La forma con igual liga siempre el valor a la flag, y el CLI rechaza entonces un valor con guion
inicial por ser un ID de sesión no válido. Esto ocurre a nivel de argv (un argumento por opción,
**sin ningún shell de por medio**), así que es inyección de flags, no ejecución de comandos, y solo
afecta a las aplicaciones que reenvían entrada no confiable a estas opciones. Sigue el mismo estilo
`--setting-sources=` ya usado en otros puntos de `buildCommand()`.

### Windows: rechazo de un CLI en script batch (0.1.21)

Windows no tiene mecanismo de shebang. Cuando la ruta del CLI apunta a un archivo `.bat` o `.cmd`, el
sistema operativo lo ejecuta reescribiendo la creación del proceso como una invocación
`cmd.exe /c`, y **cmd.exe vuelve a analizar toda la línea de comandos** en tiempo de ejecución. El
entrecomillado de argumentos sigue las reglas de argv de MSVCRT —que solo añaden comillas alrededor de
los espacios— y no las de cmd.exe, así que los metacaracteres de cmd.exe dentro del valor de un
argumento (un título de sesión en `--resume`, el JSON de `--mcp-config`, un prompt de sistema) llegan a
cmd.exe sin escapar y pueden ejecutar comandos inyectados antes incluso de que arranque el CLI.

La forma `--flag=value` de 0.1.19 no sirve en esta ruta: una vez que cmd.exe vuelve a analizar la
cadena, ya no queda ninguna frontera de argv que proteger. No existe un escape fiable para cmd.exe
(`%VAR%` se expande incluso dentro de comillas dobles), así que **rechazar es la única mitigación
robusta**, la misma que adoptó Node.js para esta clase de vulnerabilidad (CVE-2024-27980,
«BatBadBut»).

`connect()` valida la ruta resuelta antes de crear ningún proceso con ella, así que la comprobación
cubre todas las vías hacia el ejecutable: el descubrimiento por PATH, un
`ClaudeAgentOptions.cliPath(...)` explícito y la comprobación de versión que se ejecuta antes del
proceso principal.

```java
// On Windows, with cliPath pointing at npm's shim:
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .cliPath(Path.of("C:\\Users\\me\\AppData\\Roaming\\npm\\claude.cmd"))
    .build();

// throws CLIConnectionException: "Refusing to execute batch script ..."
try (var client = ClaudeSDK.createClient(options)) {
    client.connect("hello");
}
```

**Mitigación** (todas evitan cmd.exe por completo): instala Claude Code de forma nativa con
`irm https://claude.ai/install.ps1 | iex`, o apunta `cliPath` a un `claude.exe`. Si migrar de un
`claude.cmd` instalado con npm está realmente bloqueado, consulta [la habilitación explícita](#windows-habilitación-del-cli-en-batch-0122)
más abajo.

La comprobación de extensión normaliza igual que Win32 y clasifica **todos** los componentes de la
ruta, no solo el último:

| Escritura | Rechazada | Por qué |
|---|---|---|
| `C:\npm\claude.cmd` | sí | el caso simple |
| `C:\npm\claude.CMD` | sí | la comparación de extensión no distingue mayúsculas |
| `C:\npm\claude.cmd ` / `claude.cmd.` | sí | Windows quita puntos y espacios finales al resolver la ruta |
| `C:\npm\claude.cmd:stream` | sí | una especificación de flujo NTFS sigue abriendo su archivo base |
| `C:\npm\claude:evil.cmd` | sí | Win32 encuentra la extensión buscando el último punto en todo el componente, especificación de flujo incluida |
| `C:claude.cmd` | sí | el prefijo de unidad viaja en el mismo componente |
| `.cmd` | sí | `PathFindExtension` trata un `.cmd` suelto como extensión |
| `C:\claude.cmd\..\claude.exe` | sí | cuenta cualquier componente: la normalización de `.`/`..` no lo blanquea |
| `C:\bin\claude.exe` | no | ejecutable nativo |
| `/opt/claude.cmd` en Linux/macOS | no | en POSIX no hay salto por cmd.exe; `.cmd` es un nombre de archivo corriente |

La comprobación usa deliberadamente lógica de cadenas en vez de `java.nio.file.Path`: el análisis de
rutas difiere entre POSIX y Windows, y solo la lógica de cadenas se comporta igual en ambos.
Clasificar todos los componentes cierra de golpe toda la clase de trucos de normalización y no cuesta
nada legítimo: ningún `claude.exe` real vive bajo un directorio con nombre de archivo batch.

### Windows: orden de descubrimiento del CLI (0.1.21)

El descubrimiento prefiere un ejecutable nativo, porque un `claude` sin extensión en Windows es un
script envoltorio de git-bash / WSL que el sistema operativo no puede ejecutar directamente:

1. Recorre **todo** el `PATH` buscando primero `claude.exe`. El PATH se recorre por directorios, así
   que sin esto un script envoltorio en un directorio anterior ensombrecería un `claude.exe` real
   instalado en uno posterior.
2. Si no, recurre a `~/.local/bin/claude.exe`. Las ubicaciones con forma POSIX deliberadamente **no**
   se sondean en Windows: una coincidencia sin extensión allí adelantaría al rechazo explicativo con
   un fallo de creación de proceso opaco, y un `/usr/local/bin/claude` con raíz pero sin unidad se
   resuelve contra la unidad actual, una ubicación que otro usuario local puede crear, lo que la
   convierte en un vector de plantado de binarios.
3. Si no, devuelve un shim `claude.cmd` / `claude.bat` si hay uno en el `PATH`, precisamente para que
   `connect()` lance el rechazo de script batch *con su mitigación* en lugar de un escueto error de
   «no encontrado».
4. Si no, devuelve un acierto no nativo del `PATH` para que el error de creación indique qué hay
   instalado realmente.
5. Si no, lanza `CLINotFoundException` con un mensaje específico de Windows que apunta al instalador
   nativo. No recomienda `npm install -g @anthropic-ai/claude-code`, porque eso produce justamente el
   shim que este SDK rechaza.

El descubrimiento en POSIX no cambia: `PATH`, luego `~/.npm-global/bin`, `/usr/local/bin`,
`~/.local/bin`, `~/node_modules/.bin`, `~/.yarn/bin`, `~/.claude/local`.

### Windows: rechazo de metacaracteres de cmd.exe (0.1.21)

Defensa en profundidad. Con la creación por batch rechazada estos caracteres ya son inofensivos, pero
`resume` y `sessionId` son los valores que las aplicaciones toman más a menudo de entrada externa, así
que se rechazan igualmente, para mantenerlos inertes aunque algún día vuelva a introducirse un salto
por cmd.exe entre el SDK y el CLI.

En Windows, un `resume` o `sessionId` que contenga `&`, `|`, `<`, `>`, `^`, `%`, `!`, `"`, CR o LF
lanza `IllegalArgumentException` desde `buildCommand()`:

```java
// On Windows: IllegalArgumentException
ClaudeAgentOptions.builder().resume("R&D notes").build();

// Accepted — no format is imposed beyond the metacharacter check;
// resume values may be arbitrary session titles, not only UUIDs
ClaudeAgentOptions.builder().resume("Refactor the parser (part 2)").build();
```

**El comportamiento en POSIX no cambia**: allí no hay cmd.exe del que protegerse, así que
`resume("R&D notes")` se pasa tal cual.

### Vinculación de valores en `extraArgs` (0.1.21)

`buildCommand()` emite una entrada de `extraArgs` cuyo valor empieza por `-` como un único token
`--flag=value` en vez de dos. Es la misma clase de inyección que cerró el cambio de
`resume`/`sessionId`, aplicada al último punto de llamada con dos tokens: en la forma de dos tokens,
un valor que empieza por guion no queda ligado a su flag cuando el CLI declara esa opción con valor
opcional, y se interpreta como otra flag.

| Entrada de `extraArgs` | argv emitido |
|---|---|
| `Map.of("some-flag", "value")` | `--some-flag`, `value` |
| `Map.of("some-flag", "--evil")` | `--some-flag=--evil` |
| `Map.of("verbose-thing", "")` | `--verbose-thing` (flag booleana suelta) |

### Windows: habilitación del CLI en batch (0.1.22)

Para despliegues que no pueden migrar de un `claude.cmd` instalado con npm —distribución de software
gestionada de forma centralizada, por ejemplo— el rechazo anterior se puede eximir explícitamente:

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .cliPath(Path.of("C:\\Users\\Administrator\\AppData\\Roaming\\npm\\claude.cmd"))
    .allowUnsafeWindowsBatchCli(true)
    .build();
```

El valor por defecto es `false`; para quien no lo define, el rechazo no cambia en nada.

#### Por qué esto no es un simple bypass

La implementación evidente —saltarse la comprobación cuando la bandera está puesta— devolvería entero
el agujero de reinterpretación de cmd.exe. El detalle relevante está en el propio JDK:

```java
// OpenJDK, src/java.base/windows/classes/java/lang/ProcessImpl.java
final String value = System.getProperty("jdk.lang.Process.allowAmbiguousCommands", "true");
final boolean allowAmbiguousCommands = !"false".equalsIgnoreCase(value);
if (allowAmbiguousCommands) {
    cmdstr = createCommandLine(VERIFICATION_LEGACY, executablePath, cmd);  // escape set: ""
```

La propiedad vale `"true"` por defecto, lo que selecciona un modo heredado cuyo conjunto de escape
está **vacío**: no se entrecomilla nada salvo los espacios y se aceptan las comillas incrustadas. **En
una JVM de serie, Java está expuesto a esta clase de vulnerabilidad exactamente igual que lo estuvo
Node.js**; el rechazo no es solo un razonamiento heredado de otro ecosistema.

Pon la propiedad a `false` y un objetivo que no sea `.exe` seleccionará en su lugar
`VERIFICATION_CMD_BAT`, cuyo conjunto de escape es `"<>&|^`: los argumentos que contengan esos
caracteres o espacios se entrecomillan, y un argumento con una comilla incrustada lanza excepción
directamente.

Por eso la habilitación exige ese modo y añade la pieza que el JDK omite:

1. **`-Djdk.lang.Process.allowAmbiguousCommands=false` es obligatorio.** `connect()` lanza
   `CLIConnectionException` si la propiedad es cualquier cosa distinta de `false`, siguiendo la
   lectura del propio JDK, `!"false".equalsIgnoreCase(value)`, en lugar de inventarse otra noción de
   veracidad. El mensaje indica la bandera que hay que añadir.
2. **Se rastrean todos los argumentos del CLI** en busca de `& | < > ^ % ! "` y CR/LF, lanzando
   `IllegalArgumentException` con el nombre de la opción problemática. `%` y `!` no están en el
   conjunto de escape del JDK, y entrecomillar *no* detiene la expansión de `%VAR%`: un `%FOO%` sin
   comillas que se expanda a `x&calc` se vuelve a analizar. Esto cierra ese vector por el lado de los
   argumentos. La ruta del ejecutable en argv[0] no se rastrea: no son datos de argumento aportados
   por quien llama y ya se ha clasificado.
3. **Se registra un `WARNING` una vez por transporte**, indicando la ruta y el riesgo aceptado.

El resultado es una superficie de ataque *más estrecha* que la de no tener la comprobación.

```java
// JVM started without the flag:
// CLIConnectionException: allowUnsafeWindowsBatchCli(true) was set for '...claude.cmd',
//   but jdk.lang.Process.allowAmbiguousCommands is unset (defaults to true). ...

// With the flag, but a hostile argument value:
ClaudeAgentOptions.builder()
    .cliPath(Path.of("C:\\...\\claude.cmd"))
    .allowUnsafeWindowsBatchCli(true)
    .resume("%USERPROFILE%")
    .build();
// IllegalArgumentException: Refusing to pass the value of --resume to a Windows
//   batch CLI: it contains cmd.exe metacharacters [%], which cmd.exe re-parses.
```

#### Riesgo residual

cmd.exe expande `%VAR%` desde el **entorno**. El barrido de argv cierra el vector por el lado de los
argumentos; no sirve de nada si un atacante controla el entorno que la JVM pasa al CLI. Usa la
habilitación solo donde la ruta del CLI y todos los valores de los argumentos estén bajo control del
administrador, y trátala como un puente de migración, no como un destino.

POSIX no se ve afectado en ningún momento: no hay salto por cmd.exe, así que un archivo `.cmd` es un
nombre de archivo corriente y no se aplican ni el rechazo ni las condiciones de la habilitación.

Consulta [`examples/WindowsBatchCliExample.java`](../../examples/src/main/java/examples/WindowsBatchCliExample.java).

### Inyección en `--allowedTools` mediante el nombre de una skill (0.1.22)

Las tres entradas de endurecimiento anteriores tienen que ver con fronteras de *argv*: un argumento
por opción, sin shell de por medio. Esta es distinta: es una inyección **dentro del valor de un solo
argumento**.

`applySkillsDefaults()` formatea cada entrada de `ClaudeAgentOptions.skills(List)` como
`Skill(<name>)` y une el resultado en la única cadena `--allowedTools`. El CLI vuelve entonces a
dividir esa cadena en reglas de permisos por comas y espacios fuera de paréntesis, y **ese tokenizador
no reconoce ninguna secuencia de escape**: el escape solo existe en la gramática de cada regla, que se
aplica después de dividir. Un nombre que lleve un delimitador, por tanto, no puede transmitirse de
forma fiable; en qué se tokeniza depende de lo que lo rodee.

```java
// Before 0.1.22:  --allowedTools "Skill(x),Bash(*)"
//                                          ^^^^^^^ an extra rule, never requested
ClaudeAgentOptions.builder().skills(List.of("x),Bash(*")).build();
```

Ahora `validateSkillName()` se ejecuta sobre cada entrada antes del formateo, así que el rechazo
aflora desde `buildCommand()`, en el `connect()`, antes de crear el subproceso. Rechaza paréntesis,
comas, caracteres de control (C0, DEL, C1), marcas de orden de bytes, nombres vacíos, un `*` literal y
los sufijos comodín; y, para que una regla muerta no acabe concediendo nada en silencio, también los
espacios alrededor, un `/` inicial, las barras invertidas consecutivas, una barra invertida final sin
pareja y los sustitutos solitarios. Los nombres corrientes —con calificación de plugin, con espacios
interiores, con una sola barra invertida, no ASCII— construyen exactamente el mismo argv que antes.

`rejectNonListSkills()` protege la forma del valor en sí. El par `skills(List)` / `skillsAll()` del
builder ya hace inalcanzable una cadena suelta o un iterable que no sea lista —el sistema de tipos de
Java hace aquí lo que el SDK de Python impone en tiempo de ejecución—, pero la comprobación se
mantiene para que quien llame con tipos crudos o por reflexión falle de forma segura en lugar de
instalar en silencio ningún filtro de skills.

Tabla completa de rechazos, lista de nombres aceptados y las dos divergencias deliberadas respecto al
SDK de Python (sustitutos solitarios frente a cualesquiera, eliminación de espacios duros): consulta
[Skills → Validación de nombres](./feature-skills.md#validación-de-nombres-0122).

## Transporte propio

Implementa la interfaz `Transport` para una comunicación propia:

```java
public class RemoteTransport implements Transport {
    private Socket socket;
    private BufferedWriter writer;
    private BufferedReader reader;
    
    @Override
    public void connect() throws CLIConnectionException {
        try {
            socket = new Socket("remote-host", 8080);
            writer = new BufferedWriter(
                new OutputStreamWriter(socket.getOutputStream())
            );
            reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream())
            );
        } catch (IOException e) {
            throw new CLIConnectionException("Connection failed", e);
        }
    }
    
    @Override
    public void write(String data) throws CLIConnectionException {
        try {
            writer.write(data);
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            throw new CLIConnectionException("Write failed", e);
        }
    }
    
    @Override
    public Iterator<Map<String, Object>> readMessages() {
        return new Iterator<>() {
            private final ObjectMapper mapper = new ObjectMapper();
            
            @Override
            public boolean hasNext() {
                return true;  // Or check connection
            }
            
            @Override
            public Map<String, Object> next() {
                try {
                    String line = reader.readLine();
                    if (line == null) {
                        throw new NoSuchElementException();
                    }
                    return mapper.readValue(line, Map.class);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        };
    }
    
    @Override
    public void endInput() {
        try {
            writer.close();
        } catch (IOException e) {
            // Log error
        }
    }
    
    @Override
    public boolean isReady() {
        return socket != null && socket.isConnected();
    }
    
    @Override
    public void close() {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException e) {
            // Log error
        }
    }
}
```

## Usar un transporte propio

Con un transporte propio, el SDK llama a su `connect()`, entrega los prompts mediante `write()` y envía
los hooks, los agentes y los demás ajustes de la petición `initialize` (incluido `systemPromptSnapshot`)
a través del protocolo de control. Todo lo que `SubprocessCLITransport` convierte en flags del CLI o
variables de entorno —modelo, cwd, modo de permisos, herramientas, `env`, etc.— **no** se aplica, y se
omite la reanudación de sesión respaldada por un store. `verbatimPrompts` sigue aplicándose, ya que el
SDK marca los prompts antes de llamar a `write()`.

```java
// Create custom transport
Transport transport = new RemoteTransport();

// Use with ClaudeSDK.query
List<Message> messages = ClaudeSDK.query(
    prompt,
    options,
    transport  // Custom transport
);

// Or with streaming query
List<Message> messages = ClaudeSDK.query(
    messageStream,
    options,
    transport
);
```

## Buenas prácticas

1. **Seguridad entre hilos**: asegúrate de que write() sea seguro entre hilos
2. **Gestión de recursos**: implementa close() correctamente
3. **Manejo de errores**: lanza CLIConnectionException ante los errores
4. **Lecturas bloqueantes**: readMessages() debe bloquear hasta que haya datos
5. **Formato JSON**: los mensajes deben ser objetos JSON, uno por línea

## Véase también
- [Arquitectura](./architecture.md#4-capa-de-transporte): el diseño de la capa de transporte
- Código fuente del transporte: `sdk/src/main/java/in/vidyalai/claude/sdk/transport/`
