#!/usr/bin/env python3
"""Compile the production ESP gates and print what each helper decides.

The gate is extracted from engine/darkplaces/touch_tactics.c and the combined
"offline AND ESP on" decision from touch_lua_clapi.c, so this runs the shipped
code without an engine build or a display. Run through
scripts/test-touch-esp.sh.
"""
import pathlib, subprocess, tempfile

root = pathlib.Path(__file__).resolve().parents[2]
tactics = (root / 'engine/darkplaces/touch_tactics.c').read_text()
weapons = (root / 'engine/darkplaces/touch_weapons.h').read_text()
clapi = (root / 'engine/darkplaces/touch_lua_clapi.c').read_text()


def function(source, signature):
    start = source.index(signature)
    brace = source.index('{', start)
    depth = 1
    end = brace + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]


source = '''#include <stdio.h>
#include <string.h>
#include <strings.h>
#include <stdarg.h>
#include <stdlib.h>
typedef int qbool;
#define TW_GATE_OPEN 0
#define TW_GATE_NO_SERVER 1
struct {const char *string;} cl_touch_esp_enabled;
void Con_Printf(const char *s, ...) {(void)s;}
void dp_strlcpy(char *d, const char *s, size_t n) {snprintf(d,n,"%s",s);}
static int tlua_test_offline = 1;
qbool TLua_Offline(const char **why) {(void)why; return tlua_test_offline;}
'''
# Include any local gate predicate introduced alongside the gate.
if 'static qbool esp_disabled(' in tactics:
    source += function(tactics, 'static qbool esp_disabled(') + '\n'
source += function(tactics, 'int TouchTactics_EspGate(') + '\n'
if 'qbool TouchTactics_EspOn(' in tactics:
    source += function(tactics, 'qbool TouchTactics_EspOn(') + '\n'
else:
    # Before the fix: the Lua path used the offline environment only.
    source += 'static qbool TouchTactics_EspOn(void) { return TouchTactics_EspGate(TW_GATE_OPEN) == TW_GATE_OPEN; }\n'
if 'static qbool tlua_entities_sees_all(' in clapi:
    source += function(clapi, 'static qbool tlua_entities_sees_all(') + '\n'
else:
    # Before the fix: xt.entities consulted the offline environment only.
    source += 'static qbool tlua_entities_sees_all(void) { return TLua_Offline(NULL); }\n'
source += weapons[weapons.index('typedef struct touch_practice_s'):
                   weapons.index('#endif', weapons.index('typedef struct touch_practice_s'))]
source += '''
int main(void) {
 const char *values[] = {"off", "0", "false", "FALSE", "no", "No", "", "local", "LOCAL", "1", "true", "yes", "on", "all"};
 const int open[] =      {  0,   0,    0,      0,     0,   0,   0,    1,       1,     1,    1,     1,    1,    1};
 int failed = 0;
 for (int i=0; i<14; ++i) {
  cl_touch_esp_enabled.string = values[i];
  int gate = TouchTactics_EspGate(TW_GATE_OPEN);
  touch_practice_t p = TouchWep_PracticeEffective(gate, 1, 1, 1);
  int markers = p.seethrough || p.full360;
  int intel = gate == TW_GATE_OPEN;
  tlua_test_offline = 1;
  int lua_all = tlua_entities_sees_all();
  printf("value='%s' gate=%s see-through=%d markers=%d full360=%d instant=%d intel=%d lua-entities-see-all=%d\\n",
         values[i], gate == TW_GATE_OPEN ? "open" : "closed", p.seethrough, markers, p.full360, p.instant, intel, lua_all);
  if ((gate == TW_GATE_OPEN) != open[i]) failed = 1;
  if (p.seethrough != open[i] || p.full360 != open[i] || p.instant != open[i] || markers != open[i] || intel != open[i]) failed = 1;
  if (lua_all != open[i]) failed = 1;
 }
 // Not an offline match: even with ESP on, xt.entities must not see through walls.
 tlua_test_offline = 0;
 cl_touch_esp_enabled.string = "local";
 printf("offline=0 value='local' lua-entities-see-all=%d\\n", tlua_entities_sees_all());
 if (tlua_entities_sees_all()) failed = 1;
 // Offline, ESP off: the Lua entity list stays limited however the value is spelled.
 tlua_test_offline = 1;
 cl_touch_esp_enabled.string = "false";
 printf("offline=1 value='false' lua-entities-see-all=%d\\n", tlua_entities_sees_all());
 if (tlua_entities_sees_all()) failed = 1;
 return failed;
}
'''
with tempfile.TemporaryDirectory() as d:
    c = pathlib.Path(d) / 'test.c'
    binary = pathlib.Path(d) / 'test'
    c.write_text(source)
    subprocess.run(['cc', '-std=c99', '-Wall', '-Werror', str(c), '-o', str(binary)], check=True)
    subprocess.run([str(binary)], check=True)
