# Roadmap

Ideas for later. Nothing here is being built yet.

## Conversations

- **Free conversation toggle.** A switch in the conversation settings (the gear) to talk freely in
  the language instead of saying it in English first, with mistakes explained in English. (A
  separate Conversation mode existed before and was removed in favour of this.)
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
