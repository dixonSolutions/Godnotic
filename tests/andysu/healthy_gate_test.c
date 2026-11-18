#include <assert.h>
#include "healthy_gate.h"

int main(void)
{
	healthy_gate_t g = {0};
	assert(!healthy_gate_frame(&g, 1, 0, 0));
	assert(!healthy_gate_frame(&g, 2, 1, 0));
	assert(!healthy_gate_frame(&g, 9.99, 1, 0));
	assert(healthy_gate_frame(&g, 10, 1, 0));
	assert(!healthy_gate_frame(&g, 8, 1, 1));
	g = (healthy_gate_t){0};
	assert(!healthy_gate_frame(&g, 1, 1, 0));
	assert(!healthy_gate_frame(&g, 5, 0, 0));
	assert(!healthy_gate_frame(&g, 10, 1, 0));
	assert(!healthy_gate_frame(&g, 17.99, 1, 0));
	assert(healthy_gate_frame(&g, 18, 1, 0));
	g = (healthy_gate_t){0};
	assert(!healthy_gate_frame(&g, 1, 1, 0));
	assert(healthy_gate_frame(&g, 2, 0, 1));
	assert(!healthy_gate_frame(&g, 8, 1, 1));
	/* menu-only use: any UI drawn continuously for the stable window, no world ever */
	g = (healthy_gate_t){0};
	assert(!healthy_gate_frame(&g, 100, 1, 0));
	assert(!healthy_gate_frame(&g, 107.9, 1, 0));
	assert(healthy_gate_frame(&g, 108, 1, 0));
	assert(!healthy_gate_frame(&g, 200, 1, 0));
	/* the frame loop alone (native UI, nothing drawn by the engine) confirms after 30s */
	g = (healthy_gate_t){0};
	assert(!healthy_gate_tick(&g, 50));
	assert(!healthy_gate_tick(&g, 79.9));
	assert(healthy_gate_tick(&g, 80));
	assert(!healthy_gate_tick(&g, 90));
	assert(!healthy_gate_frame(&g, 95, 1, 1));
	/* a crash before either leaves it unreported: rollback stays */
	g = (healthy_gate_t){0};
	assert(!healthy_gate_tick(&g, 1));
	assert(!healthy_gate_tick(&g, 10));
	assert(!g.reported);
	return 0;
}
