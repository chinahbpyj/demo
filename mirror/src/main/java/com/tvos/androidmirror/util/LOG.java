package com.tvos.androidmirror.util;

import android.util.Log;

public class LOG {
    private static final boolean DEBUG = false;

    public static final boolean IS_DUMP_LOCAL_VIDEO = false;

    public static boolean IS_DUMP_LOCAL_AUDIO_PCM = false;

    public static String PCM_DUMP_FILE = "/storage/emulated/0/Android/data/com.mirror.demo/files/aac.dat";

    public static void d(String tag, String msg) {
        if (DEBUG) Log.d(tag, msg);
    }

    public static void e(String tag, String msg) {
        if (DEBUG) Log.e(tag, msg);
    }

    public static void setPath(String path) {
        IS_DUMP_LOCAL_AUDIO_PCM = true;
        PCM_DUMP_FILE = path;
    }
}
