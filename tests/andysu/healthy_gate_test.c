#include <assert.h>
#include "healthy_gate.h"

int main(void)
{
	healthy_gate_t g = {0};
	assert(!healthy_gate_frame(&g, 1, 0, 0));
	assert(!healthy_gate_frame(&g, 2, 1, 0));
	assert(!healthy_gate_frame(&g, 6.99, 1, 0));
	assert(healthy_gate_frame(&g, 7, 1, 0));
	assert(!healthy_gate_frame(&g, 8, 1, 1));
	g = (healthy_gate_t){0};
	assert(!healthy_gate_frame(&g, 1, 1, 0));
	assert(!healthy_gate_frame(&g, 5, 0, 0));
	assert(!healthy_gate_frame(&g, 10, 1, 0));
	assert(!healthy_gate_frame(&g, 14.99, 1, 0));
	assert(healthy_gate_frame(&g, 15, 1, 0));
	g = (healthy_gate_t){0};
	assert(!healthy_gate_frame(&g, 1, 1, 0));
	assert(healthy_gate_frame(&g, 2, 0, 1));
	assert(!healthy_gate_frame(&g, 8, 1, 1));
	return 0;
}
