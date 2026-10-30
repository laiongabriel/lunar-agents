# NoHitDelay-Agent

Agent Java separado (não é mod Weave, nem DLL) que remove o delay de 10 ticks
aplicado depois de um **clique esquerdo que erra** no Minecraft 1.8.9.

No 1.8.9 o `leftClickCounter` só recebe `10` nos dois caminhos de erro do
`clickMouse` (raycast nulo e `MISS`). Acertos em bloco/entidade não passam por
esse contador, então o comportamento de hit normal não muda.

## O que este agent não altera

- **Acerto em entidade**: o dano é limitado pelo `hurtResistantTime` da entidade
  (invulnerabilidade de ~0,5 s), calculado e aplicado pelo **servidor**. Não é um
  delay do cliente; alterá-lo no cliente não aumenta o dano em multiplayer.
- **Quebra de bloco**: o `PlayerControllerMP.blockHitDelay` (5 ticks após quebrar
  um bloco) é outra mecânica, de "fast break", e não faz parte do NoHitDelay das
  referências.

## O que foi aproveitado de cada referência

| Origem | O que foi aproveitado |
| --- | --- |
| [WeaveNoHitDelay](https://github.com/Nilsen84/WeaveNoHitDelay) | Foco no método `clickMouse` (não varrer a classe inteira às cegas) e a ideia de trocar `BIPUSH 10` por `0`. |
| [NoHitDelayDll](https://github.com/Nilsen84/NoHitDelayDll) | Localização por padrão `bipush 10; putfield` (`0x10 0x0A 0xB5`), suporte a múltiplos mapeamentos (`net/minecraft/client/Minecraft`, `net/minecraft/class_1600`, `ave`), validação da quantidade de substituições e log de status. |
| hitdelayfix (código enviado) | Localizar o método pela string de log exclusiva `Null returned as 'hitResult', this shouldn't happen!` em vez do nome — útil se um mapeamento renomear `clickMouse`. |
| Todas | O alvo real: exatamente **2** atribuições `leftClickCounter = 10`, ambas dentro de `clickMouse`. |

O que **não** foi trazido:

- A dependência do Weave Loader e o `orElseThrow()` (que quebra o carregamento
  se o mapeamento mudar).
- O `.dll`/JVMTI: o loader já injeta `-javaagent`, então não há motivo para
  compilar código nativo. O conceito de "anexar a um processo já rodando" foi
  mantido via `agentmain` + `retransformClasses`.
- A checagem `getInterfaces().length == 3` do hitdelayfix: o `Minecraft` vanilla
  1.8.9 implementa 2 interfaces (`IThreadListener`, `IPlayerUsage`), então esse
  teste é frágil e não identifica a classe de forma confiável.
- O patch cego de todo `BIPUSH 10` do método: aqui só entra o que é seguido de
  `PUTFIELD` de um campo `int` da própria classe.

## Estratégia de patch (do mais preciso para o mais tolerante)

1. `leftClickCounter` dentro de `clickMouse` — caso MCP/Lunar.
2. Qualquer campo int único atribuído dentro de `clickMouse` — mapeamento
   diferente, mas ainda com escopo no método (ideia do Weave).
3. Campo `leftClickCounter` em qualquer método — caso o método seja renomeado.
4. Um único campo int da classe recebendo a constante — caso totalmente
   ofuscado (ideia do DLL).

O `clickMouse` é localizado pelo nome; se o nome não existir, pela string de log
exclusiva (ideia do hitdelayfix).

Só instruções `BIPUSH 10` imediatamente seguidas de `PUTFIELD` de um campo `int`
da própria classe são alteradas. `leftClickCounter = 0` (soltar o botão) e
`leftClickCounter = 10000` (recuperar foco) usam `ICONST_0`/`SIPUSH` e ficam
intactos.

A troca é `BIPUSH 10` → `BIPUSH 0`, mantendo o tamanho da instrução, então os
stack map frames não precisam ser recalculados.

## Validação e segurança

- Se o padrão não for encontrado, a classe **não** é alterada e o motivo vai para
  o log (em vez de derrubar o carregamento).
- Se a quantidade encontrada for diferente de 2, aplica e registra um aviso.
- Idempotente: uma segunda transformação não encontra mais `BIPUSH 10` e retorna
  sem mudanças.

## Build

Requer JDK 17 (`$env:JAVA_HOME`, ou `C:\Program Files\Zulu\zulu-17` sem a
variável) e ASM 9.2 no cache do Gradle (os mesmos jars usados pelos outros
agents; fora do cache padrão, defina `ASM_HOME`).

```powershell
powershell -ExecutionPolicy Bypass -File .\build.ps1
```

Gera `%TEMP%\nhdbuild\nohitdelay.jar`.

Para instalar em `$env:LUNAR_AGENTS` (ou `C:\Users\laion\Desktop\Lunar\agents`
sem a variável):

```powershell
powershell -ExecutionPolicy Bypass -File .\build.ps1 -Install
```

## Teste offline

Feito com um `net.minecraft.client.Minecraft` sintético contendo os dois
caminhos de miss, o `= 0`, o `= 10000` e o decremento do `runTick`:

| Cenário | Original | Patchado |
| --- | --- | --- |
| clique que erra | 10 | 0 |
| após 1 tick | 9 | 0 |
| segundo clique | 10 | 0 |
| soltar botão (`= 0`) | 0 | 0 |
| recuperar foco (`= 10000`) | 10000 | 10000 |

Bytecode conferido com `javap`: as duas ocorrências viraram `bipush 0`; o
`iconst_0` e o `sipush 10000` continuam iguais. Verificação do JVM com
`-Xverify:all` passou.

Segundo teste, forçando o fallback por string: o método foi renomeado para
`zap()` e o campo para `delay`, mantendo só a string de log. O log mostrou
`patched 2 assignment(s) via string marker ... (zap()V, zap()V)` e o contador
caiu de 10 para 0, também com `-Xverify:all`. O caminho normal continua sendo
usado primeiro e o patch segue idempotente (segunda passada não altera nada).

## Log

`%TEMP%\nohitdelay-status.log` — mostra quantas atribuições foram patchadas, por
qual estratégia e em quais métodos.

## Coexistência

Funciona junto com `fastplace.jar` (que mexe em `rightClickDelayTimer`) e
`accuratecps.jar` (que injeta contadores no `Minecraft`). Os três alteram pontos
diferentes da mesma classe; a ordem de injeção não importa.

## Créditos

Lógica original de referência: [Nilsen84](https://github.com/Nilsen84)
(WeaveNoHitDelay e NoHitDelayDll). A ideia de localizar o `clickMouse` pela
string de log veio do código `me.onils.hitdelayfix`.
