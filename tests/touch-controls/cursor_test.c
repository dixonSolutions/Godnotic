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
	return 0;
}
