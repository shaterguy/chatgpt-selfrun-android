package com.shaterguy.chatgptselfrun;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
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
        final String error;
        private Decoded(boolean present, boolean valid, String text, String error) {
  this.present = present;
  this.valid = valid;
  this.text = text;
  this.error = error;
        }
    }

    private NextInputCodec() {}

    static Decoded absent() { return new Decoded(false, true, "", ""); }

    static Decoded decodeToken(String encoded) {
        if (encoded == null) return absent();
        if (encoded.isEmpty()) return invalid("NEXT_INPUT_EMPTY");
        if (encoded.length() > MAX_ENCODED_CHARS) return invalid("NEXT_INPUT_ENCODED_TOO_LARGE");
        if (!CANONICAL.matcher(encoded).matches()) return invalid("NEXT_INPUT_B64URL_NON_CANONICAL");
        final byte[] bytes;
        try { bytes = Base64.getUrlDecoder().decode(encoded); }
        catch (IllegalArgumentException error) { return invalid("NEXT_INPUT_B64URL_DECODE_FAILED"); }
        if (bytes.length > MAX_UTF8_BYTES) return invalid("NEXT_INPUT_DECODED_TOO_LARGE");
        if (!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(encoded)) {
  return invalid("NEXT_INPUT_B64URL_NON_CANONICAL");
        }
        final String text;
        try {
  text = StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException error) {
  return invalid("NEXT_INPUT_UTF8_INVALID");
        }
        if (text.isEmpty()) return invalid("NEXT_INPUT_EMPTY");
        return new Decoded(true, true, text, "");
    }

    private static Decoded invalid(String error) { return new Decoded(true, false, "", error); }
}
