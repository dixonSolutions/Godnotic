#include <assert.h>
#include <stdio.h>
#include "touch_zipshot.h"
static void u16(unsigned char *p, unsigned v) { p[0]=v; p[1]=v>>8; }
static void u32(unsigned char *p, uint32_t v) { u16(p,v); u16(p+2,v>>16); }
int main(void)
{
 unsigned char e[22]={0}, d[80]={0}; uint32_t off, len; tzs_entry_t z;
 u32(e,0x06054b50u); u16(e+8,1); u16(e+10,1); u32(e+12,60); u32(e+16,40);
 assert(tzs_directory(e,22,122,&off,&len) && off==40 && len==60);
 u16(e+4,1); assert(!tzs_directory(e,22,122,&off,&len)); u16(e+4,0);
 u32(e+12,TZS_MAX_DIR+1); assert(!tzs_directory(e,22,122,&off,&len));
 u32(d,0x02014b50u); u16(d+10,8); u32(d+16,0xcbf43926); u32(d+20,9); u32(d+24,9); u16(d+28,16); memcpy(d+46,"maps/example.jpg",16);
 assert(tzs_find(d,62,"example",&z) && z.method==8 && z.size==9);
 assert(!tzs_find(d,61,"example",&z)); assert(!tzs_find(d,62,"other",&z));
 u32(d+24,TZS_MAX_IMAGE+1); assert(!tzs_find(d,62,"example",&z));
 /* the Quake 3 place, and a pack with no picture but the map's BSP */
 { unsigned char q[90]={0}; u32(q,0x02014b50u); u16(q+10,8); u32(q+16,0x1234); u32(q+20,9); u32(q+24,9); u16(q+28,22); memcpy(q+46,"levelshots/example.jpg",22);
   assert(tzs_find(q,46+22,"example",&z) && z.size==9 && !z.bsp && z.namelen==22);
   assert(!tzs_find_bsp(q,46+22,"example",&z)); }
 { unsigned char q[90]={0}; u32(q,0x02014b50u); u16(q+10,8); u32(q+16,0x1234); u32(q+20,100); u32(q+24,300); u16(q+28,16); memcpy(q+46,"maps/example.bsp",16);
   assert(!tzs_find(q,62,"example",&z)); assert(tzs_find_bsp(q,62,"example",&z) && z.bsp && z.size==300 && z.compressed==100);
   u32(q+20,TZS_MAX_BSP_PACKED+1); assert(!tzs_find_bsp(q,62,"example",&z)); }
 assert(tzs_crc((const unsigned char *)"123456789",9)==0xcbf43926);
 puts("ZIP levelshot bounds: PASS"); return 0;
}
