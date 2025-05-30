// README.md
# File Synchronization System

## Overview
This project implements a simple file synchronization system in Java. It consists of a server and a client. The client sends files from its local directory to the server, ensuring both devices have the same data.

## Concepts Covered
- File I/O
- Multithreading
- Networking (Sockets)

## How It Works
- The server (`FileSyncServer.java`) listens for incoming connections and receives files from clients.
- The client (`FileSyncClient.java`) connects to the server and uploads all files from its `client_sync_dir` directory.
- Both server and client use Java Sockets and streams for file transfer.

## Usage
1. **Create Directories:**
   - On the server: `server_sync_dir` (created automatically)
   - On the client: `client_sync_dir` (created automatically)
2. **Add files to `client_sync_dir`** on the client side.
3. **Run the server:**
   ```powershell
   javac FileSyncServer.java
   java FileSyncServer
   ```
4. **Run the client:**
   ```powershell
   javac FileSyncClient.java
   java FileSyncClient
   ```
5. **Check `server_sync_dir`** for received files.

## Notes
- The system can be extended to support bidirectional sync, file deletion, or conflict resolution.
- For demonstration, both server and client can run on `localhost`.
