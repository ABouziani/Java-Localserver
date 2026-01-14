# Java HTTP/1.1 Server

## Overview

This project aims to provide a deep understanding of how the Internet works from the **server side** by implementing a custom **HTTP/1.1-compliant web server in Java**.

The Hypertext Transfer Protocol (HTTP) is the backbone of the World Wide Web, enabling reliable communication between clients (such as browsers) and servers through a request/response model. In this project, you will build a lightweight, extensible, and crash-proof HTTP server **from scratch**, without relying on existing server frameworks.

---

## Role Play

You are a **backend engineer** at a startup tasked with building a lightweight web server to handle:

- Internal APIs
- Static content delivery
- File uploads
- CGI-based dynamic content

The server must be **highly available**, **non-blocking**, and **configurable**, while remaining simple and dependency-free.

---

## Learning Objectives

By completing this project, you will be able to:

- Design and implement an **HTTP/1.1-compliant server**
- Use **non-blocking I/O** with Java NIO
- Manually parse and construct HTTP requests and responses
- Configure routing, error handling, uploads, and CGI execution
- Evaluate performance under stress and ensure stability
- Prevent crashes, memory leaks, and resource exhaustion

---

## Technical Skills Covered

- Socket programming
- Asynchronous and event-driven I/O
- File system management
- Process execution and management
- Configuration parsing
- HTTP protocol internals

---

## Constraints & Technologies

- **Language:** Java
- **Allowed libraries:**
  - `java.nio` (non-blocking I/O)
  - `java.net` (networking)
- **Forbidden:**
  - Server frameworks (Netty, Jetty, Grizzly, etc.)
  - Asynchronous runtimes
- **Architecture:**
  - Single process
  - Single thread
  - Event-driven design

---

## Server Requirements

Your HTTP server must:

- Never crash
- Timeout long or stalled requests
- Listen on **multiple ports**
- Support **multiple server instances**
- Use **HTTP/1.1**
- Handle the following HTTP methods:
  - `GET`
  - `POST`
  - `DELETE`
- Receive and store file uploads
- Handle cookies and sessions
- Support both **chunked** and **unchunked** requests
- Return correct HTTP status codes
- Provide default error pages for:
  - `400 Bad Request`
  - `403 Forbidden`
  - `404 Not Found`
  - `405 Method Not Allowed`
  - `413 Payload Too Large`
  - `500 Internal Server Error`

---

## CGI Support

- Execute **one CGI type** (e.g. `.py`)
- Use `ProcessBuilder`
- Pass the script path as the first argument
- Use the `PATH_INFO` environment variable
- Handle relative and absolute paths correctly
- Sanitize all inputs

---

## Configuration File

The server must be fully configurable via a configuration file.

Supported configuration options include:

### Global Configuration
- Host
- Multiple ports
- Default server
- Client body size limit
- Custom error page paths

### Routes Configuration
- Accepted HTTP methods
- Redirections
- Root directories (file or directory)
- Default index file for directories
- CGI activation by file extension
- Directory listing enable/disable
- Default response file for directories

> **Note:** Regex support is **not required**.

---