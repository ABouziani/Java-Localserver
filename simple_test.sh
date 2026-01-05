#!/bin/bash

# Simple test script for HTTP upload
HOST="www.example.com"
PORT=8080

echo "=== Creating test files ==="
mkdir -p uploads

# Create test text file
echo "This is a test file for upload!" > test.txt

# Create test JSON
echo '{"message":"Hello from JSON"}' > test.json

# Create a small PNG image (1x1 red pixel)
echo "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8/5+hHgAHggJ/PchI7wAAAABJRU5ErkJggg==" | base64 -d > test.png

# Create a larger binary file for testing
dd if=/dev/urandom of=large.bin bs=1024 count=50 2>/dev/null

echo "✓ Files created: test.txt, test.json, test.png, large.bin"

echo ""
echo "=== Test 1: Upload text file ==="
curl -X POST \
  -H "Host: $HOST" \
  -H "Content-Type: text/plain" \
  --data-binary @test.txt \
  http://localhost:$PORT/upload

echo ""
echo ""
echo "=== Test 2: Upload image ==="
curl -X POST \
  -H "Host: $HOST" \
  -H "Content-Type: image/png" \
  --data-binary @test.png \
  http://localhost:$PORT/upload

echo ""
echo ""
echo "=== Test 3: Upload JSON ==="
curl -X POST \
  -H "Host: $HOST" \
  -H "Content-Type: application/json" \
  --data-binary @test.json \
  http://localhost:$PORT/upload



echo ""
echo ""
echo "=== Test 5: Chunked transfer ==="
curl -X POST \
  -H "Host: $HOST" \
  -H "Transfer-Encoding: chunked" \
  --data-binary @test.txt \
  http://localhost:$PORT/upload

echo ""
echo ""
echo "=== Test 6: Form data ==="
curl -X POST \
  -H "Host: $HOST" \
  -d "name=test&value=123" \
  http://localhost:$PORT/upload

echo ""
echo ""
echo "=== Test 7: Multipart file upload ==="
curl -X POST \
  -H "Host: $HOST" \
  -F "file=@test.txt" \
  -F "description=My test file" \
  http://localhost:$PORT/upload

echo ""
echo ""
echo "=== Test 8: No Content-Length (should fail with 411) ==="
(
  printf "POST /upload HTTP/1.1\r\n"
  printf "Host: $HOST\r\n"
  printf "\r\n"
) | nc localhost $PORT

echo ""
echo ""
echo "=== Test 9: GET with body (should fail with 400) ==="
(
  printf "GET /upload HTTP/1.1\r\n"
  printf "Host: $HOST\r\n"
  printf "Content-Length: 5\r\n"
  printf "\r\n"
  printf "Hello"
) | nc localhost $PORT

echo ""
echo ""
echo "=== Test 10: Manual chunked request ==="
(
  printf "POST /upload HTTP/1.1\r\n"
  printf "Host: $HOST\r\n"
  printf "Transfer-Encoding: chunked\r\n"
  printf "\r\n"
  printf "5\r\n"
  printf "Hello\r\n"
  printf "0\r\n"
  printf " World\r\n"
  printf "0\r\n"
  printf "\r\n"
) | nc localhost $PORT

echo ""
echo ""
echo "=== Cleanup ==="
rm -f test.txt test.json test.png large.bin

echo ""
echo "All tests completed!"