#include <jni.h>
#include <stdint.h>
#include <errno.h>
#include <netinet/in.h>
#include <unistd.h>
#include <sys/socket.h>

JNIEXPORT jint JNICALL Java_io_github_flysky701_opencage_preflight_SocketProbe_routeMark(
        JNIEnv *env, jclass type, jint fd) {
    (void)env;
    (void)type;
    uint32_t mark = 0;
    socklen_t size = sizeof(mark);
    if (getsockopt(fd, SOL_SOCKET, SO_MARK, &mark, &size) != 0 || size != sizeof(mark)) return -1;
    return mark & 0xffff;
}

JNIEXPORT jint JNICALL Java_io_github_flysky701_opencage_preflight_SocketProbe_ipv6Route(
        JNIEnv *env, jclass type) {
    (void)env;
    (void)type;
    int fd = socket(AF_INET6, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return errno == EAFNOSUPPORT ? 0 : -1;
    struct sockaddr_in6 dst = {.sin6_family=AF_INET6, .sin6_port=htons(9),
        .sin6_addr.s6_addr={0x20,0x01,0x0d,0xb8,0,0,0,0,0,0,0,0,0,0,0,1}};
    int result = connect(fd,(const struct sockaddr *)&dst,sizeof(dst));
    int error = errno;
    close(fd);
    if (result == 0) return 1;
    return (error==ENETUNREACH || error==EHOSTUNREACH || error==EACCES || error==EPERM) ? 0 : -1;
}
