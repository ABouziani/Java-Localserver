#!/usr/bin/env python3

import http.client
import json
import os

HOST = "www.example.com"
PORT = 8080

def test_text_upload():
    """Test 1: Upload text file with Content-Length"""
    print("\n=== Test 1: Upload text file ===")
    
    data = b"Hello, this is a test file!"
    
    conn = http.client.HTTPConnection("localhost", PORT)
    headers = {
        "Host": HOST,
        "Content-Type": "text/plain",
        "Content-Length": str(len(data))
    }
    
    conn.request("POST", "/upload", data, headers)
    response = conn.getresponse()
    
    print(f"Status: {response.status} {response.reason}")
    print(f"Response: {response.read().decode()}")
    conn.close()

def test_json_upload():
    """Test 2: Upload JSON data"""
    print("\n=== Test 2: Upload JSON ===")
    
    data = json.dumps({"name": "test", "value": 123, "items": [1, 2, 3]})
    data_bytes = data.encode('utf-8')
    
    conn = http.client.HTTPConnection("localhost", PORT)
    headers = {
        "Host": HOST,
        "Content-Type": "application/json",
        "Content-Length": str(len(data_bytes))
    }
    
    conn.request("POST", "/upload", data_bytes, headers)
    response = conn.getresponse()
    
    print(f"Status: {response.status} {response.reason}")
    print(f"Response: {response.read().decode()}")
    conn.close()

def test_binary_upload():
    """Test 3: Upload binary data (simulated image)"""
    print("\n=== Test 3: Upload binary data ===")
    
    # Create random binary data
    data = os.urandom(1024)  # 1KB random data
    
    conn = http.client.HTTPConnection("localhost", PORT)
    headers = {
        "Host": HOST,
        "Content-Type": "application/octet-stream",
        "Content-Length": str(len(data))
    }
    
    conn.request("POST", "/upload", data, headers)
    response = conn.getresponse()
    
    print(f"Status: {response.status} {response.reason}")
    print(f"Response: {response.read().decode()}")
    conn.close()

def test_chunked_upload():
    """Test 4: Upload with chunked transfer encoding"""
    print("\n=== Test 4: Chunked transfer encoding ===")
    
    conn = http.client.HTTPConnection("localhost", PORT)
    conn.putrequest("POST", "/upload")
    conn.putheader("Host", HOST)
    conn.putheader("Transfer-Encoding", "chunked")
    conn.endheaders()
    
    # Send data in chunks
    chunks = [b"Hello ", b"World", b"! This is ", b"chunked."]
    for chunk in chunks:
        conn.send(f"{len(chunk):X}\r\n".encode())
        conn.send(chunk)
        conn.send(b"\r\n")
    
    # Send final chunk
    conn.send(b"0\r\n\r\n")
    
    response = conn.getresponse()
    print(f"Status: {response.status} {response.reason}")
    print(f"Response: {response.read().decode()}")
    conn.close()

def test_form_data():
    """Test 5: Form data upload"""
    print("\n=== Test 5: Form data ===")
    
    data = "name=test&email=test@example.com&message=Hello+World"
    data_bytes = data.encode('utf-8')
    
    conn = http.client.HTTPConnection("localhost", PORT)
    headers = {
        "Host": HOST,
        "Content-Type": "application/x-www-form-urlencoded",
        "Content-Length": str(len(data_bytes))
    }
    
    conn.request("POST", "/upload", data_bytes, headers)
    response = conn.getresponse()
    
    print(f"Status: {response.status} {response.reason}")
    print(f"Response: {response.read().decode()}")
    conn.close()

def test_no_content_length():
    """Test 6: POST without Content-Length (should fail with 411)"""
    print("\n=== Test 6: POST without Content-Length (expect 411) ===")
    
    try:
        conn = http.client.HTTPConnection("localhost", PORT)
        conn.putrequest("POST", "/upload")
        conn.putheader("Host", HOST)
        conn.endheaders()
        conn.send(b"This should fail")
        
        response = conn.getresponse()
        print(f"Status: {response.status} {response.reason}")
        print(f"Response: {response.read().decode()}")
        conn.close()
    except Exception as e:
        print(f"Error (expected): {e}")

def test_get_with_body():
    """Test 7: GET with body (should fail with 400)"""
    print("\n=== Test 7: GET with body (expect 400) ===")
    
    data = b"This should not be allowed"
    
    conn = http.client.HTTPConnection("localhost", PORT)
    headers = {
        "Host": HOST,
        "Content-Length": str(len(data))
    }
    
    conn.request("GET", "/upload", data, headers)
    response = conn.getresponse()
    
    print(f"Status: {response.status} {response.reason}")
    print(f"Response: {response.read().decode()}")
    conn.close()

def test_large_upload():
    """Test 8: Upload large file"""
    print("\n=== Test 8: Large file upload (1MB) ===")
    
    data = os.urandom(1024 * 1024)  # 1MB
    
    conn = http.client.HTTPConnection("localhost", PORT)
    headers = {
        "Host": HOST,
        "Content-Type": "application/octet-stream",
        "Content-Length": str(len(data))
    }
    
    conn.request("POST", "/upload", data, headers)
    response = conn.getresponse()
    
    print(f"Status: {response.status} {response.reason}")
    print(f"Body length: {len(data)} bytes")
    conn.close()

def test_too_large_upload():
    """Test 9: Upload file larger than limit (should fail with 413)"""
    print("\n=== Test 9: Too large upload (expect 413) ===")
    
    # Config has clientMaxBody: 10485760 (10MB)
    # Try to upload 11MB
    data_size = 11 * 1024 * 1024
    
    conn = http.client.HTTPConnection("localhost", PORT)
    headers = {
        "Host": HOST,
        "Content-Type": "application/octet-stream",
        "Content-Length": str(data_size)
    }
    
    try:
        conn.request("POST", "/upload", b"x" * data_size, headers)
        response = conn.getresponse()
        print(f"Status: {response.status} {response.reason}")
        print(f"Response: {response.read().decode()}")
    except Exception as e:
        print(f"Error: {e}")
    finally:
        conn.close()

def test_pipeline_requests():
    """Test 10: Pipelined requests"""
    print("\n=== Test 10: Pipeline multiple requests ===")
    
    import socket
    
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.connect(("localhost", PORT))
    
    # First request
    request1 = (
        f"GET / HTTP/1.1\r\n"
        f"Host: {HOST}\r\n"
        f"Connection: keep-alive\r\n"
        f"\r\n"
    )
    
    # Second request
    data = b"Hello World"
    request2 = (
        f"POST /upload HTTP/1.1\r\n"
        f"Host: {HOST}\r\n"
        f"Content-Length: {len(data)}\r\n"
        f"Connection: close\r\n"
        f"\r\n"
    )
    
    sock.send(request1.encode())
    sock.send(request2.encode())
    sock.send(data)
    
    response = sock.recv(4096)
    print(f"Response:\n{response.decode()}")
    
    sock.close()

if __name__ == "__main__":
    print("=" * 50)
    print("HTTP Upload Tests")
    print("=" * 50)
    
    try:
        test_text_upload()
        test_json_upload()
        test_binary_upload()
        test_chunked_upload()
        test_form_data()
        test_no_content_length()
        test_get_with_body()
        test_large_upload()
        test_too_large_upload()
        test_pipeline_requests()
        
    except Exception as e:
        print(f"\nError during tests: {e}")
        import traceback
        traceback.print_exc()
    
    print("\n" + "=" * 50)
    print("All tests completed!")
    print("=" * 50)