/*
Godnotic UI bridge: the local stream transport (docs/UI_BRIDGE.md 9.2).

  -uibridge fd:<n>          inherited AF_UNIX socket (one end of a socketpair)
  -uibridge stdio           stdin/stdout (console output goes to stderr)
  -uibridge listen[:<path>] Unix socket, default $XDG_RUNTIME_DIR/godnotic/uibridge.sock (0600 in 0700)

NDJSON both ways, non-blocking, on the engine thread. The connection is an ordinary
TouchUIB_Open client, so the stream and the in-process API behave the same.

Back-end mode (any -uibridge): the menu VM describes but does not draw while a native
renderer is attached, the window is hidden while the app context is "menu" or
"loading" and while the game's menu is up over a match (app.menu: the pause menu and
the dialogs over a match, which the front end draws), shown while the match has the
input, and the engine quits cleanly when the front end goes away (fd/stdio at once;
listen after 10 s without a client).

One window on Linux: with GODNOTIC_EMBED_PARENT=<X11 window id> (the GTK app's own
window, the app on X11 or XWayland) the game window is created hidden, put inside
that window as a child (XReparentWindow), kept at its size, and mapped over the app's
pages while the match has the input: no second top-level window ever appears. The
app's window focus and visibility come in its caps (`focused`, `visible`): the game
mutes (snd_mutewhenidle) and stops drawing by them whenever its own window is not a
top-level of its own.
*/
#include "quakedef.h"
#include "touch_uib_int.h"

#if !defined(WIN32) && !defined(__EMSCRIPTEN__)
#include <errno.h>
#include <fcntl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>
#define UIB_STREAM 1
#endif

#if defined(CONFIG_MENU) && defined(__has_include) && !defined(__ANDROID__) && !defined(__EMSCRIPTEN__)
#if __has_include(<SDL.h>)
#include <SDL.h>
#define UIB_SDL_WINDOW 1
#endif
#endif

// The X11 part of SDL_SysWMinfo needs the Xlib headers only: it is there whenever they
// are, also with an SDL configured without them (the union keeps its size).
#if defined(UIB_SDL_WINDOW) && defined(__linux__) && __has_include(<SDL_syswm.h>) && __has_include(<X11/Xlib.h>)
#ifndef SDL_VIDEO_DRIVER_X11
#define SDL_VIDEO_DRIVER_X11 1
#endif
#include <SDL_syswm.h>
#include <dlfcn.h>
#define UIB_X11_EMBED 1
#endif

static struct
{
	int mode;               // 0 off, 1 fd, 2 stdio, 3 listen
	int rfd, wfd, lfd;
	char path[256];
	touchuib_conn_t *conn;
	char *in;
	size_t inlen, incap;
	char *out;
	size_t outlen, outoff, outcap;
	double lost_at;         // listen: when the last client left
	int ever_connected;
	int window_hidden;
	double start_time;
	int first_hello_logged;
	int menu_published;     // app.menu as last published: -1 never
	unsigned long embed_parent; // GODNOTIC_EMBED_PARENT: the app's X11 window, 0 none
	unsigned long embedded; // the game window's X11 id once it is inside the app's window
	double embed_next;      // the next look at the app's window size
} sm = {0, -1, -1, -1};

int UIBStream_BackendMode(void) { return sm.mode != 0; }
int UIBStream_Active(void) { return sm.conn != NULL; }

#ifdef UIB_STREAM
static void nonblock(int fd)
{
	int fl = fcntl(fd, F_GETFL, 0);
	if (fl >= 0)
		fcntl(fd, F_SETFL, fl | O_NONBLOCK);
}

static void attach(int rfd, int wfd)
{
	sm.rfd = rfd;
	sm.wfd = wfd;
	nonblock(rfd);
	if (wfd != rfd)
		nonblock(wfd);
	sm.conn = TouchUIB_Open("linux", 5);
	sm.inlen = sm.outlen = sm.outoff = 0;
	sm.ever_connected = 1;
}

static void detach(const char *why)
{
	Con_DPrintf("uibridge: front end disconnected (%s)\n", why);
	if (sm.conn)
		TouchUIB_Close(sm.conn);
	sm.conn = NULL;
	if (sm.mode == 3)
	{
		if (sm.rfd >= 0)
			close(sm.rfd);
		sm.rfd = sm.wfd = -1;
		sm.lost_at = Sys_DirtyTime();
		return;
	}
	// fd/stdio: the front end owns us; leave cleanly (saves the config)
	sm.rfd = sm.wfd = -1;
	Cbuf_AddText(cmd_local, "\nquit\n");
}

static void read_input(void)
{
	for (;;)
	{
		if (sm.incap - sm.inlen < 65536)
		{
			sm.incap = sm.incap ? sm.incap * 2 : 131072;
			if (sm.incap > UIB_MAX_MESSAGE * 2 + 65536)
			{
				detach("message too large");
				return;
			}
			sm.in = (char *)realloc(sm.in, sm.incap);
		}
		ssize_t n = read(sm.rfd, sm.in + sm.inlen, sm.incap - sm.inlen - 1);
		if (n > 0)
		{
			sm.inlen += n;
			continue;
		}
		if (n == 0)
		{
			detach("end of stream");
			return;
		}
		if (errno == EINTR)
			continue;
		if (errno != EAGAIN && errno != EWOULDBLOCK)
			detach(strerror(errno));
		break;
	}
	size_t start = 0;
	for (size_t i = 0; i < sm.inlen; i++)
		if (sm.in[i] == '\n')
		{
			size_t len = i - start;
			if (len && !(len == 1 && sm.in[start] == '\r'))
			{
				int r = TouchUIB_Post(sm.conn, sm.in + start, len);
				if (r == -3)
					Con_Printf("uibridge: dropped a message over 4 MiB\n");
			}
			start = i + 1;
		}
	memmove(sm.in, sm.in + start, sm.inlen - start);
	sm.inlen -= start;
}

static void write_output(void)
{
	char *m;
	size_t len;
	while ((m = TouchUIB_Take(sm.conn, &len)))
	{
		if (sm.outlen + len + 1 > sm.outcap)
		{
			while (sm.outlen + len + 1 > sm.outcap)
				sm.outcap = sm.outcap ? sm.outcap * 2 : 262144;
			sm.out = (char *)realloc(sm.out, sm.outcap);
		}
		memcpy(sm.out + sm.outlen, m, len);
		sm.outlen += len;
		sm.out[sm.outlen++] = '\n';
		TouchUIB_Free(m);
	}
	while (sm.outoff < sm.outlen)
	{
		ssize_t n = write(sm.wfd, sm.out + sm.outoff, sm.outlen - sm.outoff);
		if (n > 0)
		{
			sm.outoff += n;
			continue;
		}
		if (n < 0 && errno == EINTR)
			continue;
		if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK))
			break;
		detach(n < 0 ? strerror(errno) : "write failed");
		return;
	}
	if (sm.outoff == sm.outlen)
		sm.outoff = sm.outlen = 0;
}

static int open_listen(const char *path)
{
	char dir[256];
	dp_strlcpy(dir, path, sizeof(dir));
	char *slash = strrchr(dir, '/');
	if (slash)
	{
		*slash = 0;
		mkdir(dir, 0700);
		chmod(dir, 0700);
	}
	int fd = socket(AF_UNIX, SOCK_STREAM, 0);
	if (fd < 0)
		return -1;
	struct sockaddr_un sa;
	memset(&sa, 0, sizeof(sa));
	sa.sun_family = AF_UNIX;
	if (strlen(path) >= sizeof(sa.sun_path))
	{
		close(fd);
		return -1;
	}
	dp_strlcpy(sa.sun_path, path, sizeof(sa.sun_path));
	struct stat stt;
	if (!lstat(path, &stt) && S_ISSOCK(stt.st_mode))
		unlink(path);
	mode_t old = umask(0177);
	int r = bind(fd, (struct sockaddr *)&sa, sizeof(sa));
	umask(old);
	if (r < 0 || listen(fd, 1) < 0)
	{
		close(fd);
		return -1;
	}
	chmod(path, 0600);
	nonblock(fd);
	return fd;
}
#endif

// The QuakeC renderer's Copy: the menu VM sets _uib_clipboard to the text (a
// cvar_set, so the text is never parsed as a command) and the engine puts it on
// the system clipboard. _uib_clipboard_ok says whether this build can.
static cvar_t uib_clipboard = {CF_CLIENT, "_uib_clipboard", "", "set by the menu: text to put on the system clipboard"};
static cvar_t uib_clipboard_ok = {CF_CLIENT | CF_READONLY, "_uib_clipboard_ok", "0", "1 when this build can write the system clipboard"};
static void UIB_Clipboard_c(cvar_t *var)
{
#ifdef UIB_SDL_WINDOW
	if (var->string[0])
		SDL_SetClipboardText(var->string);
#else
	(void)var;
#endif
}

static void TouchUIB_WindowStatus_f(cmd_state_t *cmd);

void UIBStream_Init(void)
{
	Cmd_AddCommand(CF_CLIENT, "uib_window", TouchUIB_WindowStatus_f, "print the UI bridge back end's window state: the game window shown or hidden (or inside the app's window), the menu, the app window's focus and visibility, and whether the sound is muted (tests)");
	Cvar_RegisterVariable(&uib_clipboard);
	Cvar_RegisterCallback(&uib_clipboard, UIB_Clipboard_c);
	Cvar_RegisterVariable(&uib_clipboard_ok);
#ifdef UIB_SDL_WINDOW
	Cvar_SetQuick(&uib_clipboard_ok, "1");
#endif
	sm.menu_published = -1;
	int i = Sys_CheckParm("-uibridge");
	if (!i)
		return;
	const char *spec = i + 1 < sys.argc ? sys.argv[i + 1] : "listen";
	sm.start_time = Sys_DirtyTime();
	const char *ep = getenv("GODNOTIC_EMBED_PARENT");
	if (ep && *ep)
		sm.embed_parent = strtoul(ep, NULL, 0);
#ifdef UIB_STREAM
	if (!strncmp(spec, "fd:", 3))
	{
		int fd = atoi(spec + 3);
		if (fd < 3 || fcntl(fd, F_GETFD) < 0)
		{
			Con_Printf("uibridge: -uibridge %s: not an open descriptor\n", spec);
			return;
		}
		fcntl(fd, F_SETFD, FD_CLOEXEC);
		sm.mode = 1;
		attach(fd, fd);
	}
	else if (!strcmp(spec, "stdio"))
	{
		sm.mode = 2;
		sys.outfd = 2;  // console output must not mix with the protocol
		attach(0, 1);
	}
	else if (!strncmp(spec, "listen", 6))
	{
		if (spec[6] == ':' && spec[7])
			dp_strlcpy(sm.path, spec + 7, sizeof(sm.path));
		else
		{
			const char *rt = getenv("XDG_RUNTIME_DIR");
			dpsnprintf(sm.path, sizeof(sm.path), "%s/godnotic/uibridge.sock", rt && *rt ? rt : "/tmp");
		}
		sm.lfd = open_listen(sm.path);
		if (sm.lfd < 0)
		{
			Con_Printf("uibridge: cannot listen on %s: %s\n", sm.path, strerror(errno));
			return;
		}
		sm.mode = 3;
		sm.lost_at = Sys_DirtyTime();
		Con_Printf("uibridge: listening on %s\n", sm.path);
	}
	else
		Con_Printf("uibridge: unknown -uibridge %s (fd:<n>, stdio, listen[:<path>])\n", spec);
#else
	Con_Printf("uibridge: the stream transport is not available on this platform\n");
#endif
}

// The game's menu is open (key_dest): over a match that is the pause menu or a dialog
// of it (the join and statistics questions), which the front end shows; false while
// the match has the input. Published as app.menu in back-end mode only.
static int menu_open(void)
{
#ifdef CONFIG_MENU
	return key_dest == key_menu || key_dest == key_menu_grabbed;
#else
	return 0;
#endif
}

static void publish_menu(void)
{
	if (!sm.mode || cls.state == ca_dedicated)
		return;
	int m = menu_open();
	if (m == sm.menu_published)
		return;
	uibnode_t *app = UIBS_Find("app");
	if (!app)
		return;
	UIBS_SetProp(app, "menu", m ? "true" : "false", m ? 4 : 5);
	sm.menu_published = m;
}

// The front end's window, from its caps: focused (the active window) and visible
// (mapped, not minimised). Unknown counts as yes (an older front end).
static int caps_flag(const char *key)
{
	const char *caps = UIB_LastCaps();
	char out[16];
	if (!caps || !*caps || !UIBJ_PathText(caps, strlen(caps), key, out, sizeof(out)))
		return 1;
	return strcmp(out, "false") && strcmp(out, "0");
}

#ifdef UIB_X11_EMBED
// libX11 is SDL's (already loaded when SDL runs on X11): looked up, not linked.
static struct
{
	int tried, ok;
	int (*reparent)(Display *, Window, Window, int, int);
	int (*resize)(Display *, Window, unsigned, unsigned);
	int (*raise)(Display *, Window);
	int (*focus)(Display *, Window, int, Time);
	int (*geometry)(Display *, Drawable, Window *, int *, int *, unsigned *, unsigned *, unsigned *, unsigned *);
	int (*sync)(Display *, Bool);
	int (*flush)(Display *);
	XErrorHandler (*handler)(XErrorHandler);
} x11;

static int x11_load(void)
{
	if (x11.tried)
		return x11.ok;
	x11.tried = 1;
	void *h = dlopen("libX11.so.6", RTLD_NOW | RTLD_GLOBAL);
	if (!h)
		return 0;
	*(void **)&x11.reparent = dlsym(h, "XReparentWindow");
	*(void **)&x11.resize = dlsym(h, "XResizeWindow");
	*(void **)&x11.raise = dlsym(h, "XRaiseWindow");
	*(void **)&x11.focus = dlsym(h, "XSetInputFocus");
	*(void **)&x11.geometry = dlsym(h, "XGetGeometry");
	*(void **)&x11.sync = dlsym(h, "XSync");
	*(void **)&x11.flush = dlsym(h, "XFlush");
	*(void **)&x11.handler = dlsym(h, "XSetErrorHandler");
	x11.ok = x11.reparent && x11.resize && x11.raise && x11.focus && x11.geometry && x11.sync && x11.flush && x11.handler;
	return x11.ok;
}

static int x11_failed;
static int x11_quiet(Display *d, XErrorEvent *e) { (void)d; (void)e; x11_failed = 1; return 0; }

static int x11_info(SDL_Window *w, SDL_SysWMinfo *info)
{
	SDL_VERSION(&info->version);
	return SDL_GetWindowWMInfo(w, info) && info->subsystem == SDL_SYSWM_X11 && x11_load();
}

// Puts the game window inside the app's window (once per window: vid_restart makes a new one).
static void embed(SDL_Window *w)
{
	SDL_SysWMinfo info;
	if (!sm.embed_parent || !x11_info(w, &info) || sm.embedded == (unsigned long)info.info.x11.window)
		return;
	Display *d = info.info.x11.display;
	x11_failed = 0;
	XErrorHandler old = x11.handler(x11_quiet);
	x11.reparent(d, info.info.x11.window, (Window)sm.embed_parent, 0, 0);
	x11.sync(d, False);
	x11.handler(old);
	if (x11_failed)
	{
		Con_Printf("uibridge: could not put the game window inside the app's window 0x%lx; it stays a window of its own\n", sm.embed_parent);
		sm.embed_parent = 0;
		return;
	}
	sm.embedded = (unsigned long)info.info.x11.window;
	sm.embed_next = 0;
	Con_DPrintf("uibridge: game window 0x%lx inside the app's window 0x%lx\n", sm.embedded, sm.embed_parent);
}

// Keeps the game window at the app window's size; a few times a second.
static void embed_fit(SDL_Window *w)
{
	SDL_SysWMinfo info;
	Window root;
	int x, y;
	unsigned pw, ph, bw, depth, cw, ch;
	if (!sm.embedded || Sys_DirtyTime() < sm.embed_next || !x11_info(w, &info))
		return;
	sm.embed_next = Sys_DirtyTime() + 0.25;
	Display *d = info.info.x11.display;
	x11_failed = 0;
	XErrorHandler old = x11.handler(x11_quiet);
	if (x11.geometry(d, (Window)sm.embed_parent, &root, &x, &y, &pw, &ph, &bw, &depth)
	    && x11.geometry(d, info.info.x11.window, &root, &x, &y, &cw, &ch, &bw, &depth)
	    && (pw != cw || ph != ch) && pw > 0 && ph > 0)
		x11.resize(d, info.info.x11.window, pw, ph);
	x11.sync(d, False);
	x11.handler(old);
}

// The game window takes the keys when it comes up inside the app's window (and when
// the app's window becomes active again over a match): the window manager focuses the
// app's top-level, whose own key handler would get them otherwise.
static void embed_focus(SDL_Window *w)
{
	SDL_SysWMinfo info;
	if (!sm.embedded || !x11_info(w, &info))
		return;
	Display *d = info.info.x11.display;
	x11_failed = 0;
	XErrorHandler old = x11.handler(x11_quiet);
	x11.raise(d, info.info.x11.window);
	x11.focus(d, info.info.x11.window, RevertToParent, CurrentTime);
	x11.sync(d, False);
	x11.handler(old);
}
#endif

#ifdef UIB_X11_EMBED
static int fe_focused_prev = -1;
#endif

static void window_policy(void)
{
	publish_menu();
#ifdef UIB_SDL_WINDOW
	if (!sm.mode)
		return;
	uibnode_t *app = UIBS_Find("app");
	size_t l;
	const char *ctx = app ? UIBS_PropJSON(app, "context", &l) : NULL;
	int want_hidden = sm.conn && (!ctx || strncmp(ctx, "\"game\"", 6) || menu_open());
	SDL_Window *w = SDL_GL_GetCurrentWindow();
	if (!w)
		return;
#ifdef UIB_X11_EMBED
	embed(w);
	embed_fit(w);
	{
		int f = caps_flag("focused");
		if (f && fe_focused_prev == 0 && !sm.window_hidden)
			embed_focus(w); // back to the app over a running match
		fe_focused_prev = f;
	}
#endif
	if (want_hidden == sm.window_hidden)
		return;
	if (want_hidden)
		SDL_HideWindow(w);
	else
	{
		SDL_ShowWindow(w);
		SDL_RaiseWindow(w);
#ifdef UIB_X11_EMBED
		embed_focus(w);
#endif
	}
	Con_DPrintf("uibridge: game window %s%s\n", want_hidden ? "hidden" : "shown", sm.embedded ? " (inside the app's window)" : "");
	sm.window_hidden = want_hidden;
#endif
}

// SDL creates the game window hidden in back-end mode: the bridge shows it when a
// match takes the screen, so nothing flashes up at start (vid_sdl.c hook).
unsigned UIBStream_WindowFlags(void)
{
#ifdef UIB_SDL_WINDOW
	return Sys_CheckParm("-uibridge") ? SDL_WINDOW_HIDDEN : 0;
#else
	return 0;
#endif
}

// After the window events (vid_sdl.c hook): whenever the game window is not a
// top-level of its own on screen (hidden behind the app, or inside the app's
// window), its focus and visibility are the app window's. vid_activewindow then
// mutes the sound (snd_mutewhenidle) and lowers the frame rate (cl_maxidlefps) when
// the app is in the background; vid_hidden stops drawing while nothing shows it.
void UIBStream_WindowState(void)
{
#ifdef UIB_SDL_WINDOW
	if (!sm.mode || !sm.conn)
		return;
	if (sm.window_hidden)
	{
		// behind the app (its pages show): nothing to draw, the idle frame rate
		vid_hidden = true;
		vid_activewindow = false;
		return;
	}
	if (!sm.embedded)
		return; // the game's own top-level window: SDL's own focus and visibility
	// inside the app's window, over its pages: the keys are the game window's own (SDL's
	// focus) or the app's (its top-level is focused); either way the app is in front
	int visible = caps_flag("visible");
	if (!visible)
		vid_hidden = true;
	vid_activewindow = visible && (vid_activewindow || caps_flag("focused"));
#endif
}

// The sound follows the window the player sees: the app's while the game window is
// hidden behind its pages (snd_main.c hook, after snd_mutewhenidle). The menu's music
// plays while the app's window is the active one and stops with it; over a match,
// UIBStream_WindowState has already folded the app's focus into vid_activewindow.
static int own_window(void)
{
	// shown, as a top-level of its own or inside the app's window: vid_activewindow says it
	return !sm.mode || !sm.conn || !sm.window_hidden;
}

int UIBStream_Muted(int blocked)
{
	if (own_window() || cls.state == ca_dedicated)
		return blocked;
	int mute = (int)Cvar_VariableValue(&cvars_all, "snd_mutewhenidle", ~0u);
	int visible = caps_flag("visible");
	return (mute == 1 && !(visible && caps_flag("focused"))) || (mute == 2 && !visible) || cls.timedemo;
}

static void TouchUIB_WindowStatus_f(cmd_state_t *cmd)
{
	(void)cmd;
	int mute = (int)Cvar_VariableValue(&cvars_all, "snd_mutewhenidle", ~0u);
	int blocked = UIBStream_Muted((mute == 1 && !vid_activewindow) || (mute == 2 && vid_hidden) || cls.timedemo);
	Con_Printf("uib window: back end %d, game window %s%s, menu %d, app focused %d visible %d, vid_hidden %d, vid_activewindow %d, sound %s\n",
		sm.mode, sm.window_hidden ? "hidden" : "shown", sm.embedded ? " (embedded)" : "", menu_open(),
		caps_flag("focused"), caps_flag("visible"), (int)vid_hidden, (int)vid_activewindow, blocked ? "muted" : "on");
}

void UIBStream_Frame(void)
{
#ifdef UIB_STREAM
	if (!sm.mode)
		return;
	if (sm.mode == 3 && !sm.conn && sm.lfd >= 0)
	{
		int c = accept(sm.lfd, NULL, NULL);
		if (c >= 0)
		{
			fcntl(c, F_SETFD, FD_CLOEXEC);
			attach(c, c);
			Con_DPrintf("uibridge: front end connected\n");
		}
		else if (Sys_DirtyTime() - sm.lost_at > 10.0)
		{
			Con_Printf("uibridge: no front end for 10 s, quitting\n");
			sm.lost_at = 1e30;
			Cbuf_AddText(cmd_local, "\nquit\n");
		}
	}
	if (sm.conn)
	{
		read_input();
		if (sm.conn)
			write_output();
	}
	if (!sm.first_hello_logged && sm.conn && UIB_ConnCount())
	{
		sm.first_hello_logged = 1;
		Con_DPrintf("uibridge: first description sent %.0f ms after the transport started\n", (Sys_DirtyTime() - sm.start_time) * 1000.0);
	}
#endif
	window_policy();
}

void UIBStream_Shutdown(void)
{
#ifdef UIB_STREAM
	if (sm.conn)
	{
		write_output();
		TouchUIB_Close(sm.conn);
		sm.conn = NULL;
	}
	if (sm.lfd >= 0)
	{
		close(sm.lfd);
		unlink(sm.path);
	}
#endif
	sm.mode = 0;
}
