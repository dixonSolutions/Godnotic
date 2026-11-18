#include <SDL.h>
#include <assert.h>
#include <stdio.h>

static int surface_missing;
static void drawable_size(SDL_Window *window, int *w, int *h)
{
	if (surface_missing)
		*w = *h = 0;
	else
		SDL_GL_GetDrawableSize(window, w, h);
}
#define SDL_GL_GetDrawableSize drawable_size
#include "touch_sdl_resize.h"

typedef struct cvar_s { int integer; void (*callback)(struct cvar_s *); } cvar_t;
static cvar_t vid_width, vid_height;
static int modesets;
static void modeset(cvar_t *var) { (void)var; ++modesets; }
static void Cvar_SetValueQuick(cvar_t *var, float value)
{
	var->integer = (int)value;
	if (var->callback) var->callback(var);
}
#include "touch_sdl_resize_cvars.h"

int main(void)
{
	SDL_Window *window;
	SDL_GLContext context;
	int width, height, actual_width, actual_height;
	assert(SDL_Init(SDL_INIT_VIDEO) == 0);
	window = SDL_CreateWindow("resize test", 0, 0, 640, 360, SDL_WINDOW_OPENGL | SDL_WINDOW_RESIZABLE | SDL_WINDOW_HIDDEN);
	if (!window) { fprintf(stderr, "SDL_CreateWindow: %s\n", SDL_GetError()); return 1; }
	context = SDL_GL_CreateContext(window);
	if (!context) { fprintf(stderr, "SDL_GL_CreateContext: %s\n", SDL_GetError()); return 1; }
	SDL_GL_GetDrawableSize(window, &width, &height);
	assert(width > 0 && height > 0);
	Touch_SDLResizeBefore(SDL_WINDOWEVENT_SIZE_CHANGED, width, height);
	SDL_SetWindowSize(window, 360, 640);
	SDL_PumpEvents();
	SDL_GL_GetDrawableSize(window, &actual_width, &actual_height);
	Touch_SDLResizeAfter(SDL_WINDOWEVENT_SIZE_CHANGED, window, &width, &height);
	assert(width == actual_width && height == actual_height && width < height);
	vid_width.callback = vid_height.callback = modeset;
	Touch_SDLResizeCvars(SDL_WINDOWEVENT_SIZE_CHANGED, width, height);
	assert(vid_width.integer == width && vid_height.integer == height && modesets == 0);
	assert(vid_width.callback == modeset && vid_height.callback == modeset);
	Touch_SDLResizeCvars(SDL_WINDOWEVENT_SIZE_CHANGED, 0, 0);
	assert(vid_width.integer == width && vid_height.integer == height);
	Touch_SDLResizeBefore(SDL_WINDOWEVENT_RESIZED, width, height);
	surface_missing = 1;
	width = height = 0; /* upstream SDL event handler saw a lost surface */
	Touch_SDLResizeAfter(SDL_WINDOWEVENT_RESIZED, window, &width, &height);
	assert(width == actual_width && height == actual_height);
	surface_missing = 0;
	SDL_GL_DeleteContext(context);
	SDL_DestroyWindow(window);
	SDL_Quit();
	puts("SDL offscreen drawable resize and surface loss: PASS");
	return 0;
}
