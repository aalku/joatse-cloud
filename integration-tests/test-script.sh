#!/bin/sh

echo "=== Integration Test Suite ==="
echo "All services are healthy, starting tests immediately..."

echo "Testing direct HTTP access to echo service..."
java -jar /app/test-client.jar http://http-echo:8080/echo/test 512
if [ $? -ne 0 ]; then
    echo "❌ Direct HTTP test failed"
    exit 1
fi

echo "Testing HTTP tunnel through preconfirmed share..."
java -jar /app/test-client.jar http://joatse-cloud:9101/echo/test 512
if [ $? -ne 0 ]; then
    echo "❌ HTTP tunnel test failed"
    exit 1
fi

echo "Testing WebSocket tunnel through preconfirmed share..."
java -jar /app/test-client.jar ws://joatse-cloud:9101/ws/echo 1024 --ws=text
if [ $? -ne 0 ]; then
    echo "❌ WebSocket text tunnel test failed"
    exit 1
fi

echo "Testing WebSocket binary messages through preconfirmed share..."
java -jar /app/test-client.jar ws://joatse-cloud:9101/ws/echo 2048 --ws=binary --count=5
if [ $? -ne 0 ]; then
    echo "❌ WebSocket binary tunnel test failed"
    exit 1
fi

echo "=== All Integration Tests Passed! ==="