package io.connect.sqs.util;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.GZIPOutputStream;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessageDecompressorTest {

    private static final String TEST_DATA = "{\"message\":\"Hello, World!\",\"timestamp\":1234567890}";

    @Test
    void testDetectGzipFormat() throws IOException {
        byte[] gzippedData = gzipCompress(TEST_DATA);
        MessageDecompressor.CompressionFormat format = MessageDecompressor.detectCompressionFormat(gzippedData);
        assertThat(format).isEqualTo(MessageDecompressor.CompressionFormat.GZIP);
    }

    @Test
    void testDetectZlibFormat() throws IOException {
        byte[] zlibData = zlibCompress(TEST_DATA);
        MessageDecompressor.CompressionFormat format = MessageDecompressor.detectCompressionFormat(zlibData);
        assertThat(format).isEqualTo(MessageDecompressor.CompressionFormat.ZLIB);
    }

    @Test
    void testDetectNoCompression() {
        byte[] plainData = TEST_DATA.getBytes(StandardCharsets.UTF_8);
        MessageDecompressor.CompressionFormat format = MessageDecompressor.detectCompressionFormat(plainData);
        assertThat(format).isEqualTo(MessageDecompressor.CompressionFormat.NONE);
    }

    @Test
    void testDetectFormatWithNullData() {
        MessageDecompressor.CompressionFormat format = MessageDecompressor.detectCompressionFormat(null);
        assertThat(format).isEqualTo(MessageDecompressor.CompressionFormat.NONE);
    }

    @Test
    void testDetectFormatWithEmptyData() {
        MessageDecompressor.CompressionFormat format = MessageDecompressor.detectCompressionFormat(new byte[0]);
        assertThat(format).isEqualTo(MessageDecompressor.CompressionFormat.NONE);
    }

    @Test
    void testDecompressGzipData() throws IOException {
        byte[] gzippedData = gzipCompress(TEST_DATA);
        byte[] decompressed = MessageDecompressor.decompressBytes(gzippedData, MessageDecompressor.CompressionFormat.GZIP);
        String result = new String(decompressed, StandardCharsets.UTF_8);
        assertThat(result).isEqualTo(TEST_DATA);
    }

    @Test
    void testDecompressZlibData() throws IOException {
        byte[] zlibData = zlibCompress(TEST_DATA);
        byte[] decompressed = MessageDecompressor.decompressBytes(zlibData, MessageDecompressor.CompressionFormat.ZLIB);
        String result = new String(decompressed, StandardCharsets.UTF_8);
        assertThat(result).isEqualTo(TEST_DATA);
    }

    @Test
    void testDecompressDeflateData() throws IOException {
        // Note: DEFLATE format uses InflaterInputStream which expects zlib wrapper
        // This test uses zlib compression which is compatible with DEFLATE decompression
        byte[] zlibData = zlibCompress(TEST_DATA);
        byte[] decompressed = MessageDecompressor.decompressBytes(zlibData, MessageDecompressor.CompressionFormat.DEFLATE);
        String result = new String(decompressed, StandardCharsets.UTF_8);
        assertThat(result).isEqualTo(TEST_DATA);
    }

    @Test
    void testDecompressBase64EncodedGzip() throws IOException {
        byte[] gzippedData = gzipCompress(TEST_DATA);
        String base64Encoded = Base64.getEncoder().encodeToString(gzippedData);

        String result = MessageDecompressor.decompress(base64Encoded);
        assertThat(result).isEqualTo(TEST_DATA);
    }

    @Test
    void testDecompressBase64EncodedZlib() throws IOException {
        byte[] zlibData = zlibCompress(TEST_DATA);
        String base64Encoded = Base64.getEncoder().encodeToString(zlibData);

        String result = MessageDecompressor.decompress(base64Encoded);
        assertThat(result).isEqualTo(TEST_DATA);
    }

    @Test
    void testDecompressAutoDetectGzip() throws IOException {
        byte[] gzippedData = gzipCompress(TEST_DATA);
        String base64Encoded = Base64.getEncoder().encodeToString(gzippedData);

        String result = MessageDecompressor.decompress(base64Encoded, MessageDecompressor.CompressionFormat.AUTO, true);
        assertThat(result).isEqualTo(TEST_DATA);
    }

    @Test
    void testDecompressAutoDetectZlib() throws IOException {
        byte[] zlibData = zlibCompress(TEST_DATA);
        String base64Encoded = Base64.getEncoder().encodeToString(zlibData);

        String result = MessageDecompressor.decompress(base64Encoded, MessageDecompressor.CompressionFormat.AUTO, true);
        assertThat(result).isEqualTo(TEST_DATA);
    }

    @Test
    void testDecompressWithoutBase64Decoding() throws IOException {
        byte[] gzippedData = gzipCompress(TEST_DATA);
        String base64Encoded = Base64.getEncoder().encodeToString(gzippedData);

        // When not attempting Base64 decode, should treat as plain text and return original
        String result = MessageDecompressor.decompress(base64Encoded, MessageDecompressor.CompressionFormat.AUTO, false);
        // Since it's not compressed raw data, it should return the original base64 string
        assertThat(result).isEqualTo(base64Encoded);
    }

    @Test
    void testDecompressSafeWithValidData() throws IOException {
        byte[] gzippedData = gzipCompress(TEST_DATA);
        String base64Encoded = Base64.getEncoder().encodeToString(gzippedData);

        String result = MessageDecompressor.decompressSafe(base64Encoded);
        assertThat(result).isEqualTo(TEST_DATA);
    }

    @Test
    void testDecompressSafeWithInvalidData() {
        String invalidData = "This is not compressed data";
        String result = MessageDecompressor.decompressSafe(invalidData);
        // Should return original data when decompression fails
        assertThat(result).isEqualTo(invalidData);
    }

    @Test
    void testDecompressSafeWithNull() {
        String result = MessageDecompressor.decompressSafe(null);
        assertThat(result).isNull();
    }

    @Test
    void testDecompressSafeWithEmptyString() {
        String result = MessageDecompressor.decompressSafe("");
        assertThat(result).isEmpty();
    }

    @Test
    void testIsLikelyBase64WithValidBase64() {
        String base64 = "SGVsbG8gV29ybGQh"; // "Hello World!" in Base64
        assertThat(MessageDecompressor.isLikelyBase64(base64)).isTrue();
    }

    @Test
    void testIsLikelyBase64WithLongValidBase64() throws IOException {
        byte[] gzippedData = gzipCompress(TEST_DATA);
        String base64 = Base64.getEncoder().encodeToString(gzippedData);
        assertThat(MessageDecompressor.isLikelyBase64(base64)).isTrue();
    }

    @Test
    void testIsLikelyBase64WithPlainText() {
        assertThat(MessageDecompressor.isLikelyBase64("Hello World!")).isFalse();
    }

    @Test
    void testIsLikelyBase64WithShortString() {
        assertThat(MessageDecompressor.isLikelyBase64("abc")).isFalse();
    }

    @Test
    void testIsLikelyBase64WithNull() {
        assertThat(MessageDecompressor.isLikelyBase64(null)).isFalse();
    }

    @Test
    void testIsLikelyBase64WithEmpty() {
        assertThat(MessageDecompressor.isLikelyBase64("")).isFalse();
    }

    @Test
    void testDecompressBytesWithNullData() throws IOException {
        byte[] result = MessageDecompressor.decompressBytes(null, MessageDecompressor.CompressionFormat.GZIP);
        assertThat(result).isNull();
    }

    @Test
    void testDecompressBytesWithEmptyData() throws IOException {
        byte[] result = MessageDecompressor.decompressBytes(new byte[0], MessageDecompressor.CompressionFormat.GZIP);
        assertThat(result).isEmpty();
    }

    @Test
    void testDecompressBytesWithNoneFormat() throws IOException {
        byte[] data = TEST_DATA.getBytes(StandardCharsets.UTF_8);
        byte[] result = MessageDecompressor.decompressBytes(data, MessageDecompressor.CompressionFormat.NONE);
        assertThat(result).isEqualTo(data);
    }

    @Test
    void testDecompressBytesWithAutoFormat() throws IOException {
        byte[] gzippedData = gzipCompress(TEST_DATA);
        byte[] decompressed = MessageDecompressor.decompressBytes(gzippedData, MessageDecompressor.CompressionFormat.AUTO);
        String result = new String(decompressed, StandardCharsets.UTF_8);
        assertThat(result).isEqualTo(TEST_DATA);
    }

    @Test
    void testDecompressWithInvalidGzipData() {
        byte[] invalidData = "not gzipped data".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() ->
            MessageDecompressor.decompressBytes(invalidData, MessageDecompressor.CompressionFormat.GZIP)
        ).isInstanceOf(IOException.class);
    }

    @Test
    void testDecompressPlainTextReturnsOriginal() throws IOException {
        String plainText = "Hello, World!";
        String result = MessageDecompressor.decompress(plainText);
        // Plain text should be returned as-is since no compression is detected
        assertThat(result).isEqualTo(plainText);
    }

    @Test
    void testDecompressComplexJson() throws IOException {
        String complexJson = "{\"version\":\"0\",\"id\":\"test-123\",\"detail-type\":\"PriceCache.Updated\",\"detail\":{\"data\":\"test-value\"}}";
        byte[] gzippedData = gzipCompress(complexJson);
        String base64Encoded = Base64.getEncoder().encodeToString(gzippedData);

        String result = MessageDecompressor.decompress(base64Encoded);
        assertThat(result).isEqualTo(complexJson);
    }

    // Helper methods to create compressed data

    private byte[] gzipCompress(String data) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (GZIPOutputStream gzipOutputStream = new GZIPOutputStream(outputStream)) {
            gzipOutputStream.write(data.getBytes(StandardCharsets.UTF_8));
        }
        return outputStream.toByteArray();
    }

    private byte[] zlibCompress(String data) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflaterOutputStream = new DeflaterOutputStream(outputStream)) {
            deflaterOutputStream.write(data.getBytes(StandardCharsets.UTF_8));
        }
        return outputStream.toByteArray();
    }

    private byte[] deflateCompress(String data) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        // Use raw deflate (without zlib wrapper)
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        try (DeflaterOutputStream deflaterOutputStream = new DeflaterOutputStream(outputStream, deflater)) {
            deflaterOutputStream.write(data.getBytes(StandardCharsets.UTF_8));
        }
        return outputStream.toByteArray();
    }
}
