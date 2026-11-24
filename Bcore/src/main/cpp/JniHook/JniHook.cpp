#include <jni.h>
#include "JniHook.h"
#include "Log.h"
#include "ArtMethod.h"

static struct {
    int api_level;
    size_t art_field_size;
    size_t art_field_flags_offset;

    size_t art_method_size;
    size_t art_method_flags_offset;
    size_t art_method_native_offset;

    jclass method_utils_class;
    jmethodID get_method_desc_id;
    jmethodID get_method_declaring_class_id;
    jmethodID get_method_name_id;

} HookEnv;

static bool g_initialized = false;

inline static uint32_t GetAccessFlags(const char *art_method) {
    return *reinterpret_cast<const uint32_t *>(art_method + HookEnv.art_method_flags_offset);
}

inline static bool SetAccessFlags(char *art_method, uint32_t flags) {
    *reinterpret_cast<uint32_t *>(art_method + HookEnv.art_method_flags_offset) = flags;
    return true;
}

inline static bool AddAccessFlag(char *art_method, uint32_t flag) {
    uint32_t old_flag = GetAccessFlags(art_method);
    uint32_t new_flag = old_flag | flag;
    return new_flag != old_flag && SetAccessFlags(art_method, new_flag);
}

inline static bool ClearAccessFlag(char *art_method, uint32_t flag) {
    uint32_t old_flag = GetAccessFlags(art_method);
    uint32_t new_flag = old_flag & ~flag;
    return new_flag != old_flag && SetAccessFlags(art_method, new_flag);
}

static uint32_t GetFieldAccessFlags(const char *art_field) {
    return *reinterpret_cast<const uint32_t *>(art_field + HookEnv.art_field_flags_offset);
}

static void SetFieldAccessFlags(char *art_field, uint32_t flags) {
    *reinterpret_cast<uint32_t *>(art_field + HookEnv.art_field_flags_offset) = flags;
}

static void AddFieldAccessFlag(char *art_field, uint32_t flag) {
    SetFieldAccessFlags(art_field, GetFieldAccessFlags(art_field) | flag);
}

static void ClearFieldAccessFlag(char *art_field, uint32_t flag) {
    SetFieldAccessFlags(art_field, GetFieldAccessFlags(art_field) & ~flag);
}

inline static bool HasAccessFlag(char *art_method, uint32_t flag) {
    uint32_t flags = GetAccessFlags(art_method);
    ALOGD("AccessFlag:flags = 0x%x,flag = 0x%x",flags,flag);
    return (flags & flag) == flag;
}


inline static bool ClearFastNativeFlag(char *art_method) {
    return HookEnv.api_level < __ANDROID_API_P__ && ClearAccessFlag(art_method, kAccFastNative);
}

static void *GetReflectedArtMethod(JNIEnv *env, jobject method) {
    if (!method) return nullptr;
    jclass executable = env->FindClass("java/lang/reflect/Executable");
    if (!executable) {
        env->ExceptionClear();
        return nullptr;
    }
    jfieldID artId = env->GetFieldID(executable, "artMethod", "J");
    env->DeleteLocalRef(executable);
    if (!artId) {
        env->ExceptionClear();
        return nullptr;
    }
    jlong artMethod = env->GetLongField(method, artId);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return nullptr;
    }
    return reinterpret_cast<void *>(artMethod);
}

static void *GetArtMethod(JNIEnv *env, jclass clazz, jmethodID methodId, bool isStatic) {
    if (!clazz || !methodId) return nullptr;
    if (HookEnv.api_level < __ANDROID_API_Q__) return methodId;
    jobject method = env->ToReflectedMethod(clazz, methodId, isStatic ? JNI_TRUE : JNI_FALSE);
    if (!method || env->ExceptionCheck()) {
        if (method) env->DeleteLocalRef(method);
        env->ExceptionClear();
        return nullptr;
    }
    void *artMethod = GetReflectedArtMethod(env, method);
    env->DeleteLocalRef(method);
    return artMethod;
}

static void *GetFieldMethod(JNIEnv *env, jobject field) {
    if (!field) return nullptr;
    if (HookEnv.api_level >= __ANDROID_API_Q__) {
        jclass fieldClass = env->FindClass("java/lang/reflect/Field");
        if (!fieldClass) {
            env->ExceptionClear();
            return nullptr;
        }
        jmethodID getArtField = env->GetMethodID(fieldClass, "getArtField", "()J");
        env->DeleteLocalRef(fieldClass);
        if (!getArtField) {
            env->ExceptionClear();
            return nullptr;
        }
        jlong artField = env->CallLongMethod(field, getArtField);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return nullptr;
        }
        return reinterpret_cast<void *>(artField);
    } else {
        return env->FromReflectedField(field);
    }
}

bool CheckFlags(void *artMethod) {
    char *method = static_cast<char *>(artMethod);
    try {
        if (!HasAccessFlag(method, kAccNative)) {
            ALOGD("Method is not native, skipping hook");
            return false;
        }
        ClearFastNativeFlag(method);
        return true;
    } catch (...) {
        ALOGD("Error checking method flags, assuming not native");
        return false;
    }
}

void JniHook::HookJniFun(JNIEnv *env, jobject java_method, void *new_fun,
                         void **orig_fun, bool is_static) {
    if (!java_method || !HookEnv.method_utils_class || !HookEnv.get_method_declaring_class_id ||
        !HookEnv.get_method_name_id || !HookEnv.get_method_desc_id) return;
    const jmethodID getters[] = {HookEnv.get_method_declaring_class_id, HookEnv.get_method_name_id,
                                 HookEnv.get_method_desc_id};
    jstring strings[3]{};
    const char *chars[3]{};
    for (size_t i = 0; i < 3; ++i) {
        strings[i] = static_cast<jstring>(
                env->CallStaticObjectMethod(HookEnv.method_utils_class, getters[i], java_method));
        if (env->ExceptionCheck() || !strings[i]) break;
        chars[i] = env->GetStringUTFChars(strings[i], nullptr);
        if (!chars[i]) break;
    }
    if (chars[2]) HookJniFun(env, chars[0], chars[1], chars[2], new_fun, orig_fun, is_static);
    for (size_t i = 0; i < 3; ++i) {
        if (chars[i]) env->ReleaseStringUTFChars(strings[i], chars[i]);
        if (strings[i]) env->DeleteLocalRef(strings[i]);
    }
}

void
JniHook::HookJniFun(JNIEnv *env, const char *class_name, const char *method_name, const char *sign,
                    void *new_fun, void **orig_fun, bool is_static) {
    if (HookEnv.art_method_native_offset == 0 || !new_fun || !orig_fun) {
        return;
    }
    jclass clazz = env->FindClass(class_name);
    if (!clazz) {
        ALOGD("findClass fail: %s %s", class_name, method_name);
        env->ExceptionClear();
        return;
    }
    jmethodID method = nullptr;
    if (is_static) {
        method = env->GetStaticMethodID(clazz, method_name, sign);
    } else {
        method = env->GetMethodID(clazz, method_name, sign);
    }
    if (!method) {
        env->ExceptionClear();
        env->DeleteLocalRef(clazz);
        ALOGD("get method id fail: %s %s", class_name, method_name);
        return;
    }
    JNINativeMethod gMethods[] = {
            {method_name, sign, (void *) new_fun},
    };

    auto artMethod = static_cast<uintptr_t *>(GetArtMethod(env, clazz, method, is_static));
    if (!artMethod || !CheckFlags(artMethod)) {
        env->DeleteLocalRef(clazz);
        ALOGD("Skipping hook for non-native method: %s.%s", class_name, method_name);
        return;
    }
    void *current = reinterpret_cast<void *>(artMethod[HookEnv.art_method_native_offset]);
    if (!current || current == new_fun) {
        env->DeleteLocalRef(clazz);
        return;
    }
    bool wrote_orig = (*orig_fun == nullptr);
    if (wrote_orig) {
        *orig_fun = current;
    }
    jint result = env->RegisterNatives(clazz, gMethods, 1);
    env->DeleteLocalRef(clazz);
    if (result < 0) {
        env->ExceptionClear();
        if (wrote_orig) {
            *orig_fun = nullptr;
        }
        ALOGE("jni hook error. class：%s, method：%s", class_name, method_name);
        return;
    }

    if (HookEnv.api_level == __ANDROID_API_O__ || HookEnv.api_level == __ANDROID_API_O_MR1__) {
        AddAccessFlag((char *) artMethod, kAccFastNative);
    }
    ALOGD("register class：%s, method：%s success!", class_name, method_name);
}

__attribute__((section (".mytext")))  JNICALL void native_offset
        (JNIEnv *, jclass) {
}

__attribute__((section (".mytext")))  JNICALL void native_offset2
        (JNIEnv *, jclass) {
}

__attribute__((section (".mytext")))  JNICALL void set_method_accessible
        (JNIEnv *env, jclass, jclass, jobject method) {
    if (!HookEnv.art_method_flags_offset) return;
    auto *art_method = static_cast<char *>(GetReflectedArtMethod(env, method));
    if (!art_method) return;
    AddAccessFlag(art_method, kAccPublic);
    if (HookEnv.api_level >= __ANDROID_API_Q__) {
        AddAccessFlag(art_method, kAccPublicApi);
    }
}

__attribute__((section (".mytext")))  JNICALL void set_field_accessible
        (JNIEnv *env, jclass, jclass, jobject field) {
    if (!HookEnv.art_field_flags_offset) return;
    auto *artField = static_cast<char *>(GetFieldMethod(env, field));
    if (!artField) return;
    AddFieldAccessFlag(artField, kAccPublic);
    if (HookEnv.api_level >= __ANDROID_API_Q__) {
        AddFieldAccessFlag(artField, kAccPublicApi);
    }
    ClearFieldAccessFlag(artField, kAccFinal);
}

static bool registerNative(JNIEnv *env) {
    jclass clazz = env->FindClass("top/niunaijun/jnihook/jni/JniHook");
    if (!clazz) return false;
    JNINativeMethod gMethods[] = {
            {"nativeOffset",  "()V",                                            (void *) native_offset},
            {"nativeOffset2", "()V",                                            (void *) native_offset2},
            {"setAccessible", "(Ljava/lang/Class;Ljava/lang/reflect/Method;)V", (void *) set_method_accessible},
            {"setAccessible", "(Ljava/lang/Class;Ljava/lang/reflect/Field;)V",  (void *) set_field_accessible},
    };
    jint result = env->RegisterNatives(clazz, gMethods, sizeof(gMethods) / sizeof(gMethods[0]));
    env->DeleteLocalRef(clazz);
    if (result != JNI_OK) {
        ALOGE("jni register error.");
    }
    return result == JNI_OK;
}

static void *GetStaticArtField(JNIEnv *env, jclass clazz, const char *name) {
    jfieldID fieldId = env->GetStaticFieldID(clazz, name, "I");
    if (!fieldId) {
        env->ExceptionClear();
        return nullptr;
    }
    jobject field = env->ToReflectedField(clazz, fieldId, JNI_TRUE);
    if (!field || env->ExceptionCheck()) {
        if (field) env->DeleteLocalRef(field);
        env->ExceptionClear();
        return nullptr;
    }
    void *artField = GetFieldMethod(env, field);
    env->DeleteLocalRef(field);
    return artField;
}

static size_t GetArtMemberSize(const void *first, const void *second) {
    if (!first || !second) return 0;
    auto a = reinterpret_cast<uintptr_t>(first);
    auto b = reinterpret_cast<uintptr_t>(second);
    size_t size = (b >= a) ? (b - a) : (a - b);
    return size > 0 && size <= 256 ? size : 0;
}

void JniHook::InitJniHook(JNIEnv *env, int api_level) {
    if (g_initialized) return;
    HookEnv.api_level = api_level;
    HookEnv.art_method_native_offset = 0;
    HookEnv.art_method_flags_offset = 0;
    HookEnv.art_field_flags_offset = 0;
    if (!registerNative(env)) return;

    jclass clazz = env->FindClass("top/niunaijun/jnihook/jni/JniHook");
    if (!clazz) return;
    jmethodID nativeOffsetId = env->GetStaticMethodID(clazz, "nativeOffset", "()V");
    jmethodID nativeOffset2Id = nativeOffsetId ? env->GetStaticMethodID(clazz, "nativeOffset2", "()V") : nullptr;
    if (!nativeOffset2Id) {
        env->ExceptionClear();
        env->DeleteLocalRef(clazz);
        return;
    }

    void *nativeOffset = GetArtMethod(env, clazz, nativeOffsetId, true);
    void *nativeOffset2 = GetArtMethod(env, clazz, nativeOffset2Id, true);
    void *nativeOffsetField = GetStaticArtField(env, clazz, "NATIVE_OFFSET");
    void *nativeOffsetField2 = GetStaticArtField(env, clazz, "NATIVE_OFFSET_2");
    env->DeleteLocalRef(clazz);
    HookEnv.art_method_size = GetArtMemberSize(nativeOffset, nativeOffset2);
    HookEnv.art_field_size = GetArtMemberSize(nativeOffsetField, nativeOffsetField2);
    if (!HookEnv.art_method_size) {
        ALOGE("init jni hook error. invalid art_method_size");
        return;
    }

    size_t native_entry_index = 0;
    auto artMethod = static_cast<const uintptr_t *>(nativeOffset);
    for (size_t i = 1; i < HookEnv.art_method_size / sizeof(uintptr_t); ++i) {
        if (reinterpret_cast<void *>(artMethod[i]) == native_offset) {
            native_entry_index = i;
            break;
        }
    }
    if (!native_entry_index) {
        ALOGE("init jni hook error. art_method_native_offset not found!");
        return;
    }

    uint32_t flags = 0x0;
    flags = flags | kAccPublic;
    flags = flags | kAccStatic;
    flags = flags | kAccNative;
    flags = flags | kAccFinal;
    if (api_level >= __ANDROID_API_Q__) {
        flags = flags | kAccPublicApi;
    }
    if (api_level >= __ANDROID_API_S__) {
        flags = flags | kAccNterpInvokeFastPathFlag;
    }
    // Android 15 (API 35): kAccMustCountLocks added — not set on native methods
    // Android 16 (API 36): kAccReservedRefFields added — not set on native methods
    // These flags are NOT included in the pattern because our calibration method
    // (nativeOffset/nativeOffset2) doesn't use monitor-enter, so ART won't set them.

    auto method_flags = static_cast<const uint32_t *>(nativeOffset);
    for (size_t i = 1; i < HookEnv.art_method_size / sizeof(uint32_t); ++i) {
        if (method_flags[i] == flags) {
            HookEnv.art_method_flags_offset = i * sizeof(uint32_t);
            break;
        }
    }
    // Fallback for API 35/36: mask out upper bits that may vary
    if (!HookEnv.art_method_flags_offset) {
        ALOGE("art_method_flags_offset: exact match failed, trying masked search (API %d)", api_level);
        uint32_t mask = 0x0000FFFF | kAccPublicApi;  // stable bits
        uint32_t maskedFlags = flags & mask;
        for (size_t i = 1; i < HookEnv.art_method_size / sizeof(uint32_t); ++i) {
            if ((method_flags[i] & mask) == maskedFlags) {
                HookEnv.art_method_flags_offset = i * sizeof(uint32_t);
                ALOGD("art_method_flags_offset found via masked search at offset %zu",
                      HookEnv.art_method_flags_offset);
                break;
            }
        }
    }
    if (!HookEnv.art_method_flags_offset) {
        ALOGE("init jni hook error. art_method_flags_offset not found! API=%d", api_level);
        return;
    }
    HookEnv.art_method_native_offset = native_entry_index;

    flags = 0x0;
    flags = flags | kAccPublic;
    flags = flags | kAccStatic;
    flags = flags | kAccFinal;
    if (api_level >= __ANDROID_API_Q__) {
        flags = flags | kAccPublicApi;
    }
    auto field_flags = static_cast<const uint32_t *>(nativeOffsetField);
    for (size_t i = 1; i < HookEnv.art_field_size / sizeof(uint32_t); ++i) {
        if (field_flags[i] == flags) {
            HookEnv.art_field_flags_offset = i * sizeof(uint32_t);
            break;
        }
    }
    if (!HookEnv.art_field_flags_offset) {
        ALOGE("init jni hook error. art_field_flags_offset not found!");
    }

    if (!HookEnv.method_utils_class) {
        jclass method_utils = env->FindClass("top/niunaijun/jnihook/MethodUtils");
        if (!method_utils) return;
        const char *signature = "(Ljava/lang/reflect/Method;)Ljava/lang/String;";
        jmethodID desc = env->GetStaticMethodID(method_utils, "getDesc", signature);
        jmethodID declaring_class = desc ? env->GetStaticMethodID(method_utils, "getDeclaringClass", signature) : nullptr;
        jmethodID name = declaring_class ? env->GetStaticMethodID(method_utils, "getMethodName", signature) : nullptr;
        if (name) {
            HookEnv.get_method_desc_id = desc;
            HookEnv.get_method_declaring_class_id = declaring_class;
            HookEnv.get_method_name_id = name;
            HookEnv.method_utils_class = static_cast<jclass>(env->NewGlobalRef(method_utils));
        }
        env->DeleteLocalRef(method_utils);
    }
    g_initialized = true;
}
