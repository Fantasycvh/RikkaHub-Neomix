// PDF→SVG JNI 桥：链接 app 内置 libmupdf_java.so 的导出 C API，
// 把 tectonic 编译的 PDF 转成 SVG（文字保留为 <text> 元素，FZ_SVG_TEXT_AS_TEXT）。
//
// 背景：dvisvgm 链路的文字轮廓依赖随包 TeX 字体资产（长期缺失），
// mupdf 的 SVG writer 从 PDF 内嵌字体（tectonic 输出必内嵌）+ ToUnicode CMap
// 直接产出 <text>，文字交给前端字体栈渲染——彻底绕开字体资产问题。
// mupdf 1.26.8 的 Java 桥未暴露 SVG device（官方 Android SDK 不做导出），
// 但底层 C API 完整导出（已在 libmupdf_java.so 的 dynsym 验证），此处自写薄桥。
//
// 仅 arm64-v8a（与 libtex_poc.so 一致）。
// 注：fz_new_context 是宏 → fz_new_context_imp(alloc, locks, max_store, FZ_VERSION)。

#include <jni.h>
#include <string.h>
#include <stdio.h>

#include <android/log.h>
#define LOGTAG "Pdf2SvgBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOGTAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOGTAG, __VA_ARGS__)

// ---- mupdf 结构前向声明（只使用不透明指针，不访问字段）----
typedef struct fz_context fz_context;
typedef struct fz_document fz_document;
typedef struct fz_page fz_page;
typedef struct fz_device fz_device;
typedef struct fz_buffer fz_buffer;
typedef struct fz_output fz_output;
typedef struct fz_alloc_context fz_alloc_context;
typedef struct fz_locks_context fz_locks_context;
typedef struct fz_matrix { float a, b, c, d, e, f; } fz_matrix;
typedef struct fz_rect { float x0, y0, x1, y1; } fz_rect;

// ---- 从 libmupdf_java.so 解析的导出符号（已验证存在）----
extern fz_context *fz_new_context_imp(const fz_alloc_context *alloc, const fz_locks_context *locks, unsigned long long max_store, const char *version);
extern void fz_register_document_handlers(fz_context *ctx);
extern fz_document *fz_open_document_with_buffer(fz_context *ctx, const char *magic, fz_buffer *buffer);
extern int fz_count_pages(fz_context *ctx, fz_document *doc);
extern fz_page *fz_load_page(fz_context *ctx, fz_document *doc, int number);
extern fz_rect fz_bound_page(fz_context *ctx, fz_page *page);
extern int fz_run_page(fz_context *ctx, fz_page *page, fz_device *dev, fz_matrix transform, void *cookie);
extern void fz_close_device(fz_context *ctx, fz_device *dev);
extern void fz_drop_device(fz_context *ctx, fz_device *dev);
extern fz_buffer *fz_new_buffer(fz_context *ctx, size_t capacity);
extern fz_output *fz_new_output_with_buffer(fz_context *ctx, fz_buffer *buf);
extern size_t fz_buffer_storage(fz_context *ctx, fz_buffer *buf, unsigned char **data);
extern void fz_drop_output(fz_context *ctx, fz_output *out);
extern void fz_drop_buffer(fz_context *ctx, fz_buffer *buf);
extern void fz_drop_page(fz_context *ctx, fz_page *page);
extern void fz_drop_document(fz_context *ctx, fz_document *doc);
extern void fz_drop_context(fz_context *ctx);
extern fz_device *fz_new_svg_device_with_id(fz_context *ctx, fz_output *out, float page_width, float page_height, int text_format, int reuse_images, int *id);
extern fz_buffer *fz_new_buffer_from_copied_data(fz_context *ctx, const unsigned char *data, size_t size);
extern const fz_matrix fz_identity;

#define FZ_SVG_TEXT_AS_TEXT 1
#define FZ_STORE_UNLIMITED ((size_t)-1)

// ---- 全局锁：mupdf context 非线程安全，串行化桥调用（隔离进程内本来就单线程）----
static __thread fz_context *g_ctx = NULL;

static fz_context *ensure_context(void) {
    if (!g_ctx) {
        g_ctx = fz_new_context_imp(NULL, NULL, FZ_STORE_UNLIMITED, "1.26.8");
        if (g_ctx) fz_register_document_handlers(g_ctx);
    }
    return g_ctx;
}

JNIEXPORT jstring JNICALL
Java_app_androidrik_rikkahub_tikz_MupdfSvgBridge_nativePdfToSvg(JNIEnv *env, jclass clazz, jbyteArray pdfBytes) {
    jstring result = NULL;
    fz_context *ctx = ensure_context();
    if (!ctx) {
        LOGE("fz_new_context_imp failed");
        return NULL;
    }

    jsize n = (*env)->GetArrayLength(env, pdfBytes);
    jbyte *raw = (*env)->GetByteArrayElements(env, pdfBytes, NULL);
    if (!raw) return NULL;

    fz_document *doc = NULL;
    fz_page *page = NULL;
    fz_device *dev = NULL;
    fz_buffer *inbuf = NULL;
    fz_buffer *outbuf = NULL;
    fz_output *out = NULL;
    unsigned char *svg_data = NULL;
    size_t svg_len = 0;

    inbuf = fz_new_buffer_from_copied_data(ctx, (const unsigned char *)raw, (size_t)n);
    (*env)->ReleaseByteArrayElements(env, pdfBytes, raw, JNI_ABORT);
    if (!inbuf) { LOGE("fz_new_buffer failed"); goto cleanup; }

    doc = fz_open_document_with_buffer(ctx, "application/pdf", inbuf);
    if (!doc) { LOGE("fz_open_document failed"); goto cleanup; }

    int pages = fz_count_pages(ctx, doc);
    if (pages <= 0) { LOGE("no pages"); goto cleanup; }
    page = fz_load_page(ctx, doc, 0);

    fz_rect bbox = fz_bound_page(ctx, page);

    outbuf = fz_new_buffer(ctx, 4096);
    out = fz_new_output_with_buffer(ctx, outbuf);
    dev = fz_new_svg_device_with_id(ctx, out, bbox.x1, bbox.y1, FZ_SVG_TEXT_AS_TEXT, 0, NULL);
    if (!dev) { LOGE("fz_new_svg_device failed"); goto cleanup; }

    fz_run_page(ctx, page, dev, fz_identity, NULL);
    fz_close_device(ctx, dev);

    svg_len = fz_buffer_storage(ctx, outbuf, &svg_data);

    result = (*env)->NewStringUTF(env, (const char *)svg_data);
    LOGI("pdf->svg ok: %zu bytes, %d pages, bbox=(%f,%f)", svg_len, pages, bbox.x1, bbox.y1);

cleanup:
    if (dev) fz_drop_device(ctx, dev);
    if (out) fz_drop_output(ctx, out);
    if (outbuf) fz_drop_buffer(ctx, outbuf);
    if (inbuf) fz_drop_buffer(ctx, inbuf);
    if (page) fz_drop_page(ctx, page);
    if (doc) fz_drop_document(ctx, doc);
    return result;
}

JNIEXPORT jstring JNICALL
Java_app_androidrik_rikkahub_tikz_MupdfSvgBridge_nativeLastError(JNIEnv *env, jclass clazz) {
    // mupdf 错误在 stderr/fz_log；此处不给额外槽位，返回 NULL 表示无自定义错误。
    return NULL;
}
