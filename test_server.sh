#!/bin/bash

# HTTP Parser Byte Test Script
# Run your server first, then execute this script

SERVER="http://localhost:8080"

echo "=== HTTP Parser Byte Tests with cURL ==="
echo ""

# Test 1: Simple GET
echo "Test 1: Simple GET"
curl -v "$SERVER/index.html" 2>&1 | head -n 20
echo ""
echo "---"
echo ""

# Test 2: POST with text body
echo "Test 2: POST with text data"
curl -v -X POST "$SERVER/me./test_server.sh
" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "username=admin&password=secret123" 2>&1 | head -n 20
echo ""
echo "---"
echo ""

# Test 3: POST with JSON
echo "Test 3: POST with JSON"
curl -v -X POST "$SERVER/api/data" \
  -H "Content-Type: application/json" \
  -d '{"name":"test","value":123}' 2>&1 | head -n 20
echo ""
echo "---"
echo ""

# Test 4: POST with binary file
echo "Test 4: POST with binary file"
# Create a test binary file
dd if=/dev/urandom of=/tmp/test_binary.bin bs=1024 count=10 2>/dev/null
curl -v -X POST "$SERVER/upload" \
  -H "Content-Type: application/octet-stream" \
  --data-binary "@/tmp/test_binary.bin" 2>&1 | head -n 20
echo ""
echo "---"
echo ""

# Test 5: POST with large body
echo "Test 5: POST with large body (1MB)"
dd if=/dev/zero of=/tmp/large_file.bin bs=1024 count=1024 2>/dev/null
curl -v -X POST "$SERVER/large" \
  --data-binary "@/tmp/large_file.bin" 2>&1 | head -n 20
echo ""
echo "---"
echo ""

# Test 6: Chunked transfer encoding
echo "Test 6: Chunked transfer encoding"
curl -v -X POST "$SERVER/chunked" \
  -H "Transfer-Encoding: chunked" \
  -d "This is chunked data" 2>&1 | head -n 20
echo ""
echo "---"
echo ""

# Test 7: Multiple headers
echo "Test 7: Multiple custom headers"
curl -v "$SERVER/headers" \
  -H "X-Custom-1: value1" \
  -H "X-Custom-2: value2" \
  -H "X-Custom-3: value3" 2>&1 | head -n 20
echo ""
echo "---"
echo ""

# Test 8: URL with special characters
echo "Test 8: URL with encoded characters"
curl -v "$SERVER/path/with%20space/and%2Fslash?query=test%26value" 2>&1 | head -n 20
echo ""
echo "---"
echo ""

# Test 9: Invalid request (should fail)
echo "Test 9: Invalid HTTP version (should fail with 505)"
printf "GET /test HTTP/2.0\r\nHost: localhost\r\n\r\n" | nc localhost 8080
echo ""
echo "---"
echo ""

# Test 10: Missing Host header (should fail)
echo "Test 10: Missing Host header (should fail with 400)"
printf "GET /test HTTP/1.1\r\n\r\n" | nc localhost 8080
echo ""
echo "---"
echo ""

# Cleanup
rm -f /tmp/test_binary.bin /tmp/large_file.bin

echo ""
echo "=== Tests Complete ==="