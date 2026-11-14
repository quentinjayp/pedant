# Roadmap

Ideas for later. Nothing here is being built yet.

## Modes

Three modes to choose from. Anything not mentioned here stays as it is today.

- **Beginner.** You say what you want in English, as now. The app then takes you through the
  phrase one word at a time in the bubble, and each word has to be said right. After two failed
  tries at a word, Skip is offered. Once the words are done, you say the full phrase.
- **Intermediate.** Today's behaviour: say it in English, then repeat the full phrase until it's
  right.
- **Advanced.** Free conversation in the language, with no English first. The tutor makes no
  corrections or suggestions about what you said, meaning it never makes you repeat a phrase until
  it's right, as Beginner and Intermediate do. This mode may use a different speech recognizer
  (undecided).
  - **Reaching for a missing word.** Say a sentence in the language with the one word you don't
    know in English, and get back just that word.
  - **Tutor's note.** After you say something, and before the tutor's reply, a correction or
    clarification of what you said is shown, perhaps as its own card in the conversation called
    "Tutor's note". The tutor then replies as normal.
  - **Asking about the language as you go.** Ask the tutor in English mid-conversation ("What does
    that mean?", "How do I say…?", "Why this word and not that one?"), then carry on.

## Conversations

- **Voice selector.** Choose the language's voice in the conversation settings (the gear).
- **Your own scenarios.** Write a scenario (who Gemma plays and the setting) alongside the
  built-in ones. Their openings are written on the phone the first time and saved, since only the
  built-in scenarios' openings ship with the app.

## Practice

- **Word grades as labels.** Show each word's grade as Right / Close / Off instead of a percentage,
  once scoring is settled.
- **IPA for both cases.** Where a word sounds different next to the following word, show both: the
  word on its own and as it's linked in the sentence. For example French liaison (les /le/, les
  enfants /lez‿ɑ̃fɑ̃/), Portuguese s before a vowel (os amigos /uz‿ɐˈmiɡus/), voicing changes in
  Slavic languages, and Swedish and Norwegian r merging with the next consonant.

## Models

- **A/B test Gemma sizes.** Compare the current Gemma 4 E4B with the smaller, lower-quality
  Gemma 4 E2B for speed, memory and the quality of replies, translations and word meanings.
- **Evals with promptfoo, as a non-blocking CI job.** Changing a model (for example, swapping in
  E2B) runs promptfoo in CI on a fixed set of phrases with known-good reference translations,
  scoring the model's translations by their similarity to the references, and produces a report of
  whether it's better or worse. The job reports only; it never blocks a build.

## Testing

- **Calibration with the app's own scoring code.** The calibration tool holds a hand-kept Python
  copy of the app's scoring rules, which can drift from the app. Instead, Python only records what
  the recognizers heard, and a Kotlin test scores it with the app's real code, the way the
  dictionary report works.

## Settings

- **Your own language.** Choose the language you speak (English today) for translations, meanings
  and explanations.

## Platforms

- **Investigate iOS feasibility.**
