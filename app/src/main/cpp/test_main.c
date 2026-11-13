/* For the check in CI: prints espeak-ng's IPA for each "voice<TAB>text" line on stdin. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "phonemize.h"

int main(int argc, char **argv) {
  if (argc < 2 || !pedant_espeak_init(argv[1])) {
    fprintf(stderr, "couldn't start espeak-ng with %s\n", argc > 1 ? argv[1] : "(no data dir)");
    return 1;
  }
  char line[8192];
  int failed = 0;
  while (fgets(line, sizeof line, stdin)) {
    line[strcspn(line, "\n")] = '\0';
    char *tab = strchr(line, '\t');
    if (!tab) continue;
    *tab = '\0';
    char *ipa = pedant_phonemize(line, tab + 1);
    if (!ipa || !*ipa) failed = 1;
    printf("%s\t%s\t%s\n", line, tab + 1, ipa ? ipa : "(nothing)");
    free(ipa);
  }
  return failed;
}
