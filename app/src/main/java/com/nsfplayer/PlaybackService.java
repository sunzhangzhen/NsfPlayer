package com.nsfplayer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 播放服务：持有 NsfPlayer、播放列表、SAF 扫描与文件缓存。
 * Activity 只负责发指令和收状态广播。
 */
public class PlaybackService extends Service {

    public static final String ACTION_PLAY   = "com.nsfplayer.PLAY";    // 播指定文件/曲目
    public static final String ACTION_TOGGLE = "com.nsfplayer.TOGGLE";
    public static final String ACTION_PREV   = "com.nsfplayer.PREV";
    public static final String ACTION_NEXT   = "com.nsfplayer.NEXT";
    public static final String ACTION_SEEK   = "com.nsfplayer.SEEK";
    public static final String ACTION_STOP   = "com.nsfplayer.STOP";
    public static final String ACTION_SCAN   = "com.nsfplayer.SCAN";
    public static final String ACTION_OPEN_FILE = "com.nsfplayer.OPEN_FILE"; // 打开分享/单文件
    public static final String ACTION_SELECT = "com.nsfplayer.SELECT";  // Spinner 切换文件

    public static final String EXTRA_URI        = "uri";
    public static final String EXTRA_FILE_INDEX = "file_index";
    public static final String EXTRA_TRACK      = "track";
    public static final String EXTRA_POS_MS     = "pos_ms";
    public static final String EXTRA_SEEK_MS    = "seek_ms";

    public static final String BROADCAST = "com.nsfplayer.STATE";
    public static final String BROADCAST_LIST = "com.nsfplayer.LIST";

    private static final String CHANNEL_ID = "nsf_playback";
    private static final int NOTIFY_ID = 1;

    private final NsfPlayer player = new NsfPlayer();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private MediaSession session;
    private AudioManager audioMgr;
    private AudioFocusRequest focusRequest;
    private boolean gotFocus = false;
    private boolean pausedByFocusLoss = false;

    private SharedPreferences sp;
    private final List<File> nsfFiles = new ArrayList<>();
    private final List<String> displayNames = new ArrayList<>();
    private String[] titles = new String[0];
    private int[] lengths = new int[0];
    private int trackCount = 0;
    private int currentTrack = 0;
    private int currentFileIndex = -1;
    private boolean started = false;
    private int restorePosMs = -1;
    private boolean restoreState = false;
    private String safError = null;
    private long lastNotifyTime = 0;
    private long lastSaveTime = 0;

    @Override public void onCreate() {
        super.onCreate();
        sp = getSharedPreferences("cfg", MODE_PRIVATE);
        audioMgr = (AudioManager) getSystemService(AUDIO_SERVICE);

        session = new MediaSession(this, "NsfPlayer");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() {
                if (player.isPaused()) { requestFocus(); player.resume(); refreshNotification(); broadcastState(); }
                else if (!player.isPlaying()) playCurrent();
            }
            @Override public void onPause() {
                if (player.isPlaying()) pausePlay();
            }
            @Override public void onSkipToNext() { step(1); }
            @Override public void onSkipToPrevious() { step(-1); }
            @Override public void onSeekTo(long pos) { doSeek((int) pos); }
            @Override public void onStop() { stopEverything(); }
        });
        session.setActive(true);

        createChannel();
        startForeground(NOTIFY_ID, buildNotification());

        player.setOnTrackFinished(new NsfPlayer.OnTrackFinished() {
            @Override public void onFinished() {
                ui.post(new Runnable() { @Override public void run() { step(1); } });
            }
        });

        loadCacheIntoList();
        ui.postDelayed(tickRunnable, 250);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) handleAction(intent);
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onDestroy() {
        saveProgress();
        ui.removeCallbacks(tickRunnable);
        abandonFocus();
        player.release();
        if (session != null) { session.setActive(false); session.release(); }
        super.onDestroy();
    }

    private void handleAction(Intent intent) {
        String a = intent.getAction();
        if (a == null) return;
        switch (a) {
            case ACTION_SCAN:
                scanFiles();
                break;
            case ACTION_SELECT: {
                int idx = intent.getIntExtra(EXTRA_FILE_INDEX, -1);
                if (idx >= 0 && idx != currentFileIndex) openFile(idx);
                break;
            }
            case ACTION_OPEN_FILE: {
                Uri u = intent.getParcelableExtra(EXTRA_URI);
                openSharedUri(u);
                break;
            }
            case ACTION_PLAY: {
                int tr = intent.getIntExtra(EXTRA_TRACK, -1);
                int pos = intent.getIntExtra(EXTRA_POS_MS, -1);
                if (tr >= 0 && tr < trackCount) currentTrack = tr;
                if (pos >= 0) { restorePosMs = pos; restoreState = true; }
                playCurrent();
                break;
            }
            case ACTION_TOGGLE:
                togglePlay();
                break;
            case ACTION_PREV:
                step(-1);
                break;
            case ACTION_NEXT:
                step(1);
                break;
            case ACTION_SEEK:
                doSeek(intent.getIntExtra(EXTRA_SEEK_MS, 0));
                break;
            case ACTION_STOP:
                stopEverything();
                break;
        }
    }

    // ---------- 扫描（分批刷新 + JSON 缓存） ----------
    private Uri savedTree() {
        String s = sp.getString("tree", null);
        if (s == null) return null;
        try { return Uri.parse(s); } catch (Exception e) { return null; }
    }

    private boolean treeAlive(Uri tree) {
        if (tree == null) return false;
        try {
            for (android.content.UriPermission p : getContentResolver().getPersistedUriPermissions()) {
                if (p.getUri().toString().equals(tree.toString()) && p.isReadPermission()) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private File musicDir() {
        File d = new File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_MUSIC), "NSF");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** 冷启动：先从缓存读文件名秒显列表，不复制文件。*/
    private void loadCacheIntoList() {
        displayNames.clear();
        nsfFiles.clear();
        try {
            String s = sp.getString("file_cache", null);
            if (s == null) return;
            JSONArray arr = new JSONArray(s);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String name = o.optString("fileName");
                if (name == null || name.length() == 0) continue;
                displayNames.add(name);
                nsfFiles.add(new File(new File(getCacheDir(), "nsf"), name));
            }
        } catch (Exception ignored) {}
        broadcastList();
        if (!nsfFiles.isEmpty()) tryRestore();
    }

    private void scanFiles() {
        Uri tree = savedTree();
        safError = null;
        List<String> names = new ArrayList<>();

        if (tree != null) {
            if (!treeAlive(tree)) {
                safError = "文件夹授权已失效（重装或清数据后会发生），请重新选择文件夹。";
            } else {
                try {
                    listNsfViaSaf(tree, names);
                } catch (Exception e) {
                    safError = "读取授权文件夹失败：" + e.getMessage();
                }
            }
        }
        if (names.isEmpty()) {
            File dir = musicDir();
            File[] arr = dir.listFiles();
            if (arr != null) {
                for (File f : arr) {
                    if (f.isFile() && f.getName().toLowerCase().endsWith(".nsf")) names.add(f.getName());
                }
            }
        }

        displayNames.clear();
        nsfFiles.clear();

        // 分批：每攒 80 个复制完就刷一次 UI
        File cache = new File(getCacheDir(), "nsf");
        if (!cache.exists()) cache.mkdirs();
        int sinceFlush = 0;
        JSONArray cacheArr = new JSONArray();
        for (String n : names) {
            File f = (tree != null && treeAlive(tree)) ? copyDocToCache(tree, n) : new File(cache, n);
            if (f == null || !f.exists()) continue;
            displayNames.add(n);
            nsfFiles.add(f);
            sinceFlush++;
            try {
                JSONObject o = new JSONObject();
                o.put("fileName", n);
                o.put("fileSizeBytes", f.length());
                o.put("lastModified", f.lastModified());
                cacheArr.put(o);
            } catch (Exception ignored) {}
            if (sinceFlush >= 80) {
                sinceFlush = 0;
                writeCache(cacheArr);
                ui.post(new Runnable() { @Override public void run() { broadcastList(); } });
            }
        }
        writeCache(cacheArr);
        broadcastList();

        if (nsfFiles.isEmpty()) {
            currentFileIndex = -1;
            trackCount = 0;
            titles = new String[0];
            lengths = new int[0];
            broadcastState();
        } else {
            if (currentFileIndex < 0 || currentFileIndex >= nsfFiles.size()) currentFileIndex = 0;
            openFile(currentFileIndex);
            tryRestore();
        }
    }

    private void writeCache(JSONArray arr) {
        try { sp.edit().putString("file_cache", arr.toString()).apply(); } catch (Exception ignored) {}
    }

    private void listNsfViaSaf(Uri tree, List<String> out) {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree,
                DocumentsContract.getTreeDocumentId(tree));
        Cursor c = getContentResolver().query(children,
                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
        if (c != null) {
            while (c.moveToNext()) {
                String n = c.getString(0);
                if (n != null && n.toLowerCase().endsWith(".nsf")) out.add(n);
            }
            c.close();
        }
    }

    private File copyDocToCache(Uri tree, String displayName) {
        try {
            Uri docUri = DocumentsContract.buildDocumentUriUsingTree(tree,
                    DocumentsContract.getTreeDocumentId(tree) + "/" + displayName);
            InputStream in;
            try {
                in = getContentResolver().openInputStream(docUri);
            } catch (Exception e) { in = null; }
            if (in == null) {
                Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree,
                        DocumentsContract.getTreeDocumentId(tree));
                Cursor c = getContentResolver().query(children,
                        new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                                DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
                if (c != null) {
                    while (c.moveToNext()) {
                        if (displayName.equals(c.getString(1))) {
                            in = getContentResolver().openInputStream(
                                    DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)));
                            break;
                        }
                    }
                    c.close();
                }
            }
            if (in == null) return null;
            File cache = new File(getCacheDir(), "nsf");
            if (!cache.exists()) cache.mkdirs();
            File out = new File(cache, displayName);
            if (out.exists() && out.length() > 0) { in.close(); return out; }
            FileOutputStream fo = new FileOutputStream(out);
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) fo.write(buf, 0, r);
            fo.flush(); fo.close(); in.close();
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private void openSharedUri(Uri uri) {
        if (uri == null) return;
        String name = queryName(uri);
        if (name == null || name.length() == 0) name = "shared.nsf";
        if (!name.toLowerCase().endsWith(".nsf")) name = name + ".nsf";
        File cache = new File(getCacheDir(), "nsf");
        if (!cache.exists()) cache.mkdirs();
        File out = new File(cache, name);
        try {
            InputStream in = getContentResolver().openInputStream(uri);
            FileOutputStream fo = new FileOutputStream(out);
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) fo.write(buf, 0, r);
            fo.flush(); fo.close(); in.close();
            displayNames.clear();
            nsfFiles.clear();
            displayNames.add(name);
            nsfFiles.add(out);
            broadcastList();
            currentFileIndex = 0;
            openFile(0);
        } catch (Exception e) {
            safError = "打开分享文件失败：" + e.getMessage();
            broadcastState();
        }
    }

    private String queryName(Uri uri) {
        try {
            Cursor c = getContentResolver().query(uri, null, null, null, null);
            if (c != null) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (c.moveToFirst() && idx >= 0) { String n = c.getString(idx); c.close(); return n; }
                c.close();
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ---------- 载入文件 ----------
    private void openFile(int index) {
        if (index < 0 || index >= nsfFiles.size()) return;
        saveProgress();
        player.stop();
        started = false;
        currentFileIndex = index;
        File f = nsfFiles.get(index);
        if (!player.load(f.getAbsolutePath())) {
            titles = new String[0];
            lengths = new int[0];
            trackCount = 0;
            broadcastState();
            return;
        }
        trackCount = player.trackCount();
        titles = new String[trackCount];
        lengths = new int[trackCount];
        for (int i = 0; i < trackCount; i++) {
            String[] info = player.trackInfo(i);
            String t = (info != null && info[0].length() > 0) ? info[0]
                    : (info != null && info[1].length() > 0 ? info[1] : null);
            titles[i] = String.format("%02d", i + 1) + " - " + (t == null ? ("Track " + (i + 1)) : t);
            int ms = 0;
            try { if (info != null) ms = Integer.parseInt(info[4]); } catch (Exception ignored) {}
            // gme 对无时长标签的 NSF 返回 <=0（未知/无限循环）。
            // 此时不用假的 60000（会导致进度条被截断、无法拖动），改用 20 分钟封顶。
            if (ms <= 0 || ms > 1200000) ms = 1200000;
            lengths[i] = ms;
        }
        currentTrack = 0;
        broadcastState();
    }

    private void tryRestore() {
        if (nsfFiles.isEmpty()) return;
        String lastFile = sp.getString("lastFile", null);
        if (lastFile == null) { broadcastState(); return; }
        int idx = -1;
        for (int i = 0; i < nsfFiles.size(); i++) {
            if (nsfFiles.get(i).getName().equals(lastFile)) { idx = i; break; }
        }
        if (idx < 0) { broadcastState(); return; }
        currentFileIndex = idx;
        openFile(idx);
        int lt = sp.getInt("lastTrack", 0);
        int lp = sp.getInt("lastPosMs", 0);
        if (lt >= 0 && lt < trackCount) {
            currentTrack = lt;
            restorePosMs = lp;
            restoreState = true;
        }
        broadcastState();
    }

    // ---------- 播放控制 ----------
    private void playCurrent() {
        if (trackCount == 0) return;
        if (currentTrack < 0) currentTrack = trackCount - 1;
        if (currentTrack >= trackCount) currentTrack = 0;
        requestFocus();
        if (restoreState && restorePosMs > 0) {
            player.setStartMs(restorePosMs);
            restorePosMs = 0;
            restoreState = false;
        }
        player.play(currentTrack, lengths[currentTrack]);
        started = true;
        updateMetadata();
        refreshNotification();
        broadcastState();
    }

    private void pausePlay() {
        player.pause();
        saveProgress();
        refreshNotification();
        broadcastState();
    }

    private void togglePlay() {
        if (trackCount == 0) return;
        if (player.isPlaying()) pausePlay();
        else if (player.isPaused()) {
            requestFocus();
            player.resume();
            refreshNotification();
            broadcastState();
        } else playCurrent();
    }

    private void step(int dir) {
        if (trackCount == 0) return;
        saveProgress();
        currentTrack += dir;
        if (currentTrack < 0) currentTrack = trackCount - 1;
        if (currentTrack >= trackCount) currentTrack = 0;
        if (started) playCurrent();
        else { updateMetadata(); refreshNotification(); broadcastState(); }
    }

    private void doSeek(int ms) {
        if (ms < 0) ms = 0;
        player.seekTo(ms);
        // 手动 seek 不应污染冷启动恢复用的 restorePosMs
        restoreState = false;
        saveProgress(ms);
        broadcastState();
    }

    private void stopEverything() {
        saveProgress();
        player.stop();
        abandonFocus();
        started = false;
        refreshNotification();
        broadcastState();
    }

    private final Runnable tickRunnable = new Runnable() {
        @Override public void run() {
            long now = System.currentTimeMillis();
            if (now - lastSaveTime > 3000 && started && player.isPlaying()) {
                lastSaveTime = now;
                saveProgress();
            }
            broadcastState();
            if (now - lastNotifyTime > 1000) {
                lastNotifyTime = now;
                refreshNotification();
            }
            ui.postDelayed(this, 250);
        }
    };
    private void saveProgress() {
        saveProgress(player.positionMs());
    }

    private void saveProgress(int posMs) {
        if (sp == null || nsfFiles.isEmpty() || currentFileIndex < 0) return;
        sp.edit()
          .putString("lastFile", nsfFiles.get(currentFileIndex).getName())
          .putInt("lastTrack", currentTrack)
          .putInt("lastPosMs", posMs)
          .putBoolean("wasPlaying", started && player.isPlaying())
          .apply();
    }

    // ---------- AudioFocus ----------
    private void requestFocus() {
        if (gotFocus) return;
        if (focusRequest == null) {
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setOnAudioFocusChangeListener(focusListener)
                    .build();
        }
        gotFocus = (audioMgr.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
    }

    private void abandonFocus() {
        if (!gotFocus) return;
        audioMgr.abandonAudioFocusRequest(focusRequest);
        gotFocus = false;
    }

    private final AudioManager.OnAudioFocusChangeListener focusListener =
            new AudioManager.OnAudioFocusChangeListener() {
                @Override public void onAudioFocusChange(int change) {
                    switch (change) {
                        case AudioManager.AUDIOFOCUS_LOSS:
                            abandonFocus();
                            if (player.isPlaying()) { player.pause(); pausedByFocusLoss = true; }
                            break;
                        case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                            if (player.isPlaying()) { player.pause(); pausedByFocusLoss = true; }
                            break;
                        case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                            break;
                        case AudioManager.AUDIOFOCUS_GAIN:
                            if (pausedByFocusLoss) { player.resume(); pausedByFocusLoss = false; }
                            break;
                    }
                    refreshNotification();
                    broadcastState();
                }
            };

    // ---------- 通知栏 ----------
    private void createChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "播放", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String title = (titles.length > currentTrack) ? titles[currentTrack] : "NSF 播放器";
        String text = (currentFileIndex >= 0 && displayNames.size() > currentFileIndex)
                ? displayNames.get(currentFileIndex) : "";
        boolean playing = player.isPlaying();

        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notif_music)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(playing)
                .setVisibility(Notification.VISIBILITY_PUBLIC);

        b.addAction(action(1, playing ? R.drawable.ic_notif_pause : R.drawable.ic_notif_play,
                playing ? "暂停" : "播放", ACTION_TOGGLE));
        b.addAction(action(2, R.drawable.ic_notif_prev, "上一曲", ACTION_PREV));
        b.addAction(action(3, R.drawable.ic_notif_next, "下一曲", ACTION_NEXT));

        b.setStyle(new Notification.MediaStyle()
                .setShowActionsInCompactView(0, 1, 2)
                .setMediaSession(session != null ? session.getSessionToken() : null));
        return b.build();
    }

    private Notification.Action action(int code, int icon, String label, String act) {
        Intent i = new Intent(act).setClass(this, PlaybackService.class);
        PendingIntent pi = PendingIntent.getService(this, code, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, icon), label, pi).build();
    }

    private void refreshNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIFY_ID, buildNotification());
    }

    // ---------- MediaSession / 广播 ----------
    private void updateMetadata() {
        if (session == null) return;
        session.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE,
                        (titles.length > currentTrack) ? titles[currentTrack] : "")
                .putString(MediaMetadata.METADATA_KEY_ARTIST,
                        (currentFileIndex >= 0 && displayNames.size() > currentFileIndex)
                                ? displayNames.get(currentFileIndex) : "")
                .putLong(MediaMetadata.METADATA_KEY_DURATION,
                        (lengths.length > currentTrack) ? lengths[currentTrack] : 0)
                .putLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER, currentTrack + 1)
                .putLong(MediaMetadata.METADATA_KEY_NUM_TRACKS, trackCount)
                .build());
    }

    private void broadcastState() {
        if (session != null) {
            int state = player.isPlaying() ? PlaybackState.STATE_PLAYING
                    : player.isPaused() ? PlaybackState.STATE_PAUSED
                    : PlaybackState.STATE_STOPPED;
            session.setPlaybackState(new PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                            | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
                            | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO
                            | PlaybackState.ACTION_STOP)
                    .setState(state, player.positionMs(), 1.0f)
                    .build());
            updateMetadata();
        }
        Intent i = new Intent(BROADCAST).setPackage(getPackageName());
        i.putExtra("pos", player.positionMs());
        i.putExtra("playing", player.isPlaying());
        i.putExtra("paused", player.isPaused());
        i.putExtra("track", currentTrack);
        i.putExtra("track_count", trackCount);
        i.putExtra("file_index", currentFileIndex);
        i.putExtra("started", started);
        i.putExtra("error", safError);
        i.putExtra("titles", titles);
        i.putExtra("lengths", lengths);
        sendBroadcast(i);
    }

    private void broadcastList() {
        Intent i = new Intent(BROADCAST_LIST).setPackage(getPackageName());
        i.putStringArrayListExtra("names", new ArrayList<>(displayNames));
        i.putExtra("count", nsfFiles.size());
        sendBroadcast(i);
    }
}
