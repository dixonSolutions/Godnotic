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

    /* The theme plays through a join: connecting, downloading, loading and the
     * preview are not a match, so the menu theme is the music there. */
    assert(TouchMusic_JoinPolicy(TM_STAGE_JOINING, 1, 0, 0, 0) == 0);  /* never start during a join */
    assert(TouchMusic_JoinPolicy(TM_STAGE_JOINING, 1, 1, 0, 0) == 0);  /* keep it through the load */
    assert(TouchMusic_JoinPolicy(TM_STAGE_MENU, 1, 1, 0, 0) == 0);

    /* The first-run data download screen is not a match either: the theme
     * starts as soon as the music pack lands and keeps going into the menu. */
    assert(TouchMusic_JoinPolicy(TM_STAGE_DOWNLOAD, 1, 0, 0, 0) == 1);
    assert(TouchMusic_JoinPolicy(TM_STAGE_DOWNLOAD, 1, 1, 0, 0) == 0);
    assert(TouchMusic_JoinPolicy(TM_STAGE_DOWNLOAD, 0, 0, 0, 0) == 0);

    /* The map's own track replaces the stream: the theme is done, in a join or
     * in a match, and the mixer fades the new track in. */
    assert(TouchMusic_JoinPolicy(TM_STAGE_JOINING, 1, 1, 1, 0) == -1);
    assert(TouchMusic_JoinPolicy(TM_STAGE_GAME, 1, 1, 1, 0) == -1);
    assert(TouchMusic_JoinPolicy(TM_STAGE_GAME, 1, 1, 1, 1) == -1); /* a map track wins over the setting */

    /* In a match: stop the theme, unless the map has no music and the player
     * allows the theme to stay. */
    assert(TouchMusic_JoinPolicy(TM_STAGE_GAME, 1, 1, 0, 0) == -1);
    assert(TouchMusic_JoinPolicy(TM_STAGE_GAME, 1, 1, 0, 1) == 0);
    assert(TouchMusic_JoinPolicy(TM_STAGE_GAME, 1, 0, 0, 1) == 0);

    /* No theme on the device (muted or not downloaded): nothing to do, and no
     * asking for a track that is not there. */
    assert(TouchMusic_JoinPolicy(TM_STAGE_JOINING, 0, 0, 0, 0) == 0);
    assert(TouchMusic_JoinPolicy(TM_STAGE_JOINING, 0, 0, 1, 0) == 0);

    /* Telling the theme from the map's track, which a server may name by path. */
    assert(TouchMusic_IsTheme("rising-of-the-phoenix") == 1);
    assert(TouchMusic_IsTheme("sound/cdtracks/rising-of-the-phoenix.ogg") == 1);
    assert(TouchMusic_IsTheme("sound/cdtracks/darkpegasus") == 0);
    assert(TouchMusic_IsTheme("rising-of-the-phoenix-remix") == 0);
    assert(TouchMusic_IsTheme("") == 0);
    assert(TouchMusic_IsTheme(NULL) == 0);
    return 0;
}
