// Chat log ring buffer, events, fade, scroll box, scrolling, virtual window, hit targets, icons and export (engine/darkplaces/touch_chatlog_logic.h).
#include <stdio.h>
#include <stdlib.h>
#include "touch_chatlog_logic.h"

static int failures;
#define CHECK(cond, ...) do { if (!(cond)) { failures++; printf("FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)

static tcl_log_t L;

static void test_ring(void)
{
	char s[32];
	int i;
	memset(&L, 0, sizeof(L));
	CHECK(!TouchChatLog_Add(&L, 0, 0, "\n"), "empty line refused");
	CHECK(!TouchChatLog_Add(&L, 0, 0, "^3  ^7\n"), "colour codes only refused");
	CHECK(TouchChatLog_Add(&L, 0, 0, "\x01^3Bob^7: hi\n"), "chat line");
	CHECK(!strcmp(TouchChatLog_Get(&L, 0)->text, "^3Bob^7: hi"), "marker and newline dropped: '%s'", TouchChatLog_Get(&L, 0)->text);
	for (i = 0; i < TCL_CAP + 10; i++)
	{
		snprintf(s, sizeof(s), "line %d", i);
		TouchChatLog_Add(&L, i, 0, s);
	}
	CHECK(L.count == TCL_CAP, "capped at %d, have %u", TCL_CAP, L.count);
	CHECK(!strcmp(TouchChatLog_Get(&L, 0)->text, "line 10"), "oldest dropped: '%s'", TouchChatLog_Get(&L, 0)->text);
	snprintf(s, sizeof(s), "line %d", TCL_CAP + 9);
	CHECK(!strcmp(TouchChatLog_Get(&L, TCL_CAP - 1)->text, s), "newest kept");
	CHECK(TouchChatLog_Get(&L, TCL_CAP) == NULL, "out of range");
}

static void test_fade(void)
{
	memset(&L, 0, sizeof(L));
	TouchChatLog_Add(&L, 0, 0, "old");
	TouchChatLog_Add(&L, 50, 0, "mid");
	TouchChatLog_Add(&L, 100, 0, "new");
	CHECK(TouchChatLog_Visible(&L, 111, 60) == 1, "only the newest is under a minute old");
	CHECK(TouchChatLog_Visible(&L, 105, 60) == 2, "two at t=105");
	CHECK(TouchChatLog_Visible(&L, 500, 60) == 0, "all faded");
	CHECK(L.count == 3, "fading removes nothing from the log");
}

static void test_box(void)
{
	CHECK(TouchChatLog_BoxLines(0, 2, 8) == 2, "keeps a minimum when every line has faded");
	CHECK(TouchChatLog_BoxLines(5, 2, 8) == 5, "grows with the lines shown");
	CHECK(TouchChatLog_BoxLines(30, 2, 8) == 8, "up to a maximum");
	CHECK(TouchChatLog_BoxLines(0, 3, 1) == 3, "max below min: min wins");
	memset(&L, 0, sizeof(L));
	{
		int i;
		for (i = 0; i < 20; i++) TouchChatLog_Add(&L, i, 0, "x");
	}
	CHECK(TouchChatLog_ClampScroll(&L, -4, 5) == 0, "not below the newest");
	CHECK(TouchChatLog_ClampScroll(&L, 99, 5) == 15, "not past the oldest: box shows 5 of 20");
	CHECK(TouchChatLog_ClampScroll(&L, 7, 5) == 7, "inside");
	memset(&L, 0, sizeof(L));
	CHECK(TouchChatLog_ClampScroll(&L, 3, 5) == 0, "short log does not scroll");
}

static void test_export(void)
{
	char out[512];
	struct tm t = { 0 };
	time_t w;
	size_t n;
	setenv("TZ", "UTC", 1);
	tzset();
	memset(&L, 0, sizeof(L));
	t.tm_year = 126; t.tm_mon = 10; t.tm_mday = 23; t.tm_hour = 12; t.tm_min = 5; t.tm_sec = 9;
	w = timegm(&t);
	TouchChatLog_Add(&L, 1, w, "\x01^1Al^7: hello @bob");
	TouchChatLog_Add(&L, 2, w + 61, "\x03^4Carol^7 is now spectating");
	n = TouchChatLog_Export(&L, out, sizeof(out));
	CHECK(!strcmp(out, "[12:05:09] Al: hello @bob\n[12:06:10] Carol moved to spectators\n"), "export:\n%s", out);
	CHECK(n == strlen(out), "length");
	n = TouchChatLog_Export(&L, out, 40);
	CHECK(!strcmp(out, "[12:05:09] Al: hello @bob\n") && n == strlen(out), "cut at a line boundary: '%s'", out);
	CHECK(TouchChatLog_Export(&L, out, 1) == 0 && !out[0], "tiny buffer");
}

static void test_clear(void)
{
	char out[256];
	int i;
	memset(&L, 0, sizeof(L));
	for (i = 0; i < TCL_CAP + 5; i++) /* wrapped ring */
		TouchChatLog_Add(&L, i, 1000 + i, "some line");
	CHECK(L.count == TCL_CAP, "full before the clear");
	TouchChatLog_Clear(&L);
	CHECK(L.count == 0 && L.head == 0, "emptied: count %u head %u", L.count, L.head);
	CHECK(TouchChatLog_Get(&L, 0) == NULL, "no line comes back through Get");
	CHECK(TouchChatLog_Visible(&L, 10, 60) == 0, "none on show");
	CHECK(TouchChatLog_Export(&L, out, sizeof(out)) == 0 && !out[0], "nothing to export");
	CHECK(TouchChatLog_ClampScroll(&L, 9, 5) == 0, "scroll resets to the live feed");
	TouchChatLog_Add(&L, 20, 0, "after");
	CHECK(L.count == 1 && !strcmp(TouchChatLog_Get(&L, 0)->text, "after"), "first line after the clear is the only one");
	TouchChatLog_Clear(&L);
	TouchChatLog_Clear(&L);
	CHECK(L.count == 0, "clearing an empty log is harmless");

	/* stock lines older than the clear stay hidden, newer ones show */
	CHECK(TouchChatLog_FadeAfterClear(60, 100, -1) == 60, "never cleared: stock window");
	{
		float w = TouchChatLog_FadeAfterClear(60, 110, 100);
		CHECK(w > 9.9f && w < 10.0f, "window %.4f is the time since the clear, a hair less", w);
		CHECK(100.0 < 110 - (double)w, "a line stamped at the clear is outside the window");
		CHECK(105.0 > 110 - (double)w, "a line after the clear is inside it");
	}
	CHECK(TouchChatLog_FadeAfterClear(5, 400, 100) == 5, "stock window wins once it is the smaller");
	CHECK(TouchChatLog_FadeAfterClear(60, 100, 100) > 0, "never 0 (0 means no limit to the stock box)");
	CHECK(TouchChatLog_FadeAfterClear(0, 120, 100) > 19 && TouchChatLog_FadeAfterClear(0, 120, 100) < 20, "stock 0 (unlimited) is still cut at the clear");
	CHECK(TouchChatLog_FadeAfterClear(60, 5, 100) == 60, "clock restarted on a new map: the clear no longer applies");

	/* icons: one size, evenly spaced right to left, a clear gap, none past the box's right edge */
	{
		int k;
		float prev = 1e9f;
		for (k = 0; k < TCL_ICONS; k++)
		{
			float ix = TouchChatLog_IconX(10, 300, 20, k);
			CHECK(ix >= 9.999f, "icon %d starts at the box's left edge or right of it: %g", k, ix);
			if (k)
				CHECK(prev - (ix + 20) >= 20 * 0.39f && prev - (ix + 20) <= 20 * 0.41f, "even gap before icon %d: %g", k, prev - (ix + 20));
			CHECK(TouchChatLog_InIcon(ix + 1, 5, ix, 0, 20), "hit test icon %d", k);
			prev = ix;
		}
		CHECK(TouchChatLog_IconX(10, 300, 20, TCL_ICONS - 1) == 10, "the log icon at the box's left edge (the chat text's x)");
		CHECK(!TouchChatLog_InIcon(TouchChatLog_IconX(10, 300, 20, 0) - 3, 5, TouchChatLog_IconX(10, 300, 20, 0), 0, 20), "the gap hits no icon");
	}
}


/* the first line of a printed chat-box line, as console.c hands it to the log: "^7^3" then the game's text */
static const tcl_entry_t *add3(const char *line)
{
	memset(&L, 0, sizeof(L));
	CHECK(TouchChatLog_AddLead(&L, 1, 1000, 3, line), "added '%s'", line);
	return TouchChatLog_Get(&L, 0);
}

static void plain_of(const tcl_entry_t *e, char *out, size_t n)
{
	TouchMention_StripColors(e->text, out, n);
}

static void test_events(void)
{
	char p[TCL_LEN];
	const tcl_entry_t *e;

	e = add3("^7^3^1Bob^3 connected");
	plain_of(e, p, sizeof(p));
	CHECK(e->kind == TCL_JOIN && !strcmp(e->name, "Bob") && !strcmp(p, "Bob joined the server"), "join: kind %d name '%s' text '%s'", e->kind, e->name, p);
	CHECK(strstr(e->text, "^1Bob"), "the name keeps its colour: '%s'", e->text);
	CHECK(strstr(e->text, TouchChatLog_KindColor(TCL_JOIN)), "the verb has the join colour");

	e = add3("^7^3^x8f8Mr Big^3 is now playing");
	plain_of(e, p, sizeof(p));
	CHECK(e->kind == TCL_PLAY && !strcmp(e->name, "Mr Big") && !strcmp(p, "Mr Big started playing"), "play: kind %d name '%s' text '%s'", e->kind, e->name, p);

	e = add3("^7^3Bob^3 is now playing on the ^1Red^3 team");
	plain_of(e, p, sizeof(p));
	CHECK(e->kind == TCL_PLAY && !strcmp(p, "Bob joined the Red team"), "joined a team: kind %d '%s'", e->kind, p);
	CHECK(strstr(e->text, "^1Red"), "the team keeps its colour: '%s'", e->text);

	e = add3("^7^3Bob^3 switched to the ^4Blue^3 team");
	plain_of(e, p, sizeof(p));
	CHECK(e->kind == TCL_PLAY && !strcmp(p, "Bob switched to the Blue team"), "team change: kind %d '%s'", e->kind, p);

	e = add3("^7^3Bob^3 is now^7 spectating");
	plain_of(e, p, sizeof(p));
	CHECK(e->kind == TCL_SPEC && !strcmp(e->name, "Bob") && !strcmp(p, "Bob moved to spectators"), "spectate: kind %d name '%s' text '%s'", e->kind, e->name, p);

	e = add3("^7^3Bob^3 disconnected\n");
	plain_of(e, p, sizeof(p));
	CHECK(e->kind == TCL_LEAVE && !strcmp(e->name, "Bob") && !strcmp(p, "Bob left the server"), "leave: kind %d '%s'", e->kind, p);

	e = add3("^7^3Bob^3 changed name to ^2Rob");
	plain_of(e, p, sizeof(p));
	CHECK(e->kind == TCL_RENAME && !strcmp(e->name, "Rob") && !strcmp(p, "Bob is now known as Rob"), "the server's rename line: kind %d name '%s' '%s'", e->kind, e->name, p);

	e = add3("^7^3Bob^3 left the game (Disconnect by user)");
	plain_of(e, p, sizeof(p));
	CHECK(e->kind == TCL_LEAVE && !strcmp(p, "Bob left the server (Disconnect by user)"), "left with a reason: '%s'", p);
	/* ... and the server's second line about the same leaving is not a second event */
	CHECK(!TouchChatLog_AddLead(&L, 1.5, 1000, 3, "^7^3Bob^3 disconnected\n"), "a duplicate leave within 3 s is dropped");
	CHECK(TouchChatLog_AddLead(&L, 9, 1000, 3, "^7^3Bob^3 disconnected\n"), "but the same player leaving much later is a new event");
	TouchChatLog_AddLead(&L, 10, 1000, 3, "^7^3Bob^3 connected\n");
	CHECK(TouchChatLog_AddLead(&L, 10.5, 1000, 3, "^7^3Bob^3 is now playing\n"), "play right after join is kept");
	CHECK(TouchChatLog_AddLead(&L, 11, 1000, 3, "^7^3Bob^3 is now spectating\n") && TouchChatLog_AddLead(&L, 11.5, 1000, 3, "^7^3Bob^3 is now playing\n"), "play, spectate, play again within seconds: all three are kept");

	e = add3("^7^3Bob^3 was kicked after idling for 60 seconds");
	plain_of(e, p, sizeof(p));
	CHECK(e->kind == TCL_LEAVE && !strcmp(p, "Bob was kicked after idling for 60 seconds"), "kick keeps its reason: '%s'", p);

	e = add3("^7^3Bob^3 was forced to spectate for excessive teamkilling");
	CHECK(e->kind == TCL_SPEC, "forced to spectate is a spectator event: %d", e->kind);

	e = add3("^7^3connected^3 connected");
	CHECK(e->kind == TCL_JOIN && !strcmp(e->name, "connected"), "a player called 'connected': name '%s'", e->name);

	e = add3("^7^3Bob killed Al with the Vortex");
	CHECK(e->kind == TCL_NOTE && !e->name[0] && strstr(e->text, "killed"), "an unknown game line is a note, kept as printed: kind %d '%s'", e->kind, e->text);

	/* a player's words are never an event, whatever they say */
	memset(&L, 0, sizeof(L));
	TouchChatLog_AddLead(&L, 1, 1000, 1, "^7^3^7Bob^7: Al just connected");
	e = TouchChatLog_Get(&L, 0);
	CHECK(e->kind == TCL_CHAT && !strcmp(e->name, "Bob") && strstr(e->text, "Al just connected"), "chat stays chat: kind %d name '%s'", e->kind, e->name);
	TouchChatLog_AddLead(&L, 1, 1000, 1, "^7^3^7Bob^7: Al is now spectating");
	CHECK(TouchChatLog_Get(&L, 1)->kind == TCL_CHAT, "chat that sounds like an event stays chat");
	TouchChatLog_AddLead(&L, 1, 1000, 1, "^7^3no colon here");
	CHECK(TouchChatLog_Get(&L, 2)->kind == TCL_CHAT && !TouchChatLog_Get(&L, 2)->name[0], "no speaker found: empty name");

	/* a rename is worked out by the client */
	memset(&L, 0, sizeof(L));
	CHECK(TouchChatLog_AddRename(&L, 1, 1000, "^1Old", "^2New"), "rename added");
	e = TouchChatLog_Get(&L, 0);
	plain_of(e, p, sizeof(p));
	CHECK(e->kind == TCL_RENAME && !strcmp(e->name, "New") && !strcmp(p, "Old is now known as New"), "rename: kind %d name '%s' text '%s'", e->kind, e->name, p);

	/* every kind has its own icon */
	{
		int k, j;
		for (k = 0; k < TCL_NKINDS; k++)
			for (j = k + 1; j < TCL_NKINDS; j++)
				CHECK(TouchChatLog_KindIcon(k) != TouchChatLog_KindIcon(j), "kinds %d and %d share an icon", k, j);
	}
}

static void test_feed(void)
{
	unsigned idx[16];
	unsigned n;
	memset(&L, 0, sizeof(L));
	TouchChatLog_AddLead(&L, 0, 0, 1, "^7^3^7A^7: old chat");
	TouchChatLog_AddLead(&L, 50, 0, 3, "^7^3B^3 connected");
	TouchChatLog_AddLead(&L, 60, 0, 3, "^7^3B killed C");
	TouchChatLog_AddLead(&L, 70, 0, 1, "^7^3^7C^7: new chat");
	n = TouchChatLog_FeedLines(&L, TCL_FEED, 75, 60, idx, 16);
	CHECK(n == 2 && idx[0] == 3 && idx[1] == 1, "chat and events within a minute, the game's other lines left out, newest first: n=%u", n);
	n = TouchChatLog_FeedLines(&L, TCL_ALL, 75, 60, idx, 16);
	CHECK(n == 3, "with the game's own lines on: %u", n);
	n = TouchChatLog_FeedLines(&L, TCL_FEED, 75, 60, idx, 1);
	CHECK(n == 1 && idx[0] == 3, "capped at max");
	n = TouchChatLog_FeedLines(&L, TCL_FEED, 500, 60, idx, 16);
	CHECK(n == 0 && L.count == 4, "everything faded from the feed, nothing left the log");
}

/* retention: the log is the session's history, not the feed's */
static void test_retention(void)
{
	int i;
	char s[64];
	memset(&L, 0, sizeof(L));
	for (i = 0; i < 300; i++)
	{
		snprintf(s, sizeof(s), "^7^3P%d^3 connected", i);
		TouchChatLog_AddLead(&L, i, 1000 + i, 3, s);
	}
	CHECK(L.count == 300, "a long session keeps every line: %u", L.count);
	CHECK(TouchChatLog_Visible(&L, 100000, 60) == 0, "hours later none is on the feed");
	CHECK(!strcmp(TouchChatLog_Get(&L, 0)->name, "P0") && !strcmp(TouchChatLog_Get(&L, 299)->name, "P299"), "all of them still there, oldest first");
	for (i = 300; i < TCL_CAP + 40; i++)
	{
		snprintf(s, sizeof(s), "^7^3P%d^3 connected", i);
		TouchChatLog_AddLead(&L, i, 1000 + i, 3, s);
	}
	CHECK(L.count == TCL_CAP, "capped at %d", TCL_CAP);
	snprintf(s, sizeof(s), "P%d", 40);
	CHECK(!strcmp(TouchChatLog_Get(&L, 0)->name, s), "the oldest go first: '%s'", TouchChatLog_Get(&L, 0)->name);
	{
		static char big[TCL_CAP * (TCL_LEN + 16)];
		size_t n = TouchChatLog_Export(&L, big, sizeof(big));
		CHECK(n > 0 && strstr(big, "P40 joined the server\n") && strstr(big, "joined the server\n"), "the export carries the events as text");
		CHECK(strchr(big, '[') == big && big[9] == ']', "with a time on each line: '%.12s'", big);
	}
}

/* ---- scrolling ---- */

static int measured;
static int measure_two_rows_when_long(void *ctx, tcl_entry_t *e)
{
	(void)ctx;
	if (e->rows)
		return e->rows; /* cached */
	measured++;
	return strlen(e->text) > 40 ? 2 : 1;
}

static void fill(int n)
{
	int i;
	char s[96];
	memset(&L, 0, sizeof(L));
	for (i = 0; i < n; i++)
	{
		if (i % 5 == 0)
			snprintf(s, sizeof(s), "^7^3^7P%d^7: a longer message that has to wrap onto two rows %d", i, i);
		else
			snprintf(s, sizeof(s), "^7^3^7P%d^7: hi %d", i, i);
		TouchChatLog_AddLead(&L, i, 1000 + i, 1, s);
	}
}

static void test_scroll(void)
{
	tcl_scroller_t sc;
	float max;
	int i;
	memset(&sc, 0, sizeof(sc));
	max = 1000;

	/* wheel: up is older, clamped at both ends */
	TouchChatLog_ScrollWheel(&sc, 3, 20, max);
	CHECK(sc.pos == 60, "wheel up 3 notches of 20: %g", sc.pos);
	TouchChatLog_ScrollWheel(&sc, -5, 20, max);
	CHECK(sc.pos == 0, "wheel down stops at the newest line: %g", sc.pos);
	TouchChatLog_ScrollWheel(&sc, 1000, 20, max);
	CHECK(sc.pos == max, "wheel up stops at the oldest: %g", sc.pos);

	/* a drag: the content follows the finger, after the slop, without a jump */
	memset(&sc, 0, sizeof(sc));
	TouchChatLog_ScrollDown(&sc, 500, 10.0);
	CHECK(!TouchChatLog_ScrollMove(&sc, 503, 10.01, max, 8) && sc.pos == 0, "inside the slop it is still a tap");
	CHECK(TouchChatLog_ScrollMove(&sc, 540, 10.05, max, 8) && sc.pos == 40, "40 down from the touch-down brings 40 units of older lines: %g", sc.pos);
	TouchChatLog_ScrollMove(&sc, 520, 10.10, max, 8);
	CHECK(sc.pos == 20, "back up 20: %g", sc.pos);
	TouchChatLog_ScrollMove(&sc, -5000, 10.15, max, 8);
	CHECK(sc.pos == 0, "dragged up past the newest line: clamped: %g", sc.pos);
	TouchChatLog_ScrollMove(&sc, 9000, 10.20, max, 8);
	CHECK(sc.pos == max, "dragged past the oldest: clamped: %g", sc.pos);
	CHECK(!TouchChatLog_ScrollUp(&sc, 10.5), "lifting after a drag is not a tap");

	/* a tap */
	memset(&sc, 0, sizeof(sc));
	TouchChatLog_ScrollDown(&sc, 100, 1.0);
	TouchChatLog_ScrollMove(&sc, 102, 1.05, max, 8);
	CHECK(TouchChatLog_ScrollUp(&sc, 1.1) == 1, "down and up inside the slop is a tap");
	CHECK(sc.vel == 0 && sc.pos == 0, "and moves nothing");

	/* a flick coasts and slows to a stop */
	memset(&sc, 0, sizeof(sc));
	TouchChatLog_ScrollDown(&sc, 100, 1.0);
	for (i = 1; i <= 6; i++)
		TouchChatLog_ScrollMove(&sc, 100 + i * 20, 1.0 + i * 0.016, max, 8); /* 1250 units/s */
	CHECK(sc.vel > 900 && sc.vel < 1500, "flick speed %g", sc.vel);
	TouchChatLog_ScrollUp(&sc, 1.0 + 6 * 0.016 + 0.01);
	{
		float at_release = sc.pos, last = sc.pos;
		int frames = 0, monotone = 1;
		for (i = 0; i < 600 && sc.vel != 0; i++)
		{
			TouchChatLog_ScrollStep(&sc, 1 / 60.0f, max);
			if (sc.pos < last - 0.001f)
				monotone = 0;
			last = sc.pos;
			frames++;
		}
		CHECK(sc.pos > at_release + 100, "it carries on after the finger left: %g -> %g", at_release, sc.pos);
		CHECK(monotone && sc.vel == 0 && frames < 400, "and stops by itself (%d frames), never going back", frames);
	}
	/* a flick that ends against the end of the log stops there */
	memset(&sc, 0, sizeof(sc));
	sc.pos = 10; sc.vel = 3000;
	for (i = 0; i < 200; i++)
		TouchChatLog_ScrollStep(&sc, 1 / 60.0f, 50);
	CHECK(sc.pos == 50 && sc.vel == 0, "coast stops at the oldest line: %g", sc.pos);
	/* a finger stops a coast; holding still before lifting does not fling */
	memset(&sc, 0, sizeof(sc));
	sc.pos = 100; sc.vel = 2000;
	TouchChatLog_ScrollDown(&sc, 0, 5.0);
	CHECK(sc.vel == 0, "touching the list stops the coast");
	TouchChatLog_ScrollMove(&sc, 60, 5.02, max, 8);
	TouchChatLog_ScrollMove(&sc, 80, 5.04, max, 8);
	TouchChatLog_ScrollUp(&sc, 5.6);
	CHECK(sc.vel == 0, "a finger that rested before lifting leaves no fling");
}

static void test_window(void)
{
	tcl_window_t w;
	float rowh = 10, pad = 2, view = 100;
	float content;
	int i;
	fill(1000);
	measured = 0;
	TouchChatLog_Window(&L, TCL_ALL, measure_two_rows_when_long, NULL, rowh, pad, view, 0, &w);
	CHECK(w.n > 0 && w.idx[0] == 999, "newest line first at the live end: n=%d idx0=%u", w.n, w.idx[0]);
	CHECK(measured <= w.n + 1, "only the lines in view were measured: %d measured, %d in view", measured, w.n);
	CHECK(fabsf(w.top[0] + w.h[0] - view) < 0.001f, "the newest line sits on the bottom edge: top %g h %g", w.top[0], w.h[0]);
	for (i = 1; i < w.n; i++)
		CHECK(fabsf(w.top[i] + w.h[i] - w.top[i - 1]) < 0.001f, "lines are contiguous: %d", i);
	CHECK(w.top[w.n - 1] < 0.001f && w.top[w.n - 1] + w.h[w.n - 1] > 0, "the oldest in the window reaches the top edge");

	/* scrolled into the middle: still only what is in view (plus those walked past) */
	measured = 0;
	TouchChatLog_Window(&L, TCL_ALL, measure_two_rows_when_long, NULL, rowh, pad, view, 3000, &w);
	CHECK(w.n >= 5 && w.n < 20, "a window of about %g units holds a handful of lines: %d", view, w.n);
	CHECK(w.idx[0] < 999 && w.idx[0] > 500, "from deep in the log: idx0=%u", w.idx[0]);
	CHECK(measured < 400, "scrolling to 3000 measured %d lines, not all 1000", measured);
	CHECK(w.top[0] < view && w.top[0] + w.h[0] > 0, "every line in the window overlaps the view");

	/* the end of the content: scrolled to max shows the oldest line */
	/* (lines never seen count one row, so dragging to the end re-measures: a few frames settle it) */
	for (i = 0; i < 40; i++)
	{
		content = TouchChatLog_Content(&L, TCL_ALL, rowh, pad);
		TouchChatLog_Window(&L, TCL_ALL, measure_two_rows_when_long, NULL, rowh, pad, view, content - view, &w);
	}
	CHECK(w.idx[w.n - 1] == 0, "scrolled to the end: the oldest line is in view (idx %u)", w.idx[w.n - 1]);
	/* once measured, the content height counts the wrapped rows (every 5th line is 2 rows) */
	CHECK(TouchChatLog_Content(&L, TCL_ALL, rowh, pad) > 1000 * (rowh + pad) + 100, "content grows with measured wraps: %g", TouchChatLog_Content(&L, TCL_ALL, rowh, pad));

	/* a filter: only chat */
	memset(&L, 0, sizeof(L));
	TouchChatLog_AddLead(&L, 1, 1, 1, "^7^3^7A^7: hi");
	TouchChatLog_AddLead(&L, 2, 1, 3, "^7^3B^3 connected");
	TouchChatLog_AddLead(&L, 3, 1, 1, "^7^3^7C^7: yo");
	TouchChatLog_Window(&L, TCL_BIT(TCL_CHAT), measure_two_rows_when_long, NULL, rowh, pad, view, 0, &w);
	CHECK(w.n == 2 && w.idx[0] == 2 && w.idx[1] == 0, "chat only: %d lines", w.n);
	TouchChatLog_Window(&L, TCL_EVENTS, measure_two_rows_when_long, NULL, rowh, pad, view, 0, &w);
	CHECK(w.n == 1 && w.idx[0] == 1, "events only: %d lines", w.n);
}

static void test_hit(void)
{
	tcl_target_t t[3];
	int k;
	/* three icons 20 wide in a row with 8 between them; a finger needs 44 */
	for (k = 0; k < 3; k++)
	{
		t[k].x = 10 + k * 28;
		t[k].y = 10;
		t[k].w = t[k].h = 20;
		t[k].id = 100 + k;
		t[k].arg = k;
	}
	CHECK(TouchChatLog_HitTarget(t, 3, 20, 20, 0, 0) == 0, "the middle of the first icon");
	CHECK(TouchChatLog_HitTarget(t, 3, 31, 20, 0, 0) == -1, "the gap hits nothing at its own size");
	CHECK(TouchChatLog_HitTarget(t, 3, 31, 20, 44, 44) >= 0, "the gap is inside the 44 unit targets");
	CHECK(TouchChatLog_HitTarget(t, 3, 31, 20, 44, 44) == 0, "nearer the first icon: the first");
	CHECK(TouchChatLog_HitTarget(t, 3, 35, 20, 44, 44) == 1, "nearer the second: the second");
	CHECK(TouchChatLog_HitTarget(t, 3, 20, 20 + 20, 44, 44) == 0, "a finger a little below the icon still hits it");
	CHECK(TouchChatLog_HitTarget(t, 3, 20, 100, 44, 44) == -1, "far below hits nothing");
	CHECK(TouchChatLog_HitTarget(t, 3, 66 + 24, 20, 44, 44) == 2, "right of the last icon within its target");
	CHECK(TouchChatLog_HitTarget(t, 3, 66 + 40, 20, 44, 44) == -1, "past it: nothing");
	CHECK(TouchChatLog_HitTarget(t, 0, 20, 20, 44, 44) == -1, "no targets");
}

static void test_icons(void)
{
	int icon, px;
	static unsigned char buf[96 * 96 * 4];
	for (icon = 0; icon < TCL_I_COUNT; icon++)
		for (px = 24; px <= 96; px += 24)
		{
			long ink = 0, mid = 0, corner = 0;
			int x, y, n = px * px;
			TouchChatLog_IconPixels(icon, px, buf);
			for (y = 0; y < px; y++)
				for (x = 0; x < px; x++)
				{
					int a = buf[(y * px + x) * 4 + 3];
					ink += a;
					if (a > 20 && a < 235)
						mid++;
					if (x < px / 10 && y < px / 10)
						corner += a;
				}
			CHECK(ink > n * 255 / 40, "icon %d at %d px has ink (%ld)", icon, px, ink);
			CHECK(ink < (long)n * 255 * 6 / 10, "icon %d at %d px is not a blob (%ld)", icon, px, ink);
			CHECK(corner == 0, "icon %d at %d px leaves the corner clear", icon, px);
			CHECK(mid < n * 4 / 10 / (px / 24 > 1 ? 1 : 1), "icon %d at %d px: edges are thin (%ld soft pixels of %d)", icon, px, mid, n);
		}
	/* the same icon at two sizes covers the same share of the square (drawn at size, not stretched) */
	{
		static unsigned char a[24 * 24 * 4], b[96 * 96 * 4];
		for (icon = 0; icon < TCL_I_COUNT; icon++)
		{
			double ca = 0, cb = 0;
			int i;
			TouchChatLog_IconPixels(icon, 24, a);
			TouchChatLog_IconPixels(icon, 96, b);
			for (i = 0; i < 24 * 24; i++) ca += a[i * 4 + 3] / 255.0;
			for (i = 0; i < 96 * 96; i++) cb += b[i * 4 + 3] / 255.0;
			ca /= 24 * 24;
			cb /= 96 * 96;
			CHECK(fabs(ca - cb) < 0.04, "icon %d covers %.3f at 24 px and %.3f at 96 px", icon, ca, cb);
		}
	}
}

int main(void)
{
	test_ring();
	test_fade();
	test_box();
	test_export();
	test_clear();
	test_events();
	test_feed();
	test_retention();
	test_scroll();
	test_window();
	test_hit();
	test_icons();
	if (failures) { printf("%d failure(s)\n", failures); return 1; }
	printf("chatlog: ok\n");
	return 0;
}
