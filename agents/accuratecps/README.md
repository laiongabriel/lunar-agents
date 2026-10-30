# AccurateCPS-Agent

Agent Java separado (não é mod Weave) que faz o HUD de CPS do Lunar contar as
**ações reais** de clique do Minecraft, em vez dos eventos crus do mouse.

Com o agent de FastPlace segurando o botão direito a 10 CPS, o Lunar mostra os
cliques brutos (que continuam em ~5 CPS, porque o botão só é pressionado uma vez).
Este agent conta `Minecraft.rightClickMouse()`, que é chamado a cada 2 ticks com o
FastPlace, e faz o HUD mostrar ~10 CPS reais.

## Como funciona

Na transformação de classes (ASM):

1. `net.minecraft.client.Minecraft` recebe:
   - os campos/contadores de `CpsRuntime` (copiados para dentro da própria classe);
   - uma chamada no início de `clickMouse`, `rightClickMouse` e `runTick`.
2. Os getters de CPS do tracker do Lunar
   (`...RRRRRIRCCIIORHHCCCHICOCRRCOOOH`) são localizados por forma:
   - getter esquerdo: `(Z)I` que lê `LongList` e chama `size()`;
   - getter direito: `()I` que lê a `LongList` preenchida no ramo `ICONST_1` /
     `IF_ICMPNE` do handler de registro.
   Os corpos são substituídos por chamadas a `Minecraft.fastcps$left()` e
   `Minecraft.fastcps$right()`.

O estado vive dentro do `Minecraft` porque o `GenesisClassLoader` do Lunar não
enxerga classes do jar do agent.

## Build

Requer JDK 17 (`$env:JAVA_HOME`, ou `C:\Program Files\Zulu\zulu-17` sem a
variável) e os jars do ASM 9.2 no cache do Gradle (os mesmos usados pelo agent
do FastPlace; fora do cache padrão, defina `ASM_HOME`).

```powershell
powershell -ExecutionPolicy Bypass -File .\build.ps1
```

Gera `%TEMP%\cpsbuild\accuratecps.jar`.

Para já instalar em `$env:LUNAR_AGENTS` (ou `C:\Users\laion\Desktop\Lunar\agents`
sem a variável):

```powershell
powershell -ExecutionPolicy Bypass -File .\build.ps1 -Install
```

## Teste offline

O transform foi validado com:

- um `net.minecraft.client.Minecraft` sintético (injeção dos contadores e dos
  hooks, incluindo verificação do bytecode com `-Xverify:all`);
- a classe real do tracker extraída de
  `~/.lunarclient/offline/multiver/lunar.jar` (getters corretos substituídos).

## Log

`%TEMP%\accuratecps-status.log` — mostra o que foi patchado e os getters
encontrados.

## Coexistência com o FastPlace

`fastplace.jar` e `accuratecps.jar` ficam ambos em `Desktop\Lunar\agents` e são
injetados como `-javaagent`. Os dois alteram `Minecraft` em pontos diferentes
(constante do delay vs. contadores), então a ordem de carregamento não importa.
