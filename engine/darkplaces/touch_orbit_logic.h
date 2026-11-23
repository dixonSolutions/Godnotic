/*
Godnotic: the free orbit camera after death and at the end of a round or match
(touch_orbit.c), as pure functions the tests compile alone. No engine headers.

The camera itself is the game's own event chase (cl_eventchase_*), which every
CSQC runs, stock servers' included. This module only turns the view with a
finger, scales cl_eventchase_distance with a pinch or the mouse wheel, and
switches the death chase on at the first gesture. It never sends a command.
*/
#ifndef TOUCH_ORBIT_LOGIC_H
#define TOUCH_ORBIT_LOGIC_H

#include <math.h>

#define TOUCHORBIT_CTX_NONE  0
#define TOUCHORBIT_CTX_DEAD  1   // waiting to respawn
#define TOUCHORBIT_CTX_INTER 2   // end of match: scoreboard or the map vote
#define TOUCHORBIT_CTX_STOP  3   // round over (game stopped), still alive

#define TOUCHORBIT_WHEEL_STEP 0.85f

// mode: TOUCHMODE_* (touch_hudmode.h): 1 playing, 2 dead, 7 intermission, 8 map vote
static inline int TouchOrbit_Context(int mode, int game_stopped)
{
	if (mode == 2)
		return TOUCHORBIT_CTX_DEAD;
	if (mode == 7 || mode == 8)
		return TOUCHORBIT_CTX_INTER;
	if (mode == 1 && game_stopped)
		return TOUCHORBIT_CTX_STOP;
	return TOUCHORBIT_CTX_NONE;
}

static inline float TouchOrbit_Clamp(float v, float lo, float hi)
{
	return v < lo ? lo : v > hi ? hi : v;
}

// Fingers spreading (distance grows) bring the camera closer: the multiplier
// on the chase distance shrinks by the same ratio.
static inline float TouchOrbit_Pinch(float mult, float prev_d, float d, float lo, float hi)
{
	if (prev_d < 1 || d < 1)
		return mult;
	return TouchOrbit_Clamp(mult * prev_d / d, lo, hi);
}

// wheel_up: zoom in
static inline float TouchOrbit_Wheel(float mult, int wheel_up, float lo, float hi)
{
	return TouchOrbit_Clamp(wheel_up ? mult * TOUCHORBIT_WHEEL_STEP : mult / TOUCHORBIT_WHEEL_STEP, lo, hi);
}

// One frame of easing toward the wanted multiplier.
static inline float TouchOrbit_Ease(float cur, float want, float dt)
{
	float k = 1.0f - (float)exp(-16.0f * (dt < 0 ? 0 : dt > 0.1f ? 0.1f : dt));
	float v = cur + (want - cur) * k;
	return fabsf(want - v) < 0.002f ? want : v;
}

// The chase distance in game units from the player's own setting.
static inline float TouchOrbit_Distance(float base, float mult)
{
	return TouchOrbit_Clamp(base * mult, 24.0f, 1200.0f);
}

// A contact is a drag once it has travelled the dead zone from where it landed.
static inline int TouchOrbit_IsDrag(float sx, float sy, float x, float y, float deadzone)
{
	float dx = x - sx, dy = y - sy;
	return dx * dx + dy * dy >= deadzone * deadzone;
}

// Whether a landing contact may orbit in a map vote: it is eligible only when
// the vote's own highlight says it landed on empty space (selection < 0). On a
// vote that counts empty space as "abstain" the highlight is never negative, so
// nothing orbits there and a tap keeps meaning what the server made it mean.
static inline int TouchOrbit_VoteEligible(int selection_known, int selection)
{
	return selection_known && selection < 0;
}

#endif
