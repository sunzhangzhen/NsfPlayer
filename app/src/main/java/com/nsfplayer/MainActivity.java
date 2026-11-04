package com.nsfplayer;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.Toast;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final int REQ_FOLDER = 10;
    private final NsfPlayer player = new NsfPlayer();
    private final List<File> nsfFiles = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private Spinner fileSpinner;
    private TextView trackTitle;
    private TextView statusText;
    private SeekBar seekBar;
    private Button playBtn;
    private boolean userDragging = false;
    private boolean started = false;    private SharedPreferences sp;    private long lastSaveTime = 0;    private int restorePosMs = -1;    private boolean restoreState = false;
    private long lastBackTime = 0;
    private int currentFileIndex = -1;
    private int currentTrack = 0;
    private int trackCount = 0;
    private String[] titles = new String[0];
    private int[] lengths = new int[0];

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
        Button refresh = findViewById(R.id.refresh_btn);
        ImageButton choose = findViewById(R.id.choose_btn);

        choose.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickFolder(); }
        });
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { scanFiles(); }
        });

        fileSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int i, long id) {
                if (i == currentFileIndex) return;
                currentFileIndex = i;
                openFile(i);
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });

        playBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { togglePlay(); }
        });
        prev.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { step(-1); }
        });
        next.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { step(1); }
        });

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (fromUser && lengths.length > 0 && currentTrack < lengths.length) {
                    trackTitle.setText(fmt(progress) + " / " + fmt(lengths[currentTrack] / 1000)
                            + "  " + titles[currentTrack]);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar s) { userDragging = true; }
            @Override public void onStopTrackingTouch(SeekBar s) {
                userDragging = false;
                if (lengths.length > 0 && currentTrack < lengths.length) {
                    player.seekTo(s.getProgress() * 1000);
                }
            }
        });

        player.setOnTrackFinished(new NsfPlayer.OnTrackFinished() {
            @Override public void onFinished() {
                ui.post(new Runnable() { @Override public void run() { step(1); } });
            }
        });

        ui.post(new Runnable() {
            @Override public void run() { tick(); ui.postDelayed(this, 250); }
        });

        scanFiles();
        handleIncomingIntent(getIntent());
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
                persistPermission(treeUri);
                SharedPreferences sp = getSharedPreferences("cfg", MODE_PRIVATE);
                sp.edit().putString("tree", treeUri.toString()).apply();
                scanFiles();
            }
        }
    }

    private void persistPermission(Uri treeUri) {
        try {
            int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            getContentResolver().takePersistableUriPermission(treeUri, flags);
        } catch (Exception ignored) {}
    }

    private Uri savedTree() {
        SharedPreferences sp = getSharedPreferences("cfg", MODE_PRIVATE);
        String s = sp.getString("tree", null);
        if (s == null) return null;
        try { return Uri.parse(s); } catch (Exception e) { return null; }
    }

    // ---------- 扫描 ----------
    private File musicDir() {
        File d = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_MUSIC), "NSF");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private void scanFiles() {
        nsfFiles.clear();
        // 1) 优先用已授权的 SAF 文件夹
        Uri tree = savedTree();
        if (tree != null) {
            try {
                List<String> names = listNsfViaSaf(tree);
                for (String n : names) {
                    File f = copyDocToCache(tree, n);
                    if (f != null) nsfFiles.add(f);
                }
            } catch (Exception ignored) {}
        }
        // 2) 回退：直接扫公共目录（root/旧系统可读时）
        if (nsfFiles.isEmpty()) {
            File dir = musicDir();
            File[] arr = dir.listFiles();
            if (arr != null) {
                for (File f : arr) {
                    if (f.isFile() && f.getName().toLowerCase().endsWith(".nsf")) nsfFiles.add(f);
                }
            }
        }

        List<String> names = new ArrayList<>();
        for (File f : nsfFiles) names.add(f.getName());
        ArrayAdapter<String> ad = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, names);
        fileSpinner.setAdapter(ad);

        if (nsfFiles.isEmpty()) {
            statusText.setText("未找到 NSF 文件。\n点“选择音乐文件夹”，选中 Music/NSF 所在目录；"
                    + "\n或从文件管理器长按 .nsf → 分享 → 本播放器。");
            currentFileIndex = -1;
        } else {
            statusText.setText("找到 " + nsfFiles.size() + " 个 NSF 文件");
            if (currentFileIndex < 0 || currentFileIndex >= nsfFiles.size()) currentFileIndex = 0;
            fileSpinner.setSelection(currentFileIndex);
            openFile(currentFileIndex);
            tryRestore();
        }
    }

    private List<String> listNsfViaSaf(Uri tree) {
        List<String> out = new ArrayList<>();
        try {
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree,
                    DocumentsContract.getTreeDocumentId(tree));
            Cursor c = getContentResolver().query(children,
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                    null, null, null);
            if (c != null) {
                while (c.moveToNext()) {
                    String n = c.getString(0);
                    if (n != null && n.toLowerCase().endsWith(".nsf")) out.add(n);
                }
                c.close();
            }
        } catch (Exception ignored) {}
        return out;
    }

    private File copyDocToCache(Uri tree, String displayName) {
        try {
            Uri docUri = DocumentsContract.buildDocumentUriUsingTree(tree,
                    DocumentsContract.getTreeDocumentId(tree) + "/" + displayName);
            // 有些实现需要用 find 得到真正的 documentId
            InputStream in = null;
            try {
                in = getContentResolver().openInputStream(docUri);
            } catch (Exception e) { in = null; }
            if (in == null) {
                // 遍历子文档拿到真实 uri
                Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree,
                        DocumentsContract.getTreeDocumentId(tree));
                Cursor c = getContentResolver().query(children,
                        new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                                DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                        null, null, null);
                if (c != null) {
                    while (c.moveToNext()) {
                        if (displayName.equals(c.getString(1))) {
                            Uri u = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0));
                            in = getContentResolver().openInputStream(u);
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
        try {
            File f = copyUriToCache(uri);
            if (f != null) {
                nsfFiles.clear();
                nsfFiles.add(f);
                List<String> names = new ArrayList<>();
                names.add(f.getName());
                ArrayAdapter<String> ad = new ArrayAdapter<>(this,
                        android.R.layout.simple_spinner_dropdown_item, names);
                fileSpinner.setAdapter(ad);
                currentFileIndex = 0;
                fileSpinner.setSelection(0);
                openFile(0);
            }
        } catch (Exception e) {
            statusText.setText("打开分享文件失败：" + e.getMessage());
        }
    }

    private File copyUriToCache(Uri uri) {
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
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private String queryName(Uri uri) {
        try {
            Cursor c = getContentResolver().query(uri, null, null, null, null);
            if (c != null) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (c.moveToFirst() && idx >= 0) {
                    String n = c.getString(idx);
                    c.close();
                    return n;
                }
                c.close();
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ---------- 播放 ----------
    private void openFile(int index) {
        if (index < 0 || index >= nsfFiles.size()) return;
        player.stop();
        File f = nsfFiles.get(index);
        if (!player.load(f.getAbsolutePath())) {
            statusText.setText("无法打开：" + f.getName());
            titles = new String[0];
            lengths = new int[0];
            trackCount = 0;
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
            if (ms <= 0) ms = 60000;
            lengths[i] = ms;
        }
        currentTrack = 0;
        statusText.setText(f.getName() + "  ·  共 " + trackCount + " 首");
        started = false;
        prepareCurrent();
    }

    private void prepareCurrent() {
        if (trackCount == 0) return;
        if (currentTrack < 0) currentTrack = trackCount - 1;
        if (currentTrack >= trackCount) currentTrack = 0;
        int len = lengths[currentTrack] / 1000;
        seekBar.setMax(len);
        int initPos = 0;
        if (restoreState && currentTrack < lengths.length) initPos = Math.min(restorePosMs / 1000, len);
        seekBar.setProgress(initPos);
        playBtn.setText("播放");
        trackTitle.setText(fmt(initPos) + " / " + fmt(len) + "  " + titles[currentTrack]);
    }

    private void playCurrent() {
        if (trackCount == 0) return;
        if (currentTrack < 0) currentTrack = trackCount - 1;
        if (currentTrack >= trackCount) currentTrack = 0;
        if (restoreState && restorePosMs > 0) { player.setStartMs(restorePosMs); restorePosMs = 0; restoreState = false; }
        player.play(currentTrack, lengths[currentTrack]);
        seekBar.setMax(lengths[currentTrack] / 1000);
        seekBar.setProgress(0);
        playBtn.setText("暂停");
        started = true;
    }

    private void togglePlay() {
        if (trackCount == 0) return;
        if (player.isPlaying()) { player.pause(); playBtn.setText("播放"); saveProgress(); }
        else if (player.isPaused()) { player.resume(); playBtn.setText("暂停"); }
        else { playCurrent(); }
    }

    private void step(int dir) {
        if (trackCount == 0) return;
        saveProgress();
        currentTrack += dir;
        if (started) playCurrent(); else prepareCurrent();
    }

    private void tick() {
        if (userDragging || trackCount == 0) return;
        long now2 = System.currentTimeMillis();
        if (now2 - lastSaveTime > 3000 && started && player.isPlaying()) {
            lastSaveTime = now2;
            saveProgress();
        }
        int pos = player.positionMs() / 1000;
        int len = lengths.length > currentTrack ? lengths[currentTrack] / 1000 : 0;
        if (len > 0) {
            seekBar.setProgress(Math.min(pos, len));
            trackTitle.setText(fmt(pos) + " / " + fmt(len) + "  " + titles[currentTrack]);
        }
    }

    private String fmt(int sec) {
        if (sec < 0) sec = 0;
        return String.format("%d:%02d", sec / 60, sec % 60);
    }

    private void saveProgress() {
        if (sp == null || nsfFiles.isEmpty() || currentFileIndex < 0) return;
        sp.edit()
          .putString("lastFile", nsfFiles.get(currentFileIndex).getName())
          .putInt("lastTrack", currentTrack)
          .putInt("lastPosMs", player.positionMs())
          .putBoolean("wasPlaying", started && player.isPlaying())
          .apply();
    }

    private void tryRestore() {
        if (nsfFiles.isEmpty()) return;
        String lastFile = sp.getString("lastFile", null);
        if (lastFile == null) return;
        int idx = -1;
        for (int i = 0; i < nsfFiles.size(); i++) {
            if (nsfFiles.get(i).getName().equals(lastFile)) { idx = i; break; }
        }
        if (idx < 0) return;
        currentFileIndex = idx;
        fileSpinner.setSelection(idx);
        openFile(idx);
        int lastTrack = sp.getInt("lastTrack", 0);
        int lastPos = sp.getInt("lastPosMs", 0);
        if (lastTrack >= 0 && lastTrack < trackCount) {
            currentTrack = lastTrack;
            restorePosMs = lastPos;
            restoreState = true;
            int len = lengths[currentTrack] / 1000;
            seekBar.setMax(len);
            seekBar.setProgress(Math.min(restorePosMs / 1000, len));
            playBtn.setText("播放");
            trackTitle.setText(fmt(Math.min(restorePosMs / 1000, len)) + " / " + fmt(len) + "  " + titles[currentTrack]);
        }
    }

    @Override protected void onDestroy() {
        saveProgress();
        super.onDestroy();
        player.release();
    }

    @Override protected void onPause() {
        saveProgress();
        super.onPause();
    }

    @Override public void onBackPressed() {
        saveProgress();
        long now = System.currentTimeMillis();
        if (now - lastBackTime < 1500) {
            moveTaskToBack(true);
            finish();
            System.exit(0);
        } else {
            lastBackTime = now;
            Toast.makeText(this, "再按一次退出", Toast.LENGTH_SHORT).show();
        }
    }
}