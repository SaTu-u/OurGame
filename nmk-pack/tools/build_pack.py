# Builds the server resource pack: NMK potion names and bottle models (regeneration, strength, swiftness, invisibility)
# and the skin of Mila, the companion from the Mila plugin.
# Usage: python3 build_pack.py  ->  ../dist/NMK-pack.zip (deterministic: same input, same bytes and SHA-1)
import hashlib
import io
import json
import os
import subprocess
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
BUILD = os.path.join(ROOT, 'build')
DIST = os.path.join(ROOT, 'dist')

# family -> (texture name, name shown in game, potion ids that get this bottle)
FAMILIES = {
    'regeneration': ('strawberry', 'Клубничный НМК', ['regeneration', 'long_regeneration', 'strong_regeneration']),
    'strength': ('chocolate', 'Шоколадный НМК', ['strength', 'long_strength', 'strong_strength']),
    'swiftness': ('coffee', 'Кофейный НМК', ['swiftness', 'long_swiftness', 'strong_swiftness']),
    'invisibility': ('caramel', 'Карамельный НМК', ['invisibility', 'long_invisibility']),
}

# Minecraft 26.2 uses resource pack format 88, 26.3 uses 97.
PACK_MCMETA = {'pack': {'description': 'НМК вместо зелий и скин Милы', 'min_format': 88, 'max_format': 97}}

# Vanilla assets/minecraft/items/potion.json (26.2/26.3): every other potion keeps its normal look.
VANILLA_POTION_MODEL = {'type': 'minecraft:model', 'model': 'minecraft:item/potion',
                        'tints': [{'type': 'minecraft:potion', 'default': -13083194}]}


def files():
    out = {}
    out['pack.mcmeta'] = json.dumps(PACK_MCMETA, ensure_ascii=False, indent=2)
    cases = []
    for family, (tex, _, ids) in FAMILIES.items():
        cases.append({'when': [{'potion': 'minecraft:' + i} for i in ids],
                      'model': {'type': 'minecraft:model', 'model': 'nmk:item/' + tex}})
        out[f'assets/nmk/models/item/{tex}.json'] = json.dumps(
            {'parent': 'minecraft:item/generated', 'textures': {'layer0': 'nmk:item/' + tex}}, indent=2)
    out['assets/minecraft/items/potion.json'] = json.dumps({'model': {
        'type': 'minecraft:select', 'property': 'minecraft:component', 'component': 'minecraft:potion_contents',
        'cases': cases, 'fallback': VANILLA_POTION_MODEL}}, indent=2)
    names = {'item.minecraft.potion.effect.' + family: name for family, (_, name, _) in FAMILIES.items()}
    for lang in ('ru_ru', 'en_us', 'uk_ua', 'be_by'):
        out[f'assets/minecraft/lang/{lang}.json'] = json.dumps(names, ensure_ascii=False, indent=2)
    return out


def main():
    tex_dir = os.path.join(BUILD, 'textures')
    subprocess.run([sys.executable, os.path.join(HERE, 'draw_textures.py'), tex_dir], check=True)
    skin_dir = os.path.join(BUILD, 'skin')
    subprocess.run([sys.executable, os.path.join(HERE, 'draw_skin.py'), skin_dir], check=True)
    os.makedirs(DIST, exist_ok=True)
    zpath = os.path.join(DIST, 'NMK-pack.zip')
    entries = files()
    for _, (tex, _, _) in FAMILIES.items():
        entries[f'assets/nmk/textures/item/{tex}.png'] = open(os.path.join(tex_dir, tex + '.png'), 'rb').read()
    # Mila's skin: the plugin's mannequin points its profile texture at mila:entity/mila
    entries['assets/mila/textures/entity/mila.png'] = open(os.path.join(skin_dir, 'mila.png'), 'rb').read()
    # pack icon: the four bottles side by side, scaled to 128x128
    from PIL import Image
    icon = Image.new('RGBA', (64, 64), (0, 0, 0, 0))
    for i, (tex, _, _) in enumerate(FAMILIES.values()):
        im = Image.open(os.path.join(tex_dir, tex + '.png'))
        icon.alpha_composite(im, ((i % 2) * 32, (i // 2) * 32))
    buf = io.BytesIO()
    icon.resize((128, 128), Image.NEAREST).save(buf, 'PNG')
    entries['pack.png'] = buf.getvalue()
    with zipfile.ZipFile(zpath, 'w', zipfile.ZIP_DEFLATED) as z:
        for name in sorted(entries):
            data = entries[name]
            info = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, data.encode('utf-8') if isinstance(data, str) else data)
    sha1 = hashlib.sha1(open(zpath, 'rb').read()).hexdigest()
    print(f'built {zpath} ({os.path.getsize(zpath)} bytes) sha1={sha1}')


if __name__ == '__main__':
    main()
