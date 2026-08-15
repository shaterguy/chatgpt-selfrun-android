package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.util.Base64;

import static org.junit.Assert.*;

public class NextInputCodecTest {
    @Test public void roundTripsKoreanMultilineQuotesBracketsEqualsEmojiSpacesAndControlCharacters() {
        String input = "  승인할게.\n둘째 줄 \\\"quote\\\" ' ] = 😎\t\u0001 끝  ";
        String encoded = NextInputCodec.encode(input);
        assertFalse(encoded.contains("="));
        assertFalse(encoded.contains("\n"));
        NextInputCodec.Decoded decoded = NextInputCodec.decodeToken(encoded);
        assertTrue(decoded.valid);
        assertEquals(input, decoded.text);
        assertEquals(encoded, decoded.encoded);
        assertEquals(64, decoded.fingerprint.length());
    }

    @Test public void rejectsNonCanonicalPaddingAndMalformedUtf8() {
        assertFalse(NextInputCodec.decodeToken("YQ==").valid);
        String malformed = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[]{(byte) 0xc3, (byte) 0x28});
        NextInputCodec.Decoded decoded = NextInputCodec.decodeToken(malformed);
        assertFalse(decoded.valid);
        assertEquals("NEXT_INPUT_UTF8_INVALID", decoded.error);
    }

    @Test public void rejectsEmptyAndOversizedEncodedInputBeforeDecode() {
        assertFalse(NextInputCodec.decodeToken("").valid);
        String oversized = "A".repeat(NextInputCodec.MAX_ENCODED_CHARS + 1);
        NextInputCodec.Decoded decoded = NextInputCodec.decodeToken(oversized);
        assertFalse(decoded.valid);
        assertEquals("NEXT_INPUT_ENCODED_TOO_LARGE", decoded.error);
    }

    @Test public void longPayloadWithinLimitRoundTrips() {
        String input = "가".repeat(50_000);
        assertEquals(input, NextInputCodec.decodeToken(NextInputCodec.encode(input)).text);
    }
}
