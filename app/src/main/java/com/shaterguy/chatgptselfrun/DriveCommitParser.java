package com.shaterguy.chatgptselfrun;
import java.util.*;import java.util.regex.*;
final class DriveSignalParser {
 enum Type{COMMAND_RECEIVED,TURN_COMPLETED,USER_ACTION_REQUIRED,PAUSED,DONE}
 static final class Event{final Type type;final String timestamp,raw;final int cursor;Event(Type t,String ts,String r,int c){type=t;timestamp=ts;raw=r;cursor=c;}}
 static final class Scan{final List<Event> unseen;final int totalCount;final Event latest;final boolean cursorRebased;Scan(List<Event> u,int n,Event l,boolean r){unseen=u;totalCount=n;latest=l;cursorRebased=r;}}
 private static final Pattern LINE=Pattern.compile("^\\[(\\d{4}\\.\\d{2}\\.\\d{2} \\| \\d{2}:\\d{2}:\\d{2})] \\[(SELF_RUN_COMMAND_RECEIVED|SELF_RUN_TURN_COMPLETED|SELF_RUN_USER_ACTION_REQUIRED|SELF_RUN_PAUSED|SELF_RUN_DONE) ([A-Za-z0-9._-]{1,128})]$");
 private DriveSignalParser(){}
 static Scan scan(String text,String jobId,int consumed){List<Event> all=new ArrayList<>();for(String line:(text==null?"":text).split("\\r?\\n")){Matcher m=LINE.matcher(line.trim());if(!m.matches()||!jobId.equals(m.group(3)))continue;all.add(new Event(type(m.group(2)),m.group(1),m.group(0),all.size()+1));}int requested=Math.max(0,consumed);boolean rebased=requested>all.size();int base=Math.min(requested,all.size());List<Event> unseen=base>=all.size()?Collections.emptyList():new ArrayList<>(all.subList(base,all.size()));return new Scan(Collections.unmodifiableList(unseen),all.size(),all.isEmpty()?null:all.get(all.size()-1),rebased);}
 private static Type type(String s){return switch(s){case "SELF_RUN_COMMAND_RECEIVED"->Type.COMMAND_RECEIVED;case "SELF_RUN_TURN_COMPLETED"->Type.TURN_COMPLETED;case "SELF_RUN_USER_ACTION_REQUIRED"->Type.USER_ACTION_REQUIRED;case "SELF_RUN_PAUSED"->Type.PAUSED;case "SELF_RUN_DONE"->Type.DONE;default->throw new IllegalArgumentException("unknown signal");};}
}
