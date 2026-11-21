# Mila

A companion for every player on a Paper/Purpur 26.2 server. `/mila` calls her: she follows you, fights the
monsters that go after you, carries a backpack, takes gifts, hugs you and kisses you on the cheek once you are
close enough. Plugin only; her skin comes from the server resource pack (`../nmk-pack`).

## Commands

| Command | What it does |
|---|---|
| `/mila` | call her (or bring her to you) |
| `/mila follow` / `stay` | follow you / wait where she is |
| `/mila hug` | hug, needs affection 10 |
| `/mila kiss` | a kiss on the cheek, needs affection 40 |
| `/mila gift` | give her the item in your hand (right click on her does the same) |
| `/mila bag` | her 27-slot backpack (also Shift + right click) |
| `/mila info` | affection, gifts today |
| `/mila name <имя>` | rename your companion |
| `/mila bye` | send her home |
| `/mila reload` | reload config.yml (`mila.admin`, op) |

Russian words work as subcommands too: `иди`, `жди`, `обнять`, `поцеловать`, `подарок`, `рюкзак`, `инфо`, `имя`, `пока`.

## Affection

0..100 per player. +1 for every 3 minutes together (she is out and within 16 blocks), gifts add more
(flowers 3, cake 10, NMK potions 6, ...; at most 6 gifts a day), hitting her costs 5.
At 10 she can be hugged, at 40 kissed, at 70 she carries a flower. She refuses politely below the threshold.

## How she is built

- She is a `Mannequin` (player model) moved by the plugin: A* path finding over the block grid, steps up one
  block, drops, swims, opens wooden doors and gates and closes them behind her. Far away, stuck for 5 s or in
  another world, she teleports to her owner.
- She cannot be hurt (`/kill` and the void remove her, and she comes back to her owner). She is never saved with
  the world: when her owner leaves she leaves too, and returns when they join.
- She attacks monsters that target her owner or her, or that her owner hits (not creepers), 4 damage about
  once a second.
- Skin: the profile points at `mila:entity/mila` = `assets/mila/textures/entity/mila.png` in the server pack.
  Players who have not loaded the pack would see a purple-black "missing texture", so a twin entity with a
  built-in skin (`skin.fallback`, default `minecraft:entity/player/slim/kai`) is shown to them instead; the
  plugin hides one of the two per player, following the resource pack status each client reports.
- Data: `plugins/Mila/players/<uuid>.yml` (name, affection, mode, backpack).

## Build

```
./build.sh        # needs ../claude-bridge/build/libs (run claude-bridge/build.sh once); writes dist/Mila-1.0.0.jar
```
