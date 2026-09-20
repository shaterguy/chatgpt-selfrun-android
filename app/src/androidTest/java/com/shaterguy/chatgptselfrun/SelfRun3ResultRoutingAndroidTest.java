package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteException;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.Proxy;
import java.util.UUID;
import static org.junit.Assert.*;

/** Real SQLite transaction failures must never become Result repair requests. */
@RunWith(AndroidJUnit4.class)
public final class SelfRun3ResultRoutingAndroidTest {
    private static final String INPUT_PREFS="selfrun_drive_user_next_input";
    private final Context context=ApplicationProvider.getApplicationContext();

    @Test public void repairPersistsOriginalResultAndInputAcrossLedgerRecreation() throws Exception {
        Fixture f=new Fixture(true);
        SelfRun3Engine.State repair;
        try(SelfRun3Ledger ledger=new SelfRun3Ledger(context)) {
            repair=SelfRun3UserInput.commit(context,f.store,ledger,ledger.load(f.id));
            assertEquals("REPAIR",repair.text("executionKind"));
            assertEquals(f.original,ledger.committedResult(f.id,1));
            assertEquals("pending input",SelfRun3UserInput.snapshot(context,f.id).text);
            assertEquals(7,SelfRun3UserInput.snapshot(context,f.id).revision);
            int events=ledger.eventCount(f.id);
            SelfRun3Engine.State duplicate=SelfRun3UserInput.commit(context,f.store,ledger,f.source);
            assertEquals(repair.turnId(),duplicate.turnId()); assertEquals(events,ledger.eventCount(f.id));
        }
        try(SelfRun3Ledger ledger=new SelfRun3Ledger(context)) {
            SelfRun3Engine.State restored=ledger.load(f.id);
            assertEquals(repair.turnId(),restored.turnId());
            assertEquals(f.source.config().toString(),restored.config().toString());
            assertEquals(f.source.resource("resultDocumentId"),restored.text("repairTargetDocumentId"));
            assertTrue(restored.resource("resultDocumentId").isEmpty());
            assertEquals(f.original,ledger.committedResult(f.id,1));
        }
    }

    @Test public void sqliteWriteFailureRollsBackRepairAndPreservesResult() throws Exception {
        Fixture f=new Fixture(true);
        try(SelfRun3Ledger ledger=new SelfRun3Ledger(context)) {
            String before=ledger.load(f.id).json().toString(); int count=ledger.eventCount(f.id);
            ledger.getWritableDatabase().execSQL("CREATE TEMP TRIGGER fail_commit BEFORE INSERT ON events "
                    +"WHEN NEW.kind='COMMIT' BEGIN SELECT RAISE(ABORT, 'injected ledger failure'); END");
            try {
                assertThrows(SQLiteException.class,()->SelfRun3UserInput.commit(context,f.store,ledger,f.source));
                assertEquals(before,ledger.load(f.id).json().toString());
                assertEquals(count,ledger.eventCount(f.id));
                assertEquals("",ledger.committedResult(f.id,1));
                assertEquals("pending input",SelfRun3UserInput.snapshot(context,f.id).text);
            } finally { ledger.getWritableDatabase().execSQL("DROP TRIGGER fail_commit"); }
        }
    }

    @Test public void staleInputTaskIsAnInternalErrorWithoutRepair() throws Exception {
        Fixture f=new Fixture(true);
        f.store.start("other-"+UUID.randomUUID(),"CHAT",SelfRunScript.GENERAL_CHAT_URL,"other");
        try(SelfRun3Ledger ledger=new SelfRun3Ledger(context)) {
            int before=ledger.eventCount(f.id);
            IllegalStateException error=assertThrows(IllegalStateException.class,
                    ()->SelfRun3UserInput.commit(context,f.store,ledger,f.source));
            assertEquals("STALE_INPUT_TASK",error.getMessage()); assertEquals(before,ledger.eventCount(f.id));
            assertEquals("NORMAL",ledger.load(f.id).text("executionKind"));
        }
    }

    @Test public void inputStorageFailureDoesNotBecomeRepairAfterNormalCommit() throws Exception {
        Fixture f=new Fixture(false);
        Context failing=new ContextWrapper(context) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name,int mode) {
                SharedPreferences real=context.getSharedPreferences(name,mode);
                if(!INPUT_PREFS.equals(name)) return real;
                return (SharedPreferences)Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[]{SharedPreferences.class},(proxy,method,args)-> {
                    if(!"edit".equals(method.getName())) return method.invoke(real,args);
                    return Proxy.newProxyInstance(getClass().getClassLoader(),
                            new Class<?>[]{SharedPreferences.Editor.class},(editor,editMethod,editArgs)-> {
                        if("commit".equals(editMethod.getName())) return false;
                        if("apply".equals(editMethod.getName())) return null;
                        return editor;
                    });
                });
            }
        };
        try(SelfRun3Ledger ledger=new SelfRun3Ledger(context)) {
            IllegalStateException error=assertThrows(IllegalStateException.class,
                    ()->SelfRun3UserInput.commit(failing,f.store,ledger,f.source));
            assertEquals("INPUT_CONSUME_COMMIT_FAILED",error.getMessage());
            SelfRun3Engine.State after=ledger.load(f.id);
            assertEquals("NORMAL",after.text("executionKind"));
            assertEquals(2,after.turn()); assertTrue(after.text("repairReason").isEmpty());
        }
    }

    private final class Fixture {
        final String id="routing-"+UUID.randomUUID();
        final SelfRunStore store=new SelfRunStore(context);
        final SelfRun3Engine.State source;
        final String original;
        Fixture(boolean broken) throws Exception {
            store.bindBaseFolder("acct_123","abcdefgh","Runs","",1L);
            store.start(id,"CHAT",SelfRunScript.GENERAL_CHAT_URL,"routing test");
            context.getSharedPreferences(INPUT_PREFS,Context.MODE_PRIVATE).edit().clear()
                    .putString("runId",id).putString("text","pending input").putLong("revision",7L).commit();
            JSONObject config=new JSONObject().put("mode","CHAT").put("taskMode","CHAT")
                    .put("model","gpt-5-6-thinking").put("reasoning","medium");
            SelfRun3Engine.State state=SelfRun3Engine.create(id,id+":turn:1",config);
            try(SelfRun3Ledger ledger=new SelfRun3Ledger(context)) {
                state=ledger.ensure(state);
                state=apply(ledger,state,SelfRun3Engine.Kind.SETUP_DONE,new JSONObject());
                state=apply(ledger,state,SelfRun3Engine.Kind.RESOURCE,new JSONObject().put("key","resultDocumentId").put("value","original-result"));
                state=apply(ledger,state,SelfRun3Engine.Kind.TURN_READY,new JSONObject().put("prompt","work").put("inputRevision",7L).put("inputText","pending input"));
                JSONObject result=SelfRun3Engine.emptyResult(state).put("committed",true).put("status","CONTINUE");
                if(!broken) result.put("next_profile",new JSONObject().put("mode","CHAT").put("model","gpt-5-6-thinking").put("reasoning","medium"));
                original=result.toString();
                source=apply(ledger,state,SelfRun3Engine.Kind.RESULT,new JSONObject().put("text",original));
            }
        }
    }
    private static SelfRun3Engine.State apply(SelfRun3Ledger ledger,SelfRun3Engine.State state,SelfRun3Engine.Kind kind,JSONObject payload) {
        return ledger.apply(new SelfRun3Engine.Event(UUID.randomUUID().toString(),kind,state.taskId(),state.turnId(),payload));
    }
}
