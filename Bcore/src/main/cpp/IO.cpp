#include "IO.h"
#include "Log.h"
#include <cstdlib>
#include <cstring>
#include <new>
#include <stdint.h>

jmethodID getAbsolutePathMethodId;

list<IO::RelocateInfo> relocate_rule;

char *replace(const char *str, const char *src, const char *dst) {
    if (!str || !src || !*src || !dst) return nullptr;
    size_t src_len = strlen(src);
    size_t dst_len = strlen(dst);
    const char *pos = str;
    size_t count = 0;
    while ((pos = strstr(pos, src))) {
        count++;
        pos += src_len;
    }

    size_t unmatched = strlen(str) - src_len * count;
    if (count && dst_len > (SIZE_MAX - unmatched - 1) / count) return nullptr;
    size_t result_len = unmatched + dst_len * count + 1;
    auto *result = static_cast<char *>(calloc(result_len, 1));
    if (!result) return nullptr;

    const char *left = str;
    const char *right;
    char *out = result;
    while ((right = strstr(left, src))) {
        size_t prefix_len = right - left;
        memcpy(out, left, prefix_len);
        out += prefix_len;
        memcpy(out, dst, dst_len);
        out += dst_len;
        left = right + src_len;
    }
    strcpy(out, left);
    return result;
}

const char *IO::redirectPath(const char *__path) {
    if (!__path) return nullptr;

    if (strstr(__path, "resource-cache")) {
        ALOGD("Blocking resource-cache path: %s", __path);
        return "/dev/null";
    }

    if (strstr(__path, "@idmap")) {
        ALOGD("Blocking idmap path: %s", __path);
        return "/dev/null";
    }

    if (strstr(__path, "systemui") && (strstr(__path, ".frro") || strstr(__path, "-accent-") || strstr(__path, "-dynamic-") || strstr(__path, "-neutral-"))) {
        ALOGD("Blocking systemui problematic path: %s", __path);
        return "/dev/null";
    }

    if (strstr(__path, "data@resource-cache@")) {
        ALOGD("Blocking data@resource-cache@ pattern: %s", __path);
        return "/dev/null";
    }

    if (strstr(__path, ".frro")) {
        ALOGD("Blocking .frro file: %s", __path);
        return "/dev/null";
    }

    if (strstr(__path, "systemui")) {
        ALOGD("Blocking systemui path: %s", __path);
        return "/dev/null";
    }

    list<IO::RelocateInfo>::iterator iterator;
    for (iterator = relocate_rule.begin(); iterator != relocate_rule.end(); ++iterator) {
        IO::RelocateInfo info = *iterator;
        if (strstr(__path, info.targetPath) && !strstr(__path, "/blackbox/")) {
            char *ret = replace(__path, info.targetPath, info.relocatePath);
            return ret ? ret : __path;
        }
    }
    return __path;
}

jstring IO::redirectPath(JNIEnv *env, jstring path) {
    return BoxCore::redirectPathString(env, path);
}

jobject IO::redirectPath(JNIEnv *env, jobject path) {
    return BoxCore::redirectPathFile(env, path);
}

void IO::addRule(const char *targetPath, const char *relocatePath) {
    if (!targetPath || !*targetPath || !relocatePath) return;
    char *target = strdup(targetPath);
    char *relocate = strdup(relocatePath);
    if (target && relocate) {
        try {
            relocate_rule.push_back({target, relocate});
            return;
        } catch (const std::bad_alloc &) {
        }
    }
    free(target);
    free(relocate);
}

void IO::init(JNIEnv *env) {
    if (!env) return;
    jclass tmpFile = env->FindClass("java/io/File");
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        if (tmpFile) env->DeleteLocalRef(tmpFile);
        return;
    }
    if (!tmpFile) return;
    jmethodID method = env->GetMethodID(tmpFile, "getAbsolutePath", "()Ljava/lang/String;");
    env->DeleteLocalRef(tmpFile);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return;
    }
    if (method) getAbsolutePathMethodId = method;
}
