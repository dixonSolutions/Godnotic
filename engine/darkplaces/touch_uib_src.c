/*
Godnotic UI bridge: data sources, read by window (offset, limit, sort, filter), so a
2,000-server list never crosses the bridge at once.

  servers, servers.lan, servers.fav   the engine's server list (Internet, LAN, VPN)
  maps, maps:<mode>                   installed maps from the maps/ mapinfo files
  anything else                       rows the menu VM sets (modes, recent, mods ...)

Rows are kept parsed (one cell array per row) so sorting and filtering re-window without
rebuilding. Servers are re-read at most 4 times a second, and only while a list in the
tree shows them; a change bumps the source's rev and every renderer gets a `source` notice.
*/
#include "quakedef.h"
#include "netconn.h"
#include "touch_lan_logic.h"
#include "touch_srvlist.h"
#include "touch_datafetch.h"
#include "touch_uib_int.h"

typedef struct
{
	const char *k;   // points into the row's key pool
	char *s;         // string value (display), NULL for numbers/bools
	double n;
	char kind;       // 's', 'n', 'b'
} cell_t;

struct uibrow_s
{
	char *key, *image;
	cell_t *cells;
	int ncells;
};

#define MAX_SOURCES 48
#define MAX_PARAMS 12

typedef struct
{
	char name[160];
	int kind;               // 0 static, 1 servers, 2 maps
	uibrow_t *rows;
	int nrows, maxrows;
	int64_t rev;
	unsigned sig;
	double next_poll;
	int used;               // a list node shows it
	struct { char k[32]; char v[128]; } params[MAX_PARAMS];
	int nparams;
	int64_t notified_rev;
	int lasttotal;
} source_t;

static source_t sources[MAX_SOURCES];
static int nsources;
static source_t *building;
static int params_changed; // a filter changed: sources are re-read this frame, not at the next poll

static void freerows(source_t *s)
{
	for (int i = 0; i < s->nrows; i++)
	{
		for (int c = 0; c < s->rows[i].ncells; c++)
		{
			free((char *)s->rows[i].cells[c].k);
			free(s->rows[i].cells[c].s);
		}
		free(s->rows[i].cells);
		free(s->rows[i].key);
		free(s->rows[i].image);
	}
	s->nrows = 0;
}

static source_t *getsource(const char *name, int create)
{
	for (int i = 0; i < nsources; i++)
		if (!strcmp(sources[i].name, name))
			return &sources[i];
	if (!create || nsources >= MAX_SOURCES || strlen(name) >= sizeof(sources[0].name))
		return NULL;
	source_t *s = &sources[nsources++];
	memset(s, 0, sizeof(*s));
	dp_strlcpy(s->name, name, sizeof(s->name));
	s->rev = 1;
	if (!strncmp(name, "servers", 7))
		s->kind = 1;
	else if (!strcmp(name, "maps") || !strncmp(name, "maps:", 5))
		s->kind = 2;
	s->notified_rev = s->rev;
	return s;
}

static const char *param(source_t *s, const char *k, const char *def)
{
	for (int i = 0; i < s->nparams; i++)
		if (!strcmp(s->params[i].k, k))
			return s->params[i].v;
	// servers.* share the Servers screen's filters
	if (s->kind == 1 && strcmp(s->name, "servers") && strcmp(s->name, "servers.all"))
	{
		source_t *base = getsource("servers", 0);
		if (base)
			return param(base, k, def);
	}
	return def;
}

void UIBSrc_Param(const char *source, const char *key, const char *value)
{
	source_t *s = getsource(source, 1);
	if (!s)
		return;
	for (int i = 0; i < s->nparams; i++)
		if (!strcmp(s->params[i].k, key))
		{
			if (strcmp(s->params[i].v, value))
			{
				dp_strlcpy(s->params[i].v, value, sizeof(s->params[i].v));
				s->rev++;
				s->sig = 0;
				s->next_poll = 0;
				params_changed = 1;
			}
			return;
		}
	if (s->nparams >= MAX_PARAMS)
		return;
	dp_strlcpy(s->params[s->nparams].k, key, sizeof(s->params[0].k));
	dp_strlcpy(s->params[s->nparams].v, value, sizeof(s->params[0].v));
	s->nparams++;
	s->rev++;
	s->sig = 0;
	s->next_poll = 0;
}

void UIBSrc_HoldNode(const char *nodeid, int touching)
{
	uibnode_t *n = UIBS_Find(nodeid);
	size_t l;
	const char *sj = n ? UIBS_PropJSON(n, "source", &l) : NULL;
	char name[160];
	if (!sj || l < 3 || l > sizeof(sources[0].name))
		return;
	dpsnprintf(name, sizeof(name), "%.*s", (int)(l - 2), sj + 1);
	if (!strncmp(name, "servers", 7))
		UIBSrc_Param(name, "hold", touching ? "1" : "0");
}

// ---------------------------------------------------------------- row building

static uibrow_t *addrow(source_t *s, const char *key, const char *image)
{
	if (s->nrows >= s->maxrows)
	{
		s->maxrows = s->maxrows ? s->maxrows * 2 : 64;
		s->rows = (uibrow_t *)realloc(s->rows, sizeof(uibrow_t) * s->maxrows);
	}
	uibrow_t *r = &s->rows[s->nrows++];
	memset(r, 0, sizeof(*r));
	r->key = strdup(key);
	r->image = image && *image ? strdup(image) : NULL;
	return r;
}

static cell_t *addcell(uibrow_t *r, const char *k)
{
	r->cells = (cell_t *)realloc(r->cells, sizeof(cell_t) * (r->ncells + 1));
	cell_t *c = &r->cells[r->ncells++];
	memset(c, 0, sizeof(*c));
	c->k = strdup(k);
	return c;
}

static void cell_s(uibrow_t *r, const char *k, const char *v, size_t len)
{
	cell_t *c = addcell(r, k);
	c->kind = 's';
	c->s = (char *)malloc(len + 1);
	UIB_CleanText(v, len, c->s, len + 1);
}

static void cell_n(uibrow_t *r, const char *k, double v)
{
	cell_t *c = addcell(r, k);
	c->kind = 'n';
	c->n = v;
}

static void cell_b(uibrow_t *r, const char *k, int v)
{
	cell_t *c = addcell(r, k);
	c->kind = 'b';
	c->n = v != 0;
}

static const cell_t *getcell(const uibrow_t *r, const char *k)
{
	for (int i = 0; i < r->ncells; i++)
		if (!strcmp(r->cells[i].k, k))
			return &r->cells[i];
	return NULL;
}

// ---------------------------------------------------------------- servers

static const char *server_net(const char *cname)
{
	unsigned a, b, c, d;
	if (sscanf(cname, "%u.%u.%u.%u", &a, &b, &c, &d) == 4)
	{
		if (a == 100 && b >= 64 && b <= 127)
			return "vpn";  // CGNAT range: Tailscale, ZeroTier ...
		if (a == 10 || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168) || a == 127 || (a == 169 && b == 254))
			return "lan";
		return "internet";
	}
	if (!strncasecmp(cname, "[fe80", 5) || !strncasecmp(cname, "fe80", 4))
		return "lan";
	if (!strncasecmp(cname, "[fd", 3) || !strncasecmp(cname, "fd", 2))
		return "vpn";
	if (!strncmp(cname, "local", 5))
		return "lan";
	return "internet";
}

#ifdef CONFIG_MENU
static unsigned server_sig(void)
{
	unsigned h = 2166136261u ^ serverlist_cachecount;
	h = (h ^ TouchSrvList_Serial()) * 16777619u;  // rows added, answered, gone stale or removed
	h = (h ^ (unsigned)TouchSrvList_SavedRows()) * 16777619u;  // the saved rows shown before the first refresh
	for (unsigned i = 0; i < serverlist_cachecount; i++)
	{
		const serverlist_info_t *in = &serverlist_cache[i].info;
		h = (h ^ (unsigned)in->ping) * 16777619u;
		h = (h ^ (unsigned)in->numplayers) * 16777619u;
		h = (h ^ (unsigned)in->isfavorite) * 16777619u;
		h = (h ^ (unsigned)serverlist_cache[i].responded) * 16777619u;
		for (const char *p = in->map; *p; p++)
			h = (h ^ (unsigned char)*p) * 16777619u;
	}
	return h ? h : 1;
}

// what one server row shows, from the live list or from the saved one
typedef struct
{
	const char *cname, *name, *map, *qcstatus;
	int name_len, map_len, cname_len;
	int numplayers, numbots, numhumans, maxplayers, freeslots, ping, isfavorite, stale;
} srvrow_t;

typedef struct
{
	int lanonly, favonly, showempty, showfull, showlaggy, laggyms;
} srvfilter_t;

static void server_row(source_t *s, const srvfilter_t *f, const srvrow_t *in)
{
	const char *net = server_net(in->cname);
	if (f->lanonly && !strcmp(net, "internet"))
		return;
	if (f->favonly && !in->isfavorite)
		return;
	if (!f->showempty && in->numhumans <= 0)
		return;
	if (!f->showfull && in->freeslots <= 0 && in->maxplayers > 0)
		return;
	if (!f->showlaggy && in->ping >= f->laggyms)
		return;
	// no row image: the server list is text, dense (no map previews)
	uibrow_t *r = addrow(s, in->cname, NULL);
	cell_s(r, "name", in->name, in->name_len);
	cell_s(r, "map", in->map, in->map_len);
	char mode[32] = "";
	const char *colon = strchr(in->qcstatus, ':');
	if (colon && colon - in->qcstatus < (int)sizeof(mode))
		memcpy(mode, in->qcstatus, colon - in->qcstatus), mode[colon - in->qcstatus] = 0;
	cell_s(r, "mode", mode, strlen(mode));
	cell_n(r, "players", in->numplayers);
	cell_n(r, "max", in->maxplayers);
	cell_n(r, "bots", in->numbots);
	cell_n(r, "ping", in->ping);
	cell_b(r, "lock", strstr(in->qcstatus, TOUCHLAN_QCSTATUS_LOCK) != NULL);
	cell_b(r, "fav", in->isfavorite);
	cell_s(r, "address", in->cname, in->cname_len);
	cell_s(r, "net", net, strlen(net));
	const char *ver = colon ? colon + 1 : "";
	const char *ve = strchr(ver, ':');
	size_t vl = ve ? (size_t)(ve - ver) : strlen(ver);
	cell_s(r, "version", ver, vl < 32 ? vl : 31);
	// from the saved list, not answered yet this refresh (touch_srvlist.c)
	cell_b(r, "stale", in->stale);
}

static void build_servers(source_t *s)
{
	freerows(s);
	int all = !strcmp(s->name, "servers.all");  // lookups by address: no filters
	srvfilter_t f;
	f.lanonly = !all && (!strcmp(s->name, "servers.lan") || atoi(param(s, "lan", "0")));
	f.favonly = !strcmp(s->name, "servers.fav");
	f.showempty = all || atoi(param(s, "empty", "1"));
	f.showfull = all || atoi(param(s, "full", "1"));
	f.showlaggy = all || atoi(param(s, "laggy", "1"));
	f.laggyms = atoi(param(s, "laggyms", "150"));
	int saved = TouchSrvList_SavedRows();
	if (saved)
	{
		// No refresh yet this session: the saved rows, as the list's first frame
		// will show them (a screen built ahead has them before it is opened)
		for (int i = 0; i < saved; i++)
		{
			touchsl_saved_t saved_row, *rec = &saved_row;
			if (!TouchSrvList_SavedRow(i, &saved_row))
				continue;
			srvrow_t in;
			in.cname = rec->cname; in.cname_len = (int)strlen(rec->cname);
			in.name = rec->name; in.name_len = (int)strlen(rec->name);
			in.map = rec->map; in.map_len = (int)strlen(rec->map);
			in.qcstatus = rec->qcstatus;
			in.numplayers = rec->numplayers;
			in.numbots = rec->numbots;
			in.numhumans = rec->numplayers - max(0, rec->numbots);
			in.maxplayers = rec->maxplayers;
			in.freeslots = rec->maxplayers - rec->numplayers;
			in.ping = rec->ping;
			in.isfavorite = 0;
			in.stale = 1;
			server_row(s, &f, &in);
		}
		return;
	}
	for (unsigned i = 0; i < serverlist_cachecount; i++)
	{
		const serverlist_entry_t *e = &serverlist_cache[i];
		const serverlist_info_t *si = &e->info;
		if (!e->responded || !si->cname[0])
			continue;
		srvrow_t in;
		in.cname = si->cname; in.cname_len = si->cname_len;
		in.name = si->name; in.name_len = si->name_len;
		in.map = si->map; in.map_len = si->map_len;
		in.qcstatus = si->qcstatus;
		in.numplayers = si->numplayers;
		in.numbots = si->numbots;
		in.numhumans = si->numhumans;
		in.maxplayers = si->maxplayers;
		in.freeslots = si->freeslots;
		in.ping = si->ping;
		in.isfavorite = si->isfavorite;
		in.stale = TouchSrvList_IsStale(i);
		server_row(s, &f, &in);
	}
}

// ---------------------------------------------------------------- maps

#else
// Dedicated server (make sv-release): there is no server browser cache.
static unsigned server_sig(void) { return 1; }
static void build_servers(source_t *s) { freerows(s); }
#endif

static void build_maps(source_t *s)
{
	freerows(s);
	const char *mode = !strncmp(s->name, "maps:", 5) ? s->name + 5 : NULL;
	fssearch_t *search = FS_Search("maps/*.mapinfo", true, true, NULL);
	if (!search)
		return;
	for (int i = 0; i < search->numfilenames; i++)
	{
		const char *fn = search->filenames[i];
		char name[64];
		const char *base = fn + 5;
		size_t bl = strlen(base) - 8;
		if (bl >= sizeof(name) || bl == 0)
			continue;
		memcpy(name, base, bl);
		name[bl] = 0;
		if (name[0] == '_')
			continue;  // _hudsetup and other tools
		fs_offset_t len;
		char *text = (char *)FS_LoadFile(fn, tempmempool, true, &len);
		if (!text)
			continue;
		char title[128] = "", author[256] = "", desc[512] = "", modes[256] = "";
		int hasmode = !mode;
		for (char *line = text; line && *line;)
		{
			char *eol = strchr(line, '\n');
			if (eol)
				*eol = 0;
			if (!strncmp(line, "title ", 6)) dp_strlcpy(title, line + 6, sizeof(title));
			else if (!strncmp(line, "author ", 7)) dp_strlcpy(author, line + 7, sizeof(author));
			else if (!strncmp(line, "description ", 12)) dp_strlcpy(desc, line + 12, sizeof(desc));
			else if (!strncmp(line, "gametype ", 9) || !strncmp(line, "type ", 5))
			{
				char g[32];
				if (sscanf(line + (line[0] == 'g' ? 9 : 5), "%31s", g) == 1)
				{
					if (!strcmp(g, "rc")) dp_strlcpy(g, "race", sizeof(g));
					if (strlen(modes) + strlen(g) + 2 < sizeof(modes))
					{
						if (modes[0]) dp_strlcat(modes, " ", sizeof(modes));
						dp_strlcat(modes, g, sizeof(modes));
					}
					if (mode && !strcmp(g, mode))
						hasmode = 1;
				}
			}
			line = eol ? eol + 1 : NULL;
		}
		Mem_Free(text);
		if (!hasmode)
			continue;
		char img[96];
		dpsnprintf(img, sizeof(img), "pk3:maps/%s", name);
		uibrow_t *r = addrow(s, name, img);
		cell_s(r, "name", name, strlen(name));
		if (!title[0])
			dp_strlcpy(title, name, sizeof(title));
		cell_s(r, "title", title, strlen(title));
		cell_s(r, "author", author, strlen(author));
		cell_s(r, "description", desc, strlen(desc));
		cell_s(r, "modes", modes, strlen(modes));
		{
			// "ready", or "downloading" with progress 0..1 (-1: queued) while the
			// split set is still coming in (docs/DATA_FETCH.md)
			float progress;
			int st = TouchData_MapState(name, &progress);
			const char *state = st == 0 ? "ready" : st == 1 ? "downloading" : "missing";
			cell_s(r, "state", state, strlen(state));
			cell_n(r, "progress", progress);
		}
	}
	FS_FreeSearch(search);
}

// ---------------------------------------------------------------- static rows (menu VM)

void UIBSrc_StaticBegin(const char *source)
{
	building = getsource(source, 1);
	if (building && building->kind == 0)
		freerows(building);
	else
		building = NULL;
}

void UIBSrc_StaticRow(const char *key, const char *cellsjson, const char *image)
{
	if (!building || building->nrows >= 65536)
		return;
	uibjdoc_t *d = UIBJ_Parse(cellsjson, strlen(cellsjson), NULL, 0);
	uibj_t *o = UIBJ_Root(d);
	uibrow_t *r = addrow(building, key, image);
	if (o && o->type == UIBJ_OBJ)
		for (uibj_t *m = o->child; m; m = m->next)
		{
			if (m->type == UIBJ_STR) cell_s(r, m->key, m->str, m->len);
			else if (m->type == UIBJ_NUM) cell_n(r, m->key, m->num);
			else if (m->type == UIBJ_TRUE || m->type == UIBJ_FALSE) cell_b(r, m->key, m->type == UIBJ_TRUE);
		}
	UIBJ_Free(d);
}

void UIBSrc_StaticEnd(void)
{
	if (!building)
		return;
	// a cheap signature so an unchanged set does not bump the rev
	unsigned h = 2166136261u ^ (unsigned)building->nrows;
	for (int i = 0; i < building->nrows; i++)
	{
		for (const char *p = building->rows[i].key; *p; p++)
			h = (h ^ (unsigned char)*p) * 16777619u;
		for (int c = 0; c < building->rows[i].ncells; c++)
		{
			const cell_t *cel = &building->rows[i].cells[c];
			if (cel->s) for (const char *p = cel->s; *p; p++) h = (h ^ (unsigned char)*p) * 16777619u;
			else h = (h ^ (unsigned)(cel->n * 1000)) * 16777619u;
		}
	}
	if (h != building->sig)
	{
		building->sig = h;
		building->rev++;
	}
	building = NULL;
}

// ---------------------------------------------------------------- refresh, notify

static void refresh(source_t *s, int force)
{
	double now = UIB_Time();
	if (s->kind == 0)
		return;
	if (!force && now < s->next_poll)
		return;
	s->next_poll = now + (s->kind == 2 ? 0.5 : 0.25); // maps: packs land while the game runs (touch_datafetch.c)
	if (s->kind == 1)
	{
		// A finger on the list (the QuakeC renderer: the engine sees it) or a
		// renderer that set the source's "hold" param: the rows stay exactly
		// where they are, and the source catches up on release.
		if (s->kind == 1 && s->nrows && (TouchSrvList_Hold() || atoi(param(s, "hold", "0"))))
			return;
		unsigned sig = server_sig();
		if (sig == s->sig)
			return;
		s->sig = sig;
		build_servers(s);
		s->rev++;
	}
	else if (s->kind == 2)
	{
		unsigned sig = TouchData_Generation() | 0x80000000u;
		if (s->sig == sig && !force)
			return;
		build_maps(s);
		s->sig = sig;
		s->rev++;
	}
}

int64_t UIBSrc_Rev(const char *source)
{
	source_t *s = getsource(source, 0);
	return s ? s->rev : 0;
}

// a list node's source name (its "source" prop, unquoted); 0 if it has none
static int list_source(uibnode_t *n, char *name, size_t size)
{
	size_t l;
	const char *sj = UIBS_PropJSON(n, "source", &l);
	if (!sj || l < 3 || l > sizeof(sources[0].name) || l - 2 >= size)
		return 0;
	dpsnprintf(name, size, "%.*s", (int)(l - 2), sj + 1);
	return 1;
}

// A list that just appeared (docs/UI_BRIDGE.md, "Opening a screen fast"): its
// count now, from its source read now, not at the next quarter-second poll, so
// the diff that creates it already says how many rows it has. Returns the count.
int UIBSrc_CountFresh(uibnode_t *n)
{
	char name[160], cnt[24];
	if (!n || strcmp(n->type, "list") || !list_source(n, name, sizeof(name)))
		return -1;
	source_t *s = getsource(name, 1);
	if (!s)
		return -1;
	s->used = 1;
	refresh(s, 1);
	s->notified_rev = s->rev; // the first rows go out with this rev (UIB_PushFirstRows)
	int c = dpsnprintf(cnt, sizeof(cnt), "%d", s->nrows);
	UIBS_SetProp(n, "count", cnt, c);
	return s->nrows;
}

// the list nodes' count follows their source; renderers get a `source` notice
void UIBSrc_UpdateCounts(void)
{
	for (int i = 0; i < nsources; i++)
		sources[i].used = 0;
	for (uibnode_t *n = UIBS_FirstNode(); n; n = n->lnext)
	{
		if (strcmp(n->type, "list"))
			continue;
		char name[160];
		if (!list_source(n, name, sizeof(name)))
			continue;
		source_t *s = getsource(name, 1);
		if (!s)
			continue;
		s->used = 1;
		refresh(s, 0);
		char cnt[24];
		int c = dpsnprintf(cnt, sizeof(cnt), "%d", s->nrows);
		UIBS_SetProp(n, "count", cnt, c);
	}
	for (int i = 0; i < nsources; i++)
	{
		source_t *s = &sources[i];
		if (!s->used || s->rev == s->notified_rev)
			continue;
		s->notified_rev = s->rev;
		char buf[256];
		int n = dpsnprintf(buf, sizeof(buf), "{\"uib\":1,\"t\":\"source\",\"id\":0,\"source\":\"%s\",\"rev\":%lld,\"total\":%d}",
			s->name, (long long)s->rev, s->nrows);
		UIB_Broadcast(buf, n);
		UIB_Trace("source %s rev %lld: %d rows", s->name, (long long)s->rev, s->nrows);
	}
}

void UIBSrc_Frame(void)
{
	static double next;
	double now = UIB_Time();
	// a filter the player just changed applies in this frame (its rows follow the
	// diff that shows the new checkbox), not up to a quarter second later
	if (now < next && !params_changed)
		return;
	params_changed = 0;
	next = now + 0.25;
	UIBSrc_UpdateCounts();
}

// ---------------------------------------------------------------- windows

static const char *sortkey;
static int sortdesc;
static source_t *sortsrc;

static int cmp_rows(const void *a, const void *b)
{
	const uibrow_t *ra = &sortsrc->rows[*(const int *)a], *rb = &sortsrc->rows[*(const int *)b];
	const cell_t *ca = getcell(ra, sortkey), *cb = getcell(rb, sortkey);
	int r;
	if (!ca || !cb)
		r = (ca != NULL) - (cb != NULL);
	else if (ca->s && cb->s)
		r = strcasecmp(ca->s, cb->s);
	else if (ca->s || cb->s)
		r = ca->s ? 1 : -1;
	else
	{
		double x = ca->n, y = cb->n;
		// a ping of 0 means unknown: always last
		if (!strcmp(sortkey, "ping") && (x <= 0 || y <= 0))
			return (x <= 0) - (y <= 0);
		r = x < y ? -1 : x > y;
	}
	if (sortdesc)
		r = -r;
	// equal keys (a dozen servers with 0 players, the same ping) in one fixed order:
	// by address, not by where the server list happens to hold them, which a
	// refresh reshuffles (rows swapped places on every update)
	if (!r && ra->key && rb->key)
		r = strcmp(ra->key, rb->key);
	if (!r)
		r = *(const int *)a - *(const int *)b;
	return r;
}

static int has_ci(const char *hay, const char *needle)
{
	size_t n = strlen(needle);
	for (; *hay; hay++)
		if (!strncasecmp(hay, needle, n))
			return 1;
	return 0;
}

static int row_matches(const uibrow_t *r, const char *filter)
{
	if (!filter || !*filter)
		return 1;
	for (int i = 0; i < r->ncells; i++)
		if (r->cells[i].s && has_ci(r->cells[i].s, filter))
			return 1;
	return 0;
}

// The visible order: indices into s->rows, filtered and sorted.
static int *order(source_t *s, const char *sort, int desc, const char *filter, int *count)
{
	int *idx = (int *)malloc(sizeof(int) * (s->nrows + 1));
	int n = 0;
	for (int i = 0; i < s->nrows; i++)
		if (row_matches(&s->rows[i], filter))
			idx[n++] = i;
	if (s->kind == 1 && (!sort || !*sort))
		sort = "ping";
	if (sort && *sort)
	{
		sortkey = sort;
		sortdesc = desc;
		sortsrc = s;
		qsort(idx, n, sizeof(int), cmp_rows);
	}
	*count = n;
	return idx;
}

static void row_json(uibbuf_t *b, const uibrow_t *r)
{
	UIBB_Puts(b, "{\"key\":");
	UIBB_Str(b, r->key, strlen(r->key), 255, 0);
	if (r->image)
	{
		UIBB_Puts(b, ",\"image\":");
		UIBB_Str(b, r->image, strlen(r->image), 250, 0);
	}
	UIBB_Puts(b, ",\"cells\":{");
	for (int c = 0; c < r->ncells; c++)
	{
		const cell_t *cel = &r->cells[c];
		if (c)
			UIBB_Putc(b, ',');
		UIBB_Str(b, cel->k, strlen(cel->k), 64, 0);
		UIBB_Putc(b, ':');
		if (cel->kind == 's') UIBB_Str(b, cel->s, strlen(cel->s), UIB_MAX_STRING, 0);
		else if (cel->kind == 'b') UIBB_Puts(b, cel->n ? "true" : "false");
		else UIBB_Num(b, cel->n);
	}
	UIBB_Puts(b, "}}");
}

void UIBSrc_Window(uibbuf_t *b, int msgid, int64_t re, const char *source, int offset, int limit,
                   const char *sort, int desc, const char *filter, int hasfilter)
{
	source_t *s = getsource(source, 0);
	if (!s && (!strncmp(source, "servers", 7) || !strncmp(source, "maps", 4)))
		s = getsource(source, 1);
	if (!s)
	{
		UIBB_Printf(b, "{\"uib\":1,\"t\":\"error\",\"id\":%d,\"re\":%lld,\"code\":\"not_found\",\"msg\":\"no such source\"}", msgid, (long long)re);
		return;
	}
	refresh(s, 0);
	if (!hasfilter)
		filter = param(s, "search", "");
	if (offset < 0) offset = 0;
	if (limit < 1) limit = 1;
	if (limit > UIB_MAX_WINDOW) limit = UIB_MAX_WINDOW;
	int count;
	int *idx = order(s, sort, desc, filter, &count);
	UIBB_Printf(b, "{\"uib\":1,\"t\":\"rows\",\"id\":%d,\"re\":%lld,\"source\":", msgid, (long long)re);
	UIBB_Str(b, s->name, strlen(s->name), 0, 0);
	UIBB_Printf(b, ",\"rev\":%lld,\"total\":%d,\"offset\":%d", (long long)s->rev, count, offset);
	if (sort && *sort)
	{
		UIBB_Puts(b, ",\"sort\":");
		UIBB_Str(b, sort, strlen(sort), 64, 0);
		UIBB_Printf(b, ",\"desc\":%s", desc ? "true" : "false");
	}
	if (hasfilter)
	{
		UIBB_Puts(b, ",\"filter\":");
		UIBB_Str(b, filter, strlen(filter), 256, 0);
	}
	UIBB_Puts(b, ",\"rows\":[");
	for (int i = offset; i < count && i < offset + limit; i++)
	{
		if (i > offset)
			UIBB_Putc(b, ',');
		row_json(b, &s->rows[idx[i]]);
	}
	UIBB_Puts(b, "]}");
	free(idx);
}

// ---------------------------------------------------------------- the QuakeC renderer's window

static struct
{
	source_t *s;
	int *idx;
	int count, offset, n;
} lw;

int UIBSrc_LocalWindow(const char *source, int offset, int limit, const char *sort, int desc, const char *filter)
{
	source_t *s = getsource(source, 1);
	free(lw.idx);
	memset(&lw, 0, sizeof(lw));
	if (!s)
		return 0;
	refresh(s, 0);
	if (!filter || !*filter)
		filter = param(s, "search", "");
	lw.s = s;
	lw.idx = order(s, sort, desc, filter, &lw.count);
	if (offset < 0) offset = 0;
	lw.offset = offset;
	lw.n = lw.count - offset;
	if (lw.n > limit) lw.n = limit;
	if (lw.n < 0) lw.n = 0;
	return lw.n;
}

int UIBSrc_LocalTotal(void) { return lw.count; }

static const uibrow_t *lrow(int i)
{
	if (!lw.s || i < 0 || i >= lw.n)
		return NULL;
	return &lw.s->rows[lw.idx[lw.offset + i]];
}

const char *UIBSrc_LocalKey(int i) { const uibrow_t *r = lrow(i); return r ? r->key : ""; }
const char *UIBSrc_LocalImage(int i) { const uibrow_t *r = lrow(i); return r && r->image ? r->image : ""; }

const char *UIBSrc_LocalCell(int i, const char *key)
{
	static char num[64];
	const uibrow_t *r = lrow(i);
	const cell_t *c = r ? getcell(r, key) : NULL;
	if (!c)
		return "";
	if (c->s)
		return c->s;
	if (c->n == (double)(long long)c->n)
		dpsnprintf(num, sizeof(num), "%lld", (long long)c->n);
	else
		dpsnprintf(num, sizeof(num), "%g", c->n);
	return num;
}

void UIBSrc_Init(void) {}

void UIBSrc_Reset(void)
{
	for (int i = 0; i < nsources; i++)
		freerows(&sources[i]), free(sources[i].rows);
	nsources = 0;
	free(lw.idx);
	memset(&lw, 0, sizeof(lw));
}
