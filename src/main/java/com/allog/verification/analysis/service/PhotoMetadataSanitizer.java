package com.allog.verification.analysis.service;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;

/**
 * Removes EXIF/GPS and every other metadata block from photo bytes before they leave for an AI provider.
 *
 * <p>This is an allowlist byte filter, not a re-encode: compressed pixel data is copied verbatim, so the
 * image the provider decodes is bit-identical to the original at full resolution. Unsupported formats and
 * malformed bytes fail closed - the original bytes are never returned to the caller.
 */
final class PhotoMetadataSanitizer {

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    /** PNG chunks needed to decode the image. Everything else (eXIf, tEXt, iTXt, zTXt, tIME, ...) is dropped. */
    private static final Set<String> PNG_KEPT_CHUNKS = Set.of(
            "IHDR", "PLTE", "IDAT", "IEND", "tRNS", "gAMA", "cHRM", "sRGB", "sBIT", "pHYs", "iCCP"
    );

    private PhotoMetadataSanitizer() {
    }

    static byte[] strip(String contentType, byte[] content) {
        Objects.requireNonNull(content, "content must not be null");
        return switch (Objects.requireNonNull(contentType, "contentType must not be null")) {
            case "image/jpeg" -> stripJpeg(content);
            case "image/png" -> stripPng(content);
            default -> throw new SanitizationException("photo content type cannot be sanitized");
        };
    }

    /**
     * Drops APP1..APP15 (EXIF, GPS, XMP, maker notes) and COM comments. APP0/JFIF carries no personal data
     * and is kept so decoders keep their density hints.
     */
    private static byte[] stripJpeg(byte[] content) {
        if (content.length < 4 || (content[0] & 0xFF) != 0xFF || (content[1] & 0xFF) != 0xD8) {
            throw new SanitizationException("jpeg start of image marker is missing");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(content.length);
        out.write(0xFF);
        out.write(0xD8);
        int index = 2;
        while (true) {
            int fillStart = index;
            while (index < content.length && (content[index] & 0xFF) == 0xFF) {
                index++;
            }
            if (index == fillStart || index >= content.length) {
                throw new SanitizationException("jpeg marker is missing");
            }
            int marker = content[index++] & 0xFF;
            if (marker == 0xD9) {
                out.write(0xFF);
                out.write(marker);
                return sanitized(out);
            }
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                out.write(0xFF);
                out.write(marker);
                continue;
            }
            int length = readUnsignedShort(content, index);
            if (length < 2 || index + length > content.length) {
                throw new SanitizationException("jpeg segment length is out of bounds");
            }
            if (!isDroppedJpegSegment(marker)) {
                out.write(0xFF);
                out.write(marker);
                out.write(content, index, length);
            }
            index += length;
            if (marker == 0xDA) {
                out.write(content, index, content.length - index);
                return sanitized(out);
            }
        }
    }

    private static boolean isDroppedJpegSegment(int marker) {
        return (marker >= 0xE1 && marker <= 0xEF) || marker == 0xFE;
    }

    private static byte[] stripPng(byte[] content) {
        if (content.length < PNG_SIGNATURE.length
                || !Arrays.equals(content, 0, PNG_SIGNATURE.length, PNG_SIGNATURE, 0, PNG_SIGNATURE.length)) {
            throw new SanitizationException("png signature is missing");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(content.length);
        out.write(PNG_SIGNATURE, 0, PNG_SIGNATURE.length);
        int index = PNG_SIGNATURE.length;
        boolean firstChunk = true;
        while (true) {
            if (index + 8 > content.length) {
                throw new SanitizationException("png chunk header is truncated");
            }
            long dataLength = readUnsignedInt(content, index);
            String type = new String(content, index + 4, 4, StandardCharsets.US_ASCII);
            if (firstChunk && !"IHDR".equals(type)) {
                throw new SanitizationException("png does not start with IHDR");
            }
            firstChunk = false;
            if (dataLength > Integer.MAX_VALUE - 12 || index + 12 + dataLength > content.length) {
                throw new SanitizationException("png chunk length is out of bounds");
            }
            int chunkLength = (int) dataLength + 12;
            if (PNG_KEPT_CHUNKS.contains(type)) {
                out.write(content, index, chunkLength);
            }
            index += chunkLength;
            if ("IEND".equals(type)) {
                return sanitized(out);
            }
        }
    }

    private static int readUnsignedShort(byte[] content, int index) {
        if (index + 2 > content.length) {
            throw new SanitizationException("jpeg segment length is truncated");
        }
        return ((content[index] & 0xFF) << 8) | (content[index + 1] & 0xFF);
    }

    private static long readUnsignedInt(byte[] content, int index) {
        return ByteBuffer.wrap(content, index, 4).getInt() & 0xFFFFFFFFL;
    }

    private static byte[] sanitized(ByteArrayOutputStream out) {
        byte[] result = out.toByteArray();
        if (result.length == 0) {
            throw new SanitizationException("sanitized photo is empty");
        }
        return result;
    }

    /** Carries no image bytes: the message must never leak the payload it rejected. */
    static final class SanitizationException extends RuntimeException {

        SanitizationException(String message) {
            super(message);
        }
    }
}
