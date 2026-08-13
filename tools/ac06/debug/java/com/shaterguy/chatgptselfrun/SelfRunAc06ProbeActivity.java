package com.shaterguy.chatgptselfrun;

import android.app.Activity;
import android.os.Bundle;
import android.os.Process;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public final class SelfRunAc06ProbeActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String mode = getIntent().getStringExtra("mode");
        new Thread(() -> {
            try {
                JSONObject result = switch (mode == null ? "" : mode) {
                    case "matrix" -> SelfRunAc06MatrixRunner.run(this);
                    case "process_seed" -> SelfRunAc06RecoveryRunner.processSeed(this);
                    case "process_verify" -> SelfRunAc06RecoveryRunner.processVerify(this);
                    case "renderer" -> SelfRunAc06RecoveryRunner.rendererRecovery(this);
                    default -> error("unknown_mode:" + mode);
                };
                write(mode == null || mode.isEmpty() ? "error" : mode.replace('_', '-'), result);
            } catch (Throwable failure) {
                try {
                    write(mode == null || mode.isEmpty() ? "error" : mode.replace('_', '-'),
                            error(failure.getClass().getSimpleName() + ":" + String.valueOf(failure.getMessage())));
                } catch (Throwable ignored) {}
            } finally {
                // Keep the seed process alive. The host script must be the thing that kills it.
                if (!"process_seed".equals(mode)) runOnUiThread(this::finish);
            }
        }, "SelfRunAc06Probe").start();
    }

    private void write(String name, JSONObject value) throws Exception {
        File file = new File(getFilesDir(), "ac06-" + name + ".json");
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write((value.toString() + "\n").getBytes(StandardCharsets.UTF_8));
        }
    }

    private JSONObject error(String detail) throws Exception {
        JSONObject out = new JSONObject();
        out.put("error", detail == null ? "" : detail);
        out.put("pid", Process.myPid());
        return out;
    }
}
