// Tests for touch_playermodel_logic.h: cc -I engine/darkplaces -o t playermodel_test.c && ./t
#include "touch_playermodel_logic.h"
#include <assert.h>

static int fails;
#define CHECK(c) do { if (!(c)) { printf("FAIL line %d: %s\n", __LINE__, #c); ++fails; } } while (0)

static void put32(unsigned char *p, unsigned v) { p[0] = v; p[1] = v >> 8; p[2] = v >> 16; p[3] = v >> 24; }

int main(void)
{
	// formats
	unsigned char iqm[400] = "INTERQUAKEMODEL";
	put32(iqm + 16, 2);
	put32(iqm + 16 + 4 * 19, 120);  // frames
	CHECK(!strcmp(TPM_FormatOf(iqm, sizeof(iqm)), "iqm"));
	CHECK(TPM_Frames(iqm, sizeof(iqm)) == 120);
	CHECK(!strcmp(TPM_FormatOf((const unsigned char *)"IDP3....", 8), "md3"));
	CHECK(!strcmp(TPM_FormatOf((const unsigned char *)"OggS....", 8), ""));
	unsigned char dpm[100] = "DARKPLACESMODEL";
	dpm[71] = 9;
	CHECK(TPM_Frames(dpm, sizeof(dpm)) == 9);
	CHECK(TPM_Frames(iqm, 20) == 0);

	// iqm with its anim names: 2 anims, "idle" and "run"
	unsigned char big[1024];
	memset(big, 0, sizeof(big));
	memcpy(big, "INTERQUAKEMODEL", 16);
	put32(big + 16, 2);
	const char text[] = "\0idle\0run";
	memcpy(big + 600, text, sizeof(text));
	put32(big + 16 + 4 * 3, sizeof(text)); put32(big + 16 + 4 * 4, 600);
	put32(big + 16 + 4 * 17, 2); put32(big + 16 + 4 * 18, 700);
	put32(big + 700, 1); put32(big + 720, 6);
	CHECK(TPM_IqmAnimsNamed(big, sizeof(big)) == 2);

	// framegroups
	const char *fg = "0 10 20 0\n10 5 15 1\n\n// c\n20 3 10 0\r\n";
	CHECK(TPM_FramegroupLines(fg, strlen(fg)) == 3);

	// decisions
	tpm_facts_t f;
	memset(&f, 0, sizeof(f));
	char why[200];
	CHECK(!TPM_Decide(&f, why, sizeof(why)) && strstr(why, "No model"));
	f.has_model = 1; f.model_size = 5000; f.header_ok = 1; f.frames = 50; f.has_txt = 1; f.has_skin = 1;
	strcpy(f.fmt, "iqm");
	CHECK(!TPM_Decide(&f, why, sizeof(why)) && strstr(why, "No animations"));
	f.framegroup_lines = 10;
	CHECK(!TPM_Decide(&f, why, sizeof(why)) && strstr(why, "Only 10 of the 23"));
	f.framegroup_lines = 28;
	CHECK(TPM_Decide(&f, why, sizeof(why)));
	f.framegroup_lines = 0; f.iqm_anims_named = 23;
	CHECK(TPM_Decide(&f, why, sizeof(why)));
	f.has_skin = 0;
	CHECK(!TPM_Decide(&f, why, sizeof(why)) && strstr(why, "No skin"));
	f.has_skin = 1; f.replaces_stock = 1;
	CHECK(!TPM_Decide(&f, why, sizeof(why)) && strstr(why, "already in the game"));
	f.replaces_stock = 0; f.model_size = 100;
	CHECK(!TPM_Decide(&f, why, sizeof(why)) && strstr(why, "KB"));
	f.model_size = 5000; f.pack_size = 40L << 20;
	CHECK(!TPM_Decide(&f, why, sizeof(why)) && strstr(why, "over 32 MB"));

	// names
	char stem[48];
	TPM_Stem("/sdcard/Down loads/My Model!.IQM", stem, sizeof(stem));
	CHECK(!strcmp(stem, "my_model_"));
	TPM_Stem("", stem, sizeof(stem));
	CHECK(!strcmp(stem, "model"));

	// zip round trip
	const char *names[2] = { "models/player/zed.iqm", "models/player/zed_0.txt" };
	const unsigned char *datas[2] = { (const unsigned char *)"abcdef", (const unsigned char *)"name Zed\n" };
	size_t sizes[2] = { 6, 9 };
	static unsigned char z[4096];
	size_t zn = TPM_StoredZip(z, 2, names, datas, sizes);
	static tpm_zent_t ents[8];
	CHECK(TPM_ZipList(z, zn, ents, 8) == 2);
	CHECK(!strcmp(ents[1].name, "models/player/zed_0.txt") && ents[1].size == 9 && ents[0].method == 0);
	CHECK(TPM_ZipList((const unsigned char *)"not a zip at all, not at all", 28, ents, 8) == -1);
	CHECK(tpm_crc32((const unsigned char *)"123456789", 9) == 0xCBF43926u);

	puts(fails ? "playermodel tests FAILED" : "playermodel tests passed");
	return fails != 0;
}
