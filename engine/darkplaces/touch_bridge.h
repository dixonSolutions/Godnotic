#ifndef TOUCH_BRIDGE_H
#define TOUCH_BRIDGE_H

#include <rtc/rtc.h>

/* Callbacks execute on libdatachannel threads; the caller owns their context. */
int Touch_BridgeCreatePeer(void *context, rtcDescriptionCallbackFunc description,
                           rtcCandidateCallbackFunc candidate, rtcDataChannelCallbackFunc channel);
int Touch_BridgeOpenChannel(int peer, const char *label, void *context,
                            rtcOpenCallbackFunc open, rtcMessageCallbackFunc message);
int Touch_BridgeAcceptChannel(int channel, void *context, rtcOpenCallbackFunc open,
                              rtcMessageCallbackFunc message);
int Touch_BridgeSend(int channel, const void *bytes, int size);

#endif
