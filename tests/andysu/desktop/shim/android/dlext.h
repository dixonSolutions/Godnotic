/* Desktop stand-in for <android/dlext.h>. ANDROID_DLEXT_FORCE_LOAD (a fresh mapping of a file that is
 * already loaded) becomes: copy the file to a new name, then dlopen the copy, which glibc maps anew. */
#ifndef GODNOTIC_DESKTOP_ANDROID_DLEXT_H
#define GODNOTIC_DESKTOP_ANDROID_DLEXT_H
#include <dlfcn.h>
#include <stdint.h>
#include <stdio.h>
#include <sys/stat.h>
#include <unistd.h>
#define ANDROID_DLEXT_FORCE_LOAD 0x40
typedef struct { uint64_t flags; } android_dlextinfo;
static inline void *android_dlopen_ext(const char *path, int flags, const android_dlextinfo *ext)
{
	static int n;
	char copy[700];
	FILE *in, *out;
	char buf[65536];
	size_t got;
	if (!ext || !(ext->flags & ANDROID_DLEXT_FORCE_LOAD))
		return dlopen(path, flags);
	snprintf(copy, sizeof copy, "%s.force%d.so", path, ++n);
	unlink(copy); /* a read-only copy left by an earlier run */
	in = fopen(path, "rb");
	out = in ? fopen(copy, "wb") : NULL;
	if (!in || !out) { if (in) fclose(in); return NULL; }
	while ((got = fread(buf, 1, sizeof buf, in)) > 0) fwrite(buf, 1, got, out);
	fclose(in);
	fclose(out);
	chmod(copy, 0555);
	return dlopen(copy, flags);
}
#endif
