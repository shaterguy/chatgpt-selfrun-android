package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.database.DatabaseErrorHandler;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONObject;
import java.io.File;

/** App-private transactional journal. The task snapshot, event receipt and turn record commit together. */
final class SelfRun3Ledger extends SQLiteOpenHelper {
    private static final class PreserveCorruptionHandler implements DatabaseErrorHandler {
        @Override public void onCorruption(SQLiteDatabase dbObj) {
            throw new SQLiteException("SelfRun 3 ledger corruption detected; database preserved for recovery");
        }
    }

    SelfRun3Ledger(Context context) {
        super(context.getApplicationContext(),
                new File(context.getNoBackupFilesDir(), "selfrun3-ledger.db").getAbsolutePath(),
                null, 1, new PreserveCorruptionHandler());
        setWriteAheadLoggingEnabled(true);
    }
    @Override public void onConfigure(SQLiteDatabase db) { db.setForeignKeyConstraintsEnabled(true); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE tasks(task_id TEXT PRIMARY KEY NOT NULL, snapshot TEXT NOT NULL, revision INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE events(task_id TEXT NOT NULL,event_id TEXT NOT NULL,kind TEXT NOT NULL,turn_id TEXT NOT NULL,PRIMARY KEY(task_id,event_id),FOREIGN KEY(task_id) REFERENCES tasks(task_id))");
        db.execSQL("CREATE TABLE turns(task_id TEXT NOT NULL,ordinal INTEGER NOT NULL,turn_id TEXT NOT NULL,document_id TEXT NOT NULL,result TEXT NOT NULL,committed INTEGER NOT NULL,PRIMARY KEY(task_id,ordinal),UNIQUE(task_id,turn_id),FOREIGN KEY(task_id) REFERENCES tasks(task_id))");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int from, int to) { throw new IllegalStateException("explicit ledger migration required"); }
    @Override public void onDowngrade(SQLiteDatabase db, int from, int to) { throw new IllegalStateException("ledger downgrade forbidden"); }
    synchronized SelfRun3Engine.State ensure(SelfRun3Engine.State initial) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.execSQL("INSERT OR IGNORE INTO tasks(task_id,snapshot,revision) VALUES(?,?,0)", new Object[]{initial.taskId(), initial.json().toString()});
            SelfRun3Engine.State current = read(db, initial.taskId());
            db.setTransactionSuccessful(); return current;
        } finally { db.endTransaction(); }
    }
    synchronized SelfRun3Engine.State load(String taskId) { return read(getReadableDatabase(), taskId); }
    synchronized SelfRun3Engine.State apply(SelfRun3Engine.Event event) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            SelfRun3Engine.State before = read(db, event.taskId);
            if (before == null) throw new IllegalStateException("task not found");
            try (Cursor c = db.rawQuery("SELECT 1 FROM events WHERE task_id=? AND event_id=?", new String[]{event.taskId, event.id})) {
                if (c.moveToFirst()) { db.setTransactionSuccessful(); return before; }
            }
            SelfRun3Engine.State after = SelfRun3Engine.reduce(before, event);
            if (after == before) { db.setTransactionSuccessful(); return before; }
            db.execSQL("INSERT INTO events(task_id,event_id,kind,turn_id) VALUES(?,?,?,?)", new Object[]{event.taskId,event.id,event.kind.name(),event.turnId});
            db.execSQL("UPDATE tasks SET snapshot=?,revision=revision+1 WHERE task_id=?", new Object[]{after.json().toString(), event.taskId});
            if (event.kind == SelfRun3Engine.Kind.RESULT && after.hasResult()) saveTurn(db, after, false);
            if (event.kind == SelfRun3Engine.Kind.COMMIT) saveTurn(db, before, true);
            db.setTransactionSuccessful(); return after;
        } finally { db.endTransaction(); }
    }
    synchronized int eventCount(String taskId) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM events WHERE task_id=?", new String[]{taskId})) { return c.moveToFirst() ? c.getInt(0) : 0; }
    }
    synchronized String committedResult(String taskId, int turn) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT result FROM turns WHERE task_id=? AND ordinal=? AND committed=1", new String[]{taskId,String.valueOf(turn)})) { return c.moveToFirst() ? c.getString(0) : ""; }
    }
    private static SelfRun3Engine.State read(SQLiteDatabase db, String taskId) {
        try (Cursor c = db.rawQuery("SELECT snapshot FROM tasks WHERE task_id=?", new String[]{taskId})) {
            if (!c.moveToFirst()) return null;
            try { return new SelfRun3Engine.State(new JSONObject(c.getString(0))); }
            catch (Exception e) { throw new IllegalStateException("ledger snapshot invalid; data preserved", e); }
        }
    }
    private static void saveTurn(SQLiteDatabase db, SelfRun3Engine.State s, boolean committed) {
        db.execSQL("INSERT OR REPLACE INTO turns(task_id,ordinal,turn_id,document_id,result,committed) VALUES(?,?,?,?,?,?)",
                new Object[]{s.taskId(),s.turn(),s.turnId(),s.resource("resultDocumentId"),s.text("result"),committed?1:0});
    }
}
