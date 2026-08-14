package com.shaterguy.chatgptselfrun;
import org.junit.Test;import java.nio.file.*;import static org.junit.Assert.*;
public class SelfRunDriveReleasePolicyTest {
 @Test public void releaseChannelsAreSeparated() throws Exception {String build=read(".github/workflows/build-drive-v1.yml"),release=read(".github/workflows/release-drive-v1.yml"),channels=read("docs/RELEASE_CHANNELS.md");assertTrue(build.contains("selfrun-drive/v*-dev*"));assertTrue(build.contains("selfrun-drive/v*-rc*"));assertTrue(release.contains("branches:\n      - main\n"));assertFalse(release.contains("selfrun-drive/main"));assertTrue(release.contains("DRIVE_TAG=drive-v"));assertTrue(release.contains("com.shaterguy.chatgptselfrun.drive"));assertTrue(channels.contains("Drive canonical branch: `main`"));assertTrue(channels.contains("WebView maintenance branch: `selfrun-webview/main`"));assertTrue(channels.contains("Drive `main`과 통합·병합하지 않습니다"));}
 static String read(String f)throws Exception{Path p=Paths.get(f);if(!Files.exists(p))p=Paths.get("../"+f);return new String(Files.readAllBytes(p),java.nio.charset.StandardCharsets.UTF_8);}
}
