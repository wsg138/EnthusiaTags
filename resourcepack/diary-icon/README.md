# Diary advancement icon

Source artwork: `journal_quill.aseprite`, exported losslessly at 16x16.

The icon uses PAPER custom-model-data 815002. This intentionally shares the same
Nexo/PAPER pipeline as the already-working Enthusia tab logo at 815001.

Nexo install:

1. Add `enthusia_diary_advancement_icon.yml` to the Nexo item configs.
2. Put `Enthusia-Diary-Icon-Nexo-Assets.zip` in `plugins/Nexo/pack/external_packs/`.
3. Rebuild Nexo's resource pack and make clients accept the rebuilt pack.

If the old client-side `Enthusia-Advancements-Logo-26.2.zip` is still enabled,
remove/replace it. Its PAPER definition intentionally mapped 815002 back to vanilla
paper and can override the new diary mapping. The replacement
`Enthusia-Advancements-Icons-26.2.zip` contains both the logo and diary mappings.
