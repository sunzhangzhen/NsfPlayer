package com.nsfplayer;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

public class NsfPlayer {
    public static final int RATE = 44100;
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
        int n = NsfCore.nativeOpen(path, RATE);
        return n > 0;
    }
    public int trackCount() { return NsfCore.nativeTrackCount(); }
    public String[] trackInfo(int i) { return NsfCore.nativeTrackInfo(i); }

    public void play(int index, int lengthMs) {
        stop();
        currentTrack = index;
        trackLengthMs = lengthMs;
        if (NsfCore.nativeStartTrack(index, lengthMs > 8000 ? lengthMs - 5000 : 0) != 0) return;
        if (startMs > 0) { NsfCore.nativeSeek(startMs); startMs = 0; }
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
                    if (NsfCore.nativeRender(buf, frames) != 0) break;
                    if (!running) break;
                    track.write(buf, 0, buf.length);
                    if (trackLengthMs > 0 && NsfCore.nativeTell() > trackLengthMs + 1000) {
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
    public int positionMs() { return NsfCore.nativeTell(); }
    public int lengthMs() { return trackLengthMs; }
    public int currentTrackIndex() { return currentTrack; }

    public void seekTo(int ms) {
        NsfCore.nativeSeek(ms);
        if (track != null) track.flush();
    }

    public void stop() {
        running = false;
        if (thread != null) {
            try { thread.join(300); } catch (InterruptedException ignored) {}
            thread = null;
        }
        if (track != null) {
            try { track.stop(); } catch (IllegalStateException ignored) {}
            track.release();
            track = null;
        }
        paused = false;
    }

    public void release() { stop(); NsfCore.nativeClose(); }
}
