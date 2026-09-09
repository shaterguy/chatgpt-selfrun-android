package com.shaterguy.chatgptselfrun;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Durable task with independently addressed, disposable conversation executions. */
final class SelfRun3Engine {
    static final String STATE_SCHEMA = "selfrun-task-state-v3";
    static final String RESULT_SCHEMA = "selfrun-turn-result-v3";
    static final int MAX_RESULT_BYTES = 512 * 1024;
    enum Stage { SETUP, PREPARING, READY, DISPATCHING, WAITING, RECONCILING,
        WAITING_USER_INTERVENTION, BRANCH_COMPLETE, PAUSED, DONE, STOPPED }
    enum Kind { RESOURCE, SETUP_DONE, TURN_READY, CLAIM_SEND, STARTED, ACCEPTED, UNSENT, ENDED,
        RESULT_BASELINE, RESULT_MUTATED, RESULT, COMMIT, RECONCILE, REPAIR, PAUSE, RESUME, STOP, ERROR }
    enum Action { SETUP, PREPARE_TURN, PREPARE_WEB, WAIT, READ_RESULT, CHECK_RECEIPT, COMMIT, NONE }
    private static final Set<String> GLOBAL = Set.of("executions", "history", "maxTurn", "lastConsumedInputRevision", "taskPaused", "taskStopped", "taskMode");
    private static final Set<String> NO_SEND_PROOFS = Set.of("SEND_DISABLED", "STOP", "COMPOSER_CLEARING",
        "COMPOSER_INPUTTING", "TARGET_ERROR", "AUTH_REQUIRED", "TURN_PROTOCOL_BUSY", "TURN_PROTOCOL_UNAVAILABLE");

    static final class State {
        private final JSONObject value;
        State(JSONObject value) {
            this.value = copy(value);
            if (!this.value.has("executions")) put(this.value,"legacyContract",true);
            if (!this.value.has("executions") && "PAUSED".equals(this.value.optString("stage"))) {
                put(this.value,"taskPaused",true);
                put(this.value,"stage",this.value.optString("resumeStage","WAITING"));
            }
            require(STATE_SCHEMA.equals(text("schema")) && validId(taskId()) && validId(turnId()) && validId(requestId()), "invalid task identity");
            Stage.valueOf(text("stage")); require(turn() > 0, "invalid turn ordinal");
        }
        String text(String k) { return value.optString(k, ""); }
        boolean flag(String k) { return value.optBoolean(k, false); }
        int number(String k) { return value.optInt(k, 0); }
        long time(String k) { return value.optLong(k, 0L); }
        String taskId() { return text("taskId"); }
        String turnId() { return text("turnId"); }
        String requestId() { return text("requestId"); }
        int turn() { return number("turn"); }
        Stage stage() { return flag("taskStopped") ? Stage.STOPPED : flag("taskPaused") ? Stage.PAUSED : Stage.valueOf(text("stage")); }
        JSONObject json() { return copy(value); }
        JSONObject config() { return copy(value.optJSONObject("config")); }
        String taskMode() { return value.optString("taskMode", config().optString("taskMode", config().optString("mode"))); }
        String resource(String k) { return copy(value.optJSONObject("resources")).optString(k, ""); }
        boolean terminal() { return stage() == Stage.DONE || stage() == Stage.STOPPED; }
        boolean hasResult() { return !text("result").isEmpty(); }
        JSONArray history() { return array(value.optJSONArray("history")); }
        State execution(String id) {
            if (turnId().equals(id)) return this;
            JSONObject e = copy(value.optJSONObject("executions")).optJSONObject(id);
            return e == null ? null : new State(withGlobals(e, value));
        }
        List<State> executions() {
            ArrayList<State> out = new ArrayList<>();
            JSONObject all = copy(value.optJSONObject("executions"));
            put(all, turnId(), record(value));
            Iterator<String> keys = all.keys();
            while(keys.hasNext()) out.add(new State(withGlobals(all.optJSONObject(keys.next()), value)));
            out.sort((a,b) -> Integer.compare(a.turn(), b.turn()));
            return out;
        }
    }
    static final class Event {
        final String id, taskId, turnId; final Kind kind; final JSONObject payload;
        Event(String id, Kind kind, String taskId, String turnId, JSONObject payload) {
            require(validId(id) && validId(taskId), "invalid event identity");
            this.id=id; this.kind=Objects.requireNonNull(kind); this.taskId=taskId;
            this.turnId=turnId == null ? "" : turnId; this.payload=copy(payload);
        }
    }
    static State create(String taskId, String turnId, JSONObject config) {
        require(validId(taskId) && validId(turnId), "task identity required");
        String policy=config.optString("taskMode", config.optString("mode"));
        require(Set.of("CHAT","WORK","HYBRID").contains(policy), "task mode required");
        require(Set.of("CHAT","WORK").contains(config.optString("mode")), "execution mode required");
        JSONObject v=new JSONObject();
        put(v,"schema",STATE_SCHEMA); put(v,"taskId",taskId); put(v,"taskMode",policy);
        put(v,"turnId",turnId); put(v,"requestId",turnId+"-request"); put(v,"turn",1);
        put(v,"maxTurn",1); put(v,"stage","SETUP"); put(v,"phase","PLAN");
        put(v,"config",copy(config)); put(v,"resources",new JSONObject());
        put(v,"executionKind","NORMAL"); put(v,"signalType","INITIAL");
        put(v,"lastConsumedInputRevision",0L); put(v,"history",new JSONArray());
        return persist(v, false);
    }
    static State reduce(State original, Event e) {
        if (!original.taskId().equals(e.taskId) || original.flag("taskStopped")) return original;
        if (e.kind==Kind.PAUSE || e.kind==Kind.RESUME || e.kind==Kind.STOP) {
            JSONObject v=original.json();
            if(e.kind==Kind.STOP) { put(v,"taskStopped",true); put(v,"pauseReason","USER_STOP"); }
            else if(e.kind==Kind.PAUSE) { put(v,"taskPaused",true); put(v,"pauseReason",e.payload.optString("reason")); }
            else { put(v,"taskPaused",false); v.remove("pauseReason"); }
            return persist(v,true);
        }
        State s=original.execution(e.turnId); if(s==null) return original;
        if(s.flag("superseded") && e.kind!=Kind.RESOURCE) return original;
        if(s.terminal() && e.kind!=Kind.RESOURCE) return original;
        JSONObject v=s.json(), p=e.payload; Stage stage=Stage.valueOf(s.text("stage"));
        if(Set.of(Kind.STARTED,Kind.ACCEPTED,Kind.UNSENT,Kind.ENDED).contains(e.kind)
                && !s.requestId().equals(p.optString("requestId"))) return original;
        switch(e.kind) {
            case RESOURCE -> {
                String k=p.optString("key"), val=p.optString("value");
                require(Set.of("folderId","requirementDocumentId","resultDocumentId","conversationUrl","resultCreateIntent","requirementCreateIntent").contains(k),"unknown resource");
                require(!val.isEmpty() && val.length()<=2048,"invalid resource");
                JSONObject r=copy(v.optJSONObject("resources")); String prior=r.optString(k);
                if("conversationUrl".equals(k)) require(val.matches("https://chatgpt\\.com/c/[A-Za-z0-9-]+"),"canonical conversation URL required");
                require(prior.isEmpty() || prior.equals(val),"pinned resource cannot change");
                if(prior.equals(val)) return original;
                put(r,k,val); put(v,"resources",r);
            }
            case SETUP_DONE -> {
                require(stage==Stage.SETUP && !s.resource("folderId").isEmpty() && !s.resource("requirementDocumentId").isEmpty(),"setup resources required");
                put(v,"stage","PREPARING");
            }
            case TURN_READY -> {
                require(stage==Stage.PREPARING && !s.resource("resultDocumentId").isEmpty(),"turn resources required");
                require(!p.optString("prompt").isEmpty() && utf8(p.optString("prompt"))<=1024*1024,"bounded prompt required");
                put(v,"prompt",p.optString("prompt")); put(v,"inputText",p.optString("inputText"));
                put(v,"inputRevision",p.optLong("inputRevision",-1)); put(v,"stage","READY");
            }
            case RESULT_BASELINE -> {
                String documentId=p.optString("documentId"), fingerprint=p.optString("fingerprint");
                require(documentId.equals(s.resource("resultDocumentId")) && fingerprint.matches("[a-f0-9]{64}"),"valid result baseline required");
                if(!s.text("resultSeedDocumentId").isEmpty() || !s.text("resultSeedFingerprint").isEmpty()) {
                    require(documentId.equals(s.text("resultSeedDocumentId")) && fingerprint.equals(s.text("resultSeedFingerprint")),"result baseline changed");
                    return original;
                }
                require(stage==Stage.PREPARING,"result baseline must precede dispatch");
                put(v,"resultSeedDocumentId",documentId); put(v,"resultSeedFingerprint",fingerprint);
            }
            case CLAIM_SEND -> {
                if(stage!=Stage.READY || s.flag("sendClaimed") || original.flag("taskPaused")) return original;
                require(activeCount(original)<2,"maximum two automatic conversations");
                for(State other:original.executions()) require(other.turnId().equals(s.turnId()) || other.stage()!=Stage.DISPATCHING,"dispatch is sequential");
                put(v,"sendClaimed",true); put(v,"stage","DISPATCHING"); put(v,"submittedAt",p.optLong("at"));
            }
            case STARTED, ACCEPTED -> {
                if(!s.flag("sendClaimed")) return original;
                if(e.kind==Kind.STARTED && !v.has("canonicalPostConfirmedElapsed")
                        && "canonical_post".equals(p.optString("source"))
                        && "turn_request".equals(p.optString("protocolStage"))
                        && p.has("atElapsed") && p.has("atWall") && p.has("bootCount")
                        && p.optLong("atElapsed",-1L)>=0L && p.optLong("atWall",-1L)>0L
                        && p.optInt("bootCount",-1)>=0) {
                    put(v,"canonicalPostConfirmedElapsed",p.optLong("atElapsed"));
                    put(v,"canonicalPostConfirmedAtWall",p.optLong("atWall"));
                    put(v,"canonicalPostBootCount",p.optInt("bootCount"));
                }
                put(v,"dispatchObserved",true); put(v,"accepted",true);
                if(!s.flag("committed")) put(v,"stage",s.hasResult()?"RECONCILING":"WAITING");
            }
            case UNSENT -> {
                if(stage!=Stage.DISPATCHING || s.flag("dispatchObserved") || s.flag("accepted")) return original;
                require(NO_SEND_PROOFS.contains(p.optString("status")),"positive no-dispatch proof required");
                put(v,"sendClaimed",false); put(v,"stage","READY"); v.remove("submittedAt");
            }
            case ENDED -> { return original; } // Transport completion is never a result gate.
            case RESULT_MUTATED -> {
                if(s.flag("resultBodyMutationObserved")) return original;
                String documentId=p.optString("documentId"), fingerprint=p.optString("fingerprint");
                require(documentId.equals(s.resource("resultDocumentId")) && documentId.equals(s.text("resultSeedDocumentId")),"result mutation document mismatch");
                require(fingerprint.matches("[a-f0-9]{64}") && !fingerprint.equals(s.text("resultSeedFingerprint")),"actual result body mutation required");
                put(v,"resultBodyMutationObserved",true); put(v,"resultBodyMutationFingerprint",fingerprint);
                if(p.optLong("atWall",0L)>0L) put(v,"resultBodyMutationObservedAtWall",p.optLong("atWall"));
            }
            case RESULT -> {
                if(!s.flag("sendClaimed")) return original;
                JSONObject r=parseResult(p.optString("text"),s); if(r==null) return original;
                if(s.hasResult()) {
                    JSONObject old=object(s.text("result"));
                    boolean resolved="USER_ACTION_REQUIRED".equals(old.optString("status")) && "USER_ACTION_RESOLVED".equals(r.optString("status"));
                    if(!resolved) { require(equivalent(old,r),"committed result changed"); return original; }
                }
                put(v,"result",r.toString()); put(v,"committed",false); put(v,"stage","RECONCILING");
            }
            case COMMIT -> {
                require(!original.flag("taskPaused") && s.hasResult() && !s.flag("committed"),"committed result required");
                JSONObject r=parseResult(s.text("result"),s); require(r!=null,"result required");
                put(v,"committed",true);
                String status=r.optString("status");
                if("USER_ACTION_REQUIRED".equals(status)) {
                    put(v,"stage","WAITING_USER_INTERVENTION"); put(v,"interventionRequested",true); put(v,"pauseReason",r.optString("reason"));
                } else if(isBranch(s)) {
                    put(v,"stage","BRANCH_COMPLETE");
                } else {
                    put(v,"checkpoint",r.toString());
                    put(v,"lastConsumedInputRevision",Math.max(s.time("lastConsumedInputRevision"),s.time("inputRevision")));
                    if("DONE".equals(status) && !p.optBoolean("lateInput")) { put(v,"stage","DONE"); put(v,"phase","DONE"); }
                    else {
                        put(v,"stage","BRANCH_COMPLETE");
                        if("PAUSED".equals(status)) put(v,"taskPaused",true);
                        State saved=persist(v,false);
                        JSONObject plan=r.optJSONObject("next_execution");
                        if(plan!=null && "PARALLEL".equals(plan.optString("type")) && !p.optBoolean("lateInput")) {
                            v=fanout(saved,r,plan);
                        } else {
                            JSONObject profile=nextProfile(r,s);
                            String phase=p.optBoolean("lateInput") ? "PLAN" : r.optString("next_phase",s.text("phase"));
                            String signal="USER_ACTION_RESOLVED".equals(status) ? "USER_ACTION_RESUME" : p.optBoolean("lateInput") ? "USER_INPUT" : "AUTO_NEXT_TURN";
                            v=fresh(saved,phase,profile,"NORMAL",signal,s.resource("resultDocumentId"));
                            put(v,"checkpoint",r.toString()); put(v,"nextInput",r.optString("next_input"));
                            if(r.has("intervention")) put(v,"intervention",r.optJSONObject("intervention"));
                            if("PAUSED".equals(status)) put(v,"taskPaused",true);
                        }
                    }
                }
            }
            case RECONCILE -> { if(s.flag("sendClaimed") && !s.flag("committed")) put(v,"stage","RECONCILING"); }
            case REPAIR -> {
                require(((!s.flag("committed") && !s.hasResult()) || stage==Stage.WAITING_USER_INTERVENTION) && s.number("repairAttempt")==0,"repair requires unresolved result");
                require(p.optBoolean("safeToRepair"),"explicit evidence original automatic work is inactive required");
                put(v,"stage","BRANCH_COMPLETE"); put(v,"committed",true); put(v,"superseded",true); State saved=persist(v,false);
                v=fresh(saved,s.text("phase"),executionProfile(s),"REPAIR","REPAIR",s.text("previousResultDocumentId"));
                put(v,"repairTargetDocumentId",s.resource("resultDocumentId")); put(v,"repairAttempt",1);
                if(s.flag("interventionRequested")) put(v,"interventionRequested",true);
                if(isBranch(s)) {
                    put(v,"repairBranch",true);
                    for(String key:new String[]{"parallelGroupId","branchId","branchDepth","branchObjective","mutationBoundary","branchPlan","mergeProfile","mergePhase","branchInputRevision","branchInputText"})
                        if(s.json().has(key)) put(v,key,s.json().opt(key));
                }
                put(v,"checkpoint",s.text("checkpoint"));
            }
            case ERROR -> { String code=p.optString("code"); require(code.matches("[A-Z0-9_:-]{1,100}"),"safe error code required"); put(v,"error",code); }
            default -> { return original; }
        }
        State result=persist(v,true);
        return maybeMerge(result);
    }
    static Action nextAction(State s) {
        return switch(s.stage()) {
            case SETUP -> Action.SETUP;
            case PREPARING -> Action.PREPARE_TURN;
            case READY -> Action.PREPARE_WEB;
            case DISPATCHING, WAITING, WAITING_USER_INTERVENTION -> Action.WAIT;
            case RECONCILING -> s.hasResult() ? Action.COMMIT : Action.READ_RESULT;
            default -> Action.NONE;
        };
    }
    static List<State> waitingExecutions(State s) {
        ArrayList<State> out=new ArrayList<>();
        for(State x:s.executions()) if(Set.of(Stage.DISPATCHING,Stage.WAITING,Stage.WAITING_USER_INTERVENTION).contains(x.stage()) || (x.stage()==Stage.RECONCILING && !x.hasResult())) out.add(x);
        return out;
    }
    static int activeCount(State s) {
        int count=0; for(State x:s.executions()) if(x.flag("sendClaimed") && !x.flag("committed") && !"BRANCH_COMPLETE".equals(x.text("stage"))) count++;
        return count;
    }
    private static State maybeMerge(State s) {
        if(s.flag("taskPaused") || s.flag("taskStopped")) return s;
        for(State a:s.executions()) {
            if(!isBranch(a) || a.flag("superseded") || a.text("parallelGroupId").isEmpty()) continue;
            String group=a.text("parallelGroupId"); List<State> members=new ArrayList<>(); boolean merged=false;
            for(State b:s.executions()) if(group.equals(b.text("parallelGroupId"))) {
                if("PARALLEL_MERGE".equals(b.text("executionKind"))) merged=true;
                else if(isBranch(b) && !b.flag("superseded")) members.add(b);
            }
            if(merged || members.size()!=2 || members.stream().anyMatch(x -> !"BRANCH_COMPLETE".equals(x.text("stage")))) continue;
            JSONObject profile=copy(a.json().optJSONObject("mergeProfile"));
            if(profile.length()==0) profile=executionProfile(a);
            JSONObject v=fresh(s,a.text("mergePhase"),profile,"PARALLEL_MERGE","PARALLEL_MERGE",a.text("previousResultDocumentId"));
            put(v,"parallelGroupId",group); JSONArray ids=new JSONArray();
            for(State b:members) ids.put(b.resource("resultDocumentId"));
            put(v,"mergedFrom",ids); put(v,"checkpoint",a.text("checkpoint"));
            return persist(v,true);
        }
        return s;
    }
    private static JSONObject fanout(State s, JSONObject result, JSONObject plan) {
        JSONArray branches=plan.optJSONArray("branches"); require(branches!=null && branches.length()==2,"exactly two branches required");
        JSONObject v=s.json(); String group=plan.optString("parallel_group_id");
        for(int i=0;i<2;i++) {
            JSONObject branch=branches.optJSONObject(i); State base=new State(v);
            v=fresh(base,result.optString("next_phase"),branch.optJSONObject("profile"),"PARALLEL_BRANCH","PARALLEL_BRANCH",s.resource("resultDocumentId"));
            put(v,"parallelGroupId",group); put(v,"branchId",branch.optString("branch_id")); put(v,"branchDepth",1);
            put(v,"branchObjective",branch.optString("objective")); put(v,"mutationBoundary",branch.optJSONArray("mutation_boundary"));
            put(v,"branchPlan",branches); put(v,"mergeProfile",nextProfile(result,s)); put(v,"mergePhase",result.optString("next_phase"));
            put(v,"checkpoint",result.toString()); put(v,"branchInputRevision",s.time("inputRevision")); put(v,"branchInputText",""); v=persist(v,false).json();
        }
        return v;
    }
    private static JSONObject fresh(State s,String phase,JSONObject profile,String kind,String signal,String predecessor) {
        int ordinal=Math.max(s.number("maxTurn"),s.turn())+1; require(ordinal>0,"turn ordinal overflow");
        JSONObject v=s.json(), resources=copy(v.optJSONObject("resources"));
        JSONObject all=copy(v.optJSONObject("executions")); put(all,s.turnId(),record(v));
        JSONObject config=s.config(); applyProfile(config,profile,s.taskMode());
        for(String k:new String[]{"prompt","result","inputText","inputRevision","nextInput","submittedAt","error","repairAttempt","pauseReason","conversationId","intervention",
                "parallelGroupId","branchId","branchDepth","branchObjective","mutationBoundary","branchPlan","mergeProfile","mergePhase","mergedFrom","repairTargetDocumentId","interventionRequested","branchInputRevision","branchInputText","superseded","repairBranch","legacyContract",
                "canonicalPostConfirmedElapsed","canonicalPostConfirmedAtWall","canonicalPostBootCount","resultSeedDocumentId","resultSeedFingerprint","resultBodyMutationObserved","resultBodyMutationFingerprint","resultBodyMutationObservedAtWall"}) v.remove(k);
        resources.remove("resultDocumentId"); resources.remove("resultCreateIntent"); resources.remove("conversationUrl");
        put(v,"resources",resources); put(v,"executions",all); put(v,"config",config);
        put(v,"turn",ordinal); put(v,"maxTurn",ordinal); put(v,"turnId",s.taskId()+":turn:"+ordinal);
        put(v,"requestId",s.taskId()+":turn:"+ordinal+"-request"); put(v,"phase",phase);
        put(v,"previousResultDocumentId",predecessor); put(v,"executionKind",kind); put(v,"signalType",signal);
        put(v,"stage","PREPARING"); put(v,"sendClaimed",false); put(v,"dispatchObserved",false); put(v,"accepted",false); put(v,"ended",false); put(v,"committed",false);
        return v;
    }
    private static State persist(JSONObject value,boolean select) {
        JSONObject v=copy(value), all=copy(v.optJSONObject("executions")); put(all,v.optString("turnId"),record(v)); put(v,"executions",all);
        JSONArray history=new JSONArray();
        List<JSONObject> rows=new ArrayList<>(); Iterator<String> keys=all.keys();
        while(keys.hasNext()) rows.add(all.optJSONObject(keys.next()));
        rows.sort((a,b)->Integer.compare(a.optInt("turn"),b.optInt("turn")));
        for(JSONObject x:rows) {
            JSONObject h=new JSONObject(), c=copy(x.optJSONObject("config")), r=copy(x.optJSONObject("resources"));
            for(String k:new String[]{"turn","turnId","requestId","phase","executionKind","signalType","parallelGroupId","branchId","submittedAt","canonicalPostConfirmedAtWall"}) put(h,k,x.opt(k));
            put(h,"mode",c.optString("mode")); put(h,"model",c.optString("model")); put(h,"reasoning",c.optString("reasoning",c.optString("chatBootstrap")));
            put(h,"conversationUrl",r.optString("conversationUrl")); put(h,"resultDocumentId",r.optString("resultDocumentId"));
            put(h,"previousResultDocumentId",x.optString("previousResultDocumentId")); put(h,"superseded",x.optBoolean("superseded"));
            put(h,"resultBodyMutationObserved",x.optBoolean("resultBodyMutationObserved"));
            put(h,"dispatchStatus",x.optBoolean("dispatchObserved")?"CONFIRMED":x.optBoolean("sendClaimed")?"UNCERTAIN":"PENDING");
            JSONObject result=x.optString("result").isEmpty()?new JSONObject():object(x.optString("result"));
            put(h,"resultStatus",result.optString("status",x.optBoolean("committed")?"COMMITTED":"PENDING")); history.put(h);
        }
        put(v,"history",history);
        if(select && !v.optBoolean("taskPaused") && !v.optBoolean("taskStopped")) {
            JSONObject chosen=null; int priority=99;
            for(JSONObject x:rows) {
                String stage=x.optString("stage");
                int p=switch(stage) { case "RECONCILING" -> x.optString("result").isEmpty()?3:0; case "DISPATCHING" -> 1; case "SETUP","PREPARING","READY" -> 2; case "WAITING" -> 3; case "WAITING_USER_INTERVENTION" -> 4; case "DONE" -> 5; default -> 9; };
                if(p<priority) { chosen=x; priority=p; }
            }
            if(chosen!=null) v=withGlobals(chosen,v);
        }
        return new State(v);
    }
    private static JSONObject record(JSONObject v) { JSONObject r=copy(v); for(String k:GLOBAL) r.remove(k); return r; }
    private static JSONObject withGlobals(JSONObject record,JSONObject root) { JSONObject r=copy(record); for(String k:GLOBAL) if(root.has(k)) put(r,k,root.opt(k)); return r; }
    static JSONObject executionProfile(State s) {
        JSONObject c=s.config(), p=new JSONObject(); put(p,"mode",c.optString("mode")); put(p,"model",c.optString("model"));
        String reason=c.optString("reasoning"); if(reason.isEmpty()) reason=c.optString("chatBootstrap");
        put(p,"reasoning",reason); return p;
    }
    static JSONObject nextProfile(JSONObject r,State s) {
        JSONObject plan=r.optJSONObject("next_execution"), p=plan==null?null:plan.optJSONObject("profile");
        if(p==null) p=r.optJSONObject("next_profile"); if(p==null) p=r.optJSONObject("profile");
        return p==null?executionProfile(s):copy(p);
    }
    static void applyProfile(JSONObject config,JSONObject p,String policy) {
        require(p!=null,"execution profile required"); String mode=p.optString("mode",config.optString("mode"));
        require(Set.of("CHAT","WORK").contains(mode) && ("HYBRID".equals(policy)||policy.equals(mode)),"task mode boundary");
        String model=p.optString("model"), reasoning=p.optString("reasoning");
        require("CHAT".equals(mode)? model.isEmpty() && ProfileRegistry.resolveChat(reasoning)!=null : ProfileRegistry.resolveWork(model,reasoning)!=null,"unregistered profile");
        put(config,"mode",mode); put(config,"model",model); put(config,"reasoning",reasoning); put(config,"chatBootstrap",reasoning); config.remove("chatContinuation");
    }

    static JSONObject parseResult(String raw,State s) {
        if(raw==null || raw.trim().isEmpty()) return null;
        require(utf8(raw)<=MAX_RESULT_BYTES && raw.indexOf('\0')<0,"result size or NUL");
        JSONObject r=SelfRun3StrictJson.parseObject(raw.trim());
        require(RESULT_SCHEMA.equals(r.optString("schema")),"result schema mismatch");
        require(s.taskId().equals(r.optString("task_id")) && s.turnId().equals(r.optString("turn_id")),"result identity mismatch");
        require(s.resource("resultDocumentId").equals(r.optString("document_id")) && !s.resource("resultDocumentId").isEmpty(),"result document mismatch");
        require((s.turnId()+":result").equals(r.optString("event_id")),"event identity mismatch");
        Object ordinal=r.opt("turn");
        require((ordinal instanceof Integer || ordinal instanceof Long) && ((Number)ordinal).longValue()==s.turn(),"turn ordinal mismatch");
        if(Boolean.FALSE.equals(r.opt("committed"))) return null;
        require(Boolean.TRUE.equals(r.opt("committed")),"boolean commit required");
        String status=r.optString("status"); boolean branch=isBranch(s);
        require(Set.of("CONTINUE","DONE","PAUSED","USER_ACTION_REQUIRED","USER_ACTION_RESOLVED","COMPLETE","PARTIAL").contains(status),"unknown result status");
        JSONObject context=r.optJSONObject("execution_context");
        if(branch) {
            require(context!=null && "PARALLEL_BRANCH".equals(context.optString("type"))
                    && s.text("parallelGroupId").equals(context.optString("parallel_group_id"))
                    && s.text("branchId").equals(context.optString("branch_id"))
                    && context.optInt("branch_depth")==1,"branch identity mismatch");
            require(!r.has("next_execution") && !r.has("next_profile"),"branch cannot schedule work");
            JSONObject br=r.optJSONObject("branch_result");
            require(br!=null && Set.of("COMPLETE","PARTIAL","USER_ACTION_REQUIRED","USER_ACTION_RESOLVED").contains(status) && status.equals(br.optString("status")),"matching branch result required");
        } else {
            require(!"COMPLETE".equals(status) && !"PARTIAL".equals(status),"branch-only status");
            String completed=r.optString("phase_completed"), next=r.optString("next_phase");
            require(Set.of("PLAN","WORK","VERIFY","DONE").contains(next),"unknown next phase");
            if("DONE".equals(status)) require("VERIFY".equals(s.text("phase")) && "VERIFY_DONE".equals(completed) && "DONE".equals(next),"verification required for DONE");
            else require(!"DONE".equals(next),"nonterminal result cannot advance DONE");
            if("CONTINUE".equals(status)) {
                boolean valid=switch(completed) {
                    case "PLAN" -> "PLAN".equals(s.text("phase")) && "WORK".equals(next);
                    case "PLAN_PARTIAL" -> "PLAN".equals(s.text("phase")) && "PLAN".equals(next);
                    case "WORK" -> "WORK".equals(s.text("phase")) && "VERIFY".equals(next);
                    case "WORK_PARTIAL" -> "WORK".equals(s.text("phase")) && "WORK".equals(next);
                    case "VERIFY_PARTIAL","VERIFY_WORK_PARTIAL" -> "VERIFY".equals(s.text("phase")) && Set.of("WORK","VERIFY").contains(next);
                    case "VERIFY_WORK" -> "VERIFY".equals(s.text("phase")) && "VERIFY".equals(next);
                    default -> false;
                };
                require(valid,"invalid phase transition");
            }
        }
        JSONObject handoff=r.optJSONObject("handoff");
        require(handoff!=null && !handoff.optString("objective").trim().isEmpty(),"full handoff required");
        for(String key:new String[]{"completed","remaining","evidence","constraints","next_action"}) require(handoff.has(key),"full handoff missing "+key);
        if(!s.flag("legacyContract")) for(String key:new String[]{"requirements","decisions","assumptions","materials","external_state","verification_state","do_not_repeat"})
            require(handoff.has(key),"full handoff missing "+key);
        if(r.has("next_input")) require(r.opt("next_input") instanceof String && utf8(r.optString("next_input"))<=64*1024,"bounded next input required");
        if("USER_ACTION_RESOLVED".equals(status)) {
            require(s.flag("interventionRequested"),"resolution must follow intervention");
            JSONObject intervention=r.optJSONObject("intervention");
            require(intervention!=null && Boolean.TRUE.equals(intervention.opt("user_reported_complete"))
                    && !intervention.optString("resume_action").trim().isEmpty(),"intervention resolution required");
        }
        if("USER_ACTION_REQUIRED".equals(status)) require(!r.optString("reason").trim().isEmpty(),"concrete user action required");
        if("PARALLEL_MERGE".equals(s.text("executionKind"))) {
            require(context!=null && "PARALLEL_MERGE".equals(context.optString("type"))
                    && s.text("parallelGroupId").equals(context.optString("parallel_group_id")),"merge identity mismatch");
            require(equivalent(s.json().optJSONArray("mergedFrom"),r.optJSONArray("merged_from")),"exact merged predecessors required");
        }
        if(!branch && !"DONE".equals(status)) {
            JSONObject profile=nextProfile(r,s); JSONObject check=s.config(); applyProfile(check,profile,s.taskMode());
            JSONObject plan=r.optJSONObject("next_execution");
            if(plan!=null) {
                String type=plan.optString("type"); require(Set.of("SERIAL","PARALLEL").contains(type),"unknown execution type");
                if("PARALLEL".equals(type)) {
                    String group=plan.optString("parallel_group_id");
                    require(validId(group),"parallel group identity");
                    for(State prior:s.executions()) require(!group.equals(prior.text("parallelGroupId")),"parallel group already used");
                    JSONArray branches=plan.optJSONArray("branches"); require(branches!=null && branches.length()==2,"exactly two branches required");
                    Set<String> ids=new java.util.HashSet<>(), writes=new java.util.HashSet<>();
                    for(int i=0;i<2;i++) {
                        JSONObject b=branches.optJSONObject(i); require(b!=null && validId(b.optString("branch_id")) && ids.add(b.optString("branch_id")),"unique branch identity");
                        require(!b.optString("objective").trim().isEmpty(),"branch objective required");
                        applyProfile(s.config(),b.optJSONObject("profile"),s.taskMode());
                        JSONArray boundary=b.optJSONArray("mutation_boundary"); require(boundary!=null,"explicit mutation boundary required (empty for read-only)");
                        for(int j=0;j<boundary.length();j++) {
                            Object rawTarget=boundary.opt(j); require(rawTarget instanceof String,"string mutation boundary required");
                            String target=canonicalBoundary((String)rawTarget);
                            for(String prior:writes) require(!target.equals(prior) && !target.startsWith(prior+"/") && !prior.startsWith(target+"/"),"overlapping mutation boundary");
                            writes.add(target);
                        }
                    }
                }
            }
        }
        return r;
    }
    static JSONObject emptyResult(State s) {
        JSONObject r=new JSONObject(); put(r,"schema",RESULT_SCHEMA); put(r,"task_id",s.taskId()); put(r,"turn_id",s.turnId());
        put(r,"turn",s.turn()); put(r,"document_id",s.resource("resultDocumentId")); put(r,"event_id",s.turnId()+":result"); put(r,"committed",false);
        if(isBranch(s) || "PARALLEL_MERGE".equals(s.text("executionKind"))) {
            JSONObject c=new JSONObject(); put(c,"type",isBranch(s)?"PARALLEL_BRANCH":s.text("executionKind")); put(c,"parallel_group_id",s.text("parallelGroupId"));
            if(isBranch(s)) { put(c,"branch_id",s.text("branchId")); put(c,"branch_depth",1); }
            put(r,"execution_context",c);
        }
        return r;
    }
    static boolean isBranch(State s) { return "PARALLEL_BRANCH".equals(s.text("executionKind")) || s.flag("repairBranch"); }
    static String canonicalBoundary(String value) {
        require(value!=null && value.length()<=2048 && value.matches("[a-z][a-z0-9-]*:[A-Za-z0-9._/@:-]+")
                && !value.contains("..") && !value.contains("//") && !value.endsWith("/") && !value.contains("/./"),"canonical resource key required");
        for(String segment:value.substring(value.indexOf(':')+1).split("/")) require(!segment.equals(".") && !segment.equals(".."),"canonical path segment required");
        return value.toLowerCase(java.util.Locale.ROOT);
    }
    static boolean validId(String v) { return v!=null && v.matches("[A-Za-z0-9._:-]{1,240}"); }
    static boolean validProfileToken(String v) { return v!=null && v.matches("[a-z0-9][a-z0-9._:-]{0,79}"); }
    static int utf8(String v) { return v.getBytes(StandardCharsets.UTF_8).length; }
    static void require(boolean condition,String message) { if(!condition) throw new IllegalStateException(message); }
    static JSONObject object(String raw) { try { return new JSONObject(raw); } catch(Exception e) { throw new IllegalArgumentException("invalid JSON object",e); } }
    static JSONObject copy(JSONObject v) { return v==null?new JSONObject():object(v.toString()); }
    static JSONArray array(JSONArray a) { try { return a==null?new JSONArray():new JSONArray(a.toString()); } catch(Exception e) { throw new IllegalArgumentException(e); } }
    static void put(JSONObject v,String key,Object val) { try { v.put(key,val); } catch(Exception e) { throw new IllegalArgumentException("JSON value rejected",e); } }
    private static boolean equivalent(Object a,Object b) {
        if(a instanceof JSONObject x && b instanceof JSONObject y) {
            if(x.length()!=y.length()) return false; Iterator<String> keys=x.keys();
            while(keys.hasNext()) { String k=keys.next(); if(!y.has(k)||!equivalent(x.opt(k),y.opt(k))) return false; } return true;
        }
        if(a instanceof JSONArray x && b instanceof JSONArray y) {
            if(x.length()!=y.length()) return false; for(int i=0;i<x.length();i++) if(!equivalent(x.opt(i),y.opt(i))) return false; return true;
        }
        return Objects.equals(a,b);
    }
    private SelfRun3Engine() {}
}
