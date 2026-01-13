#!/bin/bash

# Extra tests for your server

echo "===== TEST INVALID METHOD (PUT) /upload ====="
curl -X PUT http://127.0.0.1:8080/upload -v
echo -e "\n\n"

echo "===== TEST POST /test (method not allowed) ====="
curl -X POST --resolve test.com:8080:127.0.0.1 http://test.com/test -v
echo -e "\n\n"

echo "===== TEST GET /upload with directory listing ====="
curl http://127.0.0.1:8080/upload -v
echo -e "\n\n"

echo "===== TEST CHUNKED SMALL TEXT POST ====="
echo -e "Hello\nThis is chunked text\n" > small.txt
curl -X POST http://127.0.0.1:8080/upload \
     -H "Transfer-Encoding: chunked" \
     -H "Content-Type: text/plain" \
     --data-binary @small.txt -v
rm small.txt
echo -e "\n\n"

echo "===== TEST CHUNKED LARGE TEXT POST ====="
dd if=/dev/zero of=big.txt bs=1M count=2
curl -X POST http://127.0.0.1:8080/upload \
     -H "Transfer-Encoding: chunked" \
     -H "Content-Type: text/plain" \
     --data-binary @big.txt -v
rm big.txt
echo -e "\n\n"



echo "===== TEST 404 on undefined route ====="
curl http://127.0.0.1:8080/undefined-route -v
echo -e "\n\n"

echo "===== TEST 500 by CGI error (simulate) ====="
# Create a Python script that fails
echo 'raise Exception("fail")' > fail.py
curl -X GET http://127.0.0.1:8080/fail.py -v
rm fail.py
echo -e "\n\n"

echo "===== TEST MULTIPLE HOSTS ====="
curl --resolve test.com:8080:127.0.0.1 http://test.com/ -v
curl --resolve teswt.com:8081:127.0.0.1 http://teswt.com/ -v
echo -e "\n\n"
