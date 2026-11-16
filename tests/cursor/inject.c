#define _GNU_SOURCE
#include <SDL.h>
#include <dlfcn.h>
#include <stdio.h>
#include <string.h>
#include <time.h>
int SDL_PollEvent(SDL_Event *e) {
 static int (*next)(SDL_Event *); static double start; static int step;
 struct timespec t; double now; const double times[]={5,7,9,11,11.05};
 if(!next) next=dlsym(RTLD_NEXT,"SDL_PollEvent");
 if(next(e)) return 1;
 clock_gettime(CLOCK_MONOTONIC,&t); now=t.tv_sec+t.tv_nsec/1e9; if(!start) start=now;
 if(step>=5 || now-start<times[step]) return 0;
 memset(e,0,sizeof(*e));
 if(step==1 || step==4) { e->type=SDL_MOUSEMOTION; e->motion.which=1; e->motion.x=100; e->motion.y=100; e->motion.xrel=10; }
 else { e->type=SDL_FINGERMOTION; e->tfinger.touchId=step==3?101:202; e->tfinger.fingerId=1; e->tfinger.x=.5; e->tfinger.y=.5; }
 fprintf(stderr,"INJECT step=%d type=%u id=%lld\n",step,e->type,(long long)(step==1||step==4?1:e->tfinger.touchId)); step++; return 1;
}
SDL_TouchDeviceType SDL_GetTouchDeviceType(SDL_TouchID id) {
 static SDL_TouchDeviceType (*next)(SDL_TouchID); if(id==101) return SDL_TOUCH_DEVICE_INVALID; if(id==202) return SDL_TOUCH_DEVICE_DIRECT;
 if(!next) next=dlsym(RTLD_NEXT,"SDL_GetTouchDeviceType"); return next(id);
}
