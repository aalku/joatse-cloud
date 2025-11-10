package org.aalku.joatse.cloud.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Security configuration properties for the Joatse Cloud service.
 * 
 * SECURITY WARNING: These settings can significantly impact the security posture
 * of your deployment. Review carefully before enabling any unsafe options.
 */
@Component
@ConfigurationProperties(prefix = "joatse.security")
public class SecurityConfigurationProperties {

    /**
     * UNSAFE: Enable static remote address authorization for tunnels.
     * 
     * WARNING: This setting is UNSAFE for production environments!
     * 
     * When enabled (true):
     * - Allows tunnels to be authorized based on static IP addresses from allowedAddresses
     * - Bypasses the secure wildcard DNS-based authorization mechanism
     * - Creates potential security vulnerabilities through IP spoofing and network attacks
     * 
     * When disabled (false, default):
     * - Uses secure wildcard DNS-based authorization with cryptographic subdomain generation
     * - Prevents IP-based authorization vulnerabilities
     * - Recommended for all production deployments
     * 
     * This setting should ONLY be enabled for:
     * - Integration testing environments
     * - Embedded systems without wildcard DNS capabilities
     * - Development environments with controlled network access
     * 
     * Default: false (secure mode)
     */
    private boolean unsafeStaticRemoteAddressAuthorization = false;

    public boolean isUnsafeStaticRemoteAddressAuthorization() {
        return unsafeStaticRemoteAddressAuthorization;
    }

    public void setUnsafeStaticRemoteAddressAuthorization(boolean unsafeStaticRemoteAddressAuthorization) {
        this.unsafeStaticRemoteAddressAuthorization = unsafeStaticRemoteAddressAuthorization;
    }
}