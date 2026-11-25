# Roadmap

Ideas for later. Nothing here is being built yet.

## Modes

Beginner and Intermediate are built. A third mode is planned.

- **Rename the modes for the help they give.** The modes are how much help the app gives, not how
  good the learner is, so they're named for what they do: **Repeat word** (each word, then the whole
  phrase; today's Beginner), **Repeat phrase** (the whole phrase until it's right; today's
  Intermediate) and **Free talk** (say it and move on; the planned mode below). They form a ladder
  from the most help to none, separate from the language level (see Levels and scenarios), and
  every mode works at every level.
- **Free talk** (planned as "Advanced"). Free conversation in the language, with no English first. The tutor never makes
  you repeat a phrase until it's right, as Beginner and Intermediate do. This mode may use a
  different speech recognizer (undecided).
  - **Reaching for a missing word.** Say a sentence in the language with the one word you don't
    know in English, and get back just that word.
  - **Tutor's note.** After you say something, and before the tutor's reply, a correction or
    clarification of what you said is shown, perhaps as its own card in the conversation called
    "Tutor's note". The tutor then replies as normal.
  - **Text only.** Type your side of the conversation instead of saying it. The Tutor's note gives
    Gemma's corrections of the grammar and word use in what you typed.
  - **Asking about the language as you go.** Ask the tutor in English mid-conversation ("What does
    that mean?", "How do I say…?", "Why this word and not that one?"), then carry on.

## Learning

- **Sounds practice.** A learning section where you practise saying different IPA sounds and are
  graded on them.
- **Investigate tracking words said right and wrong over time.** Whether keeping how often each word
  is said right or wrong (for example in a SQLite table of attempts) would show useful trends.

## Languages

- **Tones and intonation.** Grade the pitch of each syllable against its expected tone (espeak-ng
  gives the tones for Mandarin and Vietnamese), and the rise and fall of a sentence, such as a
  question's. Then bring back Mandarin and Vietnamese, removed because the app couldn't tell when a
  tone was wrong.

- **Investigate dialects, such as Québécois French.** Whether the models can handle one: Gemma may
  hold a dialect's vocabulary, and no Québécois Piper voice has been found, without which expected
  sounds would be France French.

## Words

- **Investigate Gemma choosing the meaning that fits the sentence.** How accurately Gemma picks the
  right one of the dictionary's meanings given the sentence, measured in the evals. Also whether
  showing both the whole word's meaning and its parts' helps (for words split at an apostrophe or
  hyphen).
- **Investigate expressions with a meaning of their own in the word bubble.** How to handle groups
  of words whose meaning isn't their words' meanings put together (like "wise ass" in English).
- **Investigate meanings in the learner's own language.** Sources for meanings in languages other
  than English. The dictionaries are English Wiktionary, so meanings are in English for every own
  language, and English meanings lose some of what other languages' forms mark (the imperfect's "used
  to", aspect, gender).
- **Investigate the meaning order for Hungarian.** Other languages show the most common meaning first,
  using part-of-speech counts from Universal Dependencies treebanks; Hungarian keeps Wiktionary's
  order because its only treebank is licensed for non-commercial use. Other sources of counts.
- **Investigate the word bubble scrolling on small phones.** The meanings scroll on their own, but the
  whole bubble can still scroll when it's taller than the screen.
- **Investigate supplemental dictionaries.** More extensive dictionaries with English meanings, for
  languages whose English Wiktionary dictionary is small: Slovenian (about 45,000 entries) and Slovak
  (about 54,000), against hundreds of thousands for most languages. Sources of forms without
  meanings found so far: for Slovenian, Sloleks 3.0 (about 365,000 base words with all their forms,
  CC BY-SA 4.0); for Slovak, the Slovak National Corpus's morphological database (licence unclear)
  and MorfFlex SK (non-commercial, so ruled out). Also whether Turkish, Hindi and Swahili lack words,
  since checking recognized words against their dictionaries made recognition worse.
- **Investigate inflected forms for Finnish.** Ways to include them without the dictionary growing to
  about 485 MB. Every other language's dictionary includes its inflected forms.

## Grading

- **Investigate optional liaisons.** The expected sounds come from espeak-ng's reading of the whole
  phrase, which includes liaisons that are optional ("vous avez un", "pas encore"), so leaving one out
  counts against the learner as if it were required. Whether to tell required from optional ones,
  and how.
- **Investigate grading leniency by band.** Grading stays against one voice, but how forgiving it
  is could follow the band, as the CEFR's phonology scales do: they expect systematic mispronunciation
  at the A levels and B1, and fewer mispronunciations at each band above. It has to be done carefully
  and never too strict, since ZIPA itself mishears: calibrated on real speech, as grading changes are
  now. See [docs/research/bands.md](docs/research/bands.md#phonology).
- **Prosody.** Grade stress, rhythm and intonation against the voice, as well as individual sounds.
  The CEFR treats prosody as half of phonological control from A1. Related to "Tones and intonation"
  under Languages, which grades the pitch of syllables and the rise and fall of sentences.

## Levels and scenarios

A second dimension beside the help modes: how hard the language is, on the scale the international
proficiency tests use. The fixed list of scenarios is replaced by scenarios made for each level.
The plan, broken into tasks, is [docs/plans/levels-and-scenarios.md](docs/plans/levels-and-scenarios.md).

- **Research the bands first.** The band descriptors, the oral exams' task shapes and the topics
  (the CEFR's themes, one list for every language) are done; see
  [docs/research/bands.md](docs/research/bands.md). Before building, study what each band
  actually asks of a speaker: the CEFR's descriptors (its 2020 Companion Volume has detailed ones
  for speaking and interaction) and the oral parts of the tests (DELF, DELE, Goethe, TYS and the
  others, from their free sample papers). That gives the band's task lists, and what to measure.
- **Evals of whether the models can do each band.** A pipeline like the translation evals, testing
  Gemma (E2B and E4B) per band and language: whether it keeps to short, common language at Basic,
  and whether it can hold a Proficient conversation at all (nuance, idiom, persuading, handling a
  delicate subject). If a model can't do the Proficient band in a language, that band isn't offered
  for it, rather than offered badly.
- **Bands.** The CEFR's three bands, under its own names: **Basic** (A1–A2), **Independent**
  (B1–B2) and **Proficient** (C1–C2). The CEFR is the internal scale for every language: it was
  written to be language-neutral and is used for non-European languages too (Turkey's TYS,
  Indonesia's BIPA framework, Russia's TORFL mapping). You choose the band to practise at; nothing
  places you in one or tracks progress through them.
- **The band shapes the whole conversation.** Gemma speaks at the band's level (short, common words
  at Basic; no simplifying at Proficient), and acts as the listener the band assumes: at Basic it
  speaks slowly, repeats, rephrases and helps; at Proficient it doesn't adjust its language at all.
  What the learner says is translated into something they can say at that level, with the meaning
  kept over simplicity; and the quiz's word limits follow the band rather than the help mode.
- **Generated scenarios.** The app draws a scenario's ingredients at random from lists in a data
  file, in two layers:
  - **Shape:** one of the band's task shapes, the same for every language, generalised from the oral
    exams. Basic: an interview about you, an everyday transaction, planning together. Independent: an
    unusual everyday situation, defending a view then debating it. Proficient: a controversial
    question where the learner tries to convince Gemma. The shape decides who leads and Gemma's role.
  - **Content:** a topic, a place or relationship, and sometimes a twist.

  Gemma writes the scene from them, set where the language is spoken (a warung, not a Parisian café,
  for Indonesian). Random ingredients stop Gemma drifting back to the same few scenes, and the band's
  shapes keep the level right.
- **Cards, and a choice of two.** Each draw is shown as a card: the shape, the situation, and the
  shape's prompts (words to ask about, points to plan, a statement to argue). The app draws two cards
  and the learner picks one, as the exams do. The chosen card stays above the conversation.
- **Gemma asks for the scene.** For the kind of situation picked, Gemma asks the learner to fill it
  in ("Where do you usually get food or drinks?"), and the answer becomes the scene, so the
  conversation is about the learner's own life. At Basic it's asked in the learner's own language;
  at Independent and Proficient in the language being learned, as the conversation's first exchange.
- **Your own scenarios.** The learner describes a situation ("my landlord is late fixing the
  heating") and Gemma builds the scene around it at the chosen band.
- **Talk about whatever.** Kept in every band, as the option with no scene. It and "Gemma asks for
  the scene" are the interview and conversation shapes, with no topic or with the learner's.
- **The flow.** Pick a language and band; then one of two drawn cards, your own scenario, or talk
  about whatever; Gemma asks its question if the shape has one; the scene starts.
- **What it costs.** Openings are no longer written ahead and shipped, so a conversation no longer
  opens instantly: Gemma writes the scene and opening on the phone (a few seconds with E2B, more
  with E4B). Its question to the learner covers part of that, and the next random scenario can be
  prepared in the background when a conversation ends. The saved conversation stores the scene
  itself, for Continue and the quiz. The evals pipeline would check a sample of generated scenes per
  band and language for sense and level, and whether Gemma holds each band's level at all (E2B at
  Basic, both models at Proficient).

## Models

- **Explore running the model on your own computer.** For what Gemma on the phone may not manage
  (the Proficient band, if the evals show E4B can't hold it in a language), explore letting Pedant
  use a bigger model served from the learner's own computer: Ollama or llama.cpp's server (both
  speak the same OpenAI-style API), reached from the phone anywhere over Tailscale, behind HTTPS
  with `tailscale serve`, with no ports opened to the internet. Nothing goes to a company and
  nothing is paid per use. Speech, sounds, the voice and grading stay on the phone; only text goes
  to the server. When the computer can't be reached, Pedant falls back to Gemma, or to the bands
  Gemma can do. The evals would gain a third engine, "an OpenAI-style server at this address", so
  the learner's own model is measured the same way as Gemma. The same setting could take a cloud
  provider's address and key, for learners without a capable computer.

## Conversations

- **Voice selector.** Choose the language's voice in the conversation settings (the gear).
- **Investigate whether to offer Formal / Casual at the Basic band.** The CEFR expects a neutral
  register up to B1, a choice between formal and informal from B2, and consistent control of formal,
  informal and colloquial at C1. See [docs/research/bands.md](docs/research/bands.md#register).
- **Investigate whether Gemma should welcome words from the learner's own language mid-sentence.**
  The CEFR counts it as a skill: at B1, "Can 'foreignise' word/signs in their first language and ask
  for confirmation", and using all of one's languages runs through every level. Related to "Reaching
  for a missing word" under Free talk. See
  [docs/research/bands.md](docs/research/bands.md#strategies).

## Platforms

- **Investigate iOS feasibility.**

## Development

- **Investigate the branching and release workflow.** Options discussed: dev and main branches with
  releases cut from main, or trunk-based with release branches; semantic version tags; a fixed link
  to the latest development build; main as the default branch.
- **Investigate preparing for Google Play.** Release builds signed with a permanent key kept as a
  GitHub secret (the current builds are debug-signed, so moving to the permanent key needs one
  reinstall); whether development builds get their own app ID, so they install alongside the release;
  and whether Play's internal testing track replaces the development build link.
- **Investigate testing the app on an emulator in CI.** Driving the real app through its screens on
  an Android emulator, with the models faked, beside the screenshot tests.
