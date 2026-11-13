"""
The app's languages, read from the app's own list (assets/languages.json): the one place the tools
and workflows read them.

    python3 tools/app_languages.py

prints a line per language: its code, English name, Piper voice and espeak-ng voice, tab-separated.
"""

import collections
import json
import os
import unicodedata

LANGUAGES_JSON = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app/src/main/assets/languages.json")

Language = collections.namedtuple("Language", "code name native voice espeak")


def languages():
    """Every language of the app, in its order."""
    with open(LANGUAGES_JSON, encoding="utf-8") as f:
        return [Language(l["code"], l["englishName"], l["nativeName"], l["piperVoice"], l["espeakVoice"])
                for l in json.load(f)["languages"]]


def dictionary_key(word):
    """A word's lookup key in the dictionaries, as the app's Dictionary.key makes it."""
    return unicodedata.normalize("NFC", word).lower().replace("’", "'")


if __name__ == "__main__":
    for lang in languages():
        print(f"{lang.code}\t{lang.name}\t{lang.voice}\t{lang.espeak}")
