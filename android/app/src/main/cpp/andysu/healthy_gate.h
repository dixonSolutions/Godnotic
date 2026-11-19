#ifndef GODNOTIC_HEALTHY_GATE_H
#define GODNOTIC_HEALTHY_GATE_H

/* A release is healthy once it has been up and drawing without crashing; a world render is not
 * required (a player who only uses menus never renders one). Three ways in:
 *  - a main world was drawn: immediately;
 *  - frames were drawn continuously (host active, any UI: QuakeC menu, native settings, loading)
 *    for HEALTHY_GATE_STABLE_S seconds;
 *  - the frame loop has simply kept running for HEALTHY_GATE_UPTIME_S seconds (covers native UI
 *    that stops the engine drawing). A crash before any of these still rolls the trial back. */
#define HEALTHY_GATE_STABLE_S 8.0
#define HEALTHY_GATE_UPTIME_S 30.0

typedef struct { double menu_since, first_tick; int menu_visible, reported, ticking; } healthy_gate_t;

/* Called only after drawing. `menu` means "some UI is being drawn and the engine is active". */
static inline int healthy_gate_frame(healthy_gate_t *g, double now, int menu, int world)
{
	if (g->reported) return 0;
	if (!menu) g->menu_visible = 0;
	else if (!g->menu_visible) { g->menu_visible = 1; g->menu_since = now; }
	if (world || (g->menu_visible && now - g->menu_since >= HEALTHY_GATE_STABLE_S))
	{
		g->reported = 1;
		return 1;
	}
	return 0;
}

/* Called once per engine frame (drawn or not): healthy after the loop has run long enough. */
static inline int healthy_gate_tick(healthy_gate_t *g, double now)
{
	if (g->reported) return 0;
	if (!g->ticking) { g->ticking = 1; g->first_tick = now; return 0; }
	if (now - g->first_tick >= HEALTHY_GATE_UPTIME_S)
	{
		g->reported = 1;
		return 1;
	}
	return 0;
}
#endif
