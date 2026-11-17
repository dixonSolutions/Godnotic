#include <assert.h>
#include "../../engine/darkplaces/touch_music.h"

int main(void)
{
    int playing = 0;
    assert(TouchMusic_Policy(0, 0, playing) == 0);
    assert(TouchMusic_Policy(0, 1, playing) == 1); /* download lands */
    playing = 1;
    assert(TouchMusic_Policy(0, 1, playing) == 0);
    assert(TouchMusic_Policy(1, 1, playing) == -1); /* enter match */
    playing = 0;
    assert(TouchMusic_Policy(1, 1, playing) == 0);
    assert(TouchMusic_Policy(0, 1, playing) == 1); /* back to menu */
    assert(TouchMusic_Policy(0, 1, 0) == 1); /* later launch */
    /* the ask cadence: half a second while the menu is young, five after a
     * minute, two after an ask so a starting track is not restarted */
    assert(TouchMusic_NextCheck(0.0, 0) == 0.5);
    assert(TouchMusic_NextCheck(10.0, 0) == 10.5);
    assert(TouchMusic_NextCheck(100.0, 0) == 105.0);
    assert(TouchMusic_NextCheck(10.0, 1) == 12.0);
    return 0;
}
