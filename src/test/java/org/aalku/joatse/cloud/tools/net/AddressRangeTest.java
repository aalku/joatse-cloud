package org.aalku.joatse.cloud.tools.net;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetAddress;
import java.net.UnknownHostException;

import org.junit.jupiter.api.Test;

public class AddressRangeTest {

    @Test
    public void testWildcardMatching() throws UnknownHostException {
        AddressRange wildcard = AddressRange.of("*");
        
        assertTrue(wildcard.isWildcard());
        assertFalse(wildcard.isExact());
        assertFalse(wildcard.isCidr());
        
        // Should match any IPv4 address
        assertTrue(wildcard.matches(InetAddress.getByName("192.168.1.1")));
        assertTrue(wildcard.matches(InetAddress.getByName("10.0.0.1")));
        assertTrue(wildcard.matches(InetAddress.getByName("127.0.0.1")));
        
        // Should match any IPv6 address
        assertTrue(wildcard.matches(InetAddress.getByName("::1")));
        assertTrue(wildcard.matches(InetAddress.getByName("2001:db8::1")));
        
        // Should match string representations
        assertTrue(wildcard.matches("8.8.8.8"));
        assertTrue(wildcard.matches("fe80::1"));
        
        assertEquals("*", wildcard.toString());
    }
    
    @Test
    public void testExactIPv4Matching() throws UnknownHostException {
        AddressRange exact = AddressRange.of("192.168.1.100");
        
        assertFalse(exact.isWildcard());
        assertTrue(exact.isExact());
        assertFalse(exact.isCidr());
        
        // Should match exact address
        assertTrue(exact.matches(InetAddress.getByName("192.168.1.100")));
        assertTrue(exact.matches("192.168.1.100"));
        
        // Should not match different addresses
        assertFalse(exact.matches(InetAddress.getByName("192.168.1.101")));
        assertFalse(exact.matches(InetAddress.getByName("192.168.2.100")));
        assertFalse(exact.matches("192.168.1.101"));
        
        assertEquals("192.168.1.100", exact.toString());
    }
    
    @Test
    public void testExactIPv6Matching() throws UnknownHostException {
        AddressRange exact = AddressRange.of("2001:db8::1");
        
        assertTrue(exact.isExact());
        
        // Should match exact address
        assertTrue(exact.matches(InetAddress.getByName("2001:db8::1")));
        assertTrue(exact.matches("2001:db8::1"));
        
        // Should not match different addresses
        assertFalse(exact.matches(InetAddress.getByName("2001:db8::2")));
        assertFalse(exact.matches("::1"));
    }
    
    @Test
    public void testCIDRIPv4Matching() throws UnknownHostException {
        AddressRange cidr = AddressRange.of("192.168.1.0/24");
        
        assertFalse(cidr.isWildcard());
        assertFalse(cidr.isExact());
        assertTrue(cidr.isCidr());
        
        // Should match addresses in the network
        assertTrue(cidr.matches(InetAddress.getByName("192.168.1.0")));
        assertTrue(cidr.matches(InetAddress.getByName("192.168.1.1")));
        assertTrue(cidr.matches(InetAddress.getByName("192.168.1.100")));
        assertTrue(cidr.matches(InetAddress.getByName("192.168.1.255")));
        assertTrue(cidr.matches("192.168.1.50"));
        
        // Should not match addresses outside the network
        assertFalse(cidr.matches(InetAddress.getByName("192.168.0.1")));
        assertFalse(cidr.matches(InetAddress.getByName("192.168.2.1")));
        assertFalse(cidr.matches(InetAddress.getByName("10.0.0.1")));
        assertFalse(cidr.matches("192.168.2.1"));
        
        assertEquals("192.168.1.0/24", cidr.toString());
    }
    
    @Test
    public void testCIDRIPv6Matching() throws UnknownHostException {
        AddressRange cidr = AddressRange.of("2001:db8::/32");
        
        assertTrue(cidr.isCidr());
        
        // Should match addresses in the network
        assertTrue(cidr.matches(InetAddress.getByName("2001:db8::")));
        assertTrue(cidr.matches(InetAddress.getByName("2001:db8::1")));
        assertTrue(cidr.matches(InetAddress.getByName("2001:db8:1234:5678::1")));
        assertTrue(cidr.matches("2001:db8:abcd::1"));
        
        // Should not match addresses outside the network
        assertFalse(cidr.matches(InetAddress.getByName("2001:db9::1")));
        assertFalse(cidr.matches(InetAddress.getByName("::1")));
        assertFalse(cidr.matches("fe80::1"));
    }
    
    @Test
    public void testSmallCIDRNetworks() throws UnknownHostException {
        // Test /30 network (4 addresses)
        AddressRange cidr = AddressRange.of("192.168.1.0/30");
        
        assertTrue(cidr.matches("192.168.1.0"));
        assertTrue(cidr.matches("192.168.1.1"));
        assertTrue(cidr.matches("192.168.1.2"));
        assertTrue(cidr.matches("192.168.1.3"));
        assertFalse(cidr.matches("192.168.1.4"));
        
        assertEquals("192.168.1.0/30", cidr.toString());
    }
    
    @Test
    public void testSingleHostCIDR() throws UnknownHostException {
        // /32 should match only one host
        AddressRange cidr = AddressRange.of("192.168.1.100/32");
        
        assertTrue(cidr.matches("192.168.1.100"));
        assertFalse(cidr.matches("192.168.1.101"));
        
        assertEquals("192.168.1.100/32", cidr.toString());
    }
    
    @Test
    public void testInvalidPatterns() {
        // Invalid IP address
        assertThrows(IllegalArgumentException.class, () -> 
            AddressRange.of("999.999.999.999"));
        
        // Invalid CIDR
        assertThrows(IllegalArgumentException.class, () -> 
            AddressRange.of("192.168.1.0/"));
        
        // Invalid prefix length
        assertThrows(IllegalArgumentException.class, () -> 
            AddressRange.of("192.168.1.0/33"));
        
        // Empty pattern
        assertThrows(IllegalArgumentException.class, () -> 
            AddressRange.of(""));
        
        // Null pattern
        assertThrows(IllegalArgumentException.class, () -> 
            AddressRange.of(null));
    }
    
    @Test
    public void testNullAndInvalidInputs() throws UnknownHostException {
        AddressRange exact = AddressRange.of("192.168.1.1");
        
        // Null inputs should return false
        assertFalse(exact.matches((InetAddress) null));
        assertFalse(exact.matches((String) null));
        assertFalse(exact.matches(""));
        assertFalse(exact.matches("   "));
        
        // Invalid IP string should return false
        assertFalse(exact.matches("invalid-ip"));
        assertFalse(exact.matches("999.999.999.999"));
    }
    
    @Test
    public void testEqualsAndHashCode() {
        AddressRange addr1 = AddressRange.of("192.168.1.0/24");
        AddressRange addr2 = AddressRange.of("192.168.1.0/24");
        AddressRange addr3 = AddressRange.of("192.168.2.0/24");
        AddressRange wildcard1 = AddressRange.of("*");
        AddressRange wildcard2 = AddressRange.of("*");
        
        assertEquals(addr1, addr2);
        assertEquals(addr1.hashCode(), addr2.hashCode());
        
        assertNotEquals(addr1, addr3);
        
        assertEquals(wildcard1, wildcard2);
        assertEquals(wildcard1.hashCode(), wildcard2.hashCode());
        
        assertNotEquals(addr1, wildcard1);
    }
}
