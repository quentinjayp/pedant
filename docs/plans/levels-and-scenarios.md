# Plan: Levels and scenarios

The plan for ROADMAP.md's "Levels and scenarios" section, broken into tasks. Nothing here is built
yet.

## Context

The section adds a second dimension beside the help modes: how hard the language is, on the CEFR's
three bands, Basic (A1–A2), Independent (B1–B2) and Proficient (C1–C2). It replaces the fixed
scenario list with scenarios made for each band: generated from random ingredients, Gemma asking the
learner for the scene, the learner's own, or talk about whatever. The roadmap says to research the
bands first, and to measure whether Gemma can do each band, since E2B and E4B may not hold the
Proficient band in every language.

## Order

- **A (research)** comes first. It gates the evals (B) and the ingredient lists (task 9).
- **B (measuring the models)** decides which bands each language offers.
- **C (foundations)** comes before D (scenarios).
- **E (tests and docs)** runs alongside C and D.

## A. Research

Investigation only. Output: `docs/research/bands.md`.

1. **Band descriptors.** The CEFR 2020 Companion Volume's descriptors for spoken production,
   spoken interaction and mediation, A1 to C2, reduced to each band's can-do task list.
   - What can a speaker do at each band, in tasks a conversation can ask for?
   - Which descriptors can a phone app with speech recognition and Gemma actually exercise?
2. **Oral exam task shapes.** From the free sample papers of DELF/DALF, DELE, Goethe, TYS, TORFL,
   BIPA and others where they exist: the oral task types at each level (guided interview, information
   exchange, role-play, monologue, debate).
   - Which task shapes appear at which levels?
   - Which ones are instructions to Gemma, and which need something new in the app (a monologue
     with no reply, for example)?
3. **Example courses.** Courses for each level, such as *Sahabatku Indonesia* (A1–C2): the topics and
   situations each level covers. This feeds the ingredient lists (task 9).
   - What topics and situations are typical for each band, across languages?
   - What differs by culture, so scenes are set where the language is spoken?
4. **Per-language test labels.** For each of the 25 languages, the local test that maps onto the
   CEFR, or none (then only the CEFR is shown). Later this becomes data in
   `app/src/main/assets/languages.json`, as `articles` is.
   - Which tests map officially, which only approximately, and which languages have none?

## B. Measuring the models

5. **Band evals.** Extend the evals pipeline (`tools/evals.py`, `.github/workflows/evals*.yml`,
   non-blocking, models from `evals/models.json`). Gemma, told a band, holds short conversations in
   each language.
   - **Measured:** at Basic, short sentences and common words (sentence length, and the share of
     words in a frequency list for the language).
   - **Judged:** whether replies show the band's features.
   - **Output:** a report per band and language, and a decision on which bands each language offers.
     A band a model can't do isn't offered.
   - **5a. One-time Claude judgment.** In a session, Claude judges a sample of Gemma's replies per
     band and language against the band descriptors from task 1. This isn't a permanent eval: it's a
     one-off reference, saved with the sample, to test other judges against.
   - **5b. Gemma as judge.** Gemma judges the same sample: E4B, judging replies written by E2B and by
     itself, set up to look for what's wrong. Its judgments are compared with Claude's.
     - If they agree closely enough, Gemma becomes the permanent judge in CI.
     - If not, the permanent eval keeps only the measurable parts (sentence length, word commonness).
   - **5c. Free hosted judges.** Check whether GitHub still offers free model inference to Actions
     (GitHub Models). Reports say it was retired in July 2026; that's unconfirmed. If it still
     exists, compare it with Claude's judgments the same way.
6. **Band-aware translation evals.** Extend the translation evals: translations told a band are
   checked for keeping the meaning (chrF against references, as now) and, at Basic, for simplicity.

## C. Foundations in the app

7. **Rename the help modes** to Repeat word, Repeat phrase and Free talk. This changes the titles of
   `Mode` in `app/src/main/java/com/pedant/app/Scenarios.kt` only; Free talk itself is a separate
   roadmap item.
8. **Band setting.**
   - **Choosing:** a `Band` (Basic, Independent, Proficient), chosen first in New conversation and
     remembered.
   - **Saving:** saved with the conversation (`SavedConversation.kt`).
   - **Gemma:** a `{band}` placeholder in `instructions.json` (read by `Instructions.kt` and
     `tools/app_languages.py`), so Gemma's replies and the learner's translations follow the band,
     keeping the meaning over simplicity.
   - **Quiz:** its word limits (`Quiz.wordLimit` in `Quiz.kt`) follow the band, not the help mode.
   - **Labels:** each band shows its test labels, from task 4's data.

## D. Scenarios

9. **Ingredient lists.** A data file in `app/src/main/assets/` with each band's can-do tasks (from
   tasks 1–3), kinds of place or relationship, and twists.
10. **Generated scenarios.** The app picks ingredients at random, and Gemma writes the scene, its role
    and the opening, set where the language is spoken.
    - **Replaces:** `Scenario` (Scenarios.kt) and the `roles` in `instructions.json`.
    - **Saving:** the scene is saved with the conversation, for Continue and the quiz.
    - **Showing:** it's shown at the top of the conversation, where `scenario.scene` is now.
11. **Gemma asks for the scene.** For the kind of situation picked, Gemma asks the learner to fill it
    in, and the answer becomes the scene. At Basic it asks in the learner's own language; at
    Independent and Proficient, in the language being learned.
12. **Your own scenarios.** The learner describes a situation, and Gemma builds the scene at the band.
13. **Talk about whatever** in every band: today's Default, with the band applied.
14. **New conversation flow.** Language → band → generated, your own, or talk about whatever →
    Gemma's question → the scene. This changes `LanguageScreen` in `MainActivity.kt` and
    `startSession` in `AppViewModel.kt`.
15. **Openings.**
    - **Removed:** the shipped openings (`openings.json`, `OpeningCache.kt`,
      `tools/build_openings.py`, `.github/workflows/openings.yml`).
    - **Instead:** when a conversation ends, the next random scenario is prepared in the background,
      to cover Gemma writing the opening on the phone.
16. **Generated-scene evals.** A sample of generated scenes per band and language, checked for sense
    and level. This extends task 5.

## E. Tests and docs

17. **Tests and docs.** Unit tests and screenshots for the new screens, like `QuizScreenshotTest`;
    DECISIONS.md and ROADMAP.md updated as decisions are made.
