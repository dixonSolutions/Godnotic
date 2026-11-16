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
    return 0;
}
