package com.shaterguy.chatgptselfrun;
import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class SelfRun3ParallelLedgerAndroidTest {
    private static SelfRun3Ledger ledger;
    private Context context;
    @Before public void before() {
        context=ApplicationProvider.getApplicationContext();
        cleanup(); ProfileRegistry.resetForTests(); ledger=new SelfRun3Ledger(context);
    }
    @After public void after() { if(ledger!=null) ledger.close(); cleanup(); }
    private void cleanup() {
        for(String suffix:new String[]{"","-wal","-shm","-journal"}) {
            File f=new File(context.getNoBackupFilesDir(),"selfrun3-ledger.db"+suffix);
            if(f.exists()) assertTrue(f.delete());
        }
    }
    @Test public void parallelClaimsResultsAndMergeSurviveRealDatabaseReopen() {
        SelfRun3Engine.State root=dispatch(wave());
        assertEquals("B",root.text("branchId"));
        ledger.close(); ledger=new SelfRun3Ledger(context);
        root=ledger.load("task"); assertEquals("B",root.text("branchId"));
        String a=branch(root,"A").turnId(), b=branch(root,"B").turnId();
        assertTrue(ledger.loadExecution("task",a).flag("dispatchObserved"));
        assertFalse(ledger.loadExecution("task",b).flag("sendClaimed"));
        root=dispatch(root); assertEquals(2,SelfRun3Engine.activeCount(root));
        root=completeBranch(root,a,"COMPLETE");
        ledger.close(); ledger=new SelfRun3Ledger(context); root=ledger.load("task");
        assertEquals(1,SelfRun3Engine.activeCount(root));
        assertFalse(ledger.committedResult("task",2).isEmpty());
        root=completeBranch(root,b,"PARTIAL");
        ledger.close(); ledger=new SelfRun3Ledger(context); root=ledger.load("task");
        assertEquals("PARALLEL_MERGE",root.text("executionKind"));
        assertEquals(4,root.history().length()); assertEquals(2,root.json().optJSONArray("mergedFrom").length());
        assertEquals(0,SelfRun3Engine.activeCount(root)); assertFalse(root.flag("sendClaimed"));
        assertEquals(0L,root.time("lastConsumedInputRevision"));
    }
    private static SelfRun3Engine.State wave() {
        SelfRun3Engine.State s=dispatch(initial()); JSONObject r=result(s); put(r,"next_execution",plan());
        s=accept(s,s.turnId(),r); return event(s,s.turnId(),SelfRun3Engine.Kind.COMMIT,new JSONObject());
    }
    private static SelfRun3Engine.State initial() {
        JSONObject c=new JSONObject(); put(c,"mode","CHAT"); put(c,"reasoning","medium");
        SelfRun3Engine.State s=ledger.ensure(SelfRun3Engine.create("task","task:turn:1",c));
        s=resource(s,"folderId","folder"); s=resource(s,"requirementDocumentId","requirements");
        return event(s,s.turnId(),SelfRun3Engine.Kind.SETUP_DONE,new JSONObject());
    }
    private static SelfRun3Engine.State dispatch(SelfRun3Engine.State s) {
        String id=s.turnId(); s=resource(s,"resultDocumentId","doc-"+id);
        JSONObject p=new JSONObject(); put(p,"prompt","bootstrap"); put(p,"inputRevision",0);
        s=event(s,id,SelfRun3Engine.Kind.TURN_READY,p);
        s=event(s,id,SelfRun3Engine.Kind.CLAIM_SEND,new JSONObject());
        put(p,"requestId",s.execution(id).requestId()); return event(s,id,SelfRun3Engine.Kind.STARTED,p);
    }
    private static SelfRun3Engine.State completeBranch(SelfRun3Engine.State root,String id,String status) {
        root=accept(root,id,branchResult(root.execution(id),status));
        return event(root,id,SelfRun3Engine.Kind.COMMIT,new JSONObject());
    }
    private static JSONObject branchResult(SelfRun3Engine.State s,String status) {
        JSONObject r=SelfRun3Engine.emptyResult(s); put(r,"committed",true); put(r,"status",status); put(r,"handoff",handoff());
        JSONObject b=new JSONObject(); put(b,"status",status); put(r,"branch_result",b);
        if("USER_ACTION_REQUIRED".equals(status)) put(r,"reason","user login needed");
        return r;
    }
    private static JSONObject result(SelfRun3Engine.State s) {
        JSONObject r=SelfRun3Engine.emptyResult(s); put(r,"committed",true); put(r,"status","CONTINUE");
        put(r,"phase_completed","PLAN"); put(r,"next_phase","WORK"); put(r,"handoff",handoff()); return r;
    }
    private static JSONObject handoff() {
        JSONObject h=new JSONObject(); put(h,"objective","test"); put(h,"next_action","continue");
        for(String k:new String[]{"completed","remaining","evidence","constraints","requirements","decisions","assumptions","materials","external_state","verification_state","do_not_repeat"}) put(h,k,new JSONArray());
        return h;
    }
    private static JSONObject plan() {
        JSONObject p=new JSONObject(); put(p,"type","PARALLEL"); put(p,"parallel_group_id","wave1");
        JSONArray b=new JSONArray(); for(String id:new String[]{"A","B"}) {
            JSONObject x=new JSONObject(); put(x,"branch_id",id); put(x,"objective","independent read "+id);
            put(x,"profile",profile("CHAT","","medium")); put(x,"mutation_boundary",new JSONArray()); b.put(x);
        } put(p,"branches",b); return p;
    }
    private static JSONObject profile(String mode,String model,String reason) {
        JSONObject p=new JSONObject(); put(p,"mode",mode); put(p,"model",model); put(p,"reasoning",reason); return p;
    }
    private static SelfRun3Engine.State branch(SelfRun3Engine.State s,String id) {
        return s.executions().stream().filter(x->id.equals(x.text("branchId"))).findFirst().orElseThrow();
    }
    private static boolean hasMerge(SelfRun3Engine.State s) { return s.executions().stream().anyMatch(x->"PARALLEL_MERGE".equals(x.text("executionKind"))); }
    private static SelfRun3Engine.State accept(SelfRun3Engine.State s,String id,JSONObject r) {
        JSONObject p=new JSONObject(); put(p,"text",r.toString()); return event(s,id,SelfRun3Engine.Kind.RESULT,p);
    }
    private static SelfRun3Engine.State resource(SelfRun3Engine.State s,String k,String value) {
        JSONObject p=new JSONObject(); put(p,"key",k); put(p,"value",value); return event(s,s.turnId(),SelfRun3Engine.Kind.RESOURCE,p);
    }
    private static SelfRun3Engine.State event(SelfRun3Engine.State s,String id,SelfRun3Engine.Kind kind,JSONObject p) {
        return ledger.apply(new SelfRun3Engine.Event("event-"+java.util.UUID.randomUUID(),kind,s.taskId(),id,p));
    }
    private static void put(JSONObject o,String k,Object v) { SelfRun3Engine.put(o,k,v); }
}
