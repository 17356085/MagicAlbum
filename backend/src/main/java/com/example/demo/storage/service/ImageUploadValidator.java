package com.example.demo.storage.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

@Component
public class ImageUploadValidator {

    private static final long MAX_SIZE = 10L * 1024 * 1024;
    private static final long MAX_PIXELS = 24_000_000L;
    private static final int MAX_EDGE = 20_000;
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg",
            "image/png",
            "image/gif",
            "image/webp"
    );
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            ".jpg", ".jpeg", ".png", ".gif", ".webp"
    );

    public ValidatedImage validate(MultipartFile file) {
        if (file == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件不能为空");
        }
        if (file.getSize() > MAX_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "图片大小不能超过10MB");
        }

        String contentType = normalize(file.getContentType());
        if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "仅支持 JPG、PNG、GIF、WEBP 图片上传");
        }

        String ext = extractExtension(file.getOriginalFilename());
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "图片扩展名不受支持");
        }

        byte[] bytes = readBounded(file);
        validateImage(bytes, contentType, ext);

        return new ValidatedImage(bytes, contentType, ext);
    }

    private byte[] readBounded(MultipartFile file) {
        try (InputStream input = new BoundedInputStream(file.getInputStream(), MAX_SIZE);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (read == 0) {
                    int single = input.read();
                    if (single == -1) {
                        break;
                    }
                    output.write(single);
                } else {
                    output.write(buffer, 0, read);
                }
            }
            if (output.size() == 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件不能为空");
            }
            return output.toByteArray();
        } catch (PayloadTooLargeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "图片大小不能超过10MB");
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "读取上传文件失败");
        }
    }

    private void validateImage(byte[] bytes, String contentType, String extension) {
        String declaredContentFormat = formatFromContentType(contentType);
        String declaredExtensionFormat = formatFromExtension(extension);
        if (declaredContentFormat == null || !declaredContentFormat.equals(declaredExtensionFormat)) {
            throw invalidImage();
        }

        try (ByteArrayInputStream input = new ByteArrayInputStream(bytes);
             ImageInputStream imageInput = ImageIO.createImageInputStream(input)) {
            if (imageInput == null) {
                throw invalidImage();
            }

            ImageReader reader = findReader(imageInput);
            if (reader == null) {
                throw invalidImage();
            }
            try {
                imageInput.seek(0);
                reader.setInput(imageInput, false, true);
                String actualFormat = canonicalFormat(reader.getFormatName());
                if (!declaredContentFormat.equals(actualFormat)) {
                    throw invalidImage();
                }

                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                validateDimensions(width, height);

                BufferedImage decoded = reader.read(0);
                if (decoded == null || decoded.getWidth() != width || decoded.getHeight() != height) {
                    throw invalidImage();
                }
            } finally {
                reader.dispose();
            }
        } catch (ResponseStatusException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw invalidImage();
        }
    }

    private ImageReader findReader(ImageInputStream input) throws IOException {
        Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
        while (readers.hasNext()) {
            ImageReader reader = readers.next();
            try {
                if (canonicalFormat(reader.getFormatName()) != null) {
                    return reader;
                }
            } catch (IOException | RuntimeException e) {
                reader.dispose();
                throw e;
            }
            reader.dispose();
        }
        return null;
    }

    private void validateDimensions(int width, int height) {
        if (width <= 0 || height <= 0 || width > MAX_EDGE || height > MAX_EDGE
                || (long) width * height > MAX_PIXELS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "图片尺寸超过处理限制");
        }
    }

    private String formatFromContentType(String contentType) {
        return switch (normalize(contentType)) {
            case "image/jpeg" -> "jpeg";
            case "image/png" -> "png";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            default -> null;
        };
    }

    private String formatFromExtension(String extension) {
        return switch (extension) {
            case ".jpg", ".jpeg" -> "jpeg";
            case ".png" -> "png";
            case ".gif" -> "gif";
            case ".webp" -> "webp";
            default -> null;
        };
    }

    private String canonicalFormat(String format) {
        if (format == null) {
            return null;
        }
        return switch (format.toLowerCase(Locale.ROOT)) {
            case "jpg", "jpeg" -> "jpeg";
            case "png" -> "png";
            case "gif" -> "gif";
            case "webp" -> "webp";
            default -> null;
        };
    }

    private ResponseStatusException invalidImage() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "上传文件不是有效图片");
    }

    private String extractExtension(String filename) {
        if (filename == null) return "";
        int idx = filename.lastIndexOf('.');
        if (idx < 0) return "";
        return filename.substring(idx).toLowerCase(Locale.ROOT);
    }

    private String normalize(String contentType) {
        return contentType == null ? "" : contentType.toLowerCase(Locale.ROOT).trim();
    }

    private static final class PayloadTooLargeException extends IOException {
        private PayloadTooLargeException() {
            super("upload exceeds limit");
        }
    }

    private static final class BoundedInputStream extends FilterInputStream {
        private final long maxBytes;
        private long bytesRead;

        private BoundedInputStream(InputStream input, long maxBytes) {
            super(input);
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            if (bytesRead >= maxBytes) {
                int extra = in.read();
                if (extra != -1) {
                    throw new PayloadTooLargeException();
                }
                return -1;
            }
            int value = in.read();
            if (value != -1) {
                bytesRead++;
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            if (bytesRead >= maxBytes) {
                return read();
            }

            int boundedLength = (int) Math.min(length, maxBytes - bytesRead);
            int read = in.read(bytes, offset, boundedLength);
            if (read > 0) {
                bytesRead += read;
            }
            return read;
        }
    }

    public record ValidatedImage(byte[] bytes, String contentType, String extension) {}
}
