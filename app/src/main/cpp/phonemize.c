#include "phonemize.h"

#include <stdlib.h>
#include <string.h>

#include <espeak-ng/speak_lib.h>

static int ready = 0;

int pedant_espeak_init(const char *data_dir) {
  if (!ready) ready = espeak_Initialize(AUDIO_OUTPUT_SYNCHRONOUS, 0, data_dir, 0) > 0;
  return ready;
}

char *pedant_phonemize(const char *voice, const char *text) {
  if (!ready || espeak_SetVoiceByName(voice) != EE_OK) return NULL;
  size_t cap = 256, len = 0;
  char *out = malloc(cap);
  if (!out) return NULL;
  out[0] = '\0';
  const void *rest = text;
  while (rest) {
    const char *clause = espeak_TextToPhonemes(&rest, espeakCHARS_UTF8, espeakPHONEMES_IPA);
    if (!clause) break;
    size_t n = strlen(clause);
    if (len + n + 2 > cap) {
      while (len + n + 2 > cap) cap *= 2;
      char *bigger = realloc(out, cap);
      if (!bigger) {
        free(out);
        return NULL;
      }
      out = bigger;
    }
    if (len) out[len++] = ' ';
    memcpy(out + len, clause, n);
    len += n;
    out[len] = '\0';
  }
  return out;
}
