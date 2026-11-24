#include "bytehook_api.h"
#include <android/log.h>
#include <dlfcn.h>
#include <elf.h>
#include <errno.h>
#include <ifaddrs.h>
#include <jni.h>
#include <limits.h>
#include <link.h>
#include <linux/if_packet.h>
#include <net/if.h>
#include <netinet/in.h>
#include <pthread.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/system_properties.h>

namespace {
constexpr unsigned SIM = 1, TIMEZONE = 2, VPN = 4;
constexpr size_t MAX_ROOTS = 8, MAX_INTERFACES = 32;
BytehookApi api{};
pthread_mutex_t configuration = PTHREAD_MUTEX_INITIALIZER;
unsigned enabled;
char active_zone[PROP_VALUE_MAX] = "Asia/Tokyo";
bool installed;
bool installation_failed;
char roots[MAX_ROOTS][PATH_MAX]{};
size_t root_count;
char interfaces[MAX_INTERFACES][64]{};
size_t interface_count;
using GetIfaddrs = int (*)(ifaddrs**);
using FreeIfaddrs = void (*)(ifaddrs*);
using PropertyCallback = void (*)(void*, const char*, const char*, uint32_t);
using ReadPropertyCallback = void (*)(const prop_info*, PropertyCallback, void*);
GetIfaddrs real_getifaddrs;
FreeIfaddrs real_freeifaddrs;
ReadPropertyCallback real_property_callback;
bool release_verified;
unsigned filtered_properties, filtered_interfaces, ownership_refusals;
unsigned hook_success[4]{}, hook_failure[4]{};
unsigned hook_calls[4]{};
unsigned filter_calls;
HookStub stubs[4]{};
struct OwnedIfaddrs {
    ifaddrs ifa;
    char name[65];
    sockaddr_storage addr;
    sockaddr_storage netmask;
    sockaddr_storage ifu;
};
static_assert(offsetof(OwnedIfaddrs, ifa) == 0, "ifaddrs must be at offset 0");

void log(const char* message) {
    int saved = errno;
    __android_log_write(ANDROID_LOG_INFO, "CageNativePrivacy", message);
    errno = saved;
}

void stage(const char* label, size_t index) {
    char message[96];
    snprintf(message, sizeof(message), "%s.%zu", label, index);
    log(message);
}

bool readable(uintptr_t address, size_t bytes) {
    if (!address || bytes > UINTPTR_MAX - address) return false;
    FILE* file = fopen("/proc/self/maps", "re");
    if (!file) return false;
    char line[2048], permissions[5];
    unsigned long long begin, end;
    bool found = false;
    while (fgets(line, sizeof(line), file)) {
        if (sscanf(line, "%llx-%llx %4s", &begin, &end, permissions) != 3) continue;
        if (permissions[0] == 'r' && address >= begin && address + bytes <= end) {
            found = true;
            break;
        }
    }
    fclose(file);
    return found;
}

bool arm_header(uintptr_t base) {
    if (!readable(base, sizeof(Elf64_Ehdr))) return false;
    Elf64_Ehdr header;
    memcpy(&header, reinterpret_cast<void*>(base), sizeof(header));
    return memcmp(header.e_ident, ELFMAG, SELFMAG) == 0 && header.e_ident[EI_CLASS] == ELFCLASS64
        && header.e_ident[EI_DATA] == ELFDATA2LSB && header.e_machine == EM_AARCH64;
}

#include "modern_bytehook.h"

bool root_matches_locked(const char* path) {
    if (!path) return false;
    for (size_t i = 0; i < root_count; ++i) {
        size_t length = strlen(roots[i]);
        if (strncmp(path, roots[i], length) == 0 && (path[length] == '/' || path[length] == '!')) return true;
    }
    return false;
}

bool path_matches(const char* path) {
    pthread_mutex_lock(&configuration);
    bool result = root_matches_locked(path);
    pthread_mutex_unlock(&configuration);
    return result;
}

#include "diagnostics.h"

struct ImageQuery { const char* path; bool arm; };
int inspect_image(dl_phdr_info* info, size_t, void* opaque) {
    auto* query = static_cast<ImageQuery*>(opaque);
    if (info->dlpi_name && strcmp(info->dlpi_name, query->path) == 0) {
        query->arm = arm_header(info->dlpi_addr);
        return 1;
    }
    return 0;
}

bool excluded_caller(const char* path) {
    const char* slash = strrchr(path, '/');
    const char* name = slash ? slash + 1 : path;
    const char* excluded[] = {"libcage_privacy.so", "libcage_compat.so", "libc.so", "libdl.so",
        "liblog.so", "libnb.so", "libtcb.so", "libhoudini.so", "linker", "linker64"};
    for (const char* item : excluded) if (strcmp(name, item) == 0) return true;
    return false;
}

bool mapped_arm_caller(const char* path) {
    ImageQuery query{path, false};
    dl_iterate_phdr(inspect_image, &query);
    return query.arm;
}

bool caller_filter(const char* path, void*) {
    struct RestoreErrno {
        int saved = errno;
        ~RestoreErrno() { errno = saved; }
    } restore_errno;
    // Only selected ARM64 callers outside the excluded system and support libraries are eligible.
    bool first = __atomic_fetch_add(&filter_calls, 1u, __ATOMIC_RELAXED) == 0;
    if (first) log("filter.first.before.scope");
    bool selected = path_matches(path);
    if (first) log("filter.first.after.scope");
    if (!selected) { diagnostic_filter(path, 0); return false; }
    if (excluded_caller(path)) { diagnostic_filter(path, 1); return false; }
    log("filter.selected.before.iteratePhdr");
    bool arm = mapped_arm_caller(path);
    log("filter.selected.after.iteratePhdr");
    diagnostic_filter(path, arm ? 3 : 2);
    return arm;
}

unsigned call_policy() {
    unsigned flags = __atomic_load_n(&enabled, __ATOMIC_ACQUIRE);
    if (!flags) return 0;
    Dl_info caller{};
    if (!dladdr(api.return_address(), &caller) || !path_matches(caller.dli_fname)) return 0;
    return flags;
}

struct HookFrame {
    void* return_address;
    explicit HookFrame(void* address) : return_address(address) {}
    ~HookFrame() {
        int saved = errno;
        api.pop(return_address);
        errno = saved;
    }
};

const char* property_value(const char* key, unsigned flags, char (&zone_buf)[PROP_VALUE_MAX]) {
    if (!key) return nullptr;
    if ((flags & TIMEZONE) && strcmp(key, "persist.sys.timezone") == 0) {
        pthread_mutex_lock(&configuration);
        size_t length = strnlen(active_zone, sizeof(active_zone) - 1);
        memcpy(zone_buf, active_zone, length);
        zone_buf[length] = '\0';
        pthread_mutex_unlock(&configuration);
        return zone_buf;
    }
    if (!(flags & SIM)) return nullptr;
    if (strcmp(key, "gsm.sim.state") == 0) return "ABSENT";
    if (strcmp(key, "gsm.operator.isroaming") == 0) return "false";
    if (strcmp(key, "gsm.current.phone-type") == 0) return "0";
    const char* empty_keys[] = {"gsm.sim.operator.alpha", "gsm.sim.operator.numeric",
        "gsm.sim.operator.iso-country", "gsm.operator.alpha", "gsm.operator.numeric",
        "gsm.operator.iso-country"};
    for (const char* candidate : empty_keys) if (strcmp(key, candidate) == 0) return "";
    return nullptr;
}

void note_call(size_t index, unsigned flags) {
    if (!flags || __atomic_fetch_add(&hook_calls[index], 1u, __ATOMIC_RELAXED) != 0) return;
    int saved = errno;
    char message[64];
    snprintf(message, sizeof(message), "hook.%zu.selected.first.call", index);
    log(message);
    errno = saved;
}

int property_get(const char* key, char* value) {
    int saved = errno;
    HookFrame frame(__builtin_return_address(0));
    using Function = int (*)(const char*, char*);
    auto previous = reinterpret_cast<Function>(api.previous(reinterpret_cast<void*>(property_get)));
    unsigned flags = call_policy();
    note_call(0, flags);
    char zone_buf[PROP_VALUE_MAX]{};
    const char* replacement = property_value(key, flags, zone_buf);
    errno = saved;
    if (!replacement || !value) return previous(key, value);
    size_t length = strlen(replacement);
    if (length >= PROP_VALUE_MAX) return previous(key, value);
    memcpy(value, replacement, length + 1);
    if (__atomic_fetch_add(&filtered_properties, 1u, __ATOMIC_RELAXED) == 0) log("property.filtered.first");
    return static_cast<int>(length);
}

struct PropertyLookup { unsigned flags; const char* replacement; char zone_buf[PROP_VALUE_MAX]; };
void lookup_property(void* opaque, const char* name, const char*, uint32_t) {
    auto* lookup = static_cast<PropertyLookup*>(opaque);
    lookup->replacement = property_value(name, lookup->flags, lookup->zone_buf);
}

int property_read(const prop_info* info, char* name, char* value) {
    int saved = errno;
    HookFrame frame(__builtin_return_address(0));
    using Function = int (*)(const prop_info*, char*, char*);
    auto previous = reinterpret_cast<Function>(api.previous(reinterpret_cast<void*>(property_read)));
    unsigned flags = call_policy();
    note_call(1, flags);
    errno = saved;
    int result = previous(info, name, value);
    saved = errno;
    PropertyLookup lookup{flags, nullptr, {}};
    // Preserve the caller's original legacy name buffer, including its nullness
    // and Bionic's truncation behavior. The modern read supplies the complete key.
    if (result >= 0 && flags && value && real_property_callback)
        real_property_callback(info, lookup_property, &lookup);
    const char* replacement = lookup.replacement;
    if (replacement) {
        size_t length = strlen(replacement);
        if (length < PROP_VALUE_MAX) {
            memcpy(value, replacement, length + 1);
            result = static_cast<int>(length);
            if (__atomic_fetch_add(&filtered_properties, 1u, __ATOMIC_RELAXED) == 0) log("property.filtered.first");
        }
    }
    errno = saved;
    return result;
}

struct PropertyRead { PropertyCallback callback; void* cookie; unsigned flags; };
void property_callback(void* opaque, const char* name, const char* value, uint32_t serial) {
    auto* read = static_cast<PropertyRead*>(opaque);
    char zone_buf[PROP_VALUE_MAX]{};
    const char* replacement = property_value(name, read->flags, zone_buf);
    if (replacement) {
        value = replacement;
        if (__atomic_fetch_add(&filtered_properties, 1u, __ATOMIC_RELAXED) == 0) log("property.filtered.first");
    }
    read->callback(read->cookie, name, value, serial);
}

void property_read_callback(const prop_info* info, PropertyCallback callback, void* cookie) {
    int saved = errno;
    HookFrame frame(__builtin_return_address(0));
    using Function = void (*)(const prop_info*, PropertyCallback, void*);
    auto previous = reinterpret_cast<Function>(api.previous(reinterpret_cast<void*>(property_read_callback)));
    unsigned flags = call_policy();
    note_call(2, flags);
    errno = saved;
    if (!flags || !callback) { previous(info, callback, cookie); return; }
    // Bionic's read callback is synchronous; this stack context cannot outlive the call.
    PropertyRead read{callback, cookie, flags};
    previous(info, property_callback, &read);
}

bool tunnel_name_with(const char* name, const char (&local_interfaces)[MAX_INTERFACES][64], size_t local_count) {
    if (!name) return false;
    for (size_t i = 0; i < local_count; ++i)
        if (strcmp(local_interfaces[i], name) == 0) return true;
    const char* prefixes[] = {"tun", "tap", "ppp", "wg", "ipsec"};
    for (const char* prefix : prefixes) {
        size_t length = strlen(prefix);
        if (strncmp(name, prefix, length) != 0 || name[length] == '\0') continue;
        const char* digit = name + length;
        while (*digit >= '0' && *digit <= '9') ++digit;
        if (*digit == '\0') return true;
    }
    return false;
}

size_t sockaddr_copy_len(const sockaddr* sa, sa_family_t fallback_family) {
    if (!sa) return 0;
    switch (sa->sa_family) {
        case AF_INET: return sizeof(sockaddr_in);
        case AF_INET6: return sizeof(sockaddr_in6);
        case AF_PACKET: return sizeof(sockaddr_ll);
        case AF_UNSPEC:
            if (fallback_family == AF_INET) return sizeof(sockaddr_in);
            if (fallback_family == AF_INET6) return sizeof(sockaddr_in6);
            return 0;
        default: return 0;
    }
}

bool copy_sockaddr(const sockaddr* source, sa_family_t fallback_family,
        sockaddr_storage& storage, sockaddr*& destination) {
    if (!source) return true;
    size_t length = sockaddr_copy_len(source, fallback_family);
    if (length == 0 || length > sizeof(storage)) return false;
    memcpy(&storage, source, length);
    destination = reinterpret_cast<sockaddr*>(&storage);
    return true;
}

bool copy_interface(const ifaddrs* item, OwnedIfaddrs* node) {
    if (item->ifa_data) return false;
    if (item->ifa_name) {
        size_t length = strnlen(item->ifa_name, sizeof(node->name));
        if (length == sizeof(node->name)) return false;
        memcpy(node->name, item->ifa_name, length + 1);
        node->ifa.ifa_name = node->name;
    }
    node->ifa.ifa_flags = item->ifa_flags;
    sa_family_t addr_family = item->ifa_addr ? item->ifa_addr->sa_family : static_cast<sa_family_t>(AF_UNSPEC);
    return copy_sockaddr(item->ifa_addr, AF_UNSPEC, node->addr, node->ifa.ifa_addr)
        && copy_sockaddr(item->ifa_netmask, addr_family, node->netmask, node->ifa.ifa_netmask)
        && copy_sockaddr(item->ifa_ifu.ifu_broadaddr, addr_family, node->ifu, node->ifa.ifa_ifu.ifu_broadaddr);
}

enum class FilterResult { Unchanged, Filtered, Failed };

FilterResult filter_interfaces(ifaddrs** output, int* out_errno) {
    char local_interfaces[MAX_INTERFACES][64];
    pthread_mutex_lock(&configuration);
    memcpy(local_interfaces, interfaces, sizeof(local_interfaces));
    size_t local_count = interface_count;
    pthread_mutex_unlock(&configuration);

    size_t total = 0, kept = 0;
    for (ifaddrs* item = *output; item; item = item->ifa_next) {
        ++total;
        if (!tunnel_name_with(item->ifa_name, local_interfaces, local_count)) ++kept;
    }
    if (kept == total) return FilterResult::Unchanged;
    if (kept == 0) {
        real_freeifaddrs(*output);
        *output = nullptr;
        return FilterResult::Filtered;
    }

    ifaddrs* head = nullptr;
    ifaddrs** tail = &head;
    int error = 0;
    for (ifaddrs* item = *output; item; item = item->ifa_next) {
        if (tunnel_name_with(item->ifa_name, local_interfaces, local_count)) continue;
        auto* node = static_cast<OwnedIfaddrs*>(calloc(1, sizeof(OwnedIfaddrs)));
        if (!node) {
            error = ENOMEM;
            break;
        }
        if (!copy_interface(item, node)) {
            free(node);
            error = ENOTSUP;
            break;
        }
        *tail = &node->ifa;
        tail = &node->ifa.ifa_next;
    }

    if (error) {
        while (head) {
            ifaddrs* next = head->ifa_next;
            free(head);
            head = next;
        }
        *out_errno = error;
        real_freeifaddrs(*output);
        *output = nullptr;
        return FilterResult::Failed;
    }
    real_freeifaddrs(*output);
    *output = head;
    return FilterResult::Filtered;
}

int network_interfaces(ifaddrs** output) {
    int saved = errno;
    HookFrame frame(__builtin_return_address(0));
    auto previous = reinterpret_cast<GetIfaddrs>(api.previous(reinterpret_cast<void*>(network_interfaces)));
    unsigned flags = call_policy();
    note_call(3, flags);
    errno = saved;
    int result = previous(output);
    saved = errno;
    if (result == 0 && output && *output && (flags & VPN)) {
        if (!release_verified || previous != real_getifaddrs) {
            __atomic_fetch_add(&ownership_refusals, 1u, __ATOMIC_RELAXED);
            static bool unverified_logged;
            if (!__atomic_exchange_n(&unverified_logged, true, __ATOMIC_RELAXED))
                log("getifaddrs.not.filtered.ownership.unverified");
        } else {
            int filter_err = ENOMEM;
            FilterResult fr = filter_interfaces(output, &filter_err);
            if (fr == FilterResult::Failed) {
                __atomic_fetch_add(&ownership_refusals, 1u, __ATOMIC_RELAXED);
                static bool failure_logged;
                if (!__atomic_exchange_n(&failure_logged, true, __ATOMIC_RELAXED)) log("getifaddrs.filter.failed");
                errno = filter_err;
                return -1;
            }
            if (fr == FilterResult::Filtered && __atomic_fetch_add(&filtered_interfaces, 1u, __ATOMIC_RELAXED) == 0)
                log("getifaddrs.filtered.first");
        }
    }
    errno = saved;
    return result;
}

bool same_libc_owner(void* first, void* second) {
    Dl_info owners[2]{};
    if (!first || !second || !dladdr(first, &owners[0]) || !dladdr(second, &owners[1])
        || !owners[0].dli_fbase || owners[0].dli_fbase != owners[1].dli_fbase) return false;
    for (const Dl_info& owner : owners) {
        if (!owner.dli_fname) return false;
        const char* slash = strrchr(owner.dli_fname, '/');
        if (strcmp(slash ? slash + 1 : owner.dli_fname, "libc.so") != 0) return false;
    }
    return true;
}

void hook_result(HookStub, int status, const char*, const char*, void*, void*, void* opaque) {
    size_t index = reinterpret_cast<uintptr_t>(opaque);
    if (index >= 4) return;
    if (status == 0) __atomic_fetch_add(&hook_success[index], 1u, __ATOMIC_RELAXED);
    else if (status != 13) __atomic_fetch_add(&hook_failure[index], 1u, __ATOMIC_RELAXED);
    if (status != 13) {
        char message[96];
        snprintf(message, sizeof(message), "hook.%zu.status=%d", index, status);
        log(message);
    }
}

bool read_strings(JNIEnv* env, jobjectArray values, char* output, size_t stride, size_t capacity, size_t* count) {
    *count = 0;
    if (!values) return true;
    jsize size = env->GetArrayLength(values);
    if (size < 0 || static_cast<size_t>(size) > capacity) return false;
    for (jsize i = 0; i < size; ++i) {
        auto value = static_cast<jstring>(env->GetObjectArrayElement(values, i));
        if (!value) return false;
        const char* bytes = env->GetStringUTFChars(value, nullptr);
        if (!bytes) { env->DeleteLocalRef(value); return false; }
        size_t length = strlen(bytes);
        bool ok = length > 0 && length < stride;
        if (ok) memcpy(output + static_cast<size_t>(i) * stride, bytes, length + 1);
        env->ReleaseStringUTFChars(value, bytes);
        env->DeleteLocalRef(value);
        if (!ok) return false;
        ++*count;
    }
    return !env->ExceptionCheck();
}
}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_io_github_flysky701_opencage_privacy_internal_NativePrivacy_nativeSetZone(JNIEnv* env, jclass, jstring java_zone) {
    if (!java_zone) return;
    const char* bytes = env->GetStringUTFChars(java_zone, nullptr);
    if (!bytes) return;
    size_t len = strlen(bytes);
    bool ok = len > 0 && len < sizeof(active_zone);
    for (size_t i = 0; ok && i < len; ++i) {
        char c = bytes[i];
        ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
            || c == '/' || c == '_' || c == '-' || c == '+';
    }
    if (ok) {
        pthread_mutex_lock(&configuration);
        memcpy(active_zone, bytes, len + 1);
        pthread_mutex_unlock(&configuration);
    }
    env->ReleaseStringUTFChars(java_zone, bytes);
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_flysky701_opencage_privacy_internal_NativePrivacy_nativeBind(JNIEnv* env, jclass, jlong table,
        jobjectArray java_roots, jobjectArray java_interfaces, jint flags, jboolean synchronize_loads,
        jboolean native_arm64) {
    (void)table;
    log("nativeBind.enter");
    __atomic_store_n(&enabled, 0u, __ATOMIC_RELEASE);
    if (installation_failed) return -4;
    char new_roots[MAX_ROOTS][PATH_MAX]{}, new_interfaces[MAX_INTERFACES][64]{};
    size_t new_root_count = 0, new_interface_count = 0;
    log("nativeBind.before.readArguments");
    if ((flags & ~7) || !read_strings(env, java_roots, &new_roots[0][0], PATH_MAX, MAX_ROOTS, &new_root_count)
        || !new_root_count || !read_strings(env, java_interfaces, &new_interfaces[0][0], 64, MAX_INTERFACES, &new_interface_count)) return -1;
    log("nativeBind.after.readArguments");
    for (size_t i = 0; i < new_root_count; ++i)
        if (strncmp(new_roots[i], "/data/", 6) != 0 || strstr(new_roots[i], "/../") || strcmp(new_roots[i], "/data") == 0) return -2;
    log("nativeBind.before.bindApi");
    if (!native_arm64 || !bind_modern_api()) {
        log("bind.bytehook.api.rejected");
        return -3;
    }
    log("nativeBind.after.bindApi");
    if (synchronize_loads) return -7;
    log("nativeBind.before.configurationLock");
    pthread_mutex_lock(&configuration);
    memcpy(roots, new_roots, sizeof(roots));
    root_count = new_root_count;
    memcpy(interfaces, new_interfaces, sizeof(interfaces));
    interface_count = new_interface_count;
    pthread_mutex_unlock(&configuration);
    log("nativeBind.after.configurationUnlock");
    if (!installed) {
        log("nativeBind.before.resolveSymbols");
        real_getifaddrs = reinterpret_cast<GetIfaddrs>(dlsym(RTLD_DEFAULT, "getifaddrs"));
        real_freeifaddrs = reinterpret_cast<FreeIfaddrs>(dlsym(RTLD_DEFAULT, "freeifaddrs"));
        real_property_callback = reinterpret_cast<ReadPropertyCallback>(dlsym(RTLD_DEFAULT, "__system_property_read_callback"));
        if (!real_property_callback) { log("bind.property.callback.unavailable"); return -5; }
        log("nativeBind.after.resolveSymbols");
        capture_observation(&binding_observation, -1); // before registering any privacy task
        const char* names[] = {"__system_property_get", "__system_property_read", "__system_property_read_callback",
            "getifaddrs"};
        void* functions[] = {reinterpret_cast<void*>(property_get), reinterpret_cast<void*>(property_read),
            reinterpret_cast<void*>(property_read_callback), reinterpret_cast<void*>(network_interfaces)};
        for (size_t i = 0; i < 4; ++i) {
            stage("nativeBind.before.partial", i);
            stubs[i] = api.partial(caller_filter, nullptr, nullptr, names[i], functions[i], hook_result, reinterpret_cast<void*>(i));
            stage("nativeBind.after.partial", i);
            if (!stubs[i]) {
                installation_failed = true;
                for (size_t previous = 0; previous < i; ++previous) {
                    api.unhook(stubs[previous]);
                    stubs[previous] = nullptr;
                }
                log("bind.hook.request.failed");
                return -4;
            }
        }
        release_verified = same_libc_owner(reinterpret_cast<void*>(real_getifaddrs), reinterpret_cast<void*>(real_freeifaddrs));
        installed = true;
    }
    if (!load_observer_requested) {
        api.add_dlopen(nullptr, diagnostic_after_dlopen, nullptr);
        load_observer_requested = true;
        log("observation.dlopen.callback.requested");
    }
    __atomic_store_n(&enabled, static_cast<unsigned>(flags), __ATOMIC_RELEASE);
    log(release_verified ? "bound.arm.plt.ownership.verified" : "bound.arm.plt.ownership.pending");
    return release_verified ? 2 : 1;
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_flysky701_opencage_privacy_internal_NativePrivacy_nativeDeactivate(JNIEnv*, jclass) {
    // Leave trampolines installed for in-flight calls; every subsequent call passes through.
    __atomic_store_n(&enabled, 0u, __ATOMIC_RELEASE);
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_flysky701_opencage_privacy_internal_NativePrivacy_nativeObserveInterface(JNIEnv* env, jclass, jstring value) {
    if (!value || !__atomic_load_n(&enabled, __ATOMIC_ACQUIRE)) return;
    const char* name = env->GetStringUTFChars(value, nullptr);
    if (!name) return;
    size_t length = strlen(name);
    if (length > 0 && length < sizeof(interfaces[0])) {
        pthread_mutex_lock(&configuration);
        bool exists = false;
        for (size_t i = 0; i < interface_count; ++i) if (strcmp(interfaces[i], name) == 0) exists = true;
        if (!exists && interface_count < MAX_INTERFACES) memcpy(interfaces[interface_count++], name, length + 1);
        pthread_mutex_unlock(&configuration);
    }
    env->ReleaseStringUTFChars(value, name);
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_flysky701_opencage_privacy_internal_NativePrivacy_nativeDiagnostics(JNIEnv* env, jclass) {
    char status[512];
    snprintf(status, sizeof(status), "active=%u installed=%d ownership=%d hooks=%u,%u,%u,%u calls=%u,%u,%u,%u failures=%u,%u,%u,%u properties=%u interfaces=%u ownershipRefusals=%u",
        __atomic_load_n(&enabled, __ATOMIC_ACQUIRE), installed, release_verified,
        __atomic_load_n(&hook_success[0], __ATOMIC_RELAXED), __atomic_load_n(&hook_success[1], __ATOMIC_RELAXED),
        __atomic_load_n(&hook_success[2], __ATOMIC_RELAXED), __atomic_load_n(&hook_success[3], __ATOMIC_RELAXED),
        __atomic_load_n(&hook_calls[0], __ATOMIC_RELAXED), __atomic_load_n(&hook_calls[1], __ATOMIC_RELAXED),
        __atomic_load_n(&hook_calls[2], __ATOMIC_RELAXED), __atomic_load_n(&hook_calls[3], __ATOMIC_RELAXED),
        __atomic_load_n(&hook_failure[0], __ATOMIC_RELAXED), __atomic_load_n(&hook_failure[1], __ATOMIC_RELAXED),
        __atomic_load_n(&hook_failure[2], __ATOMIC_RELAXED), __atomic_load_n(&hook_failure[3], __ATOMIC_RELAXED),
        __atomic_load_n(&filtered_properties, __ATOMIC_RELAXED), __atomic_load_n(&filtered_interfaces, __ATOMIC_RELAXED),
        __atomic_load_n(&ownership_refusals, __ATOMIC_RELAXED));
    return env->NewStringUTF(status);
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_flysky701_opencage_privacy_internal_NativePrivacy_nativeCapture(JNIEnv* env, jclass, jint sequence) {
    if (!__atomic_load_n(&enabled, __ATOMIC_ACQUIRE)) return env->NewStringUTF("inactive\n");
    if (sequence == -1) return env->NewStringUTF(binding_observation.bytes);
    ObservationText observation;
    capture_observation(&observation, sequence);
    return env->NewStringUTF(observation.bytes);
}
