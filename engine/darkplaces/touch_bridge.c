#include "touch_bridge.h"
#include <string.h>

int Touch_BridgeCreatePeer(void *context, rtcDescriptionCallbackFunc description,
                           rtcCandidateCallbackFunc candidate, rtcDataChannelCallbackFunc channel)
{
    rtcConfiguration config;
    int peer;
    memset(&config, 0, sizeof(config));
    config.disableAutoNegotiation = true;
    peer = rtcCreatePeerConnection(&config);
    if (peer < 0)
        return peer;
    rtcSetUserPointer(peer, context);
    if (rtcSetLocalDescriptionCallback(peer, description) < 0 ||
        rtcSetLocalCandidateCallback(peer, candidate) < 0 ||
        rtcSetDataChannelCallback(peer, channel) < 0) {
        rtcDeletePeerConnection(peer);
        return RTC_ERR_FAILURE;
    }
    return peer;
}

int Touch_BridgeAcceptChannel(int channel, void *context, rtcOpenCallbackFunc open,
                              rtcMessageCallbackFunc message)
{
    rtcSetUserPointer(channel, context);
    if (rtcSetOpenCallback(channel, open) < 0 || rtcSetMessageCallback(channel, message) < 0)
        return RTC_ERR_FAILURE;
    return 0;
}

int Touch_BridgeOpenChannel(int peer, const char *label, void *context,
                            rtcOpenCallbackFunc open, rtcMessageCallbackFunc message)
{
    rtcDataChannelInit init;
    int channel;
    memset(&init, 0, sizeof(init));
    init.reliability.unordered = true;
    init.reliability.unreliable = true;
    init.reliability.maxRetransmits = 0;
    channel = rtcCreateDataChannelEx(peer, label, &init);
    if (channel >= 0 && Touch_BridgeAcceptChannel(channel, context, open, message) < 0) {
        rtcDeleteDataChannel(channel);
        return RTC_ERR_FAILURE;
    }
    return channel;
}

int Touch_BridgeSend(int channel, const void *bytes, int size)
{
    if (!bytes || size <= 0)
        return RTC_ERR_INVALID;
    return rtcSendMessage(channel, (const char *)bytes, size);
}
