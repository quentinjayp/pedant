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
- **Downloads:** the models are downloaded on first run from Hugging Face, not bundled in the APK. The exception is Silero VAD (under 1 MB), which tells when the learner has stopped talking.
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
  - **Named for the help they give,** from the most to none: Repeat word, Repeat phrase and Free talk (Repeat word and Repeat phrase were Beginner and Intermediate).
  - **Repeat word:** word by word first, on a full-screen page with the phrase at the top, each word's IPA, its "sounds like" respelling and its meaning. Skip is offered after 2 failed tries at a word. Then the whole phrase.
  - **Repeat phrase:** the whole phrase straight away.
  - **Free talk:** the learner says what they want in the language. What the speech recognizer heard is shown, its words tappable, and goes straight to the tutor, who replies. Nothing is repeated or graded, there's no text box, and the quiz isn't made from it.
    - The speech recognizer is Omnilingual 300M, as in the other modes. A long turn is recorded and heard whole; cutting it at pauses is on the roadmap, for if long turns fail.
  - **Conversation-only mode:** removed.
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
  - spot the mistake: Repeat phrase only;
  - said or silent: final consonants and liaisons, French only.
- **Rejected:** "pick the reply".
- **Word limits:** put it in order and say it from memory use 5 words or fewer at the Basic band and 8 or fewer at Independent.
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
- **When Gemma explains words:** only for words the dictionary lacks, and only when asked for: a tapped word, then the other words of its sentence the dictionary lacks; and the phrase's words as the word-by-word page or the practice window opens, the word shown first. Nothing is looked up ahead for new sentences. The Tutor's note still looks for an expression in the background.
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
  - In Russian, Ukrainian, Serbian and Slovenian, Wiktionary's inflection tables mark stress or tone, which normal writing leaves out. Those forms are filed under their plain spelling, so text finds them, and are shown with their marks.
  - Every word under a spelling is shown: a spelling that is a form of two words ("замка", of за́мок and замо́к) shows both.
- **Kept and left out:** definitions aren't removed to tidy entries; phrase-level definitions aren't used.

## Look and feel

- **Palette:** soft pastel, with a mist-blue background, pastel cyan as the main colour, pastel violet as the second and pastel orange for the main action.
- **Mockups:** used for style only. They add no functionality (no streaks).
- **Logo:** the capital P from Potta One, in white, with an even shadow on all sides, on a vibrant three-way gradient (violet top left, cyan top right, orange at the bottom). The logo is vibrant; the app is pastel.
- **Home page:** Continue conversation (greyed out when there's none), New conversation and Settings. The Settings button says only "Settings".
- **New conversation:** the mode's description at the top; below it, the whole page scrolls: Level, Mode, Style, Scenario, Language, then Start.
  - **Dropdowns:** a choice with more than three options is a dropdown, with a search box at the top of its menu. Scenario and Language are.
  - **Language:** chosen, then started with Start, and remembered for the next conversation.
- **Loading:** an estimated loading bar. The app opens without a loading screen while the models load behind it.
- **Battery:** a notice asks for unrestricted battery use and opens that setting.
- **Footer:** the conversation footer folds away with a chevron, leaving the talk button.
- **Talk buttons:** a tap starts listening and a second tap stops it, on every talk button (the conversation's, the practice window's, the word-by-word page's and the quiz's).
  - **Getting ready:** a spinner shows from the tap until the microphone is recording, then the button says "Tap to stop". There's no beep: it was removed after English speech came out in Arabic letters, suspected to be the beep ending up in the recording.
  - **Stopping on its own:** after 5 seconds with no speech, since the microphone started recording or since the learner last spoke. Silero VAD tells speech from silence.

## Levels and scenarios

- **One scale for every language: the CEFR.** Pedant's levels are modelled on the CEFR's bands, the
  same for every language. They structure the conversations and give the app guidance and goals at
  each band.
- **Two bands: Basic (A1–A2) and Independent (B1–B2).** The Proficient band (C1–C2) is on the
  roadmap only: a learner at that level can talk with a general assistant such as Claude directly,
  and it would need models other than the phone's.
  - The CEFR was written to be language-neutral and is used for non-European languages too (Turkey's TYS, Indonesia's BIPA framework, Russia's TORFL mapping).
- **Choosing the band:** first in New conversation ("Level"), remembered for the next one, and saved with the conversation. Nothing places the learner in a band or tracks progress through them.
- **Gemma at each band** (instructions.json's bands):
  - **Basic:** short sentences of common everyday words and set phrases, mostly the present tense, simple linking, everyday topics, one question at a time. It helps: asked to repeat, it says it again more slowly and simply; after a mistake, it answers what was meant. One or two short sentences a reply.
  - **Independent:** connected sentences and some complex ones, familiar topics and now and then something less routine, asking for opinions and reasons and giving its own. It rephrases briefly when the learner is lost. Two to four sentences a reply.
  - **Names:** where a scene needs one, Gemma makes up a plausible name, never a placeholder.
  - **Tested** in French, in a session: two conversations per band with E4B, judged against the band descriptors in docs/research/bands.md.
- **Translations follow the band:** the learner's lines are translated into what they can say at it, keeping the whole meaning.
  - **How:** each band's instructions say how to translate at it, with five examples shown in English, the same for every language.
  - **Tested in French** (the 20 eval sentences, judged by Claude in a session): at Basic, 16 of 20 translations were at the level with the examples, against 9 without. But a detail was lost in 12 of 20, against 5 without, even though the instructions say not to leave anything out.
  - **French examples** did no better than the English ones. Telling Gemma to say it "as simply as you would explain it to a child, but as an adult speaking to adults" added little, with or without the examples.
- **Gemma isn't the judge of translations at a band.** Gemma E4B judged the same French translations as Claude, against the same criteria from the CEFR descriptors, with and without examples of judgments. It missed most of the meaning lost and the unnatural French that Claude found, marked Basic translations down for what A1–A2 allows (the passé composé), and sometimes judged the English instead of the French.
- **Openings:** the shipped openings are written for each band.
- **One set of task shapes per band, for every language.** The conversation tasks for each band come
  from the oral exams' shapes, generalised once and applied to every language, not taken from each
  language's own exam. Only the setting of each scene differs by language. See
  [docs/research/bands.md](docs/research/bands.md).
- **Each band's scenarios are the exams' scenarios.** A fixed list for each band, of the situations
  the oral exams use at its levels (A1–A2 for Basic, B1–B2 for Independent), from the research. The
  exams are only the source: nothing is tested or graded. A scenario at a level both bands cover is
  in both lists. The lists are still to be reviewed.
- **Choosing a scenario:** a dropdown of No Scenario, Random, 2 Card, then each of the band's
  scenarios by name. Random picks one; 2 Card draws two as cards and the learner picks one. The app's
  random numbers draw them, never Gemma: Gemma picking "at random" isn't random.

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
