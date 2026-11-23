# pedant

Pedant is a free, offline app for learning to speak a language, for Android. You hold a conversation with an AI tutor in one of 25 languages. The goal is replicating conversations with a 'native speaker' while giving you aids to learn pronunciation, vocabulary and grammar organically through conversation instead of rote learning. [CEFR](https://www.coe.int/en/web/common-european-framework-reference-languages/level-descriptions) is used as the basis for structuring conversation levels and learning aids and there is ongoing work to give this more structure.

No data is collected, and all models are local.

## Included

- **LLM:** Gemma 4 E4B
- **Speech-to-text:** Meta Omnilingual ASR CTC 300M
- **Pronunciation:** ZIPA (Zipa-Cr-large)
- **Text-to-speech:** Piper
- **Text to phonemes:** espeak-ng
- **Word dictionaries:** English Wiktionary, via kaikki.org (Wiktextract)
- **Lesson structure:** CEFR levels
- **Review scheduling:** FSRS
- **Answer checking:** LanguageTool, Hunspell, UniMorph, Universal Dependencies

## License

GPL-3.0-or-later; see [LICENSE](LICENSE). Third-party work is credited in
[CREDITS.md](CREDITS.md).
