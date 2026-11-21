# Mila

A companion for every player on a Paper/Purpur 26.2 server. `/mila` calls her. She follows you and defends you from
monsters, and on command she works: mines, chops trees, farms, hunts, guards a place, fishes, collects loose items
and carries the loot to a chest. She also takes gifts, hugs you and kisses you on the cheek once you are close
enough. Plugin only; her skin comes from the server resource pack (`../nmk-pack`).

Above her head: her name, and under it her owner's nick (gold) and what she is doing right now.

## Commands (`/mila help` lists them all, click a line to put it into the chat)

| Command | What it does |
|---|---|
| `/mila` | call her, or bring her to you (cooldown `call-cooldown-seconds`, 30 s; `mila.nocooldown` skips it) |
| `/mila follow` / `stay` | follow you / wait where she is |
| `/mila stop` | drop the current job |
| `/mila bag` | her 27-slot backpack (also Shift + right click) |
| `/mila give` | put the item in your hand into her backpack (pickaxe, axe, sword, fishing rod, torches) |
| `/mila info` | affection, current job and progress, chest |
| `/mila bye` | send her home |
| `/mila chest` | remember the chest you look at |
| `/mila mine <resource> [n]` | mine a resource and bring n to the chest: coal, iron, copper, gold, redstone, lapis, diamond, emerald, quartz, debris, stone, deepslate, dirt, sand, gravel, clay (`/mila resources`; Russian names work too) |
| `/mila chop [n]` | chop n logs from grown trees, replant saplings |
| `/mila farm [radius]` | harvest ripe crops, plant them again, sow empty farmland, bring the harvest to the chest (until `/mila stop`) |
| `/mila hunt [mob] [n]` | hunt monsters (default) or cow, pig, chicken, sheep, rabbit, zombie, skeleton, spider... |
| `/mila guard [radius]` | keep this place clear of monsters (until `/mila stop`) |
| `/mila fish [n]` | fish with a rod from her backpack |
| `/mila collect [radius]` | pick up items lying around |
| `/mila deposit` | empty her backpack into the chest (she keeps tools) |
| `/mila fetch <item> [n]` | bring an item from the chest, e.g. `/mila fetch bread 5` |
| `/mila light` | on/off: she puts torches from her backpack down in dark places |
| `/mila gift` | give her the item in your hand (right click on her does the same) |
| `/mila hug` / `kiss` | hug (affection 10) / kiss on the cheek (affection 40) |
| `/mila name <имя>` | rename your companion |
| `/mila reload` | reload config.yml (`mila.admin`, op) |

Jobs that produce loot use the chest you look at when typing the command; otherwise the one remembered with
`/mila chest`. Hunting and fishing work without a chest too (the loot stays in her backpack).

## How she works

- **Tools.** She uses what is in her backpack: the best pickaxe/axe/shovel for each block (iron ore needs a stone
  pickaxe, like for a player), the best sword or axe for fights, a fishing rod for fishing. Tools wear out
  (Unbreaking counts). Break times follow the vanilla formula.
- **Rules from Baritone.** Path finding is A* in ticks where a block in the way costs its break time
  (moves: walk, step up, drop, dig down), and long trips are walked in segments, after
  [Baritone](https://github.com/cabaletta/baritone) (LGPL-3.0). The code is written for the server API: Baritone is
  a client mod that steers the player's own keyboard and camera, so its code cannot run on a server.
  From Baritone's `avoidBreaking`: she never breaks a block next to lava or water, under liquid, ice, infested
  blocks or a block holding unsupported gravel. From its `FarmProcess`: what counts as ripe (crops at full age,
  pumpkins and melons from a stem, cane/bamboo/cactus above the base, nether wart, cocoa).
- **Other players' things.** Every block she breaks or places is announced as her owner's
  (`BlockBreakEvent`/`BlockPlaceEvent`), so claim plugins can forbid it and loggers record it. On her way she digs only
  through natural terrain (stone, deepslate, dirt, sand, gravel, ores...), never planks, bricks, glass or containers.
  She chops only trees with natural leaves (player-placed leaves are "persistent"). She hunts only adult farm
  animals without a name or leash, and always leaves two adults of a kind nearby. She does not pick up items other
  players threw away.
- **Mining.** The resource is searched in the loaded chunks around her (7 x 7 chunks, copied on the main thread and
  scanned off it), nearest first, whole veins at a time. When her bag is full or she has enough she walks back to the
  chest (if the way back takes too long, she teleports there). While she works, the chunks around her stay loaded.
- She cannot be hurt. `/kill` and the void remove her and she comes back to her owner. She is never saved with the
  world: when her owner leaves she leaves too (the job ends), and she returns when they join.
- **Skin.** The profile points at `mila:entity/mila` = `assets/mila/textures/entity/mila.png` in the server pack.
  Players who have not loaded the pack would see a purple-black "missing texture", so a twin entity with a built-in
  skin (`skin.fallback`, default `minecraft:entity/player/slim/noor`) is shown to them instead; the plugin hides one
  of the two per player, following the resource pack status each client reports.
- Data: `plugins/Mila/players/<uuid>.yml` (name, affection, mode, backpack, chest, torches, last call).

## Build

```
./build.sh        # needs ../claude-bridge/build/libs (run claude-bridge/build.sh once); writes dist/Mila-<version>.jar
```
