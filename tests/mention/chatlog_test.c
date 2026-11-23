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
			CHECK(ix + 20 <= 310.001f, "icon %d inside the box's right end: %g", k, ix);
			if (k)
				CHECK(prev - (ix + 20) >= 20 * 0.39f && prev - (ix + 20) <= 20 * 0.41f, "even gap before icon %d: %g", k, prev - (ix + 20));
			CHECK(TouchChatLog_InIcon(ix + 1, 5, ix, 0, 20), "hit test icon %d", k);
			prev = ix;
		}
		CHECK(TouchChatLog_IconX(10, 300, 20, 0) == 290, "share icon at the box's right edge");
		CHECK(!TouchChatLog_InIcon(TouchChatLog_IconX(10, 300, 20, 0) - 3, 5, TouchChatLog_IconX(10, 300, 20, 0), 0, 20), "the gap hits no icon");
	}
}

int main(void)
{
	test_ring();
	test_fade();
	test_box();
	test_export();
	test_clear();
	if (failures) { printf("%d failure(s)\n", failures); return 1; }
	printf("chatlog: ok\n");
	return 0;
}
