# Plan: Levels and scenarios

The plan for ROADMAP.md's "Levels and scenarios" section, broken into tasks. Tasks marked Done are
built; the others aren't.

## Context

The section adds a second dimension beside the help modes: how hard the language is, on the CEFR's
first two bands, Basic (A1–A2) and Independent (B1–B2). The Proficient band (C1–C2) is on the roadmap
only, with models other than the phone's. It replaces the fixed scenario list with a fixed list for
each band: the scenarios the oral exams use at the band's levels, chosen from a dropdown (No
Scenario, Random, 2 Card, or each by name). The roadmap says to research the bands first, and to
measure whether Gemma can do each band in each language.

## Order

- **A (research)** comes first. It gates the evals (B) and the scenario lists (task 8).
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
   language is spoken (task 9). This feeds the scenario lists (task 8).
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

4. **Band evals.** **Done for French**, in a session: Claude held two conversations per band with
   E4B and judged Gemma's replies against the band descriptors. The band texts were adjusted from it
   (a length limit, and no placeholder names), and the Proficient band moved to the roadmap. The
   pipeline in CI and 4b weren't built. As planned: extend the evals pipeline (`tools/evals.py`, `.github/workflows/evals*.yml`,
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
   - **Tested in French, in a session:** E4B translated the 20 eval sentences at each band, Claude
     judged them against the CEFR descriptors (meaning kept, at the level, natural French), and Gemma
     E4B judged the same translations. This is 4a and 4b, for translations.
   - **The translation instructions** gained each band's guide, with five examples shown in English:
     at Basic, 16 of 20 at the level against 9, but a detail lost in 12 against 5.
   - **Gemma as judge failed** (see DECISIONS.md), so by 4b the CI eval keeps only what can be
     counted.
   - **Done:** the Evals workflow translates with each model at both bands (`--band` in
     `tools/evals.py`), and the report gives chrF and the words per translation for each. Not built:
     a word-commonness measure (which frequency list is undecided).

## C. Foundations in the app

6. **Rename the help modes** to Repeat word, Repeat phrase and Free talk. **Done**, with Free talk
   itself: the titles of `Mode` in `app/src/main/java/com/pedant/app/Scenarios.kt` changed (its
   names stay, as the saved choice and saved quizzes use them), and Free talk was added.
7. **Band setting.** **Done.** The shipped openings are written for each band, and the translation
   evals translate at Basic until task 5.
   - **Choosing:** a `Band` (Basic, Independent), chosen first in New conversation and
     remembered.
   - **Saving:** saved with the conversation (`SavedConversation.kt`).
   - **Gemma:** a `{band}` placeholder in `instructions.json` (read by `Instructions.kt` and
     `tools/app_languages.py`), so Gemma's replies and the learner's translations follow the band,
     keeping the meaning over simplicity.
   - **Quiz:** its word limits (`Quiz.wordLimit` in `Quiz.kt`) follow the band, not the help mode.

## D. Scenarios

8. **Each band's scenario list.** A data file in `app/src/main/assets/`: each band's scenarios, from
   the oral exams' scenarios in the research (and the CEFR's tasks where the exams give one
   example), each with its situation, Gemma's role and the card's prompts, and its setting for each
   of the 25 languages, where the language is spoken. Written by Claude in a session, not by Gemma.
   **Done:** `app/src/main/assets/scenarios.json`, 28 scenarios: the two that suit both bands
   (getting to know you, planning something together) are one entry each, in both lists. Still to
   be reviewed. The lists:
   - **Basic (A1–A2):**
     1. Getting to know you: the tutor asks about you, your family, tastes and activities (DELF
        A1–A2, Goethe A1).
     2. Getting to know the tutor: you ask the questions, on a theme such as the weekend (DELF A1,
        Goethe A1).
     3. Buying something in a shop: the price, the quantities, paying (DELF A1).
     4. Asking for information at the station (DELF A2).
     5. Asking someone for things and favours (Goethe A1).
     6. Talking about an event, an activity, a project or a place, then answering questions (DELF A2).
     7. Planning something together (Goethe, A2–B1: in both bands).
     8. Ordering a meal.
     9. Pointing out that something is wrong ("The food is cold", "There's no light in my room").
     10. Buying tickets and using public transport.
     11. Asking for and giving directions.
     12. Making a doctor's appointment and saying what's wrong.
     13. Making, accepting and turning down invitations.
     14. Arranging when and where to meet.
     15. A short phone call with someone you know.
   - **Independent (B1–B2):**
     1. Getting to know you: work, studies, habits, past and plans (DELF B1: in both bands).
     2. An unusual everyday situation to sort out: propose solutions and insist (DELF B1).
     3. Planning something together, such as visiting a friend in hospital (Goethe B1: in both bands).
     4. Presenting a topic, then answering questions (Goethe B1).
     5. Defending a view, then debating it (DELF B2, Goethe B2).
     6. Telling a story about something that happened to you.
     7. The plot of a book or film, and what you thought of it.
     8. Your plans and ambitions, with reasons.
     9. Choosing between options: what to do, where to go.
     10. Sorting out travel arrangements, at a travel agent or a tourist office.
     11. Returning a faulty purchase, or making a complaint.
     12. Describing symptoms to a doctor and asking for advice.
     13. A job interview.
     14. Phoning about a problem at a hotel or with a hire car.
     15. Explaining how to do something.
   - **Left out until the app can do them:** a prepared talk (preparation time), an opinion on a
     short document (a text to read), describing a picture (pictures).
9. **Scenarios in the app.** **Done.** The band's list replaces `Scenario` (Scenarios.kt) and the `roles` in
   `instructions.json`. Gemma is given the scene with its setting for the language.
   - **Choosing:** the Scenario dropdown in New conversation: No Scenario, Random, 2 Card, then each
     of the band's scenarios by name. Random and 2 Card are drawn with the app's random numbers,
     never by Gemma.
   - **2 Card:** two scenarios drawn as cards (the situation and its prompts), and the learner picks
     one, as the exams do ("Vous tirez au sort deux sujets. Vous en choisissez un").
   - **The card:** the chosen scenario's card stays above the conversation, where `scenario.scene` is
     now.
   - **Saving:** the scenario is saved with the conversation, for Continue and the quiz.
10. **Gemma asks for the scene.** Dropped: replaced by each band's fixed scenarios.
11. **Your own scenarios.** Dropped: replaced by each band's fixed scenarios.
12. **No Scenario** in every band: **Done.** no scene, today's Default with the band applied.
13. **New conversation.** **Done.** The page is already reworked (Level, Mode, Style, Scenario, Language,
    Start); the Scenario dropdown changes to No Scenario, Random, 2 Card and the band's list, and 2
    Card shows its two cards. This changes `LanguageScreen` in `MainActivity.kt` and `startSession`
    in `AppViewModel.kt`.
14. **Openings.** **Done.** Written by Claude in a session: 1,600, for each scenario, band, register
    and language, in `assets/openings.json`, named language-own-scenario-register-band. They replace
    the openings E4B wrote in CI (`tools/build_openings.py` and `.github/workflows/openings.yml` are
    removed). They no longer change name when the instructions change: a scenario whose role changes
    has its openings edited in the file.
15. **Scenario evals.** Was: generated scenes checked for sense and level. With fixed scenarios, what
    to check is undecided.

## E. Tests and docs

16. **Tests and docs.** Unit tests and screenshots for the new screens, like `QuizScreenshotTest`;
    DECISIONS.md and ROADMAP.md updated as decisions are made.
    - **Done so far:** screenshots of New conversation (`NewConversationScreenshotTest`: no language,
      a language chosen, the menus, 2 Card, one scenario), and the docs for each task.
