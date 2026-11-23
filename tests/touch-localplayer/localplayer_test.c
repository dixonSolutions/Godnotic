/* Godnotic: the Local tab's choices (qcsrc/menu/xonotic/touch_uib_localplayer_logic.qc),
 * compiled as C with the few QuakeC builtins it uses stood in here. */
#include <math.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef const char *string;
#define float double
#define LP_TEST 1

static int lp_same(string a, string b) { return !strcmp(a, b); }
#define LP_Same lp_same

#define LP_NARGS(...) ((int)(sizeof((string[]){__VA_ARGS__}) / sizeof(string)))
static string lp_strcat(int n, ...)
{
	static char ring[16][4096];
	static int at;
	char *o = ring[at++ & 15];
	va_list ap;
	o[0] = 0;
	va_start(ap, n);
	for (int i = 0; i < n; ++i) strcat(o, va_arg(ap, string));
	va_end(ap);
	return o;
}
#define strcat(...) lp_strcat(LP_NARGS(__VA_ARGS__), __VA_ARGS__)

static int strstrofs(string h, string n, int from)
{
	const char *p = strstr(h + from, n);
	return p ? (int)(p - h) : -1;
}
static string substring(string s, int start, int len)
{
	static char ring[16][4096];
	static int at;
	char *o = ring[at++ & 15];
	int l = (int)strlen(s);
	if (start > l) start = l;
	if (len < 0) len = l - start + len + 1;
	if (len > l - start) len = l - start;
	if (len < 0) len = 0;
	memcpy(o, s + start, len);
	o[len] = 0;
	return o;
}
#define strlen(s) ((int)strlen(s))
static char *zoned[256];
static int nz;
static string strzone(string s) { char *d = strdup(s); zoned[nz++] = d; return d; }
static void strunzone(string s)
{
	for (int i = 0; i < nz; ++i)
		if (zoned[i] == s) { free(zoned[i]); zoned[i] = zoned[--nz]; return; }
	fprintf(stderr, "strunzone of a string that is not zoned\n");
	abort();
}
static string ftos(double d) { static char ring[16][32]; static int at; char *o = ring[at++ & 15]; snprintf(o, 32, "%g", d); return o; }
static double bound(double lo, double v, double hi) { return v < lo ? lo : v > hi ? hi : v; }

static char tok[64][1024];
static int ntok;
static int tokenizebyseparator(string s, string sep)
{
	ntok = 0;
	const char *p = s;
	for (;;)
	{
		const char *q = strstr(p, sep);
		int l = q ? (int)(q - p) : (int)strlen(p);
		memcpy(tok[ntok], p, l);
		tok[ntok][l] = 0;
		++ntok;
		if (!q) break;
		p = q + strlen(sep);
	}
	return ntok;
}
static string argv(int i)   /* a tempstring, as in the engine: it survives the next tokenize */
{
	static char ring[64][1024];
	static int at;
	char *o = ring[at++ & 63];
	snprintf(o, 1024, "%s", (i >= 0 && i < ntok) ? tok[i] : "");
	return o;
}

#include "../../engine/data/xonotic-data.pk3dir/qcsrc/menu/xonotic/touch_uib_localplayer_logic.qc"

static int fails;
#define CHECK(c) do { if (!(c)) { printf("FAIL line %d: %s\n", __LINE__, #c); ++fails; } } while (0)
#define SAME(a, b) do { string _a = (a), _b = (b); if (strcmp(_a, _b)) { printf("FAIL line %d: \"%s\" != \"%s\"\n", __LINE__, _a, _b); ++fails; } } while (0)

static const char *EREBUS = "models/player/erebus.iqm:0";
static const char *NYX = "models/player/nyx.iqm:2";
static const char *MINE = "models/player/mine.iqm:0";
static const char *LINE = "Hellfire\tignis\t0\t4\t0\t0\t0\t0\t-0.5\t-1\t1\t1\t-0.5\t-1\t-1\t2\t0.5\t-1";

int main(void)
{
	/* keys */
	SAME(LP_KeyModel(NYX), "models/player/nyx.iqm");
	SAME(LP_KeySkin(NYX), "2");
	SAME(LP_KeySkin("models/player/nyx.iqm"), "0");
	SAME(LP_Key("a.iqm", "1"), "a.iqm:1");
	SAME(LP_BotModelName(NYX), "nyx.iqm");
	SAME(LP_BotModelName("custom.iqm:0"), "custom.iqm");

	/* "same as online" is the default and applies nothing */
	SAME(LP_YouModel("online", EREBUS, NYX), "");
	CHECK(LP_YouColour("online", 33, 0.5, 0.5) == -1);
	SAME(LP_YouModel("", EREBUS, NYX), "");          /* an unset cvar is online too */
	CHECK(LP_YouColour("", 33, 0.5, 0.5) == -1);
	/* a separate local choice */
	SAME(LP_YouModel("model", EREBUS, NYX), EREBUS);
	SAME(LP_YouModel("random", EREBUS, NYX), NYX);
	SAME(LP_YouModel("random", EREBUS, ""), "");     /* none installed: nothing applied */
	SAME(LP_YouModel("model", MINE, NYX), MINE);     /* an uploaded model is just a key */

	/* colours of the player: random vs fixed vs online */
	CHECK(LP_YouColour("fixed", 0x4b, 0.9, 0.9) == 0x4b);
	CHECK(LP_YouColour("fixed", 999, 0, 0) == 255);
	CHECK(LP_YouColour("fixed", -5, 0, 0) == 0);
	CHECK(LP_YouColour("random", 0, 0, 0) == 0);
	CHECK(LP_YouColour("random", 0, 0.999999, 0.999999) == 14 * 16 + 14);
	CHECK(LP_YouColour("random", 0, 0.5, 0.2) == 7 * 16 + 3);
	for (int i = 0; i < 100; ++i)
	{
		double c = LP_YouColour("random", 0, i / 100.0, (99 - i) / 100.0);
		CHECK(c >= 0 && c <= 14 * 16 + 14 && ((int)c % 16) <= 14);
	}

	/* bots: random (default) leaves bots.txt alone */
	SAME(LP_BotModel("random", NYX, "a:0,b:0", 0.5), "");
	SAME(LP_BotModel("", NYX, "a:0,b:0", 0.5), "");
	SAME(LP_BotModel("one", NYX, "a:0,b:0", 0.5), NYX);
	SAME(LP_BotModel("set", NYX, "a:0,b:1,c:2", 0.0), "a:0");
	SAME(LP_BotModel("set", NYX, "a:0,b:1,c:2", 0.5), "b:1");
	SAME(LP_BotModel("set", NYX, "a:0,b:1,c:2", 0.999), "c:2");
	SAME(LP_BotModel("set", NYX, "a:0,b:1,c:2", 1.0), "c:2");
	SAME(LP_BotModel("set", NYX, "", 0.5), "");      /* an empty set changes nothing */
	CHECK(!LP_BotsChanged("random", "bot"));
	CHECK(!LP_BotsChanged("", ""));
	CHECK(LP_BotsChanged("one", "bot"));
	CHECK(LP_BotsChanged("set", "bot"));
	CHECK(LP_BotsChanged("random", "match"));
	CHECK(LP_BotsChanged("random", "fixed"));
	CHECK(LP_BotColour("bot", 33, 77) == -1);
	CHECK(LP_BotColour("match", 33, 77) == 77);
	CHECK(LP_BotColour("fixed", 33, 77) == 33);

	/* bots.txt lines */
	SAME(LP_BotLine(LINE, "random", NYX, "", 0, "bot", 0, 0), LINE);          /* defaults: byte for byte */
	SAME(LP_BotLine("// a comment", "one", NYX, "", 0, "fixed", 33, 0), "// a comment");
	SAME(LP_BotLine("", "one", NYX, "", 0, "fixed", 33, 0), "");
	SAME(LP_BotLine(LINE, "one", NYX, "", 0, "bot", 0, 0),
		"Hellfire\tnyx.iqm\t2\t4\t0\t0\t0\t0\t-0.5\t-1\t1\t1\t-0.5\t-1\t-1\t2\t0.5\t-1");
	SAME(LP_BotLine(LINE, "random", NYX, "", 0, "fixed", 0x4b, 0),
		"Hellfire\tignis\t0\t4\t11\t0\t0\t0\t-0.5\t-1\t1\t1\t-0.5\t-1\t-1\t2\t0.5\t-1");
	SAME(LP_BotLine(LINE, "set", "", "erebus.iqm:1,nyx.iqm:0", 0.9, "match", 0, 2 * 16 + 9),
		"Hellfire\tnyx.iqm\t0\t2\t9\t0\t0\t0\t-0.5\t-1\t1\t1\t-0.5\t-1\t-1\t2\t0.5\t-1");
	SAME(LP_BotLine("Short\tignis\t0", "one", NYX, "", 0, "fixed", 33, 0), "Short\tignis\t0");
	CHECK(nz == 0);                                                              /* every zone released */

	/* sets */
	SAME(LP_SetToggle("", "a:0"), "a:0");
	SAME(LP_SetToggle("a:0", "b:0"), "a:0,b:0");
	SAME(LP_SetToggle("a:0,b:0,c:0", "b:0"), "a:0,c:0");
	SAME(LP_SetToggle("a:0", "a:0"), "");
	SAME(LP_SetToggle("a:0", ""), "a:0");
	CHECK(LP_SetHas("a:0,b:1", "b:1"));
	CHECK(!LP_SetHas("a:0,b:1", "b:"));
	CHECK(!LP_SetHas("a:0,b:10", "b:1"));
	CHECK(LP_SetCount("") == 0 && LP_SetCount("a:0,b:1,c:2") == 3);
	{
		static char big[4096];
		char k[16];
		big[0] = 0;
		for (int i = 0; i < 32; ++i) { snprintf(k, sizeof k, "%sm%d:0", i ? "," : "", i); strncat(big, k, sizeof big - strlen(big) - 1); }
		CHECK(LP_SetCount(big) == 32);
		SAME(LP_SetToggle(big, "extra:0"), big);                                 /* a full set takes no more */
		CHECK(!LP_SetHas(LP_SetToggle(big, "m5:0"), "m5:0"));
	}

	/* when: apply at the start of a local match, put back at its end, also after a crash */
	CHECK(LP_Step(0, 1, 0) == 1);      /* a local match begins */
	CHECK(LP_Step(1, 1, 1) == 0);      /* while it runs */
	CHECK(LP_Step(1, 0, 1) == 2);      /* it ends: your values come back */
	CHECK(LP_Step(0, 0, 1) == 2);      /* the app died in a match: the next start puts them back */
	CHECK(LP_Step(0, 0, 0) == 0);      /* online and in the menu: nothing is ever touched */
	return fails ? 1 : (printf("localplayer: all ok\n"), 0);
}
