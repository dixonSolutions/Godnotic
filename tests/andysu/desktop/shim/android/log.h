/* Desktop stand-in for <android/log.h>: the loader's log lines and AndySU-Event JSON go to stderr. */
#ifndef GODNOTIC_DESKTOP_ANDROID_LOG_H
#define GODNOTIC_DESKTOP_ANDROID_LOG_H
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
enum { ANDROID_LOG_INFO = 4, ANDROID_LOG_ERROR = 6 };
/* The running engine's frame count, from its engine_frames events (the swap harness waits on it). */
static volatile unsigned harness_frames;
static inline int __android_log_write(int prio, const char *tag, const char *text)
{
	const char *f = strstr(text, "\"event\":\"engine_frames\"");
	(void)prio;
	if (f && (f = strstr(f, "\"frames\":")))
		harness_frames = (unsigned)strtoul(f + 9, NULL, 10);
	fprintf(stderr, "[%s] %s\n", tag, text);
	fflush(stderr);
	return 0;
}
static inline int __android_log_print(int prio, const char *tag, const char *fmt, ...)
{
	va_list ap;
	(void)prio;
	fprintf(stderr, "[%s] ", tag);
	va_start(ap, fmt);
	vfprintf(stderr, fmt, ap);
	va_end(ap);
	fputc('\n', stderr);
	fflush(stderr);
	return 0;
}
#endif
