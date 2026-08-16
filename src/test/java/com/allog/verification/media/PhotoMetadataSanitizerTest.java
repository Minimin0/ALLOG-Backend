package com.allog.verification.media;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhotoMetadataSanitizerTest {

    private static final byte[] GPS_TAGS = "GPSLatitude=37.5665 GPSLongitude=126.9780".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CAMERA_TAGS = "Make=Canon Model=EOS-R DateTimeOriginal=2026:08:15 10:00:00"
            .getBytes(StandardCharsets.US_ASCII);

    /** APP1 EXIF payloads start with "Exif" and two NUL padding bytes. */
    private static final byte[] EXIF_HEADER = "Exif\0\0".getBytes(StandardCharsets.US_ASCII);

    /** TIFF header (little-endian) + IFD0 with a single Orientation=6 entry. */
    private static final byte[] EXIF_ORIENTATION_6 = {
            'I', 'I', 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00,
            0x01, 0x00,
            0x12, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00, 0x06, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00
    };

    private static final byte[] CLEAN_JPEG = encode("jpeg");
    private static final byte[] CLEAN_PNG = encode("png");

    @Test
    void stripsGpsAndCameraExifFromJpegApp1() {
        byte[] tagged = jpegWithApp1(concat(EXIF_HEADER, GPS_TAGS, CAMERA_TAGS));

        byte[] sanitized = PhotoMetadataSanitizer.strip("image/jpeg", tagged);

        assertAll(
                () -> assertTrue(contains(tagged, GPS_TAGS), "fixture must actually carry GPS tags"),
                () -> assertTrue(contains(tagged, CAMERA_TAGS), "fixture must actually carry camera tags"),
                () -> assertFalse(contains(sanitized, GPS_TAGS)),
                () -> assertFalse(contains(sanitized, CAMERA_TAGS)),
                () -> assertArrayEquals(CLEAN_JPEG, sanitized)
        );
    }

    @Test
    void stripsGpsAndTextMetadataChunksFromPng() {
        byte[] tagged = pngWithChunk(pngWithChunk(CLEAN_PNG, "eXIf", GPS_TAGS), "tEXt", CAMERA_TAGS);

        byte[] sanitized = PhotoMetadataSanitizer.strip("image/png", tagged);

        assertAll(
                () -> assertTrue(contains(tagged, GPS_TAGS), "fixture must actually carry GPS tags"),
                () -> assertFalse(contains(sanitized, GPS_TAGS)),
                () -> assertFalse(contains(sanitized, CAMERA_TAGS)),
                () -> assertArrayEquals(CLEAN_PNG, sanitized)
        );
    }

    /**
     * Portrait phone photos carry Orientation != 1. That hint decides whether the photo is read upright,
     * so it is rewritten into a fresh APP1 while every other EXIF field is dropped.
     */
    @Test
    void keepsOrientationWhileDroppingEveryOtherExifField() throws IOException {
        byte[] tagged = jpegWithApp1(concat(EXIF_HEADER, EXIF_ORIENTATION_6, GPS_TAGS, CAMERA_TAGS));

        byte[] sanitized = PhotoMetadataSanitizer.strip("image/jpeg", tagged);

        BufferedImage before = decode(tagged);
        BufferedImage after = decode(sanitized);
        assertAll(
                () -> assertTrue(contains(tagged, GPS_TAGS), "fixture must actually carry GPS tags"),
                () -> assertTrue(contains(sanitized, EXIF_ORIENTATION_6), "orientation must survive"),
                () -> assertFalse(contains(sanitized, GPS_TAGS)),
                () -> assertFalse(contains(sanitized, CAMERA_TAGS)),
                // pixel bytes are copied verbatim, so the decoded image is untouched
                () -> assertEquals(before.getWidth(), after.getWidth()),
                () -> assertEquals(before.getHeight(), after.getHeight()),
                () -> assertArrayEquals(raster(before), raster(after))
        );
    }

    @Test
    void rewritesOrientationIntoAnApp1OfItsOwn() {
        byte[] sanitized = PhotoMetadataSanitizer.strip("image/jpeg", jpegWithApp1(exifWithOrientation6()));

        // 36 bytes: APP1 marker + length + "Exif\0\0" + a TIFF block holding one Orientation entry
        assertAll(
                () -> assertEquals(CLEAN_JPEG.length + 36, sanitized.length),
                () -> assertTrue(contains(sanitized, EXIF_ORIENTATION_6))
        );
    }

    @Test
    void addsNothingWhenTheOrientationIsAbsentOrDefault() {
        byte[] defaultOrientation = EXIF_ORIENTATION_6.clone();
        defaultOrientation[18] = 1;

        assertAll(
                () -> assertArrayEquals(CLEAN_JPEG, PhotoMetadataSanitizer.strip(
                        "image/jpeg",
                        jpegWithApp1(concat(EXIF_HEADER, defaultOrientation))
                )),
                () -> assertArrayEquals(CLEAN_JPEG, PhotoMetadataSanitizer.strip(
                        "image/jpeg",
                        jpegWithApp1(concat(EXIF_HEADER, GPS_TAGS))
                ))
        );
    }

    @Test
    void keepsTheImageDecodableAtTheSameResolution() throws IOException {
        byte[] taggedJpeg = jpegWithApp1(concat(EXIF_HEADER, GPS_TAGS));
        byte[] taggedPng = pngWithChunk(CLEAN_PNG, "eXIf", GPS_TAGS);

        BufferedImage jpeg = decode(PhotoMetadataSanitizer.strip("image/jpeg", taggedJpeg));
        BufferedImage png = decode(PhotoMetadataSanitizer.strip("image/png", taggedPng));

        assertAll(
                () -> assertNotNull(jpeg),
                () -> assertEquals(8, jpeg.getWidth()),
                () -> assertEquals(4, jpeg.getHeight()),
                () -> assertNotNull(png),
                () -> assertEquals(8, png.getWidth()),
                () -> assertEquals(4, png.getHeight())
        );
    }

    @Test
    void leavesAlreadyCleanImagesByteIdentical() {
        assertAll(
                () -> assertArrayEquals(CLEAN_JPEG, PhotoMetadataSanitizer.strip("image/jpeg", CLEAN_JPEG)),
                () -> assertArrayEquals(CLEAN_PNG, PhotoMetadataSanitizer.strip("image/png", CLEAN_PNG))
        );
    }

    @Test
    void failsClosedWithoutReturningTheOriginalBytes() {
        byte[] notAnImage = {1, 2, 3, 4};
        byte[] truncatedJpeg = Arrays.copyOf(CLEAN_JPEG, 6);
        byte[] lyingSegmentLength = CLEAN_JPEG.clone();
        lyingSegmentLength[4] = (byte) 0x7F;
        byte[] truncatedPng = Arrays.copyOf(CLEAN_PNG, 12);

        assertAll(
                () -> assertThrows(PhotoMetadataSanitizer.SanitizationException.class,
                        () -> PhotoMetadataSanitizer.strip("image/jpeg", notAnImage)),
                () -> assertThrows(PhotoMetadataSanitizer.SanitizationException.class,
                        () -> PhotoMetadataSanitizer.strip("image/png", notAnImage)),
                () -> assertThrows(PhotoMetadataSanitizer.SanitizationException.class,
                        () -> PhotoMetadataSanitizer.strip("image/jpeg", truncatedJpeg)),
                () -> assertThrows(PhotoMetadataSanitizer.SanitizationException.class,
                        () -> PhotoMetadataSanitizer.strip("image/jpeg", lyingSegmentLength)),
                () -> assertThrows(PhotoMetadataSanitizer.SanitizationException.class,
                        () -> PhotoMetadataSanitizer.strip("image/png", truncatedPng)),
                // an unsanitizable format must never be forwarded as-is
                () -> assertThrows(PhotoMetadataSanitizer.SanitizationException.class,
                        () -> PhotoMetadataSanitizer.strip("image/heic", CLEAN_JPEG)),
                () -> assertThrows(PhotoMetadataSanitizer.SanitizationException.class,
                        () -> PhotoMetadataSanitizer.strip("image/webp", CLEAN_JPEG)),
                () -> assertThrows(PhotoMetadataSanitizer.SanitizationException.class,
                        () -> PhotoMetadataSanitizer.strip("video/mp4", CLEAN_JPEG))
        );
    }

    /** iOS uploads land as HEIC unless the client converts. No decoder here, so it must stay blocked. */
    @Test
    void treatsHeicAsUnsupported() {
        assertAll(
                () -> assertFalse(PhotoMetadataSanitizer.supports("image/heic")),
                () -> assertFalse(PhotoMetadataSanitizer.supports("image/heif")),
                () -> assertTrue(PhotoMetadataSanitizer.supports("image/jpeg")),
                () -> assertTrue(PhotoMetadataSanitizer.supports("image/png"))
        );
    }

    /**
     * Minimal but valid EXIF payload: little-endian TIFF header plus an IFD0 holding only
     * Orientation (tag 0x0112, SHORT) = 6, the "rotate 90 CW" value phones write for portrait shots.
     */
    private static byte[] exifWithOrientation6() {
        return concat(EXIF_HEADER, EXIF_ORIENTATION_6);
    }

    private static int[] raster(BufferedImage image) {
        return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
    }

    private static byte[] encode(String format) {
        BufferedImage image = new BufferedImage(8, 4, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(image, format, out)) {
                throw new IllegalStateException("no " + format + " writer available");
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return out.toByteArray();
    }

    private static BufferedImage decode(byte[] content) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(content));
    }

    private static byte[] jpegWithApp1(byte[] payload) {
        int length = payload.length + 2;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(CLEAN_JPEG, 0, 2);
        out.write(0xFF);
        out.write(0xE1);
        out.write(length >>> 8);
        out.write(length);
        out.writeBytes(payload);
        out.write(CLEAN_JPEG, 2, CLEAN_JPEG.length - 2);
        return out.toByteArray();
    }

    private static byte[] pngWithChunk(byte[] png, String type, byte[] data) {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(png, 0, png.length - 12);
        writeInt(out, data.length);
        out.writeBytes(typeBytes);
        out.writeBytes(data);
        writeInt(out, (int) crc.getValue());
        out.write(png, png.length - 12, 12);
        return out.toByteArray();
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write(value >>> 24);
        out.write(value >>> 16);
        out.write(value >>> 8);
        out.write(value);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Arrays.stream(parts).forEach(out::writeBytes);
        return out.toByteArray();
    }

    /** ISO-8859-1 maps every byte 1:1, so this is an exact byte-subsequence search. */
    private static boolean contains(byte[] haystack, byte[] needle) {
        return new String(haystack, StandardCharsets.ISO_8859_1)
                .contains(new String(needle, StandardCharsets.ISO_8859_1));
    }
}
