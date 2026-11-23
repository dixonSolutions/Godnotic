// Touch: predictive Devastator guiding. Pure maths (needs touch_weapons.h
// first, for TouchWep_GuideStep), so tests/touch-aim/guide_test.c can fly the
// same rocket the server flies against simulated targets.
//
// The server turns a guided rocket at most guiderate degrees per second toward
// a goal point guidegoal units ahead on the player's view ray. Steering is
// therefore choosing the ray: here, the ray through the point where rocket and
// target are predicted to meet.
//
//  * Target: a motion model from the filtered velocity and an acceleration
//    estimate (a strafing player reverses within a fraction of a second, so the
//    acceleration fades out with TG_ACC_TAU instead of being extrapolated),
//    gravity while airborne, and the world (a trace) so the prediction stops at
//    a wall the way the player does.
//  * Rocket: its real position, heading, speed, acceleration and turn rate.
//    Candidate meeting points are the target's predicted positions at many
//    future times; for each the rocket's flight along that ray is simulated
//    with TouchWep_GuideStep and the world, and the candidate with the least
//    miss wins. The turn rate and the rocket's lag behind the ray are part of
//    the simulation, which a straight-line intercept ignores.
//  * Smoothness: the choice keeps to the previous meeting time unless another
//    is clearly better, and the aim point is low-pass filtered, so the view
//    neither jumps between candidates nor oscillates.
//  * Detonation: the plan knows when the rocket is closest to the target; it
//    is set off then if the blast reaches the target's predicted position.
#ifndef TOUCH_AIM_GUIDE_H
#define TOUCH_AIM_GUIDE_H

#define TG_DT        (1.0f / 30.0f)
#define TG_STEPS     66        // 2.2 s of flight
#define TG_ACC_TAU   0.45f     // the strafe acceleration fades over this
#define TG_ACC_MAX   1800.0f   // u/s^2, what a player can do
#define TG_STICKY    10.0f     // a candidate within this many units of the best keeps the old time
#define TG_AIM_TAU   0.05f     // aim point smoothing
#define TG_AIM_SLEW  300.0f    // deg/s the aim direction may swing, seen from the eye
#define TG_MEMORY    0.6f      // seconds a target out of sight is still steered at
#define TG_WALL_PEN  200.0f    // a flight that hits the world short of the target

typedef struct
{
	float speed, accel, speed_max; // g_balance_devastator_speed / _speedaccel / the top speed
	float rate_deg, goal;          // g_balance_devastator_guiderate / _guidegoal
	float splash;                  // blast radius
} tg_rocket_t;

typedef struct
{
	float org[3], vel[3], acc[3];  // filtered motion; acc is the estimate
	float gravity;                 // only while airborne
	int airborne;
	float floor_z;                 // centre height when standing on the floor below
} tg_target_t;

// world: 1 and *end = where the segment a->b first hits solid, 0 clear
typedef int (*tg_trace_fn)(void *ctx, const float a[3], const float b[3], float end[3]);

typedef struct
{
	int valid;
	float aim[3];       // the meeting point the view ray goes through
	float t_meet;       // seconds from now
	float miss;         // closest approach, flight along that ray
	float t_close;      // when it is
	int hits_world;
} tg_plan_t;

typedef struct
{
	int have;
	float prev_t;       // last meeting time (counted down each frame)
	float aim_s[3];     // smoothed aim point
} tg_state_t;

static float tg_len(const float *a) { return sqrtf(tw_dot(a, a)); }

// the target after t seconds
static void TouchGuide_TargetAt(const tg_target_t *g, float t, float out[3])
{
	float e = expf(-t / TG_ACC_TAU), k = TG_ACC_TAU * (t - TG_ACC_TAU * (1 - e));
	int i;
	for (i = 0; i < 2; i++)
		out[i] = g->org[i] + g->vel[i] * t + g->acc[i] * k;
	out[2] = g->org[2];
	if (g->airborne)
	{
		out[2] += g->vel[2] * t - 0.5f * g->gravity * t * t;
		if (out[2] < g->floor_z)
			out[2] = g->floor_z;
	}
}

// the target's velocity after t seconds (for the detonation rule)
static void TouchGuide_VelAt(const tg_target_t *g, float t, float out[3])
{
	float e = expf(-t / TG_ACC_TAU);
	int i;
	for (i = 0; i < 2; i++)
		out[i] = g->vel[i] + g->acc[i] * TG_ACC_TAU * (1 - e);
	out[2] = g->airborne ? g->vel[2] - g->gravity * t : 0;
}

// a target last seen `age` seconds ago, moved on by its own model, fading
// out; 0 when it has been out of sight too long to steer at
static int TouchGuide_Advance(tg_target_t *g, float age)
{
	float p[3], v[3], fade;
	if (age <= 0)
		return 1;
	if (age >= TG_MEMORY)
		return 0;
	TouchGuide_TargetAt(g, age, p);
	TouchGuide_VelAt(g, age, v);
	fade = 1 - age / TG_MEMORY; // a player nobody sees any more is less and less where the model says
	memcpy(g->org, p, sizeof(p));
	memcpy(g->vel, v, sizeof(v));
	g->vel[0] *= fade;
	g->vel[1] *= fade;
	g->acc[0] = g->acc[1] = g->acc[2] = 0;
	if (g->airborne && g->org[2] <= g->floor_z)
		g->airborne = 0;
	return 1;
}

// Acceleration estimate from successive filtered velocities: a first-order
// low-pass on the difference, clamped to what a player can do.
typedef struct { int have; float vel[3]; float acc[3]; } tg_track_t;
static void TouchGuide_TrackUpdate(tg_track_t *t, const float vel[3], float dt, float acc_out[3])
{
	int i;
	float a[3] = { 0, 0, 0 }, k, m;
	if (dt < 1e-4f)
	{
		memcpy(acc_out, t->acc, sizeof(t->acc));
		return;
	}
	if (t->have)
		for (i = 0; i < 2; i++)
			a[i] = (vel[i] - t->vel[i]) / dt;
	k = 1 - expf(-dt / 0.12f);
	for (i = 0; i < 2; i++)
		t->acc[i] += (a[i] - t->acc[i]) * (t->have ? k : 1);
	m = sqrtf(t->acc[0] * t->acc[0] + t->acc[1] * t->acc[1]);
	if (m > TG_ACC_MAX)
		for (i = 0; i < 2; i++)
			t->acc[i] *= TG_ACC_MAX / m;
	memcpy(t->vel, vel, sizeof(t->vel));
	t->have = 1;
	memcpy(acc_out, t->acc, sizeof(t->acc));
}

typedef struct { float miss, t_close; int hit_world; } tg_flight_t;

// fly the rocket along the ray eye -> through `ray_pt`, against the target's
// predicted track P[] (index i = i * TG_DT from now)
static void TouchGuide_Fly(const tg_rocket_t *r, const float rorg[3], const float rvel[3], const float eye[3], const float ray_pt[3],
	const float P[][3], tg_trace_fn tr, void *ctx, tg_flight_t *out)
{
	float p[3], dir[3], view[3], np[3], end[3], sp = tg_len(rvel), best = 1e9f, d[3];
	int i;
	out->miss = 1e9f;
	out->t_close = 0;
	out->hit_world = 0;
	if (sp < 1)
		return;
	memcpy(p, rorg, sizeof(p));
	for (i = 0; i < 3; i++)
	{
		dir[i] = rvel[i] / sp;
		view[i] = ray_pt[i] - eye[i];
	}
	{
		float l = tg_len(view);
		if (l < 1e-3f)
			return;
		for (i = 0; i < 3; i++)
			view[i] /= l;
	}
	for (i = 1; i <= TG_STEPS; i++)
	{
		memcpy(np, p, sizeof(np));
		TouchWep_GuideStep(np, dir, &sp, eye, view, r->goal, r->rate_deg, r->accel, r->speed_max, TG_DT);
		if (tr && tr(ctx, p, np, end))
		{
			for (int k = 0; k < 3; k++)
				d[k] = end[k] - P[i][k];
			if (tg_len(d) < best)
			{
				best = tg_len(d);
				out->t_close = i * TG_DT;
			}
			// bursting on something short of the target counts against it
			out->hit_world = 1;
			if (best > 0.6f * r->splash)
				best += TG_WALL_PEN;
			break;
		}
		for (int k = 0; k < 3; k++)
			d[k] = np[k] - P[i][k];
		if (tg_len(d) < best)
		{
			best = tg_len(d);
			out->t_close = i * TG_DT;
		}
		else if (tg_len(d) > best + 60 && best < 1e8f && i * TG_DT > out->t_close + 0.25f)
			break; // past it and moving away
		memcpy(p, np, sizeof(p));
	}
	out->miss = best;
}

// Plan from the rocket's own state. state may be reset (have = 0) at launch.
static void TouchGuide_Plan(const tg_rocket_t *r, const float rorg[3], const float rvel[3], const float eye[3], const tg_target_t *g,
	tg_trace_fn tr, void *ctx, float dt, tg_state_t *st, tg_plan_t *out)
{
	static float P[TG_STEPS + 1][3];
	float prev[3], hit[3];
	float best = 1e9f, best_t = 0, pick_t = 0, pick_miss = 1e9f;
	tg_flight_t fl[TG_STEPS + 1], pf;
	int i, pick = -1;
	memset(out, 0, sizeof(*out));
	// the target's predicted track, stopped by the world where the player is
	memcpy(prev, g->org, sizeof(prev));
	memcpy(P[0], g->org, sizeof(prev));
	for (i = 1; i <= TG_STEPS; i++)
	{
		TouchGuide_TargetAt(g, i * TG_DT, P[i]);
		if (tr && tr(ctx, prev, P[i], hit))
			memcpy(P[i], hit, sizeof(hit));
		memcpy(prev, P[i], sizeof(prev));
	}
	for (i = 2; i <= TG_STEPS; i += 2)
	{
		TouchGuide_Fly(r, rorg, rvel, eye, P[i], (const float (*)[3])P, tr, ctx, &pf);
		fl[i] = pf;
		if (pf.miss + 0.4f * i < best)
		{
			best = pf.miss + 0.4f * i;
			best_t = i * TG_DT;
		}
	}
	if (best >= 1e8f)
		return;
	// the best candidate and any within TG_STICKY of it: keep to the last time
	pick_t = best_t;
	{
		float want = st->have ? st->prev_t - dt : -1, bd = 1e9f;
		for (i = 2; i <= TG_STEPS; i += 2)
		{
			if (fl[i].miss + 0.4f * i > best + TG_STICKY)
				continue;
			if (want < 0)
			{
				if (fl[i].miss + 0.4f * i <= best + 1e-3f)
					pick = i;
			}
			else if (fabsf(i * TG_DT - want) < bd)
			{
				bd = fabsf(i * TG_DT - want);
				pick = i;
			}
		}
	}
	if (pick < 0)
		return;
	pick_t = pick * TG_DT;
	pick_miss = fl[pick].miss;
	st->prev_t = pick_t;
	if (!st->have)
		memcpy(st->aim_s, P[pick], sizeof(st->aim_s));
	else
	{
		float k = 1 - expf(-dt / TG_AIM_TAU);
		float a0[3], a1[3], l0, l1, c, ang, mx = TG_AIM_SLEW * dt * (float)(M_PI / 180.0);
		for (i = 0; i < 3; i++)
		{
			a0[i] = st->aim_s[i] - eye[i];
			st->aim_s[i] += (P[pick][i] - st->aim_s[i]) * k;
			a1[i] = st->aim_s[i] - eye[i];
		}
		// never swing faster than the view can follow: keep the distance, limit the turn
		l0 = tg_len(a0);
		l1 = tg_len(a1);
		if (l0 > 1 && l1 > 1)
		{
			c = tw_dot(a0, a1) / (l0 * l1);
			c = c > 1 ? 1 : c < -1 ? -1 : c;
			ang = acosf(c);
			if (ang > mx && ang > 1e-4f)
			{
				float f = mx / ang;
				for (i = 0; i < 3; i++)
					a1[i] = a0[i] / l0 + (a1[i] / l1 - a0[i] / l0) * f;
				c = tg_len(a1);
				for (i = 0; i < 3; i++)
					st->aim_s[i] = eye[i] + a1[i] / c * l1;
			}
		}
	}
	st->have = 1;
	memcpy(out->aim, st->aim_s, sizeof(out->aim));
	out->valid = 1;
	out->t_meet = pick_t;
	out->miss = pick_miss;
	out->t_close = fl[pick].t_close;
	out->hits_world = fl[pick].hit_world;
}

// Set the rocket off now? When the plan says it is at its closest to where the
// target will be (within the time for the press to arrive) and the blast
// reaches it, or the rocket has already passed and is moving away.
// d_now: distance rocket to target now; closing: its rate of approach (>0 closer);
// self_d: distance from the player to the rocket.
static int TouchGuide_Detonate(const tg_plan_t *p, float d_now, float closing, float lookahead, float self_d, float splash)
{
	if (self_d < splash + 40)
		return 0;
	if (d_now > splash * 0.95f)
		return 0;
	if (p->valid && p->t_close <= lookahead && p->miss < splash * 0.7f)
		return 1;
	return closing <= 0 && d_now < splash * 0.75f;
}

#endif
