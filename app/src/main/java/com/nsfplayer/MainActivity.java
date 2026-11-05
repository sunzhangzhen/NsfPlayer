package com.nsfplayer;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * UI 层：播放状态与播放列表均来自 PlaybackService（广播），本类不再持有 NsfPlayer。
 */
public class MainActivity extends Activity {

    private static final int REQ_FOLDER = 10;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private Spinner fileSpinner;
    private TextView trackTitle;
    private TextView statusText;
    private SeekBar seekBar;
    private Button playBtn;
    private boolean userDragging = false;
    private SharedPreferences sp;
    private long lastBackTime = 0;

    // 来自服务的状态镜像
    private final List<String> names = new ArrayList<>();
    private List<String> svcTitles = new ArrayList<>();
    private List<Integer> svcLengths = new ArrayList<>();
    private int trackCount = 0;
    private int currentTrack = 0;
    private int currentFileIndex = -1;
    private int svcPosMs = 0;
    private boolean svcPlaying = false;
    private boolean svcPaused = false;
    private boolean started = false;
    private String svcError = null;
    private boolean listReady = false;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        sp = getSharedPreferences("cfg", MODE_PRIVATE);

        fileSpinner = findViewById(R.id.file_spinner);
        trackTitle = findViewById(R.id.track_title);
        statusText = findViewById(R.id.status_text);
        seekBar = findViewById(R.id.seek_bar);
        playBtn = findViewById(R.id.play_btn);
        Button prev = findViewById(R.id.prev_btn);
        Button next = findViewById(R.id.next_btn);
        ImageButton choose = findViewById(R.id.choose_btn);

        choose.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickFolder(); }
        });

        fileSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int i, long id) {
                if (i == currentFileIndex) return;
                Intent si = new Intent(PlaybackService.ACTION_SELECT).setClass(MainActivity.this, PlaybackService.class);
                si.putExtra(PlaybackService.EXTRA_FILE_INDEX, i);
                startService(si);
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });

        playBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { send(PlaybackService.ACTION_TOGGLE); }
        });
        prev.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { send(PlaybackService.ACTION_PREV); }
        });
        next.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { send(PlaybackService.ACTION_NEXT); }
        });

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (fromUser && svcLengths.size() > currentTrack) {
                    trackTitle.setText(fmt(progress) + " / " + fmt(svcLengths.get(currentTrack) / 1000)
                            + "  " + titleAt(currentTrack));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar s) { userDragging = true; }
            @Override public void onStopTrackingTouch(SeekBar s) {
                userDragging = false;
                Intent si = new Intent(PlaybackService.ACTION_SEEK).setClass(MainActivity.this, PlaybackService.class);
                si.putExtra(PlaybackService.EXTRA_SEEK_MS, s.getProgress() * 1000);
                startService(si);
            }
        });
    }

    // ---------- 服务通信 ----------
    private Intent send(String action) {
        Intent i = new Intent(action).setClass(this, PlaybackService.class);
        startService(i);
        return i;
    }

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            if (!PlaybackService.BROADCAST.equals(intent.getAction())) return;
            svcPosMs = intent.getIntExtra("pos", 0);
            svcPlaying = intent.getBooleanExtra("playing", false);
            svcPaused = intent.getBooleanExtra("paused", false);
            currentTrack = intent.getIntExtra("track", 0);
            trackCount = intent.getIntExtra("track_count", 0);
            currentFileIndex = intent.getIntExtra("file_index", -1);
            started = intent.getBooleanExtra("started", false);
            svcError = intent.getStringExtra("error");
            String[] t = intent.getStringArrayExtra("titles");
            int[] l = intent.getIntArrayExtra("lengths");
            svcTitles.clear();
            svcLengths.clear();
            if (t != null) for (String s : t) svcTitles.add(s);
            if (l != null) for (int v : l) svcLengths.add(v);
            renderUi();
        }
    };

    private final BroadcastReceiver listReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            ArrayList<String> ns = intent.getStringArrayListExtra("names");
            names.clear();
            if (ns != null) names.addAll(ns);
            listReady = true;
            applyList();
        }
    };

    @Override protected void onStart() {
        super.onStart();
        registerReceiver(stateReceiver, new IntentFilter(PlaybackService.BROADCAST), RECEIVER_NOT_EXPORTED);
        registerReceiver(listReceiver, new IntentFilter(PlaybackService.BROADCAST_LIST), RECEIVER_NOT_EXPORTED);
        ensureService();
        requestNotifPermission();
    }

    @Override protected void onStop() {
        try { unregisterReceiver(stateReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(listReceiver); } catch (Exception ignored) {}
        super.onStop();
    }

    /** 拉起服务；服务新建时会自动扫描并恢复进度。*/
    private void ensureService() {
        startService(new Intent(this, PlaybackService.class));
        if (names.isEmpty() && !listReady) {
            send(PlaybackService.ACTION_SCAN);
        }
    }

    private void requestNotifPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            try { requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 20); }
            catch (Exception ignored) {}
        }
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] grants) {
        super.onRequestPermissionsResult(req, perms, grants);
    }

    // ---------- 文件夹选择（SAF） ----------
    private void pickFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        try {
            startActivityForResult(intent, REQ_FOLDER);
        } catch (Exception e) {
            statusText.setText("无法打开文件夹选择器：" + e.getMessage());
        }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_FOLDER && res == RESULT_OK && data != null) {
            Uri treeUri = data.getData();
            if (treeUri != null) {
                try {
                    getContentResolver().takePersistableUriPermission(treeUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                } catch (Exception ignored) {}
                sp.edit().putString("tree", treeUri.toString()).apply();
                listReady = false;
                send(PlaybackService.ACTION_SCAN);
            }
        }
    }

    // ---------- 分享/打开入口 ----------
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        Uri uri = null;
        if (Intent.ACTION_SEND.equals(action)) {
            uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        } else if (Intent.ACTION_VIEW.equals(action)) {
            uri = intent.getData();
        }
        if (uri == null) return;
        ensureService();
        Intent i = new Intent(PlaybackService.ACTION_OPEN_FILE).setClass(this, PlaybackService.class);
        i.putExtra(PlaybackService.EXTRA_URI, uri);
        startService(i);
    }

    // ---------- 渲染 ----------
    private String titleAt(int i) {
        return (svcTitles.size() > i) ? svcTitles.get(i) : "";
    }
    private int lengthAt(int i) {
        return (svcLengths.size() > i) ? svcLengths.get(i) : 0;
    }

    private void applyList() {
        ArrayAdapter<String> ad = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, new ArrayList<>(names));
        fileSpinner.setAdapter(ad);
        if (currentFileIndex >= 0 && currentFileIndex < names.size()) {
            fileSpinner.setSelection(currentFileIndex);
        }
        renderUi();
    }

    private void renderUi() {
        if (names.isEmpty()) {
            Uri tree = savedTree();
            if (svcError != null) statusText.setText(svcError);
            else if (tree == null) {
                statusText.setText("尚未授权音乐文件夹。\n请点“选择文件夹”按钮，选中存放 .nsf 的目录；"
                        + "\n或从文件管理器长按 .nsf → 分享 → 本播放器。");
            } else {
                statusText.setText("所选文件夹里没有 .nsf 文件。\n换到存放 .nsf 的目录再选一次；"
                        + "\n或从文件管理器长按 .nsf → 分享 → 本播放器。");
            }
            playBtn.setText("播放");
            trackTitle.setText("");
            return;
        }
        String fname = (currentFileIndex >= 0 && currentFileIndex < names.size())
                ? names.get(currentFileIndex) : "";
        statusText.setText(fname + "  ·  共 " + trackCount + " 首");
        playBtn.setText(svcPlaying ? "暂停" : "播放");

        int len = lengthAt(currentTrack) / 1000;
        if (len > 0) {
            if (!userDragging) {
                seekBar.setMax(len);
                int pos = Math.min(svcPosMs / 1000, len);
                seekBar.setProgress(pos);
                trackTitle.setText(fmt(pos) + " / " + fmt(len) + "  " + titleAt(currentTrack));
            }
        } else if (!userDragging) {
            seekBar.setMax(0);
            seekBar.setProgress(0);
            trackTitle.setText(titleAt(currentTrack));
        }
    }

    private Uri savedTree() {
        String s = sp.getString("tree", null);
        if (s == null) return null;
        try { return Uri.parse(s); } catch (Exception e) { return null; }
    }

    private String fmt(int sec) {
        if (sec < 0) sec = 0;
        return String.format("%d:%02d", sec / 60, sec % 60);
    }

    @Override public void onBackPressed() {
        long now = System.currentTimeMillis();
        if (now - lastBackTime < 1500) {
            // 退出界面但保留服务：通知栏继续可控
            moveTaskToBack(true);
            finish();
        } else {
            lastBackTime = now;
            Toast.makeText(this, "再按一次退出", Toast.LENGTH_SHORT).show();
        }
    }
}