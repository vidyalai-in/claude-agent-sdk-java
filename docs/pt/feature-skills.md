# Skills

> **Sobre esta tradução**: a documentação em inglês é a única autoritativa. Esta tradução pode estar desatualizada em relação ao [original em inglês](../feature-skills.md); em caso de divergência, o inglês prevalece. Os blocos de código são mantidos idênticos ao original e não foram traduzidos.

A opção `skills` em `ClaudeAgentOptions` é o único lugar onde se habilitam as skills do Claude Code
para a sessão principal. O SDK conecta automaticamente `allowedTools` e `settingSources`, de modo
que quem chama não precisa configurar os dois manualmente.

> **O que é uma skill?** Uma skill é um pacote reutilizável de capacidades instalado em
> `.claude/skills/<name>/SKILL.md` (escopo de projeto) ou `~/.claude/skills/<name>/SKILL.md`
> (escopo de usuário). Skills são invocadas pela ferramenta embutida `Skill`. Consulte a
> documentação do Claude Code para escrever skills.

## Início rápido

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

| Chamada do builder | Injeção em `allowedTools` | Padrão de `settingSources` | Campo no initialize |
|---|---|---|---|
| _omitido_ | nenhuma | nenhum | omitido |
| `.skillsAll()` | adiciona `Skill` simples | `[user, project]` | omitido |
| `.skills(List.of("a", "b"))` | adiciona `Skill(a)`, `Skill(b)` | `[user, project]` | `["a", "b"]` |
| `.skills(List.of())` | nenhuma | `[user, project]` | `[]` |

Observações:

- **`null` ≠ skills desligadas.** Omitir a opção mantém intactos os padrões do CLI. Para retirar
  todas as skills da listagem que o modelo vê, passe uma lista vazia.
- **`"all"` e omitido são equivalentes no protocolo.** Ambos omitem o campo `skills` na requisição
  de controle initialize. O CLI trata a omissão como "sem filtro".
- **A lista vazia é enviada no protocolo.** `List.of()` vira `"skills": []` na requisição
  initialize, dizendo aos CLIs compatíveis para não carregar skill alguma no prompt de sistema.

## Como funciona a ligação automática

`SubprocessCLITransport.applySkillsDefaults()` monta as flags efetivas do CLI antes de o
subprocesso ser criado. O `ClaudeAgentOptions` original nunca é modificado.

Para `skillsAll()`:

- Se `allowedTools` ainda não contiver `"Skill"`, a ferramenta simples é acrescentada.
- Se `settingSources` for null, o padrão passa a ser `[USER, PROJECT]` para que o CLI descubra as
  skills instaladas.
- Um `settingSources(...)` explícito sempre prevalece sobre o padrão.

Para `skills(List.of(...))`:

- Para cada nome `n`, acrescenta `Skill(n)` a `allowedTools` (com desduplicação contra as entradas
  existentes).
- O mesmo comportamento padrão de `settingSources`.

Para `skills(List.of())`:

- `allowedTools` fica inalterado.
- O mesmo comportamento padrão de `settingSources`.

## Validação de nomes (0.1.22)

Os nomes passados a `skills(List.of(...))` são validados antes de serem formatados no valor
`--allowedTools` do CLI. Toda rejeição lança `IllegalArgumentException` **no momento da conexão** —
a partir de `buildCommand()`, antes de o subprocesso do CLI ser criado.

A validação existe porque `--allowedTools` é uma única string que o CLI divide em regras de
permissão por vírgulas e espaços fora de parênteses, e esse tokenizador não reconhece nenhuma
sequência de escape. O escape só existe na gramática de cada regra, aplicada *depois* da divisão,
então um nome que carregue um delimitador não pode ser repassado de forma confiável — no que ele se
tokeniza depende do que estiver ao redor:

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

### Formas rejeitadas

| Forma | Exemplo | Por quê |
|---|---|---|
| Parênteses ou vírgulas | `"x),Bash(*"`, `"a,b"`, `"()"` | delimitadores de regra — o vetor de injeção acima |
| Caracteres de controle | C0 (`\n`, `\t`, `\u0000`), DEL (`\u007F`), C1 (`\u0080`–`\u009F`) | nunca aparecem no nome de um diretório de skill |
| Byte-order mark | `﻿` em qualquer posição do nome | o CLI apara U+FEFF como espaço; a regra nomearia outra skill |
| Vazio ou só espaços | `""`, `" "`, `"  \t "` | não nomeia nada |
| Curinga puro | `"*"` | use `skillsAll()` em vez disso |
| Sufixo curinga | `"pdf:*"`, `"my skill *"` | liste cada skill pelo nome exato |
| Espaços ao redor | `" pdf"`, `"pdf "` | nunca casaria — a ferramenta `Skill` apara o nome invocado |
| `/` no início | `"/commit"` | a opção recebe o nome canônico, não a forma de comando com barra |
| Barras invertidas consecutivas | `"mid\\\\dle"` | o parser de cada regra as colapsa, então a regra nomearia outra skill |
| Barra invertida final sem par | `"name\\"` | escape solto |
| Substituto sem par | um `\ud800` sozinho | nunca poderia casar com um nome que o CLI descobriu |

Só as três primeiras linhas são vetores de injeção. As demais se tokenizam bem, mas construiriam uma
regra que jamais casaria com a skill indicada — são rejeitadas para que a falha apareça alto e claro
no `connect()`, em vez de a skill sumir silenciosamente da sessão. Os exemplos de string acima estão
como literais de código Java, então `"mid\\\\dle"` é um nome com duas barras invertidas e
`"dir\\sub"` (aceito) tem uma.

### Nomes aceitos

Nomes comuns não são afetados. Todos estes continuam gerando exatamente o mesmo argv de antes:

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

### Mudanças incompatíveis

Duas formas antes aceitas agora lançam exceção:

| Antes | Comportamento anterior | Agora |
|---|---|---|
| `skills(List.of("*"))`, `skills(List.of("plugin:*"))` | criava uma regra curinga | lança exceção — use `skillsAll()` ou adicione uma entrada `Skill(...)` diretamente em `allowedTools` para casamento por prefixo |
| `skills(List.of(" name"))`, `skills(List.of("/name"))` | criava uma regra que não casava com nada, deixando a skill **silenciosamente indisponível** | lança exceção, nomeando o problema |

### Comportamento específico do Java

Duas verificações diferem de propósito do SDK Python, porque as linguagens modelam strings de forma
distinta:

- **Substitutos (surrogates).** O Python rejeita todo code point substituto — o que faz sentido lá,
  já que uma `str` do Python guarda code points e um caractere astral é um único item não
  substituto, de modo que qualquer substituto presente está, por construção, sem par. Strings Java
  são UTF-16, onde um caractere astral legitimamente *é* um par alto/baixo. Por isso o Java rejeita
  apenas substitutos **solitários**; um nome como `"𝕤kill"` é aceito.
- **Espaços em branco.** `String.strip()` segue `Character.isWhitespace`, que deixa passar os
  espaços não separáveis (U+00A0, U+2007, U+202F) que o `str.strip()` do Python remove. A
  verificação de preenchimento faz a união com `Character.isSpaceChar`, então esses também são
  pegos. U+FEFF fica fora de ambos e é rejeitado como caractere inválido, exatamente como no Python.

`skillsAll()` não passa por checagem de nome — não há nome a checar — e `skills(List.of())` continua
sendo um no-op válido.

## Protocolo do initialize

As skills também trafegam pelo protocolo de controle do SDK via
`SDKControlInitializeRequest.skills`. Apenas uma lista explícita é enviada; `"all"` e `null` omitem
o campo.

CLIs mais antigos que não reconhecem o campo `skills` do initialize simplesmente o ignoram — as
entradas de `allowedTools` injetadas automaticamente continuam valendo.

> **Obsolescência:** passar o token `"Skill"` puro em `allowedTools(...)` (ou em
> `AgentDefinition.tools`) está **obsoleto**. Use `skillsAll()` / `skills(List.of(...))` — eles
> configuram tudo o que é necessário (inclusive permitir a ferramenta `Skill`) e evitam divergências
> entre `allowedTools` e o campo `skills` do protocolo.

## Exemplos

### Combinando com um `allowedTools` explícito

As skills complementam, nunca substituem, uma lista de permissão existente:

```java
var options = ClaudeAgentOptions.builder()
    .allowedTools(List.of("Read", "Write"))
    .skills(List.of("commit"))
    .build();
// effective allowedTools: [Read, Write, Skill(commit)]
```

### Injeção idempotente

Se você já adicionou `Skill` ou `Skill(name)` à sua lista de permissão, o SDK não duplica:

```java
var options = ClaudeAgentOptions.builder()
    .allowedTools(List.of("Skill(pdf)"))
    .skills(List.of("pdf"))
    .build();
// effective allowedTools: [Skill(pdf)]   (not [Skill(pdf), Skill(pdf)])
```

### Preservando um `settingSources` explícito

```java
var options = ClaudeAgentOptions.builder()
    .skillsAll()
    .settingSources(List.of(SettingSource.LOCAL))
    .build();
// effective settingSources: [LOCAL]   (your value wins over the [USER, PROJECT] default)
```

## Nota de segurança

A opção `skills` é um **filtro de contexto, não um sandbox.** Skills não listadas ficam ocultas da
listagem que o modelo vê e não podem ser invocadas pela ferramenta `Skill`, mas seus arquivos
continuam no disco — uma sessão com `Read` ou `Bash` ainda consegue acessar `.claude/skills/**`
diretamente.

Para isolamento rígido:

- Aponte `cwd` para um diretório cujo `.claude/skills/` contenha apenas o subconjunto desejado, **ou**
- Adicione regras de negação de permissão para `Read`/`Bash` nos caminhos das skills.

Skills que vêm junto e skills de plugins instalados são descobertas independentemente de
`settingSources`. A lista de permissão `skills` é o único mecanismo que as esconde da listagem do
modelo.

**Não guarde segredos em arquivos de skill.**

## Exemplo completo

Veja [`examples/SkillsExample.java`](../../examples/src/main/java/examples/SkillsExample.java) para
uma demonstração executável dos três modos.

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

## Veja também

- [Opções de configuração](./feature-configuration-options.md) — a API completa do builder
- [Definições de agente](./feature-agents.md) — o campo `skills` em `AgentDefinition` (lista por subagente)
- [Histórico de sessões](./feature-session-history.md) — ler transcrições do disco
