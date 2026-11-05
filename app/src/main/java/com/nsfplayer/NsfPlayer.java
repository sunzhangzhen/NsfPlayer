package com.nsfplayer;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

public class NsfPlayer {
    public static final int RATE = 44100;
    // gme 引擎非线程安全：play/seek/tell/start/close 必须串行。
    private final Object nativeLock = new Object();
    private AudioTrack track;
    private Thread thread;
    private volatile boolean running = false;
    private volatile boolean paused = false;
    private volatile int currentTrack = -1;
    private volatile int trackLengthMs = 0;
    private volatile int startMs = 0;
    private OnTrackFinished listener;

    public interface OnTrackFinished { void onFinished(); }
    public void setOnTrackFinished(OnTrackFinished l) { listener = l; }
    public void setStartMs(int ms) { startMs = ms; }

    public boolean load(String path) {
        synchronized (nativeLock) {
            int n = NsfCore.nativeOpen(path, RATE);
            return n > 0;
        }
    }
    public int trackCount() { synchronized (nativeLock) { return NsfCore.nativeTrackCount(); } }
    public String[] trackInfo(int i) { synchronized (nativeLock) { return NsfCore.nativeTrackInfo(i); } }

    public void play(int index, int lengthMs) {
        stop();
        currentTrack = index;
        trackLengthMs = lengthMs;
        synchronized (nativeLock) {
            if (NsfCore.nativeStartTrack(index, lengthMs > 8000 ? lengthMs - 5000 : 0) != 0) return;
            if (startMs > 0) { NsfCore.nativeSeek(startMs); startMs = 0; }
        }
        startAudio();
    }

    private void startAudio() {
        int minBuf = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        int bufSize = Math.max(minBuf, RATE * 2 * 2 * 4);
        track = new AudioTrack(AudioManager.STREAM_MUSIC, RATE,
                AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
                bufSize, AudioTrack.MODE_STREAM);
        track.play();
        running = true;
        final int frames = RATE / 20;
        thread = new Thread(new Runnable() {
            @Override public void run() {
                short[] buf = new short[frames * 2];
                while (running) {
                    if (paused) {
                        try { Thread.sleep(30); } catch (InterruptedException e) { break; }
                        continue;
                    }
                    int pos;
                    boolean ended;
                    synchronized (nativeLock) {
                        if (!running) break;
                        // gme_play 即使 seek 到末尾也会填充静音帧并返回成功；
                        // 只有真正出错才返回非0。这里不因单次失败就退出线程。
                        int err = NsfCore.nativeRender(buf, frames);
                        if (err != 0) {
                            // 渲染出错：填静音继续，避免线程直接退出导致永久静音
                            java.util.Arrays.fill(buf, (short) 0);
                        }
                        pos = NsfCore.nativeTell();
                        ended = trackLengthMs > 0 && pos > trackLengthMs + 1000;
                    }
                    if (!running) break;
                    track.write(buf, 0, buf.length);
                    if (ended) {
                        if (listener != null) listener.onFinished();
                        break;
                    }
                }
                running = false;
            }
        }, "NsfRenderThread");
        thread.start();
    }

    public void pause() { paused = true; if (track != null) track.pause(); }
    public void resume() { if (track != null) track.play(); paused = false; }
    public boolean isPaused() { return paused; }
    public boolean isPlaying() { return running && !paused; }

    public int positionMs() {
        synchronized (nativeLock) { return NsfCore.nativeTell(); }
    }
    public int lengthMs() { return trackLengthMs; }
    public int currentTrackIndex() { return currentTrack; }

    public void seekTo(int ms) {
        synchronized (nativeLock) {
            NsfCore.nativeSeek(ms);
        }
        if (track != null) {
            track.flush();
            if (!paused) track.play(); // flush 后恢复到播放态，避免静音
        }
    }

    public void stop() {
        running = false;
        if (thread != null) {
            try { thread.join(400); } catch (InterruptedException ignored) {}
            thread = null;
        }
        if (track != null) {
            try { track.stop(); } catch (IllegalStateException ignored) {}
            track.release();
            track = null;
        }
        paused = false;
    }

    public void release() { stop(); synchronized (nativeLock) { NsfCore.nativeClose(); } }
}
