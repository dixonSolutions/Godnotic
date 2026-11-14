/*
Godnotic: what the in-match touch HUD is showing, as pure functions both HUD
copies, the native front ends and the tests share.

  - the mode the HUD is in (playing, dead, free-fly, following, out of the
    round, intermission, the next-map vote), derived once per frame by the
    engine (touch_hudmode.c) from the client state and the loaded CSQC's own
    globals, and published as read-only cvars (_touch_hud_*). The port's CSQC
    reads those instead of guessing again; touch_shellbtn.c can too.
  - the controls each mode has (TouchMode_Controls): one layout model, a
    control set per mode.
  - the MENU sheet's action list (TouchMode_Actions): Resume, Join, Move to
    spectators, Change team, Settings, Leave, each available, disabled or
    asking for a confirm, for the in-match menu and the native pause menus.
  - joining (TouchJoinDlg_*): Xonotic's own join dialog (the menu's Team
    Selection: auto-select, the teams, spectate), which comes up by itself
    for an observer when a match starts and from the MENU sheet after that.
  - the join slider (TouchSlide_*): "Swipe to join" on the spectator HUD,
    a wide track at the bottom middle whose knob is pushed right (a tap or a
    click does the same). It joins, or opens the join dialog in a team game.

No engine headers: scripts/test-touch-hudmode.sh compiles this alone. The
QuakeC side mirrors the numbers in qcsrc/client/touch_mode.qh.
*/
#ifndef TOUCH_HUDMODE_H
#define TOUCH_HUDMODE_H

#include <string.h>

#include "touch_spectate.h"

// ---------------------------------------------------------------------------
// Modes
// ---------------------------------------------------------------------------
#define TOUCHMODE_OFF          0   // not in a match (menu, loading, demo)
#define TOUCHMODE_PLAYING      1
#define TOUCHMODE_DEAD         2   // waiting to respawn
#define TOUCHMODE_OBSERVING    3   // spectator, free-fly
#define TOUCHMODE_FOLLOWING    4   // spectator, watching a player
#define TOUCHMODE_OUT          5   // in the game, out of the round, watching
#define TOUCHMODE_OUT_FREE     6   // in the game, out of the round, free-fly
#define TOUCHMODE_INTERMISSION 7   // end-of-match scoreboard
#define TOUCHMODE_MAPVOTE      8   // the next map / gametype vote
#define TOUCHMODE_COUNT        9

// Flags beside the mode
#define TOUCHMODE_F_WARMUP 1    // warmup stage
#define TOUCHMODE_F_READY  2    // warmup waits for this player's "ready"
#define TOUCHMODE_F_TEAMS  4    // a team game: joining picks a team
#define TOUCHMODE_F_ROUND  8    // a round-based game with a round under way
#define TOUCHMODE_F_CARRY  16   // carrying an objective (flag, key, ball)

// What joining would do now (spectators only)
#define TOUCHJOIN_NONE    0   // not offered
#define TOUCHJOIN_OPEN    1
#define TOUCHJOIN_PENDING 2   // sent, waiting for the server
#define TOUCHJOIN_QUEUED  3   // the server queued us for a slot
#define TOUCHJOIN_LOCKED  4   // the server did not take it (locked, full, spectate-only)

typedef struct touchmode_in_s
{
	int in_match;      // connected, world in, game code loaded, not a demo
	int intermission;  // end-of-match
	int mapvote;       // the next map / gametype vote is up
	int spec_state;    // TOUCHSPEC_* (touch_spectate.h)
	int health;        // STAT_HEALTH
	int warmup;
	int ready_for_me;  // the warmup waits for this player's ready
	int teamplay;
	int round_live;    // a round-based game, round under way
	int carrying;      // holding a flag, key or ball
	int join_pending;  // a join was sent and not answered yet
	int join_queued;   // the server queued the join
	int join_refused;  // a join was refused a short while ago
} touchmode_in_t;

typedef struct touchmode_s
{
	int mode;
	int flags;
	int join;
} touchmode_t;

static inline int TouchMode_IsSpectating(int mode)
{
	return mode == TOUCHMODE_OBSERVING || mode == TOUCHMODE_FOLLOWING;
}

static inline int TouchMode_IsOutOfRound(int mode)
{
	return mode == TOUCHMODE_OUT || mode == TOUCHMODE_OUT_FREE;
}

static inline int TouchMode_IsMatchEnd(int mode)
{
	return mode == TOUCHMODE_INTERMISSION || mode == TOUCHMODE_MAPVOTE;
}

static inline int TouchMode_FreeFly(int mode)
{
	return mode == TOUCHMODE_OBSERVING || mode == TOUCHMODE_OUT_FREE;
}

static inline touchmode_t TouchMode_Derive(const touchmode_in_t *in)
{
	touchmode_t m;
	m.mode = TOUCHMODE_OFF;
	m.flags = 0;
	m.join = TOUCHJOIN_NONE;
	if (!in->in_match)
		return m;
	if (in->mapvote)
		m.mode = TOUCHMODE_MAPVOTE;
	else if (in->intermission)
		m.mode = TOUCHMODE_INTERMISSION;
	else switch (in->spec_state)
	{
	case TOUCHSPEC_OBSERVING: m.mode = TOUCHMODE_OBSERVING; break;
	case TOUCHSPEC_FOLLOWING: m.mode = TOUCHMODE_FOLLOWING; break;
	case TOUCHSPEC_OUT: m.mode = TOUCHMODE_OUT; break;
	case TOUCHSPEC_OUT_FREE: m.mode = TOUCHMODE_OUT_FREE; break;
	default: m.mode = in->health > 0 ? TOUCHMODE_PLAYING : TOUCHMODE_DEAD; break;
	}
	if (in->warmup && !TouchMode_IsMatchEnd(m.mode))
		m.flags |= TOUCHMODE_F_WARMUP;
	if (in->ready_for_me && (m.mode == TOUCHMODE_PLAYING || m.mode == TOUCHMODE_DEAD))
		m.flags |= TOUCHMODE_F_READY;
	if (in->teamplay)
		m.flags |= TOUCHMODE_F_TEAMS;
	if (in->round_live && (m.mode == TOUCHMODE_PLAYING || m.mode == TOUCHMODE_DEAD))
		m.flags |= TOUCHMODE_F_ROUND;
	if (in->carrying && m.mode == TOUCHMODE_PLAYING)
		m.flags |= TOUCHMODE_F_CARRY;
	if (TouchMode_IsSpectating(m.mode))
		m.join = in->join_queued ? TOUCHJOIN_QUEUED
			: in->join_pending ? TOUCHJOIN_PENDING
			: in->join_refused ? TOUCHJOIN_LOCKED
			: TOUCHJOIN_OPEN;
	return m;
}

static inline const char *TouchMode_Name(int mode)
{
	static const char *names[TOUCHMODE_COUNT] = {
		"off", "playing", "dead", "spectating", "following", "out", "out_free", "intermission", "mapvote"
	};
	return mode >= 0 && mode < TOUCHMODE_COUNT ? names[mode] : "off";
}

static inline const char *TouchMode_JoinName(int join)
{
	switch (join)
	{
	case TOUCHJOIN_OPEN: return "open";
	case TOUCHJOIN_PENDING: return "pending";
	case TOUCHJOIN_QUEUED: return "queued";
	case TOUCHJOIN_LOCKED: return "locked";
	default: return "none";
	}
}

// ---------------------------------------------------------------------------
// Controls per mode. A control also needs its own layout switch
// (touch_<name>_visible) and, for some, a weapon that has it (ALT, ZOOM,
// HOOK): the mode only says whether it may be there at all.
// ---------------------------------------------------------------------------
#define TOUCHCTL_MENU     (1 << 0)
#define TOUCHCTL_CHAT     (1 << 1)
#define TOUCHCTL_SCORE    (1 << 2)
#define TOUCHCTL_MOVE     (1 << 3)
#define TOUCHCTL_LOOK     (1 << 4)
#define TOUCHCTL_FIRE     (1 << 5)
#define TOUCHCTL_ALT      (1 << 6)
#define TOUCHCTL_HOP      (1 << 7)
#define TOUCHCTL_DUCK     (1 << 8)
#define TOUCHCTL_WEAPONS  (1 << 9)    // the strip, its grip, WEP
#define TOUCHCTL_ZOOM     (1 << 10)
#define TOUCHCTL_HOOK     (1 << 11)
#define TOUCHCTL_NADE     (1 << 12)
#define TOUCHCTL_TURN     (1 << 13)
#define TOUCHCTL_RELOAD   (1 << 14)
#define TOUCHCTL_DODGE    (1 << 15)
#define TOUCHCTL_VITALS   (1 << 16)
#define TOUCHCTL_SPECBAR  (1 << 17)   // previous / follow-or-free / next
#define TOUCHCTL_FLY      (1 << 18)   // up / down while free-flying
#define TOUCHCTL_JOIN     (1 << 19)   // the join slider ("Swipe to join"), spectators only
#define TOUCHCTL_TOSPEC   (1 << 20)   // "move to spectators", when placed
#define TOUCHCTL_READY    (1 << 21)
#define TOUCHCTL_VOTECHIP (1 << 22)   // a vote called in the match
#define TOUCHCTL_RESPAWN  (1 << 23)   // HOP's place, as RESPAWN

#define TOUCHCTL_CHROME (TOUCHCTL_MENU | TOUCHCTL_CHAT | TOUCHCTL_SCORE | TOUCHCTL_VOTECHIP)
#define TOUCHCTL_PLAY (TOUCHCTL_MOVE | TOUCHCTL_LOOK | TOUCHCTL_FIRE | TOUCHCTL_ALT | TOUCHCTL_HOP | TOUCHCTL_DUCK \
	| TOUCHCTL_WEAPONS | TOUCHCTL_ZOOM | TOUCHCTL_HOOK | TOUCHCTL_NADE | TOUCHCTL_TURN | TOUCHCTL_RELOAD \
	| TOUCHCTL_DODGE | TOUCHCTL_VITALS | TOUCHCTL_TOSPEC)

static inline int TouchMode_Controls(int mode, int flags)
{
	int c;
	switch (mode)
	{
	case TOUCHMODE_PLAYING:
		c = TOUCHCTL_CHROME | TOUCHCTL_PLAY;
		break;
	case TOUCHMODE_DEAD:
		c = TOUCHCTL_CHROME | TOUCHCTL_LOOK | TOUCHCTL_RESPAWN | TOUCHCTL_TOSPEC;
		break;
	case TOUCHMODE_OBSERVING:
		c = TOUCHCTL_CHROME | TOUCHCTL_MOVE | TOUCHCTL_LOOK | TOUCHCTL_SPECBAR | TOUCHCTL_FLY | TOUCHCTL_JOIN;
		break;
	case TOUCHMODE_OUT_FREE:
		c = TOUCHCTL_CHROME | TOUCHCTL_MOVE | TOUCHCTL_LOOK | TOUCHCTL_SPECBAR | TOUCHCTL_FLY;
		break;
	case TOUCHMODE_FOLLOWING:
		c = TOUCHCTL_CHROME | TOUCHCTL_SPECBAR | TOUCHCTL_VITALS | TOUCHCTL_JOIN;
		break;
	case TOUCHMODE_OUT:
		c = TOUCHCTL_CHROME | TOUCHCTL_SPECBAR | TOUCHCTL_VITALS;
		break;
	case TOUCHMODE_INTERMISSION:
	case TOUCHMODE_MAPVOTE:
		// The scoreboard and the vote own the screen: MENU only (and the
		// console button, touch_shellbtn.c, which is not part of the HUD).
		return TOUCHCTL_MENU;
	default:
		return 0;
	}
	if (flags & TOUCHMODE_F_READY)
		c |= TOUCHCTL_READY;
	return c;
}

// ---------------------------------------------------------------------------
// The in-match MENU sheet's actions, for the touch menu and the native
// pause menus (docs/CONTROLS.md, "Menu actions"). Fixed order.
// ---------------------------------------------------------------------------
#define TOUCHACT_RESUME   0
#define TOUCHACT_JOIN     1
#define TOUCHACT_SPECTATE 2   // "Move to spectators"
#define TOUCHACT_TEAM     3   // "Change team"
#define TOUCHACT_SETTINGS 4
#define TOUCHACT_LEAVE    5
#define TOUCHACT_COUNT    6

#define TOUCHACT_HIDDEN   0
#define TOUCHACT_ENABLED  1
#define TOUCHACT_DISABLED 2   // shown, not now (a join being answered, in the queue)
#define TOUCHACT_CONFIRM  3   // enabled, asks first: it would forfeit something

static inline const char *TouchAct_Id(int act)
{
	static const char *ids[TOUCHACT_COUNT] = { "resume", "join", "spectate", "team", "settings", "leave" };
	return act >= 0 && act < TOUCHACT_COUNT ? ids[act] : "";
}

static inline const char *TouchAct_Label(int act)
{
	static const char *labels[TOUCHACT_COUNT] = { "Resume", "Join game", "Spectate", "Change team", "Settings", "Leave" };
	return act >= 0 && act < TOUCHACT_COUNT ? labels[act] : "";
}

// Moving to the spectators forfeits something mid-round in a round-based
// game (the life in this round) or while holding an objective.
static inline int TouchMode_SpectateForfeits(const touchmode_t *m)
{
	return (m->flags & (TOUCHMODE_F_ROUND | TOUCHMODE_F_CARRY)) != 0;
}

static inline int TouchAct_State(const touchmode_t *m, int act)
{
	if (m->mode == TOUCHMODE_OFF)
		return TOUCHACT_HIDDEN;
	switch (act)
	{
	case TOUCHACT_RESUME:
	case TOUCHACT_SETTINGS:
	case TOUCHACT_LEAVE:
		return TOUCHACT_ENABLED;
	case TOUCHACT_JOIN:
		if (!TouchMode_IsSpectating(m->mode))
			return TOUCHACT_HIDDEN;
		return m->join == TOUCHJOIN_OPEN || m->join == TOUCHJOIN_LOCKED ? TOUCHACT_ENABLED : TOUCHACT_DISABLED;
	case TOUCHACT_SPECTATE:
		if (m->mode == TOUCHMODE_PLAYING || m->mode == TOUCHMODE_DEAD)
			return TouchMode_SpectateForfeits(m) ? TOUCHACT_CONFIRM : TOUCHACT_ENABLED;
		if (TouchMode_IsOutOfRound(m->mode))
			return TOUCHACT_ENABLED;
		return TOUCHACT_HIDDEN;
	case TOUCHACT_TEAM:
		if (!(m->flags & TOUCHMODE_F_TEAMS) || TouchMode_IsMatchEnd(m->mode))
			return TOUCHACT_HIDDEN;
		return TOUCHACT_ENABLED;
	default:
		return TOUCHACT_HIDDEN;
	}
}

// The console command an action runs. JOIN and TEAM open the join dialog
// (touch_join_dialog: Xonotic's Team Selection, with auto-select, the teams
// the server offers and spectate).
static inline const char *TouchAct_Command(const touchmode_t *m, int act)
{
	(void)m;
	switch (act)
	{
	case TOUCHACT_RESUME: return "togglemenu 0";
	case TOUCHACT_JOIN: return "touch_join_dialog";
	case TOUCHACT_SPECTATE: return "spec";
	case TOUCHACT_TEAM: return "touch_join_dialog";
	case TOUCHACT_SETTINGS: return "menu_cmd settings";
	case TOUCHACT_LEAVE: return "disconnect; g_campaign 0; menu_cmd sync; menu_cmd directmenu Welcome RESET";
	default: return "";
	}
}

// ---------------------------------------------------------------------------
// Joining. The join dialog is Xonotic's own (the menu's Team Selection:
// "join 'best' team (auto-select)", the teams, "spectate"), finger-sized on a
// touchscreen. It is owed once per match to a player who starts it as an
// observer, and opens when that has lasted a moment and nothing else has the
// screen; after that it is the MENU sheet's "Join game" (observing) and
// "Change team". A game hosted on this device with no team to pick skips it:
// the player goes straight in ("cmd join", a few tries while the server is
// not ready for it). Picking Spectate, or being in the game once, settles
// it: spectating later in the same match is the player's own choice.
// ---------------------------------------------------------------------------
#define TOUCHJOINDLG_SETTLE  0.75f  // seconds as an observer before it opens
#define TOUCHJOINDLG_FREE    0.50f  // seconds the screen has been free (a menu, the pause page just closed)
#define TOUCHJOINDLG_RETRY   1.5f   // a game hosted here: between "join" tries
#define TOUCHJOINDLG_TRIES   6

#define TOUCHJOINDLG_NOTHING 0
#define TOUCHJOINDLG_OPEN    1   // open the join dialog
#define TOUCHJOINDLG_JOIN    2   // hosted here, no team to pick: join now

typedef struct touchjoindlg_s
{
	int owed;       // this match still owes the observer the dialog
	int tries;      // straight-in joins left (a game hosted here)
	double since;   // an observer since, 0 = not
	double next;    // nothing before this time
	double free_since;  // the game has had the screen since, 0 = not
} touchjoindlg_t;

// One frame. local_game: the server is this process. campaign: the campaign
// joins by itself. screen_free: the game has the screen (no menu, console,
// chat sheet or CSQC pointer UI). enabled: touch_join_dialog allows it here.
static inline int TouchJoinDlg_Frame(touchjoindlg_t *d, int mode, int flags, int local_game, int campaign, int screen_free, int enabled, double now)
{
	if (mode == TOUCHMODE_OFF || TouchMode_IsMatchEnd(mode))
	{
		d->owed = 1;
		d->tries = TOUCHJOINDLG_TRIES;
		d->since = 0;
		d->next = 0;
		d->free_since = 0;
		return TOUCHJOINDLG_NOTHING;
	}
	if (mode == TOUCHMODE_PLAYING || TouchMode_IsOutOfRound(mode))
	{
		d->owed = 0;
		d->since = 0;
		return TOUCHJOINDLG_NOTHING;
	}
	if (!TouchMode_IsSpectating(mode))
	{
		// dead: also what a client reads before its first scoreboard arrives
		d->since = 0;
		return TOUCHJOINDLG_NOTHING;
	}
	if (!d->owed || campaign)
		return TOUCHJOINDLG_NOTHING;
	if (!d->since)
		d->since = now;
	// Never in the frames a menu (the pause page) goes away: a tap meant for
	// it must not land on the dialog, and the two must not look like one.
	if (!screen_free)
		d->free_since = 0;
	else if (!d->free_since)
		d->free_since = now;
	if (now - d->since < TOUCHJOINDLG_SETTLE || now < d->next || !screen_free || now - d->free_since < TOUCHJOINDLG_FREE)
		return TOUCHJOINDLG_NOTHING;
	if (local_game && !(flags & TOUCHMODE_F_TEAMS) && d->tries > 0)
	{
		d->tries--;
		d->next = now + TOUCHJOINDLG_RETRY;
		return TOUCHJOINDLG_JOIN;
	}
	d->owed = 0;
	return enabled ? TOUCHJOINDLG_OPEN : TOUCHJOINDLG_NOTHING;
}

// The player answered the dialog (or the MENU sheet's Spectate): nothing is
// owed any more in this match.
static inline void TouchJoinDlg_Answered(touchjoindlg_t *d)
{
	d->owed = 0;
}

// What a choice in the dialog sends. Choices: "auto", "red", "blue",
// "yellow", "pink", "spectate". teams: a team game. Returns "" for a choice
// that is not one. The same commands the stock dialog's buttons run
// (team_auto, team_red, ...; "cmd spectate"), without the menu toggles.
static inline const char *TouchJoinDlg_Command(const char *choice, int teams)
{
	if (!strcmp(choice, "auto")) return teams ? "cmd selectteam auto; cmd join" : "cmd join";
	if (!strcmp(choice, "red")) return "cmd selectteam red; cmd join red";
	if (!strcmp(choice, "blue")) return "cmd selectteam blue; cmd join blue";
	if (!strcmp(choice, "yellow")) return "cmd selectteam yellow; cmd join yellow";
	if (!strcmp(choice, "pink")) return "cmd selectteam pink; cmd join pink";
	if (!strcmp(choice, "spectate")) return "cmd spectate";
	return "";
}

// ---------------------------------------------------------------------------
// The join slider: "Swipe to join" on the spectator HUD (free-fly and
// following). A wide track at the bottom middle, between the stick's rocker
// and the spectator bar, its knob at the left end. Pushing the knob right
// past most of the track and letting go joins (in a team game it opens the
// join dialog, to pick the team); short of that it springs back. A tap on it
// (or a click at a desk) does the same as a full swipe. A finger that sets
// off up or down was looking around: it is handed to the look.
// It keeps clear of the system's gestures: the bottom edge (Home, the app
// switcher's sideways swipe along the bar) and the side edges (Back).
// Positions in console units, u = units per dp, times in seconds.
// ---------------------------------------------------------------------------
#define TOUCHSLIDE_H_DP        56.0f   // the track's height: a primary control
#define TOUCHSLIDE_W_DP        300.0f  // its width, when there is room
#define TOUCHSLIDE_MIN_W_DP    200.0f  // narrower than this: it goes above the bottom row
#define TOUCHSLIDE_GAP_DP      16.0f   // from its neighbours
#define TOUCHSLIDE_EDGE_DP     48.0f   // from the bottom edge, inside the safe area
#define TOUCHSLIDE_ACCEPT      0.70f   // of the knob's travel, on release
#define TOUCHSLIDE_TAP_DP      12.0f   // a tap moves less than this ...
#define TOUCHSLIDE_TAP_TIME    0.45f   // ... and is up within this
#define TOUCHSLIDE_LOOK_DP     14.0f   // this far up or down before right: the look's

#define TOUCHSLIDE_TRACKING 0
#define TOUCHSLIDE_ACCEPTED 1   // pushed far enough
#define TOUCHSLIDE_TAPPED   2   // a tap: the same as accepted
#define TOUCHSLIDE_SHORT    3   // let go too early: springs back
#define TOUCHSLIDE_LOOK     4   // handed to the look

typedef struct touchslide_s
{
	float x0, y0, x, y;
	double t0, t;
	float travel;   // how far the knob goes, units
	float u;        // units per dp
	float moved;    // the farthest the finger got from where it landed
	int state;
} touchslide_t;

static inline void TouchSlide_Begin(touchslide_t *s, float x, float y, double t, float travel, float u)
{
	s->x0 = s->x = x;
	s->y0 = s->y = y;
	s->t0 = s->t = t;
	s->travel = travel > 1 ? travel : 1;
	s->u = u > 0 ? u : 1;
	s->moved = 0;
	s->state = TOUCHSLIDE_TRACKING;
}

static inline int TouchSlide_Move(touchslide_t *s, float x, float y, double t)
{
	float dx, dy, ady, d;
	if (s->state != TOUCHSLIDE_TRACKING)
		return s->state;
	s->x = x;
	s->y = y;
	s->t = t;
	dx = x - s->x0;
	dy = y - s->y0;
	ady = dy < 0 ? -dy : dy;
	d = (dx < 0 ? -dx : dx) > ady ? (dx < 0 ? -dx : dx) : ady;
	if (d > s->moved)
		s->moved = d;
	// mostly up or down before the knob got going: a look, not a join
	if (ady > TOUCHSLIDE_LOOK_DP * s->u && ady > (dx > 0 ? dx : 0) * 1.2f && dx < s->travel * 0.15f)
		s->state = TOUCHSLIDE_LOOK;
	return s->state;
}

// How far the knob is along, 0 at rest, 1 at the far end.
static inline float TouchSlide_Progress(const touchslide_t *s)
{
	float p = (s->x - s->x0) / s->travel;
	return p < 0 ? 0 : p > 1 ? 1 : p;
}

static inline int TouchSlide_End(touchslide_t *s, float x, float y, double t)
{
	if (TouchSlide_Move(s, x, y, t) != TOUCHSLIDE_TRACKING)
		return s->state;
	if (TouchSlide_Progress(s) >= TOUCHSLIDE_ACCEPT)
		s->state = TOUCHSLIDE_ACCEPTED;
	else if (s->moved < TOUCHSLIDE_TAP_DP * s->u && t - s->t0 <= TOUCHSLIDE_TAP_TIME)
		s->state = TOUCHSLIDE_TAPPED;
	else
		s->state = TOUCHSLIDE_SHORT;
	return s->state;
}

static inline int TouchSlide_Joins(int state)
{
	return state == TOUCHSLIDE_ACCEPTED || state == TOUCHSLIDE_TAPPED;
}

static inline const char *TouchSlide_Name(int state)
{
	switch (state)
	{
	case TOUCHSLIDE_TRACKING: return "tracking";
	case TOUCHSLIDE_ACCEPTED: return "accepted";
	case TOUCHSLIDE_TAPPED: return "tapped";
	case TOUCHSLIDE_SHORT: return "short";
	case TOUCHSLIDE_LOOK: return "look";
	default: return "?";
	}
}

// Where the track goes. left / right: the inner edges of what is beside it in
// the bottom row (the stick's ring and its rocker on one side, the spectator
// bar on the other); row_cy: that row's middle; row_top: the top of the
// bottom row's controls, for when the gap between them is too narrow.
typedef struct touchslide_in_s
{
	float w, h, u;
	float in_l, in_r, in_b;   // safe-area insets
	float left, right;
	float row_cy, row_top;
} touchslide_in_t;

typedef struct touchslide_rect_s
{
	float cx, cy, hx, hy;
	int above;   // no room in the row: it sits above it
} touchslide_rect_t;

static inline touchslide_rect_t TouchSlide_Layout(const touchslide_in_t *in)
{
	touchslide_rect_t r;
	float u = in->u, gap = TOUCHSLIDE_GAP_DP * u, edge = TOUCHSLIDE_EDGE_DP * u;
	float lo = in->left > in->in_l + edge ? in->left : in->in_l + edge;
	float hi = in->right < in->w - in->in_r - edge ? in->right : in->w - in->in_r - edge;
	float room = hi - lo - 2 * gap, want = TOUCHSLIDE_W_DP * u, lowest;
	r.hy = TOUCHSLIDE_H_DP * 0.5f * u;
	lowest = in->h - in->in_b - edge - r.hy;
	r.above = room < TOUCHSLIDE_MIN_W_DP * u;
	if (!r.above)
	{
		r.hx = (room < want ? room : want) * 0.5f;
		r.cx = (lo + hi) * 0.5f;
		r.cy = in->row_cy < lowest ? in->row_cy : lowest;
	}
	else
	{
		float wide = in->w - in->in_l - in->in_r - 2 * edge;
		r.hx = (wide < want ? wide : want) * 0.5f;
		r.cx = (in->in_l + in->w - in->in_r) * 0.5f;
		r.cy = in->row_top - gap - r.hy;
		if (r.cy > lowest)
			r.cy = lowest;
	}
	return r;
}

// The knob: a circle at the track's left end, inset; its travel is the rest.
static inline float TouchSlide_KnobRadius(const touchslide_rect_t *r)
{
	return r->hy * 0.84f;
}

static inline float TouchSlide_Travel(const touchslide_rect_t *r)
{
	return 2 * (r->hx - r->hy);
}

// ---------------------------------------------------------------------------
// Teams, for the join dialog's player counts: Xonotic's team colours as the
// scoreboard's colours byte has them (pants: red 4, blue 13, yellow 12,
// pink 9). Returns the team's bit (1 red, 2 blue, 4 yellow, 8 pink, as
// _teams_available), 0 for no team.
// ---------------------------------------------------------------------------
static inline int TouchJoin_TeamBit(int colors)
{
	switch (colors & 15)
	{
	case 4: return 1;
	case 13: return 2;
	case 12: return 4;
	case 9: return 8;
	default: return 0;
	}
}

// ---------------------------------------------------------------------------
// Sizes in density-independent pixels: primary actions 56-64 dp, secondary
// 46-48 dp, never a touch target under 48 dp.
// ---------------------------------------------------------------------------
#define TOUCHDP_PRIMARY     60.0f
#define TOUCHDP_SECONDARY   46.0f
#define TOUCHDP_MIN_TARGET  48.0f
#define TOUCHDP_PILL_HEIGHT 36.0f   // drawn; its target is TOUCHDP_MIN_TARGET or more

// Physical pixels per dp from a reported dpi; without one, a guess from the
// shorter side (a 1080 px phone is about 2.6), never below 1.
static inline float TouchDp_Density(float dpi, float short_px)
{
	float d;
	if (dpi > 1 && dpi != 160)
		d = dpi / 160.0f;
	else
		d = short_px >= 900 ? short_px / 420.0f : 1.0f;
	return d < 1 ? 1 : d > 4 ? 4 : d;
}

#endif
