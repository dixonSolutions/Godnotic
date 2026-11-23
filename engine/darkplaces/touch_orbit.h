/*
Godnotic: free orbit camera after death and at the end of a round or match.
See touch_orbit_logic.h. Under any CSQC.
*/
#ifndef TOUCH_ORBIT_H
#define TOUCH_ORBIT_H

#include "qtypes.h"

void TouchOrbit_Init(void);
// Once per frame, from IN_Move.
void TouchOrbit_Frame(void);
// A key CSQC did not take: the mouse wheel zooms, the left button may start a
// drag over empty space in a map vote. True when taken.
qbool TouchOrbit_Key(int key, qbool down);
// Before the config is written: the user's own cl_eventchase_* back in place.
void TouchOrbit_RestoreCvars(void);
// True while a pinch is under way: the look must not also turn.
qbool TouchOrbit_Pinching(void);

#endif
