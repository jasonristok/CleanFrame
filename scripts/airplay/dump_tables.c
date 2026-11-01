/* SPDX-License-Identifier: GPL-3.0-or-later
   Build: cc -I <UxPlay>/lib/playfair dump_tables.c -o dump_tables && ./dump_tables playfair.bin
   Dumps the playfair tables (GPL-3.0, from UxPlay lib/playfair/omg_hax.h) to one binary file.
   Format: for each table: u8 name length, name, u8 element size (1 or 4), u32 LE count, data (LE). */
#include <stdio.h>
#include <stdint.h>
#include <string.h>
#include "omg_hax.h"
static void put32(FILE *f, uint32_t v) { for (int i = 0; i < 4; i++) fputc((v >> (8 * i)) & 0xff, f); }
static void bytes(FILE *f, const char *name, const unsigned char *p, size_t n) {
  fputc((int) strlen(name), f); fputs(name, f); fputc(1, f); put32(f, (uint32_t) n); fwrite(p, 1, n, f);
}
static void words_l(FILE *f, const char *name, const unsigned long *p, size_t n) {
  fputc((int) strlen(name), f); fputs(name, f); fputc(4, f); put32(f, (uint32_t) n);
  for (size_t i = 0; i < n; i++) { if (p[i] > 0xffffffffUL) { fprintf(stderr, "value too large in %s\n", name); } put32(f, (uint32_t) p[i]); }
}
static void words_u(FILE *f, const char *name, const uint32_t *p, size_t n) {
  fputc((int) strlen(name), f); fputs(name, f); fputc(4, f); put32(f, (uint32_t) n);
  for (size_t i = 0; i < n; i++) put32(f, p[i]);
}
int main(int argc, char **argv) {
  FILE *f = fopen(argv[1], "wb");
  fwrite("PLYF", 1, 4, f);
  bytes(f, "message_key", &message_key[0][0], sizeof(message_key));
  bytes(f, "message_iv", &message_iv[0][0], sizeof(message_iv));
  bytes(f, "z_key", z_key, sizeof(z_key));
  bytes(f, "x_key", x_key, sizeof(x_key));
  bytes(f, "t_key", t_key, sizeof(t_key));
  words_l(f, "table_s5", table_s5, sizeof(table_s5) / sizeof(table_s5[0]));
  words_l(f, "table_s6", table_s6, sizeof(table_s6) / sizeof(table_s6[0]));
  words_l(f, "table_s7", table_s7, sizeof(table_s7) / sizeof(table_s7[0]));
  words_l(f, "table_s8", table_s8, sizeof(table_s8) / sizeof(table_s8[0]));
  bytes(f, "table_s1", table_s1, sizeof(table_s1));
  bytes(f, "table_s3", table_s3, sizeof(table_s3));
  bytes(f, "table_s4", table_s4, sizeof(table_s4));
  bytes(f, "table_s2", table_s2, sizeof(table_s2));
  words_u(f, "table_s9", table_s9, sizeof(table_s9) / sizeof(table_s9[0]));
  bytes(f, "table_s10", table_s10, sizeof(table_s10));
  fclose(f);
  printf("message_key %zu message_iv %zu z %zu x %zu t %zu s5 %zu s6 %zu s7 %zu s8 %zu s1 %zu s3 %zu s4 %zu s2 %zu s9 %zu s10 %zu\n",
    sizeof(message_key), sizeof(message_iv), sizeof(z_key), sizeof(x_key), sizeof(t_key),
    sizeof(table_s5)/sizeof(table_s5[0]), sizeof(table_s6)/sizeof(table_s6[0]), sizeof(table_s7)/sizeof(table_s7[0]), sizeof(table_s8)/sizeof(table_s8[0]),
    sizeof(table_s1), sizeof(table_s3), sizeof(table_s4), sizeof(table_s2), sizeof(table_s9)/sizeof(table_s9[0]), sizeof(table_s10));
  return 0;
}
