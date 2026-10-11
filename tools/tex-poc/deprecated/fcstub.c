/* Android POC fontconfig stub.
 * Tectonic 的 FcBackend 在引擎启动时初始化 fontconfig 并枚举系统字体；
 * Android 上没有 fontconfig，字体经 IoStack 注入。此 stub 让初始化成功
 * 且字体列表为空（系统字体不可用），仅满足链接与启动路径。 */
#include <stdlib.h>

typedef struct { int nfont; int sfont; void **fonts; } FcFontSetStub;
typedef struct { int dummy; } FcPatStub;
typedef struct { int dummy; } FcConfigStub;

static FcConfigStub g_config;
static int g_inited = 0;

int FcInit(void) { g_inited = 1; return 1; }
void FcFini(void) { g_inited = 0; }
void *FcConfigGetCurrent(void) { return g_inited ? (void *)&g_config : NULL; }

void *FcNameParse(const unsigned char *s) { (void)s; return malloc(sizeof(FcPatStub)); }
void FcNameUnparse(void) { }
void FcPatternDestroy(void *p) { free(p); }
void *FcPatternReference(void *p) { return p; }
int FcPatternGetInteger(void *p, const char *o, int n, int *v) { (void)p;(void)o;(void)n;(void)v; return 0; }
int FcPatternGetString(void *p, const char *o, int n, unsigned char **v) { (void)p;(void)o;(void)n;(void)v; return 0; }

void *FcObjectSetCreate(void) { return malloc(sizeof(FcPatStub)); }
int FcObjectSetAdd(void *os, const char *s) { (void)os;(void)s; return 1; }
void FcObjectSetDestroy(void *os) { free(os); }

/* 永远返回同一个空字体表（nfont=0）；FcFontSetDestroy 为 no-op */
void *FcFontList(void *c, void *p, void *os) {
    (void)c; (void)p; (void)os;
    static FcFontSetStub empty = { 0, 0, NULL };
    return &empty;
}
void FcFontSetDestroy(void *fs) { (void)fs; }
