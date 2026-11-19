#include <assert.h>
#include <stdio.h>
typedef int qbool;
#include "touch_aim_input.h"

int main(void)
{
	/* Touch and keyboard-only work; a mouse requires opt-in. */
	assert(TouchAim_EnabledForInput(0, 0));
	assert(TouchAim_EnabledForInput(0, 1));
	assert(!TouchAim_EnabledForInput(1, 0));
	assert(TouchAim_EnabledForInput(1, 1));
	/* Simulate an idle HUD, mouse acquisition and motion, and both buttons. */
	assert(!TouchAim_InputActive(0, 1, 0, 0, 0));
	assert(TouchAim_InputActive(0, 1, 1, 0, 0));
	assert(TouchAim_InputActive(0, 1, 1, 3.0f, 0));
	assert(TouchAim_InputActive(0, 1, 0, 0, 1)); /* primary or secondary */
	assert(TouchAim_InputActive(0, 1, 0, 2.0f, 0)); /* keyboard turn */
	assert(TouchAim_InputActive(0, 0, 0, 0, 0)); /* keyboard-only desk */
	assert(TouchAim_InputActive(1, 1, 0, 0, 0)); /* finger */
	puts("touch aim input engagement: OK");
	return 0;
}
