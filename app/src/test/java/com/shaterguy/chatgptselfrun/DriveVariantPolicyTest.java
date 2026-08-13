package com.shaterguy.chatgptselfrun;
import org.junit.Test;import java.nio.file.*;import static org.junit.Assert.*;
public class DriveVariantPolicyTest {
 @Test public void stableIdentityDefaultChatAndKeyboardVisibility() throws Exception {String g=read("app/build.gradle","build.gradle"),a=src("SelfRunNewActivity.java");assertTrue(g.contains("selfRunDriveVersionCode = 1000004"));assertTrue(g.contains("selfRunDriveVersionName = '1.0.0'"));assertTrue(g.contains("com.shaterguy.chatgptselfrun.drive"));assertTrue(a.contains("MODE_VALUES = {SelfRunStore.MODE_CHAT, SelfRunStore.MODE_WORK}"));assertTrue(a.contains("requestRectangleOnScreen"));assertTrue(a.contains("addTextChangedListener"));assertTrue(a.contains("RUN_SUFFIX_LENGTH = 6"));assertTrue(a.contains("Asia/Seoul"));assertFalse(a.contains("UUID.randomUUID"));}
 static String src(String f)throws Exception{return read("app/src/main/java/com/shaterguy/chatgptselfrun/"+f,"src/main/java/com/shaterguy/chatgptselfrun/"+f);}static String read(String a,String b)throws Exception{Path p=Paths.get(a);if(!Files.exists(p))p=Paths.get(b);return new String(Files.readAllBytes(p),java.nio.charset.StandardCharsets.UTF_8);}
}
