/*
Hit-testing of the touch weapon strip (engine/darkplaces/touch_strip.h): the
NADE row against the HOOK entry and the weapons, the weapon list holding the
hook, and an old saved layout's hook setting. Run through
scripts/test-touch-strip.sh.
*/
#include <stdio.h>

#include "touch_strip.h"

static int failures, checks;
#define CHECK_EQ(e, a, what) do { int e_ = (e), a_ = (a); checks++; \
	if (e_ != a_) { printf("FAIL %s: expected %d, got %d\n", (what), e_, a_); failures++; } } while (0)

static tstrip_t mk(int n, int hook, int hp, int nade, int np)
{
	tstrip_t s = { n, hook, hp, nade, np, 2.0f, 900.0f, 100.0f, 92.0f };
	return s;
}

/* The strip's hit-test as it was: every row padded on all four sides, the NADE row
   tested first but only while a grenade was ready (touch_hud.c, before touch_strip.h).
   Returns the weapon row hit, or -1. Kept to show what the new rule fixes. */
static int old_hit(int n, float top, float cx, float row_w, float rh, int nade_ready, float px, float py)
{
	float pad = 0.12f * rh, nade_top = top + n * rh;
	int i;
	if (nade_ready && px >= cx - row_w / 2 - pad && px <= cx + row_w / 2 + pad && py >= nade_top - pad && py <= nade_top + rh + pad)
		return 1000;
	for (i = 0; i < n; i++)
	{
		float y = top + i * rh;
		if (px >= cx - row_w / 2 - pad && px <= cx + row_w / 2 + pad && py >= y - pad && py <= y + rh + pad)
			return i;
	}
	return -1;
}

int main(void)
{
	/* the old rule: a waiting NADE row's top edge lands on the last weapon row (the Hook weapon, last) */
	CHECK_EQ(4, old_hit(5, 900.0f, 100.0f, 92.0f, 52.0f, 0, 100.0f, 900.0f + 5 * 52.0f + 2.0f), "old rule: waiting nade top edge hit the row above");
	{
	int row, k;
	/* 5 weapons, HOOK entry, NADE row; u = 2 => rows 52 px */
	tstrip_t s = mk(5, 1, 1, 1, 1);
	float rh = TSTRIP_ROW_H * s.u, hook_top = TouchStrip_HookTop(&s), nade_top = TouchStrip_NadeTop(&s);

	/* every weapon row's centre and its edges hit that row alone */
	for (k = 0; k < 5; k++)
	{
		float y = s.top + k * rh;
		CHECK_EQ(TSTRIP_WEAPON, TouchStrip_Hit(&s, s.cx, y + rh * 0.5f, &row), "weapon centre");
		CHECK_EQ(k, row, "weapon centre row");
		CHECK_EQ(TSTRIP_WEAPON, TouchStrip_Hit(&s, s.cx, y + 0.5f, &row), "weapon top edge");
		CHECK_EQ(k, row, "weapon top edge row");
		TouchStrip_Hit(&s, s.cx, y + rh - 0.5f, &row);
		CHECK_EQ(k, row, "weapon bottom edge row");
	}
	/* the HOOK entry: its whole band, and no pad into the last weapon or the gap */
	CHECK_EQ(TSTRIP_HOOK, TouchStrip_Hit(&s, s.cx, hook_top + 0.5f, NULL), "hook top edge");
	CHECK_EQ(TSTRIP_HOOK, TouchStrip_Hit(&s, s.cx, hook_top + rh * 0.5f, NULL), "hook centre");
	CHECK_EQ(TSTRIP_HOOK, TouchStrip_Hit(&s, s.cx, hook_top + rh - 0.5f, NULL), "hook bottom edge");
	CHECK_EQ(TSTRIP_WEAPON, TouchStrip_Hit(&s, s.cx, hook_top - 0.5f, NULL), "just above hook is the weapon");
	/* the gap takes the finger and does nothing */
	CHECK_EQ(TSTRIP_DEAD, TouchStrip_Hit(&s, s.cx, hook_top + rh + 1.0f, NULL), "gap is dead");
	CHECK_EQ(TSTRIP_DEAD, TouchStrip_Hit(&s, s.cx, nade_top - 0.5f, NULL), "gap bottom is dead");
	/* the NADE row: its whole band is NADE, never HOOK */
	CHECK_EQ(TSTRIP_NADE, TouchStrip_Hit(&s, s.cx, nade_top + 0.5f, NULL), "nade top edge");
	CHECK_EQ(TSTRIP_NADE, TouchStrip_Hit(&s, s.cx, nade_top + TSTRIP_ACT_H * s.u * 0.5f, NULL), "nade centre");
	CHECK_EQ(TSTRIP_NADE, TouchStrip_Hit(&s, s.cx, TouchStrip_GripTop(&s) - 0.5f, NULL), "nade bottom edge");
	CHECK_EQ(TSTRIP_MISS, TouchStrip_Hit(&s, s.cx, TouchStrip_GripTop(&s) + 0.5f, NULL), "grip is not the strip's");
	/* sweep: a finger anywhere in the NADE band never reports HOOK or a weapon */
	for (k = 0; k <= 100; k++)
	{
		float y = nade_top + (TouchStrip_GripTop(&s) - nade_top) * (k / 100.0f * 0.999f);
		int h = TouchStrip_Hit(&s, s.cx, y, NULL);
		CHECK_EQ(TSTRIP_NADE, h, "nade sweep");
	}

	/* the old bug: the NADE row waiting (not pressable), the Hook weapon last in the list.
	   Every finger on it is swallowed, none reaches the HOOK entry or a weapon. */
	s = mk(5, 1, 1, 1, 0);
	nade_top = TouchStrip_NadeTop(&s);
	for (k = 0; k <= 100; k++)
	{
		float y = nade_top - TSTRIP_GAP * s.u + (TouchStrip_GripTop(&s) - nade_top + TSTRIP_GAP * s.u) * (k / 100.0f * 0.999f);
		CHECK_EQ(TSTRIP_DEAD, TouchStrip_Hit(&s, s.cx, y, NULL), "waiting nade swallows the finger");
	}
	/* a finger on the NADE row's side pad (sideways forgiveness) still counts as NADE */
	s = mk(5, 1, 1, 1, 1);
	CHECK_EQ(TSTRIP_NADE, TouchStrip_Hit(&s, s.cx + s.row_w * 0.5f + 3.0f, nade_top + 5.0f, NULL), "nade side pad");
	CHECK_EQ(TSTRIP_MISS, TouchStrip_Hit(&s, s.cx + s.row_w * 0.5f + 40.0f, nade_top + 5.0f, NULL), "outside the strip");

	/* HOOK out of fuel (entry shown, not pressable): dead, and not the neighbours */
	s = mk(5, 1, 0, 1, 1);
	CHECK_EQ(TSTRIP_DEAD, TouchStrip_Hit(&s, s.cx, TouchStrip_HookTop(&s) + 1.0f, NULL), "hook without fuel is dead");
	CHECK_EQ(TSTRIP_DEAD, TouchStrip_Hit(&s, s.cx, TouchStrip_HookTop(&s) + rh - 1.0f, NULL), "hook without fuel bottom");
	/* no HOOK entry: NADE sits right after the weapons, still exact */
	s = mk(5, 0, 0, 1, 1);
	CHECK_EQ(TSTRIP_DEAD, TouchStrip_Hit(&s, s.cx, TouchStrip_ListEnd(&s) + 1.0f, NULL), "gap without hook");
	CHECK_EQ(TSTRIP_NADE, TouchStrip_Hit(&s, s.cx, TouchStrip_NadeTop(&s) + 1.0f, NULL), "nade without hook");
	/* no NADE row: below the list is not the strip */
	s = mk(5, 1, 1, 0, 0);
	CHECK_EQ(TSTRIP_MISS, TouchStrip_Hit(&s, s.cx, TouchStrip_ListEnd(&s) + 1.0f, NULL), "no nade row");
	/* the weapon menu contains the hook: it is the entry after the last weapon, and counts in the height */
	CHECK_EQ(TSTRIP_HOOK, TouchStrip_Hit(&s, s.cx, s.top + 5 * rh + 1.0f, NULL), "list holds the hook entry");
	CHECK_EQ(1, TouchStrip_Units(5, 1, 0) == TouchStrip_Units(6, 0, 0), "hook entry is one row of height");
	CHECK_EQ(1, TouchStrip_Units(5, 1, 1) - TouchStrip_Units(5, 1, 0) == TSTRIP_GAP + TSTRIP_ACT_H, "nade adds gap and action row");
	/* above the first row: a little forgiveness; far above: a miss */
	s = mk(5, 1, 1, 1, 1);
	CHECK_EQ(TSTRIP_WEAPON, TouchStrip_Hit(&s, s.cx, s.top - 2.0f, &row), "upward pad over first row");
	CHECK_EQ(0, row, "upward pad row");
	CHECK_EQ(TSTRIP_MISS, TouchStrip_Hit(&s, s.cx, s.top - 30.0f, NULL), "above the strip");

	/* old layouts: touch_hook_visible 0/1/2 */
	CHECK_EQ(0, TouchStrip_HookEntryOn(0), "old hook off stays off");
	CHECK_EQ(1, TouchStrip_HookEntryOn(1), "old hook always -> entry");
	CHECK_EQ(1, TouchStrip_HookEntryOn(2), "old hook contextual -> entry");

	}
	printf("%d checks, %d failures\n", checks, failures);
	return failures ? 1 : 0;
}
