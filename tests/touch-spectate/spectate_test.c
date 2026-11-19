/*
Checks what the touch controls do while spectating
(engine/darkplaces/touch_spectate.h): the spectator bar's parts state by
state, that no spectator control can send +jump (the join request: only the
join swipe does, touch_hudmode.h), and the join's refusal lock.

Run through scripts/test-touch-spectate.sh.
*/

#include <stdio.h>
#include <string.h>

#include "touch_spectate.h"

static int failures;

#define CHECK_INT(expected, actual, what) do { \
	int e_ = (expected), a_ = (actual); \
	if (e_ != a_) { \
		printf("FAIL %s: expected %d, got %d\n", (what), e_, a_); \
		failures++; \
	} \
} while (0)

#define CHECK_STR(expected, actual, what) do { \
	if (strcmp((expected), (actual))) { \
		printf("FAIL %s: expected \"%s\", got \"%s\"\n", (what), (expected), (actual)); \
		failures++; \
	} \
} while (0)

int main(void)
{
	CHECK_INT(TOUCHSPEC_OBSERVING, TouchSpec_StateFromCsqc(-1, TOUCHSPEC_NONE), "dead to observer with stale scoreboard");
	CHECK_INT(TOUCHSPEC_FOLLOWING, TouchSpec_StateFromCsqc(4, TOUCHSPEC_OBSERVING), "observer to following");
	CHECK_INT(TOUCHSPEC_NONE, TouchSpec_StateFromCsqc(0, TOUCHSPEC_OBSERVING), "spectator joins with stale scoreboard");
	CHECK_INT(TOUCHSPEC_OUT_FREE, TouchSpec_StateFromCsqc(-1, TOUCHSPEC_OUT), "out-of-round observer");
	CHECK_INT(1, TouchSpec_HideStockPanel("HUD_InfoMessages", 1, 1, 0, 1), "foreign touch spectator hints hidden");
	CHECK_INT(1, TouchSpec_HideStockPanel("HUD_PressedKeys", 1, 1, 0, 1), "foreign touch spectator keys hidden");
	CHECK_INT(0, TouchSpec_HideStockPanel("HUD_InfoMessages", 1, 1, 1, 1), "keyboard retains foreign prompts");
	CHECK_INT(0, TouchSpec_HideStockPanel("HUD_InfoMessages", 1, 1, 0, 0), "playing retains foreign messages");
	CHECK_INT(0, TouchSpec_HideStockPanel("HUD_InfoMessages", 0, 1, 0, 1), "bundled prompts use touch labels");
	CHECK_INT(0, TouchSpec_HideStockPanel("HUD_Chat", 1, 1, 0, 1), "chat is unaffected");
	// The state from the scoreboard marker, the view and the health stat
	// (values as seen on 0.8.2, 0.8.5, 0.8.6 and git servers).
	CHECK_INT(TOUCHSPEC_OBSERVING, TouchSpec_StateFrom(-666, 3, 3, 16, -666), "spectator marker, own view, observer health: free-fly");
	CHECK_INT(TOUCHSPEC_OBSERVING, TouchSpec_StateFrom(-666, 3, 3, 16, 0), "the same before the first stats");
	CHECK_INT(TOUCHSPEC_FOLLOWING, TouchSpec_StateFrom(-666, 3, 7, 16, 100), "view in another player: following (their health)");
	CHECK_INT(TOUCHSPEC_NONE, TouchSpec_StateFrom(0, 3, 3, 16, 100), "0.8.6 player (frags 0): playing");
	CHECK_INT(TOUCHSPEC_NONE, TouchSpec_StateFrom(-1, 3, 3, 16, 100), "git player (FRAGS_PLAYER -1): playing");
	CHECK_INT(TOUCHSPEC_NONE, TouchSpec_StateFrom(0, 3, 3, 16, -99900), "dead player: not a spectator");
	CHECK_INT(TOUCHSPEC_NONE, TouchSpec_StateFrom(-1, 3, 3, 16, 0), "dead in CTF waiting to respawn: not a spectator");
	CHECK_INT(TOUCHSPEC_NONE, TouchSpec_StateFrom(25, 3, 3, 16, 100), "a real score: playing");
	CHECK_INT(TOUCHSPEC_OUT, TouchSpec_StateFrom(-616, 3, 5, 16, 80), "out of the round, watching");
	CHECK_INT(TOUCHSPEC_OUT, TouchSpec_StateFrom(-669, 3, 5, 16, 80), "the same with the 0.8.6+ marker");
	CHECK_INT(TOUCHSPEC_OUT_FREE, TouchSpec_StateFrom(-669, 3, 3, 16, -666), "out of the round, free-flying");
	// failing toward play: alive with our own view is playing, whatever the marker says
	CHECK_INT(TOUCHSPEC_NONE, TouchSpec_StateFrom(-666, 3, 3, 16, 100), "stale spectator marker but alive in our own view: play controls");
	CHECK_INT(TOUCHSPEC_NONE, TouchSpec_StateFrom(-616, 3, 3, 16, 100), "the same for the out-of-round marker");
	CHECK_INT(TOUCHSPEC_OBSERVING, TouchSpec_StateFrom(-666, 3, 0, 16, -666), "a view entity that is no player counts as our own");
	CHECK_INT(TOUCHSPEC_OBSERVING, TouchSpec_StateFrom(-666, 3, 40, 16, -666), "nor one beyond the player slots");
	// The scoreboard is freed with the level; a round end or map change leaves
	// realplayerentity set for a moment. No reader may index it then.
	CHECK_INT(-1, TouchSpec_ScoreIndex(0, 16, 1), "no local player: no scoreboard slot");
	CHECK_INT(-1, TouchSpec_ScoreIndex(3, 16, 0), "no scores array: no scoreboard slot");
	CHECK_INT(-1, TouchSpec_ScoreIndex(3, 0, 1), "no client slots: no scoreboard slot");
	CHECK_INT(2, TouchSpec_ScoreIndex(3, 16, 1), "a valid local player: its slot");

	touchspec_join_t j;
	int state, part, jumped;
	double t;

	// the bar, state by state
	CHECK_STR("<", TouchSpec_BarLabel(TOUCHSPEC_OBSERVING, TOUCHSPEC_BAR_PREV), "free-fly prev");
	CHECK_STR("FREE", TouchSpec_BarLabel(TOUCHSPEC_OBSERVING, TOUCHSPEC_BAR_MAIN), "free-fly main");
	CHECK_STR(">", TouchSpec_BarLabel(TOUCHSPEC_OBSERVING, TOUCHSPEC_BAR_NEXT), "free-fly next");
	for (part = 0; part < TOUCHSPEC_BAR_PARTS; part++)
	{
		if (part == TOUCHSPEC_BAR_JOIN)
			continue;   // the JOIN part, checked below
		CHECK_INT(TOUCHSPEC_ACT_FOLLOW, TouchSpec_BarAction(TOUCHSPEC_OBSERVING, part), "free-fly: every part follows");
		CHECK_INT(TOUCHSPEC_ACT_FOLLOW, TouchSpec_BarAction(TOUCHSPEC_OUT_FREE, part), "out free: every part follows");
		CHECK_INT(TOUCHSPEC_ACT_NONE, TouchSpec_BarAction(TOUCHSPEC_NONE, part), "playing: no spectator meaning");
	}
	CHECK_INT(TOUCHSPEC_ACT_PREV, TouchSpec_BarAction(TOUCHSPEC_FOLLOWING, TOUCHSPEC_BAR_PREV), "following prev");
	CHECK_INT(TOUCHSPEC_ACT_FREE, TouchSpec_BarAction(TOUCHSPEC_FOLLOWING, TOUCHSPEC_BAR_MAIN), "following main: free-fly");
	CHECK_INT(TOUCHSPEC_ACT_NEXT, TouchSpec_BarAction(TOUCHSPEC_FOLLOWING, TOUCHSPEC_BAR_NEXT), "following next");
	CHECK_STR("FREE", TouchSpec_BarLabel(TOUCHSPEC_FOLLOWING, TOUCHSPEC_BAR_MAIN), "following main label");
	CHECK_INT(TOUCHSPEC_ACT_PREV, TouchSpec_BarAction(TOUCHSPEC_OUT, TOUCHSPEC_BAR_PREV), "out prev");
	CHECK_INT(TOUCHSPEC_ACT_NONE, TouchSpec_BarAction(TOUCHSPEC_OUT, TOUCHSPEC_BAR_MAIN), "out main: the server keeps the view");
	CHECK_STR("WATCHING", TouchSpec_BarLabel(TOUCHSPEC_OUT, TOUCHSPEC_BAR_MAIN), "out main label");
	CHECK_INT(TOUCHSPEC_ACT_NEXT, TouchSpec_BarAction(TOUCHSPEC_OUT, TOUCHSPEC_BAR_NEXT), "out next");
	CHECK_STR("", TouchSpec_BarLabel(TOUCHSPEC_NONE, TOUCHSPEC_BAR_MAIN), "playing: no bar");
	CHECK_STR("JOIN", TouchSpec_BarLabel(TOUCHSPEC_FOLLOWING, TOUCHSPEC_BAR_JOIN), "the join part says JOIN");
	CHECK_INT(1, TouchSpec_BarRole(TOUCHSPEC_ROLE_JOIN), "JOIN is a bar role");

	// off by default, on when the setting is: absent while playing either way
	CHECK_INT(1, TOUCHSPEC_BAR_DEFAULT, "the spectator bar defaults on");
	for (state = TOUCHSPEC_NONE; state <= TOUCHSPEC_OUT_FREE; state++)
	{
		CHECK_INT(state != TOUCHSPEC_NONE, TouchSpec_BarShown(state, TOUCHSPEC_BAR_DEFAULT), "the bar is present by default");
		CHECK_INT(state != TOUCHSPEC_NONE, TouchSpec_BarShown(state, 0), "old saved settings do not hide the bar");
		CHECK_INT(state != TOUCHSPEC_NONE, TouchSpec_BarShown(state, 1), "the bar is present when switched on");
	}

	// An old saved off setting must not disable spectator controls.
	{
		touchspec_keys_t k;
		memset(&k, 0, sizeof(k));
		TouchSpec_AddFinger(&k, TOUCHSPEC_OBSERVING, 0, TOUCHSPEC_ROLE_ATTACK, 1);
		TouchSpec_AddFinger(&k, TOUCHSPEC_OBSERVING, 0, TOUCHSPEC_ROLE_FREE, 1);
		TouchSpec_AddFinger(&k, TOUCHSPEC_OBSERVING, 0, TOUCHSPEC_ROLE_PREV, 1);
		CHECK_INT(1, k.fire, "old setting: FOLLOW/NEXT sends attack");
		CHECK_INT(1, k.fire2, "old setting: FREE sends attack2");
		CHECK_INT(1, k.prev, "old setting: PREV sends weapprev");
		memset(&k, 0, sizeof(k));
		TouchSpec_AddFinger(&k, TOUCHSPEC_OBSERVING, 0, TOUCHSPEC_ROLE_UP, 1);
		TouchSpec_AddFinger(&k, TOUCHSPEC_OBSERVING, 0, TOUCHSPEC_ROLE_DOWN, 1);
		CHECK_INT(1, k.up, "UP flies");
		CHECK_INT(1, k.down, "DOWN flies");
		// switched on, the same roles act
		memset(&k, 0, sizeof(k));
		TouchSpec_AddFinger(&k, TOUCHSPEC_OBSERVING, 1, TOUCHSPEC_ROLE_ATTACK, 1);
		TouchSpec_AddFinger(&k, TOUCHSPEC_OBSERVING, 1, TOUCHSPEC_ROLE_FREE, 1);
		TouchSpec_AddFinger(&k, TOUCHSPEC_OBSERVING, 1, TOUCHSPEC_ROLE_PREV, 1);
		printf("shown bar: fire=%d fire2=%d prev=%d (want 1 1 1)\n", k.fire, k.fire2, k.prev);
		CHECK_INT(1, k.fire, "shown bar: FOLLOW/NEXT sends attack");
		CHECK_INT(1, k.fire2, "shown bar: FREE sends attack2");
		CHECK_INT(1, k.prev, "shown bar: PREV sends weapprev");
		// not a spectator: no bar role acts, shown or not
		memset(&k, 0, sizeof(k));
		TouchSpec_AddFinger(&k, TOUCHSPEC_NONE, 1, TOUCHSPEC_ROLE_ATTACK, 1);
		CHECK_INT(0, k.fire, "playing: a bar role sends nothing");
	}

	// no part can mean jump (the join): bar actions are follow, next, prev, free only
	for (state = TOUCHSPEC_NONE; state <= TOUCHSPEC_OUT_FREE; state++)
		for (part = 0; part < TOUCHSPEC_BAR_PARTS; part++)
		{
			int act = TouchSpec_BarAction(state, part);
			if (part == TOUCHSPEC_BAR_JOIN)
			{
				CHECK_INT(state != TOUCHSPEC_NONE ? TOUCHSPEC_ACT_JOIN : TOUCHSPEC_ACT_NONE, act, "JOIN is the join part's action, only while spectating");
				continue;
			}
			CHECK_INT(1, act >= TOUCHSPEC_ACT_NONE && act <= TOUCHSPEC_ACT_FREE, "bar actions are follow / next / prev / free only");
		}

	CHECK_INT(1, TouchSpec_StickShown(TOUCHSPEC_OBSERVING), "stick observing");
	CHECK_INT(1, TouchSpec_StickShown(TOUCHSPEC_OUT_FREE), "stick out free");
	CHECK_INT(0, TouchSpec_StickShown(TOUCHSPEC_FOLLOWING), "stick following");
	CHECK_INT(0, TouchSpec_StickShown(TOUCHSPEC_OUT), "stick out");
	CHECK_INT(1, TouchSpec_JoinShown(TOUCHSPEC_OBSERVING), "join observing");
	CHECK_INT(1, TouchSpec_JoinShown(TOUCHSPEC_FOLLOWING), "join following");
	CHECK_INT(0, TouchSpec_JoinShown(TOUCHSPEC_OUT), "join out of the round");
	CHECK_INT(0, TouchSpec_JoinShown(TOUCHSPEC_OUT_FREE), "join out free");
	CHECK_INT(0, TouchSpec_JoinShown(TOUCHSPEC_NONE), "join playing");

	// The bar's hit test: the desk strip and the HUD both use it, so a finger
	// on < / > / FREE runs the same action the bar shows.
	{
		touchspec_bar_rect_t r[TOUCHSPEC_BAR_PARTS];
		TouchSpec_BarLayout(1920, 1080, 1650, 950, 30, 2.0f, 1, r);
		int prev = TouchSpec_BarPartAt(r, 1, r[TOUCHSPEC_BAR_PREV].cx, r[TOUCHSPEC_BAR_PREV].cy);
		int main_part = TouchSpec_BarPartAt(r, 1, r[TOUCHSPEC_BAR_MAIN].cx, r[TOUCHSPEC_BAR_MAIN].cy);
		int next = TouchSpec_BarPartAt(r, 1, r[TOUCHSPEC_BAR_NEXT].cx, r[TOUCHSPEC_BAR_NEXT].cy);
		int join = TouchSpec_BarPartAt(r, 1, r[TOUCHSPEC_BAR_JOIN].cx, r[TOUCHSPEC_BAR_JOIN].cy);
		CHECK_INT(TOUCHSPEC_BAR_PREV, prev, "bar hit: previous");
		CHECK_INT(TOUCHSPEC_BAR_MAIN, main_part, "bar hit: main");
		CHECK_INT(TOUCHSPEC_BAR_NEXT, next, "bar hit: next");
		CHECK_INT(TOUCHSPEC_BAR_JOIN, join, "bar hit: join");
		CHECK_INT(TOUCHSPEC_ACT_PREV, TouchSpec_BarAction(TOUCHSPEC_FOLLOWING, prev), "hit previous runs previous player");
		CHECK_INT(TOUCHSPEC_ACT_FREE, TouchSpec_BarAction(TOUCHSPEC_FOLLOWING, main_part), "hit main runs free-fly");
		CHECK_INT(TOUCHSPEC_ACT_NEXT, TouchSpec_BarAction(TOUCHSPEC_FOLLOWING, next), "hit next runs next player");
		CHECK_INT(TOUCHSPEC_ACT_JOIN, TouchSpec_BarAction(TOUCHSPEC_FOLLOWING, join), "hit join runs the join");
		// An edge of the bar is still on it (fat fingers), the screen corner is not.
		CHECK_INT(TOUCHSPEC_BAR_NEXT, TouchSpec_BarPartAt(r, 1, r[TOUCHSPEC_BAR_NEXT].cx + r[TOUCHSPEC_BAR_NEXT].hx, r[TOUCHSPEC_BAR_NEXT].cy + r[TOUCHSPEC_BAR_NEXT].hy), "bar hit: its own edge");
		CHECK_INT(-1, TouchSpec_BarPartAt(r, 1, 5, 5), "bar hit: far away");
		// JOIN is only part of the row when it is offered.
		CHECK_INT(-1, TouchSpec_BarPartAt(r, 0, r[TOUCHSPEC_BAR_JOIN].cx, r[TOUCHSPEC_BAR_JOIN].cy), "no join part: no hit");
	}

	// nothing sent, nothing held
	memset(&j, 0, sizeof(j));
	CHECK_INT(0, TouchSpec_JoinFrame(&j, TOUCHSPEC_OBSERVING, 5.0), "no join: no jump");

	// sent: +jump for TOUCHSPEC_JOIN_PRESS, then released; taken, it clears
	memset(&j, 0, sizeof(j));
	TouchSpec_JoinSend(&j, 10.0);
	CHECK_INT(1, TouchSpec_JoinPending(&j), "pending after send");
	jumped = 0;
	for (t = 10.0; t < 10.0 + TOUCHSPEC_JOIN_PRESS + 0.1; t += 1.0 / 60)
		jumped += TouchSpec_JoinFrame(&j, TOUCHSPEC_OBSERVING, t);
	CHECK_INT(1, jumped >= 14 && jumped <= 16, "+jump held about 0.25 s at 60 fps");
	CHECK_INT(0, TouchSpec_JoinFrame(&j, TOUCHSPEC_NONE, 10.5), "playing: taken");
	CHECK_INT(0, TouchSpec_JoinPending(&j), "taken: not pending");
	CHECK_INT(0, TouchSpec_JoinLocked(&j, 11.0), "taken: not locked");

	// refused: still spectating after TOUCHSPEC_JOIN_WAIT, then LOCKED for a while
	memset(&j, 0, sizeof(j));
	TouchSpec_JoinSend(&j, 20.0);
	for (t = 20.0; t < 20.0 + TOUCHSPEC_JOIN_WAIT + 0.1; t += 1.0 / 30)
		TouchSpec_JoinFrame(&j, TOUCHSPEC_FOLLOWING, t);
	CHECK_INT(0, TouchSpec_JoinPending(&j), "refused: no longer pending");
	CHECK_INT(1, TouchSpec_JoinLocked(&j, 25.0), "refused: locked");
	CHECK_INT(0, TouchSpec_JoinLocked(&j, 20.0 + TOUCHSPEC_JOIN_WAIT + TOUCHSPEC_JOIN_LOCKED + 1), "lock expires");
	TouchSpec_JoinSend(&j, 26.0);
	CHECK_INT(0, TouchSpec_JoinLocked(&j, 26.5), "a retry clears the lock");

	// a slow server: the join is answered after the wait, so the lock went on;
	// once playing, the lock goes, and spectating later shows the handle open
	memset(&j, 0, sizeof(j));
	TouchSpec_JoinSend(&j, 40.0);
	for (t = 40.0; t < 44.0; t += 1.0 / 30)
		TouchSpec_JoinFrame(&j, TOUCHSPEC_OBSERVING, t);
	CHECK_INT(1, TouchSpec_JoinLocked(&j, 44.0), "slow answer: locked for now");
	TouchSpec_JoinFrame(&j, TOUCHSPEC_NONE, 44.5);
	CHECK_INT(0, TouchSpec_JoinLocked(&j, 50.0), "playing clears the lock");

	if (failures)
	{
		printf("%d check(s) failed\n", failures);
		return 1;
	}
	printf("touch spectate: all checks passed\n");
	return 0;
}
