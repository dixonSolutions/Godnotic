// Unit checks for engine/darkplaces/touch_orbit_logic.h (scripts/test-touch-orbit.sh).
#include <stdio.h>
#include "touch_orbit_logic.h"

static int fails;
#define CHECK(c) do { if (!(c)) { printf("FAIL line %d: %s\n", __LINE__, #c); fails++; } } while (0)

int main(void)
{
	float m, i;
	CHECK(TouchOrbit_Context(2, 0) == TOUCHORBIT_CTX_DEAD);
	CHECK(TouchOrbit_Context(7, 0) == TOUCHORBIT_CTX_INTER);
	CHECK(TouchOrbit_Context(8, 0) == TOUCHORBIT_CTX_INTER);
	CHECK(TouchOrbit_Context(1, 1) == TOUCHORBIT_CTX_STOP);
	CHECK(TouchOrbit_Context(1, 0) == TOUCHORBIT_CTX_NONE);
	CHECK(TouchOrbit_Context(3, 1) == TOUCHORBIT_CTX_NONE);   // spectators never orbit
	CHECK(TouchOrbit_Context(0, 1) == TOUCHORBIT_CTX_NONE);
	// spreading fingers zoom in, pinching zooms out, always inside the bounds
	CHECK(TouchOrbit_Pinch(1, 100, 200, 0.25f, 4) == 0.5f);
	CHECK(TouchOrbit_Pinch(1, 200, 100, 0.25f, 4) == 2.0f);
	CHECK(TouchOrbit_Pinch(1, 1000, 0.5f, 0.25f, 4) == 1.0f);   // a degenerate pair changes nothing
	CHECK(TouchOrbit_Pinch(1, 10, 1000, 0.25f, 4) == 0.25f);
	CHECK(TouchOrbit_Pinch(1, 1000, 10, 0.25f, 4) == 4.0f);
	// wheel
	m = TouchOrbit_Wheel(1, 1, 0.25f, 4);
	CHECK(m < 1 && m > 0.8f);
	m = TouchOrbit_Wheel(1, 0, 0.25f, 4);
	CHECK(m > 1);
	for (i = 0, m = 1; i < 100; i++) m = TouchOrbit_Wheel(m, 1, 0.25f, 4);
	CHECK(m == 0.25f);
	for (i = 0; i < 100; i++) m = TouchOrbit_Wheel(m, 0, 0.25f, 4);
	CHECK(m == 4.0f);
	// easing arrives and never overshoots
	for (i = 0, m = 1; i < 60; i++) { m = TouchOrbit_Ease(m, 0.5f, 1.0f / 60); CHECK(m >= 0.5f); }
	CHECK(m == 0.5f);
	// distance bounds
	CHECK(TouchOrbit_Distance(140, 0.25f) == 35.0f);
	CHECK(TouchOrbit_Distance(140, 0.01f) == 24.0f);
	CHECK(TouchOrbit_Distance(140, 100) == 1200.0f);
	// drag dead zone
	CHECK(!TouchOrbit_IsDrag(10, 10, 14, 13, 8));
	CHECK(TouchOrbit_IsDrag(10, 10, 20, 10, 8));
	// the vote: only a highlight that says "no entry" lets a finger orbit
	CHECK(TouchOrbit_VoteEligible(1, -1));
	CHECK(!TouchOrbit_VoteEligible(1, 0));
	CHECK(!TouchOrbit_VoteEligible(1, 3));
	CHECK(!TouchOrbit_VoteEligible(0, -1));
	if (fails) return 1;
	puts("touch orbit logic: all checks passed");
	return 0;
}
