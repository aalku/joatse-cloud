package org.aalku.joatse.cloud.tools.net;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Objects;

/**
 * Represents an IP address range pattern that can be:
 * - A specific IP address (IPv4 or IPv6)
 * - A CIDR network (e.g., 192.168.1.0/24)
 * - A wildcard (*) that matches any address
 * 
 * This class replaces InetAddress for flexible IP address matching and is optimized 
 * for fast matching using byte-level operations.
 */
public class AddressRange {
    
    private final Type type;
    private final byte[] address;
    private final int prefixLength;
    private final byte[] networkMask;
    
    private enum Type {
        WILDCARD,    // Matches any address
        EXACT,       // Exact IP address match
        CIDR         // CIDR network match
    }
    
    /**
     * Creates an AddressRange from a string pattern.
     * 
     * @param pattern The pattern: "*", "192.168.1.100", "10.0.0.0/8", "::1", "2001:db8::/32"
     * @throws IllegalArgumentException if the pattern is invalid
     */
    public static AddressRange of(String pattern) {
        if (pattern == null || pattern.trim().isEmpty()) {
            throw new IllegalArgumentException("Pattern cannot be null or empty");
        }
        
        String trimmed = pattern.trim();
        
        // Handle wildcard
        if ("*".equals(trimmed)) {
            return new AddressRange(Type.WILDCARD, null, 0, null);
        }
        
        // Handle CIDR notation
        if (trimmed.contains("/")) {
            return parseCidr(trimmed);
        }
        
        // Handle exact IP address
        try {
            InetAddress addr = InetAddress.getByName(trimmed);
            return new AddressRange(Type.EXACT, addr.getAddress(), 0, null);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Invalid IP address: " + trimmed, e);
        }
    }
    
    private static AddressRange parseCidr(String cidr) {
        int slashIndex = cidr.lastIndexOf('/');
        if (slashIndex == -1 || slashIndex == cidr.length() - 1) {
            throw new IllegalArgumentException("Invalid CIDR notation: " + cidr);
        }
        
        String networkPart = cidr.substring(0, slashIndex);
        String prefixPart = cidr.substring(slashIndex + 1);
        
        int prefixLength;
        try {
            prefixLength = Integer.parseInt(prefixPart);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid prefix length in CIDR: " + cidr, e);
        }
        
        try {
            InetAddress networkAddr = InetAddress.getByName(networkPart);
            byte[] address = networkAddr.getAddress();
            
            // Validate prefix length
            int maxPrefixLength = address.length * 8; // 32 for IPv4, 128 for IPv6
            if (prefixLength < 0 || prefixLength > maxPrefixLength) {
                throw new IllegalArgumentException("Invalid prefix length " + prefixLength + 
                    " for " + (address.length == 4 ? "IPv4" : "IPv6") + " address: " + cidr);
            }
            
            byte[] networkMask = createNetworkMask(address.length, prefixLength);
            
            // Apply network mask to the address to ensure it's a proper network address
            byte[] networkAddress = new byte[address.length];
            for (int i = 0; i < address.length; i++) {
                networkAddress[i] = (byte) (address[i] & networkMask[i]);
            }
            
            return new AddressRange(Type.CIDR, networkAddress, prefixLength, networkMask);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Invalid network address in CIDR: " + cidr, e);
        }
    }
    
    private static byte[] createNetworkMask(int addressLength, int prefixLength) {
        byte[] mask = new byte[addressLength];
        int bytesWithFullMask = prefixLength / 8;
        int remainingBits = prefixLength % 8;
        
        // Set full bytes to 0xFF
        for (int i = 0; i < bytesWithFullMask; i++) {
            mask[i] = (byte) 0xFF;
        }
        
        // Set partial byte
        if (remainingBits > 0 && bytesWithFullMask < addressLength) {
            mask[bytesWithFullMask] = (byte) (0xFF << (8 - remainingBits));
        }
        
        return mask;
    }
    
    private AddressRange(Type type, byte[] address, int prefixLength, byte[] networkMask) {
        this.type = type;
        this.address = address;
        this.prefixLength = prefixLength;
        this.networkMask = networkMask;
    }
    
    /**
     * Checks if this pattern matches the given IP address.
     * 
     * @param testAddress the IP address to test
     * @return true if the address matches this pattern
     */
    public boolean matches(InetAddress testAddress) {
        if (testAddress == null) {
            return false;
        }
        
        switch (type) {
            case WILDCARD:
                return true;
                
            case EXACT:
                return Arrays.equals(address, testAddress.getAddress());
                
            case CIDR:
                return matchesCidr(testAddress.getAddress());
                
            default:
                return false;
        }
    }
    
    /**
     * Checks if this pattern matches the given IP address string.
     * 
     * @param testAddressString the IP address string to test
     * @return true if the address matches this pattern
     */
    public boolean matches(String testAddressString) {
        if (testAddressString == null || testAddressString.trim().isEmpty()) {
            return false;
        }
        
        try {
            InetAddress testAddress = InetAddress.getByName(testAddressString.trim());
            return matches(testAddress);
        } catch (UnknownHostException e) {
            return false;
        }
    }
    
    private boolean matchesCidr(byte[] testAddressBytes) {
        // Different IP versions don't match
        if (testAddressBytes.length != address.length) {
            return false;
        }
        
        // Apply network mask to test address and compare with network address
        for (int i = 0; i < address.length; i++) {
            if ((testAddressBytes[i] & networkMask[i]) != (address[i] & networkMask[i])) {
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * Returns the original pattern string representation.
     */
    @Override
    public String toString() {
        switch (type) {
            case WILDCARD:
                return "*";
                
            case EXACT:
                try {
                    return InetAddress.getByAddress(address).getHostAddress();
                } catch (UnknownHostException e) {
                    return "Invalid[" + Arrays.toString(address) + "]";
                }
                
            case CIDR:
                try {
                    return InetAddress.getByAddress(address).getHostAddress() + "/" + prefixLength;
                } catch (UnknownHostException e) {
                    return "Invalid[" + Arrays.toString(address) + "/" + prefixLength + "]";
                }
                
            default:
                return "Unknown";
        }
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        
        AddressRange that = (AddressRange) obj;
        return type == that.type &&
               prefixLength == that.prefixLength &&
               Arrays.equals(address, that.address) &&
               Arrays.equals(networkMask, that.networkMask);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(type, Arrays.hashCode(address), prefixLength, Arrays.hashCode(networkMask));
    }
    
    /**
     * Returns true if this is a wildcard pattern that matches any address.
     */
    public boolean isWildcard() {
        return type == Type.WILDCARD;
    }
    
    /**
     * Returns true if this is an exact IP address match.
     */
    public boolean isExact() {
        return type == Type.EXACT;
    }
    
    /**
     * Returns true if this is a CIDR network pattern.
     */
    public boolean isCidr() {
        return type == Type.CIDR;
    }
    
    /**
     * Converts this AddressRange to an InetAddress if it represents an exact IP address.
     * @return InetAddress if this is an exact address, empty Optional otherwise
     */
    public java.util.Optional<InetAddress> toInetAddress() {
        if (type == Type.EXACT && address != null) {
            try {
                return java.util.Optional.of(InetAddress.getByAddress(address));
            } catch (UnknownHostException e) {
                return java.util.Optional.empty();
            }
        }
        return java.util.Optional.empty();
    }
}