/*
Checks the smart DASH decision (engine/darkplaces/touch_dashplan.h): dodge or
weapon boost, up or forward, which weapon, and the refusals -- low health, a
server without self damage or dodging, weapons not owned, custom balance.
It runs against a world of boxes (the same shape as tests/touch-hook).

Run through scripts/test-touch-dash.sh.
*/

#include <math.h>
#include <stdbool.h>
#include <stdio.h>
#include <string.h>

typedef bool qbool;

#include "touch_dashplan.h"

static int failures, checks;
static int verbose;

#define CHECK(cond, what) do { \
	checks++; \
	if (!(cond)) { printf("FAIL %s\n", (what)); failures++; } \
} while (0)

#define K_SOLID 0
#define K_SKY   1
#define K_WATER 2
#define K_LAVA  3
#define K_HURT  4 // a trigger_hurt: not solid, a death volume

typedef struct box_s { float mins[3], maxs[3]; int kind; } box_t;

#define MAX_BOXES 32
static box_t boxes[MAX_BOXES];
static int nboxes;

static const float hull_mins[3] = { -16, -16, -24 };
static const float hull_maxs[3] = { 16, 16, 45 };

static void world_clear(void)
{
	nboxes = 0;
}

static void add_box(float x0, float y0, float z0, float x1, float y1, float z1, int kind)
{
	box_t *b = &boxes[nboxes++];
	b->mins[0] = x0; b->mins[1] = y0; b->mins[2] = z0;
	b->maxs[0] = x1; b->maxs[1] = y1; b->maxs[2] = z1;
	b->kind = kind;
}

// Entry fraction of start->end into box b grown by the hull, with the face's
// normal; -1 when it misses. A start inside counts as 0.
static float sweep(const box_t *b, const float start[3], const float end[3], int hull, float normal[3])
{
	float tmin = -1e9f, tmax = 1e9f;
	int i, axis = 2;
	float sign = 1;
	for (i = 0; i < 3; i++)
	{
		float lo = b->mins[i] - (hull ? hull_maxs[i] : 0);
		float hi = b->maxs[i] - (hull ? hull_mins[i] : 0);
		float d = end[i] - start[i];
		if (fabsf(d) < 1e-6f)
		{
			if (start[i] <= lo || start[i] >= hi)
				return -1;
			continue;
		}
		{
			float t0 = (lo - start[i]) / d, t1 = (hi - start[i]) / d;
			float s = -1;
			if (t0 > t1)
			{
				float t = t0;
				t0 = t1;
				t1 = t;
				s = 1;
			}
			if (t0 > tmin)
			{
				tmin = t0;
				axis = i;
				sign = s;
			}
			if (t1 < tmax)
				tmax = t1;
		}
	}
	if (tmin > tmax || tmax <= 0 || tmin > 1)
		return -1;
	normal[0] = normal[1] = normal[2] = 0;
	normal[axis] = sign;
	return tmin < 0 ? 0 : tmin;
}

static void world_trace(void *ctx, const float start[3], const float end[3], int hull, tr_trace_t *out)
{
	int i;
	float best = 2, n[3];
	(void)ctx;
	out->fraction = 1;
	out->hit = TR_HIT_NONE;
	out->startsolid = 0;
	out->normal[0] = out->normal[1] = 0;
	out->normal[2] = 1;
	for (i = 0; i < nboxes; i++)
	{
		float f;
		if (boxes[i].kind == K_HURT)
			continue;
		f = sweep(&boxes[i], start, end, hull, n);
		if (f == 0 && sweep(&boxes[i], start, start, hull, n) == 0)
		{
			out->startsolid = 1; // begins inside
			out->fraction = 0;
			out->hit = boxes[i].kind == K_SOLID ? TR_HIT_SOLID : boxes[i].kind == K_WATER ? TR_HIT_WATER : TR_HIT_DEATH;
			break;
		}
		if (f >= 0 && f < best)
		{
			best = f;
			out->fraction = f;
			out->normal[0] = n[0];
			out->normal[1] = n[1];
			out->normal[2] = n[2];
			out->hit = boxes[i].kind == K_SOLID ? TR_HIT_SOLID : boxes[i].kind == K_WATER ? TR_HIT_WATER : TR_HIT_DEATH;
		}
	}
	for (i = 0; i < 3; i++)
		out->endpos[i] = start[i] + (end[i] - start[i]) * out->fraction;
}

static float world_death(void *ctx, const float start[3], const float end[3])
{
	int i;
	float best = 2, n[3];
	(void)ctx;
	for (i = 0; i < nboxes; i++)
	{
		float f;
		if (boxes[i].kind != K_HURT)
			continue;
		f = sweep(&boxes[i], start, end, 1, n);
		if (f >= 0 && f < best)
			best = f;
	}
	return best;
}

static float g_floor_z = -600;
static tr_world_t world(void)
{
	tr_world_t w;
	memset(&w, 0, sizeof(w));
	w.trace = world_trace;
	w.death = world_death;
	w.floor_z = g_floor_z;
	w.gravity = 800;
	return w;
}

static int widx(const char *n)
{
	int i;
	for (i = 0; i < TW_NUM_WEAPONS; i++)
		if (!strcmp(touch_weapons[i].netname, n))
			return i;
	return -1;
}

static void base_in(td_in_t *in)
{
	int i;
	memset(in, 0, sizeof(*in));
	in->org[2] = 24;           // standing on a floor whose top is z 0
	in->onground = 1;
	in->health = 100;
	in->armor = 0;
	in->held = widx("blaster");
	in->owned = (1u << widx("blaster")) | (1u << widx("devastator"));
	for (i = 0; i < TW_NUM_WEAPONS; i++)
		in->ammo[i] = -1;
	in->ammo[widx("devastator")] = 20;
	in->force_weapon = -1;
}

static void flat(void)
{
	world_clear();
	add_box(-3000, -3000, -64, 3000, 3000, 0, K_SOLID);
}

// A gap `w` wide starting x 100, floor both sides at z 0.
static void gap_map(float width)
{
	world_clear();
	add_box(-3000, -3000, -64, 100, 3000, 0, K_SOLID);
	add_box(100 + width, -3000, -64, 3000, 3000, 0, K_SOLID);
}

// A platform whose top is `h` high, its face at x 100.
static void ledge_map(float h)
{
	world_clear();
	add_box(-3000, -3000, -64, 3000, 3000, 0, K_SOLID);
	add_box(100, -3000, 0, 900, 3000, h, K_SOLID);
}

static void run(const td_cfg_t *c0, const td_in_t *in, td_plan_t *p, const char *name)
{
	tr_world_t w = world();
	td_cfg_t c = *c0;
	TouchDash_Plan(&w, &c, in, p);
	if (verbose)
		printf("%-34s action %d dir %d wep %s dodge %d yaw %.0f pitch %.0f land %.0f %.0f %.0f (%s) self %.1f reaches %d miss %.0f dodge_miss %.0f jump_miss %.0f cand %d: %s\n",
			name, p->action, p->dir, p->weapon >= 0 ? touch_weapons[p->weapon].netname : "-", p->dodge, p->yaw, p->pitch,
			p->land[0], p->land[1], p->land[2], p->outcome == TR_LAND ? "ground" : p->outcome == TR_VOID ? "void" : "death",
			p->self_damage, p->reaches, p->miss, p->dodge_miss, p->jump_miss, p->candidates, p->why);
}

int main(int argc, char **argv)
{
	td_cfg_t cfg, c;
	td_in_t in;
	td_plan_t p;
	(void)argv;
	verbose = argc > 1;

	TouchDash_StockCfg(&cfg);

	// --- open floor ---
	flat();
	cfg.dodging = 1;
	base_in(&in);
	in.stick_fwd = 1;
	run(&cfg, &in, &p, "flat, dodging, stick fwd");
	CHECK(p.action == TD_DODGE, "nothing to reach on a server with dodging: a plain dash");

	cfg.dodging = 0;
	run(&cfg, &in, &p, "flat, no dodging, stick fwd");
	CHECK(p.action == TD_BOOST && p.dir == TD_FWD, "no dodging, stick pushed: boosts forward");
	CHECK(p.outcome == TR_LAND, "the forward boost lands on ground");
	{
		float along = p.land[0] - in.org[0];
		CHECK(along > 250, "the forward boost carries well past a standing jump");
		CHECK(p.self_damage > 0 && p.self_damage < 55, "the forward boost costs some health, not a death");
	}
	CHECK(p.blast[0] < in.org[0], "forward: the blast is behind the player");

	in.stick_fwd = 0;
	run(&cfg, &in, &p, "flat, no dodging, idle");
	CHECK(p.action == TD_BOOST && p.dir == TD_UP, "stick idle: boosts up");
	CHECK(p.pitch > 60, "up: fired steeply down at the floor");
	CHECK(p.land[0] * p.land[0] + p.land[1] * p.land[1] < 900.0f * 900.0f, "up lands near where it started");

	// weapon choice
	cfg.dodging = 0;
	in.stick_fwd = 1;
	run(&cfg, &in, &p, "weapon in hand vs devastator");
	CHECK(p.weapon == widx("blaster") || p.weapon == widx("devastator"), "a boost weapon is chosen");
	in.owned = 1u << widx("devastator");
	in.held = widx("devastator");
	run(&cfg, &in, &p, "only devastator");
	CHECK(p.action == TD_BOOST && p.weapon == widx("devastator"), "only the owned weapon is used");
	CHECK(p.fire_delay == 0, "the weapon in hand fires at once");
	in.ammo[widx("devastator")] = 0;
	run(&cfg, &in, &p, "devastator without ammo");
	CHECK(p.action == TD_NONE, "no ammo, no other weapon: nothing, and no dodge on this server");
	in.owned = 0;
	run(&cfg, &in, &p, "no weapons at all");
	CHECK(p.action == TD_NONE, "no weapon owned: no boost");
	base_in(&in);
	in.stick_fwd = 1;
	in.force_weapon = widx("devastator");
	in.held = widx("blaster");
	run(&cfg, &in, &p, "forced devastator, blaster in hand");
	CHECK(p.weapon == widx("devastator") && p.fire_delay > 0, "a forced weapon is used, and its switch is counted");
	in.force_weapon = widx("shotgun");
	run(&cfg, &in, &p, "forced weapon that cannot boost");
	CHECK(p.action == TD_NONE, "a forced weapon with no boost table row: nothing");

	// forced direction with dodging on
	base_in(&in);
	cfg.dodging = 1;
	in.stick_fwd = 1;
	in.force_dir = TD_UP;
	run(&cfg, &in, &p, "dodging on, forced up");
	CHECK(p.action == TD_BOOST && p.dir == TD_UP, "a forced direction boosts even where dodging works");
	in.force_dir = TD_FWD;
	run(&cfg, &in, &p, "dodging on, forced forward");
	CHECK(p.action == TD_BOOST && p.dir == TD_FWD, "forced forward");

	// --- safety ---
	base_in(&in);
	cfg.dodging = 0;
	in.stick_fwd = 1;
	in.health = 30;
	run(&cfg, &in, &p, "low health");
	CHECK(p.action == TD_NONE, "low health: refused");
	in.health = 100;
	in.armor = 100;
	run(&cfg, &in, &p, "armor");
	CHECK(p.action == TD_BOOST, "armor makes it affordable again");
	base_in(&in);
	in.stick_fwd = 1;
	{
		float hp_ok = 0;
		td_cfg_t c2 = cfg;
		c2.min_health = 25;
		run(&c2, &in, &p, "baseline for floor");
		hp_ok = p.self_damage;
		in.health = 25 + hp_ok - 1;
		run(&c2, &in, &p, "one point under the floor");
		CHECK(p.action == TD_NONE || p.self_damage < hp_ok, "never ends below the health floor");
		if (p.action == TD_BOOST)
			CHECK(in.health - p.self_damage >= 25, "the chosen blast leaves the floor health");
	}
	base_in(&in);
	in.stick_fwd = 1;
	c = cfg;
	c.selfpercent = 0;
	in.health = 10;
	c.min_health = 5;
	run(&c, &in, &p, "no-self-damage server, 10 health");
	CHECK(p.action == TD_BOOST && p.self_damage == 0, "a server without self damage allows it at low health");
	c = cfg;
	c.no_push = 1;
	base_in(&in);
	in.stick_fwd = 1;
	run(&c, &in, &p, "weapon jumps off");
	CHECK(p.action == TD_NONE, "a server whose blasts do not push: no boost");

	// --- custom balance ---
	base_in(&in);
	in.stick_fwd = 1;
	run(&cfg, &in, &p, "stock balance");
	{
		float stock = p.land[0];
		c = cfg;
		for (int i = 0; i < c.nrows; i++)
			c.rows[i].force *= 0.25f;
		run(&c, &in, &p, "quarter force");
		CHECK(p.action != TD_BOOST || p.land[0] < stock - 60, "weaker server force: a shorter predicted launch");
		c = cfg;
		for (int i = 0; i < c.nrows; i++)
			c.rows[i].force *= 1.6f;
		run(&c, &in, &p, "stronger force");
		CHECK(p.action == TD_BOOST && p.land[0] > stock + 40, "stronger server force: a longer predicted launch");
	}

	// --- a gap ---
	gap_map(300);
	base_in(&in);
	in.org[0] = 70;
	in.stick_fwd = 1;
	cfg.dodging = 1;
	run(&cfg, &in, &p, "gap 300, dodging");
	CHECK(p.have_goal && p.goal_kind == TD_FWD, "a gap ahead is found as the goal");
	CHECK(p.goal[0] > 400, "the goal is on the far side");
	CHECK(p.action == TD_BOOST && p.dir == TD_FWD, "a gap a dodge cannot cross: boosts forward");
	CHECK(p.reaches && p.outcome == TR_LAND, "and the boost lands on the far side");
	CHECK(p.land[0] > 400, "landing x past the far edge");
	cfg.dodging = 0;
	run(&cfg, &in, &p, "gap 300, no dodging");
	CHECK(p.action == TD_BOOST && p.reaches, "no dodging: boosts across too");
	gap_map(120);
	cfg.dodging = 1;
	run(&cfg, &in, &p, "gap 120, dodging");
	CHECK(p.action == TD_DODGE, "a gap a dodge crosses: dodges, no weapon");
	gap_map(2500);
	run(&cfg, &in, &p, "gap 2500");
	CHECK(p.action != TD_BOOST || p.reaches, "a gap nothing crosses is never boosted into");
	// a pit: the same gap, with a hurt volume under it, must never be landed in
	gap_map(300);
	add_box(100, -3000, -500, 400, 3000, -300, K_HURT);
	in.stick_fwd = 1;
	run(&cfg, &in, &p, "gap 300 over a hurt pit");
	CHECK(p.action == TD_DODGE || p.outcome == TR_LAND, "never plans a landing in the pit");

	// --- a ledge ---
	ledge_map(96);
	base_in(&in);
	in.org[0] = 20;
	cfg.dodging = 1;
	run(&cfg, &in, &p, "ledge 96, dodging, idle");
	CHECK(p.have_goal && p.goal_kind == TD_UP, "a ledge is found as the goal");
	CHECK(p.goal[2] > 90, "the goal is on top of it");
	CHECK(p.action == TD_BOOST && p.dir == TD_UP, "a ledge a dodge cannot reach: boosts up");
	CHECK(p.reaches && p.land[2] > 100, "and lands on top");
	ledge_map(40);
	run(&cfg, &in, &p, "ledge 40");
	CHECK(p.action != TD_BOOST, "a step a jump takes needs no weapon");
	ledge_map(900);
	run(&cfg, &in, &p, "ledge 900");
	CHECK(p.action != TD_BOOST || p.reaches, "a ledge out of reach is never boosted at");
	// a ceiling over the player stops an up boost
	ledge_map(96);
	add_box(-3000, -3000, 70, 90, 3000, 90, K_SOLID);
	run(&cfg, &in, &p, "ledge with a low ceiling");
	CHECK(p.action != TD_BOOST || p.reaches, "a ceiling: no boost that cannot clear it");


	// --- more ---
	// the weapon in hand is kept when it does the job
	gap_map(300);
	base_in(&in);
	in.org[0] = 70;
	in.stick_fwd = 1;
	cfg.dodging = 1;
	run(&cfg, &in, &p, "gap 300, blaster in hand");
	CHECK(p.weapon == widx("blaster") && p.fire_delay == 0, "the weapon in hand does the job: no switch");
	// a pit under the gap: every landing planned is on the far side
	add_box(100, -3000, -500, 400, 3000, -300, K_HURT);
	run(&cfg, &in, &p, "gap 300 with a pit under it");
	CHECK(p.action != TD_BOOST || (p.outcome == TR_LAND && p.land[0] > 400), "never plans a landing in the pit");
	// not enough ammo for the weapon's cost
	base_in(&in);
	in.owned = 1u << widx("devastator");
	in.held = widx("devastator");
	in.ammo[widx("devastator")] = 3;
	in.stick_fwd = 1;
	cfg.dodging = 0;
	flat();
	run(&cfg, &in, &p, "devastator with 3 rockets (costs 4)");
	CHECK(p.action == TD_NONE, "ammo under the weapon's cost: not used");
	// unknown dodging counts as possible
	base_in(&in);
	in.stick_fwd = 1;
	cfg.dodging = -1;
	run(&cfg, &in, &p, "dodging unknown");
	CHECK(p.action == TD_DODGE, "dodging unknown: try the dash");
	// a dodge goes with the blast when it carries further
	cfg.dodging = 1;
	in.force_dir = TD_FWD;
	run(&cfg, &in, &p, "forced forward, dodging");
	{
		float with = p.land[0];
		cfg.dodging = 0;
		run(&cfg, &in, &p, "forced forward, no dodging");
		CHECK(with > p.land[0], "dodge plus blast carries further than the blast alone");
	}
	// learned calibration scales the push
	cfg.dodging = 0;
	run(&cfg, &in, &p, "uncalibrated");
	{
		float full = p.land[0];
		c = cfg;
		for (int i = 0; i < c.nrows; i++)
			c.rows[i].cal = 0.5f;
		run(&c, &in, &p, "calibrated to half");
		CHECK(p.action != TD_BOOST || p.land[0] < full, "a push measured weaker shortens the predicted launch");
	}

	// the bottom of the map is where it kills: no landing within 160 units of it
	flat();
	base_in(&in);
	in.stick_fwd = 1;
	cfg.dodging = 0;
	g_floor_z = -100;
	run(&cfg, &in, &p, "floor of the world right under the floor");
	CHECK(p.action == TD_NONE, "ground near the lowest point of the map is not trusted: no boost");
	g_floor_z = -600;
	// an open drop far below is not boosted into
	world_clear();
	add_box(-3000, -3000, -64, 100, 3000, 0, K_SOLID);
	add_box(100, -3000, -1000, 3000, 3000, -900, K_SOLID);
	g_floor_z = -2000;
	in.org[0] = 70;
	run(&cfg, &in, &p, "ledge over a deep drop");
	CHECK(p.action != TD_BOOST || p.land[2] > in.org[2] - 320, "an open forward boost never plans a drop of over 320 units");
	g_floor_z = -600;

	printf("%d checks, %d failed\n", checks, failures);
	return failures ? 1 : 0;
}
