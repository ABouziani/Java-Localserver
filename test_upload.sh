#!/bin/bash

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

HOST="www.example.com"
PORT=8080

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}  HTTP Upload & Chunked Transfer Tests${NC}"
echo -e "${BLUE}========================================${NC}\n"

# Create test files
echo -e "${YELLOW}Creating test files...${NC}"
mkdir -p test_files

# Create a text file
echo "Hello, this is a test file!" > test_files/test.txt

# Create a small image (1x1 pixel PNG)
printf '\x89\x50\x4e\x47\x0d\x0a\x1a\x0a\x00\x00\x00\x0d\x49\x48\x44\x52\x00\x00\x00\x01\x00\x00\x00\x01\x08\x02\x00\x00\x00\x90\x77\x53\xde\x00\x00\x00\x0c\x49\x44\x41\x54\x08\xd7\x63\xf8\xcf\xc0\x00\x00\x03\x01\x01\x00\x18\xdd\x8d\xb4\x00\x00\x00\x00\x49\x45\x4e\x44\xae\x42\x60\x82' > test_files/test.png

# Create a larger file for testing
dd if=/dev/urandom of=test_files/large.bin bs=1024 count=100 2>/dev/null

# Create a JSON file
echo '{"name":"test","value":123}' > test_files/test.json

echo -e "${GREEN}✓ Test files created${NC}\n"

# Test 1: Simple POST with Content-Length (text file)
echo -e "${BLUE}Test 1: POST text file with Content-Length${NC}"
curl -v -X POST \
  -H "Host: $HOST" \
  -H "Content-Type: text/plain" \
  --data-binary @test_files/test.txt \
  http://localhost:$PORT/upload 2>&1 | grep -E "HTTP|Content-Length|< "
echo -e "\n"

# Test 2: POST image with Content-Length
echo -e "${BLUE}Test 2: POST PNG image with Content-Length${NC}"
curl -v -X POST \
  -H "Host: $HOST" \
  -H "Content-Type: image/png" \
  --data-binary @test_files/test.png \
  http://localhost:$PORT/upload 2>&1 | grep -E "HTTP|Content-Length|< "
echo -e "\n"

# Test 3: POST with chunked transfer encoding
echo -e "${BLUE}Test 3: POST with Chunked Transfer-Encoding${NC}"
curl -v -X POST \
  -H "Host: $HOST" \
  -H "Transfer-Encoding: chunked" \
  -H "Content-Type: text/plain" \
  --data-binary @test_files/test.txt \
  http://localhost:$PORT/upload 2>&1 | grep -E "HTTP|Transfer-Encoding|< "
echo -e "\n"

# Test 4: POST JSON data
echo -e "${BLUE}Test 4: POST JSON data${NC}"
curl -v -X POST \
  -H "Host: $HOST" \
  -H "Content-Type: application/json" \
  --data-binary @test_files/test.json \
  http://localhost:$PORT/upload 2>&1 | grep -E "HTTP|Content-Length|< "
echo -e "\n"

# Test 6: Multipart form data (simulated)
echo -e "${BLUE}Test 6: POST form data${NC}"
curl -v -X POST \
  -H "Host: $HOST" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  --data "name=test&value=123&description=hello+world" \
  http://localhost:$PORT/upload 2>&1 | grep -E "HTTP|Content-Length|< "
echo -e "\n"

# Test 7: Test chunked encoding manually
echo -e "${BLUE}Test 7: Manual chunked transfer test${NC}"
(
  echo -ne "POST /upload HTTP/1.1\r\n"
  echo -ne "Host: $HOST\r\n"
  echo -ne "Transfer-Encoding: chunked\r\n"
  echo -ne "Content-Type: text/plain\r\n"
  echo -ne "\r\n"
  # First chunk (5 bytes)
  echo -ne "5\r\n"
  echo -ne "Hello\r\n"
  # Second chunk (7 bytes)
  echo -ne "7\r\n"
  echo -ne " World!\r\n"
  # Last chunk
  echo -ne "0\r\n"
  echo -ne "\r\n"
) | nc localhost $PORT
echo -e "\n"

# Test 8: Test without Content-Length (should fail)
echo -e "${BLUE}Test 8: POST without Content-Length (should return 411)${NC}"
(
  echo -ne "POST /upload HTTP/1.1\r\n"
  echo -ne "Host: $HOST\r\n"
  echo -ne "Content-Type: text/plain\r\n"
  echo -ne "\r\n"
  echo -ne "This should fail"
) | nc localhost $PORT
echo -e "\n"

# Test 9: GET with body (should fail)
echo -e "${BLUE}Test 9: GET with body (should return 400)${NC}"
(
  echo -ne "GET /upload HTTP/1.1\r\n"
  echo -ne "Host: $HOST\r\n"
  echo -ne "Content-Length: 10\r\n"
  echo -ne "\r\n"
  echo -ne "1234567890"
) | nc localhost $PORT
echo -e "\n"

# Test 10: Invalid chunked encoding
echo -e "${BLUE}Test 10: Invalid chunked size (should handle gracefully)${NC}"
(
  echo -ne "POST /upload HTTP/1.1\r\n"
  echo -ne "Host: $HOST\r\n"
  echo -ne "Transfer-Encoding: chunked\r\n"
  echo -ne "\r\n"
  echo -ne "INVALID\r\n"
  echo -ne "Hello\r\n"
  echo -ne "0\r\n"
  echo -ne "\r\n"
) | nc localhost $PORT
echo -e "\n"

# Test 11: Body too large (should return 413)
echo -e "${BLUE}Test 11: Body larger than clientMaxBody (should return 413)${NC}"
dd if=/dev/urandom of=test_files/toolarge.bin bs=1M count=11 2>/dev/null
curl -v -X POST \
  -H "Host: $HOST" \
  -H "Content-Type: application/octet-stream" \
  --data-binary @test_files/toolarge.bin \
  http://localhost:$PORT/upload 2>&1 | grep -E "HTTP|Content-Length|413|< "
echo -e "\n"

# Test 12: Multiple requests in same connection (pipelining)
echo -e "${BLUE}Test 12: Pipeline multiple requests${NC}"
(
  echo -ne "GET / HTTP/1.1\r\n"
  echo -ne "Host: $HOST\r\n"
  echo -ne "Connection: keep-alive\r\n"
  echo -ne "\r\n"
  
  echo -ne "POST /upload HTTP/1.1\r\n"
  echo -ne "Host: $HOST\r\n"
  echo -ne "Content-Length: 12\r\n"
  echo -ne "Content-Type: text/plain\r\n"
  echo -ne "Connection: close\r\n"
  echo -ne "\r\n"
  echo -ne "Hello World!"
) | nc localhost $PORT
echo -e "\n"

# Test 13: Upload actual image with proper multipart (using curl)
echo -e "${BLUE}Test 13: Upload file with multipart/form-data${NC}"
curl -v -X POST \
  -H "Host: $HOST" \
  -F "file=@test_files/test.png" \
  -F "name=myimage" \
  http://localhost:$PORT/upload 2>&1 | grep -E "HTTP|Content-Type|< "
echo -e "\n"

# Cleanup
echo -e "${YELLOW}Cleaning up test files...${NC}"
rm -rf test_files

echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  All tests completed!${NC}"
echo -e "${GREEN}========================================${NC}"