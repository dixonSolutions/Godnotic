/*
Godnotic: free orbit camera after death and at the end of a round or match.

The camera is the game's own event chase, so a finger or the wheel only has
three things to do, none of which sends a command to the server:
  - turn the view (cl.viewangles) where nothing else does: at the end of the
    match, on the vote screen;
  - scale cl_eventchase_distance (pinch, wheel), restored afterwards;
  - while dead, switch the chase on at the first gesture (cl_eventchase_death 1)
    instead of waiting for the corpse to stop.
A finger that lands on a control, a pill or a vote entry never orbits, and
nothing is consumed: a tap still votes and still respawns. See
touch_orbit_logic.h and docs/CONTROLS.md.
*/
#include "quakedef.h"
#include "csprogs.h"
#include "touch_orbit.h"
#include "touch_orbit_logic.h"
#include "touch_hud.h"
#include "touch_hudmode.h"
#include "touch_feel.h"
#include "input.h"
#include "keys.h"

extern float multitouch[11][3];
#define ORBIT_FINGERS 12          // the engine's 11 slots (the last is a mouse standing in for a finger), then the vote mouse
#define ORBIT_MOUSE (ORBIT_FINGERS - 1)
extern cvar_t vid_touchscreen;
extern cvar_t vid_conwidth;
extern cvar_t vid_conheight;

static cvar_t touch_orbit = {CF_CLIENT | CF_ARCHIVE, "touch_orbit", "1", "orbit the camera with a finger drag, pinch or the mouse wheel after death and at the end of a round or match"};
static cvar_t touch_orbit_min = {CF_CLIENT | CF_ARCHIVE, "touch_orbit_min", "0.25", "closest the orbit camera zooms, as a fraction of cl_eventchase_distance"};
static cvar_t touch_orbit_max = {CF_CLIENT | CF_ARCHIVE, "touch_orbit_max", "4", "farthest the orbit camera zooms, as a multiple of cl_eventchase_distance"};
static cvar_t orbit_pinch = {CF_CLIENT, "_touch_orbit_pinch", "0", "1 while a pinch is zooming the orbit camera (the look stands still)"};

typedef struct ofinger_s
{
	int down;
	int eligible, decided, dragged, tainted;
	int frames;
	float sx, sy, x, y, px, py;  // landing, latest and previous frame, console units
} ofinger_t;

static struct
{
	int ctx;
	int engaged;
	float mult, want;
	cvar_t *dist_var, *death_var;
	float saved_dist, saved_death;
	int dist_set, death_set;
	float set_dist;           // what this module last wrote to the distance
	ofinger_t f[ORBIT_FINGERS];
	int pinching;
	float pinch_d;
	touchlook_t look;
	int look_f;               // the finger that turns the view
	int mouse_down, mouse_ok;
	double last_time;
} orb;

// A finger's press reaches the game as K_MOUSE1 too (touch_hud.c cursor): that
// is the same contact, not a mouse.
static int fingers_down(void)
{
	int i;
	for (i = 0; i < 11; i++)
		if (multitouch[i][0] != 0)
			return 1;
	return 0;
}

// ---- the loaded CSQC's globals --------------------------------------------
typedef struct oglobal_s { int ofs, type; } oglobal_t;
static oglobal_t g_sel;
static int stat_stopped = -1;
static void *bound_globals;

static void bind(void)
{
	prvm_prog_t *prog = CLVM_prog;
	mdef_t *d;
	mdef_t *f_id;
	bound_globals = prog->loaded ? (void *)prog->globals.fp : NULL;
	g_sel.ofs = -1;
	stat_stopped = -1;
	if (!prog->loaded)
		return;
	d = PRVM_ED_FindGlobal(prog, "mv_mouse_selection");
	if (d)
	{
		g_sel.ofs = d->ofs;
		g_sel.type = d->type & ~DEF_SAVEGLOBAL;
	}
	d = PRVM_ED_FindGlobal(prog, "STAT_GAME_STOPPED");
	f_id = PRVM_ED_FindField(prog, "m_id");
	if (d && f_id && (d->type & ~DEF_SAVEGLOBAL) == ev_entity)
	{
		int n = PRVM_GLOBALFIELDEDICT(d->ofs);
		if (n > 0 && n < prog->num_edicts)
		{
			prvm_eval_t *v = PRVM_EDICTFIELDVALUE(PRVM_EDICT_NUM(n), f_id->ofs);
			int id = (f_id->type & ~DEF_SAVEGLOBAL) == ev_float ? (int)v->_float : v->_int;
			if (id >= 0 && id < MAX_CL_STATS)
				stat_stopped = id;
		}
	}
}

// 1 and the value when the CSQC has the vote highlight, else 0.
static int vote_selection(int *sel)
{
	prvm_prog_t *prog = CLVM_prog;
	if (!prog->loaded)
		return 0;
	if (bound_globals != (void *)prog->globals.fp)
		bind();
	if (g_sel.ofs < 0 || g_sel.ofs >= prog->numglobals)
		return 0;
	*sel = g_sel.type == ev_float ? (int)PRVM_GLOBALFIELDFLOAT(g_sel.ofs) : PRVM_GLOBALFIELDVALUE(g_sel.ofs)->_int;
	return 1;
}

static float cv(const char *name, float def)
{
	cvar_t *v = Cvar_FindVar(&cvars_all, name, ~0);
	return v ? v->value : def;
}
static float ui_w(void) { return max(1.0f, vid_conwidth.value); }
static float ui_h(void) { return max(1.0f, vid_conheight.value); }
static float dp_units(void)
{
	float d = 1.0f, dpi = cv("vid_touchscreen_ydpi", 0), s = min(vid.mode.width, vid.mode.height);
	if (!vid_touchscreen.integer && !cv("vid_touchscreen_detected", 0))
		d = 1;
	else if (dpi > 1 && dpi != 160)
		d = dpi / 160;
	else if (s >= 900)
		d = s / 420;
	d = d < 1 ? 1 : d > 4 ? 4 : d;
	return d * (vid.mode.height > 0 ? ui_h() / vid.mode.height : 1);
}

static void look_cfg(touchlook_cfg_t *c)
{
	c->sens = cv("touch_sens_base", 3.6f);
	c->y_mult = cv("touch_sens_y_mult", 0.85f);
	c->accel = cv("touch_look_accel", 0.8f);
	c->accel_lo = cv("touch_look_accel_lo", 200);
	c->accel_hi = cv("touch_look_accel_hi", 800);
	c->filter = (int)cv("touch_look_filter", 1);
	c->fcmin = cv("touch_look_fcmin", 4.0f);
	c->beta = cv("touch_look_beta", 0.05f);
	c->max_rate = cv("touch_look_max_deg_per_s", 900) * max(1.0f, c->sens / 2.8f) * (1 + max(0.0f, c->accel));
}

static void turn(float yaw, float pitch)
{
	if (cv("touch_invert_y", 0))
		pitch = -pitch;
	cl.viewangles[YAW] -= yaw;
	cl.viewangles[PITCH] += pitch;
	cl.viewangles[PITCH] = bound(-89, cl.viewangles[PITCH], 89);
	cl.viewangles[ROLL] = 0;
}

// ---- the user's own cvars ---------------------------------------------------
static void restore_distance(void)
{
	if (orb.dist_set && orb.dist_var && fabs(orb.dist_var->value - orb.set_dist) < 0.01f)
		Cvar_SetValueQuick(orb.dist_var, orb.saved_dist);
	orb.dist_set = 0;
}

static void restore_death(void)
{
	if (orb.death_set && orb.death_var && orb.death_var->integer == 1)
		Cvar_SetValueQuick(orb.death_var, orb.saved_death);
	orb.death_set = 0;
}

void TouchOrbit_RestoreCvars(void)
{
	restore_distance();
	restore_death();
}

static void deactivate(void)
{
	TouchOrbit_RestoreCvars();
	memset(&orb.f, 0, sizeof(orb.f));
	orb.ctx = TOUCHORBIT_CTX_NONE;
	orb.engaged = 0;
	orb.mult = orb.want = 1;
	orb.pinching = 0;
	orb.mouse_down = 0;
	if (orbit_pinch.integer)
		Cvar_SetValueQuick(&orbit_pinch, 0);
}

qbool TouchOrbit_Pinching(void)
{
	return orb.pinching != 0;
}

static int map_vote_up(void)
{
	return TouchHudMode_Current()->mode == TOUCHMODE_MAPVOTE;
}

// ---- eligibility -------------------------------------------------------------
static void land(int i, float x, float y)
{
	ofinger_t *f = &orb.f[i];
	int second = 0, k;
	memset(f, 0, sizeof(*f));
	f->down = 1;
	f->sx = f->x = x;
	f->sy = f->y = y;
	for (k = 0; k < ORBIT_FINGERS; k++)
		if (k != i && orb.f[k].down)
			second = 1;
	f->eligible = TouchHUD_PointFree(x, y) != 0;
	f->decided = 1;
	Con_DPrintf("TouchOrbit: contact %d lands at %.0f,%.0f: %s%s\n", i, x, y, f->eligible ? "free" : "on a control", second ? " (second)" : "");
	// the first finger on a vote is the engine's pointer: it votes if it landed on an entry
	if (f->eligible && !second && map_vote_up() && i != ORBIT_MOUSE)
		f->decided = 0;
}

static void decide_vote_finger(ofinger_t *f)
{
	int sel = 0, known;
	f->decided = 1;
	known = vote_selection(&sel); // a separate statement: the argument order of a call is not defined
	f->eligible = f->eligible && TouchOrbit_VoteEligible(known, sel);
	Con_DPrintf("TouchOrbit: vote finger at %.0f,%.0f: highlight %d, %s\n", f->sx, f->sy, sel, f->eligible ? "orbits" : "left to the vote");
}

// ---- the frame ----------------------------------------------------------------
void TouchOrbit_Frame(void)
{
	double now = host.realtime;
	float dt = (float)bound(1.0 / 240, now - orb.last_time, 0.1);
	const touchmode_t *m;
	int ctx, i, n_el = 0, a = -1, b = -1, stopped = 0, own_turn;
	float u = max(0.001f, dp_units()), dz = cv("touch_look_deadzone_px", 8);
	orb.last_time = now;

	if (!touch_orbit.integer || !CLVM_prog->loaded || cls.state != ca_connected || cls.signon != SIGNONS || cls.demoplayback
		|| key_dest != key_game || (key_consoleactive & KEY_CONSOLEACTIVE_USER))
	{
		if (orb.ctx != TOUCHORBIT_CTX_NONE)
			deactivate();
		return;
	}
	if (bound_globals != (void *)CLVM_prog->globals.fp)
		bind();
	if (stat_stopped >= 0)
		stopped = cl.stats[stat_stopped] != 0;
	m = TouchHudMode_Current();
	ctx = TouchOrbit_Context(m->mode, stopped);
	if (ctx == TOUCHORBIT_CTX_NONE)
	{
		if (orb.ctx != TOUCHORBIT_CTX_NONE)
			deactivate();
		return;
	}
	if (orb.ctx == TOUCHORBIT_CTX_NONE)
	{
		deactivate();
		orb.dist_var = Cvar_FindVar(&cvars_all, "cl_eventchase_distance", ~0);
		orb.death_var = Cvar_FindVar(&cvars_all, "cl_eventchase_death", ~0);
	}
	orb.ctx = ctx;

	// contacts
	for (i = 0; i < ORBIT_FINGERS; i++)
	{
		int down;
		float x, y;
		if (i == ORBIT_MOUSE)
		{
			down = orb.mouse_down && cl.csqc_wantsmousemove && !fingers_down();
			x = in_windowmouse_x * ui_w() / max(1, vid.mode.width);
			y = in_windowmouse_y * ui_h() / max(1, vid.mode.height);
			if (!down && orb.f[i].down)
				orb.f[i].down = 0;
			if (down && !orb.f[i].down)
			{
				land(i, x, y);
				orb.f[i].eligible = orb.mouse_ok != 0;
			}
		}
		else
		{
			down = multitouch[i][0] != 0;
			x = multitouch[i][1] * ui_w();
			y = multitouch[i][2] * ui_h();
			if (!down)
			{
				orb.f[i].down = 0;
				continue;
			}
			if (!orb.f[i].down)
				land(i, x, y);
		}
		if (!orb.f[i].down)
			continue;
		orb.f[i].x = x;
		orb.f[i].y = y;
		orb.f[i].frames++;
		if (!orb.f[i].decided && orb.f[i].frames >= 2)
			decide_vote_finger(&orb.f[i]);
		if (orb.f[i].decided && orb.f[i].eligible)
		{
			n_el++;
			if (a < 0) a = i; else if (b < 0) b = i;
		}
	}

	// pinch
	if (n_el >= 2)
	{
		float d = (float)sqrt((orb.f[a].x - orb.f[b].x) * (orb.f[a].x - orb.f[b].x) + (orb.f[a].y - orb.f[b].y) * (orb.f[a].y - orb.f[b].y));
		if (orb.pinching)
			orb.want = TouchOrbit_Pinch(orb.want, orb.pinch_d, d, touch_orbit_min.value, touch_orbit_max.value);
		orb.pinch_d = d;
		orb.pinching = 1;
		orb.f[a].tainted = orb.f[b].tainted = 1;
		if (ctx == TOUCHORBIT_CTX_DEAD)
			orb.engaged = 1;
	}
	else
		orb.pinching = 0;
	if (orbit_pinch.integer != orb.pinching)
		Cvar_SetValueQuick(&orbit_pinch, orb.pinching);

	// one finger: turn where nothing else does; note the first drag while dead
	own_turn = (vid_touchscreen.integer || cl.csqc_wantsmousemove) && ctx == TOUCHORBIT_CTX_INTER;
	if (n_el == 1 && !orb.pinching)
	{
		ofinger_t *f = &orb.f[a];
		if (!f->tainted)
		{
			float dx = 0, dy = 0;
			if (!f->dragged && TouchOrbit_IsDrag(f->sx, f->sy, f->x, f->y, dz))
			{
				f->dragged = 1;
				dx = f->x - f->sx;
				dy = f->y - f->sy;
				orb.look_f = -1;
			}
			else if (f->dragged)
			{
				dx = f->x - f->px;
				dy = f->y - f->py;
			}
			if (f->dragged && ctx == TOUCHORBIT_CTX_DEAD)
				orb.engaged = 1;
			if (f->dragged && own_turn)
			{
				touchlook_cfg_t c;
				float yaw, pitch;
				if (orb.look_f != a)
				{
					TouchLook_Reset(&orb.look);
					orb.look_f = a;
				}
				look_cfg(&c);
				TouchLook_Step(&orb.look, &c, dx / u, dy / u, dt, &yaw, &pitch);
				turn(yaw, pitch);
			}
		}
	}
	for (i = 0; i < ORBIT_FINGERS; i++)
		if (orb.f[i].down)
		{
			orb.f[i].px = orb.f[i].x;
			orb.f[i].py = orb.f[i].y;
		}

	// zoom
	orb.mult = TouchOrbit_Ease(orb.mult, orb.want, dt);
	if (orb.dist_var)
	{
		if (!orb.dist_set && fabs(orb.mult - 1) > 0.001f)
		{
			orb.saved_dist = orb.dist_var->value;
			orb.dist_set = 1;
		}
		if (orb.dist_set)
		{
			orb.set_dist = TouchOrbit_Distance(orb.saved_dist, orb.mult);
			if (fabs(orb.dist_var->value - orb.set_dist) > 0.01f)
				Cvar_SetValueQuick(orb.dist_var, orb.set_dist);
		}
	}
	// the death chase at the first gesture
	if (orb.death_var && ctx == TOUCHORBIT_CTX_DEAD && orb.engaged && !orb.death_set && orb.death_var->integer != 1)
	{
		orb.saved_death = orb.death_var->value;
		orb.death_set = 1;
		Cvar_SetValueQuick(orb.death_var, 1);
	}
}

qbool TouchOrbit_Key(int key, qbool down)
{
	if (orb.ctx == TOUCHORBIT_CTX_NONE || !touch_orbit.integer)
		return false;
	if (key == K_MWHEELUP || key == K_MWHEELDOWN)
	{
		if (down)
		{
			orb.want = TouchOrbit_Wheel(orb.want, key == K_MWHEELUP, touch_orbit_min.value, touch_orbit_max.value);
			if (orb.ctx == TOUCHORBIT_CTX_DEAD)
				orb.engaged = 1;
		}
		return true;
	}
	if (key == K_MOUSE1 && orb.ctx == TOUCHORBIT_CTX_INTER && cl.csqc_wantsmousemove && !fingers_down())
	{
		int sel = 0, known;
		orb.mouse_down = down;
		// over empty space only; a press on a vote entry is the vote and nothing more
		known = vote_selection(&sel);
		orb.mouse_ok = down && TouchOrbit_VoteEligible(known, sel);
	}
	return false;
}

static void orbit_state_f(cmd_state_t *cmd)
{
	Con_Printf("TOUCHORBIT ctx=%d zoom=%.3f want=%.3f dist=%.1f yaw=%.1f pitch=%.1f engaged=%d pinch=%d\n", orb.ctx, orb.mult, orb.want,
		orb.dist_var ? orb.dist_var->value : -1.0f, cl.viewangles[YAW], cl.viewangles[PITCH], orb.engaged, orb.pinching);
}

void TouchOrbit_Init(void)
{
	Cvar_RegisterVariable(&touch_orbit);
	Cvar_RegisterVariable(&touch_orbit_min);
	Cvar_RegisterVariable(&touch_orbit_max);
	Cvar_RegisterVariable(&orbit_pinch);
	Cmd_AddCommand(CF_CLIENT, "touch_orbit_state", orbit_state_f, "print the orbit camera's state (tests)");
	orb.mult = orb.want = 1;
	orb.look_f = -1;
}
