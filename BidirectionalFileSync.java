// BidirectionalFileSync.java
// A Java-based bidirectional file synchronization system for two folders (deviceA_dir and deviceB_dir)
// Features: real-time sync, file add/delete/modify detection, conflict resolution, logging

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

public class BidirectionalFileSync {
    private static final String DIR_A = "deviceA_dir";
    private static final String DIR_B = "deviceB_dir";
    private static final int SYNC_INTERVAL_MS = 2000; // Interval for polling (ms)
    private static final String LOG_FILE = "sync.log";

    private static final Map<String, FileMeta> stateA = new ConcurrentHashMap<>();
    private static final Map<String, FileMeta> stateB = new ConcurrentHashMap<>();

    public static void main(String[] args) throws Exception {
        Files.createDirectories(Paths.get(DIR_A));
        Files.createDirectories(Paths.get(DIR_B));
        log("Starting Bidirectional File Sync");
        // Initial scan
        scanDirectory(DIR_A, stateA);
        scanDirectory(DIR_B, stateB);
        // Start sync threads
        ExecutorService pool = Executors.newFixedThreadPool(2);
        pool.submit(() -> watchAndSync(DIR_A, DIR_B, stateA, stateB));
        pool.submit(() -> watchAndSync(DIR_B, DIR_A, stateB, stateA));
    }

    private static void watchAndSync(String srcDir, String dstDir, Map<String, FileMeta> srcState, Map<String, FileMeta> dstState) {
        while (true) {
            try {
                Map<String, FileMeta> newState = scanDirectory(srcDir, null);
                // Check for new or modified files
                for (String relPath : newState.keySet()) {
                    FileMeta newMeta = newState.get(relPath);
                    FileMeta oldMeta = dstState.get(relPath);
                    if (oldMeta == null) {
                        // New file, copy to dst
                        copyFile(srcDir, dstDir, relPath);
                        log("Copied new file " + relPath + " from " + srcDir + " to " + dstDir);
                    } else if (!newMeta.equals(oldMeta)) {
                        // Modified file, resolve conflict
                        if (newMeta.lastModified > oldMeta.lastModified) {
                            copyFile(srcDir, dstDir, relPath);
                            log("Updated file " + relPath + " from " + srcDir + " to " + dstDir);
                        } else if (oldMeta.lastModified > newMeta.lastModified) {
                            copyFile(dstDir, srcDir, relPath);
                            log("Updated file " + relPath + " from " + dstDir + " to " + srcDir);
                        }
                    }
                }
                // Check for deleted files
                for (String relPath : new HashSet<>(dstState.keySet())) {
                    if (!newState.containsKey(relPath)) {
                        deleteFile(dstDir, relPath);
                        log("Deleted file " + relPath + " from " + dstDir + " (deleted in " + srcDir + ")");
                    }
                }
                // Update state
                srcState.clear();
                srcState.putAll(newState);
                dstState.clear();
                scanDirectory(dstDir, dstState);
                Thread.sleep(SYNC_INTERVAL_MS);
            } catch (Exception e) {
                log("Error: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    private static Map<String, FileMeta> scanDirectory(String dir, Map<String, FileMeta> state) throws IOException {
        Map<String, FileMeta> result = (state == null) ? new HashMap<>() : state;
        result.clear();
        Path base = Paths.get(dir);
        Files.walkFileTree(base, new SimpleFileVisitor<Path>() {
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String relPath = base.relativize(file).toString();
                result.put(relPath, new FileMeta(attrs.lastModifiedTime().toMillis(), checksum(file)));
                return FileVisitResult.CONTINUE;
            }
        });
        return result;
    }

    private static void copyFile(String srcDir, String dstDir, String relPath) throws IOException {
        Path src = Paths.get(srcDir, relPath);
        Path dst = Paths.get(dstDir, relPath);
        Files.createDirectories(dst.getParent());
        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
    }

    private static void deleteFile(String dir, String relPath) throws IOException {
        Path file = Paths.get(dir, relPath);
        Files.deleteIfExists(file);
    }

    private static String checksum(Path file) {
        try (InputStream fis = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[4096];
            int read;
            while ((read = fis.read(buffer)) != -1) {
                md.update(buffer, 0, read);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static void log(String msg) {
        System.out.println(msg);
        try (FileWriter fw = new FileWriter(LOG_FILE, true)) {
            fw.write(new Date() + ": " + msg + "\n");
        } catch (IOException e) {}
    }

    private static class FileMeta {
        long lastModified;
        String checksum;
        FileMeta(long lm, String cs) { lastModified = lm; checksum = cs; }
        public boolean equals(Object o) {
            if (!(o instanceof FileMeta)) return false;
            FileMeta f = (FileMeta)o;
            return lastModified == f.lastModified && Objects.equals(checksum, f.checksum);
        }
        public int hashCode() { return Objects.hash(lastModified, checksum); }
    }
}
