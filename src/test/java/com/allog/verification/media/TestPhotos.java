package com.allog.verification.media;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/** Real image bytes for tests that cross the provider-bound or storage sanitization boundary. */
public final class TestPhotos {

    private TestPhotos() {
    }

    public static byte[] jpeg(int width, int height) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "jpeg", out)) {
                throw new IllegalStateException("no jpeg writer available");
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return out.toByteArray();
    }

    /** Splices an APP1 segment carrying the given payload straight after the SOI marker. */
    public static byte[] withApp1(byte[] jpeg, byte[] payload) {
        int length = payload.length + 2;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);
        out.write(0xFF);
        out.write(0xE1);
        out.write(length >>> 8);
        out.write(length);
        out.writeBytes(payload);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }
}
