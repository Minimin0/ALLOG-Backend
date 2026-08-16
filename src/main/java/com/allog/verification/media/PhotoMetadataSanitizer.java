package com.allog.verification.media;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;

/**
 * Removes EXIF/GPS and every other metadata block from photo bytes, keeping only the orientation hint.
 *
 * <p>This is an allowlist byte filter, not a re-encode: compressed pixel data is copied verbatim, so the
 * image a decoder produces is bit-identical to the original at full resolution. The one piece of metadata
 * that survives is EXIF Orientation, rewritten into a fresh minimal APP1 segment - it carries no personal
 * information but decides whether a portrait photo is read upright. Unsupported formats and malformed bytes
 * fail closed: the original bytes are never returned to the caller.
 */
public final class PhotoMetadataSanitizer {

    private static final Set<String> SUPPORTED_CONTENT_TYPES = Set.of("image/jpeg", "image/png");

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    /** PNG chunks needed to decode the image. Everything else (eXIf, tEXt, iTXt, zTXt, tIME, ...) is dropped. */
    private static final Set<String> PNG_KEPT_CHUNKS = Set.of(
            "IHDR", "PLTE", "IDAT", "IEND", "tRNS", "gAMA", "cHRM", "sRGB", "sBIT", "pHYs", "iCCP"
    );

    /** APP1 EXIF payloads start with "Exif" and two NUL padding bytes. */
    private static final byte[] EXIF_IDENTIFIER = "Exif\0\0".getBytes(StandardCharsets.US_ASCII);

    private static final int ORIENTATION_TAG = 0x0112;
    private static final int SHORT_TYPE = 3;

    private PhotoMetadataSanitizer() {
    }

    /** Content types this sanitizer can prove clean. Anything else must not be forwarded or stored. */
    public static boolean supports(String contentType) {
        return SUPPORTED_CONTENT_TYPES.contains(contentType);
    }

    public static byte[] strip(String contentType, byte[] content) {
        Objects.requireNonNull(content, "content must not be null");
        return switch (Objects.requireNonNull(contentType, "contentType must not be null")) {
            case "image/jpeg" -> stripJpeg(content);
            case "image/png" -> stripPng(content);
            default -> throw new SanitizationException("photo content type cannot be sanitized");
        };
    }

    /**
     * Drops APP1..APP15 (EXIF, GPS, XMP, maker notes) and COM comments, then re-emits an APP1 holding
     * nothing but Orientation. APP0/JFIF carries no personal data and is kept for its density hints.
     */
    private static byte[] stripJpeg(byte[] content) {
        if (content.length < 4 || (content[0] & 0xFF) != 0xFF || (content[1] & 0xFF) != 0xD8) {
            throw new SanitizationException("jpeg start of image marker is missing");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(content.length);
        out.write(0xFF);
        out.write(0xD8);
        boolean orientationWritten = false;
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
            if (marker == 0xE1 && !orientationWritten) {
                // The EXIF block itself is dropped; only its orientation is re-emitted, in the same slot.
                writeOrientationApp1(out, readExifOrientation(content, index + 2, length - 2));
                orientationWritten = true;
            } else if (!isDroppedJpegSegment(marker)) {
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

    /** Writes a 36-byte APP1 whose IFD0 holds a single Orientation entry. No-op for the default value. */
    private static void writeOrientationApp1(ByteArrayOutputStream out, int orientation) {
        if (orientation <= 1) {
            return;
        }
        out.write(0xFF);
        out.write(0xE1);
        out.write(0x00);
        out.write(EXIF_IDENTIFIER.length + 26 + 2);
        out.writeBytes(EXIF_IDENTIFIER);
        out.writeBytes(new byte[]{
                'I', 'I', 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00,
                0x01, 0x00,
                0x12, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00, (byte) orientation, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00
        });
    }

    /**
     * Returns 1..8, or 0 when the segment carries no readable orientation. A malformed EXIF block is not
     * an error here - it is being discarded anyway, and the image itself is still perfectly valid.
     */
    private static int readExifOrientation(byte[] content, int start, int length) {
        try {
            return parseExifOrientation(content, start, length);
        } catch (RuntimeException exception) {
            return 0;
        }
    }

    private static int parseExifOrientation(byte[] content, int start, int length) {
        int tiff = start + EXIF_IDENTIFIER.length;
        if (length < EXIF_IDENTIFIER.length + 8
                || !Arrays.equals(
                        content, start, tiff,
                        EXIF_IDENTIFIER, 0, EXIF_IDENTIFIER.length)) {
            return 0;
        }
        final ByteOrder order;
        int byteOrderMark = ((content[tiff] & 0xFF) << 8) | (content[tiff + 1] & 0xFF);
        if (byteOrderMark == 0x4949) {
            order = ByteOrder.LITTLE_ENDIAN;
        } else if (byteOrderMark == 0x4D4D) {
            order = ByteOrder.BIG_ENDIAN;
        } else {
            return 0;
        }
        ByteBuffer buffer = ByteBuffer.wrap(content).order(order);
        if ((buffer.getShort(tiff + 2) & 0xFFFF) != 42) {
            return 0;
        }
        int directory = tiff + buffer.getInt(tiff + 4);
        int entries = buffer.getShort(directory) & 0xFFFF;
        int end = start + length;
        for (int entry = 0; entry < entries; entry++) {
            int offset = directory + 2 + entry * 12;
            if (offset < start || offset + 12 > end) {
                return 0;
            }
            if ((buffer.getShort(offset) & 0xFFFF) == ORIENTATION_TAG
                    && (buffer.getShort(offset + 2) & 0xFFFF) == SHORT_TYPE
                    && buffer.getInt(offset + 4) == 1) {
                int value = buffer.getShort(offset + 8) & 0xFFFF;
                return value >= 1 && value <= 8 ? value : 0;
            }
        }
        return 0;
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
    public static final class SanitizationException extends RuntimeException {

        SanitizationException(String message) {
            super(message);
        }
    }
}
