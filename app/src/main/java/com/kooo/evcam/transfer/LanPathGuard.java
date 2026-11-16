package com.kooo.evcam.transfer;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Path containment, symbolic-link rejection, media extension filtering, and RFC 5987 header helpers
 * for LAN browser sharing.
 */
public final class LanPathGuard {

    public static final Set<String> ALLOWED_MEDIA_EXTENSIONS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("mp4", "mov", "avi", "jpg", "jpeg", "png")));

    private LanPathGuard() {}

    @Nullable
    public static File resolveAuthorizedMediaFile(@Nullable File rootDir, @Nullable String relativePath) {
        if (rootDir == null || relativePath == null || relativePath.isEmpty()) {
            return null;
        }
        if (!isSafeRelativePath(relativePath) || !isAllowedMediaName(relativePath)) {
            return null;
        }
        try {
            File canonicalRoot = rootDir.getCanonicalFile();
            if (!canonicalRoot.isDirectory()) {
                return null;
            }
            File candidate = new File(canonicalRoot, relativePath);
            if (Files.isSymbolicLink(candidate.toPath())) {
                return null;
            }
            File canonicalTarget = candidate.getCanonicalFile();
            if (!isStrictlyInsideDirectory(canonicalRoot, canonicalTarget)) {
                return null;
            }
            if (!canonicalTarget.isFile() || !canonicalTarget.canRead() || canonicalTarget.length() <= 0L) {
                return null;
            }
            return canonicalTarget;
        } catch (IOException | SecurityException e) {
            return null;
        }
    }

    public static boolean isSafeAuthorizedRegularFile(@Nullable File rootDir, @Nullable File targetFile) {
        if (rootDir == null || targetFile == null) {
            return false;
        }
        try {
            File canonicalRoot = rootDir.getCanonicalFile();
            if (Files.isSymbolicLink(targetFile.toPath())) {
                return false;
            }
            File canonicalTarget = targetFile.getCanonicalFile();
            return isStrictlyInsideDirectory(canonicalRoot, canonicalTarget)
                    && canonicalTarget.isFile()
                    && canonicalTarget.canRead()
                    && isAllowedMediaName(canonicalTarget.getName());
        } catch (IOException | SecurityException e) {
            return false;
        }
    }

    static boolean isSafeRelativePath(String relativePath) {
        if (relativePath.startsWith("/") || relativePath.startsWith("\\") || relativePath.indexOf('\0') >= 0) {
            return false;
        }
        for (int i = 0; i < relativePath.length(); i++) {
            char c = relativePath.charAt(i);
            if (c < 0x20 || c == '\\') {
                return false;
            }
        }
        String[] segments = relativePath.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                return false;
            }
        }
        return true;
    }

    public static boolean isAllowedMediaName(@Nullable String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pending") || lower.endsWith(".part") || lower.endsWith(".tmp")
                || lower.endsWith(".lock") || lower.endsWith(".marks")) {
            return false;
        }
        int dot = lower.lastIndexOf('.');
        if (dot <= 0 || dot >= lower.length() - 1) {
            return false;
        }
        return ALLOWED_MEDIA_EXTENSIONS.contains(lower.substring(dot + 1));
    }

    public static boolean isVideoFileName(@Nullable String name) {
        if (!isAllowedMediaName(name)) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".avi");
    }

    static boolean isStrictlyInsideDirectory(File canonicalRoot, File canonicalTarget) {
        String rootPath = canonicalRoot.getPath();
        String targetPath = canonicalTarget.getPath();
        if (rootPath.equals(targetPath)) {
            return false;
        }
        String prefix = rootPath.endsWith(File.separator) ? rootPath : rootPath + File.separator;
        return targetPath.startsWith(prefix);
    }

    public static String buildContentDispositionAttachment(String fileName) {
        String safeName = (fileName == null || fileName.isEmpty()) ? "recording.bin" : fileName;
        StringBuilder asciiFallback = new StringBuilder(safeName.length());
        for (int i = 0; i < safeName.length(); i++) {
            char ch = safeName.charAt(i);
            if (ch >= 0x20 && ch <= 0x7E && ch != '"' && ch != '\\' && ch != ';') {
                asciiFallback.append(ch);
            } else {
                asciiFallback.append('_');
            }
        }
        String encoded = encodeRfc5987(safeName);
        return "attachment; filename=\"" + asciiFallback + "\"; filename*=UTF-8''" + encoded;
    }

    private static String encodeRfc5987(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20");
        } catch (Exception e) {
            return "file.bin";
        }
    }
}
