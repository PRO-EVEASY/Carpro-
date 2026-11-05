package dev.local.jaecoorecorder;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Worker-thread-only storage tests; no USB, media parsing or vehicle settings. Run with recording stopped. */
final class StorageSelfTest {
    private static final long MIB = 1024L * 1024L;
    private final JSONArray cases = new JSONArray();
    private int passed, failed;

    static JSONObject run(Context context) {
        return new StorageSelfTest().execute(context.getApplicationContext());
    }

    private JSONObject execute(Context app) {
        long start = SystemClock.elapsedRealtime();
        JSONObject report = new JSONObject();
        File root = null;
        try {
            File parent = new File(app.getCacheDir(), "dvr-selftest");
            root = child(parent, "run-" + UUID.randomUUID().toString());
            mkdir(root);
            Context isolated = new IsolatedContext(app, child(root, "loop"));
            DvrSettings settings = settings(isolated);
            Storage storage = new Storage(isolated);
            check("isolatedFilesDir", isolated.getFilesDir().getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator));
            check("isolatedMoviesDir", storage.internal().getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator));
            Storage.Destination a = clip(storage, 220 * MIB, false);
            Storage.Destination b = clip(storage, 220 * MIB, true);
            Storage.Destination c = clip(storage, 60 * MIB, false);
            check("syntheticSizesNoLargeWrites", a.file.length() == 4 && b.file.length() == 4 && c.file.length() == 4);
            check("galleryNewestFirst", storage.records().length() == 3 && c.uri.toString().equals(storage.records().getJSONObject(0).getString("uri")));
            storage.cleanup(false);
            check("loopEvictsOldestUnlocked", !a.file.exists() && b.file.exists() && c.file.exists());
            check("quotaAndLockedUsage", storage.usage(false) == 280 * MIB && storage.countLocked() == 1);
            check("indexPersistentAcrossStorageInstances", new Storage(isolated).records().length() == 2);
            check("lockedDeleteRejected", throwsIo(() -> storage.deleteRecord(b.uri.toString())) && b.file.exists());
            check("unlockThenDelete", storage.setLocked(b.uri.toString(), false) && storage.deleteRecord(b.uri.toString()) && !b.file.exists());
            File sentinel = child(root, "outside-index.txt");
            write(sentinel, new byte[]{7,8,9});
            check("unindexedFileUntouched", !storage.deleteRecord(android.net.Uri.fromFile(sentinel).toString()) && sentinel.exists());
            Storage.Destination active = storage.create(false);
            try {
                storage.cleanup(false);
                check("activeFilePreservedAndHiddenFromGallery", active.file.exists() && storage.records().length() == 1);
                check("activeDeleteRejected", throwsIo(() -> storage.deleteRecord(active.uri.toString())) && active.file.exists());
                boolean secondRejected = false;
                try { Storage.Destination unexpected = storage.create(false); unexpected.remove(); }
                catch (IOException expected) { secondRejected = true; }
                check("secondWriterRejected", secondRejected);
                check("currentClipLockPersisted", storage.setCurrentLocked(true) && storage.countLocked() == 1);
            } finally { active.remove(); }
            check("failedClipRemovedFromJournal", storage.countLocked() == 0 && storage.records().length() == 1);

            Context lockedContext = new IsolatedContext(app, child(root, "all-locked"));
            settings(lockedContext);
            Storage lockedStorage = new Storage(lockedContext);
            Storage.Destination locked = clip(lockedStorage, 500 * MIB, true);
            check("allLockedStopsWithoutDeletion", throwsIo(() -> { lockedStorage.cleanup(false); return true; }) && locked.file.exists());

            Context noLoopContext = new IsolatedContext(app, child(root, "loop-disabled"));
            DvrSettings noLoopSettings = settings(noLoopContext);
            Storage noLoop = new Storage(noLoopContext);
            Storage.Destination retained = clip(noLoop, 500 * MIB, false);
            noLoopSettings.set("loopRecording", false);
            check("loopDisabledStopsWithoutDeletion", throwsIo(() -> { noLoop.cleanup(false); return true; }) && retained.file.exists());
            check("appPreferencesUnchangedByIsolation", !isolated.getSharedPreferences("dvr_settings", 0).equals(app.getSharedPreferences("dvr_settings", 0)));
            report.put("scope", "isolated cache files and in-memory preferences; no USB/hardware/media extraction");
        } catch (Throwable error) {
            failed++;
            try { cases.put(new JSONObject().put("name", "unexpectedFailure").put("passed", false).put("error", error.toString())); } catch (Exception ignored) { }
        }
        try {
            report.put("passed", passed); report.put("failed", failed); report.put("success", failed == 0);
            report.put("cases", cases); report.put("elapsedMs", SystemClock.elapsedRealtime() - start);
            report.put("testRoot", root == null ? "" : root.getAbsolutePath());
            report.put("syntheticVideo", true); report.put("realVideoValidityTested", false);
            report.put("precondition", "recording stopped; sufficient device free space for reserve policy");
            AtomicFile output = new AtomicFile(new File(app.getFilesDir(), "storage-selftest.json"));
            FileOutputStream stream = null;
            try { stream = output.startWrite(); stream.write(report.toString(2).getBytes(StandardCharsets.UTF_8)); output.finishWrite(stream); stream = null; }
            finally { if (stream != null) output.failWrite(stream); }
        } catch (Exception error) {
            try { report.put("reportWriteError", error.toString()); } catch (Exception ignored) { }
        }
        return report;
    }

    private void check(String name, boolean result) throws Exception {
        cases.put(new JSONObject().put("name", name).put("passed", result));
        if (result) passed++; else failed++;
    }
    private interface IoAction { boolean run() throws Exception; }
    private static boolean throwsIo(IoAction action) throws Exception { try { action.run(); return false; } catch (IOException expected) { return true; } }
    private static DvrSettings settings(Context context) throws Exception {
        DvrSettings settings = new DvrSettings(context);
        JSONObject values = new JSONObject();
        values.put("profile", "custom"); values.put("width", 1280); values.put("height", 960);
        values.put("fps", 25); values.put("bitrate", 1000000); values.put("segmentSeconds", 5);
        values.put("internalQuotaGb", 0.5); values.put("reserveMb", 512); values.put("loopRecording", true);
        values.put("autoStart", false); settings.apply(values);
        return settings;
    }
    private static Storage.Destination clip(Storage storage, long indexedBytes, boolean locked) throws Exception {
        Storage.Destination destination = storage.create(false);
        try {
            write(destination.file, new byte[]{0x44,0x56,0x52,0x54});
            if (locked && !storage.setCurrentLocked(true)) throw new IOException("Test clip lock failed");
            storage.completed(destination, indexedBytes, 5);
            return destination;
        } finally { destination.close(); }
    }
    private static void write(File file, byte[] data) throws IOException { try (FileOutputStream out = new FileOutputStream(file)) { out.write(data); } }
    private static void mkdir(File directory) throws IOException { if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create isolated test directory"); }
    private static File child(File parent, String name) throws IOException {
        File canonicalParent = parent.getCanonicalFile();
        File result = new File(canonicalParent, name).getCanonicalFile();
        if (!result.getPath().startsWith(canonicalParent.getPath() + File.separator)) throw new IOException("Test path escaped its parent");
        return result;
    }

    private static final class IsolatedContext extends ContextWrapper {
        private final File root;
        private final Map<String, SharedPreferences> preferences = new HashMap<>();
        IsolatedContext(Context base, File path) throws IOException { super(base); root = path.getCanonicalFile(); mkdir(root); }
        @Override public Context getApplicationContext() { return this; }
        @Override public File getFilesDir() { return directory("files"); }
        @Override public File getCacheDir() { return directory("cache"); }
        @Override public File getExternalFilesDir(String type) { return directory("external-" + (type == null ? "root" : type.replaceAll("[^A-Za-z0-9_-]", "_"))); }
        private File directory(String name) { try { File result = child(root, name); mkdir(result); return result; } catch (IOException error) { throw new IllegalStateException(error); } }
        @Override public synchronized SharedPreferences getSharedPreferences(String name, int mode) {
            SharedPreferences found = preferences.get(name);
            if (found == null) { found = new MemoryPreferences(); preferences.put(name, found); }
            return found;
        }
    }

    /** Avoid writing real app SharedPreferences even when the wrapped context is reused. */
    private static final class MemoryPreferences implements SharedPreferences {
        private final Map<String, Object> values = new HashMap<>();
        private final Set<OnSharedPreferenceChangeListener> listeners = new HashSet<>();
        @Override public synchronized Map<String, ?> getAll() { return new HashMap<>(values); }
        @Override public synchronized String getString(String key, String fallback) { Object value = values.get(key); return value == null ? fallback : (String) value; }
        @SuppressWarnings("unchecked") @Override public synchronized Set<String> getStringSet(String key, Set<String> fallback) { Object value = values.get(key); return value == null ? fallback : new HashSet<>((Set<String>)value); }
        @Override public synchronized int getInt(String key, int fallback) { Object value = values.get(key); return value == null ? fallback : (Integer)value; }
        @Override public synchronized long getLong(String key, long fallback) { Object value = values.get(key); return value == null ? fallback : (Long)value; }
        @Override public synchronized float getFloat(String key, float fallback) { Object value = values.get(key); return value == null ? fallback : (Float)value; }
        @Override public synchronized boolean getBoolean(String key, boolean fallback) { Object value = values.get(key); return value == null ? fallback : (Boolean)value; }
        @Override public synchronized boolean contains(String key) { return values.containsKey(key); }
        @Override public Editor edit() { return new MemoryEditor(); }
        @Override public synchronized void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener value) { listeners.add(value); }
        @Override public synchronized void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener value) { listeners.remove(value); }
        private final class MemoryEditor implements Editor {
            private final Map<String,Object> updates = new HashMap<>(); private boolean clear;
            @Override public Editor putString(String key, String value) { updates.put(key,value); return this; }
            @Override public Editor putStringSet(String key, Set<String> value) { updates.put(key,value == null ? null : new HashSet<>(value)); return this; }
            @Override public Editor putInt(String key,int value) { updates.put(key,value); return this; }
            @Override public Editor putLong(String key,long value) { updates.put(key,value); return this; }
            @Override public Editor putFloat(String key,float value) { updates.put(key,value); return this; }
            @Override public Editor putBoolean(String key,boolean value) { updates.put(key,value); return this; }
            @Override public Editor remove(String key) { updates.put(key,null); return this; }
            @Override public Editor clear() { clear = true; return this; }
            @Override public boolean commit() {
                Set<OnSharedPreferenceChangeListener> notify;
                synchronized (MemoryPreferences.this) {
                    if (clear) values.clear();
                    for (Map.Entry<String,Object> entry : updates.entrySet()) { if (entry.getValue() == null) values.remove(entry.getKey()); else values.put(entry.getKey(),entry.getValue()); }
                    notify = new HashSet<>(listeners);
                }
                for (String key : updates.keySet()) for (OnSharedPreferenceChangeListener listener : notify) listener.onSharedPreferenceChanged(MemoryPreferences.this,key);
                return true;
            }
            @Override public void apply() { commit(); }
        }
    }
}
