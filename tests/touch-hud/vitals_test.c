// Checks touch_vitals.h: the health / armour / ammo readout is clearly bigger
// than the old 0.85 * short/540 sizing, scales with the screen, stays inside
// the safe area and within its size caps. No engine, no display.
#include <stdio.h>
#include <math.h>
#include "touch_vitals.h"

static int fails;
#define CHECK(c, ...) do { if (!(c)) { printf("FAIL %s:%d ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); fails++; } } while (0)

typedef struct { const char *name; int px_w, px_h; float dpi, conh; } dev_t;

static void run(const dev_t *d)
{
	float conw = d->conh * d->px_w / d->px_h;
	float dens = d->dpi / 160.0f; if (dens < 1) dens = 1; if (dens > 4) dens = 4;
	float dpu = dens * d->conh / d->px_h;
	float u = TouchVitals_Unit(conw, d->conh, dpu, VITALS_HUD_SCALE_DEF);
	float short_u = conw < d->conh ? conw : d->conh;
	float old_u = short_u / 540.0f * 0.85f;
	float font = VITALS_FONT_AUTHORED * u;
	float w = VITALS_WIDTH * u, h = VITALS_HEIGHT * u, ox, oy;
	TouchVitals_Origin(0.15f * conw, 0.115f * d->conh, w, h, conw, d->conh, 40 * dpu, 24 * dpu, 0, 0, VITALS_MARGIN_DP * dpu, &ox, &oy);
	printf("%-8s %4dx%-4d dpi %3.0f con %6.1fx%-5.1f number %5.1f units = %4.1f%% of short side (old %4.1f%%), %4.1f mm tall; block %4.1f%%w x %4.1f%%h at (%.0f,%.0f)\n",
		d->name, d->px_w, d->px_h, d->dpi, conw, d->conh, font, 100 * font / short_u, 100 * VITALS_FONT_AUTHORED * old_u / short_u,
		font / d->conh * d->px_h / d->dpi * 25.4f, 100 * w / conw, 100 * h / d->conh, ox, oy);
	CHECK(u > old_u * 1.3f, "%s: not clearly bigger (%.2f vs %.2f)", d->name, u, old_u);
	CHECK(h <= VITALS_MAX_H_FRAC * d->conh * 1.001f, "%s: block too tall", d->name);
	CHECK(w <= (conw < d->conh ? VITALS_MAX_W_FRAC_P : VITALS_MAX_W_FRAC) * conw * 1.001f, "%s: block too wide", d->name);
	CHECK(ox >= 40 * dpu - 0.01f && oy >= 24 * dpu - 0.01f, "%s: inside the notch inset", d->name);
	CHECK(ox + w <= conw && oy + h <= d->conh, "%s: off screen", d->name);
}

int main(void)
{
	static const dev_t devs[] = {
		{ "phone", 2400, 1080, 400, 540 }, { "tablet", 2560, 1600, 274, 800 }, { "web", 1366, 768, 96, 768 },
		{ "phone-p", 1080, 2400, 400, 1200 }, { "small", 1280, 720, 320, 540 },
	};
	unsigned i;
	for (i = 0; i < sizeof(devs) / sizeof(devs[0]); i++)
		run(&devs[i]);
	{
		float a = TouchVitals_Unit(960, 540, 1.4f, 0.6f), b = TouchVitals_Unit(960, 540, 1.4f, 0.85f), c = TouchVitals_Unit(960, 540, 1.4f, 1.6f);
		CHECK(a < b && b <= c, "scale knob order %.2f %.2f %.2f", a, b, c);
		CHECK(c * VITALS_HEIGHT <= VITALS_MAX_H_FRAC * 540 * 1.001f, "knob max overflows");
	}
	CHECK(TouchVitals_Unit(160, 90, 1, 0.85f) > 0, "tiny");
	CHECK(TouchVitals_Unit(7680, 4320, 4, 0.85f) * VITALS_HEIGHT <= VITALS_MAX_H_FRAC * 4320 * 1.001f, "8k");
	printf(fails ? "vitals_test: %d FAILED\n" : "vitals_test: all passed\n", fails);
	return fails != 0;
}
