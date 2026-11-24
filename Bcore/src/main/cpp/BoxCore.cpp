



#include "BoxCore.h"
#include "Log.h"
#include "IO.h"
#include <jni.h>
#include <pthread.h>
#include <JniHook/JniHook.h>
#include <Hook/VMClassLoaderHook.h>
#include <Hook/UnixFileSystemHook.h>
#include <Hook/FileSystemHook.h>
#include <Hook/BinderHook.h>
#include <Hook/DexFileHook.h>
#include <Hook/RuntimeHook.h>
#include "Utils/HexDump.h"
#include "hidden_api.h"

namespace {
struct JavaCallbacks {
    jclass NativeCoreClass;
    jmethodID getCallingUidId;
    jmethodID redirectPathString;
    jmethodID redirectPathFile;
    jmethodID loadEmptyDex;
};

struct {
    JavaVM *vm;
    JavaCallbacks callbacks;
    int api_level;
} VMEnv;

pthread_mutex_t g_init_mutex = PTHREAD_MUTEX_INITIALIZER;
pthread_mutex_t g_io_mutex = PTHREAD_MUTEX_INITIALIZER;
bool g_callbacks_ready;
bool g_io_enabled;

class NativeLock {
public:
    explicit NativeLock(pthread_mutex_t &mutex) : mutex(mutex) { pthread_mutex_lock(&mutex); }
    ~NativeLock() { pthread_mutex_unlock(&mutex); }

private:
    pthread_mutex_t &mutex;
};

bool JniFailed(JNIEnv *env, bool found) {
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return true;
    }
    return !found;
}

bool ResolveCallbacks(JNIEnv *env, jclass clazz, JavaCallbacks &callbacks) {
    callbacks.getCallingUidId = env->GetStaticMethodID(clazz, "getCallingUid", "(I)I");
    if (JniFailed(env, callbacks.getCallingUidId)) return false;
    callbacks.redirectPathString = env->GetStaticMethodID(clazz, "redirectPath",
                                                         "(Ljava/lang/String;)Ljava/lang/String;");
    if (JniFailed(env, callbacks.redirectPathString)) return false;
    callbacks.redirectPathFile = env->GetStaticMethodID(clazz, "redirectPath", "(Ljava/io/File;)Ljava/io/File;");
    if (JniFailed(env, callbacks.redirectPathFile)) return false;
    callbacks.loadEmptyDex = env->GetStaticMethodID(clazz, "loadEmptyDex", "()[J");
    return !JniFailed(env, callbacks.loadEmptyDex);
}
}  // namespace

JNIEnv *getEnv() {
    JNIEnv *env = nullptr;
    if (!VMEnv.vm || VMEnv.vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) return nullptr;
    return env;
}

JNIEnv *ensureEnvCreated() {
    if (!VMEnv.vm) return nullptr;
    JNIEnv *env = getEnv();
    if (!env && VMEnv.vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return nullptr;
    return env;
}

int BoxCore::getCallingUid(JNIEnv *env, int orig) {
    if (!env) env = ensureEnvCreated();
    if (!env || !__atomic_load_n(&g_callbacks_ready, __ATOMIC_ACQUIRE)) return orig;
    const JavaCallbacks &callbacks = VMEnv.callbacks;
    if (!callbacks.NativeCoreClass || !callbacks.getCallingUidId) return orig;
    return env->CallStaticIntMethod(callbacks.NativeCoreClass, callbacks.getCallingUidId, orig);
}

jstring BoxCore::redirectPathString(JNIEnv *env, jstring path) {
    if (!env) env = ensureEnvCreated();
    if (!env || !__atomic_load_n(&g_callbacks_ready, __ATOMIC_ACQUIRE)) return path;
    const JavaCallbacks &callbacks = VMEnv.callbacks;
    if (!callbacks.NativeCoreClass || !callbacks.redirectPathString) return path;
    return static_cast<jstring>(env->CallStaticObjectMethod(callbacks.NativeCoreClass, callbacks.redirectPathString, path));
}

jobject BoxCore::redirectPathFile(JNIEnv *env, jobject path) {
    if (!env) env = ensureEnvCreated();
    if (!env || !__atomic_load_n(&g_callbacks_ready, __ATOMIC_ACQUIRE)) return path;
    const JavaCallbacks &callbacks = VMEnv.callbacks;
    if (!callbacks.NativeCoreClass || !callbacks.redirectPathFile) return path;
    return env->CallStaticObjectMethod(callbacks.NativeCoreClass, callbacks.redirectPathFile, path);
}

jlongArray BoxCore::loadEmptyDex(JNIEnv *env) {
    if (!env) env = ensureEnvCreated();
    if (!env || !__atomic_load_n(&g_callbacks_ready, __ATOMIC_ACQUIRE)) return nullptr;
    const JavaCallbacks &callbacks = VMEnv.callbacks;
    if (!callbacks.NativeCoreClass || !callbacks.loadEmptyDex) return nullptr;
    return static_cast<jlongArray>(env->CallStaticObjectMethod(callbacks.NativeCoreClass, callbacks.loadEmptyDex));
}

int BoxCore::getApiLevel() {
    return __atomic_load_n(&VMEnv.api_level, __ATOMIC_ACQUIRE);
}

JavaVM *BoxCore::getJavaVM() {
    return VMEnv.vm;
}

void nativeHook(JNIEnv *env) {
    BaseHook::init(env);
    UnixFileSystemHook::init(env);
    FileSystemHook::init();
    VMClassLoaderHook::init(env);

    BinderHook::init(env);
    DexFileHook::init(env);
}

void hideXposed(JNIEnv *, jclass) {
    ALOGD("set hideXposed");
    VMClassLoaderHook::hideXposed();
}

void init(JNIEnv *env, jclass, jint api_level) {
    if (!env) return;
    NativeLock lock(g_init_mutex);
    ALOGD("NativeCore init.");
    if (__atomic_load_n(&g_callbacks_ready, __ATOMIC_ACQUIRE)) {
        JniHook::InitJniHook(env, BoxCore::getApiLevel());
        return;
    }

    jclass clazz = env->FindClass(VMCORE_CLASS);
    if (JniFailed(env, clazz)) {
        if (clazz) env->DeleteLocalRef(clazz);
        return;
    }
    JavaCallbacks callbacks{};
    if (!ResolveCallbacks(env, clazz, callbacks)) {
        env->DeleteLocalRef(clazz);
        return;
    }
    callbacks.NativeCoreClass = static_cast<jclass>(env->NewGlobalRef(clazz));
    env->DeleteLocalRef(clazz);
    if (JniFailed(env, callbacks.NativeCoreClass)) {
        if (callbacks.NativeCoreClass) env->DeleteGlobalRef(callbacks.NativeCoreClass);
        return;
    }
    JniHook::InitJniHook(env, api_level);
    VMEnv.callbacks = callbacks;
    __atomic_store_n(&VMEnv.api_level, api_level, __ATOMIC_RELEASE);
    __atomic_store_n(&g_callbacks_ready, true, __ATOMIC_RELEASE);
}

void addIORule(JNIEnv *env, jclass, jstring target_path,
               jstring relocate_path) {
    ALOGD("set addIORule");
    if (!target_path || !relocate_path) return;
    const char *target = env->GetStringUTFChars(target_path, nullptr);
    if (!target) return;
    const char *relocate = env->GetStringUTFChars(relocate_path, nullptr);
    if (relocate) {
        IO::addRule(target, relocate);
        env->ReleaseStringUTFChars(relocate_path, relocate);
    }
    env->ReleaseStringUTFChars(target_path, target);
}

void enableIO(JNIEnv *env, jclass) {
    if (!env || !__atomic_load_n(&g_callbacks_ready, __ATOMIC_ACQUIRE)) return;
    NativeLock lock(g_io_mutex);
    if (g_io_enabled) return;
    ALOGD("set enableIO");
    IO::init(env);
    nativeHook(env);
    g_io_enabled = true;
}

bool disableHiddenApi(JNIEnv *env, jclass) {
    ALOGD("set disableHiddenApi");
    if(!disable_hidden_api(env)){
        ALOGD("set disableHiddenApi Fail!!!");
        return false;
    }
    return true;
}

bool disableResourceLoading(JNIEnv *, jclass) {
    ALOGD("set disableResourceLoading");
    if(!disable_resource_loading()){
        ALOGD("set disableResourceLoading Fail!!!");
        return false;
    }
    return true;
}

static JNINativeMethod gMethods[] = {
        {"disableHiddenApi",    "()Z",                               (void *) disableHiddenApi},
        {"disableResourceLoading", "()Z",                            (void *) disableResourceLoading},
        {"hideXposed",          "()V",                               (void *) hideXposed},
        {"addIORule",           "(Ljava/lang/String;Ljava/lang/String;)V", (void *) addIORule},
        {"enableIO",            "()V",                               (void *) enableIO},
        {"init",                "(I)V",                              (void *) init},
};

int registerNativeMethods(JNIEnv *env, const char *className,
                          JNINativeMethod *gMethods, int numMethods) {
    jclass clazz;
    clazz = env->FindClass(className);
    if (clazz == nullptr) {
        return JNI_FALSE;
    }
    jint result = env->RegisterNatives(clazz, gMethods, numMethods);
    env->DeleteLocalRef(clazz);
    return result == JNI_OK ? JNI_TRUE : JNI_FALSE;
}

int registerNatives(JNIEnv *env) {
    if (!registerNativeMethods(env, VMCORE_CLASS, gMethods,
                               sizeof(gMethods) / sizeof(gMethods[0])))
        return JNI_FALSE;
    return JNI_TRUE;
}

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *) {
    JNIEnv *env;
    VMEnv.vm = vm;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_EVERSION;
    }
    if (!registerNatives(env)) return JNI_ERR;
    return JNI_VERSION_1_6;
}
