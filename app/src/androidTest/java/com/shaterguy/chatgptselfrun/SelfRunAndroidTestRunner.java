package com.shaterguy.chatgptselfrun;

import android.os.Bundle;
import androidx.test.runner.AndroidJUnitRunner;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Central runner for the active SelfRun 3.1 disposable-conversation contract. */
public final class SelfRunAndroidTestRunner extends AndroidJUnitRunner {
    private static final List<String> V3_REQUIRED = Arrays.asList(
            "com.shaterguy.chatgptselfrun.SelfRun31FirstConversationAndroidTest",
            "com.shaterguy.chatgptselfrun.SelfRun3RuntimeAndroidTest",
            "com.shaterguy.chatgptselfrun.SelfRun3DispatchAndroidTest",
            "com.shaterguy.chatgptselfrun.SelfRun3ParallelLedgerAndroidTest",
            "com.shaterguy.chatgptselfrun.SelfRun3InputCommitAndroidTest",
            "com.shaterguy.chatgptselfrun.RequestProfileRecreationAndroidTest",
            "com.shaterguy.chatgptselfrun.ChatReasoningProcessRecreationAndroidTest"
    );

    @Override public void onCreate(Bundle arguments) {
        Bundle args = arguments == null ? new Bundle() : new Bundle(arguments);
        String selected = args.getString("class", "").trim();
        String upgrade = "com.shaterguy.chatgptselfrun.SelfRun3UpgradePersistenceAndroidTest#";
        if (selected.equals(upgrade + "seedUpgradeState")
                || selected.equals(upgrade + "verifyUpgradeState")) {
            super.onCreate(args);
            return;
        }
        Set<String> merged = new LinkedHashSet<>();
        if (!selected.isEmpty()) {
            for (String item : selected.split(",")) {
                String value = item.trim();
                if (!value.isEmpty()) merged.add(value);
            }
        }
        merged.addAll(V3_REQUIRED);
        args.putString("class", String.join(",", new ArrayList<>(merged)));
        super.onCreate(args);
    }
}
