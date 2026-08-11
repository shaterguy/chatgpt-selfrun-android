package com.shaterguy.chatgptselfrun;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

final class SelfRunCommandBridgeClient {
    enum Status {
        SUCCESS,
        EMPTY,
        FAILURE
    }

    static final class Result {
        final Status status;
        final String command;
        final String savedAt;
        final String message;

        private Result(Status status, String command, String savedAt, String message) {
            this.status = status;
            this.command = command;
            this.savedAt = savedAt;
            this.message = message;
        }

        static Result success(String command, String savedAt) {
            return new Result(Status.SUCCESS, command, savedAt, "");
        }

        static Result empty(String message) {
            return new Result(Status.EMPTY, "", "", message);
        }

        static Result failure(String message) {
            return new Result(Status.FAILURE, "", "", message);
        }
    }

    private SelfRunCommandBridgeClient() {
    }

    static Result fetch(String endpoint, String token) {
        if (endpoint == null || endpoint.trim().isEmpty()) {
            return Result.failure("브리지 주소가 설정되지 않았습니다.");
        }
        if (token == null || token.isEmpty()) {
            return Result.failure("브리지 읽기 토큰이 설정되지 않았습니다.");
        }

        HttpURLConnection connection = null;
        try {
            URL url = new URL(endpoint);
            if (!"https".equalsIgnoreCase(url.getProtocol())) {
                return Result.failure("브리지 주소는 HTTPS여야 합니다.");
            }
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(15_000);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Cache-Control", "no-cache");
            connection.setRequestProperty("Authorization", "Bearer " + token);

            int statusCode = connection.getResponseCode();
            String body = readBody(statusCode >= 400 ? connection.getErrorStream() : connection.getInputStream());
            return parseResponse(statusCode, body);
        } catch (Exception error) {
            String message = error.getMessage();
            if (message == null || message.trim().isEmpty()) {
                message = error.getClass().getSimpleName();
            }
            return Result.failure("브리지 연결 실패: " + message);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    static Result parseResponse(int statusCode, String body) {
        if (statusCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
            return Result.failure("브리지 인증에 실패했습니다.");
        }
        if (statusCode == 503) {
            return Result.failure("브리지가 아직 구성되지 않았습니다.");
        }
        if (statusCode < 200 || statusCode >= 300) {
            return Result.failure("브리지 요청이 실패했습니다. HTTP " + statusCode);
        }

        try {
            JSONObject json = new JSONObject(body == null ? "" : body);
            String status = json.optString("status", "");
            if ("empty".equals(status)) {
                return Result.empty("저장된 최신 명령이 없습니다.");
            }
            if (!"ok".equals(status)) {
                return Result.failure("브리지 응답 형식이 올바르지 않습니다.");
            }

            String command = json.optString("command", "");
            String savedAt = json.optString("saved_at", "");
            String serverHash = json.optString("hash", "").toLowerCase(Locale.US);
            return validatePayload(status, command, savedAt, serverHash);
        } catch (Exception error) {
            return Result.failure("브리지 응답을 해석할 수 없습니다.");
        }
    }

    static Result validatePayload(String status, String command, String savedAt, String serverHash) {
        if ("empty".equals(status)) {
            return Result.empty("저장된 최신 명령이 없습니다.");
        }
        if (!"ok".equals(status)) {
            return Result.failure("브리지 응답 형식이 올바르지 않습니다.");
        }
        if (command == null || command.isEmpty()
                || savedAt == null || savedAt.isEmpty()
                || serverHash == null || serverHash.isEmpty()) {
            return Result.failure("브리지 응답에 필수 값이 없습니다.");
        }
        try {
            if (!serverHash.toLowerCase(Locale.US).equals(sha256(command))) {
                return Result.failure("브리지 명령 무결성 검증에 실패했습니다.");
            }
        } catch (NoSuchAlgorithmException error) {
            return Result.failure("브리지 명령 무결성 검증을 실행할 수 없습니다.");
        }
        return Result.success(command, savedAt);
    }

    static String commandForInput(String currentInput, Result result) {
        if (result != null && result.status == Status.SUCCESS) {
            return result.command;
        }
        return currentInput == null ? "" : currentInput;
    }

    private static String readBody(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > 2 * 1024 * 1024) {
                    throw new IOException("응답이 너무 큽니다.");
                }
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    static String sha256(String value) throws NoSuchAlgorithmException {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte item : digest) {
            hex.append(String.format(Locale.US, "%02x", item & 0xff));
        }
        return hex.toString();
    }
}
