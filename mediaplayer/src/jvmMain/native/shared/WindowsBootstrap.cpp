#include <jni.h>
#include <windows.h>
#include <string>
extern "C" JNIEXPORT void JNICALL Java_io_github_kdroidfilter_composemediaplayer_desktop_RuntimeDllSearch_addDirectory(JNIEnv* env, jclass, jstring path) {
    const jchar* text = env->GetStringChars(path, nullptr);
    std::wstring directory(reinterpret_cast<const wchar_t*>(text), env->GetStringLength(path));
    env->ReleaseStringChars(path, text);
    if (!SetDefaultDllDirectories(LOAD_LIBRARY_SEARCH_DEFAULT_DIRS) || !AddDllDirectory(directory.c_str())) {
        env->ThrowNew(env->FindClass("java/lang/UnsatisfiedLinkError"), "Cannot configure the bundled DLL search directory.");
    }
}
