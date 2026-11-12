# pedant

Offline AI language learning app for Android.

## Included

- **LLM:** Gemma 4 E4B
- **Speech-to-text:** Meta Omnilingual ASR CTC 300M
- **Pronunciation:** ZIPA (Zipa-Cr-large)
- **Text-to-speech:** Piper
- **Text to phonemes:** espeak-ng
- **Word dictionaries:** English Wiktionary, via kaikki.org (Wiktextract)
- **Lesson structure:** CEFR levels
- **Review scheduling:** FSRS
- **Lesson content:** FSI courses, Peace Corps language manuals, Tatoeba, Wiktionary, UniMorph, frequency lists
- **Answer checking:** LanguageTool, Hunspell, UniMorph, Universal Dependencies

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).

The logo is the capital P from [Potta One](https://fonts.google.com/specimen/Potta+One) by the Potta Project Authors, used
under the SIL Open Font License 1.1 (see [licenses/PottaOne-OFL.txt](licenses/PottaOne-OFL.txt)).

The word dictionaries are a separate download, not part of the app. They are built from
[English Wiktionary](https://en.wiktionary.org), text by Wiktionary contributors, and are licensed
[CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/). They were extracted with
Wiktextract via [kaikki.org](https://kaikki.org) (Tatu Ylonen, "Wiktextract: Wiktionary as
Machine-Readable Structured Data", LREC 2022) and cut down to each word's spelling, part of
speech, IPA, a few short English meanings, and the word it is a form of. Each entry also holds how
often its spelling is that part of speech, counted in [Universal Dependencies](https://universaldependencies.org)
treebanks licensed CC BY-SA or CC BY (each dictionary's `meta` table names the ones used), so the
common meaning shows first. They are rebuilt monthly
by `.github/workflows/dictionaries.yml`; `dictionaries/manifest.json` lists the current files.
