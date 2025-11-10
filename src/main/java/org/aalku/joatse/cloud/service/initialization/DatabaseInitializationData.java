package org.aalku.joatse.cloud.service.initialization;

import java.util.List;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * JSON schema for database initialization data.
 * Version must be "1.0" - this is the only supported version.
 * 
 * Supports initialization of:
 * - Regular users with passwords (admin users must be created through other means)
 * - Preconfirmed shares for tunnel authorization
 */
public class DatabaseInitializationData {
    private String version;
    private List<UserData> users;
    private List<ShareData> preconfirmedShares;
    
    public DatabaseInitializationData() {}
    
    public String getVersion() {
        return version;
    }
    
    public void setVersion(String version) {
        this.version = version;
    }
    
    public List<UserData> getUsers() {
        return users;
    }
    
    public void setUsers(List<UserData> users) {
        this.users = users;
    }
    
    public List<ShareData> getPreconfirmedShares() {
        return preconfirmedShares;
    }
    
    public void setPreconfirmedShares(List<ShareData> preconfirmedShares) {
        this.preconfirmedShares = preconfirmedShares;
    }
    
    /**
     * Validates the data structure and version.
     * @throws IllegalArgumentException if validation fails
     */
    public void validate() {
        if (!"1.0".equals(version)) {
            throw new IllegalArgumentException("Unsupported version: " + version + ". Only version '1.0' is supported.");
        }
        
        // Validate users if provided
        if (users != null) {
            for (int i = 0; i < users.size(); i++) {
                UserData user = users.get(i);
                if (user == null) {
                    throw new IllegalArgumentException("User at index " + i + " is null");
                }
                try {
                    user.validate();
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("User at index " + i + " is invalid: " + e.getMessage(), e);
                }
            }
        }
        
        // Validate preconfirmed shares if provided
        if (preconfirmedShares != null) {
            for (int i = 0; i < preconfirmedShares.size(); i++) {
                ShareData share = preconfirmedShares.get(i);
                if (share == null) {
                    throw new IllegalArgumentException("PreconfirmedShare at index " + i + " is null");
                }
                try {
                    share.validate();
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("PreconfirmedShare at index " + i + " is invalid: " + e.getMessage(), e);
                }
            }
        }
    }
    
    /**
     * User data for initialization
     */
    public static class UserData {
        private String login;
        private String password;
        private Boolean emailConfirmed;
        private Boolean applicationUseAllowed;
        
        public UserData() {}
        
        public String getLogin() {
            return login;
        }
        
        public void setLogin(String login) {
            this.login = login;
        }
        
        public String getPassword() {
            return password;
        }
        
        public void setPassword(String password) {
            this.password = password;
        }
        
        public Boolean getEmailConfirmed() {
            return emailConfirmed;
        }
        
        public void setEmailConfirmed(Boolean emailConfirmed) {
            this.emailConfirmed = emailConfirmed;
        }
        
        public Boolean getApplicationUseAllowed() {
            return applicationUseAllowed;
        }
        
        public void setApplicationUseAllowed(Boolean applicationUseAllowed) {
            this.applicationUseAllowed = applicationUseAllowed;
        }
        
        /**
         * Validates the user data structure.
         * @throws IllegalArgumentException if validation fails
         */
        public void validate() {
            if (login == null || login.trim().isEmpty()) {
                throw new IllegalArgumentException("login is required");
            }
            
            if (password == null || password.trim().isEmpty()) {
                throw new IllegalArgumentException("password is required");
            }
            
            if (password.length() < 6) {
                throw new IllegalArgumentException("password must be at least 6 characters long");
            }
            
            // Validate login format (basic email-like validation)
            String loginTrimmed = login.trim();
            if (!loginTrimmed.contains("@") || loginTrimmed.length() < 5) {
                throw new IllegalArgumentException("login must be a valid email address");
            }
        }
        
        @Override
        public String toString() {
            return "UserData{login='" + login + "', emailConfirmed=" + emailConfirmed + ", applicationUseAllowed="
                    + applicationUseAllowed + "}";
        }
    }
    
    /**
     * Preconfirmed share data for initialization
     */
    public static class ShareData {
        private static final ObjectMapper objectMapper = new ObjectMapper();
        
        private String preconfirmationId; // Optional: if provided, uses this ID; if not, generates random UUID
        private String ownerLogin;
        private Object resources; // JSON object that will be converted to string for database
        // requesterAddress is automatically set to "<INIT>" for initialization-created shares
        private List<String> allowedAddresses;
        private Boolean autoAuthorizeByHttpUrl;
        
        public ShareData() {}
        
        public String getPreconfirmationId() {
            return preconfirmationId;
        }
        
        public void setPreconfirmationId(String preconfirmationId) {
            this.preconfirmationId = preconfirmationId;
        }
        
        public String getOwnerLogin() {
            return ownerLogin;
        }
        
        public void setOwnerLogin(String ownerLogin) {
            this.ownerLogin = ownerLogin;
        }
        
        public Object getResources() {
            return resources;
        }
        
        public void setResources(Object resources) {
            this.resources = resources;
        }
        
        /**
         * Converts the resources object to JSON string for database storage.
         * @return JSON string representation of resources
         * @throws RuntimeException if JSON conversion fails
         */
        public String getResourcesAsJsonString() {
            if (resources == null) {
                return null;
            }
            try {
                return objectMapper.writeValueAsString(resources);
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to convert resources to JSON string: " + e.getMessage(), e);
            }
        }
        

        
        public List<String> getAllowedAddresses() {
            return allowedAddresses;
        }
        
        public void setAllowedAddresses(List<String> allowedAddresses) {
            this.allowedAddresses = allowedAddresses;
        }
        
        public Boolean getAutoAuthorizeByHttpUrl() {
            return autoAuthorizeByHttpUrl;
        }
        
        public void setAutoAuthorizeByHttpUrl(Boolean autoAuthorizeByHttpUrl) {
            this.autoAuthorizeByHttpUrl = autoAuthorizeByHttpUrl;
        }
        
        /**
         * Validates the share data structure.
         * @throws IllegalArgumentException if validation fails
         */
        public void validate() {
            if (ownerLogin == null || ownerLogin.trim().isEmpty()) {
                throw new IllegalArgumentException("ownerLogin is required");
            }
            
            if (resources == null) {
                throw new IllegalArgumentException("resources is required");
            }
            
            // Validate that resources can be converted to JSON string
            try {
                String jsonString = getResourcesAsJsonString();
                if (jsonString == null || jsonString.trim().isEmpty()) {
                    throw new IllegalArgumentException("resources cannot be empty");
                }
                // Validate it's valid JSON by parsing it
                new org.json.JSONObject(jsonString);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("resources must be valid JSON: " + e.getMessage());
            } catch (Exception e) {
                throw new IllegalArgumentException("resources must be valid JSON: " + e.getMessage());
            }
            
            // requesterAddress is automatically set to "<INIT>" for initialization-created shares
            
            // Validate IP addresses in allowedAddresses if provided
            if (allowedAddresses != null) {
                for (int i = 0; i < allowedAddresses.size(); i++) {
                    String addr = allowedAddresses.get(i);
                    if (addr == null || addr.trim().isEmpty()) {
                        throw new IllegalArgumentException("allowedAddress at index " + i + " is null or empty");
                    }
                    
                    // Validate flexible IP patterns: exact IP, CIDR notation, or wildcard (*)
                    if (!isValidIpPattern(addr.trim())) {
                        throw new IllegalArgumentException("Invalid IP address pattern in allowedAddresses[" + i + "]: " + addr + 
                            ". Supported formats: exact IP (192.168.1.1), CIDR notation (192.168.1.0/24), or wildcard (*)");
                    }
                }
            }
        }
        
        /**
         * Validates if a string is a valid IP address pattern.
         * Supports exact IP addresses, CIDR notation, and wildcard (*).
         */
        private boolean isValidIpPattern(String pattern) {
            if (pattern == null || pattern.isEmpty()) {
                return false;
            }
            
            // Allow wildcard pattern
            if ("*".equals(pattern)) {
                return true;
            }
            
            // Check CIDR notation
            if (pattern.contains("/")) {
                try {
                    String[] parts = pattern.split("/");
                    if (parts.length != 2) {
                        return false;
                    }
                    
                    // Validate network address
                    java.net.InetAddress.getByName(parts[0]);
                    
                    // Validate prefix length
                    int prefixLength = Integer.parseInt(parts[1]);
                    byte[] addressBytes = java.net.InetAddress.getByName(parts[0]).getAddress();
                    int maxPrefixLength = addressBytes.length * 8; // 32 for IPv4, 128 for IPv6
                    
                    return prefixLength >= 0 && prefixLength <= maxPrefixLength;
                } catch (Exception e) {
                    return false;
                }
            }
            
            // Check exact IP address
            try {
                java.net.InetAddress.getByName(pattern);
                return true;
            } catch (java.net.UnknownHostException e) {
                return false;
            }
        }
        
        @Override
        public String toString() {
            String resourcesStr = "null";
            if (resources != null) {
                try {
                    String jsonString = getResourcesAsJsonString();
                    resourcesStr = jsonString.length() > 50 ? 
                        jsonString.substring(0, 50) + "..." : jsonString;
                } catch (Exception e) {
                    resourcesStr = "invalid_json";
                }
            }
            return "ShareData{ownerLogin='" + ownerLogin + "', resources='" + resourcesStr + 
                   "', requesterAddress='<INIT>'}";
        }
    }
    
    @Override
    public String toString() {
        return "DatabaseInitializationData{version='" + version + 
               "', users=" + (users != null ? users.size() + " items" : "null") + 
               ", preconfirmedShares=" + (preconfirmedShares != null ? preconfirmedShares.size() + " items" : "null") + "}";
    }
}