/*
Checks touch_aim_predict.h: the velocity filter and its confidence, the shot
timing, intercepts and lobs, and the break-out rule; and, against a simulated
person who strafes, jumps and reverses at random, how often the shot of each
weapon lands with the old fixed smoothing and with the new filter and timing
(the Blaster, Mortar and Electro). No engine, no display.
*/

#define _POSIX_C_SOURCE 200809L
#include <math.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

typedef bool qbool;
#include "touch_weapons.h"
#include "touch_aim_predict.h"

static int failures, checks;
#define CHECK(c, ...) do { checks++; if (!(c)) { failures++; printf("FAIL line %d: ", __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)

static float v3len(const float *a) { return sqrtf(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]); }

static unsigned rng_state = 12345;
static float frand(void) // 0..1
{
	rng_state = rng_state * 1664525u + 1013904223u;
	return (float)((rng_state >> 8) & 0xFFFFFF) / (float)0x1000000;
}
static float gauss(void) { return (frand() + frand() + frand() + frand() - 2.0f) * 1.7f; }

// ---- filter and confidence -------------------------------------------------

static void test_filter(void)
{
	touch_pred_t clean, jit, spike;
	float s[3], v0[3] = { 0, 320, 0 };
	int i;
	float cc, cj, tau_clean, tau_jit;
	TouchPred_Reset(&clean, v0);
	TouchPred_Reset(&jit, v0);
	TouchPred_Reset(&spike, v0);
	for (i = 0; i < 240; i++)
	{
		s[0] = 0; s[1] = 320 + 3 * gauss(); s[2] = 0;
		TouchPred_Update(&clean, s, 1.0f / 60);
		s[1] = 320 + 90 * gauss();
		TouchPred_Update(&jit, s, 1.0f / 60);
	}
	cc = TouchPred_Confidence(clean.noise, 320);
	cj = TouchPred_Confidence(jit.noise, 320);
	tau_clean = TouchPred_Tau(clean.noise);
	tau_jit = TouchPred_Tau(jit.noise);
	CHECK(cc > 0.9f, "clean samples: confidence %.2f", cc);
	CHECK(cj < 0.75f && cj > 0.0f, "scattered samples: confidence %.2f", cj);
	CHECK(tau_clean < 0.04f, "clean samples smooth over %.0f ms", tau_clean * 1000);
	CHECK(tau_jit > 0.08f && tau_jit <= TP_TAU_MAX + 1e-6f, "scattered samples smooth over %.0f ms", tau_jit * 1000);
	CHECK(TouchPred_WindowScale(cc) < 1.1f && TouchPred_WindowScale(cj) > 1.2f && TouchPred_WindowScale(cj) <= 1.0f + TP_WINDOW_GAIN, "window %.2f clean, %.2f scattered", TouchPred_WindowScale(cc), TouchPred_WindowScale(cj));
	CHECK(fabsf(clean.vel[1] - 320) < 12, "clean estimate %.1f", clean.vel[1]);
	// a smooth reversal (what a person does) is not jitter
	{
		touch_pred_t r;
		float v = 320;
		TouchPred_Reset(&r, v0);
		for (i = 0; i < 120; i++)
		{
			v += (i > 30 ? -320.0f : 320.0f) - v > 0 ? 0 : 0;
			v += ((i > 30 ? -320.0f : 320.0f) - v) * (1 - expf(-1.0f / 60 / 0.10f));
			s[0] = 0; s[1] = v; s[2] = 0;
			TouchPred_Update(&r, s, 1.0f / 60);
		}
		CHECK(r.noise < 40.0f, "a smooth reversal adds little jitter (%.0f)", r.noise);
		CHECK(r.vel[1] < -280, "and the estimate follows it: %.0f", r.vel[1]);
	}
	// one garbage sample (a catch-up after a lost packet) moves it by a bounded step
	s[0] = 0; s[1] = 320 + 3000; s[2] = 0;
	{
		float before = spike.vel[1];
		TouchPred_Update(&spike, s, 1.0f / 60);
		CHECK(fabsf(spike.vel[1] - before) <= TP_INNOV_MAX * (1.0f / 60) / TP_TAU_MIN + 0.01f, "garbage sample moved it by %.0f", spike.vel[1] - before);
		CHECK(fabsf(spike.vel[1] - before) < 0.5f * 3000, "and not to the garbage");
	}
	// frames of any length are safe
	TouchPred_Update(&spike, v0, 5.0f);
	CHECK(spike.vel[1] > 0 && spike.vel[1] < 4000, "a long frame stays finite");
	TouchPred_Update(&spike, v0, 0.0f);
}

// ---- settle -----------------------------------------------------------------

static void test_settle(void)
{
	double since = 0;
	// settled, or a quick shot: never held
	CHECK(!TouchPred_Hold(&since, 10.0, 20, 0.5f) && since == 0, "settled target: fire");
	CHECK(!TouchPred_Hold(&since, 10.0, 300, 0.1f), "a shot that arrives within 0.15 s is not held");
	// unsettled: held for at most 0.25 s, then fires
	CHECK(TouchPred_Hold(&since, 10.0, 300, 0.5f), "unsettled: wait");
	CHECK(TouchPred_Hold(&since, 10.2, 300, 0.5f), "still waiting at 0.2 s");
	CHECK(!TouchPred_Hold(&since, 10.26, 300, 0.5f), "fires after 0.25 s whatever");
	CHECK(!TouchPred_Hold(&since, 10.6, 300, 0.5f), "and stays released for that stretch");
	CHECK(!TouchPred_Hold(&since, 10.7, 20, 0.5f) && since == 0, "settled again: reset");
	CHECK(TouchPred_Hold(&since, 11.0, 300, 0.5f), "a new stretch waits again");
	// the unsettled measure: sideways only
	{
		touch_pred_t p;
		float v[3] = { 0, 0, 0 };
		TouchPred_Reset(&p, v);
		p.vel[2] = -400;
		CHECK(TouchPred_Unsettled(&p) == 0, "a fall is not unsettled");
		p.vel[1] = 200;
		CHECK(fabsf(TouchPred_Unsettled(&p) - 200) < 0.01f, "a sideways change is");
	}
}

// ---- lead -----------------------------------------------------------------

static void test_lead(void)
{
	const touch_wep_t *blaster = TouchWep_Find("blaster"), *electro = TouchWep_Find("electro");
	float rel[3] = { 900, 0, 0 }, vel[3] = { 0, 300, 0 }, aim[3], tof, g = 800;
	float real_y;
	// constant velocity: the intercept lands on the runner
	CHECK(TouchWep_AimPoint(&electro->pri, rel, vel, 0.05f, g, aim, &tof), "electro intercept");
	real_y = vel[1] * (tof + 0.05f);
	CHECK(fabsf(aim[1] - real_y) < 1.0f, "constant velocity intercept off by %.1f", aim[1] - real_y);
	CHECK(TouchWep_AimPoint(&blaster->pri, rel, vel, 0.1f, g, aim, &tof) && fabsf(aim[1] - vel[1] * (tof + 0.1f)) < 1.0f, "blaster intercept");
	CHECK(tof < 0.2f, "blaster flight %.2f", tof);
	// a strafe reversal: the new velocity, once the filter has it, is led
	{
		touch_pred_t p;
		float s[3] = { 0, 300, 0 };
		int i, settled_at = -1;
		TouchPred_Reset(&p, s);
		for (i = 0; i < 90; i++)
		{
			s[1] = i < 30 ? 300.0f : -300.0f;
			TouchPred_Update(&p, s, 1.0f / 60);
			if (i >= 30 && settled_at < 0 && p.vel[1] < -270)
				settled_at = i - 30;
		}
		CHECK(settled_at >= 0 && settled_at <= 10, "the filter has the reversed strafe in %d frames", settled_at);
		CHECK(TouchWep_AimPoint(&electro->pri, rel, p.vel, 0.05f, g, aim, &tof) && aim[1] < -100, "and leads to the new side: %.0f", aim[1]);
	}
	// a person's jump: airborne, falls under gravity and lands (t_land)
	{
		float jv[3] = { 0, 250, 300 }, jrel[3] = { 800, 0, 0 };
		float t_land = TouchWep_LandTime(0.0f + 1.0f, jv[2], g);
		float at[3], diff;
		CHECK(TouchWep_AimPointAir(&electro->pri, jrel, jv, 0.05f, g, t_land, aim, &tof), "air intercept");
		TouchWep_TargetAt(jrel, jv, g, t_land, tof + 0.05f, at);
		diff = sqrtf((aim[0] - at[0]) * (aim[0] - at[0]) + (aim[1] - at[1]) * (aim[1] - at[1]) + (aim[2] - at[2]) * (aim[2] - at[2]));
		CHECK(diff < 1.0f, "jump arc intercept off by %.1f", diff);
		CHECK(aim[2] < jrel[2] + jv[2] * (tof + 0.05f), "the fall pulls the aim point below the straight line");
	}
}

// ---- lobs -----------------------------------------------------------------

// Fly a grenade launched at elevation elev (view) for t seconds: where it is.
static void fly(const touch_wep_fire_t *f, float elev, float g, float t, float *x, float *z)
{
	float v = sqrtf(f->speed * f->speed + f->speed_up * f->speed_up), phi = elev + atan2f(f->speed_up, f->speed);
	*x = v * cosf(phi) * t;
	*z = v * sinf(phi) * t - 0.5f * g * t * t;
}

static void test_mortar(void)
{
	const touch_wep_t *mortar = TouchWep_Find("mortar");
	const touch_wep_fire_t *f = &mortar->pri;
	float g = 800, dx, dz, elev, t, x, z, px, pz;
	int i;
	static const float dists[] = { 400, 800, 1200, 1600 };
	static const float heights[] = { -150, 0, 120 };
	for (i = 0; i < 4; i++)
	{
		int k;
		for (k = 0; k < 3; k++)
		{
			dx = dists[i];
			dz = heights[k];
			if (!TouchWep_Ballistic(dx, dz, f->speed, f->speed_up, g, &elev, &t))
				continue;
			fly(f, elev, g, t, &x, &z);
			CHECK(fabsf(x - dx) < 2.0f && fabsf(z - dz) < 2.0f, "mortar lands (%.1f, %.1f) for (%.0f, %.0f)", x, z, dx, dz);
			// the arc helper ends at the target, and is the low arc (rising at the start)
			CHECK(TouchPred_ArcAt(f, dx, dz, g, 1.0f, &px, &pz) && fabsf(px - dx) < 1.0f && fabsf(pz - dz) < 1.0f, "arc end (%.1f, %.1f) for (%.0f, %.0f)", px, pz, dx, dz);
			CHECK(TouchPred_ArcAt(f, dx, dz, g, 0.5f, &px, &pz) && px > 0.3f * dx && px < 0.7f * dx, "arc middle at %.0f of %.0f", px, dx);
		}
	}
	// a wall across the arc: the middle point is above where a flat line would be
	{
		float mx, mz, ex, ez;
		CHECK(TouchPred_ArcAt(f, 1000, 0, g, 0.5f, &mx, &mz) && TouchPred_ArcAt(f, 1000, 0, g, 0.92f, &ex, &ez), "arc points");
		CHECK(mz > 0, "arc rises above the muzzle height by mid-flight (%.0f)", mz);
		CHECK(ez < mz, "and comes down toward the target (%.0f -> %.0f)", mz, ez);
	}
	// out of reach is reported, not faked
	CHECK(!TouchPred_ArcAt(f, 9000, 0, g, 0.5f, &px, &pz), "out of reach");
	// a mortar at a runner: lead then lob hits where the runner is at impact
	{
		float rel[3] = { 900, 0, 0 }, vel[3] = { 0, 280, 0 }, aim[3], tof, real_y;
		CHECK(TouchWep_AimPoint(f, rel, vel, 0.1f, g, aim, &tof), "mortar lead");
		real_y = vel[1] * (tof + 0.1f);
		CHECK(fabsf(aim[1] - real_y) < 2.0f, "mortar aims where the runner is at impact: %.1f vs %.1f", aim[1], real_y);
	}
}

// ---- break-out ------------------------------------------------------------

static touch_brk_cfg_t cfg(void)
{
	touch_brk_cfg_t c = { 4.0f, 15.0f, 400.0f, 0.25f, 4.0f };
	return c;
}

// frames at 60 Hz of a drag (deg/s, direction) with the target ey/ep from the view
static int frames_until_release(touch_brk_t *b, const touch_brk_cfg_t *c, float rate, float dy, float dp, float ey, float ep, int max_frames, double *t)
{
	int i;
	float dt = 1.0f / 60;
	for (i = 1; i <= max_frames; i++)
	{
		(*t) += dt;
		if (TouchBrk_Step(b, c, *t, dt, dy * rate * dt, dp * rate * dt, 7, ey, ep))
			return i;
	}
	return 0;
}

static void test_breakout(void)
{
	touch_brk_cfg_t c = cfg();
	touch_brk_t b;
	double t = 100;
	float dt = 1.0f / 60, acc = 0;
	int i, n, rel;
	// the player can always leave: a drag that keeps going one way
	memset(&b, 0, sizeof(b));
	n = frames_until_release(&b, &c, 60, 1, 0, 0, 0, 60, &t);
	CHECK(n > 1 && n <= 8, "a 60 deg/s drag off the target releases in %d frames", n);
	CHECK(TouchBrk_Held(&b, t, 7) && !TouchBrk_Held(&b, t, 8), "only that target is held out");
	CHECK(TouchBrk_Held(&b, t + 0.24, 7) && !TouchBrk_Held(&b, t + 0.26, 7), "for 0.25 s");
	memset(&b, 0, sizeof(b));
	n = frames_until_release(&b, &c, 30, 1, 0, 0, 0, 60, &t);
	CHECK(n > 6 && n <= 30, "a 30 deg/s drag holds a moment, then releases (%d frames)", n);
	memset(&b, 0, sizeof(b));
	n = frames_until_release(&b, &c, 60, -1, 0, 6, 0, 60, &t);
	CHECK(n > 0 && n <= 8, "directly away from a target 6 deg off: %d frames", n);
	memset(&b, 0, sizeof(b));
	n = frames_until_release(&b, &c, 60, 0, 1, 6, 0, 60, &t);
	CHECK(n > 0 && n <= 8, "across the line to it: %d frames", n);
	// toward it: kept
	memset(&b, 0, sizeof(b));
	n = frames_until_release(&b, &c, 40, 1, 0, 20, 0, 120, &t);
	CHECK(n == 0, "a drag toward the target keeps it (frame %d)", n);
	// the drag that brought the view onto it does not count against it
	memset(&b, 0, sizeof(b));
	for (i = 0; i < 30; i++)
		TouchBrk_Step(&b, &c, t, dt, 100 * dt, 0, 0, 0, 0);
	CHECK(!TouchBrk_Step(&b, &c, t, dt, 100 * dt, 0, 7, 0, 0), "a target reached in mid-turn is not released on sight");
	// turning with a target that moves the same way, as fast or a bit faster
	memset(&b, 0, sizeof(b));
	n = frames_until_release(&b, &c, 50, 1, 0, 40 * dt, 0, 300, &t);
	CHECK(n == 0, "following a target moving 40 deg/s at 50 keeps it (frame %d)", n);
	memset(&b, 0, sizeof(b));
	n = frames_until_release(&b, &c, 200, 1, 0, 40 * dt, 0, 60, &t);
	CHECK(n > 0, "running well past it releases");
	// a thumb that goes back and forth leaves nothing
	memset(&b, 0, sizeof(b));
	for (i = rel = 0; i < 240; i++)
		rel |= TouchBrk_Step(&b, &c, t, dt, ((i / 2) & 1 ? 60 : -60) * dt, 0, 7, 0, 0);
	CHECK(!rel, "a 60 deg/s wobble keeps the target");
	memset(&b, 0, sizeof(b));
	n = frames_until_release(&b, &c, 8, -1, 0, 0, 0, 600, &t);
	CHECK(n == 0, "a resting thumb (8 deg/s) never releases (frame %d)", n);
	memset(&b, 0, sizeof(b));
	CHECK(TouchBrk_Step(&b, &c, t, dt, 7.0f, 0, 7, 0, 0), "a flick releases in one frame");
	// released in mid-turn: not taken back while the drag goes on, then it is
	CHECK(TouchBrk_Held(&b, t, 7), "held");
	for (i = 0; i < 60; i++)
	{
		t += dt;
		acc += 30 * dt;
		TouchBrk_Leaving(&b, &c, t, dt, 7, 30 * dt, 0, -acc, 0);
	}
	CHECK(TouchBrk_Held(&b, t, 7), "still held after a second of turning away");
	TouchBrk_Leaving(&b, &c, t + 0.2, dt, 7, 0, 0, -acc, 0);
	CHECK(!TouchBrk_Held(&b, t + 0.26, 7), "taken again once the drag stops");
	// turning back onto it
	b.out[0].ent = 7; b.out[0].until = t + 0.5;
	CHECK(!TouchBrk_Return(&b, &c, dt, 7, 0.9f, 0, 20.0f, 0, 15.0f), "toward but 15 deg off: stays held");
	CHECK(!TouchBrk_Return(&b, &c, dt, 7, -0.9f, 0, 3.0f, 0, 2.0f), "near it but turning away: stays held");
	CHECK(!TouchBrk_Return(&b, &c, dt, 7, 0.1f, 0, 3.0f, 0, 2.0f), "near it but a resting thumb: stays held");
	CHECK(TouchBrk_Return(&b, &c, dt, 7, 0.9f, 0, 3.0f, 0, 2.0f), "turn back toward it: taken again");
	CHECK(!TouchBrk_Held(&b, t, 7), "the hold is gone");
	// two enemies close together: releasing the second keeps the first out
	TouchBrk_Step(&b, &c, t, dt, 0, 0, 7, 0, 0);
	CHECK(TouchBrk_Step(&b, &c, t, dt, 7.0f, 0, 7, 0, 0), "the first is released");
	TouchBrk_Step(&b, &c, t, dt, 0, 0, 8, 0, 0);
	CHECK(TouchBrk_Step(&b, &c, t, dt, 7.0f, 0, 8, 0, 0), "then the second");
	CHECK(TouchBrk_Held(&b, t, 7) && TouchBrk_Held(&b, t, 8), "both are held out");
	// a short frame that a whole touch event landed in is not a flick
	TouchBrk_Step(&b, &c, t, 0.004f, 0, 0, 9, 0, 0);
	CHECK(!TouchBrk_Step(&b, &c, t, 0.004f, 1.7f, 0, 9, 0, 0), "1.7 deg in a 4 ms frame is no flick");
}

// ---- hit rates against a simulated person ---------------------------------

typedef struct person_s
{
	float pos[3], vel[3];
	float dir;         // -1, 0, 1: where the sideways key points
	float next_flip;   // seconds until the next change
	float air;         // seconds left in a jump
	float vz;
} person_t;

#define RUN_SPEED 320.0f
#define FLIP_RATE 1.6f
#define JUMP_RATE 0.25f

static void person_step(person_t *p, float dt)
{
	p->next_flip -= dt;
	if (p->next_flip <= 0)
	{
		float r = frand();
		p->dir = r < 0.42f ? -1.0f : r < 0.84f ? 1.0f : 0.0f; // sometimes stops
		p->next_flip = -logf(1.0f - frand() * 0.999f) / FLIP_RATE;
	}
	p->vel[1] += (p->dir * RUN_SPEED - p->vel[1]) * (1 - expf(-dt / 0.10f)); // a reversal takes a tenth of a second
	if (p->air <= 0 && frand() < JUMP_RATE * dt)
	{
		p->air = 1.0f;
		p->vz = 270;
	}
	if (p->air > 0)
	{
		p->vz -= 800 * dt;
		p->pos[2] += p->vz * dt;
		p->air -= dt;
		if (p->pos[2] <= 0)
		{
			p->pos[2] = 0;
			p->air = 0;
			p->vz = 0;
		}
	}
	p->vel[2] = p->air > 0 ? p->vz : 0;
	p->pos[1] += p->vel[1] * dt;
}

static float fut_at(const float *fut, float t, float dt)
{
	float x = t / dt - 1;
	int i = (int)x;
	if (x <= 0)
		return fut[0] * (t / dt);
	if (i >= 198)
		return fut[199];
	return fut[i] + (x - i) * (fut[i + 1] - fut[i]);
}

// What the network gives the client, per scenario: gaussian jitter on every
// sample, and a share of samples that are garbage (a lost packet's catch-up).
typedef struct scenario_s { const char *name; float jitter, spike; } scenario_t;
static const scenario_t scenarios[] = { { "clean", 8, 0 }, { "jittery", 50, 0 }, { "clean + 3% garbage", 8, 0.03f } };
#define NSCEN 3
#define NFRAMES 16

typedef struct score_s { double shots, hits, direct_all; } score_t;
// [weapon][scenario][policy: 0 old (fixed 80 ms, fire at once), 1 the new filter, 2 filter and settle]
static score_t scores[3][NSCEN][3];
static const char *const wnames[3] = { "blaster", "mortar", "electro" };

static void simulate(int w, int sc, float dist, int trials)
{
	const touch_wep_fire_t *f = &TouchWep_Find(wnames[w])->pri;
	const scenario_t *S = &scenarios[sc];
	float g = 800, dt = 1.0f / 60, latency = 0.10f;
	int i;
	for (i = 0; i < trials; i++)
	{
		person_t p, path[NFRAMES];
		touch_pred_t pv;
		float old_v[3] = { 0, 0, 0 }, v0[3] = { 0, 0, 0 };
		float olds[NFRAMES][3], news[NFRAMES][3], unsettled[NFRAMES];
		int step, nsteps = (int)((3.0f + 2.0f * frand()) * 60), pol;
		memset(&p, 0, sizeof(p));
		p.dir = frand() < 0.5f ? -1.0f : 1.0f;
		p.next_flip = 0.3f;
		TouchPred_Reset(&pv, v0);
		for (step = 0; step < nsteps + NFRAMES; step++)
		{
			float smp[3];
			int k;
			person_step(&p, dt);
			smp[0] = 0;
			smp[1] = p.vel[1] + S->jitter * gauss();
			smp[2] = p.vel[2];
			if (frand() < S->spike)
				smp[1] += (frand() < 0.5f ? -600.0f : 600.0f);
			for (k = 0; k < 3; k++)
				old_v[k] += dt / 0.08f * (smp[k] - old_v[k]);
			TouchPred_Update(&pv, smp, dt);
			if (step >= nsteps)
			{
				int j = step - nsteps;
				path[j] = p;
				memcpy(olds[j], old_v, sizeof(old_v));
				memcpy(news[j], pv.vel, sizeof(pv.vel));
				unsettled[j] = TouchPred_Unsettled(&pv);
			}
		}
		for (pol = 0; pol < 3; pol++)
		{
			// the frame the shot goes: at once, or when the settle rule lets it
			int fr = 0;
			float rel[3], aim[3], tof, fut[200], e;
			person_t q;
			int m;
			if (pol == 2)
			{
				double since = 0;
				float tof0;
				float r0[3] = { dist, 0, path[0].pos[2] };
				if (!TouchWep_AimPoint(f, r0, news[0], latency, g, aim, &tof0))
					tof0 = 0.3f;
				for (fr = 0; fr < NFRAMES - 1; fr++)
					if (!TouchPred_Hold(&since, fr * dt, unsettled[fr], tof0 + latency))
						break;
			}
			rel[0] = dist;
			rel[1] = 0;
			rel[2] = path[fr].pos[2];
			if (!TouchWep_AimPoint(f, rel, pol >= 1 ? news[fr] : olds[fr], latency, g, aim, &tof))
				continue;
			q = path[fr];
			for (m = 0; m < 200; m++)
			{
				person_step(&q, dt);
				fut[m] = q.pos[1] - path[fr].pos[1];
			}
			e = fabsf(aim[1] - fut_at(fut, tof + latency, dt));
			scores[w][sc][pol].shots++;
			scores[w][sc][pol].hits += e < 16;
		}
	}
}

static void test_hit_rates(void)
{
	int w, sc;
	static const float dists[] = { 500, 900, 1400 };
	for (w = 0; w < 3; w++)
		for (sc = 0; sc < NSCEN; sc++)
		{
			int d;
			rng_state = 777 + w * 10 + sc;
			for (d = 0; d < 3; d++)
				simulate(w, sc, dists[d], 4000);
		}
	printf("simulated person (reverses about %.1f/s, jumps, 100 ms latency, 500 / 900 / 1400 units): body hits per shot: old -> new filter -> filter and settle\n", FLIP_RATE);
	for (sc = 0; sc < NSCEN; sc++)
	{
		printf("  %-20s", scenarios[sc].name);
		for (w = 0; w < 3; w++)
		{
			const score_t *o = &scores[w][sc][0], *f = &scores[w][sc][1], *n = &scores[w][sc][2];
			printf("  %s %4.1f -> %4.1f -> %4.1f %%", wnames[w], 100 * o->hits / o->shots, 100 * f->hits / f->shots, 100 * n->hits / n->shots);
			CHECK(f->hits / f->shots >= o->hits / o->shots - 0.005, "%s, %s: the filter alone does not lose (%.3f vs %.3f)", wnames[w], scenarios[sc].name, f->hits / f->shots, o->hits / o->shots);
			CHECK(n->hits / n->shots > o->hits / o->shots + 0.01, "%s, %s: filter and timing hit more (%.3f vs %.3f)", wnames[w], scenarios[sc].name, n->hits / n->shots, o->hits / o->shots);
		}
		printf("\n");
	}
}

// ---- cost -----------------------------------------------------------------

static void test_cost(void)
{
	const int N = 2000000;
	float smp[3] = { 20, 280, 0 };
	touch_pred_t pv;
	touch_brk_t b;
	touch_brk_cfg_t c = cfg();
	struct timespec a, z;
	int i;
	double ns_filter, ns_brk;
	float sink = 0;
	TouchPred_Reset(&pv, smp);
	memset(&b, 0, sizeof(b));
	clock_gettime(CLOCK_MONOTONIC, &a);
	for (i = 0; i < N; i++)
	{
		smp[1] = 280 + (i & 7) * 9;
		TouchPred_Update(&pv, smp, 0.016f);
		sink += TouchPred_Unsettled(&pv) + TouchPred_Confidence(pv.noise, 280);
	}
	clock_gettime(CLOCK_MONOTONIC, &z);
	ns_filter = ((z.tv_sec - a.tv_sec) * 1e9 + (z.tv_nsec - a.tv_nsec)) / N;
	clock_gettime(CLOCK_MONOTONIC, &a);
	for (i = 0; i < N; i++)
	{
		TouchBrk_Step(&b, &c, i * 0.016, 0.016f, 0.1f, (i & 3) * 0.1f, 3, 2, 1);
		memset(b.out, 0, sizeof(b.out));
	}
	clock_gettime(CLOCK_MONOTONIC, &z);
	ns_brk = ((z.tv_sec - a.tv_sec) * 1e9 + (z.tv_nsec - a.tv_nsec)) / N;
	printf("cost per call: velocity filter + confidence %.0f ns (per player in view), break-out step %.0f ns (once a frame)%s\n", ns_filter, ns_brk, sink == 0 ? "!" : "");
	CHECK(ns_filter < 2000 && ns_brk < 2000, "cheap enough");
}

int main(void)
{
	test_filter();
	test_settle();
	test_lead();
	test_mortar();
	test_breakout();
	test_hit_rates();
	test_cost();
	if (failures)
	{
		printf("touch aim predict: %d of %d checks FAILED\n", failures, checks);
		return 1;
	}
	printf("touch aim predict: %d checks OK\n", checks);
	return 0;
}
