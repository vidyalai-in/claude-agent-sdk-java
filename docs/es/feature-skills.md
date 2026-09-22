# Skills

> **Sobre esta traducción**: la documentación en inglés es la única autoritativa. Esta traducción puede estar desactualizada respecto al [original en inglés](../feature-skills.md); si hay discrepancias, prevalece el inglés. Los bloques de código se mantienen idénticos al original y no se han traducido.

La opción `skills` de `ClaudeAgentOptions` es el único sitio donde se habilitan las skills de
Claude Code para la sesión principal. El SDK conecta automáticamente `allowedTools` y
`settingSources`, de modo que quien llama no tiene que configurar ambos a mano.

> **¿Qué es una skill?** Una skill es un paquete reutilizable de capacidades instalado en
> `.claude/skills/<name>/SKILL.md` (ámbito de proyecto) o `~/.claude/skills/<name>/SKILL.md`
> (ámbito de usuario). Las skills se invocan con la herramienta integrada `Skill`. Consulta la
> documentación de Claude Code para escribir skills.

## Inicio rápido

```java
import in.vidyalai.claude.sdk.ClaudeAgentOptions;

// Mode 1 — enable every discovered skill
var options = ClaudeAgentOptions.builder()
    .skillsAll()
    .build();

// Mode 2 — enable only specific skills
var options = ClaudeAgentOptions.builder()
    .skills(List.of("commit", "review"))
    .build();

// Mode 3 — suppress every skill from the listing
var options = ClaudeAgentOptions.builder()
    .skills(List.of())
    .build();

// Mode 4 (default) — no SDK auto-configuration; CLI defaults apply
var options = ClaudeAgentOptions.builder().build();
```

## Modos

| Llamada del builder | Inyección en `allowedTools` | Valor por defecto de `settingSources` | Campo del initialize |
|---|---|---|---|
| _omitido_ | ninguna | ninguno | omitido |
| `.skillsAll()` | añade `Skill` a secas | `[user, project]` | omitido |
| `.skills(List.of("a", "b"))` | añade `Skill(a)`, `Skill(b)` | `[user, project]` | `["a", "b"]` |
| `.skills(List.of())` | ninguna | `[user, project]` | `[]` |

Notas:

- **`null` ≠ skills desactivadas.** Omitir la opción deja intactos los valores por defecto del CLI.
  Para quitar todas las skills del listado que ve el modelo, pasa una lista vacía.
- **`"all"` y omitirlo son equivalentes a nivel de protocolo.** Ambos omiten el campo `skills` en la
  petición de control initialize. El CLI interpreta la omisión como «sin filtro».
- **La lista vacía sí se envía por el protocolo.** `List.of()` se convierte en `"skills": []` en la
  petición initialize, indicando a los CLI compatibles que no carguen ninguna skill en el prompt de
  sistema.

## Cómo funciona el cableado automático

`SubprocessCLITransport.applySkillsDefaults()` construye las flags efectivas del CLI antes de crear
el subproceso. El `ClaudeAgentOptions` original nunca se modifica.

Para `skillsAll()`:

- Si `allowedTools` todavía no contiene `"Skill"`, se añade la herramienta a secas.
- Si `settingSources` es null, pasa a ser `[USER, PROJECT]` para que el CLI descubra las skills
  instaladas.
- Un `settingSources(...)` explícito siempre gana frente al valor por defecto.

Para `skills(List.of(...))`:

- Por cada nombre `n`, añade `Skill(n)` a `allowedTools` (sin duplicar las entradas existentes).
- El mismo comportamiento por defecto de `settingSources`.

Para `skills(List.of())`:

- `allowedTools` no cambia.
- El mismo comportamiento por defecto de `settingSources`.

## Validación de nombres (0.1.22)

Los nombres pasados a `skills(List.of(...))` se validan antes de componerse en el valor
`--allowedTools` del CLI. Cada rechazo lanza `IllegalArgumentException` **al conectar**, desde
`buildCommand()`, antes de crear el subproceso del CLI.

La validación existe porque `--allowedTools` es una única cadena que el CLI divide en reglas de
permisos por comas y espacios fuera de paréntesis, y ese tokenizador no reconoce ninguna secuencia
de escape. El escape solo existe en la gramática de cada regla, que se aplica *después* de dividir,
así que un nombre que lleve un delimitador no puede transmitirse de forma fiable: en qué se
tokeniza depende de lo que lo rodee:

```java
// Before 0.1.22 this emitted --allowedTools "Skill(x),Bash(*)",
// silently granting the session unrestricted Bash.
ClaudeAgentOptions.builder()
    .skills(List.of("x),Bash(*"))
    .build();

// 0.1.22: IllegalArgumentException at connect()
// "Invalid skill name 'x),Bash(*': parentheses, commas, control characters,
//  and byte-order marks are not allowed. ..."
```

### Formas rechazadas

| Forma | Ejemplo | Motivo |
|---|---|---|
| Paréntesis o comas | `"x),Bash(*"`, `"a,b"`, `"()"` | delimitadores de regla: el vector de inyección anterior |
| Caracteres de control | C0 (`\n`, `\t`, `\u0000`), DEL (`\u007F`), C1 (`\u0080`–`\u009F`) | nunca aparecen en el nombre de un directorio de skill |
| Marca de orden de bytes | `﻿` en cualquier parte del nombre | el CLI recorta U+FEFF como espacio; la regla nombraría otra skill |
| Vacío o solo espacios | `""`, `" "`, `"  \t "` | no nombra nada |
| Comodín suelto | `"*"` | usa `skillsAll()` en su lugar |
| Sufijo comodín | `"pdf:*"`, `"my skill *"` | enumera cada skill por su nombre exacto |
| Espacios alrededor | `" pdf"`, `"pdf "` | nunca podría coincidir: la herramienta `Skill` recorta el nombre invocado |
| `/` inicial | `"/commit"` | la opción toma el nombre canónico, no la forma de comando con barra |
| Barras invertidas consecutivas | `"mid\\\\dle"` | el analizador de cada regla las colapsa, así que la regla nombraría otra skill |
| Barra invertida final sin pareja | `"name\\"` | escape colgante |
| Sustituto sin pareja | un `\ud800` solitario | nunca podría coincidir con un nombre que el CLI haya descubierto |

Solo las tres primeras filas son vectores de inyección. Las demás se tokenizan limpiamente, pero
construirían una regla que jamás coincidiría con la skill indicada: se rechazan para que el fallo
sea evidente en `connect()` en vez de que la skill falte en silencio en la sesión. Los ejemplos de
cadena anteriores se muestran como literales de código Java, así que `"mid\\\\dle"` es un nombre con
dos barras invertidas y `"dir\\sub"` (aceptado) tiene una.

### Nombres aceptados

Los nombres corrientes no se ven afectados. Todos estos siguen construyendo exactamente el mismo
argv que antes:

```java
.skills(List.of(
    "pdf-tools",          // hyphens
    "my_skill.v2",        // underscores, dots
    "myplugin:pdf",       // plugin-qualified
    "skill with spaces",  // interior spaces are fine; only surrounding ones are not
    "dir\\sub",           // a single backslash
    "日本語スキル"          // non-ASCII
))
```

### Cambios incompatibles

Dos formas que antes se aceptaban ahora lanzan excepción:

| Antes | Comportamiento anterior | Ahora |
|---|---|---|
| `skills(List.of("*"))`, `skills(List.of("plugin:*"))` | creaba una regla comodín | lanza excepción: usa `skillsAll()` o añade una entrada `Skill(...)` directamente en `allowedTools` para la coincidencia por prefijo |
| `skills(List.of(" name"))`, `skills(List.of("/name"))` | creaba una regla que no coincidía con nada, así que la skill quedaba **silenciosamente no disponible** | lanza excepción e indica el problema |

### Comportamiento específico de Java

Dos comprobaciones difieren a propósito del SDK de Python, porque los lenguajes modelan las cadenas
de forma distinta:

- **Sustitutos (surrogates).** Python rechaza todos los puntos de código sustitutos, lo cual es
  razonable allí: una `str` de Python contiene puntos de código y un carácter astral es un único
  elemento no sustituto, de modo que cualquier sustituto presente está, por construcción, sin
  pareja. Las cadenas de Java son UTF-16, donde un carácter astral legítimamente *es* un par
  alto/bajo. Por eso Java rechaza únicamente los sustitutos **solitarios**; un nombre como
  `"𝕤kill"` se acepta.
- **Espacios en blanco.** `String.strip()` sigue a `Character.isWhitespace`, que deja pasar los
  espacios duros (U+00A0, U+2007, U+202F) que sí elimina el `str.strip()` de Python. La comprobación
  de relleno lo une con `Character.isSpaceChar`, así que esos también se detectan. U+FEFF queda
  fuera de ambos y se rechaza como carácter no válido, exactamente igual que en Python.

`skillsAll()` no pasa por la comprobación de nombres —no hay nombre que comprobar— y
`skills(List.of())` sigue siendo un no-op válido.

## Protocolo del initialize

Las skills también viajan por el protocolo de control del SDK mediante
`SDKControlInitializeRequest.skills`. Solo se envía una lista explícita; `"all"` y `null` omiten el
campo.

Los CLI antiguos que no reconocen el campo `skills` del initialize lo ignoran: las entradas de
`allowedTools` inyectadas automáticamente se siguen respetando.

> **Obsolescencia:** pasar el token `"Skill"` a secas en `allowedTools(...)` (o en
> `AgentDefinition.tools`) está **obsoleto**. Usa `skillsAll()` / `skills(List.of(...))`: configuran
> todo lo necesario (incluido permitir la herramienta `Skill`) y evitan que `allowedTools` y el
> campo `skills` del protocolo se desincronicen.

## Ejemplos

### Combinar con un `allowedTools` explícito

Las skills amplían, nunca sustituyen, una lista de permitidas existente:

```java
var options = ClaudeAgentOptions.builder()
    .allowedTools(List.of("Read", "Write"))
    .skills(List.of("commit"))
    .build();
// effective allowedTools: [Read, Write, Skill(commit)]
```

### Inyección idempotente

Si ya has añadido `Skill` o `Skill(name)` a tu lista de permitidas, el SDK no lo duplica:

```java
var options = ClaudeAgentOptions.builder()
    .allowedTools(List.of("Skill(pdf)"))
    .skills(List.of("pdf"))
    .build();
// effective allowedTools: [Skill(pdf)]   (not [Skill(pdf), Skill(pdf)])
```

### Conservar un `settingSources` explícito

```java
var options = ClaudeAgentOptions.builder()
    .skillsAll()
    .settingSources(List.of(SettingSource.LOCAL))
    .build();
// effective settingSources: [LOCAL]   (your value wins over the [USER, PROJECT] default)
```

## Nota de seguridad

La opción `skills` es un **filtro de contexto, no un sandbox.** Las skills no listadas quedan
ocultas del listado que ve el modelo y no se pueden invocar con la herramienta `Skill`, pero sus
archivos siguen en el disco: una sesión con `Read` o `Bash` todavía puede acceder directamente a
`.claude/skills/**`.

Para un aislamiento estricto:

- Apunta `cwd` a un directorio cuyo `.claude/skills/` contenga solo el subconjunto deseado, **o**
- Añade reglas de denegación de permisos para `Read`/`Bash` en las rutas de las skills.

Las skills incluidas y las de los plugins instalados se descubren con independencia de
`settingSources`. La lista de permitidas `skills` es el único mecanismo que las oculta del listado
del modelo.

**No guardes secretos en los archivos de skill.**

## Ejemplo completo

Consulta [`examples/SkillsExample.java`](../../examples/src/main/java/examples/SkillsExample.java)
para una demostración ejecutable de los tres modos.

```java
package examples;

import java.util.List;
import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.ClaudeSDK;

public class SkillsExample {
    public static void main(String[] args) throws Exception {
        var options = ClaudeAgentOptions.builder()
            .skillsAll()
            .maxTurns(1)
            .build();
        ClaudeSDK.query("List the skills you have available.", options);
    }
}
```

## Véase también

- [Opciones de configuración](./feature-configuration-options.md): la API completa del builder
- [Definiciones de agente](./feature-agents.md): el campo `skills` de `AgentDefinition` (lista por subagente)
- [Historial de sesiones](./feature-session-history.md): leer transcripciones del disco
