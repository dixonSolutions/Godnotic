/*
Checks touch_aim_guide.h, the predictive Devastator guiding, with a
deterministic offline harness: a rocket flown by the server's own guidance
(TouchWep_GuideStep: turn rate, goal on the view ray, acceleration, the first
0.2 s unguided), a view that turns at a capped rate, and targets that stand,
strafe, jump and circle. The old steering (a constant-velocity intercept, the
view put straight onto it, set off by TouchWep_Detonate) is flown against the
same targets for comparison. Prints the closest approach at the moment of
detonation, the share that land inside the blast, and how smooth the view is.
Also checks the wall handling, the target memory, the acceleration estimator
and that no rocket is set off at the player. No engine, no display.

Run through scripts/test-touch-aim.sh.
*/

#define _POSIX_C_SOURCE 200809L
#include <math.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef bool qbool;
#include "touch_weapons.h"
#include "touch_aim_guide.h"

static int failures, checks;
#define CHECK(c, ...) do { checks++; if (!(c)) { failures++; printf("FAIL line %d: ", __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)

static unsigned rng = 20261123u;
static float frand(void) { rng = rng * 1664525u + 1013904223u; return (float)((rng >> 8) & 0xFFFFFF) / (float)0x1000000; }
static float gauss(void) { return (frand() + frand() + frand() + frand() - 2.0f) * 1.7f; }

#define SIM_DT (1.0f / 60.0f)
#define GRAV 800.0f
#define SPEED_RUN 320.0f
#define GUIDE_DELAY 0.2f
#define LATENCY 0.06f
#define VIEW_RATE 540.0f // deg/s the view can be turned

// ---- the world: optional pillar (a box) ------------------------------------
typedef struct { int on; float lo[3], hi[3]; } box_t;
static int seg_box(const box_t *b, const float a[3], const float c[3], float end[3])
{
	float t0 = 0, t1 = 1;
	int i;
	if (!b->on)
		return 0;
	for (i = 0; i < 3; i++)
	{
		float d = c[i] - a[i];
		if (fabsf(d) < 1e-6f)
		{
			if (a[i] < b->lo[i] || a[i] > b->hi[i])
				return 0;
		}
		else
		{
			float u0 = (b->lo[i] - a[i]) / d, u1 = (b->hi[i] - a[i]) / d, tmp;
			if (u0 > u1) { tmp = u0; u0 = u1; u1 = tmp; }
			if (u0 > t0) t0 = u0;
			if (u1 < t1) t1 = u1;
			if (t0 > t1)
				return 0;
		}
	}
	for (i = 0; i < 3; i++)
		end[i] = a[i] + (c[i] - a[i]) * t0;
	return 1;
}
static int trace_cb(void *ctx, const float a[3], const float b[3], float end[3]) { return seg_box((const box_t *)ctx, a, b, end); }

// ---- targets ---------------------------------------------------------------
enum { M_STAND, M_STRAFE, M_JUMP, M_CIRCLE, M_COUNT };
static const char *const mname[] = { "standing", "strafing", "jumping", "circling" };

typedef struct
{
	int mode;
	float org[3], vel[3];
	float dir[2], next_flip, t;
	float cx, cy, r, ang, w;  // circle
	int air;
	float jump_at;
} target_t;

static void target_init(target_t *g, int mode, float dist, float yaw_off)
{
	memset(g, 0, sizeof(*g));
	g->mode = mode;
	g->org[0] = dist * cosf(yaw_off);
	g->org[1] = dist * sinf(yaw_off);
	g->org[2] = 0;
	g->dir[0] = 0;
	g->dir[1] = frand() < 0.5f ? 1.0f : -1.0f;
	g->next_flip = 0.3f + 0.6f * frand();
	g->jump_at = 0.2f + 0.6f * frand();
	if (mode == M_STRAFE || mode == M_JUMP)
	{
		g->vel[1] = g->dir[1] * SPEED_RUN;
		g->vel[0] = -60 + 120 * frand();
	}
	if (mode == M_CIRCLE)
	{
		g->r = 180 + 200 * frand();
		g->w = SPEED_RUN / g->r * (frand() < 0.5f ? 1 : -1);
		g->ang = frand() * 6.28f;
		g->cx = g->org[0] - g->r * cosf(g->ang);
		g->cy = g->org[1] - g->r * sinf(g->ang);
	}
}

static void target_step(target_t *g, float dt)
{
	g->t += dt;
	if (g->mode == M_STAND)
		return;
	if (g->mode == M_CIRCLE)
	{
		g->ang += g->w * dt;
		g->org[0] = g->cx + g->r * cosf(g->ang);
		g->org[1] = g->cy + g->r * sinf(g->ang);
		g->vel[0] = -g->r * g->w * sinf(g->ang);
		g->vel[1] = g->r * g->w * cosf(g->ang);
		return;
	}
	if (g->t >= g->next_flip)
	{
		g->dir[1] = -g->dir[1];
		g->next_flip = g->t + 0.3f + 0.6f * frand();
	}
	{
		float want = g->dir[1] * SPEED_RUN, dv = want - g->vel[1], step = 1800.0f * dt; // ground acceleration
		if (g->air)
			step = 300.0f * dt; // little air control
		g->vel[1] += fabsf(dv) < step ? dv : (dv > 0 ? step : -step);
	}
	if (g->mode == M_JUMP && !g->air && g->t >= g->jump_at)
	{
		g->air = 1;
		g->vel[2] = 270;
	}
	if (g->air)
	{
		g->vel[2] -= GRAV * dt;
		if (g->org[2] + g->vel[2] * dt <= 0 && g->vel[2] < 0)
		{
			g->org[2] = 0;
			g->vel[2] = 0;
			g->air = 0;
			g->jump_at = g->t + 0.5f + frand();
		}
	}
	g->org[0] += g->vel[0] * dt;
	g->org[1] += g->vel[1] * dt;
	g->org[2] += g->vel[2] * dt;
}

// ---- one flight ------------------------------------------------------------
typedef struct { float miss; float jitter, maxjump; int flips; float t; int exploded_at_self; } result_t;

static void v_sub(const float *a, const float *b, float *o) { int i; for (i = 0; i < 3; i++) o[i] = a[i] - b[i]; }

static void rotate_view(float view[3], const float want[3], float max_deg)
{
	float c = tw_dot(view, want), ang, maxang = max_deg * (float)(M_PI / 180.0), a, b, l;
	int k;
	c = c > 1 ? 1 : c < -1 ? -1 : c;
	ang = acosf(c);
	if (ang <= maxang) { memcpy(view, want, 3 * sizeof(float)); return; }
	if (c < -0.9998f) return;
	a = sinf(ang - maxang) / sinf(ang);
	b = sinf(maxang) / sinf(ang);
	for (k = 0; k < 3; k++) view[k] = a * view[k] + b * want[k];
	l = tg_len(view);
	for (k = 0; k < 3; k++) view[k] /= l;
}

static void fly(int new_steer, int mode, float dist, float yaw_off, const box_t *world, result_t *res)
{
	const touch_wep_t *dev = TouchWep_Find("devastator");
	tg_rocket_t R;
	target_t g;
	tg_state_t st;
	tg_track_t trk;
	float eye[3] = { 0, 0, 0 }, view[3], ro[3] = { 0, 0, 0 }, rdir[3], rsp, prev_yaw_rate = 0, prev_yaw = 0;
	float t = 0, best = 1e9f, jitter = 0, maxjump = 0;
	float det_at = -1;
	int flips = 0, frame = 0, k;
	memset(&R, 0, sizeof(R));
	R.speed = dev->pri.speed; R.accel = dev->pri.accel; R.speed_max = dev->pri.speed_max;
	R.rate_deg = 90; R.goal = 512; R.splash = dev->pri.splash;
	memset(&st, 0, sizeof(st));
	memset(&trk, 0, sizeof(trk));
	target_init(&g, mode, dist, yaw_off);
	// launched along a direct line (as the press goes out lined up on the target)
	{
		float l = tg_len(g.org);
		for (k = 0; k < 3; k++) { view[k] = g.org[k] / l; rdir[k] = view[k]; }
	}
	rsp = R.speed;
	prev_yaw = atan2f(view[1], view[0]);
	while (t < 4.0f)
	{
		float want[3], rel[3], aim[3], rvel[3], tv[3], rrel[3], d, closing;
		float acc[3];
		// the player's steering decision
		for (k = 0; k < 3; k++) rvel[k] = rdir[k] * rsp;
		TouchGuide_TrackUpdate(&trk, g.vel, SIM_DT, acc);
		if (new_steer)
		{
			tg_target_t tg;
			tg_plan_t plan;
			memset(&tg, 0, sizeof(tg));
			memcpy(tg.org, g.org, sizeof(tg.org));
			memcpy(tg.vel, g.vel, sizeof(tg.vel));
			tg.acc[0] = acc[0]; tg.acc[1] = acc[1];
			tg.airborne = g.air; tg.gravity = GRAV; tg.floor_z = 0;
			TouchGuide_Plan(&R, ro, rvel, eye, &tg, world->on ? trace_cb : NULL, (void *)world, SIM_DT, &st, &plan);
			if (plan.valid) memcpy(aim, plan.aim, sizeof(aim)); else memcpy(aim, g.org, sizeof(aim));
			v_sub(g.org, ro, rrel);
			d = tg_len(rrel);
			v_sub(rvel, g.vel, tv);
			closing = d > 1e-3f ? -tw_dot(rrel, tv) / d * -1.0f : 0; // rel . relvel / d, > 0 closing
			closing = d > 1e-3f ? tw_dot(rrel, tv) / d : 0;
			if (t >= GUIDE_DELAY && TouchGuide_Detonate(&plan, d, closing, LATENCY + SIM_DT, tg_len(ro), R.splash) && det_at < 0)
				det_at = t + LATENCY;
		}
		else
		{
			touch_wep_fire_t rf;
			float a2[3], tof, tland = -1;
			memset(&rf, 0, sizeof(rf));
			rf.kind = TW_KIND_PROJ;
			rf.speed = rsp > R.speed ? rsp : R.speed;
			v_sub(g.org, ro, rel);
			if (g.air)
				tland = 0.5f;
			if (TouchWep_AimPointAir(&rf, rel, g.vel, 0, GRAV, tland, a2, &tof))
				for (k = 0; k < 3; k++) aim[k] = ro[k] + a2[k];
			else
				memcpy(aim, g.org, sizeof(aim));
			v_sub(rvel, g.vel, tv);
			v_sub(g.org, ro, rrel);
			d = tg_len(rrel);
			if (t >= GUIDE_DELAY && TouchWep_Detonate(ro, tv, g.org, eye, R.splash) && det_at < 0)
				det_at = t + LATENCY;
		}
		v_sub(aim, eye, want);
		{
			float l = tg_len(want);
			if (l < 1e-3f) memcpy(want, view, sizeof(want)); else for (k = 0; k < 3; k++) want[k] /= l;
		}
		rotate_view(view, want, VIEW_RATE * SIM_DT);
		{
			float yaw = atan2f(view[1], view[0]), dy = yaw - prev_yaw, rate;
			if (dy > M_PI)
				dy -= 2 * (float)M_PI;
			if (dy < -M_PI)
				dy += 2 * (float)M_PI;
			rate = dy / SIM_DT;
			if (frame > 12)
			{
				jitter += fabsf(dy);
				if (fabsf(dy) > maxjump) maxjump = fabsf(dy);
				if (rate * prev_yaw_rate < 0 && fabsf(rate) > 20 && fabsf(prev_yaw_rate) > 20) flips++;
			}
			prev_yaw = yaw;
			prev_yaw_rate = rate;
		}
		// the world moves on
		if (t >= GUIDE_DELAY)
			TouchWep_GuideStep(ro, rdir, &rsp, eye, view, R.goal, R.rate_deg, R.accel, R.speed_max, SIM_DT);
		else
		{
			rsp = rsp + R.accel * SIM_DT > R.speed_max ? R.speed_max : rsp + R.accel * SIM_DT;
			for (k = 0; k < 3; k++) ro[k] += rdir[k] * rsp * SIM_DT;
		}
		if (world->on)
		{
			float p0[3], e[3];
			for (k = 0; k < 3; k++) p0[k] = ro[k] - rdir[k] * rsp * SIM_DT;
			if (seg_box(world, p0, ro, e)) { memcpy(ro, e, sizeof(ro)); det_at = t; }
		}
		target_step(&g, SIM_DT);
		t += SIM_DT;
		frame++;
		v_sub(g.org, ro, rrel);
		d = tg_len(rrel);
		if (d < best) best = d;
		if (det_at >= 0 && t >= det_at)
		{
			res->miss = d;
			res->t = t;
			res->jitter = jitter;
			res->maxjump = maxjump;
			res->flips = flips;
			res->exploded_at_self = tg_len(ro) < R.splash;
			return;
		}
		if (d > best + 200 && t > 0.6f && !new_steer)
		{
			// the old rule never fired and the rocket is flying away: it is set off at the end
			det_at = t;
		}
	}
	res->miss = best + 400; // never set off: counts as a miss
	res->t = t;
	res->jitter = jitter;
	res->maxjump = maxjump;
	res->flips = flips;
	res->exploded_at_self = 0;
}

static int cmp_f(const void *a, const void *b) { float x = *(const float *)a, y = *(const float *)b; return x < y ? -1 : x > y; }

typedef struct { float mean, median, hit, hit45, p90, jit, flips, maxj; } agg_t;

static agg_t campaign(int new_steer, int mode, const box_t *world, int runs)
{
	float miss[256];
	agg_t a;
	int i, hits = 0, hits45 = 0;
	float sj = 0, sf = 0, mj = 0, sum = 0;
	unsigned save = rng;
	rng = 777u + mode * 31u; // the same target motions for old and new
	for (i = 0; i < runs; i++)
	{
		result_t r;
		float dist = 350 + 1100 * frand(), yaw = (frand() - 0.5f) * 1.2f;
		fly(new_steer, mode, dist, yaw, world, &r);
		miss[i] = r.miss;
		sum += r.miss;
		if (r.miss <= 0.75f * 110) hits++;
		if (r.miss <= 45) hits45++;
		sj += r.jitter; sf += (float)r.flips; if (r.maxjump > mj) mj = r.maxjump;
	}
	qsort(miss, (size_t)runs, sizeof(float), cmp_f);
	a.mean = sum / runs; a.median = miss[runs / 2]; a.p90 = miss[(runs * 9) / 10];
	a.hit = 100.0f * hits / runs; a.hit45 = 100.0f * hits45 / runs; a.jit = sj / runs * 57.2958f; a.flips = sf / runs; a.maxj = mj * 57.2958f;
	rng = save;
	return a;
}

// ---- unit checks -----------------------------------------------------------
static void test_target_model(void)
{
	tg_target_t g;
	float p[3], v[3];
	memset(&g, 0, sizeof(g));
	g.vel[1] = 320; g.acc[1] = -1800; // reversing
	TouchGuide_TargetAt(&g, 0.0f, p);
	CHECK(fabsf(p[1]) < 1e-3f, "t=0 at the origin, got %.2f", p[1]);
	TouchGuide_TargetAt(&g, 0.4f, p);
	TouchGuide_VelAt(&g, 0.4f, v);
	CHECK(v[1] < 320 && v[1] > -320, "reversal bounded, v %.0f", v[1]);
	TouchGuide_TargetAt(&g, 2.0f, p);
	CHECK(fabsf(p[1]) < 900, "acceleration fades, no runaway: y %.0f", p[1]);
	// a jump lands and stays on the floor
	memset(&g, 0, sizeof(g));
	g.airborne = 1; g.gravity = 800; g.vel[2] = 270; g.floor_z = 0;
	TouchGuide_TargetAt(&g, 1.0f, p);
	CHECK(p[2] >= -1e-3f && p[2] < 1.0f, "landed, z %.1f", p[2]);
	TouchGuide_TargetAt(&g, 0.3375f, p);
	CHECK(p[2] > 40 && p[2] < 50, "apex ~45 units, z %.1f", p[2]);
}

static void test_estimator(void)
{
	tg_track_t t;
	float acc[3], v[3] = { 0, 320, 0 };
	int i;
	memset(&t, 0, sizeof(t));
	for (i = 0; i < 120; i++)
	{
		v[1] = 320 + 25 * gauss();
		TouchGuide_TrackUpdate(&t, v, SIM_DT, acc);
	}
	CHECK(fabsf(acc[1]) < 600, "steady run with noise: acc %.0f (should be near 0)", acc[1]);
	for (i = 0; i < 12; i++) // reversing at 1800
	{
		v[1] = 320 - 1800 * SIM_DT * (i + 1);
		TouchGuide_TrackUpdate(&t, v, SIM_DT, acc);
	}
	CHECK(acc[1] < -800 && acc[1] >= -TG_ACC_MAX - 1, "reversal seen: acc %.0f", acc[1]);
}

static void test_memory(void)
{
	tg_target_t g;
	memset(&g, 0, sizeof(g));
	g.vel[1] = 300;
	CHECK(TouchGuide_Advance(&g, 0.2f) == 1 && g.org[1] > 30 && g.org[1] <= 61, "advanced on its velocity: %.0f", g.org[1]);
	CHECK(g.vel[1] < 300 && g.vel[1] > 100, "fading: %.0f", g.vel[1]);
	memset(&g, 0, sizeof(g));
	CHECK(TouchGuide_Advance(&g, TG_MEMORY + 0.01f) == 0, "let go after the memory");
}

static void test_wall(void)
{
	// a pillar between the player and a target that strafes behind it: the
	// predicted track is stopped by the world, a flight into the pillar is
	// marked and loses against one that clears it
	box_t w;
	tg_target_t g;
	tg_rocket_t R;
	tg_state_t st;
	tg_plan_t plan;
	float rvel[3] = { 1000, 0, 0 }, ro[3] = { 0, 0, 0 }, eye[3] = { 0, 0, 0 };
	memset(&w, 0, sizeof(w));
	w.on = 1; w.lo[0] = 600; w.hi[0] = 640; w.lo[1] = -60; w.hi[1] = 60; w.lo[2] = -300; w.hi[2] = 300;
	memset(&R, 0, sizeof(R));
	R.speed = 1000; R.accel = 600; R.speed_max = 1300; R.rate_deg = 90; R.goal = 512; R.splash = 110;
	memset(&g, 0, sizeof(g));
	g.org[0] = 900; g.org[1] = 0; g.vel[1] = 320;
	memset(&st, 0, sizeof(st));
	TouchGuide_Plan(&R, ro, rvel, eye, &g, trace_cb, &w, SIM_DT, &st, &plan);
	CHECK(plan.valid, "a plan with a pillar in the way");
	CHECK(plan.aim[1] > 100, "meets the strafing target beside the pillar, not through it: aim y %.0f", plan.aim[1]);
	{
		float a[3] = { 500, 0, 0 }, b[3] = { 700, 0, 0 }, e[3];
		CHECK(trace_cb(&w, a, b, e) && e[0] < 601, "the harness pillar blocks");
	}
}

static void test_detonate(void)
{
	tg_plan_t p;
	memset(&p, 0, sizeof(p));
	p.valid = 1; p.t_close = 0.02f; p.miss = 40;
	CHECK(TouchGuide_Detonate(&p, 60, 300, 0.07f, 800, 110), "set off at the closest approach");
	CHECK(!TouchGuide_Detonate(&p, 60, 300, 0.07f, 120, 110), "never beside the player");
	p.t_close = 0.5f;
	CHECK(!TouchGuide_Detonate(&p, 90, 300, 0.07f, 800, 110), "not yet: still closing, closest approach later");
	CHECK(TouchGuide_Detonate(&p, 60, -50, 0.07f, 800, 110), "moving away inside the blast: now");
	p.miss = 130; p.t_close = 0.02f;
	CHECK(!TouchGuide_Detonate(&p, 100, 300, 0.07f, 800, 110), "a blast that will not reach it is not wasted");
}

int main(void)
{
	box_t none;
	int m;
	float sum_old = 0, sum_new = 0, hit_old = 0, hit_new = 0, h45_old = 0, h45_new = 0;
	memset(&none, 0, sizeof(none));
	test_target_model();
	test_estimator();
	test_memory();
	test_wall();
	test_detonate();
	printf("Devastator guiding: %d launches per motion, targets 350-1450 units away; blast radius 110.\n", 60);
	printf("closest approach at detonation, units (mean / median / p90), share within the blast (82) and within a body (45)\n");
	printf("%-9s | %-42s | %-42s\n", "motion", "old: constant-velocity intercept", "new: predictive guide");
	for (m = 0; m < M_COUNT; m++)
	{
		agg_t o = campaign(0, m, &none, 60), n = campaign(1, m, &none, 60);
		printf("%-9s | %5.0f %5.0f %5.0f  blast %5.1f%% body %5.1f%% | %5.0f %5.0f %5.0f  blast %5.1f%% body %5.1f%%\n",
			mname[m], o.mean, o.median, o.p90, o.hit, o.hit45, n.mean, n.median, n.p90, n.hit, n.hit45);
		printf("          view turning: jitter %3.0f -> %3.0f deg, reversals %.1f -> %.1f, largest step %.1f -> %.1f deg/frame\n", o.jit, n.jit, o.flips, n.flips, o.maxj, n.maxj);
		sum_old += o.mean; sum_new += n.mean; hit_old += o.hit; hit_new += n.hit; h45_old += o.hit45; h45_new += n.hit45;
		if (m == M_STAND)
			CHECK(n.hit >= 90, "standing target: %.0f%% hit", n.hit);
		else
			CHECK(n.mean <= o.mean * 1.02f, "%s: the new steering is not worse (mean %.0f vs %.0f)", mname[m], n.mean, o.mean);
		CHECK(n.flips <= o.flips + 2.5f, "%s: no more oscillation than before (%.1f vs %.1f reversals)", mname[m], n.flips, o.flips);
		CHECK(n.maxj < 6.0f, "%s: no view jump, largest step %.1f deg/frame", mname[m], n.maxj);
	}
	printf("all motions: mean miss %.0f -> %.0f units; within the blast %.1f%% -> %.1f%%; within a body %.1f%% -> %.1f%%\n",
		sum_old / M_COUNT, sum_new / M_COUNT, hit_old / M_COUNT, hit_new / M_COUNT, h45_old / M_COUNT, h45_new / M_COUNT);
	CHECK(h45_new / M_COUNT > h45_old / M_COUNT + 15, "direct hits improved by more than 15 points (%.1f -> %.1f)", h45_old / M_COUNT, h45_new / M_COUNT);
	CHECK(sum_new < sum_old * 0.6f, "mean miss improved by at least 40%%");
	// a pillar between: the new steering must still reach a target beside it
	{
		box_t w;
		agg_t n, o;
		memset(&w, 0, sizeof(w));
		w.on = 1; w.lo[0] = 350; w.hi[0] = 380; w.lo[1] = -40; w.hi[1] = 40; w.lo[2] = -400; w.hi[2] = 400;
		n = campaign(1, M_STRAFE, &w, 30);
		o = campaign(0, M_STRAFE, &w, 30);
		printf("strafing with a pillar 350 units out (rockets that hit it burst there): mean %.0f -> %.0f units, within the blast %.1f%% -> %.1f%%\n", o.mean, n.mean, o.hit, n.hit);
		CHECK(n.mean <= o.mean * 1.05f, "pillar: not worse than before (%.0f vs %.0f)", n.mean, o.mean);
	}
	printf("%d checks, %d failed\n", checks, failures);
	return failures != 0;
}
