package com.shaterguy.chatgptselfrun;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import com.google.android.gms.auth.api.identity.AuthorizationClient;
import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.Scope;

import java.util.Arrays;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;

/** Google Identity Services authorization: app-owned Drive writes plus read-only cross-app signal discovery. */
final class DriveAuthorization {
    static final String DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file";
    static final String DRIVE_METADATA_READONLY_SCOPE = "https://www.googleapis.com/auth/drive.metadata.readonly";
    static final String DOCUMENTS_READONLY_SCOPE = "https://www.googleapis.com/auth/documents.readonly";
    static final String PICKED_FILE_IDS = "picked_file_ids";

    interface Callback {
        void onAuthorized(AuthorizationResult result);
        void onResolutionRequired(PendingIntent pendingIntent);
        void onFailure(Throwable error);
    }

    private DriveAuthorization() {}

    private static List<Scope> runtimeScopes() {
        return Arrays.asList(
                new Scope(DRIVE_FILE_SCOPE),
                new Scope(DRIVE_METADATA_READONLY_SCOPE),
                new Scope(DOCUMENTS_READONLY_SCOPE));
    }

    private static List<Scope> pickerScopes() {
        return Collections.singletonList(new Scope(DRIVE_FILE_SCOPE));
    }

    static AuthorizationRequest silentRequest() {
        return AuthorizationRequest.builder()
                .setRequestedScopes(runtimeScopes())
                .setOptOutIncludingGrantedScopes(true)
                .build();
    }

    static AuthorizationRequest folderPickerRequest() {
        return AuthorizationRequest.builder()
                .setRequestedScopes(pickerScopes())
                .setOptOutIncludingGrantedScopes(true)
                .setPrompt(AuthorizationRequest.Prompt.CONSENT)
                .addResourceParameter(AuthorizationRequest.ResourceParameter.PICKER_OAUTH_TRIGGER, "true")
                .addResourceParameter(AuthorizationRequest.ResourceParameter.PICKER_ALLOW_FOLDER_SELECTION, "true")
                .addResourceParameter(AuthorizationRequest.ResourceParameter.PICKER_ALLOW_MULTIPLE, "false")
                .addResourceParameter(AuthorizationRequest.ResourceParameter.PICKER_MIMETYPES,
                        DriveApiClient.MIME_FOLDER)
                .build();
    }

    static void requestSilently(Context context, Callback callback) {
        request(Identity.getAuthorizationClient(context), silentRequest(), callback);
    }

    static void requestFolderPicker(Activity activity, Callback callback) {
        request(Identity.getAuthorizationClient(activity), folderPickerRequest(), callback);
    }

    private static void request(AuthorizationClient client, AuthorizationRequest request, Callback callback) {
        client.authorize(request)
                .addOnSuccessListener(result -> {
                    if (result.hasResolution()) {
                        if (result.getPendingIntent() == null) {
                            callback.onFailure(new IllegalStateException("authorization resolution missing"));
                            return;
                        }
                        callback.onResolutionRequired(result.getPendingIntent());
                    } else {
                        callback.onAuthorized(result);
                    }
                })
                .addOnFailureListener(callback::onFailure);
    }

    static AuthorizationResult fromIntent(Context context, Intent data) throws ApiException {
        return Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data);
    }

    static String accessToken(AuthorizationResult result) {
        if (result == null || result.getAccessToken() == null) return "";
        return result.getAccessToken();
    }

    static String pickedFolderId(AuthorizationResult result) {
        if (result == null) return "";
        Bundle values = result.getTokenResponseParams();
        if (values == null) return "";
        Object raw = values.get(PICKED_FILE_IDS);
        if (raw == null) return "";
        List<String> ids = normalizePickedIds(raw);
        if (ids.size() != 1) return "";
        String id = ids.get(0).trim();
        return DriveApiClient.validFileId(id) ? id : "";
    }

    static List<String> normalizePickedIds(Object raw) {
        ArrayList<String> values = new ArrayList<>();
        if (raw instanceof String value) {
            // The documented single-selection response is one opaque ID. A comma means ambiguity.
            if (!value.trim().isEmpty() && !value.contains(",")) values.add(value);
        } else if (raw instanceof String[] array) {
            Collections.addAll(values, array);
        } else if (raw instanceof List<?> list) {
            for (Object value : list) {
                if (!(value instanceof String)) return Collections.emptyList();
                values.add((String) value);
            }
        }
        values.removeIf(value -> value == null || value.trim().isEmpty());
        return values;
    }
}
