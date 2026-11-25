# Plan: Levels and scenarios

The plan for ROADMAP.md's "Levels and scenarios" section, broken into tasks. Nothing here is built
yet.

## Context

The section adds a second dimension beside the help modes: how hard the language is, on the CEFR's
first two bands, Basic (A1–A2) and Independent (B1–B2). The Proficient band (C1–C2) is on the roadmap
only, with models other than the phone's. It replaces the fixed
scenario list with scenarios made for each band: generated from random ingredients, Gemma asking the
learner for the scene, the learner's own, or talk about whatever. The roadmap says to research the
bands first, and to measure whether Gemma can do each band in each language.

## Order

- **A (research)** comes first. It gates the evals (B) and the ingredient lists (task 8).
- **B (measuring the models)** decides which bands each language offers.
- **C (foundations)** comes before D (scenarios).
- **E (tests and docs)** runs alongside C and D.

## A. Research

Investigation only. Output: `docs/research/bands.md`.

1. **Band descriptors.** **Done.** The CEFR 2020 Companion Volume's descriptors for spoken production,
   spoken interaction and mediation, A1 to C2, reduced to each band's can-do task list.
   - What can a speaker do at each band, in tasks a conversation can ask for?
   - Which descriptors can a phone app with speech recognition and Gemma actually exercise?

   **Result:** every speaking-related scale, quoted with page numbers. Each band has a can-do list.
   - **Fits:** interaction, strategies, short spoken turns, and role-played mediation.
   - **Gemma as the listener:** each band assumes a different listener. At Basic Gemma speaks slowly,
     repeats, rephrases and helps; at Proficient it doesn't adjust its language at all. This goes
     into task 7 and the evals (task 4).
   - **Roadmap investigations it led to:** grading leniency by band, prosody, Formal / Casual at
     Basic, and words from the learner's own language mid-sentence.
2. **Oral exam task shapes.** **Done.** From the free sample papers of DELF/DALF, DELE, Goethe, TYS,
   TORFL, BIPA and others where they exist: the oral task types at each level (guided interview,
   information exchange, role-play, monologue, debate).
   - Which task shapes appear at which levels?
   - Which ones are instructions to Gemma, and which need something new in the app (a monologue
     with no reply, for example)?

   **Result:** official French guides (A1–C2) and German model papers (A1, B1–C2), with secondary
   sources for Spanish, Turkish, Russian and Indonesian, all show the same shapes at the same levels.
   - **Generalised:** one set of task shapes per band, for every language. No other language's exam
     is researched. See "Generalised: the task shapes for every language" in the research.
   - **Instructions only:** most shapes need nothing but instructions to Gemma.
   - **Needing something new:** some need prompts on screen, pictures, a short text or audio source,
     preparation time, or long turns taken in parts (task 9).
3. **Topics.** **Done.** The CEFR's themes, one list for every language, as with the task shapes. No
   language's courses are researched: the CEFR is the standard. Gemma sets each scene where the
   language is spoken (task 9). This feeds the ingredient lists (task 8).
   - Which of the CEFR's themes go with each band?

   **Result:** the CEFR's fourteen themes (personal identification, home and environment, daily
   life, free time, travel, relations with others, health, education, shopping, food and drink,
   services, places, language, weather), from the 2001 CEFR as reproduced by the Goethe-Institut.
   - **Every band draws from all of them.** The CEFR doesn't assign themes to levels.
   - **The band sets the treatment:** Basic, the learner's own life and everyday needs; Independent,
     familiar and general topics with opinions, current events, culture and the unexpected;
     Proficient, the abstract, complex, sensitive or contentious side.
   - **Sensitive issues** belong to the Proficient band.

## B. Measuring the models

4. **Band evals.** Extend the evals pipeline (`tools/evals.py`, `.github/workflows/evals*.yml`,
   non-blocking, models from `evals/models.json`). Gemma, told a band, holds short conversations in
   each language.
   - **Measured:** at Basic, short sentences and common words (sentence length, and the share of
     words in a frequency list for the language).
   - **Judged:** whether replies show the band's features, and whether Gemma acts as the listener
     the band assumes (at Basic, answering a request to repeat with a slower, simpler rephrasing).
   - **Output:** a report per band and language, and a decision on which bands each language offers.
     A band a model can't do isn't offered.
   - **4a. One-time Claude judgment.** In a session, Claude judges a sample of Gemma's replies per
     band and language against the band descriptors from task 1. This isn't a permanent eval: it's a
     one-off reference, saved with the sample, to test other judges against.
   - **4b. Gemma as judge.** Gemma judges the same sample: E4B, judging replies written by E2B and by
     itself, set up to look for what's wrong. Its judgments are compared with Claude's.
     - If they agree closely enough, Gemma becomes the permanent judge in CI.
     - If not, the permanent eval keeps only the measurable parts (sentence length, word commonness).
5. **Band-aware translation evals.** Extend the translation evals: translations told a band are
   checked for keeping the meaning (chrF against references, as now) and, at Basic, for simplicity.

## C. Foundations in the app

6. **Rename the help modes** to Repeat word, Repeat phrase and Free talk. This changes the titles of
   `Mode` in `app/src/main/java/com/pedant/app/Scenarios.kt` only; Free talk itself is a separate
   roadmap item.
7. **Band setting.**
   - **Choosing:** a `Band` (Basic, Independent), chosen first in New conversation and
     remembered.
   - **Saving:** saved with the conversation (`SavedConversation.kt`).
   - **Gemma:** a `{band}` placeholder in `instructions.json` (read by `Instructions.kt` and
     `tools/app_languages.py`), so Gemma's replies and the learner's translations follow the band,
     keeping the meaning over simplicity.
   - **Quiz:** its word limits (`Quiz.wordLimit` in `Quiz.kt`) follow the band, not the help mode.

## D. Scenarios

8. **Ingredient lists.** A data file in `app/src/main/assets/`, in two layers:
   - **Shapes:** each band's task shapes, from task 2's generalised list (interview, everyday
     transaction, plan together, talk then questions, defend a view then debate, and so on). Each
     shape says who leads, Gemma's role, and what prompts it gives the learner.
   - **Content:** for each shape, the topics, places, relationships and twists it can be filled
     with, from tasks 1 and 3.
9. **Generated scenarios.** Gemma writes the scene, its role and the opening, set where the language
    is spoken. The ingredients are drawn at random:
    1. a shape among the band's shapes;
    2. content for that shape;
    3. the setting, which Gemma localises.
    - **A choice of two:** the app draws two scenarios and the learner picks one, as the exams do
      ("Vous tirez au sort deux sujets. Vous en choisissez un").
    - **The card:** each draw is shown as a card: the shape, the situation, and the shape's prompts
      (the words to ask about, the points to plan, the statement to argue). The two cards are the
      choice. The chosen one stays above the conversation, where `scenario.scene` is now.
    - **New app pieces:** shapes that need something the app doesn't have yet are only drawn once it
      exists. That covers pictures, a short text or audio source, preparation time, and long turns
      taken in parts.
    - **Replaces:** `Scenario` (Scenarios.kt) and the `roles` in `instructions.json`.
    - **Saving:** the card is saved with the conversation, for Continue and the quiz.
10. **Gemma asks for the scene.** This is the interview shape with the topic left to the learner:
    Gemma asks the learner to fill it in, and the answer becomes the scene. At Basic it asks in the
    learner's own language; at Independent, in the language being learned.
11. **Your own scenarios.** The learner describes a situation, and Gemma builds the scene at the band.
12. **Talk about whatever** in every band: the conversation shape with no topic, today's Default with
    the band applied.
13. **New conversation flow.** Language → band → two drawn cards, your own, or talk about whatever →
    Gemma's question, if the shape has one → the scene. This changes `LanguageScreen` in
    `MainActivity.kt` and `startSession` in `AppViewModel.kt`.
14. **Openings.** The shipped openings are removed (`openings.json`, `OpeningCache.kt`,
    `tools/build_openings.py`, `.github/workflows/openings.yml`): Gemma writes each opening on the
    phone.
15. **Generated-scene evals.** A sample of generated scenes per band and language, checked for sense
    and level. This extends task 4.

## E. Tests and docs

16. **Tests and docs.** Unit tests and screenshots for the new screens, like `QuizScreenshotTest`;
    DECISIONS.md and ROADMAP.md updated as decisions are made.
