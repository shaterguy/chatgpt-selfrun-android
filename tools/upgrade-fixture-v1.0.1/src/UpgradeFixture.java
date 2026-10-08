package com.shaterguy.selfrun.upgradefixture;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Build;
import android.os.Bundle;
import org.json.JSONObject;
import org.json.JSONArray;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.security.MessageDigest;

/** Version 1.0.1. Synthetic data only; refuses physical hardware and populated app storage. */
public final class UpgradeFixture extends Instrumentation {
    private static final String VERSION="1.0.1",TASK="UPGRADE-FIXTURE-v1",TURN=TASK+":turn:1";
    private static final String[] RUNTIME={"selfrun3ResultRepairMinutes","selfrun3StallAlertMinutes","selfrun3ResultPollSeconds","selfrun3WebPreparationSeconds","selfrun3WorkMode","selfrun3OnDeviceDefaultMigrationVersion"};
    private static final String[] BINDING={"driveAccountId","driveRunsBaseFolderId","driveRunsBaseFolderName","driveRunsBaseFolderUrl","driveRunsBaseFolderBoundAt"};
    private Bundle args;
    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);args=arguments;start();}
    private Class<?> targetClass(String shortName)throws Exception{return Class.forName("com.shaterguy.chatgptselfrun."+shortName,true,getTargetContext().getClassLoader());}
    private Object construct(Class<?> type,Class<?>[] signature,Object... values)throws Exception{Constructor<?> c=type.getDeclaredConstructor(signature);c.setAccessible(true);return c.newInstance(values);}
    private Object call(Class<?> type,Object instance,String name,Class<?>[] signature,Object... values)throws Exception{Method m=type.getDeclaredMethod(name,signature);m.setAccessible(true);return m.invoke(instance,values);}
    private void require(boolean value,String message){if(!value)throw new IllegalStateException(message);}
    private Object settings(Context c)throws Exception{return construct(targetClass("SelfRun3RuntimeSettings"),new Class<?>[]{Context.class},c);}
    private JSONObject selected(Context c,String name,String[] keys)throws Exception{
        JSONObject o=new JSONObject();SharedPreferences p=c.getSharedPreferences(name,Context.MODE_PRIVATE);
        for(String k:keys){Object v=p.getAll().get(k);o.put(k,v==null?JSONObject.NULL:v);}return o;
    }
    private JSONObject snapshot(Context c)throws Exception{
        JSONObject o=new JSONObject();o.put("uid",c.getApplicationInfo().uid);o.put("package",c.getPackageName());
        Object cfg=settings(c);Class<?> st=targetClass("SelfRunStore");construct(st,new Class<?>[]{Context.class},c);
        o.put("runtime",selected(c,"selfrun3_runtime_settings",RUNTIME));o.put("binding",selected(c,"selfrun_drive",BINDING));
        Class<?> ledgerClass=targetClass("SelfRun3Ledger");SQLiteOpenHelper ledger=(SQLiteOpenHelper)construct(ledgerClass,new Class<?>[]{Context.class},c);
        try{
            SQLiteDatabase db=ledger.getReadableDatabase();JSONArray rows=new JSONArray();
            try(Cursor cursor=db.rawQuery("SELECT task_id,snapshot,revision FROM tasks ORDER BY task_id",null)){
                while(cursor.moveToNext()){require(TASK.equals(cursor.getString(0)),"unexpected task; isolated fixture required");JSONObject row=new JSONObject();row.put("task_id",cursor.getString(0));row.put("snapshot",new JSONObject(cursor.getString(1)));row.put("revision",cursor.getLong(2));rows.put(row);}
            }
            o.put("tasks",rows);JSONArray events=new JSONArray();
            try(Cursor cursor=db.rawQuery("SELECT task_id,event_id,kind,turn_id FROM events ORDER BY task_id,event_id",null)){
                while(cursor.moveToNext()){JSONArray row=new JSONArray();for(int i=0;i<4;i++)row.put(cursor.getString(i));events.put(row);}
            }
            o.put("events",events);require(rows.length()==1&&events.length()==1,"fixture task/event missing");
        }finally{ledger.close();}
        o.put("pending_user_input",selected(c,"selfrun_drive_user_next_input",new String[]{"runId","text","revision"}));
        require("SERVER".equals(String.valueOf(call(cfg.getClass(),cfg,"workMode",new Class<?>[]{}))),"work mode changed");return o;
    }
    private void seed(Context c)throws Exception{
        SharedPreferences checkpoint=c.getSharedPreferences("selfrun_upgrade_fixture_v1",Context.MODE_PRIVATE);
        require(!checkpoint.contains("snapshot"),"seed already exists; use fresh isolated emulator");
        SharedPreferences binding=c.getSharedPreferences("selfrun_drive",Context.MODE_PRIVATE);require(!binding.contains("driveAccountId"),"existing account binding; refusing overwrite");
        Class<?> lc=targetClass("SelfRun3Ledger");SQLiteOpenHelper ledger=(SQLiteOpenHelper)construct(lc,new Class<?>[]{Context.class},c);
        try{
            try(Cursor cursor=ledger.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM tasks",null)){require(cursor.moveToFirst()&&cursor.getInt(0)==0,"existing task data; refusing overwrite");}
            Class<?> engine=targetClass("SelfRun3Engine"),state=targetClass("SelfRun3Engine$State"),kind=targetClass("SelfRun3Engine$Kind"),event=targetClass("SelfRun3Engine$Event");
            JSONObject config=new JSONObject().put("mode","CHAT").put("reasoning","medium");
            Object initial=call(engine,null,"create",new Class<?>[]{String.class,String.class,JSONObject.class},TASK,TURN,config);
            call(lc,ledger,"ensure",new Class<?>[]{state},initial);
            @SuppressWarnings({"rawtypes","unchecked"}) Object pause=Enum.valueOf((Class)kind,"PAUSE");
            Object paused=construct(event,new Class<?>[]{String.class,kind,String.class,String.class,JSONObject.class},"fixture-pause",pause,TASK,TURN,new JSONObject().put("reason","ISOLATED_UPGRADE_FIXTURE"));
            call(lc,ledger,"apply",new Class<?>[]{event},paused);
        }finally{ledger.close();}
        Object cfg=settings(c);Class<?> mode=targetClass("SelfRun3RuntimeSettings$WorkMode");
        @SuppressWarnings({"rawtypes","unchecked"}) Object server=Enum.valueOf((Class)mode,"SERVER");
        require(Boolean.TRUE.equals(call(cfg.getClass(),cfg,"saveWorkMode",new Class<?>[]{mode},server)),"work mode seed failed");
        for(String method:new String[]{"saveResultRepairMinutes","saveStallAlertMinutes","saveResultPollSeconds","saveWebPreparationSeconds"})
            require(Boolean.TRUE.equals(call(cfg.getClass(),cfg,method,new Class<?>[]{String.class},method.equals("saveWebPreparationSeconds")?"180":"17")),"runtime seed failed");
        Class<?> storeClass=targetClass("SelfRunStore");Object store=construct(storeClass,new Class<?>[]{Context.class},c);
        call(storeClass,store,"bindBaseFolder",new Class<?>[]{String.class,String.class,String.class,String.class,long.class},"fixture-account-v1","fixture-runs-folder-v1","fixture-folder-name","https://drive.google.com/drive/folders/fixture-runs-folder-v1",123L);
        require(c.getSharedPreferences("selfrun_drive_user_next_input",Context.MODE_PRIVATE).edit().putString("runId",TASK).putString("text","synthetic pending edit; never submitted").putLong("revision",7).commit(),"pending input seed failed");
        require(checkpoint.edit().putString("snapshot",canonical(snapshot(c))).commit(),"checkpoint failed");
    }
    private String canonical(Object value)throws Exception{
        if(value instanceof JSONObject){JSONObject o=(JSONObject)value;ArrayList<String> keys=new ArrayList<>();Iterator<String> it=o.keys();while(it.hasNext())keys.add(it.next());Collections.sort(keys);StringBuilder b=new StringBuilder("{");for(String k:keys){if(b.length()>1)b.append(',');b.append(JSONObject.quote(k)).append(':').append(canonical(o.get(k)));}return b.append('}').toString();}
        if(value instanceof JSONArray){JSONArray a=(JSONArray)value;StringBuilder b=new StringBuilder("[");for(int i=0;i<a.length();i++){if(i>0)b.append(',');b.append(canonical(a.get(i)));}return b.append(']').toString();}
        if(value==null||value==JSONObject.NULL)return "null";return value instanceof String?JSONObject.quote((String)value):String.valueOf(value);
    }
    private String sha(String value)throws Exception{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes("UTF-8"));StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format("%02x",b&255));return out.toString();}
    @Override public void onStart(){Bundle result=new Bundle();try{
        require(args!=null&&VERSION.equals(args.getString("allowIsolatedEmulator")),"explicit isolated fixture version required");
        require("ranchu".equals(Build.HARDWARE)||"goldfish".equals(Build.HARDWARE),"physical hardware refused");
        Context c=getTargetContext();require(c.getPackageName().equals(args.getString("expectedTarget")),"target package mismatch");
        String phase=args.getString("phase");if("SEED".equals(phase))seed(c);else if("VERIFY".equals(phase)){
            String before=c.getSharedPreferences("selfrun_upgrade_fixture_v1",Context.MODE_PRIVATE).getString("snapshot","");require(!before.isEmpty(),"seed checkpoint missing");require(before.equals(canonical(snapshot(c))),"task/account-binding/settings/pending-input/UID preservation mismatch");
        }else throw new IllegalStateException("unknown fixture phase");
        result.putString("fixture_status","PASS");result.putString("fixture_phase",phase);result.putString("fixture_version",VERSION);result.putString("snapshot_sha256",sha(canonical(snapshot(c))));finish(Activity.RESULT_OK,result);
    }catch(Throwable error){result.putString("fixture_status","FAIL");result.putString("fixture_error",error.getClass().getSimpleName()+": "+String.valueOf(error.getMessage()));finish(Activity.RESULT_CANCELED,result);}}
}
