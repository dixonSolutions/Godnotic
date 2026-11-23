/* touch_bspshot.h: a picture of a Quake 1 BSP, and the PNG it is written as */
#include <assert.h>
#include <stdio.h>
#include "touch_bspshot.h"

static void put32(unsigned char *p, int v) { p[0] = (unsigned char)v; p[1] = (unsigned char)(v >> 8); p[2] = (unsigned char)(v >> 16); p[3] = (unsigned char)(v >> 24); }
static void put16(unsigned char *p, int v) { p[0] = (unsigned char)v; p[1] = (unsigned char)(v >> 8); }
static void putf(unsigned char *p, float f) { union { float f; int i; } u; u.f = f; put32(p, u.i); }

static unsigned char bsp[8192];
static size_t used;
/* append lump `which` with `n` zero bytes; returns where its data is */
static unsigned char *lump(int which, size_t n)
{
	unsigned char *at = bsp + used;
	put32(bsp + 4 + which * 8, (int)used); put32(bsp + 8 + which * 8, (int)n);
	used += n;
	return at;
}
/* a floor quad (normal +z, texture "floor") and a quad above it (texture `second`) on model 0 */
static size_t build(const char *second, int faces)
{
	unsigned char *p;
	int i;
	static const float quad[4][2] = { {0,0}, {256,0}, {256,256}, {0,256} };
	memset(bsp, 0, sizeof(bsp));
	put32(bsp, 29);
	used = 4 + 15 * 8;
	lump(0, 0);
	p = lump(1, 20); putf(p + 8, 1.0f);
	p = lump(2, 12 + 80); put32(p, 2); put32(p + 4, 12); put32(p + 8, 52);
	memcpy(p + 12, "floor", 5); memcpy(p + 52, second, strlen(second));
	p = lump(3, 8 * 12);
	for (i = 0; i < 8; i++) { putf(p + i * 12, quad[i & 3][0]); putf(p + i * 12 + 4, quad[i & 3][1]); putf(p + i * 12 + 8, i < 4 ? 0.0f : 64.0f); }
	lump(4, 0); lump(5, 0);
	p = lump(6, 80); put32(p + 32, 0); put32(p + 40 + 32, 1);
	p = lump(7, 40);
	put16(p, 0); put32(p + 4, 0); put16(p + 8, 4); put16(p + 10, 0);
	put16(p + 20, 0); put32(p + 24, 4); put16(p + 28, 4); put16(p + 30, 1);
	lump(8, 0); lump(9, 0); lump(10, 0); lump(11, 0);
	p = lump(12, 9 * 4);
	for (i = 0; i < 8; i++) { int base = (i / 4) * 4; put16(p + (i + 1) * 4, base + (i & 3)); put16(p + (i + 1) * 4 + 2, base + ((i + 1) & 3)); }
	p = lump(13, 8 * 4);
	for (i = 0; i < 8; i++) put32(p + i * 4, i + 1);
	p = lump(14, 64); put32(p + 56, 0); put32(p + 60, faces);
	return used;
}

int main(void)
{
	size_t n, pn = 0, i;
	unsigned char *rgb = (unsigned char *)malloc((size_t)TBS_W * TBS_H * 3), *png;
	int zeros = 0;
	assert(rgb);
	n = build("wall", 1);
	assert(tbs_is_q1(bsp, n));
	bsp[0] = 46; assert(!tbs_is_q1(bsp, n)); bsp[0] = 29;
	assert(tbs_render_q1(bsp, n, TBS_W, TBS_H, rgb));
	/* a floor facing the camera is drawn: some pixel is no longer the dark blue backdrop */
	{ int lit = 0; for (i = 0; i < (size_t)TBS_W * TBS_H; i++) lit += rgb[i * 3] > 60; assert(lit > 1000); }
	/* only the sky quad (named sky1) on the model: nothing is drawn, so no picture */
	n = build("sky1", 2);
	assert(tbs_render_q1(bsp, n, TBS_W, TBS_H, rgb)); /* the floor is still there */
	{ unsigned char *f = bsp + tbs_i32(bsp + 4 + 7 * 8); put32(f + 0 + 4, 4); put16(f + 8, 4); put16(f + 10, 1); /* floor face now "sky" too */ }
	assert(!tbs_render_q1(bsp, n, TBS_W, TBS_H, rgb));
	/* damaged input is refused, not read */
	n = build("wall", 1);
	assert(!tbs_render_q1(bsp, 100, 64, 36, rgb));
	assert(!tbs_render_q1(bsp, 4 + 15 * 8 + 10, 64, 36, rgb));
	put32(bsp + 8 + 7 * 8, 1 << 30); assert(!tbs_render_q1(bsp, n, TBS_W, TBS_H, rgb));
	/* the PNG: signature, header, and a stream that is the stored size (no deflater given) */
	memset(rgb, 90, (size_t)TBS_W * TBS_H * 3);
	png = tbs_png(rgb, TBS_W, TBS_H, NULL, &pn);
	assert(png && pn > (size_t)TBS_W * TBS_H * 3);
	assert(!memcmp(png, "\x89PNG\r\n\x1a\n", 8) && !memcmp(png + 12, "IHDR", 4));
	assert(png[18] == (TBS_W >> 8) && png[19] == (TBS_W & 255) && png[22] == (TBS_H >> 8) && png[23] == (TBS_H & 255));
	assert(!memcmp(png + pn - 12 + 4, "IEND", 4));
	for (i = 0; i < pn; i++) zeros += png[i] == 0;
	free(png); free(rgb);
	puts("BSP picture: PASS");
	return 0;
}
