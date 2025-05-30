// FileSyncServer.java
// A simple file synchronization server using Java Sockets and multithreading
// Listens for client connections and syncs files from a shared directory

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class FileSyncServer {
    private static final int PORT = 5000;
    private static final String SYNC_DIR = "server_sync_dir";
    private static final int THREAD_POOL_SIZE = 4;
    private static final String CLIENT_HOST = "localhost"; // Change if client is remote
    private static final int CLIENT_PORT = 6000; // Port client will listen on for server notifications

    // Keep track of files just received from the other side to avoid sync loops
    private static final Set<String> recentlySyncedFiles = Collections.synchronizedSet(new HashSet<>());

    public static void main(String[] args) throws IOException {
        Files.createDirectories(Paths.get(SYNC_DIR));
        ExecutorService pool = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        // Start file watcher thread
        new Thread(() -> watchAndNotifyClient()).start();
        ServerSocket serverSocket = new ServerSocket(PORT);
        System.out.println("FileSyncServer started on port " + PORT);
        while (true) {
            Socket clientSocket = serverSocket.accept();
            pool.execute(new ClientHandler(clientSocket));
        }
    }

    private static void watchAndNotifyClient() {
        try {
            WatchService watchService = FileSystems.getDefault().newWatchService();
            Path dir = Paths.get(SYNC_DIR);
            dir.register(watchService, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE, StandardWatchEventKinds.ENTRY_MODIFY);
            System.out.println("Server watching for changes in: " + SYNC_DIR);
            while (true) {
                WatchKey key = watchService.take();
                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();
                    Path fileName = (Path) event.context();
                    File file = new File(SYNC_DIR, fileName.toString());
                    if (kind == StandardWatchEventKinds.ENTRY_CREATE || kind == StandardWatchEventKinds.ENTRY_MODIFY) {
                        if (recentlySyncedFiles.remove(fileName.toString())) continue; // Ignore if just synced from client
                        // Wait until file is fully written
                        while (!file.exists() || file.length() == 0) {
                            Thread.sleep(100);
                        }
                        sendFileToClient(file);
                    } else if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
                        if (recentlySyncedFiles.remove(fileName.toString())) continue; // Ignore if just deleted from client
                        notifyClientDelete(fileName.toString());
                    }
                }
                key.reset();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void sendFileToClient(File file) {
        try (Socket socket = new Socket(CLIENT_HOST, CLIENT_PORT);
             DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
             FileInputStream fis = new FileInputStream(file)) {
            dos.writeUTF("SYNC");
            dos.writeInt(1);
            dos.writeUTF(file.getName());
            dos.writeLong(file.length());
            byte[] buffer = new byte[4096];
            int read;
            while ((read = fis.read(buffer)) != -1) {
                dos.write(buffer, 0, read);
            }
            System.out.println("Notified client to sync file: " + file.getName());
        } catch (IOException e) {
            System.err.println("Failed to notify client to sync file: " + file.getName());
        }
    }

    private static void notifyClientDelete(String fileName) {
        try (Socket socket = new Socket(CLIENT_HOST, CLIENT_PORT);
             DataOutputStream dos = new DataOutputStream(socket.getOutputStream())) {
            dos.writeUTF("DELETE");
            dos.writeUTF(fileName);
            System.out.println("Notified client to delete file: " + fileName);
        } catch (IOException e) {
            System.err.println("Failed to notify client to delete file: " + fileName);
        }
    }

    static class ClientHandler implements Runnable {
        private Socket socket;
        public ClientHandler(Socket socket) {
            this.socket = socket;
        }
        public void run() {
            try (DataInputStream dis = new DataInputStream(socket.getInputStream());
                 DataOutputStream dos = new DataOutputStream(socket.getOutputStream())) {
                String command = dis.readUTF();
                if (command.equals("SYNC")) {
                    int fileCount = dis.readInt();
                    for (int i = 0; i < fileCount; i++) {
                        String fileName = dis.readUTF();
                        long fileSize = dis.readLong();
                        File outFile = new File(SYNC_DIR, fileName);
                        try (FileOutputStream fos = new FileOutputStream(outFile)) {
                            byte[] buffer = new byte[4096];
                            long bytesRead = 0;
                            while (bytesRead < fileSize) {
                                int read = dis.read(buffer, 0, (int)Math.min(buffer.length, fileSize - bytesRead));
                                if (read == -1) break;
                                fos.write(buffer, 0, read);
                                bytesRead += read;
                            }
                        }
                        recentlySyncedFiles.add(fileName); // Mark as just synced
                        dos.writeUTF("RECEIVED:" + fileName);
                    }
                } else if (command.equals("DELETE")) {
                    String fileName = dis.readUTF();
                    File fileToDelete = new File(SYNC_DIR, fileName);
                    boolean deleted = fileToDelete.delete();
                    recentlySyncedFiles.add(fileName); // Mark as just deleted
                    System.out.println("Received DELETE request for: " + fileName + ", exists: " + fileToDelete.exists());
                    System.out.println("Delete result for " + fileName + ": " + deleted);
                    dos.writeUTF(deleted ? "DELETED:" + fileName : "NOT_FOUND:" + fileName);
                }
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                try { socket.close(); } catch (IOException e) { }
            }
        }
    }
}
