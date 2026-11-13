"""
The app's languages, read from Languages.kt: the one place the tools and workflows read them.

    python3 tools/app_languages.py

prints a line per language: its code, English name, Piper voice and espeak-ng voice, tab-separated.
"""

import collections
import os
import re
import unicodedata

LANGUAGES_KT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app/src/main/java/com/pedant/app/Languages.kt")

Language = collections.namedtuple("Language", "code name native voice espeak")

_PATTERN = re.compile(r'Language\("([a-z]{2})", "([^"]+)", "([^"]+)", "([^"]+)".*espeakVoice = "([^"]+)"')


def languages(path=LANGUAGES_KT):
    """Every language in Languages.kt, in its order."""
    with open(path, encoding="utf-8") as f:
        return [Language(*m.groups()) for m in map(_PATTERN.search, f) if m]


def dictionary_key(word):
    """A word's lookup key in the dictionaries, as the app's Dictionary.key makes it."""
    return unicodedata.normalize("NFC", word).lower().replace("’", "'")


if __name__ == "__main__":
    for lang in languages():
        print(f"{lang.code}\t{lang.name}\t{lang.voice}\t{lang.espeak}")
