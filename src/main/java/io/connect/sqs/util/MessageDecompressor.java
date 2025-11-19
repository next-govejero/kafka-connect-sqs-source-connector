package io.connect.sqs.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Utility class for decompressing message data.
 * Supports auto-detection and decompression of gzip, deflate, and zlib compressed data.
 * Can handle both raw binary compressed data and Base64-encoded compressed data.
 */
public class MessageDecompressor {

    private static final Logger log = LoggerFactory.getLogger(MessageDecompressor.class);

    // Magic bytes for compression format detection
    private static final byte GZIP_MAGIC_BYTE_1 = (byte) 0x1f;
    private static final byte GZIP_MAGIC_BYTE_2 = (byte) 0x8b;
    private static final byte ZLIB_MAGIC_BYTE_1 = (byte) 0x78;

    /**
     * Compression format enum
     */
    public enum CompressionFormat {
        GZIP,
        DEFLATE,
        ZLIB,
        NONE,
        AUTO
    }

    /**
     * Decompresses data with automatic format detection.
     * First attempts to decode from Base64 if data appears to be Base64-encoded,
     * then auto-detects compression format and decompresses.
     *
     * @param data The data to decompress (may be Base64-encoded)
     * @return Decompressed string
     * @throws IOException if decompression fails
     */
    public static String decompress(String data) throws IOException {
        return decompress(data, CompressionFormat.AUTO, true);
    }

    /**
     * Decompresses data with specified format.
     *
     * @param data The data to decompress
     * @param format The compression format (use AUTO for auto-detection)
     * @param tryBase64Decode Whether to attempt Base64 decoding first
     * @return Decompressed string
     * @throws IOException if decompression fails
     */
    public static String decompress(String data, CompressionFormat format, boolean tryBase64Decode)
            throws IOException {
        if (data == null || data.isEmpty()) {
            return data;
        }

        byte[] compressedBytes;

        // Try Base64 decoding if requested
        if (tryBase64Decode && isLikelyBase64(data)) {
            try {
                compressedBytes = Base64.getDecoder().decode(data);
                log.debug("Successfully decoded Base64 data, {} bytes", compressedBytes.length);
            } catch (IllegalArgumentException e) {
                // Not Base64, treat as raw string
                log.debug("Data is not valid Base64, treating as raw bytes");
                compressedBytes = data.getBytes(StandardCharsets.UTF_8);
            }
        } else {
            compressedBytes = data.getBytes(StandardCharsets.UTF_8);
        }

        // Auto-detect compression format if needed
        CompressionFormat detectedFormat = format;
        if (format == CompressionFormat.AUTO) {
            detectedFormat = detectCompressionFormat(compressedBytes);
            log.debug("Auto-detected compression format: {}", detectedFormat);
        }

        // If no compression detected, return original string
        if (detectedFormat == CompressionFormat.NONE) {
            return data;
        }

        // Decompress based on format
        byte[] decompressedBytes = decompressBytes(compressedBytes, detectedFormat);
        String result = new String(decompressedBytes, StandardCharsets.UTF_8);
        log.debug("Decompressed {} bytes to {} bytes", compressedBytes.length, decompressedBytes.length);
        return result;
    }

    /**
     * Decompresses raw byte array data.
     *
     * @param compressedData The compressed data
     * @param format The compression format
     * @return Decompressed byte array
     * @throws IOException if decompression fails
     */
    public static byte[] decompressBytes(byte[] compressedData, CompressionFormat format) throws IOException {
        if (compressedData == null || compressedData.length == 0) {
            return compressedData;
        }

        ByteArrayInputStream inputStream = new ByteArrayInputStream(compressedData);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        try {
            switch (format) {
                case GZIP:
                    try (GZIPInputStream gzipInputStream = new GZIPInputStream(inputStream)) {
                        copyStream(gzipInputStream, outputStream);
                    }
                    break;

                case DEFLATE:
                case ZLIB:
                    try (InflaterInputStream inflaterInputStream = new InflaterInputStream(inputStream)) {
                        copyStream(inflaterInputStream, outputStream);
                    }
                    break;

                case AUTO:
                    // Should not reach here - auto should be resolved before calling this method
                    CompressionFormat detected = detectCompressionFormat(compressedData);
                    return decompressBytes(compressedData, detected);

                case NONE:
                    return compressedData;

                default:
                    throw new IOException("Unsupported compression format: " + format);
            }

            return outputStream.toByteArray();

        } catch (IOException e) {
            log.error("Failed to decompress data with format {}: {}", format, e.getMessage());
            throw new IOException("Decompression failed: " + e.getMessage(), e);
        }
    }

    /**
     * Detects compression format based on magic bytes.
     *
     * @param data The data to analyze
     * @return Detected compression format
     */
    public static CompressionFormat detectCompressionFormat(byte[] data) {
        if (data == null || data.length < 2) {
            return CompressionFormat.NONE;
        }

        // Check for GZIP magic bytes (0x1f 0x8b)
        if (data[0] == GZIP_MAGIC_BYTE_1 && data[1] == GZIP_MAGIC_BYTE_2) {
            return CompressionFormat.GZIP;
        }

        // Check for zlib/deflate magic byte (0x78)
        // Common zlib headers: 0x78 0x01 (low compression), 0x78 0x9c (default), 0x78 0xda (best)
        if (data[0] == ZLIB_MAGIC_BYTE_1 && data.length >= 2) {
            byte secondByte = data[1];
            if (secondByte == 0x01 || secondByte == 0x5e || secondByte == (byte) 0x9c ||
                secondByte == (byte) 0xda) {
                return CompressionFormat.ZLIB;
            }
        }

        return CompressionFormat.NONE;
    }

    /**
     * Checks if a string is likely to be Base64-encoded.
     * This is a heuristic check, not definitive.
     *
     * @param data The string to check
     * @return true if the string appears to be Base64
     */
    public static boolean isLikelyBase64(String data) {
        if (data == null || data.isEmpty()) {
            return false;
        }

        // Base64 strings should only contain A-Z, a-z, 0-9, +, /, and = for padding
        // Also check for URL-safe Base64 which uses - and _ instead of + and /
        String trimmed = data.trim();

        // Very short strings are unlikely to be Base64-encoded compressed data
        if (trimmed.length() < 16) {
            return false;
        }

        // Check if it matches Base64 character set
        return trimmed.matches("^[A-Za-z0-9+/\\-_]+={0,2}$");
    }

    /**
     * Attempts to decompress data, returning original if decompression fails.
     * This is a safe wrapper that won't throw exceptions.
     *
     * @param data The data to decompress
     * @return Decompressed data, or original data if decompression fails
     */
    public static String decompressSafe(String data) {
        if (data == null || data.isEmpty()) {
            return data;
        }

        try {
            return decompress(data);
        } catch (IOException e) {
            log.debug("Could not decompress data, returning original: {}", e.getMessage());
            return data;
        }
    }

    /**
     * Attempts to decompress data with specific format, returning original if decompression fails.
     *
     * @param data The data to decompress
     * @param format The compression format
     * @param tryBase64Decode Whether to attempt Base64 decoding first
     * @return Decompressed data, or original data if decompression fails
     */
    public static String decompressSafe(String data, CompressionFormat format, boolean tryBase64Decode) {
        if (data == null || data.isEmpty()) {
            return data;
        }

        try {
            return decompress(data, format, tryBase64Decode);
        } catch (IOException e) {
            log.debug("Could not decompress data with format {}, returning original: {}",
                      format, e.getMessage());
            return data;
        }
    }

    /**
     * Helper method to copy data from input stream to output stream.
     *
     * @param input Input stream
     * @param output Output stream
     * @throws IOException if copying fails
     */
    private static void copyStream(java.io.InputStream input, ByteArrayOutputStream output)
            throws IOException {
        byte[] buffer = new byte[8192];
        int bytesRead;
        while ((bytesRead = input.read(buffer)) != -1) {
            output.write(buffer, 0, bytesRead);
        }
    }
}
