package org.aalku.joatse.cloud.util;

public class MimeTypeUtil {
    /**
     * Guesses the content type based on file extension.
     *
     * @param fileName The filename
     * @return The MIME type
     */
    public static String guessContentType(String fileName) {
        if (fileName == null) {
            return "application/octet-stream";
        }
        String lower = fileName.toLowerCase();
        // Text formats
        if (lower.endsWith(".txt")) return "text/plain";
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html";
        if (lower.endsWith(".css")) return "text/css";
        if (lower.endsWith(".csv")) return "text/csv";
        if (lower.endsWith(".log")) return "text/plain";
        // Data formats
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".xml")) return "application/xml";
        // Documents
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".doc")) return "application/msword";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".xls")) return "application/vnd.ms-excel";
        if (lower.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (lower.endsWith(".ppt")) return "application/vnd.ms-powerpoint";
        if (lower.endsWith(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        // Images
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".ico")) return "image/x-icon";
        if (lower.endsWith(".bmp")) return "image/bmp";
        // Video
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".webm")) return "video/webm";
        if (lower.endsWith(".avi")) return "video/x-msvideo";
        if (lower.endsWith(".mov")) return "video/quicktime";
        // Audio
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".ogg")) return "audio/ogg";
        // Code/Script
        if (lower.endsWith(".js")) return "application/javascript";
        if (lower.endsWith(".java")) return "text/x-java-source";
        if (lower.endsWith(".py")) return "text/x-python";
        if (lower.endsWith(".c") || lower.endsWith(".h")) return "text/x-c";
        if (lower.endsWith(".cpp") || lower.endsWith(".hpp")) return "text/x-c++";
        if (lower.endsWith(".sh")) return "application/x-sh";
        // Archives
        if (lower.endsWith(".zip")) return "application/zip";
        if (lower.endsWith(".tar")) return "application/x-tar";
        if (lower.endsWith(".gz")) return "application/gzip";
        if (lower.endsWith(".7z")) return "application/x-7z-compressed";
        if (lower.endsWith(".rar")) return "application/vnd.rar";
        return "application/octet-stream";
    }
}
