// Touch: size and placement of the in-match health / armour / ammo readout.
// Pure maths, no engine state, so tests/touch-hud/vitals_test.c can check it
// for a phone, a tablet and a desktop window. touch_hud.c (engine HUD) and
// touch_hud.qc (the port's CSQC HUD) use the same numbers; keep them in step.
//
// The readout is authored in rows of 30 units at the 540-unit reference short
// axis. Its unit (hud_u) used to be short_axis/540 * 0.85, about 3.8% of the
// short side for the numbers. Now it follows the physical size of the screen:
// the number is VITALS_FONT_DP density-independent pixels tall, held between
// VITALS_MIN_FRAC and VITALS_MAX_FRAC of the short side, and the whole block
// never takes more than VITALS_MAX_H_FRAC of the height or VITALS_MAX_W_FRAC
// of the width. touch_hud_scale (default 0.85) stays the user's knob, as a
// ratio to its default.
#ifndef TOUCH_VITALS_H
#define TOUCH_VITALS_H

#define VITALS_FONT_AUTHORED  24.0f
#define VITALS_FONT_DP        40.0f
#define VITALS_MIN_FRAC       0.060f   // number height / short side
#define VITALS_MAX_FRAC       0.085f
#define VITALS_MAX_H_FRAC     0.235f   // block height / screen height: the chat log starts below it (hud_panel_chat_pos)
#define VITALS_MAX_W_FRAC     0.265f   // block width / screen width (landscape)
#define VITALS_MAX_W_FRAC_P   0.46f    // the same on a portrait screen
#define VITALS_HUD_SCALE_DEF  0.85f
#define VITALS_MARGIN_DP      6.0f

// authored units: icon, gap, number slot, gap, bar; rows and gaps
#define VITALS_ICON   26.0f
#define VITALS_GAP1   6.0f
#define VITALS_NUM    54.0f
#define VITALS_GAP2   12.0f
#define VITALS_BAR    72.0f
#define VITALS_ROW_H  30.0f
#define VITALS_ROW_GAP 6.0f
#define VITALS_WIDTH  (VITALS_ICON + VITALS_GAP1 + VITALS_NUM + VITALS_GAP2 + VITALS_BAR)
// health and armour are the big rows; the ammo row under them is VITALS_AMMO_SCALE of their size
#define VITALS_AMMO_SCALE 0.7f
#define VITALS_HEIGHT ((VITALS_ROW_H + VITALS_ROW_GAP) * 2 + VITALS_ROW_H * VITALS_AMMO_SCALE)

static inline float TouchVitals_Unit(float ui_w, float ui_h, float dp_units, float hud_scale_cvar)
{
	float short_u = ui_w < ui_h ? ui_w : ui_h;
	float u, lo, hi, user, cap;
	u = VITALS_FONT_DP * dp_units / VITALS_FONT_AUTHORED;
	lo = VITALS_MIN_FRAC * short_u / VITALS_FONT_AUTHORED;
	hi = VITALS_MAX_FRAC * short_u / VITALS_FONT_AUTHORED;
	u = u < lo ? lo : u > hi ? hi : u;
	user = hud_scale_cvar > 0.4f ? hud_scale_cvar : 0.4f;
	u *= user / VITALS_HUD_SCALE_DEF;
	cap = VITALS_MAX_H_FRAC * ui_h / VITALS_HEIGHT;
	if (u > cap)
		u = cap;
	cap = (ui_w < ui_h ? VITALS_MAX_W_FRAC_P : VITALS_MAX_W_FRAC) * ui_w / VITALS_WIDTH;
	if (u > cap)
		u = cap;
	return u;
}

// Top-left corner of the block: centred on (cx, cy), then kept inside the
// safe rectangle (insets from a notch or rounded corners, plus a margin).
static inline void TouchVitals_Origin(float cx, float cy, float w, float h, float ui_w, float ui_h,
	float in_l, float in_t, float in_r, float in_b, float margin, float *ox, float *oy)
{
	float x = cx - w * 0.5f, y = cy - h * 0.5f;
	float xmin = in_l + margin, xmax = ui_w - in_r - margin - w;
	float ymin = in_t + margin, ymax = ui_h - in_b - margin - h;
	if (xmax < xmin) xmax = xmin;
	if (ymax < ymin) ymax = ymin;
	*ox = x < xmin ? xmin : x > xmax ? xmax : x;
	*oy = y < ymin ? ymin : y > ymax ? ymax : y;
}

#endif
