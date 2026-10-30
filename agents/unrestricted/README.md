# Unrestricted-Agent

Agent Java separado (não é mod Weave) que aplica os 5 hooks do
[weave-unrestricted](https://github.com/marioparaschiv/weave-unrestricted) —
um port do [unrestricted](https://github.com/Youded-byte/unrestricted), do
Youded — via `-javaagent`, no mesmo esquema do `fastplace.jar`,
`accuratecps.jar` e `nohitdelay.jar`.

O mod original registrava um `ClassFileTransformer` pelo
`ModInitializer.preInit(Instrumentation)` do Weave. O transformer em si não usa
nada do Weave; aqui ele é registrado no `premain` padrão.

## Hooks

| Hook | Como identifica | O que faz |
| --- | --- | --- |
| Mod blacklist | método `public (JsonElement)V` com LDC `"modSettings"` | corpo vira `RETURN` |
| Polling rate | método `public start()V` com LDC `"Unable to start polling detection thread in headless client!"` | corpo vira `RETURN` |
| Texture pack | método `public static (String)Z` com LDC `"assets/lunar/"` | vira `return true` |
| Reach display | classe com `"REACH_DISPLAY"` + `"#.##"`; `Math.min(D,D)` depois de um `INVOKEINTERFACE ...()D` | `Math.min` vira `POP2`; formato vira `"0.00"` |
| Staff/Xray | classe com LDC `"Xray"` | getter do gate vira `true`; setter não consegue desligar |

O staff tem dois formatos:

- **Legacy**: campo `boolean` primitivo, getter/setter triviais — getter vira
  `ICONST_1; IRETURN` e setter vira `this.campo = true`.
- **Atual (Lunar de 23/10/2026)**: o gate é um config
  `...RRRRRIRCC...<java.lang.Boolean>` em vez de um campo `Z`. A classe tem
  exatamente um getter `public boolean ()Z` e um setter `public void (Z)V`
  tocando o mesmo campo; o getter vira `true` e o setter passa `true` para o
  `Boolean.valueOf`, então o gate não desliga.

## Teste offline

Testado contra as classes reais extraídas de
`C:\Users\laion\.lunarclient\offline\multiver\lunar.jar`:

| Hook | Classe | Resultado |
| --- | --- | --- |
| blacklist | `.../RRRRRIRCC.../RHCHRRCOO...` | método `ORCCIR...` só com `return` |
| polling | `.../HHIHOIHC.../HHIHOIHC...` | `start()` só com `return` |
| texture | `com/moonsworth/lunar/client/util/CHOHIOC...` | `iconst_1; ireturn` |
| reach | `.../IIROOHRROHOCRRR.../RRRRRIRCC...` | `pop2` no lugar de `Math.min`; `0.00` no lugar de `#.##` |
| staff | `.../CORCORRIIRHORORRR.../RRRRRIRCC...` | getter `iconst_1; ireturn`; setter com `iconst_1` no lugar de `iload_1` |

Os 5 passaram no `BasicVerifier` do ASM, e uma segunda passada em cada classe
retorna `null` (patch idempotente).

## Build

```powershell
powershell -ExecutionPolicy Bypass -File .\build.ps1
```

Gera `%TEMP%\unrestrictedbuild\unrestricted.jar`.

Para instalar em `$env:LUNAR_AGENTS` (ou `C:\Users\laion\Desktop\Lunar\agents`
sem a variável):

```powershell
powershell -ExecutionPolicy Bypass -File .\build.ps1 -Install
```

O `payload.js` já injeta todos os `.jar` de `agents/`; não precisa mexer nele.

## Log

`%TEMP%\unrestricted-status.log` — uma linha por hook aplicado, por exemplo:

```
blacklist patched: com/moonsworth/lunar/client/.../RHCHRR....ORCCIR...
polling-rate patched: com/moonsworth/lunar/client/.../HHIHOIHC....start
texture-pack patched: com/moonsworth/lunar/client/util/CHOHIOC....RHOOIC...
reach patched: com/moonsworth/lunar/client/.../RRRRRIRCC... (clamp sites=1)
staff patched (config): com/moonsworth/lunar/client/.../RRRRRIRCC... (...)
```

## Diferenças em relação ao mod original

- `premain`/`agentmain` do `java.lang.instrument` em vez do `preInit` do Weave.
- `ClassWriter.COMPUTE_MAXS` em vez de `COMPUTE_FRAMES`: os patches não criam
  frames novos, e `COMPUTE_FRAMES` obrigaria o classloader do agent a resolver
  as classes do Lunar.
- `agentmain` retransforma só classes `com.moonsworth.lunar`/`com.lunarclient`
  cujo `.class` contenha um dos marcadores.
- Pré-filtro pelos marcadores no bytecode antes de parsear com ASM; o original
  passava ASM em toda classe não ignorada.
- Checagens de acesso por bitmask (`public`, `static`) em vez de igualdade
  exata, para sobreviver a `final`/`synthetic`.
- O matcher de staff cobre o formato antigo (campo `Z`) e o atual (config
  `Boolean`).
- Log em `%TEMP%\unrestricted-status.log` em vez de stdout.
- Ignora os pacotes dos outros agents (`me/zircta/`, `me/youded/`).

## Risco

Isto é um bypass de restrições do Lunar: blacklist de mods, detecção de polling
rate, lock de textura e gate de staff (Xray). O uso pode violar as regras do
Lunar e dos servidores, com risco de ban. O hook de reach é só HUD; o de textura
é de assets.

Os matchers são estruturais e frágeis por natureza: quando o Lunar muda um
marcador, o hook correspondente para de casar e fica silencioso. O log é o
primeiro lugar para conferir.

## Créditos

- Ideia e implementação original: **Youded** — [Youded-byte/unrestricted](https://github.com/Youded-byte/unrestricted).
- Port para Weave 1.1.0 / Lunar de maio de 2026: **marioparaschiv** — [weave-unrestricted](https://github.com/marioparaschiv/weave-unrestricted).
- Port para `-javaagent` e adaptação do staff ao build atual: este projeto.
