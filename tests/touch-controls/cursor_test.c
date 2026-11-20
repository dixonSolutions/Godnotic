#include <assert.h>
#include "touch_cursor.h"

int main(void)
{
	int cursor;
	for (cursor = 0; cursor <= 1; ++cursor) {
		/* Vote and intermission: a finger never shows either pointer. */
		assert(TouchCursor_HideSystem(1, 0, 1, cursor, cursor));
		assert(TouchCursor_HidePic("gfx/menu/wickedx/cursor", 1, 0, 0));
		/* A mouse over a CSQC or prydon pointer UI gets the SDL cursor. */
		assert(!TouchCursor_HideSystem(1, 1, 1, 1, cursor));
		assert(!TouchCursor_HideSystem(0, 1, 1, cursor, 1));
		assert(TouchCursor_HidePic("gfx/menu/wickedx/cursor", 1, 1, 1));
	}
	assert(!TouchCursor_HidePic("gfx/menu/wickedx/cursor", 0, 1, 0));
	assert(!TouchCursor_HidePic("gfx/menu/wickedx/cursor_text", 1, 0, 1));
	assert(!TouchCursor_HidePic("gfx/hud/cursor", 1, 0, 1));
	/* Android draws its own pointer (vid_system_cursor 0): SDL's stays hidden,
	 * and a mouse gets one in the console; a finger (pointer_mouse 0) none. */
#ifdef __ANDROID__
	assert(!strcmp(TOUCH_CURSOR_SYSTEM_DEFAULT, "0"));
	assert(TouchCursor_HideSDL(0, 0));
	assert(TouchCursor_ConsolePointer(0, 1, 1));
	assert(!TouchCursor_ConsolePointer(0, 0, 1));
	assert(!TouchCursor_ConsolePointer(0, 1, 0));
	assert(!TouchCursor_HidePic("gfx/menu/luma/cursor", 0, 1, 0)); /* CSQC pointer UIs draw it */
	assert(!TouchCursor_HidePic("gfx/menu/luma/cursor", 1, 1, 0)); /* mouse + touchscreen */
	assert(TouchCursor_HidePic("gfx/menu/luma/cursor", 1, 0, 0)); /* finger: none */
#else
	assert(!strcmp(TOUCH_CURSOR_SYSTEM_DEFAULT, "1"));
	assert(!TouchCursor_HideSDL(0, 1));
	assert(!TouchCursor_ConsolePointer(1, 1, 1)); /* SDL draws it */
#endif
	return 0;
}
