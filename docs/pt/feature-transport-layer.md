# Camada de transporte

Implementações de transporte próprias para a comunicação com o Claude Code.

> **Sobre esta tradução**: a documentação em inglês é a única autoritativa. Esta tradução pode estar desatualizada em relação ao [original em inglês](../feature-transport-layer.md); em caso de divergência, o inglês prevalece. Os blocos de código são mantidos idênticos ao original e não foram traduzidos.

## Visão geral

A camada de transporte cuida da E/S com o CLI do Claude Code. Você pode implementar transportes
próprios para conexões remotas ou outros meios de comunicação.

## A interface Transport

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

## Implementação padrão

### SubprocessCLITransport

O transporte padrão cria o CLI do Claude Code como um subprocesso. Ele é construído somente a partir
das options — o prompt e o modo de streaming são levados por `ClaudeAgentOptions` e pelo fluxo de
mensagens, não pelo transporte:

```java
Transport transport = new SubprocessCLITransport(options);
```

**Recursos**:
- Gerencia o ciclo de vida do subprocesso
- Comunicação por stdin/stdout
- Leitura com buffer
- Suporte a callback de stderr
- Limpeza automática
- Desligamento gracioso com período de tolerância (espera o subprocesso gravar o arquivo de sessão após o EOF do stdin antes de enviar SIGTERM)
- Define `CLAUDE_CODE_ENTRYPOINT=sdk-java` por padrão (sobrescrevível via `ClaudeAgentOptions.env()`)
- Define `CLAUDE_CODE_SDK_READS_SESSION_STATE=1`, a menos que o `env()` de quem chama ou o ambiente herdado já a nomeie (com qualquer combinação de maiúsculas e minúsculas), para que o CLI envie quadros `session_state_changed` marcados com `sdk_host_only`; o `QueryHandler` os lê para decidir quando o stdin pode ser fechado e os descarta antes do consumidor. Veja [Arquitetura → Ciclo de vida do stdin](./architecture.md#ciclo-de-vida-do-stdin-e-o-fim-de-uma-execução). A lista completa de variáveis que o transporte define está em [Opções de configuração → env()](./feature-configuration-options.md#env).
- Emite um aviso no momento da conexão (log `WARNING`) quando o CLI é anterior à 2.0.0, e quando `verbatimPrompts` está ativado mas o CLI é anterior à 2.1.248, que ignora `client_composed`. A verificação de versão executa `<cli> -v` e é pulada quando `CLAUDE_AGENT_SDK_SKIP_VERSION_CHECK` está definida.

### Encaminhamento de flags do CLI

`SubprocessCLITransport.buildCommand()` traduz `ClaudeAgentOptions` em flags do CLI. Flags relevantes
para as opções mais recentes:

| Opção | Flag(s) do CLI | Observações |
|---|---|---|
| `sessionStore(...)` | `--session-mirror` | Adicionada quando `sessionStore != null`. Diz ao CLI para emitir quadros `transcript_mirror` no stdout, que o SDK separa e encaminha ao `SessionStore` configurado. |
| `thinking(ThinkingConfigAdaptive(SUMMARIZED))` | `--thinking adaptive --thinking-display summarized` | `--thinking-display` só é encaminhada para configurações `Adaptive` e `Enabled` (e apenas quando `display != null`); `Disabled` nunca a emite. |
| `thinking(ThinkingConfigEnabled(20000, OMITTED))` | `--max-thinking-tokens 20000 --thinking-display omitted` | As duas flags saem juntas quando `display` está definido. |
| `systemPrompt(SystemPromptCustom.of(p, …))` | `--system-prompt <p>` | O mesmo que uma string simples; `snapshot` viaja na requisição `initialize`. |
| `plugins(List.of(SdkPluginConfig.local(dir)))` | `--plugin-dir <dir>` | Um por plugin `local`; os demais tipos são ignorados. |

**Encanamento do stderr**: o stderr só é redirecionado quando `options.stderrCallback() != null`. A
antiga detecção do argumento extra `--debug-to-stderr` foi removida na 0.1.13 (preparação para a
obsolescência dessa flag do CLI). Para capturar a saída de depuração detalhada do CLI, passe
`extraArgs(Map.of("debug-file", "/path/to/log"))` e leia esse arquivo.

**Isolamento do callback de stderr** (0.1.16): cada chamada `stderrCallback.accept(line)` é envolvida
num `try/catch(Throwable)` por linha. Um callback que lança é capturado, registrado em `FINE`, e o
laço de leitura continua com a próxima linha. Antes, uma exceção saía do laço e descartava em
silêncio todas as linhas de stderr seguintes pelo resto da sessão. Falhas do laço externo (fluxo
fechado inesperadamente, erros de E/S) também são registradas em `FINE` em vez de engolidas.

**Limpeza de filhos órfãos** (0.1.18): todo processo de CLI criado é registrado num conjunto estático
`ACTIVE_CHILDREN`, e um shutdown hook da JVM encerra, com o melhor esforço, os que ainda estiverem
vivos caso o processo saia antes de `close()` rodar. O `close()` escalona o encerramento (período de
tolerância → `destroy()` / SIGTERM → `destroyForcibly()` / SIGKILL) e só então remove o processo de
`ACTIVE_CHILDREN`, **apenas depois de confirmar que ele não está mais vivo** (`!process.isAlive()`).
Um filho que de algum modo sobreviva ao escalonamento (um kill em corrida, ou um `waitFor` que expirou)
continua, portanto, sendo rastreado, de modo que o coletor do shutdown hook ainda tenha uma chance com
ele em vez de vazar como um processo `claude` órfão.

**Proteção contra injeção de flags em argv por `resume` / `sessionId`** (0.1.19): `buildCommand()`
emite `resume` e `sessionId` como um único token argv `--flag=value` (`--resume=<value>`,
`--session-id=<value>`) em vez de dois tokens separados (`--resume`, `<value>`). O CLI declara
`--resume` com valor *opcional*, então, na forma de dois tokens, um valor iniciado por traço não fica
vinculado à flag e é interpretado como uma flag independente. Uma aplicação que encaminhe entrada não
confiável para `resume`/`sessionId` (por exemplo, um endpoint "retomar minha sessão" que lê um ID de
sessão da requisição) poderia assim injetar flags arbitrárias — `resume("--version")` executava
silenciosamente `claude --version` e devolvia zero mensagens. A forma com sinal de igual sempre
vincula o valor à flag, e o CLI então rejeita um valor iniciado por traço como ID de sessão inválido.
Isso é no nível de argv (um argumento por opção, **nenhum shell envolvido**), portanto é injeção de
flag, não execução de comandos, e só afeta aplicações que encaminham entrada não confiável para essas
opções. Segue o mesmo estilo `--setting-sources=` já usado em outros pontos de `buildCommand()`.

### Windows: recusa de CLI em script batch (0.1.21)

O Windows não tem mecanismo de shebang. Quando o caminho do CLI aponta para um arquivo `.bat` ou
`.cmd`, o sistema operacional o executa reescrevendo a criação do processo como uma invocação
`cmd.exe /c`, e **o cmd.exe reinterpreta toda a linha de comando** no momento da execução. A colocação
de aspas nos argumentos segue as regras de argv do MSVCRT — que só acrescentam aspas em torno de
espaços em branco — e não as do cmd.exe, então metacaracteres do cmd.exe dentro do valor de um
argumento (um título de sessão em `--resume`, o JSON de `--mcp-config`, um prompt de sistema) chegam
ao cmd.exe sem escape e podem executar comandos injetados antes mesmo de o CLI iniciar.

A forma `--flag=value` da 0.1.19 não ajuda nesse caminho: depois que o cmd.exe reinterpreta a string,
não sobra nenhuma fronteira de argv para proteger. Não existe escape confiável para o cmd.exe
(`%VAR%` expande até dentro de aspas duplas), então **recusar é a única correção robusta** — a mesma
que o Node.js adotou para essa classe de vulnerabilidade (CVE-2024-27980, "BatBadBut").

O `connect()` valida o caminho resolvido antes de qualquer processo ser criado com ele, então a
verificação cobre todas as rotas até o executável: descoberta pelo PATH, um
`ClaudeAgentOptions.cliPath(...)` explícito e a sondagem de versão que roda antes do processo
principal.

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

**Correção** (todas evitam o cmd.exe por completo): instale o Claude Code nativamente com
`irm https://claude.ai/install.ps1 | iex`, ou aponte `cliPath` para um `claude.exe`. Se migrar de um
`claude.cmd` instalado via npm estiver genuinamente bloqueado, veja [a habilitação explícita](#windows-habilitação-de-cli-em-batch-0122)
abaixo.

A verificação de extensão normaliza do jeito que o Win32 faz e classifica **todos** os componentes do
caminho, não apenas o último:

| Grafia | Recusada | Por quê |
|---|---|---|
| `C:\npm\claude.cmd` | sim | o caso simples |
| `C:\npm\claude.CMD` | sim | a comparação de extensão não diferencia maiúsculas |
| `C:\npm\claude.cmd ` / `claude.cmd.` | sim | o Windows remove pontos e espaços finais na resolução do caminho |
| `C:\npm\claude.cmd:stream` | sim | uma especificação de fluxo NTFS ainda abre o arquivo base |
| `C:\npm\claude:evil.cmd` | sim | o Win32 encontra a extensão varrendo o último ponto do componente inteiro, inclusive a especificação de fluxo |
| `C:claude.cmd` | sim | o prefixo de unidade vai no mesmo componente |
| `.cmd` | sim | `PathFindExtension` trata um `.cmd` isolado como extensão |
| `C:\claude.cmd\..\claude.exe` | sim | qualquer componente conta — a normalização de `.`/`..` não o disfarça |
| `C:\bin\claude.exe` | não | executável nativo |
| `/opt/claude.cmd` no Linux/macOS | não | no POSIX não há passagem pelo cmd.exe; `.cmd` é um nome de arquivo comum |

A verificação usa deliberadamente lógica simples de strings em vez de `java.nio.file.Path`: a análise
de caminhos difere entre POSIX e Windows, e só a lógica de strings se comporta igual nos dois.
Classificar todos os componentes fecha de uma vez toda a classe de truques de normalização, e não
custa nada de legítimo — nenhum `claude.exe` de verdade fica sob um diretório com nome de arquivo
batch.

### Windows: ordem de descoberta do CLI (0.1.21)

A descoberta prefere um executável nativo, porque um `claude` sem extensão no Windows é um script
wrapper de git-bash / WSL que o sistema operacional não consegue executar diretamente:

1. Varra o `PATH` **inteiro** procurando `claude.exe` primeiro. O PATH é percorrido por diretório,
   então, sem isso, um script wrapper num diretório anterior encobriria um `claude.exe` de verdade
   instalado num diretório posterior.
2. Caso contrário, recorra a `~/.local/bin/claude.exe`. As localizações no formato POSIX
   deliberadamente **não** são sondadas no Windows: uma correspondência sem extensão ali substituiria a
   recusa explicativa por uma falha de criação de processo obscura, e um `/usr/local/bin/claude` com
   raiz mas sem unidade resolve contra a unidade atual — um local que outro usuário local pode criar,
   o que o torna um vetor de plantio de binário.
3. Caso contrário, devolva um shim `claude.cmd` / `claude.bat` se houver um no `PATH`, justamente para
   que `connect()` lance a recusa de script batch *com sua correção* em vez de um mero erro de "não
   encontrado".
4. Caso contrário, devolva um acerto não nativo do `PATH` para que o erro de criação diga o que de
   fato está instalado.
5. Caso contrário, lance `CLINotFoundException` com uma mensagem específica do Windows apontando para o
   instalador nativo. Ela não recomenda `npm install -g @anthropic-ai/claude-code`, porque isso produz
   exatamente o shim que este SDK recusa.

A descoberta no POSIX permanece a mesma: `PATH`, depois `~/.npm-global/bin`, `/usr/local/bin`,
`~/.local/bin`, `~/node_modules/.bin`, `~/.yarn/bin`, `~/.claude/local`.

### Windows: rejeição de metacaracteres do cmd.exe (0.1.21)

Defesa em profundidade. Com a criação via batch recusada, esses caracteres já são inofensivos, mas
`resume` e `sessionId` são os valores que as aplicações mais costumam pegar de entrada externa, então
são rejeitados mesmo assim — mantendo-os inertes caso uma passagem pelo cmd.exe volte a existir entre
o SDK e o CLI.

No Windows, um `resume` ou `sessionId` contendo `&`, `|`, `<`, `>`, `^`, `%`, `!`, `"`, CR ou LF
lança `IllegalArgumentException` a partir de `buildCommand()`:

```java
// On Windows: IllegalArgumentException
ClaudeAgentOptions.builder().resume("R&D notes").build();

// Accepted — no format is imposed beyond the metacharacter check;
// resume values may be arbitrary session titles, not only UUIDs
ClaudeAgentOptions.builder().resume("Refactor the parser (part 2)").build();
```

**O comportamento no POSIX não muda** — não há cmd.exe do qual se proteger, então
`resume("R&D notes")` é repassado como está.

### Vinculação de valores em `extraArgs` (0.1.21)

`buildCommand()` emite uma entrada de `extraArgs` cujo valor começa com `-` como um único token
`--flag=value`, e não dois. É a mesma classe de injeção que a mudança em `resume`/`sessionId` fechou,
aplicada ao último ponto de chamada com dois tokens: na forma de dois tokens, um valor iniciado por
traço não fica vinculado à sua flag quando o CLI declara aquela opção com valor opcional, e é
interpretado como outra flag.

| Entrada de `extraArgs` | argv emitido |
|---|---|
| `Map.of("some-flag", "value")` | `--some-flag`, `value` |
| `Map.of("some-flag", "--evil")` | `--some-flag=--evil` |
| `Map.of("verbose-thing", "")` | `--verbose-thing` (flag booleana simples) |

### Windows: habilitação de CLI em batch (0.1.22)

Para implantações que não conseguem migrar de um `claude.cmd` instalado via npm — distribuição de
software gerenciada centralmente, por exemplo — a recusa acima pode ser dispensada explicitamente:

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .cliPath(Path.of("C:\\Users\\Administrator\\AppData\\Roaming\\npm\\claude.cmd"))
    .allowUnsafeWindowsBatchCli(true)
    .build();
```

O padrão é `false`; nada muda na recusa para quem não define essa opção.

#### Por que isto não é um simples bypass

A implementação óbvia — pular a verificação quando a flag estiver ligada — devolveria todo o buraco de
reinterpretação do cmd.exe. O detalhe relevante está no próprio JDK:

```java
// OpenJDK, src/java.base/windows/classes/java/lang/ProcessImpl.java
final String value = System.getProperty("jdk.lang.Process.allowAmbiguousCommands", "true");
final boolean allowAmbiguousCommands = !"false".equalsIgnoreCase(value);
if (allowAmbiguousCommands) {
    cmdstr = createCommandLine(VERIFICATION_LEGACY, executablePath, cmd);  // escape set: ""
```

A propriedade tem padrão `"true"`, selecionando um modo legado cujo conjunto de escape é **vazio** —
nada além de espaços em branco é colocado entre aspas, e aspas embutidas são aceitas. **Numa JVM
padrão, o Java está exposto a essa classe de vulnerabilidade exatamente como o Node.js esteve**; a
recusa não é apenas um raciocínio herdado de outro ecossistema.

Defina a propriedade como `false` e um alvo que não seja `.exe` passa a selecionar
`VERIFICATION_CMD_BAT`, cujo conjunto de escape é `"<>&|^`: argumentos que contenham esses caracteres
ou espaços são colocados entre aspas, e um argumento com aspas embutidas lança exceção de imediato.

Então a habilitação exige esse modo e acrescenta a peça que o JDK omite:

1. **`-Djdk.lang.Process.allowAmbiguousCommands=false` é obrigatório.** O `connect()` lança
   `CLIConnectionException` se a propriedade for qualquer coisa diferente de `false` — seguindo a
   própria leitura do JDK, `!"false".equalsIgnoreCase(value)`, em vez de inventar outra noção de
   verdade. A mensagem indica a flag a acrescentar.
2. **Todo argumento do CLI é varrido** em busca de `& | < > ^ % ! "` e CR/LF, lançando
   `IllegalArgumentException` que nomeia a opção problemática. `%` e `!` não estão no conjunto de
   escape do JDK, e as aspas *não* impedem a expansão de `%VAR%` — um `%FOO%` sem aspas que expanda
   para `x&calc` é reinterpretado. Isso fecha esse vetor do lado dos argumentos. O caminho do
   executável em argv[0] não é varrido: não é dado de argumento fornecido por quem chama e já foi
   classificado.
3. **Um `WARNING` é registrado uma vez por transporte**, nomeando o caminho e o risco aceito.

O resultado é uma superfície de ataque *mais estreita* do que simplesmente não ter a verificação.

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

#### Risco residual

O cmd.exe expande `%VAR%` a partir do **ambiente**. A varredura de argv fecha o vetor do lado dos
argumentos; ela não ajuda se um atacante controlar o ambiente que a JVM passa ao CLI. Use a
habilitação só onde o caminho do CLI e todos os valores de argumento sejam controlados por
administradores, e trate-a como uma ponte de migração, não como destino.

O POSIX não é afetado em nada disso — não há passagem pelo cmd.exe, então um arquivo `.cmd` é um nome
de arquivo comum e nem a recusa nem as condições da habilitação se aplicam.

Veja [`examples/WindowsBatchCliExample.java`](../../examples/src/main/java/examples/WindowsBatchCliExample.java).

### Injeção em `--allowedTools` por nome de skill (0.1.22)

As três entradas de proteção acima dizem respeito a fronteiras de *argv* — um argumento por opção, sem
shell envolvido. Esta é diferente: é uma injeção **dentro do valor de um único argumento**.

`applySkillsDefaults()` formata cada entrada de `ClaudeAgentOptions.skills(List)` como `Skill(<name>)`
e junta o resultado na única string `--allowedTools`. O CLI então divide essa string de volta em
regras de permissão por vírgulas e espaços fora de parênteses, e **esse tokenizador não reconhece
nenhuma sequência de escape** — o escape só existe na gramática de cada regra, aplicada depois da
divisão. Um nome que carregue um delimitador, portanto, não pode ser repassado de forma confiável; no
que ele se tokeniza depende do que estiver ao redor.

```java
// Before 0.1.22:  --allowedTools "Skill(x),Bash(*)"
//                                          ^^^^^^^ an extra rule, never requested
ClaudeAgentOptions.builder().skills(List.of("x),Bash(*")).build();
```

`validateSkillName()` agora roda em cada entrada antes da formatação, então a rejeição aparece a
partir de `buildCommand()` — no `connect()`, antes de o subprocesso ser criado. Ela rejeita parênteses,
vírgulas, caracteres de controle (C0, DEL, C1), byte-order marks, nomes vazios, um `*` literal e
sufixos curinga; e, para que uma regra morta não conceda nada em silêncio, também espaços ao redor, um
`/` inicial, barras invertidas consecutivas, uma barra invertida final sem par e substitutos
solitários. Nomes comuns — com qualificação de plugin, espaços internos, barras invertidas simples,
não ASCII — constroem exatamente o mesmo argv de antes.

`rejectNonListSkills()` protege o próprio formato do valor. O par `skills(List)` / `skillsAll()` do
builder já torna inalcançável uma string simples ou um iterável que não seja lista — o sistema de tipos
do Java faz aqui o que o SDK Python impõe em tempo de execução —, mas a verificação é mantida para que
um chamador com tipos crus ou por reflexão falhe de forma segura em vez de instalar silenciosamente
nenhum filtro de skill.

Tabela completa de rejeições, lista de nomes aceitos e as duas divergências deliberadas em relação ao
SDK Python (substitutos solitários vs. quaisquer, remoção de espaços não separáveis): veja
[Skills → Validação de nomes](./feature-skills.md#validação-de-nomes-0122).

## Transporte próprio

Implemente a interface `Transport` para uma comunicação própria:

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

## Usando um transporte próprio

Com um transporte próprio, o SDK chama o `connect()` dele, entrega os prompts por `write()` e envia
hooks, agentes e as demais configurações da requisição `initialize` (incluindo
`systemPromptSnapshot`) pelo protocolo de controle. Tudo o que o `SubprocessCLITransport` converte
em flags do CLI ou variáveis de ambiente — model, cwd, modo de permissão, ferramentas, `env` e assim
por diante — **não** é aplicado, e a retomada de sessão a partir de um store é ignorada.
`verbatimPrompts` continua valendo, já que o SDK marca os prompts antes de chamar `write()`.

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

## Boas práticas

1. **Segurança entre threads**: garanta que write() seja thread-safe
2. **Gerenciamento de recursos**: implemente close() corretamente
3. **Tratamento de erros**: lance CLIConnectionException para erros
4. **Leituras bloqueantes**: readMessages() deve bloquear até haver dados
5. **Formato JSON**: as mensagens devem ser objetos JSON, um por linha

## Veja também
- [Arquitetura](./architecture.md#4-camada-de-transporte) — o design da camada de transporte
- Código-fonte do transporte: `sdk/src/main/java/in/vidyalai/claude/sdk/transport/`
