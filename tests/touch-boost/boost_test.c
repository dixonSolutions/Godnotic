/*
Checks the decisions touch auto boost makes (engine/darkplaces/touch_boost.h):
the gates (trigger, pitch, floor distance, no floor), the weapon chosen for
the launch (not merely what is in hand), the pitch it fires along, and the
cases where what is in hand is kept or refused.

Run through scripts/test-touch-boost.sh.
*/

#include <math.h>
#include <stdbool.h>
#include <stdio.h>

typedef bool qbool;

#include "touch_boost.h"

// Indices into touch_weapons[] (engine/darkplaces/touch_weapons.h), by netname.
#define W_BLASTER     0
#define W_SHOTGUN     1
#define W_MACHINEGUN  2
#define W_ARC         3
#define W_MORTAR      4
#define W_ELECTRO     6
#define W_HAGAR      12
#define W_SEEKER     13
#define W_DEVASTATOR 14
#define W_FIREBALL   15

static int failures, checks;

#define CHECK(cond, what) do { \
	checks++; \
	if (!(cond)) { \
		printf("FAIL %s\n", (what)); \
		failures++; \
	} \
} while (0)

#define CHECK_EQ(expected, actual, what) do { \
	int e_ = (expected), a_ = (actual); \
	checks++; \
	if (e_ != a_) { \
		printf("FAIL %s: expected %d, got %d\n", (what), e_, a_); \
		failures++; \
	} \
} while (0)

#define CHECK_NEAR(expected, actual, tol, what) do { \
	double e_ = (expected), a_ = (actual); \
	checks++; \
	if (fabs(e_ - a_) > (tol)) { \
		printf("FAIL %s: expected %.4f, got %.4f\n", (what), e_, a_); \
		failures++; \
	} \
} while (0)

static int weapon_index(const char *netname)
{
	int i;
	for (i = 0; i < TW_NUM_WEAPONS; i++)
		if (!strcmp(touch_weapons[i].netname, netname))
			return i;
	return -1;
}

static void decide(float pitch, float floor_dist, int weapon, qbool want,
	qbool *fire, int *wep, float *aim)
{
	TouchBoost_Decide(pitch, floor_dist, weapon, want, fire, wep, aim);
}

// A standing eye (59 above the feet) over a plain floor: the stock case.
static void test_standing_devastator(void)
{
	qbool fire;
	int wep;
	float aim;

	decide(75, TB_EYE_HEIGHT, W_MACHINEGUN, true, &fire, &wep, &aim);
	CHECK(fire, "standing: fires");
	CHECK_EQ(weapon_index("devastator"), wep, "standing: switches to the Devastator");
	CHECK_NEAR(atan2(TB_EYE_HEIGHT, TB_BLAST_AHEAD) * TW_DEG, aim, 1e-3,
		"standing: pitch aims the blast at the box edge");

	// An unknown weapon in hand: the choice is still made.
	decide(75, TB_EYE_HEIGHT, -1, true, &fire, &wep, &aim);
	CHECK(fire, "unknown held: fires");
	CHECK_EQ(weapon_index("devastator"), wep, "unknown held: Devastator");
}

static void test_gates(void)
{
	qbool fire;
	int wep;
	float aim;

	// Trigger off: nothing, whatever the pitch and floor say.
	decide(75, TB_EYE_HEIGHT, W_MACHINEGUN, false, &fire, &wep, &aim);
	CHECK(!fire, "trigger off: no fire");

	// Pitch gate: 60 degrees down is the least; 59.9 is not a floor aim.
	decide(TB_PITCH_MIN, TB_EYE_HEIGHT, W_MACHINEGUN, true, &fire, &wep, &aim);
	CHECK(fire, "pitch gate: 60 down passes");
	decide(TB_PITCH_MIN - 0.1f, TB_EYE_HEIGHT, W_MACHINEGUN, true, &fire, &wep, &aim);
	CHECK(!fire, "pitch gate: shallower than 60 does not");
	decide(-75, TB_EYE_HEIGHT, W_MACHINEGUN, true, &fire, &wep, &aim);
	CHECK(!fire, "pitch gate: looking up does not");

	// No floor: a trace that found nothing comes back as 0.
	decide(75, 0, W_MACHINEGUN, true, &fire, &wep, &aim);
	CHECK(!fire, "no floor: no fire");

	// A trace inside the player's own box is not a floor either.
	decide(75, TB_FLOOR_MIN - 1, W_MACHINEGUN, true, &fire, &wep, &aim);
	CHECK(!fire, "floor inside the box: no fire");
}

static void test_floor_range(void)
{
	qbool fire;
	int wep;
	float aim;

	// Over a drop 200 below the eye: the box is 141 above the blast, past
	// every splash radius in the table (Devastator and Mortar reach 169).
	decide(75, 200, W_DEVASTATOR, true, &fire, &wep, &aim);
	CHECK(!fire, "floor 200: past every reach");

	// 100 below: 41 above the blast, inside the Devastator's 110.
	decide(75, 100, W_MACHINEGUN, true, &fire, &wep, &aim);
	CHECK(fire, "floor 100: fires");
	CHECK_EQ(weapon_index("devastator"), wep, "floor 100: Devastator");

	// High enough that only the widest splash still reaches (the Fireball's
	// 200, up to 59 + 200 = 259): only in hand, never auto-switched to.
	decide(75, 219, W_MACHINEGUN, true, &fire, &wep, &aim);
	CHECK(!fire, "floor 219: nothing switchable reaches");

	decide(75, 219, weapon_index("fireball"), true, &fire, &wep, &aim);
	CHECK(fire, "floor 219 with the Fireball in hand: fires");
	CHECK_EQ(weapon_index("fireball"), wep, "floor 219 with the Fireball: keeps it");
}

static void test_weapon_choice(void)
{
	qbool fire;
	int wep;
	float aim;

	// What is in hand does not decide: a MachineGun blast has no force.
	decide(75, TB_EYE_HEIGHT, weapon_index("machinegun"), true, &fire, &wep, &aim);
	CHECK_EQ(weapon_index("devastator"), wep, "MachineGun in hand: Devastator chosen");

	// Crylink pulls (-50 force): never a boost weapon.
	decide(75, TB_EYE_HEIGHT, weapon_index("crylink"), true, &fire, &wep, &aim);
	CHECK_EQ(weapon_index("devastator"), wep, "Crylink in hand: Devastator chosen");

	// The Fireball's blast would cost 130 health: past the self-damage cap.
	decide(75, TB_EYE_HEIGHT, weapon_index("fireball"), true, &fire, &wep, &aim);
	CHECK_EQ(weapon_index("devastator"), wep, "Fireball in hand: Devastator chosen");

	// A weapon already in hand that pushes nearly as hard is kept: the
	// Blaster's 375 is within 10 % of the Devastator's 400, and a switch
	// costs the raise time.
	decide(75, TB_EYE_HEIGHT, weapon_index("blaster"), true, &fire, &wep, &aim);
	CHECK(fire, "Blaster in hand: fires");
	CHECK_EQ(weapon_index("blaster"), wep, "Blaster in hand: kept over the switch");

	// The Devastator itself is the best: kept.
	decide(75, TB_EYE_HEIGHT, weapon_index("devastator"), true, &fire, &wep, &aim);
	CHECK_EQ(weapon_index("devastator"), wep, "Devastator in hand: kept");

	// Where the Blaster is out of reach (past 59 + 60 = 119) but the
	// Devastator is not (169), the switch is worth it.
	decide(75, 130, weapon_index("blaster"), true, &fire, &wep, &aim);
	CHECK(fire, "floor 130 with the Blaster: fires");
	CHECK_EQ(weapon_index("devastator"), wep, "floor 130 with the Blaster: Devastator");

	// An Out-of-reach seeker missile in hand with nothing switchable: no
	// fire at all (nothing usable is invented).
	decide(75, 200, weapon_index("seeker"), true, &fire, &wep, &aim);
	CHECK(!fire, "floor 200 with the Seeker: no fire");

	// Outputs are defined when nothing fires: held weapon and pitch back.
	decide(-75, 0, W_MACHINEGUN, true, &fire, &wep, &aim);
	CHECK(!fire, "no case: no fire");
	CHECK_EQ(W_MACHINEGUN, wep, "no case: held weapon back");
	CHECK_NEAR(-75, aim, 1e-6, "no case: pitch back");
}

static void test_pitch_per_floor(void)
{
	qbool fire;
	int wep;
	float aim;

	// The pitch aims the blast at the box edge: atan2(floor_dist, 16).
	decide(80, 64, W_MACHINEGUN, true, &fire, &wep, &aim);
	CHECK(fire, "floor 64: fires");
	CHECK_NEAR(atan2(64, 16) * TW_DEG, aim, 1e-3, "floor 64: pitch atan2(64, 16)");

	decide(80, 119, W_MACHINEGUN, true, &fire, &wep, &aim);
	CHECK_NEAR(atan2(119, 16) * TW_DEG, aim, 1e-3, "floor 119: pitch atan2(119, 16)");
}

static void test_physics(void)
{
	// Hand-computed pushes at the standing geometry (gap 0, vert
	// 59 / sqrt(16^2 + 59^2) = 0.9652): the ranking follows the balance
	// table's force at full damage.
	float vert = TB_EYE_HEIGHT / sqrt(TB_BLAST_AHEAD * TB_BLAST_AHEAD + TB_EYE_HEIGHT * TB_EYE_HEIGHT);
	const touch_boost_wep_t *dev = TouchBoost_Row(weapon_index("devastator"));
	const touch_boost_wep_t *bla = TouchBoost_Row(weapon_index("blaster"));
	const touch_boost_wep_t *fir = TouchBoost_Row(weapon_index("fireball"));

	CHECK(dev && bla && fir, "table rows found");

	CHECK_NEAR(400.0f * vert, TouchBoost_VerticalPush(dev, 0, TB_EYE_HEIGHT), 1e-2,
		"Devastator push: force * 1 * vert at gap 0");
	CHECK(TouchBoost_VerticalPush(dev, 0, TB_EYE_HEIGHT)
		> TouchBoost_VerticalPush(bla, 0, TB_EYE_HEIGHT), "Devastator out-pushes Blaster");

	// Self-damage: Devastator 52 (80 * 0.65) passes the cap, Fireball 130 does not.
	CHECK_NEAR(52.0f, TouchBoost_SelfDamage(dev, 0), 1e-2, "Devastator self-damage 52");
	CHECK(TouchBoost_Usable(dev, 0), "Devastator usable at gap 0");
	CHECK(!TouchBoost_Usable(fir, 0), "Fireball refused at gap 0: 130 health");

	// Usability is measured in gap (blast to box), not floor distance: the
	// Blaster's radius is 60, so 59 is inside and 60 is not.
	CHECK(!TouchBoost_Usable(bla, bla->radius), "Blaster at its radius: out");
	CHECK(TouchBoost_Usable(bla, bla->radius - 1), "Blaster just inside its radius");

	// High fall: the Devastator's damage thins out with the gap but stays
	// usable, and its push falls with it.
	CHECK(TouchBoost_Usable(dev, 100), "Devastator usable at gap 100");
	CHECK(TouchBoost_VerticalPush(dev, 100, 159) < TouchBoost_VerticalPush(dev, 0, TB_EYE_HEIGHT),
		"push thins with the gap");
}

int main(void)
{
	(void)W_SEEKER;
	test_standing_devastator();
	test_gates();
	test_floor_range();
	test_weapon_choice();
	test_pitch_per_floor();
	test_physics();

	printf("%d checks, %d failures\n", checks, failures);
	return failures ? 1 : 0;
}
