#ifndef GODNOTIC_HEALTHY_GATE_H
#define GODNOTIC_HEALTHY_GATE_H

typedef struct { double menu_since; int menu_visible, reported; } healthy_gate_t;

/* Called only after drawing. A menu must remain visible for five seconds. */
static inline int healthy_gate_frame(healthy_gate_t *g, double now, int menu, int world)
{
	if (g->reported) return 0;
	if (!menu) g->menu_visible = 0;
	else if (!g->menu_visible) { g->menu_visible = 1; g->menu_since = now; }
	if (world || (g->menu_visible && now - g->menu_since >= 5.0))
	{
		g->reported = 1;
		return 1;
	}
	return 0;
}
#endif
