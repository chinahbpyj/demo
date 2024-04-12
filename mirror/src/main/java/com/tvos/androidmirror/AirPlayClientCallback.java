package com.tvos.androidmirror;

import java.util.Map;

import javax.jmdns.ServiceInfo;

/**
 * Created by feiwei on 15-11-19.
 */
public interface AirPlayClientCallback {
    public void onAirplayServiceResolved(Map<String, ServiceInfo> mDiscoveryServiceMap);
    //public void onAirplayDiscovered();
    public void onRequireMirrorSuccess();
    public void onRequireMirrorFailed();

    public void onStopMirrorCompleted();
    public void onMirrorDisconnected();
}
