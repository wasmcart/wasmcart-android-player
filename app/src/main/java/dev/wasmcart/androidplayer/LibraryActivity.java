package dev.wasmcart.androidplayer;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * The carts in a folder the user picked (scanned recursively), and the way in
 * for .wasc files opened from anywhere else. Choosing a cart copies it to a real
 * file and starts PlayerActivity in its own process.
 */
public class LibraryActivity extends Activity {
    private static final String TAG = "wasmcart";
    private static final int PICK_FOLDER = 1;
    private static final String PREFS = "library";
    private static final String KEY_FOLDER = "folder";
    private static final long CART_CACHE_BYTES = 2L << 30;

    private static final class Cart {
        final Uri uri;
        final String fileName;
        final long size;
        final long modified;

        Cart(Uri uri, String fileName, long size, long modified) {
            this.uri = uri;
            this.fileName = fileName;
            this.size = size;
            this.modified = modified;
        }

        @Override
        public String toString() {
            return baseName(fileName).replace('_', ' ').replace('-', ' ');
        }
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private ArrayAdapter<Cart> adapter;
    private TextView status;
    private boolean starting = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());

        boolean opened = openFromIntent(getIntent());
        Uri folder = savedFolder();
        if (folder != null) rescan(folder);
        else if (!opened) pickFolder();
        else status.setText("Choose a folder to list your carts.");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        openFromIntent(intent);
    }

    @Override
    protected void onDestroy() {
        worker.shutdown();
        super.onDestroy();
    }

    private View buildUi() {
        TextView title = new TextView(this);
        title.setText("wasmcart");
        title.setTextSize(24);

        Button choose = new Button(this);
        choose.setText("Folder");
        choose.setOnClickListener(v -> pickFolder());

        Button rescan = new Button(this);
        rescan.setText("Rescan");
        rescan.setOnClickListener(v -> {
            Uri folder = savedFolder();
            if (folder != null) rescan(folder);
            else pickFolder();
        });

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        header.addView(choose);
        header.addView(rescan);

        status = new TextView(this);

        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        ListView list = new ListView(this);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> start(adapter.getItem(position)));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(header);
        root.addView(status);
        root.addView(list, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        // Target SDK 36 draws edge to edge; keep the list off the system bars.
        int pad = Math.round(16 * getResources().getDisplayMetrics().density);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(bars.left + pad, bars.top + pad, bars.right + pad, bars.bottom);
            return WindowInsets.CONSUMED;
        });
        return root;
    }

    // ─── Folder ─────────────────────────────────────────────────────────────

    private Uri savedFolder() {
        String s = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_FOLDER, null);
        return s != null ? Uri.parse(s) : null;
    }

    private void pickFolder() {
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), PICK_FOLDER);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_FOLDER || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri folder = data.getData();
        getContentResolver().takePersistableUriPermission(folder, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_FOLDER, folder.toString()).apply();
        rescan(folder);
    }

    private void rescan(Uri folder) {
        status.setText("Scanning…");
        worker.execute(() -> {
            List<Cart> found = new ArrayList<>();
            String error = null;
            try {
                scan(folder, DocumentsContract.getTreeDocumentId(folder), found);
            } catch (Exception e) {
                Log.e(TAG, "scan: " + e);
                error = "Could not read that folder. Choose it again.";
            }
            found.sort(Comparator.comparing(c -> c.toString().toLowerCase(Locale.ROOT)));
            String message = error != null ? error
                    : found.isEmpty() ? "No .wasc files in that folder." : found.size() + " carts";
            runOnUiThread(() -> {
                adapter.clear();
                adapter.addAll(found);
                status.setText(message);
            });
        });
    }

    private void scan(Uri tree, String documentId, List<Cart> out) {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId);
        String[] columns = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        };
        try (Cursor c = getContentResolver().query(children, columns, null, null, null)) {
            if (c == null) throw new IllegalStateException("no cursor for " + children);
            while (c.moveToNext()) {
                String id = c.getString(0);
                String name = c.getString(1);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2))) {
                    scan(tree, id, out);
                } else if (name != null && name.toLowerCase(Locale.ROOT).endsWith(".wasc")) {
                    out.add(new Cart(DocumentsContract.buildDocumentUriUsingTree(tree, id),
                            name, c.getLong(3), c.getLong(4)));
                }
            }
        }
    }

    // ─── Opening a cart ─────────────────────────────────────────────────────

    private boolean openFromIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction()) || intent.getData() == null) return false;
        Uri uri = intent.getData();
        String name = "cart.wasc";
        long size = -1;
        try (Cursor c = getContentResolver().query(uri,
                new String[] { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE }, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                if (!c.isNull(0)) name = c.getString(0);
                if (!c.isNull(1)) size = c.getLong(1);
            }
        } catch (Exception e) {
            Log.w(TAG, "no details for " + uri + ": " + e);
        }
        start(new Cart(uri, name, size, 0));
        return true;
    }

    private void start(Cart cart) {
        if (cart == null || starting) return;
        starting = true;
        status.setText("Opening " + cart + "…");
        worker.execute(() -> {
            Intent player = null;
            String error = null;
            try {
                player = prepare(cart);
                waitForPlayerExit();
            } catch (Exception e) {
                Log.e(TAG, "open " + cart.uri + ": " + e);
                error = cart + " could not be opened: " + e.getMessage();
            }
            Intent launch = player;
            String failure = error;
            runOnUiThread(() -> {
                starting = false;
                status.setText(adapter.getCount() > 0 ? adapter.getCount() + " carts" : "");
                if (launch != null) startActivity(launch);
                else new AlertDialog.Builder(this)
                        .setTitle("Cannot open cart")
                        .setMessage(failure)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            });
        });
    }

    private Intent prepare(Cart cart) throws Exception {
        File dir = new File(getCacheDir(), "carts");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("no cache directory");
        File file = new File(dir, sha256(cart.uri + "|" + cart.size + "|" + cart.modified) + ".wasc");
        if (cart.size < 0 || file.length() != cart.size) copy(cart.uri, file);
        //noinspection ResultOfMethodCallIgnored
        file.setLastModified(System.currentTimeMillis());
        trimCache(dir, file);

        int[] size = declaredSize(file);

        // The same cart keeps its save however it was opened, wherever it lives.
        File saves = new File(getFilesDir(), "saves");
        if (!saves.isDirectory() && !saves.mkdirs()) throw new IllegalStateException("no saves directory");
        String key = baseName(cart.fileName).replaceAll("[^A-Za-z0-9._ -]", "_") + "-" + file.length();

        File controls = new File(getFilesDir(), "controls");
        if (!controls.isDirectory() && !controls.mkdirs()) throw new IllegalStateException("no controls directory");

        File code = new File(getCacheDir(), "code");
        if (!code.isDirectory() && !code.mkdirs()) throw new IllegalStateException("no code cache directory");

        return new Intent(this, PlayerActivity.class)
                .putExtra(PlayerActivity.EXTRA_CART, file.getAbsolutePath())
                .putExtra(PlayerActivity.EXTRA_SAVE, new File(saves, key + ".sav").getAbsolutePath())
                .putExtra(PlayerActivity.EXTRA_CODE_CACHE, code.getAbsolutePath())
                .putExtra(PlayerActivity.EXTRA_DECLARES_SIZE, size[0] > 0 && size[1] > 0)
                .putExtra(PlayerActivity.EXTRA_CONTROLS, new File(controls, key + ".cfg").getAbsolutePath());
    }

    private void copy(Uri uri, File dest) throws Exception {
        File tmp = new File(dest.getAbsolutePath() + ".tmp");
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(tmp)) {
            if (in == null) throw new IllegalStateException("the file could not be read");
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        if (!tmp.renameTo(dest)) throw new IllegalStateException("rename failed: " + dest);
    }

    /** Oldest copies go first, never the one about to be played. */
    private static void trimCache(File dir, File keep) {
        File[] files = dir.listFiles();
        if (files == null) return;
        long total = 0;
        for (File f : files) total += f.length();
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (File f : files) {
            if (total <= CART_CACHE_BYTES) return;
            if (f.equals(keep)) continue;
            long size = f.length();
            if (f.delete()) total -= size;
        }
    }

    /**
     * The width and height the cart declares, 0 for none. The host offers a
     * declared size to the cart; the player offers the screen to the others.
     * Throws when the file is not a cart at all: the intent filters have to
     * accept any zip or unknown binary, since that is what a .wasc arrives as.
     */
    private static int[] declaredSize(File cart) throws Exception {
        try (ZipFile zip = new ZipFile(cart)) {
            ZipEntry manifest = zip.getEntry("manifest.json");
            if (manifest == null) {
                if (zip.getEntry("cart.wasm") == null) throw new IllegalArgumentException("it is not a .wasc cart");
                return new int[] { 0, 0 };
            }
            try (InputStream in = zip.getInputStream(manifest)) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buf = new byte[1 << 14];
                int n;
                while ((n = in.read(buf)) > 0) bytes.write(buf, 0, n);
                JSONObject json = new JSONObject(bytes.toString("UTF-8"));
                return new int[] { json.optInt("width"), json.optInt("height") };
            }
        } catch (ZipException e) {
            throw new IllegalArgumentException("it is not a .wasc cart");
        }
    }

    /**
     * A player still shutting down would take a cart started now down with it:
     * Android reuses a live process, and that one is about to kill itself. Give
     * it time to write its save, then make sure.
     */
    private void waitForPlayerExit() throws InterruptedException {
        for (int i = 0; i < 60; i++) {
            if (playerPid() == 0) return;
            Thread.sleep(50);
        }
        int pid = playerPid();
        if (pid != 0) android.os.Process.killProcess(pid);
        for (int i = 0; i < 20 && playerPid() != 0; i++) Thread.sleep(50);
    }

    private int playerPid() {
        ActivityManager am = getSystemService(ActivityManager.class);
        List<ActivityManager.RunningAppProcessInfo> processes = am.getRunningAppProcesses();
        if (processes == null) return 0;
        String name = getPackageName() + ":player";
        for (ActivityManager.RunningAppProcessInfo p : processes) {
            if (name.equals(p.processName)) return p.pid;
        }
        return 0;
    }

    private static String baseName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static String sha256(String s) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8"));
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) hex.append(String.format("%02x", b));
        return hex.toString();
    }
}
