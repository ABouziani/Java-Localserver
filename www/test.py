#!/usr/bin/env python3

import os
import sys
import urllib.parse

# CGI headers (REQUIRED)
print("Content-Type: text/html")
print()  # End of headers

print("<html><body>")
print("<h1>CGI Test OK</h1>")

# Request method
method = os.environ.get("REQUEST_METHOD", "UNKNOWN")
print(f"<p><b>Method:</b> {method}</p>")

# Query string (GET)
query = os.environ.get("QUERY_STRING", "")
print(f"<p><b>Query string:</b> {query}</p>")

if query:
    params = urllib.parse.parse_qs(query)
    print("<h3>Parsed GET parameters</h3><ul>")
    for k, v in params.items():
        print(f"<li>{k} = {v}</li>")
    print("</ul>")

# POST data
if method == "POST":
    length = int(os.environ.get("CONTENT_LENGTH", 0))
    body = sys.stdin.read(length) if length > 0 else ""
    print("<h3>POST body</h3>")
    print(f"<pre>{body}</pre>")

# Environment dump (useful for debugging your server)
print("<h3>CGI Environment</h3><pre>")
for k in sorted(os.environ):
    print(f"{k}={os.environ[k]}")
print("</pre>")

print("</body></html>")
print("55555555a"*100000000)
