/* Keep the end-to-end fixture independent of real LAN traffic on Linux. */
#define _GNU_SOURCE
#include <arpa/inet.h>
#include <dlfcn.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <sys/types.h>

static int loopback(const struct sockaddr *addr)
{
	return (addr->sa_family == AF_INET &&
	        (ntohl(((const struct sockaddr_in *)addr)->sin_addr.s_addr) >> 24) == 127) ||
	       (addr->sa_family == AF_INET6 &&
	        IN6_IS_ADDR_LOOPBACK(&((const struct sockaddr_in6 *)addr)->sin6_addr));
}

ssize_t sendto(int fd, const void *buf, size_t len, int flags,
               const struct sockaddr *addr, socklen_t addrlen)
{
	static ssize_t (*real_sendto)(int, const void *, size_t, int, const struct sockaddr *, socklen_t);
	if (!real_sendto)
		real_sendto = dlsym(RTLD_NEXT, "sendto");
	if (addr && (addr->sa_family == AF_INET || addr->sa_family == AF_INET6) && !loopback(addr))
		return (ssize_t)len;
	return real_sendto(fd, buf, len, flags, addr, addrlen);
}

ssize_t recvfrom(int fd, void *buf, size_t len, int flags,
                 struct sockaddr *addr, socklen_t *addrlen)
{
	static ssize_t (*real_recvfrom)(int, void *, size_t, int, struct sockaddr *, socklen_t *);
	struct sockaddr_storage source;
	socklen_t size;
	ssize_t result;
	if (!real_recvfrom)
		real_recvfrom = dlsym(RTLD_NEXT, "recvfrom");
	for (;;)
	{
		size = sizeof(source);
		result = real_recvfrom(fd, buf, len, flags, (struct sockaddr *)&source, &size);
		if (result < 0)
			return result;
		if (loopback((struct sockaddr *)&source))
		{
			if (addr && addrlen)
			{
				socklen_t copy = *addrlen < size ? *addrlen : size;
				__builtin_memcpy(addr, &source, copy);
				*addrlen = size;
			}
			return result;
		}
	}
}
