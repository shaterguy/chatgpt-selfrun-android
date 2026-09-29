package com.shaterguy.chatgptselfrun;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/** Test-only canonical registry fixtures for instrumentation isolation. */
final class CanonicalProfileAndroidTestFixtures {
    private static final String PREFS = "selfrun_drive_profile_registry";

    private CanonicalProfileAndroidTestFixtures() {}

    static void install(Context context) {
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit();
        ProfileRegistry.resetForTests();
        ProfileRegistry.initialize(app);
        ProfileRegistry.replaceCanonicalSnapshot(
                ProfileRegistry.Mode.CHAT, chatRegistry(), "androidTest-chat");
        ProfileRegistry.replaceCanonicalSnapshot(
                ProfileRegistry.Mode.WORK, workRegistry(), "androidTest-work");
    }

    static void clear(Context context) {
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit();
        ProfileRegistry.resetForTests();
    }

    private static String chatRegistry() {
        return registry(ProfileRegistry.Mode.CHAT, List.of(
                profile(ProfileRegistry.Mode.CHAT, "", "medium", List.of(
                        ProfileRegistry.Operation.set("model", "gpt-5-6-thinking"),
                        ProfileRegistry.Operation.set("thinking_effort", "standard"),
                        ProfileRegistry.Operation.remove("conversation_origin"),
                        ProfileRegistry.Operation.remove("service_tier"))),
                profile(ProfileRegistry.Mode.CHAT, "", "xhigh", List.of(
                        ProfileRegistry.Operation.set("model", "gpt-5-6-thinking"),
                        ProfileRegistry.Operation.set("thinking_effort", "max"),
                        ProfileRegistry.Operation.remove("conversation_origin"),
                        ProfileRegistry.Operation.remove("service_tier")))
        ));
    }

    private static String workRegistry() {
        return registry(ProfileRegistry.Mode.WORK, List.of(
                profile(ProfileRegistry.Mode.WORK, "5.6luna", "max", List.of(
                        ProfileRegistry.Operation.set("model", "gpt-5.6-luna-wm"),
                        ProfileRegistry.Operation.set("thinking_effort", "max"),
                        ProfileRegistry.Operation.set("conversation_origin", "tpp"),
                        ProfileRegistry.Operation.set("service_tier", "standard"))),
                profile(ProfileRegistry.Mode.WORK, "5.6sol", "xhigh", List.of(
                        ProfileRegistry.Operation.set("model", "gpt-5.6-sol-wm"),
                        ProfileRegistry.Operation.set("thinking_effort", "xhigh"),
                        ProfileRegistry.Operation.set("conversation_origin", "tpp"),
                        ProfileRegistry.Operation.set("service_tier", "standard")))
        ));
    }

    private static String registry(ProfileRegistry.Mode mode, List<JSONObject> profiles) {
        JSONObject root = new JSONObject();
        put(root, "schema", mode == ProfileRegistry.Mode.CHAT
                ? ProfileRegistry.CHAT_EXPORT_SCHEMA : ProfileRegistry.WORK_EXPORT_SCHEMA);
        put(root, "registrySchemaVersion", ProfileRegistry.SCHEMA_VERSION);
        put(root, "appVersion", "androidTest");
        JSONArray array = new JSONArray();
        for (JSONObject profile : profiles) array.put(profile);
        put(root, "profiles", array);
        return root.toString();
    }

    private static JSONObject profile(ProfileRegistry.Mode mode, String model, String reasoning,
                                      List<ProfileRegistry.Operation> operations) {
        JSONObject item = new JSONObject();
        JSONObject signal = new JSONObject();
        if (mode == ProfileRegistry.Mode.WORK) put(signal, "model", model);
        put(signal, "reasoning", reasoning);
        put(item, "signal", signal);

        JSONObject request = new JSONObject();
        JSONArray ops = new JSONArray();
        for (ProfileRegistry.Operation operation : operations) {
            ops.put(operation.toJson());
            if (operation.kind == ProfileRegistry.OperationKind.SET) {
                put(request, operation.path, operation.value);
            }
        }
        put(item, "request", request);
        put(item, "operations", ops);
        put(item, "fingerprint", ProfileRegistry.fingerprint(mode, operations));
        put(item, "builtIn", false);
        return item;
    }

    private static void put(JSONObject object, String key, Object value) {
        try {
            object.put(key, value);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }
}
