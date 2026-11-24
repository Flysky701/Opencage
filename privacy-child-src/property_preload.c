// Process-local reads only. This never writes Android's shared property area.
#include <dlfcn.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/system_properties.h>
#include <unistd.h>
#include "policy_keys.h"

#if !defined(__aarch64__)
#error Unsupported child policy architecture
#endif

#define EXPORT __attribute__((visibility("default")))
typedef void (*ReadCallback)(void*, const char*, const char*, uint32_t);
static int (*real_get)(const char*, char*);
static int (*real_read)(const prop_info*, char*, char*);
static void (*real_callback)(const prop_info*, ReadCallback, void*);
static bool ready;
static bool sim_enabled;
static bool zone_enabled;
static char active_zone[64] = "Asia/Tokyo";
static unsigned main_machine;

static bool env_is(const char* key, const char* value) {
    const char* found = getenv(key);
    return found != NULL && strcmp(found, value) == 0;
}

static bool sdk_at_least(int min_sdk) {
    char found[PROP_VALUE_MAX] = {0};
    if (!real_get || real_get("ro.build.version.sdk", found) <= 0 || found[0] < '0' || found[0] > '9')
        return false;
    for (const char* digit = found + 1; *digit; ++digit)
        if (*digit < '0' || *digit > '9') return false;
    char* end;
    int saved = errno;
    errno = 0;
    long sdk = strtol(found, &end, 10);
    bool valid = errno == 0 && end != found && *end == '\0' && sdk >= min_sdk;
    errno = saved;
    return valid;
}

static bool native_bridge_disabled(void) {
    char bridge[PROP_VALUE_MAX] = {0};
    int length = real_get != NULL ? real_get("ro.dalvik.vm.native.bridge", bridge) : -1;
    return length == 0 || (length == 1 && bridge[0] == '0');
}

static const char* replacement(const char* name) {
    if (!ready) return NULL;
    if (sim_enabled) {
        for (size_t i = 0; i < sizeof(kSimProperties) / sizeof(kSimProperties[0]); ++i) {
            if (strcmp(name, kSimProperties[i].name) == 0) return kSimProperties[i].value;
        }
    }
    if (zone_enabled && strcmp(name, "persist.sys.timezone") == 0) return active_zone;
    return NULL;
}

__attribute__((constructor)) static void initialize(void) {
    real_get = (int (*)(const char*, char*))dlsym(RTLD_NEXT, "__system_property_get");
    real_read = (int (*)(const prop_info*, char*, char*))dlsym(RTLD_NEXT, "__system_property_read");
    real_callback = (void (*)(const prop_info*, ReadCallback, void*))dlsym(RTLD_NEXT, "__system_property_read_callback");
    Elf64_Ehdr header = {0};
    int fd = open("/proc/self/exe", O_RDONLY | O_CLOEXEC);
    ssize_t count = fd >= 0 ? read(fd, &header, sizeof(header)) : -1;
    if (fd >= 0) close(fd);
    bool elf64 = count == sizeof(header) && memcmp(header.e_ident, ELFMAG, SELFMAG) == 0 &&
        header.e_ident[EI_CLASS] == ELFCLASS64 && header.e_ident[EI_DATA] == ELFDATA2LSB;
    if (elf64) main_machine = header.e_machine;
    const char* scope = getenv("CAGE_CHILD_SCOPE");
    bool selected = scope != NULL && (
        strcmp(scope, "com.anthropic.claude") == 0 ||
        strcmp(scope, "io.github.flysky701.opencage.preflightprobe") == 0 ||
        strcmp(scope, "io.github.flysky701.opencage.probe") == 0);
    bool symbols = real_get != NULL && real_read != NULL && real_callback != NULL;
    bool profile = symbols && elf64 && header.e_machine == EM_AARCH64 &&
        (env_is("CAGE_CHILD_PROFILE", "android-arm64") || env_is("CAGE_CHILD_PROFILE", "android16-arm64")) &&
        sdk_at_least(34) && native_bridge_disabled();
    sim_enabled = env_is("CAGE_CHILD_SIM", "1");
    const char* zenv = getenv("CAGE_CHILD_ZONE");
    bool zvalid = false;
    if (zenv != NULL && zenv[0] != '\0') {
        size_t zlen = strlen(zenv);
        zvalid = zlen < sizeof(active_zone);
        for (size_t i = 0; zvalid && i < zlen; ++i) {
            char c = zenv[i];
            zvalid = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                || c == '/' || c == '_' || c == '-' || c == '+';
        }
        if (zvalid) memcpy(active_zone, zenv, zlen + 1);
    }
    zone_enabled = env_is("CAGE_CHILD_TIMEZONE", "1") && zvalid;
    ready = selected && profile && env_is("CAGE_CHILD_POLICY", "1");
    // Opt-in diagnostic marker: no paths, property values, arguments or IDs.
    if (env_is("CAGE_CHILD_DIAGNOSTIC", "1")) {
        dprintf(STDERR_FILENO, "CAGE_CHILD loaded=1 mainElf=%u enabled=%u sim=%u timezone=%u\n",
            main_machine, ready ? 1u : 0u, ready && sim_enabled ? 1u : 0u, ready && zone_enabled ? 1u : 0u);
    }
}

EXPORT unsigned cage_child_policy_state(void) {
    return 1u | (ready ? 2u : 0u) | (ready && sim_enabled ? 4u : 0u) | (ready && zone_enabled ? 8u : 0u);
}

EXPORT int __system_property_get(const char* name, char* value) {
    const char* changed = replacement(name);
    if (changed != NULL) {
        size_t length = strlen(changed);
        memcpy(value, changed, length + 1);
        return (int)length;
    }
    if (real_get != NULL) return real_get(name, value);
    value[0] = '\0';
    return 0;
}

struct ReadState { const char* changed; };
static void find_replacement(void* cookie, const char* name, const char* value, uint32_t serial) {
    (void)value;
    (void)serial;
    ((struct ReadState*)cookie)->changed = replacement(name);
}

EXPORT int __system_property_read(const prop_info* info, char* name, char* value) {
    if (real_read == NULL) { value[0] = '\0'; return 0; }
    int length = real_read(info, name, value);
    if (!ready || real_callback == NULL) return length;
    // The modern callback gives the full name; deprecated name buffers need
    // not be expanded or inspected, and the original name copy stays intact.
    struct ReadState state = {NULL};
    real_callback(info, find_replacement, &state);
    if (state.changed == NULL) return length;
    size_t updated = strlen(state.changed);
    memcpy(value, state.changed, updated + 1);
    return (int)updated;
}

struct CallbackState { ReadCallback callback; void* cookie; };
static void deliver_replacement(void* opaque, const char* name, const char* value, uint32_t serial) {
    struct CallbackState* state = (struct CallbackState*)opaque;
    const char* changed = replacement(name);
    // Keep the original opaque change token, also returned by the unmodified
    // serial/wait APIs. Consumers receive a NUL-terminated policy value; only
    // get/read's explicit length return is replaced.
    state->callback(state->cookie, name, changed != NULL ? changed : value, serial);
}

EXPORT void __system_property_read_callback(const prop_info* info, ReadCallback callback, void* cookie) {
    if (real_callback == NULL) return;
    if (!ready) { real_callback(info, callback, cookie); return; }
    struct CallbackState state = {callback, cookie};
    real_callback(info, deliver_replacement, &state);
}

