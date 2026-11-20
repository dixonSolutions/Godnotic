/*
 * Desktop run of the Android live engine swap: the real resident loader (andysu_loader.c, compiled
 * here with the shims in shim/) runs a desktop build of the engine library (libmain.so, built with
 * -DANDYSU_SWAP) on this thread, exactly as AndySU_SDL_main runs on Android's SDL thread. A second
 * thread asks the loader for a swap (request_swap, what EngineSwap.nativeRequestSwap calls) once
 * the first engine has drawn its menu for a while, to the library named in SWAP_TO (default: the
 * same file, which the loader copies to a new name, so it is a second, separate engine copy).
 *
 * Environment: SWAP_AFTER_FRAMES (engine_frames count before the swap, default 600),
 * SWAP_TO (path), SWAP_FAIL_INIT=1 (the new engine fails its start: the in-process rollback).
 * The loader stages its copies in <harness dir>/stage (ANDYSU_SWAP_DIR_FMT).
 */
/* The loader stages each swapped-in engine in ANDYSU_SWAP_DIR_FMT (on Android the app's files
 * dir); here a directory next to the harness ("%.0s" swallows the package name). */
static char harness_swap_dir[600];
#define ANDYSU_SWAP_DIR_FMT harness_swap_dir
#include "../../../android/app/src/main/cpp/andysu/andysu_loader.c"

#include <dirent.h>
static void list_threads(const char *when)
{
	DIR *d = opendir("/proc/self/task");
	struct dirent *de;
	char line[2048] = "", path[128], name[64];
	int n = 0;
	if (!d) return;
	while ((de = readdir(d))) {
		FILE *f;
		if (de->d_name[0] == '.') continue;
		snprintf(path, sizeof path, "/proc/self/task/%s/comm", de->d_name);
		f = fopen(path, "r");
		if (!f) continue;
		if (fgets(name, sizeof name, f)) {
			name[strcspn(name, "\n")] = 0;
			snprintf(line + strlen(line), sizeof line - strlen(line), "%s%s", n++ ? "," : "", name);
		}
		fclose(f);
	}
	closedir(d);
	fprintf(stderr, "[harness] threads %s: %d [%s]\n", when, n, line);
}

static void *swap_trigger(void *arg)
{
	const char *to = (const char *)arg;
	const char *f = getenv("SWAP_AFTER_FRAMES");
	unsigned want = f ? (unsigned)atoi(f) : 600;
	char err[256] = "";
	for (;;) {
		usleep(100 * 1000);
		const andysu_engine *e = g_engine;
		if (!e) continue;
		/* engine_frames is emitted every 120 frames; count them through the shim's own counter. */
		if (harness_frames < want) continue;
		break;
	}
	const andysu_engine *before = g_engine;
	list_threads("before the swap");
	fprintf(stderr, "[harness] requesting a swap to %s\n", to);
	if (request_swap(to, getenv("SWAP_FAIL_INIT") != NULL, err, sizeof err)) {
		fprintf(stderr, "[harness] swap refused: %s\n", err);
		return NULL;
	}
	/* Once the next engine runs (or the previous one again, after a rollback), list the threads:
	 * a thread of the stopped engine still alive here would run on its freed memory. */
	for (int i = 0; i < 600; i++) {
		usleep(100 * 1000);
		const andysu_engine *e = g_engine;
		if (e && e != before) break;
	}
	harness_frames = 0;
	for (int i = 0; i < 1200 && harness_frames < 240; i++)
		usleep(100 * 1000);
	list_threads("after the swap");
	return NULL;
}

int main(int argc, char **argv)
{
	static char to[640];
	pthread_t t;
	const char *env = getenv("SWAP_TO");
	if (env) {
		snprintf(to, sizeof to, "%s", env);
	} else {
		char exe[600];
		ssize_t n = readlink("/proc/self/exe", exe, sizeof exe - 1);
		if (n <= 0) return 2;
		exe[n] = 0;
		*strrchr(exe, '/') = 0;
		snprintf(to, sizeof to, "%s/libmain.so", exe);
	}
	{
		char exe[500];
		ssize_t n = readlink("/proc/self/exe", exe, sizeof exe - 1);
		if (n <= 0) return 2;
		exe[n] = 0;
		*strrchr(exe, '/') = 0;
		snprintf(harness_swap_dir, sizeof harness_swap_dir, "%s/stage%%.0s", exe);
	}
	pthread_create(&t, NULL, swap_trigger, to);
	pthread_detach(t);
	return AndySU_SDL_main(argc, argv);
}
