#ifndef PEDANT_PHONEMIZE_H
#define PEDANT_PHONEMIZE_H

/* Starts espeak-ng with its data directory (the espeak-ng-data folder itself). 1 if it's ready. */
int pedant_espeak_init(const char *data_dir);

/* [text]'s phonemes in IPA from espeak-ng's [voice], clauses joined by spaces, words separated by
   spaces as espeak-ng writes them. A string to free(), or NULL if espeak-ng isn't ready or doesn't
   know the voice. */
char *pedant_phonemize(const char *voice, const char *text);

#endif
