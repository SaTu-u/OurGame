# NMK resource pack

Turns four potions into milk-cocktail bottles with new names:

| Potion | Name in game | Bottle |
|---|---|---|
| Regeneration (incl. long/strong) | Клубничный НМК | strawberry |
| Strength (incl. long/strong) | Шоколадный НМК | chocolate |
| Swiftness (incl. long/strong) | Кофейный НМК | coffee |
| Invisibility (incl. long) | Карамельный НМК | caramel |

The pack also carries the skin of Mila, the companion from `../mila` (`assets/mila/textures/entity/mila.png`, from `skins/mila.png`).

Only drinkable potions change. Splash and lingering potions, tipped arrows and every other potion keep the vanilla look.
Works with Minecraft 26.2 (pack format 88) to 26.3 (format 97).

## Build

```
python3 tools/build_pack.py      # needs Pillow; writes dist/NMK-pack.zip and prints its SHA-1
```

The zip is deterministic: the same sources always give the same bytes and SHA-1.
The bottles are drawn pixel by pixel in `tools/draw_textures.py` (32x32, original art).

## Server setup (server.properties)

```
resource-pack=https://raw.githubusercontent.com/SaTu-u/OurGame/<commit>/nmk-pack/dist/NMK-pack.zip
resource-pack-sha1=<sha1 printed by build_pack.py>
resource-pack-id=<any fixed UUID>
resource-pack-prompt={"text":"…","color":"yellow"}
require-resource-pack=false
```

Pin the URL to a commit hash, so the file never changes under the same SHA-1.
After changing the pack: rebuild, commit, push, then put the new commit and SHA-1 into server.properties and restart.
