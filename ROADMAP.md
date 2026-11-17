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
  - **Asking about the language as you go.** Ask the tutor in English mid-conversation ("What does
    that mean?", "How do I say…?", "Why this word and not that one?"), then carry on.

## Learning

- **Sounds practice.** A learning section where you practise saying different IPA sounds and are
  graded on them.

## Languages

- **Tones and intonation.** Grade the pitch of each syllable against its expected tone (espeak-ng
  gives the tones for Mandarin and Vietnamese), and the rise and fall of a sentence, such as a
  question's. Then bring back Mandarin and Vietnamese, removed because the app couldn't tell when a
  tone was wrong.

## Levels and scenarios

A second dimension beside the help modes: how hard the language is, on the scale the international
proficiency tests use. The fixed list of scenarios is replaced by scenarios made for each level.

- **Research the bands first.** Before building, study what each band actually asks of a speaker:
  the CEFR's descriptors (its 2020 Companion Volume has detailed ones for speaking and
  interaction), the oral parts of the tests (DELF, DELE, Goethe, TYS and the others, from their free
  sample papers), and example courses at each level, such as the Indonesian government's
  *Sahabatku Indonesia* books (A1–C2). That gives the band's task lists, and what to measure.
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
- **Test labels per language.** Each band shows the local test it corresponds to where that test
  maps onto the CEFR ("A1–A2 · DELF A1–A2 · NCLC 1–4" for French). Where a language's own scale
  doesn't map cleanly (Indonesian's UKBI) or there's no recognised test (Hindi, Swahili), only the
  CEFR is shown, never a made-up equivalence.
- **The band shapes the whole conversation.** Gemma speaks at the band's level (short, common words
  at Basic; no simplifying at Proficient); what the learner says is translated into something they
  can say at that level, with the meaning kept over simplicity; and the quiz's word limits follow
  the band rather than the help mode.
- **Generated scenarios.** The app picks a scenario's ingredients at random from lists in a data
  file: a task from the band's can-do list (Basic: order something, ask the way; Independent: sort
  out a problem, tell a story, give an opinion; Proficient: persuade, smooth over a conflict,
  explain something complex), a kind of place or relationship, and sometimes a twist. Gemma writes
  the scene from them, set where the language is spoken (a warung, not a Parisian café, for
  Indonesian). Random ingredients stop Gemma drifting back to the same few scenes, and the band's
  task list keeps the level right.
- **Gemma asks for the scene.** For the kind of situation picked, Gemma asks the learner to fill it
  in ("Where do you usually get food or drinks?"), and the answer becomes the scene, so the
  conversation is about the learner's own life. At Basic it's asked in the learner's own language;
  at Independent and Proficient in the language being learned, as the conversation's first exchange.
- **Your own scenarios.** The learner describes a situation ("my landlord is late fixing the
  heating") and Gemma builds the scene around it at the chosen band.
- **Talk about whatever.** Kept in every band, as the option with no scene.
- **The flow.** Pick a language and band; then a generated scenario, your own, or talk about
  whatever; Gemma asks its question if there is one; the scene starts.
- **What it costs.** Openings are no longer written ahead and shipped, so a conversation no longer
  opens instantly: Gemma writes the scene and opening on the phone (a few seconds with E2B, more
  with E4B). Its question to the learner covers part of that, and the next random scenario can be
  prepared in the background when a conversation ends. The saved conversation stores the scene
  itself, for Continue and the quiz. The evals pipeline would check a sample of generated scenes per
  band and language for sense and level, and whether Gemma holds each band's level at all (E2B at
  Basic, both models at Proficient).

## Conversations

- **Voice selector.** Choose the language's voice in the conversation settings (the gear).

## Platforms

- **Investigate iOS feasibility.**
