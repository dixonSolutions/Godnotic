/*
Checks how the engine leaves the process and which signals it ignores on Android
(engine/darkplaces/touch_exit.h), by running both in child processes:

- the updater SIGABRT and the RenderThread/hwuiTask1 SIGSEGVs: exit() ran static
  destructors (here: an atexit handler that destroys a mutex) while another
  thread still used them. TouchExit_Now must end the process without running them.
- the silent EXIT_SELF deaths: a write to a closed pipe raised SIGPIPE and the
  engine's handler quit the game. With the Android setting the write fails with
  EPIPE and the process carries on.

Run through scripts/test-android-stability.sh.
*/
#include <errno.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/wait.h>
#include <unistd.h>

#include "touch_exit.h"

static int failures;

#define CHECK_INT(expected, actual, what) do { \
	int e_ = (expected), a_ = (actual); \
	if (e_ != a_) { \
		printf("FAIL %s: expected %d, got %d\n", (what), e_, a_); \
		failures++; \
	} \
} while (0)

static int marker_fd = -1;
static pthread_mutex_t shared_lock = PTHREAD_MUTEX_INITIALIZER;
static volatile int stop_worker;

static void destructor_ran(void)
{
	/* stands in for a library's static destructor */
	pthread_mutex_destroy(&shared_lock);
	if (write(marker_fd, "D", 1) != 1)
		_exit(99);
}

static void *worker(void *arg)
{
	(void)arg;
	while (!stop_worker)
	{
		pthread_mutex_lock(&shared_lock);
		pthread_mutex_unlock(&shared_lock);
	}
	return NULL;
}

/* Child: a busy worker thread, a destructor registered, then the engine's exit. Returns
 * whether the destructor ran (read back from a pipe) and the exit status. */
static void run_exit(int android, int *destructor, int *status)
{
	int p[2], st = 0;
	char c;
	pid_t pid;
	if (pipe(p) != 0) { perror("pipe"); exit(2); }
	pid = fork();
	if (pid == 0)
	{
		pthread_t t;
		close(p[0]);
		marker_fd = p[1];
		atexit(destructor_ran);
		pthread_create(&t, NULL, worker, NULL);
		usleep(20000);
		/* Sys_Frame after Host_Shutdown: the hook, then exit(0) where the hook returns */
		if (TouchExit_Mode(android) == TOUCH_EXIT_NOW)
			TouchExit_Now(0);
		stop_worker = 1; /* desktop: libc exit (the worker would race here in a real process) */
		pthread_join(t, NULL);
		exit(0);
	}
	close(p[1]);
	*destructor = read(p[0], &c, 1) == 1;
	close(p[0]);
	waitpid(pid, &st, 0);
	*status = WIFEXITED(st) ? WEXITSTATUS(st) : 128 + WTERMSIG(st);
}

/* Child: the signal setup, then a write into a pipe nobody reads. Exit 0 = survived
 * with EPIPE, 128+n = killed by signal n. */
static int run_sigpipe(int android)
{
	int st = 0;
	pid_t pid = fork();
	if (pid == 0)
	{
		int p[2];
		signal(SIGPIPE, SIG_DFL); /* what a process has before the engine's handler */
		TouchExit_IgnoreSignals(android);
		if (pipe(p) != 0)
			_exit(3);
		close(p[0]);
		if (write(p[1], "x", 1) < 0 && errno == EPIPE)
			_exit(0);
		_exit(4);
	}
	waitpid(pid, &st, 0);
	return WIFEXITED(st) ? WEXITSTATUS(st) : 128 + WTERMSIG(st);
}

int main(void)
{
	int destructor, status;

	CHECK_INT(TOUCH_EXIT_NOW, TouchExit_Mode(1), "Android ends the process at once");
	CHECK_INT(TOUCH_EXIT_LIBC, TouchExit_Mode(0), "desktop keeps exit()");
	CHECK_INT(TOUCH_SIG_IGNORE, TouchExit_SignalAction(SIGPIPE, 1), "Android ignores SIGPIPE");
	CHECK_INT(TOUCH_SIG_ENGINE, TouchExit_SignalAction(SIGPIPE, 0), "desktop quits on SIGPIPE as before");
	CHECK_INT(TOUCH_SIG_ENGINE, TouchExit_SignalAction(SIGTERM, 1), "SIGTERM still quits cleanly on Android");
	CHECK_INT(TOUCH_SIG_ENGINE, TouchExit_SignalAction(SIGINT, 1), "SIGINT still quits cleanly on Android");

	run_exit(1, &destructor, &status);
	CHECK_INT(0, destructor, "Android exit: no destructor runs under the live updater thread");
	CHECK_INT(0, status, "Android exit: status 0");

	run_exit(0, &destructor, &status);
	CHECK_INT(1, destructor, "desktop exit: atexit handlers still run (config, native front end result)");
	CHECK_INT(0, status, "desktop exit: status 0");

	CHECK_INT(0, run_sigpipe(1), "Android: a write to a closed pipe fails with EPIPE, the game carries on");
	CHECK_INT(128 + SIGPIPE, run_sigpipe(0), "without the Android setting the same write ends the process");

	if (failures)
	{
		printf("%d check(s) failed\n", failures);
		return 1;
	}
	printf("android exit and signals: all checks passed\n");
	return 0;
}
