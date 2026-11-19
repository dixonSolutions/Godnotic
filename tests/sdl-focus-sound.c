#include <SDL.h>
#include <assert.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
typedef bool qbool;
#include "touch_sdl_visibility.h"
typedef struct { unsigned int channels, width; } format_t;
typedef struct { format_t format; unsigned int startframe, endframe, maxframes; unsigned char ring[32]; } buffer_t;
static buffer_t buffer, *snd_renderbuffer = &buffer;
static bool snd_blocked, snd_usethreadedmixing, vid_activewindow;
static bool vid_hidden;
static struct { int integer; } snd_mutewhenidle;
static struct { bool timedemo; } cls;
static unsigned int sdlaudiotime;
static struct { int integer; } developer_insane;
static void Sys_Error(const char *s, int n) { (void)s; (void)n; abort(); }
static void Con_DPrintf(const char *s, ...) { (void)s; }
static void S_MixToBuffer(unsigned char *p, unsigned int n) { memset(p, 42, n * buffer.format.width); }
#include "callback.inc"
static void update_blocked(void)
{
#include "block.inc"
}
static void check_audio(bool blocked, bool threaded, int width)
{
	unsigned char out[8];
	buffer.format.channels = 1; buffer.format.width = width;
	buffer.startframe = 0; buffer.endframe = 8; buffer.maxframes = 16;
	memset(buffer.ring, 42, sizeof(buffer.ring));
	snd_blocked = blocked; snd_usethreadedmixing = threaded;
	Buffer_Callback(NULL, out, sizeof(out));
	for (unsigned int i = 0; i < sizeof(out); i++)
		assert(out[i] == (blocked ? (width == 1 ? 128 : 0) : 42));
}
int main(void)
{
	qbool hidden = false;
	Touch_SDLVisibility(SDL_WINDOWEVENT_MINIMIZED, &hidden); assert(hidden);
	Touch_SDLVisibility(SDL_WINDOWEVENT_RESTORED, &hidden); assert(!hidden);
	for (int mode = 1; mode <= 2; mode++) {
		snd_mutewhenidle.integer = mode;
		vid_hidden = false; vid_activewindow = true; update_blocked(); assert(!snd_blocked);
		vid_activewindow = false; update_blocked(); assert(snd_blocked == (mode == 1));
		Touch_SDLVisibility(SDL_WINDOWEVENT_MINIMIZED, &vid_hidden);
		update_blocked(); assert(snd_blocked);
		Touch_SDLVisibility(SDL_WINDOWEVENT_RESTORED, &vid_hidden);
		vid_activewindow = true; update_blocked(); assert(!snd_blocked);
	}
	for (int threaded = 0; threaded < 2; threaded++)
		for (int width = 1; width <= 2; width++) {
			check_audio(false, threaded, width);
			check_audio(true, threaded, width);
			check_audio(false, threaded, width);
		}
	puts("SDL visibility and audio callback: PASS");
	return 0;
}
