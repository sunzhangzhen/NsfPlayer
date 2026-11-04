package com.nsfplayer;

public class NsfCore {
    static { System.loadLibrary("nsfcore"); }

    public static native int nativeOpen(String path, int rate);
    public static native int nativeTrackCount();
    public static native String[] nativeTrackInfo(int index);
    public static native int nativeStartTrack(int index, int fadeMs);
    public static native int nativeRender(short[] buf, int frames);
    public static native boolean nativeTrackEnded();
    public static native int nativeTell();
    public static native void nativeSeek(int ms);
    public static native void nativeClose();
}
