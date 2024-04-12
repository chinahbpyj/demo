package com.tvos.androidmirror;

/**
 * Created by feiwei on 15-11-30.
 */
public class AirplayMirrorData {
    private byte[] data;
    private int flag;
    public static final int DATA_VIDEO_FRAME = 0;
    public static final int DATA_VIDEO_CSD = 1;
    public static final int DATA_AUDIO_FRAME = 2;
    public static final int DATA_HEART_BEAT = 3;

    public void setMirrorData(byte[] data, int flag) {
        this.data = data;
        this.flag = flag;
    }
    public byte[] getData() {
        return this.data;
    }
    public int getFlag() {
        return this.flag;
    }
}
