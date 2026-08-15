package com.shaterguy.chatgptselfrun;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.regex.Pattern;

final class NextInputCodec {
    static final int MAX_ENCODED_CHARS = 900_000;
    static final int MAX_UTF8_BYTES = 675_000;
    private static final Pattern CANONICAL = Pattern.compile("[A-Za-z0-9_-]+");

    static final class Decoded {
        final boolean present;
        final boolean valid;
        final String text;
        final String encoded;
        final String fingerprint;
        final String error;

        private Decoded(boolean present, boolean valid, String text, String encoded,
                        String fingerprint, String error) {
            this.present = present;
            this.valid = valid;
            this.text = text;
            this.encoded = encoded;
            this.fingerprint = fingerprint;
            this.error = error;
        }
    }

    private NextInputCodec() {}

    static Decoded absent() {
        return new Decoded(false, true, "", "", "", "");
    }

    static Decoded decodeToken(String encoded) {
        if (encoded == null) return absent();
        if (encoded.isEmpty()) return invalid(encoded, "NEXT_INPUT_EMPTY");
        if (encoded.length() > MAX_ENCODED_CHARS) return invalid(encoded, "NEXT_INPUT_ENCODED_TOO_LARGE");
        if (!CANONICAL.matcher(encoded).matches()) return invalid(encoded, "NEXT_INPUT_B64URL_NON_CANONICAL");
        final byte[] bytes;
        try {
            bytes = Base64.getUrlDecoder().decode(encoded);
        } catch (IllegalArgumentException error) {
            return invalid(encoded, "NEXT_INPUT_B64URL_DECODE_FAILED");
        }
        if (bytes.length > MAX_UTF8_BYTES) return invalid(encoded, "NEXT_INPUT_DECODED_TOO_LARGE");
        String canonical = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        if (!canonical.equals(encoded)) return invalid(encoded, "NEXT_INPUT_B64URL_NON_CANONICAL");
        final String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException error) {
            return invalid(encoded, "NEXT_INPUT_UTF8_INVALID");
        }
        if (text.isEmpty()) return invalid(encoded, "NEXT_INPUT_EMPTY");
        return new Decoded(true, true, text, encoded, fingerprint(bytes), "");
    }

    static String encode(String text) {
        if (text == null || text.isEmpty()) throw new IllegalArgumentException("NEXT_INPUT must not be empty");
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_UTF8_BYTES) throw new IllegalArgumentException("NEXT_INPUT is too large");
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        if (encoded.length() > MAX_ENCODED_CHARS) throw new IllegalArgumentException("NEXT_INPUT encoding is too large");
        return encoded;
    }

    static String fingerprintText(String text) {
        return fingerprint((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
    }

    private static Decoded invalid(String encoded, String error) {
        return new Decoded(true, false, "", encoded == null ? "" : encoded, "", error);
    }

    private static String fingerprint(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte b : digest) out.append(String.format("%02x", b & 0xff));
            return out.toString();
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }
}
