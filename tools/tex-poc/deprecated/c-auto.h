/* Minimal c-auto.h replacement for compiling against kpathsea headers
 * without the texlive build tree (Android stub only). */
#ifndef KPATHSEA_C_AUTO_H_STUB
#define KPATHSEA_C_AUTO_H_STUB

#include <stddef.h>

#define HAVE_ASSERT_H 1
#define HAVE_DIRENT_H 1
#define HAVE_FLOAT_H 1
#define HAVE_INTTYPES_H 1
#define HAVE_LIMITS_H 1
#define HAVE_STDINT_H 1
#define HAVE_STDLIB_H 1
#define HAVE_STRING_H 1
#define HAVE_SYS_STAT_H 1
#define HAVE_SYS_TYPES_H 1
#define HAVE_UNISTD_H 1
#define STDC_HEADERS 1

/* 提供 memcpy/memmove/memset，防止 kpathsea c-memstr.h 将其宏替换为 bcopy */
#define HAVE_MEMCPY 1
#define HAVE_MEMMOVE 1
#define HAVE_MEMSET 1

/* dvisvgm FileFinder.cpp 使用 KPSEVERSION 宏 */
#define KPSEVERSION "kpathsea version 6.4.0 (Android stub)"

#endif
