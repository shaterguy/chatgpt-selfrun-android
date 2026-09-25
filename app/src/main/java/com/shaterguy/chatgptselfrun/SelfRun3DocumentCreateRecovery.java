package com.shaterguy.chatgptselfrun;

import org.json.JSONObject;

/** Durable Result/Requirement document-create reconciliation around a Drive change-log cursor. */
final class SelfRun3DocumentCreateRecovery {
    private static final int INTENT_VERSION = 2;

    interface Store {
        String documentId();
        String createIntent();
        String createState();
        void pinIntent(String value) throws Exception;
        void markSubmitting() throws Exception;
        void markRetryable() throws Exception;
        void pinDocument(String id) throws Exception;
    }

    static final class PendingOutcomeException extends java.io.IOException {
        PendingOutcomeException() {
            super("DOCUMENT_CREATE_OUTCOME_PENDING");
        }
    }

    interface Remote {
        String findExact() throws Exception;
        String currentChangeToken() throws Exception;
        String findCreatedSince(String changeToken) throws Exception;
        String create() throws Exception;
    }

    static String ensure(String expectedName, Store store, Remote remote) throws Exception {
        requireName(expectedName);
        String pinned = validDocumentIdOrEmpty(store.documentId());
        if (!pinned.isEmpty()) return pinned;

        String exact = validDocumentIdOrEmpty(remote.findExact());
        if (!exact.isEmpty()) {
            store.pinDocument(exact);
            return exact;
        }
        String rawIntent = store.createIntent();
        Intent intent;
        if (rawIntent == null || rawIntent.isEmpty()) {
            String token = requireToken(remote.currentChangeToken());
            intent = new Intent(expectedName, token, false);
            store.pinIntent(intent.encode());
        } else {
            intent = Intent.parse(rawIntent, expectedName);
            if (intent.legacy) {
                throw new IllegalStateException("DOCUMENT_CREATE_LEGACY_UNCONFIRMED");
            }
            String recovered = validDocumentIdOrEmpty(remote.findCreatedSince(intent.changeToken));
            if (!recovered.isEmpty()) {
                store.pinDocument(recovered);
                return recovered;
            }
        }

        String state = store.createState();
        if ("SUBMITTING".equals(state)) {
            throw new PendingOutcomeException();
        }
        if (!state.isEmpty() && !"RETRYABLE".equals(state)) {
            throw new IllegalStateException("DOCUMENT_CREATE_STATE_INVALID");
        }
        return createAndPin(store, remote);
    }

    private static String createAndPin(Store store, Remote remote) throws Exception {
        store.markSubmitting();
        try {
            String created = validDocumentIdOrEmpty(remote.create());
            if (created.isEmpty()) throw new IllegalStateException("DOCUMENT_CREATE_ID_MISSING");
            store.pinDocument(created);
            return created;
        } catch (DriveApiClient.CreateNotSubmittedException definitelyNotSubmitted) {
            store.markRetryable();
            throw definitelyNotSubmitted;
        } catch (DriveApiClient.ApiException rejected) {
            if (rejected.status == 408 || rejected.status == 429) store.markRetryable();
            throw rejected;
        }
    }

    private static String validDocumentIdOrEmpty(String value) {
        if (value == null || value.isEmpty()) return "";
        if (!DriveApiClient.validFileId(value)) {
            throw new IllegalStateException("DOCUMENT_CREATE_ID_INVALID");
        }
        return value;
    }
    private static void requireName(String name) {
        if (name == null || !name.matches("[A-Za-z0-9._:-]{1,240}")) {
            throw new IllegalArgumentException("safe V3 document name required");
        }
    }

    private static String requireToken(String token) {
        if (token == null || token.isEmpty() || token.length() > 1024) {
            throw new IllegalStateException("DOCUMENT_CREATE_CHANGE_TOKEN_INVALID");
        }
        for (int i = 0; i < token.length(); i++) {
            char ch = token.charAt(i);
            if (ch < 0x21 || ch > 0x7e) {
                throw new IllegalStateException("DOCUMENT_CREATE_CHANGE_TOKEN_INVALID");
            }
        }
        return token;
    }

    static final class Intent {
        final String name;
        final String changeToken;
        final boolean legacy;

        Intent(String name, String changeToken, boolean legacy) {
            this.name = name;
            this.changeToken = changeToken;
            this.legacy = legacy;
        }

        String encode() {
            JSONObject encoded = new JSONObject();
            SelfRun3Engine.put(encoded, "v", INTENT_VERSION);
            SelfRun3Engine.put(encoded, "name", name);
            SelfRun3Engine.put(encoded, "change_token", changeToken);
            return encoded.toString();
        }
        static Intent parse(String raw, String expectedName) {
            if (raw == null || raw.isEmpty()) {
                throw new IllegalStateException("DOCUMENT_CREATE_INTENT_MISSING");
            }
            if (raw.equals(expectedName)) {
                return new Intent(expectedName, "", true);
            }
            try {
                JSONObject value = new JSONObject(raw);
                int version = value.optInt("v", -1);
                String name = value.optString("name", "");
                String token = value.optString("change_token", "");
                if (version != INTENT_VERSION || !expectedName.equals(name)) {
                    throw new IllegalStateException("DOCUMENT_CREATE_INTENT_INVALID");
                }
                return new Intent(name, requireToken(token), false);
            } catch (IllegalStateException invalid) {
                throw invalid;
            } catch (Throwable invalid) {
                throw new IllegalStateException("DOCUMENT_CREATE_INTENT_INVALID", invalid);
            }
        }
    }

    private SelfRun3DocumentCreateRecovery() {}
}
