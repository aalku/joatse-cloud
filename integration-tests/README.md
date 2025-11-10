# Integration Tests

This directory contains integration tests for the complete Joatse tunneling system using Docker Compose.

## Test Architecture

The integration test spins up a complete environment:
1. **http-echo**: Target service to tunnel to
2. **joatse-cloud**: Cloud service with JSON-based initialization  
3. **joatse-target**: Creates tunnel using preconfirmed share
4. **integration-test-runner**: Runs test suite through the tunnel

## Database Initialization Testing

The test verifies the new JSON-based database initialization system:
- Creates test users during startup
- Creates preconfirmed shares with specific preconfirmation IDs
- Tests that joatse-target can use the preconfirmed share
- Validates complete tunnel flow

## Running Tests

```bash
cd integration-tests
docker compose up --build
```

The test runner will:
1. Wait for all services to be ready
2. Test direct access to the echo service  
3. Test HTTP tunneling through preconfirmed share
4. Test WebSocket tunneling through preconfirmed share
5. Report success/failure

## Test Data

- **test-data/database-init.json**: JSON initialization file
  - Creates integration test users
  - Creates preconfirmed share with preconfirmation ID `550e8400-e29b-41d4-a716-446655440001`
  - Share allows access from any IP (`0.0.0.0`)

## Expected Behavior

✅ **On Success**: All services start, tunnel establishes, tests pass  
❌ **On Failure**: Check logs for initialization errors or tunnel connection issues

## Cleanup

```bash
docker compose down -v
```

This removes all containers and volumes, ensuring clean test runs.