# Database Initialization from JSON

Initialize users and preconfirmed shares from JSON during startup. Only runs on empty database (no admin user exists), all operations are atomic.

You can't use this system to add new already-confirmed shares, or users, to an existing setup.

Security of the shares (the owner of the shared resource is the one authorizing it, and only that user can allow IP addresses to access it) is enforced from the moment the cloud application is initialized, but we allow this preinitialization as an exception for embedded systems, containers for a single purpose or integration tests.

## Configuration

```bash
export INITIALIZATION_FILE=/app/data/database-init.json
```

## JSON Format

Version must be exactly `"1.0"`. Both `users` and `preconfirmedShares` are optional.

```json
{
  "version": "1.0",
  "users": [
    {
      "login": "developer@example.com",
      "password": "developer123", 
      "emailConfirmed": true,
      "applicationUseAllowed": true
    }
  ],
  "preconfirmedShares": [
    {
      "ownerLogin": "developer@example.com",
      "resources": {
        "httpTunnels": [
          {
            "targetUrl": "http://example.com:8080"
          }
        ]
      },
      "allowedAddresses": ["127.0.0.1", "192.168.1.100"],
      "autoAuthorizeByHttpUrl": true
    }
  ]
}
```

**User fields**: `login` (email), `password` (6+ chars), `emailConfirmed`, `applicationUseAllowed`  
**Share fields**: `preconfirmationId` (optional), `ownerLogin`, `resources` (JSON object), `allowedAddresses`, `autoAuthorizeByHttpUrl`

### Resources Examples

**HTTP Tunnel:**
```json
{
  "httpTunnels": [
    {
      "targetUrl": "http://example.com:8080"
    }
  ]
}
```

**TCP Tunnel:**
```json
{
  "tcpTunnels": [
    {
      "targetHostname": "localhost",
      "targetPort": 3306
    }
  ]
}
```

**SOCKS5 Proxy:**
```json
{
  "socks5Tunnel": true
}
```

**Command Tunnel:**
```json
{
  "commandTunnels": [
    {
      "command": ["echo", "hello"]
    }
  ]
}
```

## Docker Usage

```yaml
services:
  joatse-cloud:
    environment:
      - INITIALIZATION_FILE=/app/data/database-init.json
    volumes:
      - ./data:/app/data
```

## Complete Example

```json
{
  "version": "1.0",
  "users": [
    {
      "login": "developer@example.com",
      "password": "securePassword123",
      "emailConfirmed": true,
      "applicationUseAllowed": true
    }
  ],
  "preconfirmedShares": [
    {
      "ownerLogin": "developer@example.com", 
      "resources": {
        "httpTunnels": [
          {
            "targetUrl": "http://internal-service:8080"
          }
        ]
      },
      "allowedAddresses": ["192.168.1.0/24"],
      "autoAuthorizeByHttpUrl": true
    }
  ]
}
```