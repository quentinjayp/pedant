# Band and scenario evals: Claude's judgments

Plan tasks 4 and 15 (docs/plans/levels-and-scenarios.md), run in a session on 2026-11-28 with
`tools/band_conversations.py`. Gemma 4 E4B held one conversation per band in each of the 25
languages, 50 in all, each in one of the band's scenarios (every scenario, and No Scenario, at least
once). Each opened with its shipped opening; Claude played the learner for three turns (Basic at
A1–A2, with some mistakes and requests to repeat; Independent at B1–B2) and judged Gemma's 150 replies.

What was judged, from docs/research/bands.md and each scenario in scenarios.json:
- **Level:** at Basic, short sentences of common words, one question at a time; at Independent,
  connected sentences, opinions and reasons, some less common words.
- **Helping (Basic):** asked to repeat, it says it again more simply; after a mistake, it answers
  what was meant.
- **Role and setting:** it stays in the scenario's role, where the scenario takes place.
- **Register:** formal or casual, as the conversation was set.
- **Correct language:** no made-up words or grammar mistakes.

## Results

| | Held | Not held |
|---|---|---|
| Level at Basic | 25 of 25 | |
| Level at Independent | 25 of 25 | |
| Repeating more simply when asked (Basic) | 5 of 8 (Czech, English, Hindi, Norwegian, Ukrainian) | Finnish (a sentence that means nothing), Slovak (didn't repeat, said "What I want to say:"), Serbian (asked the same wrong question again) |
| Role and setting | 48 of 50 | Hungarian Basic (the friend on the train said they were at the station waiting), Czech Independent (the neighbour talked of asking "the neighbour") |
| Understanding the learner | 49 of 50 | Turkish Basic (asked "Are you from America?" after the learner said they were Canadian) |
| Register | 44 of 50 | Formal became casual (tú, tu, Ty) in Spanish and Italian Independent and Romanian and Polish Basic; casual became formal (आप, Ви) in Hindi and Ukrainian Independent |
| Correct language | about 35 of 50 | see below |

**Level:** Gemma held both bands in every language. Basic replies were one or two short sentences
with everyday words and one question; Independent replies were two to four connected sentences,
asked for opinions and reasons, gave its own, and handled the scenario's complications (a refund
refused then given when the learner insisted, in Hindi and Ukrainian; rain on a day trip, in Danish;
the other side of the debate, in Polish).

**Correct language:** the problems are concentrated in a few languages, and are worse at Independent,
where replies are longer:
- **Many errors in nearly every reply:** Slovak (made-up forms: "niebolo", "robilaš", "bolaš",
  "bežalaš"; the Jizera Mountains, which are in Czechia, as Slovak mountains), Slovenian ("Mnenjam",
  "sporočljivo", Croatian words), Hungarian ("Időig találkozunk ott", "bevásárlásat", "tiszta
  tualet"), Romanian ("Ce anume te-a făcut să vin la mine?", mixed tu and dumneavoastră), Czech at
  Independent ("tyflí", "pesem", "v určitéch doběch").
- **Some errors:** Finnish ("Annaa", "Rovaniemiassa", "Halutko"), Serbian at Basic ("Kako vas
  zovem?"), Swahili at Basic ("busi", "mimi ni katika"), Turkish at Basic ("İzmir'liyim").
- **One slip or none:** English, French, German, Spanish, Italian, Dutch, Portuguese, Russian,
  Ukrainian, Polish, Greek, Swedish, Norwegian, Danish, Indonesian, Hindi.

**Openings:** the shipped openings (written by Claude) were never the problem; every conversation
went on from them in role.

**A mistake of the learner's, not Gemma's:** in the Swahili Basic conversation the learner asked about
the train to Mombasa, but the scenario was buying a ticket at a daladala stand in Dar es Salaam;
Gemma kept to its setting.

## What it means

By the plan, "a band a model can't do isn't offered." Gemma E4B can do both bands' level and the
scenarios in every language. What it can't do reliably is write correct Slovak, Slovenian,
Hungarian, Romanian and, at Independent, Czech. The app's default is E2B, which wasn't tested here
and is unlikely to do better.
