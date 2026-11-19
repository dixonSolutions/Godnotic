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

	CHECK_INT(1, TouchJoinPreview_ShowsLevelshot(1, 1, 1, 0), "a join with a map name and no world: its levelshot");
	CHECK_INT(0, TouchJoinPreview_ShowsLevelshot(1, 1, 1, 1), "a drawable world: no levelshot");
	CHECK_INT(0, TouchJoinPreview_ShowsLevelshot(1, 1, 0, 0), "no map name yet: no levelshot");
	CHECK_INT(0, TouchJoinPreview_ShowsLevelshot(1, 0, 1, 0), "not joining: no levelshot");
	CHECK_INT(0, TouchJoinPreview_ShowsLevelshot(0, 1, 1, 0), "touch_join_preview 0: no levelshot");

	CHECK_INT(7, TouchJoinPreview_SceneModel(7, 3), "the scene takes the current world");
	CHECK_INT(7, TouchJoinPreview_SceneModel(7, 0), "also with no cached world");
	CHECK_INT(0, TouchJoinPreview_SceneModel(0, 3), "a missing current world stays missing");

	/* The 0x233 SIGSEGV: a loading-screen redraw inside R_Modules_NewMap
	 * (R_SetSkyBox -> image load -> CL_KeepaliveMonitor) drew a preview frame
	 * through the half-reset renderer. */
	CHECK_INT(1, TouchJoinPreview_Allowed(1, 0), "a drawable world, no newmap running: allowed");
	CHECK_INT(0, TouchJoinPreview_Allowed(1, 1), "inside R_Modules_NewMap: no preview frame (the 0x233 crash)");
	CHECK_INT(0, TouchJoinPreview_Allowed(1, 2), "nested newmap: no preview frame");
	CHECK_INT(0, TouchJoinPreview_Allowed(0, 0), "not drawable stays not allowed");
	{
		int depth = 0;
		depth = TouchJoinPreview_NewMapDepth(depth, 1);
		CHECK_INT(0, TouchJoinPreview_Allowed(1, depth), "newmap begun: blocked");
		depth = TouchJoinPreview_NewMapDepth(depth, 1);
		depth = TouchJoinPreview_NewMapDepth(depth, 0);
		CHECK_INT(0, TouchJoinPreview_Allowed(1, depth), "inner newmap ended, outer still running: blocked");
		depth = TouchJoinPreview_NewMapDepth(depth, 0);
		CHECK_INT(1, TouchJoinPreview_Allowed(1, depth), "both ended: allowed again");
		depth = TouchJoinPreview_NewMapDepth(depth, 0);
		CHECK_INT(0, depth, "an unmatched end never goes below zero");
	}

	/* a server join always has a picture, even with no map name yet */
	CHECK_INT(1, TouchJoinPreview_ShowsPicture(1, 1, 0), "a join with no world: a picture, map name or not");
	CHECK_INT(0, TouchJoinPreview_ShowsPicture(1, 1, 1), "a drawable world: no picture");
	CHECK_INT(0, TouchJoinPreview_ShowsPicture(1, 0, 0), "not joining: no picture");
	CHECK_INT(0, TouchJoinPreview_ShowsPicture(0, 1, 0), "touch_join_preview 0: no picture");
	/* own picture, else the list's, else a generic one, never the logo when any exists */
	CHECK_INT(TJP_PIC_MAP, TouchJoinPreview_PictureChoice(1, 1, 1), "the map's own picture first");
	CHECK_INT(TJP_PIC_LIST, TouchJoinPreview_PictureChoice(0, 1, 1), "then the server list's map");
	CHECK_INT(TJP_PIC_GENERIC, TouchJoinPreview_PictureChoice(0, 0, 1), "then a generic picture");
	CHECK_INT(TJP_PIC_NONE, TouchJoinPreview_PictureChoice(0, 0, 0), "nothing at all");
	CHECK_INT(1, TouchJoinPreview_PictureBrightness(TJP_PIC_MAP) == 1.0f, "the map's picture is full brightness");
	CHECK_INT(1, TouchJoinPreview_PictureBrightness(TJP_PIC_GENERIC) < 0.6f, "the generic picture is dimmed");
	/* the map's pack arrives: ask again until the map's own picture is there */
	CHECK_INT(0, TouchJoinPreview_RetryPicture(TJP_PIC_MAP, 10.0, 0.0), "a found map picture is final");
	CHECK_INT(1, TouchJoinPreview_RetryPicture(TJP_PIC_GENERIC, 10.0, 9.0), "a generic picture is asked again");
	CHECK_INT(0, TouchJoinPreview_RetryPicture(TJP_PIC_GENERIC, 10.0, 9.9), "but not every frame");
	/* the precache step the card shows (the engine's own band is hidden) */
	CHECK_INT(0, TouchJoinPreview_PrecacheKind(0, 0, 1, 40, 1, 30), "precache not begun");
	CHECK_INT(1, TouchJoinPreview_PrecacheKind(1, 0, 5, 40, 1, 30), "models loading");
	CHECK_INT(2, TouchJoinPreview_PrecacheKind(1, 0, 40, 40, 7, 30), "sounds loading");
	CHECK_INT(0, TouchJoinPreview_PrecacheKind(1, 1, 40, 40, 30, 30), "precache finished");
	CHECK_INT(0, TouchJoinPreview_DrawEngineBand(1, 1), "the native card is up: no engine band under it");
	CHECK_INT(1, TouchJoinPreview_DrawEngineBand(0, 1), "no card: the engine's band stays");
	CHECK_INT(0, TouchJoinPreview_DrawEngineBand(0, 0), "no loading stack: nothing to draw");
	if (failures)
	{
		printf("%d check(s) failed\n", failures);
		return 1;
	}
	printf("touch join preview: all checks passed\n");
	return 0;
}
