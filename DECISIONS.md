# Decisions

The decisions made about Pedant, grouped by area. Ideas not yet built are in [ROADMAP.md](ROADMAP.md).

## The app

- **Free.** No ads and no paid features. At most a voluntary "buy me a coffee" link that unlocks nothing.
- **Offline.** The models run on the phone.
- **Android only.** iOS feasibility is a roadmap item.
- **Open source.** GPL-3.0-or-later. espeak-ng is kept, which requires the app to be open source.
- **Not gamified.** No streaks, milestones or "learned words" tracking. Learning is meant to be organic, through conversation, not a course.
- **Technically correct.** What the app shows (sounds, meanings, rules) should be correct, since it's called Pedant.
- **Partly deterministic.** Not 100% LLM-based: grading, sounds and dictionary lookups don't depend on Gemma.

## Models

- **Language model:** Gemma 4, run with LiteRT-LM on the CPU. The GPU option was removed: on the test phone the CPU was faster and more responsive.
  - **E2B** is the default for new installs.
  - **E4B** can be downloaded and selected in Settings. Its description says it gives better replies, translations and word meanings, and that it needs more memory, is slower, and can make the app close on some phones.
- **Speech recognition:** Meta Omnilingual ASR CTC 300M, for the words.
  - Whisper was rejected because it corrects grammar.
  - Vosk was rejected because it snaps to the closest word it knows ("mead").
- **Pronunciation:** ZIPA (Zipa-Cr-large), for the sounds. It's used together with Omnilingual: one hears words, the other hears sounds. The NS variant was avoided because of its license.
- **Voice:** Piper, chosen over MMS-TTS (clearer, though more robotic) and over Android's own text-to-speech.
  - One voice per language.
  - Ukrainian uses tetiana; lada was removed and is deleted on startup if present.
- **Phonemes:** espeak-ng, compiled into the app, for the expected sounds shown.
- **Downloads:** the models are downloaded on first run from Hugging Face, not bundled in the APK.
- **Memory:**
  - The speech recognizer loads only while the learner talks.
  - ZIPA is released after long audio.
- **Rejected:** Gemini Nano. It can't be tested without a supported phone or measured in CI.
- **In the evals:**
  - Qwen3.5-4B and Ministral 3 3B were removed.
  - Tiny Aya Global was added, run on llama.cpp. It isn't adopted unless it's significantly better than Gemma.
- **Run a model on your own computer:** on the roadmap as something to explore.

## Languages

- **25 languages.** The list started at 28: languages well supported by Gemma, Omnilingual, ZIPA and Piper together.
  - **Arabic removed:** it's written without most vowels, so its expected sounds are guesses.
  - **Mandarin and Vietnamese removed:** the app doesn't hear tones. Bringing them back is on the roadmap.
- **Serbian:** written in Latin script, and named "Srpski".
- **Learner's own language:** chosen in Settings on the home page. English is the default.
  - Dictionary meanings stay in English for now.
  - The English-style "sounds like" respelling is hidden for other own languages.
- **Dialects (such as Québécois French):** not on the roadmap for now.

## Conversations

- **Opening:** Gemma opens the conversation.
- **The loop:**
  1. The learner says or types what they want to say in their own language.
  2. Gemma translates it, and the app shows "Say: …".
  3. The learner repeats it until it's right.
  4. The tutor replies.
- **Modes:**
  - **Beginner:** word by word first, on a full-screen page with the phrase at the top, each word's IPA, its "sounds like" respelling and its meaning. Skip is offered after 2 failed tries at a word. Then the whole phrase.
  - **Intermediate:** the whole phrase straight away.
  - **Conversation-only mode:** removed; free conversation is planned as its own mode.
  - **Renaming:** planned, to Repeat word, Repeat phrase and Free talk.
- **Skip:** offered after 2 failed tries at a phrase.
- **Failed tries:** only the latest one is kept in the conversation.
- **Practice window (tapping a try):**
  - Retry the whole phrase, or practise single words.
  - Tries there are graded only; the tutor doesn't reply.
  - A better whole-phrase score replaces the one in the conversation.
  - Getting a failed phrase right there closes the window and marks the phrase as right.
  - Only the latest try keeps its recording.
- **Formal / Casual:** a per-conversation setting, in the conversation settings (the gear). Casual has its own opening, written by E4B.
- **How the phrase is given:** voice and text, voice only, or text only (never neither), set in the conversation settings.
- **Scenarios:** Default plus five role-plays.
  - The role-plays: asking for recommendations, coffee with a friend, a first date at a restaurant, the bus stop, the airport.
  - Each opens with the same line every time. The openings are written ahead by E4B and ship with the app.
  - Gemma's own limits apply to content; no extra restrictions are added.
- **Gemma's memory:** the main conversation holds only the language being learned. Translations are separate one-off steps, made when needed.
- **Your own line:** while its phrase is being practised, it can be edited or deleted from its three-dot menu (no long press).
- **Tutor's note:** a note on a common expression in a translated phrase.
  - About one in every 5 to 8 translations, at random.
  - Only on translations of the learner's own lines.
  - It slots in above the phrase card with an animation, in the strong violet.
- **Continue:** one saved conversation, replaced when a new one starts. It continues exactly where it was left. If Android closes the app, it opens on the home page, with Continue.

## Quiz

- **Offered:** when New conversation is tapped, the app asks "Quiz on your last conversation?" with Quiz and Skip. It's offered again only after more phrases have been said.
- **Format:** full screen. Leaving it part way and continuing picks it up where it was left.
- **Length:** one question per 2 phrases said, up to 10.
- **Question types:**
  - fill the gap;
  - match the pairs: 5 pairs, each matched pair in a colour of its own;
  - the article, in languages whose articles show gender;
  - what did you hear;
  - what does it mean;
  - what does this word mean;
  - put it in order: the first word is lower-cased, except in German;
  - say it from memory;
  - spot the mistake: Intermediate only;
  - said or silent: final consonants and liaisons, French only.
- **Rejected:** "pick the reply".
- **Word limits:** put it in order and say it from memory use 5 words or fewer in Beginner and 8 or fewer in Intermediate.
- **No score at the end.** Each question shows Right or Not quite.

## Grading

- **Graded against one voice.** Each sound is graded against one voice's pronunciation (the
  language's Piper voice), as a clear model to learn from. Grading's leniency by band and prosody are
  on the roadmap.
- **A word passes if:**
  - the speech recognizer heard it; or
  - its sounds are close enough to espeak-ng's expected sounds, or to ZIPA's hearing of Piper (whichever is closer); or
  - the sound-alike check passes: Piper says what was heard, and ZIPA compares it, so "sava" counts as "Ça va".

  Sounds alone can't pass words after the last word the recognizer heard anything for.
- **g and ɡ** count as the same sound.
- **Short words** (2–3 sounds) said on their own forgive one extra sound.
- **Recordings aren't trimmed.** Trimming and trimming with a buffer were evaluated against no trimming, with no difference.
- **Expected sounds come from espeak-ng's reading of the whole phrase,** which includes liaisons.
- **Rejected:** expected sounds from the dictionary (options B and B2), after calibration showed no gain.
- **Calibration:** grading changes are checked on real speech (FLEURS recordings in French, Spanish, German and English).

## Words and dictionary

- **Tappable words:** every word can be tapped, with a dotted underline.
- **The word bubble:**
  - shows the meaning, the IPA and an English-style "sounds like" respelling;
  - plays the word at normal speed on opening and on switching words;
  - switches words with looping arrows or a sideways swipe;
  - has Normal and Slow; Slow is 40% speed.
- **Dictionaries:** built from English Wiktionary via kaikki.org.
  - **Contents:** cut down to spelling, part of speech, IPA, English meanings and the word a form belongs to.
  - **Distribution:** one per language, downloaded separately from the app, from a GitHub Release.
  - **Rebuilds:** monthly. Each run commits a small manifest (versions and checksums), which keeps the schedule active. The dictionaries themselves aren't committed.
  - **Credit:** CC BY-SA, credited to Wiktionary contributors and Wiktextract.
- **The dictionary is the source of truth for display.**
  - Gemma is the fallback for words the dictionary lacks; its meanings are labelled as generated.
  - espeak-ng's IPA is used where the dictionary has none, labelled as generated.
- **The most common meaning shows first,** using part-of-speech counts from Universal Dependencies treebanks.
- **Every meaning is shown.** The dictionaries keep every meaning Wiktionary lists, and the bubble's scrolling meanings area shows them all, for now in place of Gemma choosing the meaning that fits the sentence.
  - Obsolete, archaic, dated, rare and historical meanings come last, labelled ("(archaic) …").
  - Meanings longer than 160 characters are cut.
- **Splitting words:** a word the dictionary doesn't have is looked up in parts, split at an apostrophe or hyphens. Words with their own entries aren't split.
- **Inflected forms:** added for every language except Finnish.
- **Kept and left out:** definitions aren't removed to tidy entries; phrase-level definitions aren't used.

## Look and feel

- **Palette:** soft pastel, with a mist-blue background, pastel cyan as the main colour, pastel violet as the second and pastel orange for the main action.
- **Mockups:** used for style only. They add no functionality (no streaks).
- **Logo:** the capital P from Potta One, in white, with an even shadow on all sides, on a vibrant three-way gradient (violet top left, cyan top right, orange at the bottom). The logo is vibrant; the app is pastel.
- **Home page:** Continue conversation (greyed out when there's none), New conversation and Settings. The Settings button says only "Settings".
- **Loading:** an estimated loading bar. The app opens without a loading screen while the models load behind it.
- **Battery:** a notice asks for unrestricted battery use and opens that setting.
- **Footer:** the conversation footer folds away with a chevron, leaving Hold to talk.

## Development

- **Commits:** authored as quentinjayp with the GitHub noreply address (9011291+quentinjayp@users.noreply.github.com), with no Claude co-author or attribution lines.
  - The history was rewritten to remove the personal email.
  - The environment's setup script sets this identity for new sessions.
  - The environment's hook asking for the Anthropic address is ignored.
- **Builds:** GitHub Actions builds the APK and publishes it as a release. https://github.com/quentinjayp/pedant/releases/latest/download/pedant.apk is always the latest.
- **Evals:** translation evals run as non-blocking CI jobs and never mark a run as failed.
  - Scored against human reference translations (chrF), with a promptfoo report. Langfuse isn't used.
  - Cross-language evals cover own languages other than English.
- **Quiz tests:** unit tests and screenshots of the quiz screens run as a non-blocking CI job, published on the "screenshots" pre-release.
- **Working with Claude:**
  - Ask rather than assume, and remind about unanswered questions.
  - Don't start work without a go-ahead, and don't add functionality that wasn't asked for.
  - Discussions don't need multiple-choice prompts.

## Discussed, not decided

- **Branches and releases:** dev and main branches with releases cut from main, or trunk-based with release branches. Semantic version tags, a fixed link to the latest development build, and main as the default branch.
