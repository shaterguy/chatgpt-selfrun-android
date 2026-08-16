package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

public class SelfRunNewRunIsolationTest {
    @Test public void newRunWaitsForPreviousServiceToDisappearBeforeStoreStart() throws Exception {
        String source = src("SelfRunNewActivity.java");
        int stop = source.indexOf("stopService(new Intent(this,SelfRunService.class));");
        int fence = source.indexOf("waitForPreviousRuntimeShutdown(selectedProject,request,selectedMode,runId,0,0);");
        int start = source.indexOf("store.start(runId,selectedMode,selectedProject,request);");
        assertTrue(stop >= 0);
        assertTrue(fence > stop);
        assertTrue(start > fence);
        assertTrue(source.contains("SERVICE_STOP_REQUIRED_CLEAR_POLLS = 2"));
        assertTrue(source.contains("if(nextClear>=SERVICE_STOP_REQUIRED_CLEAR_POLLS)"));
    }

    @Test public void repeatedStartTapCannotCreateTwoPendingRuns() throws Exception {
        String source = src("SelfRunNewActivity.java");
        assertTrue(source.contains("if(startPending)"));
        assertTrue(source.contains("startPending=true;"));
        assertTrue(source.contains("startPending=false;"));
    }

    @Test public void shutdownFenceTargetsOnlyThisApplicationsSelfRunService() throws Exception {
        String source = src("SelfRunNewActivity.java");
        assertTrue(source.contains("getPackageName().equals(info.service.getPackageName())"));
        assertTrue(source.contains("SelfRunService.class.getName().equals(info.service.getClassName())"));
    }

    private static String src(String file) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + file);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + file);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
