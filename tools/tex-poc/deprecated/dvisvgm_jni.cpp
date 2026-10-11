/* JNI entry for dvisvgm-as-library (Android).
 * SELinux execute_no_trans on MIUI-era kernels blocks exec() of binaries
 * from app data; loading dvisvgm as a shared library via System.loadLibrary()
 * avoids exec entirely.
 *
 * Kotlin side (TectonicNative):
 *   System.loadLibrary("dvisvgmexec")
 *   external fun dvisvgmMain(workDir: String, args: Array<String>): Int
 */
#include <jni.h>
#include <unistd.h>
#include <vector>
#include <string>

extern "C" int dvisvgm_main_impl(int argc, char *argv[]);

extern "C" JNIEXPORT jint JNICALL
Java_app_androidrik_rikkahub_tikz_TectonicNative_dvisvgmMain(
    JNIEnv *env, jclass, jstring workDir, jobjectArray args) {
    if (workDir) {
        const char *wd = env->GetStringUTFChars(workDir, nullptr);
        if (wd) {
            if (chdir(wd) != 0) return -100;
            env->ReleaseStringUTFChars(workDir, wd);
        }
    }
    jsize argc = args ? env->GetArrayLength(args) : 0;
    // 先收集完整 string（避免 vector 扩容使已存 c_str() 悬垂），再统一转指针
    std::vector<std::string> storage;
    storage.reserve(static_cast<size_t>(argc) + 1);
    storage.emplace_back("dvisvgm");
    for (jsize i = 0; i < argc; ++i) {
        auto jstr = (jstring)env->GetObjectArrayElement(args, i);
        if (!jstr) continue;
        const char *s = env->GetStringUTFChars(jstr, nullptr);
        storage.emplace_back(s ? s : "");
        env->ReleaseStringUTFChars(jstr, s);
    }
    std::vector<char *> argv;
    argv.reserve(storage.size() + 1);
    for (auto &s : storage) argv.push_back(const_cast<char *>(s.c_str()));
    argv.push_back(nullptr);
    return dvisvgm_main_impl(static_cast<int>(argv.size() - 1), argv.data());
}