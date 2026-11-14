"""Validate the static recognition tag packet; no server or player writes."""
from pathlib import Path
import hashlib, struct, yaml
root=Path(__file__).resolve().parents[2]
resources=root/'src/main/resources'
config=yaml.safe_load((resources/'config.yml').read_text(encoding='utf-8'))
text='ꂡ<bold><gradient:#82c6a2:#eee7c8>Pumpkin Patcher</gradient></bold>ꂡ'
tag=config['tags']['pumpkin_patcher']
assert tag['display-name']==tag['tag-text']==text
assert text.count('ꂡ')==2 and 'ꂠ' not in text
assert tag['icon']=='NAME_TAG' and len(tag['description'])==3
glyph=yaml.safe_load((root/'resourcepack/holiday-tags/Nexo/glyphs/pumpkin_patcher.yml').read_text(encoding='utf-8'))
g=glyph['pumpkin_patcher_seeds']
assert g==dict(texture='enthusia:font/tags/pumpkin_patcher_seeds.png',font='minecraft:default',height=10,ascent=8,char='ꂡ')
p=root/'resourcepack/holiday-tags/Nexo/pack/assets/enthusia/textures/font/tags/pumpkin_patcher_seeds.png'
data=p.read_bytes();assert data[:8]==b'\x89PNG\r\n\x1a\n' and struct.unpack('>II',data[16:24])==(12,10)
assert hashlib.sha256(data).hexdigest()=='737cb974edcb2114e05a236235709be504c8ded8d41c35b35cd4734852d7038b'
rewards=yaml.safe_load((resources/'rewards.yml').read_text(encoding='utf-8'))
assert 'pumpkin_patcher' in rewards['categories']['halloween']['tags']
assert 'pumpkin_patcher' not in rewards.get('rewards',{})
entry=yaml.safe_load((resources/'menu-sources.yml').read_text(encoding='utf-8'))['definitions']['pumpkin_patcher']
assert entry['tags']==['pumpkin_patcher'] and entry['permanent'] is True
assert 'qualification' not in entry and entry['cosmetics']==[]
print('Pumpkin Patcher static packet valid; no auto-grant or public reward rule.')
