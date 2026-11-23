// Chat log ring buffer, fade, scroll box and export (engine/darkplaces/touch_chatlog_logic.h).
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
	CHECK(!strcmp(TouchChatLog_Get(&L, TCL_CAP - 1)->text, "line 265"), "newest kept");
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
	CHECK(!strcmp(out, "[12:05:09] Al: hello @bob\n[12:06:10] Carol is now spectating\n"), "export:\n%s", out);
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
		CHECK(TouchChatLog_IconX(10, 300, 20, 3) == 10, "clear icon at the box's left edge (the chat text's x)");
		CHECK(!TouchChatLog_InIcon(TouchChatLog_IconX(10, 300, 20, 0) - 3, 5, TouchChatLog_IconX(10, 300, 20, 0), 0, 20), "the gap hits no icon");
	}
}

static void fill(int n)
{
	int i;
	char s[16];
	memset(&L, 0, sizeof(L));
	for (i = 0; i < n; i++) { snprintf(s, sizeof(s), "line %d", i); TouchChatLog_Add(&L, 0, 0, s); }
}

static void test_icons_shown(void)
{
	memset(&L, 0, sizeof(L));
	CHECK(!TouchChatLog_IconsShown(&L), "no icons before the first message");
	fill(1);
	CHECK(TouchChatLog_IconsShown(&L), "icons with one line");
	TouchChatLog_Clear(&L);
	CHECK(!TouchChatLog_IconsShown(&L), "no icons after Clear");
	TouchChatLog_Add(&L, 0, 0, "again");
	CHECK(TouchChatLog_IconsShown(&L), "icons come back with the next message");
}

static void test_px_clamp(void)
{
	fill(20);
	CHECK(TouchChatLog_MaxPx(&L, 5, 10.0f) == 150.0f, "max px: 15 lines hidden * 10");
	CHECK(TouchChatLog_ClampPx(&L, -3.5f, 5, 10.0f) == 0, "not below the live state");
	CHECK(TouchChatLog_ClampPx(&L, 999.0f, 5, 10.0f) == 150.0f, "not past the oldest");
	CHECK(TouchChatLog_ClampPx(&L, 37.25f, 5, 10.0f) == 37.25f, "fractional offsets kept");
	fill(3);
	CHECK(TouchChatLog_ClampPx(&L, 40.0f, 5, 10.0f) == 0, "short log does not scroll");
}

static void test_window(void)
{
	tcl_window_t w;
	int m, drawn;
	fill(20);
	// live: the 5 newest lines, 15..19, no partial row
	w = TouchChatLog_Window(L.count, 5, 0, 10.0f);
	CHECK(w.frac == 0 && w.mfirst == 0 && w.mlast == 4 && w.j0 == 15, "live window j0=%d m=%d..%d", w.j0, w.mfirst, w.mlast);
	// 2.5 lines back: a partial row enters at the top, frac 5, at most 6 rows
	w = TouchChatLog_Window(L.count, 5, 25.0f, 10.0f);
	CHECK(w.j0 == 13 && w.mfirst == -1 && w.mlast == 4 && w.frac == 5.0f, "mid window j0=%d m=%d..%d frac=%g", w.j0, w.mfirst, w.mlast, w.frac);
	drawn = w.mlast - w.mfirst + 1;
	CHECK(drawn == 6, "box_lines + 1 rows at most: %d", drawn);
	// the oldest line at the top: nothing below index 0 is asked for
	w = TouchChatLog_Window(L.count, 5, 150.0f, 10.0f);
	CHECK(w.j0 + w.mfirst == 0 && w.j0 + w.mlast == 4, "top end: first line %d last %d", w.j0 + w.mfirst, w.j0 + w.mlast);
	// a long log draws no more rows than fit, however deep it is
	fill(TCL_CAP);
	for (m = 0; m < 3; m++)
	{
		w = TouchChatLog_Window(L.count, 8, 37.0f + (float)m * 100, 12.0f);
		drawn = w.mlast - w.mfirst + 1;
		CHECK(drawn <= 9 && drawn >= 8, "long log draws only the visible rows: %d", drawn);
		CHECK(w.j0 + w.mfirst >= 0 && w.j0 + w.mlast < (int)L.count, "indices inside the log");
	}
	// fewer lines than the box: only the lines that exist
	fill(2);
	w = TouchChatLog_Window(L.count, 5, 0, 10.0f);
	CHECK(w.j0 + w.mfirst == 0 && w.j0 + w.mlast == 1, "short log: lines %d..%d", w.j0 + w.mfirst, w.j0 + w.mlast);
}

static void test_coast(void)
{
	float off = 50, vel = 400;
	int steps = 0;
	while (TouchChatLog_Coast(&off, &vel, 1.0f / 60, 1000.0f) && steps < 1000)
		steps++;
	CHECK(steps > 20 && steps < 400, "coasts a while then stops: %d steps", steps);
	CHECK(off > 50 && off < 1000 && vel == 0, "moved on, then at rest: off=%g", off);
	off = 20; vel = 900;
	while (TouchChatLog_Coast(&off, &vel, 1.0f / 60, 60.0f))
		;
	CHECK(off == 60.0f && vel == 0, "stops at the oldest end: %g", off);
	off = 20; vel = -900;
	while (TouchChatLog_Coast(&off, &vel, 1.0f / 60, 60.0f))
		;
	CHECK(off == 0 && vel == 0, "stops at the live end: %g", off);
	off = 10; vel = 2;
	CHECK(!TouchChatLog_Coast(&off, &vel, 0.016f, 100.0f) && vel == 0, "a crawl is rest");
}

static void test_raster(void)
{
	static unsigned char px[64 * 64 * 4];
	int slot, i, white, solid;
	for (slot = 0; slot < TCL_ICONS; slot++)
	{
		memset(px, 0, sizeof(px));
		TouchChatLog_IconRaster(slot, 64, px);
		white = 0; solid = 0;
		for (i = 0; i < 64 * 64; i++)
		{
			if (px[i * 4] > 200 && px[i * 4 + 3] > 200)
				white++;
			if (px[i * 4 + 3] > 0)
				solid++;
		}
		CHECK(white > 100, "icon %d has a glyph: %d white pixels", slot, white);
		CHECK(solid > 64 * 64 / 2, "icon %d has its plate: %d", slot, solid);
		CHECK(px[3] == 0, "icon %d plate corner is transparent", slot);
	}
	// small sizes still draw something visible
	memset(px, 0, sizeof(px));
	TouchChatLog_IconRaster(1, 16, px);
	white = 0;
	for (i = 0; i < 16 * 16; i++)
		if (px[i * 4] > 128)
			white++;
	CHECK(white > 10, "16px icon still has a glyph: %d", white);
}

static void test_pin(void)
{
	float off = 0;
	int i;
	// pinned: stays pinned however many lines arrive
	for (i = 0; i < 50; i++)
		off = TouchChatLog_PinAdjust(off, 12.0f);
	CHECK(off == 0, "pinned stays at the live state: %g", off);
	// scrolled up: each new line pushes the offset by one line, the view does not move
	off = 30.0f;
	fill(100);
	for (i = 0; i < 5; i++)
	{
		tcl_window_t before = TouchChatLog_Window(L.count, 6, off, 12.0f);
		int first_line = before.j0 + before.mfirst;
		float frac = before.frac;
		TouchChatLog_Add(&L, 0, 0, "new");
		off = TouchChatLog_PinAdjust(off, 12.0f);
		before = TouchChatLog_Window(L.count, 6, off, 12.0f);
		CHECK(before.j0 + before.mfirst == first_line && before.frac == frac, "view did not move: line %d vs %d", before.j0 + before.mfirst, first_line);
	}
	CHECK(off == 30.0f + 60.0f, "offset grew by five lines: %g", off);
	// scrolled back to the bottom: pinned again
	off = TouchChatLog_ClampPx(&L, 0.0f, 6, 12.0f);
	off = TouchChatLog_PinAdjust(off, 12.0f);
	CHECK(off == 0, "back at the bottom, follows the newest");
	// a full ring: the offset never exceeds what the log can scroll
	fill(TCL_CAP + 40);
	off = TouchChatLog_ClampPx(&L, 1e9f, 6, 12.0f);
	off = TouchChatLog_PinAdjust(off, 12.0f);
	off = TouchChatLog_ClampPx(&L, off, 6, 12.0f);
	CHECK(off == TouchChatLog_MaxPx(&L, 6, 12.0f), "clamped at the oldest end: %g", off);
}

static void test_long_history(void)
{
	// 500+ lines: whatever the offset, no more than box_lines + 1 rows are touched
	int off, worst = 0;
	fill(TCL_CAP);
	for (off = 0; off <= (int)TouchChatLog_MaxPx(&L, 8, 12.0f); off += 7)
	{
		tcl_window_t w = TouchChatLog_Window(L.count, 8, (float)off, 12.0f);
		int n = w.mlast - w.mfirst + 1;
		if (n > worst)
			worst = n;
	}
	CHECK(worst <= 9, "rows touched per frame stay at the visible count: %d", worst);
}

int main(void)
{
	test_ring();
	test_fade();
	test_box();
	test_export();
	test_clear();
	test_icons_shown();
	test_px_clamp();
	test_window();
	test_coast();
	test_raster();
	test_pin();
	test_long_history();
	if (failures) { printf("%d failure(s)\n", failures); return 1; }
	printf("chatlog: ok\n");
	return 0;
}
