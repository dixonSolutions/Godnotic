/*
Checks the join preview's scene rules (touch_joinpreview_logic.h): a frame is
drawable only when the current world has leaves and surfaces, and the scene
always takes the current model instead of a cached one from an earlier map.

Run through scripts/test-touch-joinpreview.sh.
*/
#include <stdio.h>

#include "touch_joinpreview_logic.h"

static int failures;

#define CHECK_INT(expected, actual, what) do { \
	int e_ = (expected), a_ = (actual); \
	if (e_ != a_) { \
		printf("FAIL %s: expected %d, got %d\n", (what), e_, a_); \
		failures++; \
	} \
} while (0)

int main(void)
{
	CHECK_INT(0, TouchJoinPreview_Drawable(0, 1, 1, 1, 1), "no world model: not drawable");
	CHECK_INT(0, TouchJoinPreview_Drawable(1, 0, 1, 1, 1), "no leaves: not drawable");
	CHECK_INT(0, TouchJoinPreview_Drawable(1, 1, 0, 1, 1), "leaf data missing: not drawable");
	CHECK_INT(0, TouchJoinPreview_Drawable(1, 1, 1, 0, 1), "no surfaces: not drawable (the sky crash)");
	CHECK_INT(0, TouchJoinPreview_Drawable(1, 1, 1, 1, 0), "surface data missing: not drawable");
	CHECK_INT(1, TouchJoinPreview_Drawable(1, 1, 1, 1, 1), "a loaded world: drawable");

	CHECK_INT(7, TouchJoinPreview_SceneModel(7, 3), "the scene takes the current world");
	CHECK_INT(7, TouchJoinPreview_SceneModel(7, 0), "also with no cached world");
	CHECK_INT(0, TouchJoinPreview_SceneModel(0, 3), "a missing current world stays missing");

	if (failures)
	{
		printf("%d check(s) failed\n", failures);
		return 1;
	}
	printf("touch join preview: all checks passed\n");
	return 0;
}
