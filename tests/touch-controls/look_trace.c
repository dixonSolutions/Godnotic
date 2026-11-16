/*
Prints the touch look curve before and after the 2026 default tuning, so a
review reads numbers rather than adjectives: gain at finger speeds across the
acceleration band, and the degrees a same-length swipe turns at 60 Hz (lift
included). The maths is touch_feel.h, the same as both HUDs.

Run through scripts/test-touch-controls.sh.
*/
#include <stdio.h>

#include "touch_feel.h"

static touchlook_cfg_t cfg(float lo, float hi)
{
	touchlook_cfg_t c;
	c.sens = 3.6f;
	c.y_mult = 0.85f;
	c.accel = 0.8f;
	c.accel_lo = lo;
	c.accel_hi = hi;
	c.filter = 1;
	c.fcmin = 4.0f;
	c.beta = 0.05f;
	c.max_rate = 900 * (3.6f / 2.8f) * (1 + c.accel);
	return c;
}

static float swipe(const touchlook_cfg_t *c, float cm, float seconds, float dpi)
{
	touchlook_t s;
	int n = (int)(seconds * 60 + 0.5f), i;
	float px = cm / 2.54f * dpi, density = dpi / 160.0f, total = 0, yaw, pitch;
	TouchLook_Reset(&s);
	if (n < 1)
		n = 1;
	for (i = 0; i < n; i++)
	{
		TouchLook_Step(&s, c, px / n / density, 0, 1.0f / 60.0f, &yaw, &pitch);
		total += yaw;
	}
	TouchLook_Flush(&s, &yaw, &pitch);
	return total + yaw;
}

int main(void)
{
	touchlook_cfg_t old = cfg(150, 900), now = cfg(200, 800);
	const float speeds[] = { 100, 150, 200, 300, 400, 500, 600, 700, 800, 900, 1200 };
	size_t i;
	printf("look curve (gain): speed dp/s   old 150..900   new 200..800\n");
	for (i = 0; i < sizeof(speeds) / sizeof(speeds[0]); i++)
		printf("  %6.0f  %12.3f  %14.3f\n", speeds[i],
			TouchFeel_Gain(&old, speeds[i]), TouchFeel_Gain(&now, speeds[i]));
	printf("swipe, tablet 283 dpi, 60 Hz, lift paid:\n");
	printf("  2 cm in 2.00 s   old %6.1f deg   new %6.1f deg\n", swipe(&old, 2, 2.0f, 283), swipe(&now, 2, 2.0f, 283));
	printf("  3 cm in 0.40 s   old %6.1f deg   new %6.1f deg\n", swipe(&old, 3, 0.40f, 283), swipe(&now, 3, 0.40f, 283));
	printf("  3 cm in 0.08 s   old %6.1f deg   new %6.1f deg\n", swipe(&old, 3, 0.08f, 283), swipe(&now, 3, 0.08f, 283));
	printf("  180 deg needs %.2f cm at 3 cm / 0.08 s (new: %.1f deg)\n",
		3 * 180 / swipe(&now, 3, 0.08f, 283), swipe(&now, 3, 0.08f, 283));
	return 0;
}
