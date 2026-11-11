# Roadmap

Ideas for later. Nothing here is being built yet.

## Conversations

- **Free conversation toggle.** A switch inside a conversation to talk freely in the language
  instead of saying it in English first, with mistakes explained in English. (A separate
  Conversation mode existed before and was removed in favour of this.)
- **Formal or casual.** A per-conversation choice of register: formal (vous, polite forms) or
  casual (tu, everyday speech), used for Gemma's replies and the translations of what you say.
- **Your own scenarios.** Write a scenario (who Gemma plays and the setting) alongside the
  built-in ones. Their openings are written on the phone the first time and saved, since only the
  built-in scenarios' openings ship with the app.

## Practice

- **Word grades as labels.** Show each word's grade as Right / Close / Off instead of a percentage,
  once scoring is settled.

## Models

- **A/B test Gemma sizes.** Compare the current Gemma 4 E4B with the smaller, lower-quality
  Gemma 4 E2B for speed, memory and the quality of replies, translations and word meanings.
- **Evals with Langfuse, as a non-blocking CI job.** Changing a model (for example, swapping in
  E2B) runs evaluations on fixed test conversations in CI, logged to Langfuse, and produces a report
  of whether it's better or worse. The job reports only; it never blocks a build.

## Settings

- **Your own language.** Choose the language you speak (English today) for translations, meanings
  and explanations.
