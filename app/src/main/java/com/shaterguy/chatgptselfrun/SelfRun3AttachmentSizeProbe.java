package com.shaterguy.chatgptselfrun;

import java.io.IOException;
import java.io.InputStream;
import java.util.function.BooleanSupplier;

/** Streaming size discovery for content URIs whose provider does not expose a length. */
final class SelfRun3AttachmentSizeProbe {
    private static final int BUFFER_BYTES = 64 * 1024;

    static long count(InputStream in, BooleanSupplier permitted) throws IOException {
        if (in == null) throw new IOException("ATTACHMENT_UNAVAILABLE");
        if (permitted == null) throw new IOException("OPERATION_CANCELLED");
        long size = 0L;
        byte[] buffer = new byte[BUFFER_BYTES];
        int n;
        while ((n = in.read(buffer)) != -1) {
            if (!permitted.getAsBoolean()) throw new IOException("OPERATION_CANCELLED");
            if (size > Long.MAX_VALUE - n) throw new IOException("ATTACHMENT_SIZE_OVERFLOW");
            size += n;
        }
        return size;
    }

    private SelfRun3AttachmentSizeProbe() { }
}
