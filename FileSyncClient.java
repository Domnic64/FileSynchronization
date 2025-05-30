// FileSyncClient.java
// A simple file synchronization client using Java Sockets and multithreading
// Connects to the server and syncs files from a local directory

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.*;

public class FileSyncClient {
    private static final String SERVER_HOST = "localhost";
    private static final int SERVER_PORT = 5000;
    private static final String SYNC_DIR = "client_sync_dir";
    private static final int LISTEN_PORT = 6000; // Port to listen for server notifications

    // Keep track of files just received from the other side to avoid sync loops
    private static final Set<String> recentlySyncedFiles = Collections.synchronizedSet(new HashSet<>());

    public static void main(String[] args) throws IOException, InterruptedException {
        Files.createDirectories(Paths.get(SYNC_DIR));
        // Start listener for server notifications
        new Thread(() -> listenForServerNotifications()).start();
        WatchService watchService = FileSystems.getDefault().newWatchService();
        Path dir = Paths.get(SYNC_DIR);
        dir.register(watchService, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE);
        System.out.println("Watching directory for changes: " + SYNC_DIR);
        while (true) {
            WatchKey key = watchService.take();
            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();
                Path fileName = (Path) event.context();
                File file = new File(SYNC_DIR, fileName.toString());
                if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
                    if (recentlySyncedFiles.remove(fileName.toString())) continue; // Ignore if just synced from server
                    while (!file.exists() || file.length() == 0) {
                        Thread.sleep(100);
                    }
                    sendFileToServer(file);
                } else if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
                    if (recentlySyncedFiles.remove(fileName.toString())) continue; // Ignore if just deleted from server
                    notifyServerDelete(fileName.toString());
                }
            }
            key.reset();
        }
    }

    private static void sendFileToServer(File file) {
        try (Socket socket = new Socket(SERVER_HOST, SERVER_PORT);
             DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
             DataInputStream dis = new DataInputStream(socket.getInputStream());
             FileInputStream fis = new FileInputStream(file)) {
            dos.writeUTF("SYNC");
            dos.writeInt(1); // Only one file
            dos.writeUTF(file.getName());
            dos.writeLong(file.length());
            byte[] buffer = new byte[4096];
            int read;
            while ((read = fis.read(buffer)) != -1) {
                dos.write(buffer, 0, read);
            }
            String response = dis.readUTF();
            System.out.println(response);
        } catch (IOException e) {
            System.err.println("Failed to sync file: " + file.getName());
            e.printStackTrace();
        }
    }

    private static void notifyServerDelete(String fileName) {
        System.out.println("Requesting server to delete: " + fileName);
        try (Socket socket = new Socket(SERVER_HOST, SERVER_PORT);
             DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
             DataInputStream dis = new DataInputStream(socket.getInputStream())) {
            dos.writeUTF("DELETE");
            dos.writeUTF(fileName);
            String response = dis.readUTF();
            System.out.println("Server response: " + response);
        } catch (IOException e) {
            System.err.println("Failed to notify server to delete file: " + fileName);
            e.printStackTrace();
        }
    }

    private static void listenForServerNotifications() {
        try (ServerSocket serverSocket = new ServerSocket(LISTEN_PORT)) {
            System.out.println("Client listening for server notifications on port " + LISTEN_PORT);
            while (true) {
                try (Socket socket = serverSocket.accept();
                     DataInputStream dis = new DataInputStream(socket.getInputStream())) {
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
                            System.out.println("Received file from server: " + fileName);
                        }
                    } else if (command.equals("DELETE")) {
                        String fileName = dis.readUTF();
                        File fileToDelete = new File(SYNC_DIR, fileName);
                        boolean deleted = fileToDelete.delete();
                        recentlySyncedFiles.add(fileName); // Mark as just deleted
                        System.out.println("Server requested delete: " + fileName + ", deleted: " + deleted);
                    }
                } catch (IOException e) {
                    System.err.println("Error in server notification handler: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to start client notification listener: " + e.getMessage());
        }
    }

    private static List<File> getFilesToSync() {
        File dir = new File(SYNC_DIR);
        File[] files = dir.listFiles((d, name) -> new File(d, name).isFile());
        if (files == null) return Collections.emptyList();
        return Arrays.asList(files);
    }
}
