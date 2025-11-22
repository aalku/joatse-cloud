package org.aalku.joatse.cloud.service.sharing.request;

import java.net.URL;
import java.util.Optional;

/**
 * Utility class for generating consistent default descriptions for tunnel request items.
 * This ensures that descriptions match between runtime requests and preconfirmed shares.
 */
public class TunnelDescriptionUtils {
    
    /**
     * Generate default description for HTTP tunnel if not provided
     * @param description Provided description (may be null or empty)
     * @param targetUrl Target URL
     * @return Default description or provided description if not empty
     */
    public static String getDefaultHttpDescription(String description, URL targetUrl) {
        return Optional.ofNullable(description)
                .filter(s -> !s.isEmpty())
                .orElse(targetUrl.toString());
    }
    
    /**
     * Generate default description for TCP tunnel if not provided
     * @param description Provided description (may be null or empty)
     * @param targetHostname Target hostname
     * @param targetPort Target port
     * @return Default description or provided description if not empty
     */
    public static String getDefaultTcpDescription(String description, String targetHostname, int targetPort) {
        return Optional.ofNullable(description)
                .filter(s -> !s.isEmpty())
                .orElse(targetHostname + ":" + targetPort);
    }
    
    /**
     * Generate default description for command tunnel if not provided
     * @param description Provided description (may be null or empty)  
     * @param commandArray Command array
     * @return Default description or provided description if not empty
     */
    public static String getDefaultCommandDescription(String description, String[] commandArray) {
        return Optional.ofNullable(description)
                .filter(s -> !s.isEmpty())
                .orElseGet(() -> {
                    if (commandArray != null && commandArray.length > 0) {
                        String desc = commandArray[0].replaceAll("\\s+", "").replaceAll("[^a-zA-Z0-9]+", "_")
                                .replaceAll("__+", "_").replaceAll("^_+|_+$", "");
                        return desc.substring(0, Math.min(desc.length(), 8));
                    }
                    return "command";
                });
    }
    
    /**
     * Generate default description for file tunnel if not provided
     * @param description Provided description (may be null or empty)
     * @param targetPath Target file path
     * @return Default description or provided description if not empty
     */
    public static String getDefaultFileDescription(String description, String targetPath) {
        return Optional.ofNullable(description)
                .filter(s -> !s.isEmpty())
                .orElseGet(() -> {
                    if (targetPath != null && !targetPath.isEmpty()) {
                        int lastSlash = targetPath.lastIndexOf('/');
                        if (lastSlash >= 0 && lastSlash < targetPath.length() - 1) {
                            return targetPath.substring(lastSlash + 1);
                        }
                        return targetPath;
                    }
                    return "file";
                });
    }
}