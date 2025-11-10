package org.aalku.joatse.cloud.service.initialization;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Collections;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.aalku.joatse.cloud.config.SecurityConfigurationProperties;
import org.aalku.joatse.cloud.tools.net.AddressRange;
import org.aalku.joatse.cloud.service.user.repository.PreconfirmedSharesRepository;
import org.aalku.joatse.cloud.service.user.repository.UserRepository;
import org.aalku.joatse.cloud.service.user.vo.JoatseUser;
import org.aalku.joatse.cloud.service.user.vo.PreconfirmedShare;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * MongoDB-style database initialization service.
 * Handles atomic initialization of users and preconfirmed shares from JSON files.
 * 
 * Initialization Strategy:
 * - Only initialize if database appears to be empty (no admin user exists)
 * - Always fail-fast on errors (FAIL_ON_ERROR=true behavior)
 * - Never reinitialize existing database (FORCE_REINIT=false behavior)
 * - Load users and preconfirmed shares from JSON file if INITIALIZATION_FILE is set
 * - All initialization happens in single transaction for atomicity
 */
@Component
@Configuration
public class DatabaseInitializer {

    private static final Logger log = LoggerFactory.getLogger(DatabaseInitializer.class);

    @Autowired
    private UserRepository userRepository;
    
    @Autowired
    private PreconfirmedSharesRepository preconfirmedSharesRepository;
    
    @Autowired
    private SecurityConfigurationProperties securityConfig;
    
    @Value("${initialization.file:}")
    private String initializationFile;
    
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Initializes the database if it appears to be empty.
     * This method is called during Spring context initialization.
     * The @Bean annotation ensures this is called during startup.
     * The @Transactional annotation ensures atomicity - all operations succeed or all fail.
     */
    @Bean
    @Transactional
    public String initializeDatabase() {
        log.info("Starting database initialization check...");
        log.info("Initialization file configured as: '{}'", initializationFile);
        
        // Check if database already has content (admin user exists)
        JoatseUser existingAdmin = userRepository.findByLogin("admin");
        if (existingAdmin != null && existingAdmin.getPassword() != null && !existingAdmin.getPassword().trim().isEmpty()) {
            log.info("Database already initialized (admin user exists with password), skipping initialization");
            return "Database already initialized";
        }
        
        log.info("Database appears to be empty, starting initialization...");
        
        try {
            // Create admin user
            createOrUpdateAdminUser(existingAdmin);
            
            // Load users and preconfirmed shares if JSON file is configured
            if (initializationFile != null && !initializationFile.trim().isEmpty()) {
                loadDataFromJson(initializationFile.trim());
            } else {
                log.info("No initialization file configured, skipping JSON-based initialization");
            }
            
            log.info("Database initialization completed successfully");
            return "Database initialization completed successfully";
            
        } catch (Exception e) {
            log.error("Database initialization failed", e);
            throw new RuntimeException("Database initialization failed: " + e.getMessage(), e);
        }
    }
    
    /**
     * Creates or updates the admin user with a new password if needed.
     */
    private JoatseUser createOrUpdateAdminUser(JoatseUser existingAdmin) {
        JoatseUser admin = existingAdmin;
        
        if (admin == null) {
            log.info("Creating new admin user");
            admin = JoatseUser.newLocalUser("admin", false);
        } else {
            log.info("Updating existing admin user with new password");
        }
        
        // Set new password
        admin.setPassword(randomPassword(pw -> saveTempAdminPassword(pw)));
        admin.addAuthority(new SimpleGrantedAuthority("ROLE_JOATSE_ADMIN"));
        
        userRepository.save(admin);
        log.info("Admin user created/updated successfully");
        
        return admin;
    }
    
    /**
     * Loads users and preconfirmed shares from JSON file.
     */
    private void loadDataFromJson(String jsonFilePath) {
        log.info("Loading database initialization data from JSON file: {}", jsonFilePath);
        
        Path path = Path.of(jsonFilePath);
        if (!Files.exists(path)) {
            log.warn("Database initialization JSON file does not exist: {}", jsonFilePath);
            return;
        }
        
        if (!Files.isReadable(path)) {
            throw new RuntimeException("Database initialization JSON file is not readable: " + jsonFilePath);
        }
        
        try {
            // Read and parse JSON file
            String jsonContent = Files.readString(path);
            DatabaseInitializationData data = objectMapper.readValue(jsonContent, DatabaseInitializationData.class);
            
            // Validate data structure and version
            data.validate();
            
            log.info("JSON file validation passed, version: {}, users count: {}, preconfirmed shares count: {}", 
                     data.getVersion(), 
                     data.getUsers() != null ? data.getUsers().size() : 0,
                     data.getPreconfirmedShares() != null ? data.getPreconfirmedShares().size() : 0);
            
            // Process users first (so they exist for preconfirmed shares)
            if (data.getUsers() != null && !data.getUsers().isEmpty()) {
                processUsers(data.getUsers());
            }
            
            // Process preconfirmed shares
            if (data.getPreconfirmedShares() != null && !data.getPreconfirmedShares().isEmpty()) {
                processPreconfirmedShares(data.getPreconfirmedShares());
            }
            
            log.info("Database initialization from JSON completed successfully");
            
        } catch (IOException e) {
            throw new RuntimeException("Failed to read database initialization JSON file: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to process database initialization JSON file: " + e.getMessage(), e);
        }
    }
    
    /**
     * Processes user creation from JSON data.
     */
    private void processUsers(List<DatabaseInitializationData.UserData> users) {
        log.info("Processing {} users from JSON", users.size());
        
        int createdCount = 0;
        int skippedCount = 0;
        
        for (DatabaseInitializationData.UserData userData : users) {
            try {
                boolean created = createUserFromData(userData);
                if (created) {
                    createdCount++;
                } else {
                    skippedCount++;
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to create user '" + 
                                         userData.getLogin() + "': " + e.getMessage(), e);
            }
        }
        
        log.info("User initialization completed: {} created, {} skipped", createdCount, skippedCount);
    }
    
    /**
     * Processes preconfirmed shares from JSON data.
     */
    private void processPreconfirmedShares(List<DatabaseInitializationData.ShareData> shares) {
        log.info("Processing {} preconfirmed shares from JSON", shares.size());
        
        int createdCount = 0;
        int skippedCount = 0;
        
        for (DatabaseInitializationData.ShareData shareData : shares) {
            try {
                boolean created = createPreconfirmedShare(shareData);
                if (created) {
                    createdCount++;
                } else {
                    skippedCount++;
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to create preconfirmed share for owner '" + 
                                         shareData.getOwnerLogin() + "': " + e.getMessage(), e);
            }
        }
        
        log.info("Preconfirmed shares initialization completed: {} created, {} skipped", createdCount, skippedCount);
    }
    
    /**
     * Creates a user from JSON data.
     * Returns true if created, false if skipped (user already exists).
     */
    private boolean createUserFromData(DatabaseInitializationData.UserData userData) {
        // Check if user already exists
        JoatseUser existingUser = userRepository.findByLogin(userData.getLogin());
        if (existingUser != null) {
            log.warn("Skipping user creation - user already exists: {}", userData.getLogin());
            return false;
        }
        
        // Create new user
        JoatseUser user = JoatseUser.newLocalUser(userData.getLogin(), false);
        
        // Set password (encoded)
        PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        user.setPassword(encoder.encode(userData.getPassword()));
        
        // Only regular users can be created via JSON initialization for security reasons
        
        // Set email confirmation status
        if (Boolean.TRUE.equals(userData.getEmailConfirmed())) {
            user.setEmailConfirmed();
        }
        
        // Set application use permission
        if (Boolean.TRUE.equals(userData.getApplicationUseAllowed())) {
            user.allowApplicationUse();
        }
        
        // Save user
        userRepository.save(user);
        
        log.info("Created user: {} (emailConfirmed: {}, appUseAllowed: {})", 
                 user.getUsername(), 
                 Boolean.TRUE.equals(userData.getEmailConfirmed()),
                 Boolean.TRUE.equals(userData.getApplicationUseAllowed()));
        
        return true;
    }
    
    /**
     * Creates a preconfirmed share from JSON data.
     * Returns true if created, false if skipped (user not found).
     */
    private boolean createPreconfirmedShare(DatabaseInitializationData.ShareData shareData) {
        // Find owner user
        JoatseUser owner = userRepository.findByLogin(shareData.getOwnerLogin());
        if (owner == null) {
            log.warn("Skipping preconfirmed share - owner user not found: {}", shareData.getOwnerLogin());
            return false;
        }
        
        // Create PreconfirmedShare entity
        PreconfirmedShare share = new PreconfirmedShare();
        // Use provided preconfirmation ID or generate random UUID
        if (shareData.getPreconfirmationId() != null && !shareData.getPreconfirmationId().trim().isEmpty()) {
            share.setUuid(UUID.fromString(shareData.getPreconfirmationId().trim()));
        } else {
            share.setUuid(UUID.randomUUID());
        }
        share.setOwner(owner);
        share.setResources(shareData.getResourcesAsJsonString());
        
        // Set requester address to indicate this was created through initialization
        share.setRequesterAddress("<INIT>");
        
        // Set allowed addresses if provided and unsafe static authorization is enabled
        if (securityConfig.isUnsafeStaticRemoteAddressAuthorization() && 
            shareData.getAllowedAddresses() != null && !shareData.getAllowedAddresses().isEmpty()) {
            
            // Convert to AddressRange objects for proper pattern matching
            List<AddressRange> addressRanges = shareData.getAllowedAddresses().stream()
                .filter(addr -> addr != null && !addr.trim().isEmpty())
                .map(addr -> {
                    try {
                        return AddressRange.of(addr.trim());
                    } catch (IllegalArgumentException e) {
                        log.warn("Invalid IP address pattern '{}': {}", addr.trim(), e.getMessage());
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
            
            // Store validated address patterns as strings using the new method
            List<String> validAddressPatterns = shareData.getAllowedAddresses().stream()
                .filter(addr -> addr != null && !addr.trim().isEmpty())
                .map(String::trim)
                .collect(Collectors.toList());
            
            share.setAllowedAddressPatterns(validAddressPatterns);
            
            log.warn("SECURITY WARNING: Static remote address authorization is enabled. " +
                     "This is unsafe for production and should only be used for testing/embedded systems. " +
                     "Loaded {} address range patterns for share '{}'", addressRanges.size(), share.getUuid());
        } else {
            share.setAllowedAddressPatterns(Collections.emptyList());
            if (shareData.getAllowedAddresses() != null && !shareData.getAllowedAddresses().isEmpty()) {
                log.info("Ignoring allowedAddresses from initialization data because " +
                         "unsafeStaticRemoteAddressAuthorization is disabled (secure mode)");
            }
        }
        
        // Set auto-authorize flag
        share.setAutoAuthorizeByHttpUrl(
            Optional.ofNullable(shareData.getAutoAuthorizeByHttpUrl()).orElse(false)
        );
        
        // Save the preconfirmed share
        preconfirmedSharesRepository.save(share);
        
        // Log at INFO level, especially important when preconfirmation ID was auto-generated
        if (shareData.getPreconfirmationId() == null || shareData.getPreconfirmationId().trim().isEmpty()) {
            log.warn("Created preconfirmed share for owner '{}' with auto-generated ID: {}", 
                     owner.getUsername(), share.getUuid());
        } else {
            log.info("Created preconfirmed share for owner '{}' with provided ID: {}", 
                     owner.getUsername(), share.getUuid());
        }
        return true;
    }
    
    /**
     * Saves the temporary admin password to a file for user reference.
     */
    private void saveTempAdminPassword(CharSequence pw) {
        Path path = Path.of("joatse_admin_temp_password.txt");
        String instructions1 = "This file contains the original password for admin user.";
        String instructions2 = "This file does not update if the password is changed and any manual edit of this file will have no effect.";
        String instructions3 = "If you lost the password you can reset it in the database file.";
        String instructions4 = "If you set the field blank a new one will be generated and this file will be overwritten in the next restart.";
        try {
            Files.write(path, Arrays.asList(instructions1, instructions2, instructions3, instructions4, "", pw), 
                       StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            System.err.println("Admin password saved to file: " + path.toAbsolutePath());
        } catch (IOException e) {
            throw new RuntimeException("Can't write password file: " + e.toString());
        }
    }

    /**
     * Generates a random password, prints it to the password file, encodes it and returns the encoded version.
     */
    private String randomPassword(Consumer<CharSequence> passwordListener) {
        /* Generate, print, encode, forget */
        int passLen = 30;
        String AB = "023456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
        Random rnd = new Random();
        StringBuilder sb = new StringBuilder(passLen);
        for (int i = 0; i < passLen; i++) {
            sb.append(AB.charAt(rnd.nextInt(AB.length())));
        }
        passwordListener.accept(sb);
        PasswordEncoder ec = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        String encoded = ec.encode(sb);
        sb.setLength(0);
        for (int i = 0; i < sb.capacity(); i++) {
            sb.append(' ');
        }
        return encoded;
    }
}