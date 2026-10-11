/* Minimal kpathsea stub for dvisvgm on Android.
 * dvisvgm requires kpathsea symbols at link time; on Android fonts are
 * resolved through dvisvgm's own fontmap (-m) and the working directory,
 * so the stubbed lookups just report "not found". Compiled against the
 * real kpathsea headers (arch-independent). */
#include <kpathsea/kpathsea.h>

KPSEDLL kpathsea_instance kpse_def_inst;

void kpse_set_program_name(const char *argv0, const char *progname) {
    (void)argv0; (void)progname;
}

void kpse_set_program_enabled(kpse_file_format_type fmt, int value, kpse_src_type src) {
    (void)fmt; (void)value; (void)src;
}

string kpse_find_file(const_string name, kpse_file_format_type format, int must_exist) {
    (void)name; (void)format; (void)must_exist;
    return NULL;
}

string kpse_make_tex(kpse_file_format_type fmt, const char *name) {
    (void)fmt; (void)name;
    return NULL;
}

string kpse_var_value(const_string var) {
    (void)var;
    return NULL;
}
