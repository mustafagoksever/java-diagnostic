package tr.diagnostic;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.util.*;

public final class FileSupport {
    private FileSupport() { }
    public static String safeName(String name) {
        String value = (name == null ? "java" : name).replaceAll("[^A-Za-z0-9._-]", "_");
        if (value.length() > 80) value = value.substring(value.length() - 80);
        return "jvm-" + value;
    }
    public static String sha256(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) { return digest(input); }
    }
    public static String digest(InputStream input) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536]; int n;
            while ((n = input.read(buffer)) != -1) digest.update(buffer, 0, n);
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    /** Only caller-owned staging paths may be passed here. Does not follow links. */
    public static void deleteTree(Path directory) throws IOException {
        Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException { Files.delete(file); return FileVisitResult.CONTINUE; }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException { if (e != null) throw e; Files.delete(dir); return FileVisitResult.CONTINUE; }
        });
    }
    public static void restrictDirectory(Path directory) throws IOException {
        AclFileAttributeView view = Files.getFileAttributeView(directory, AclFileAttributeView.class);
        if (view != null) {
            UserPrincipal owner = Files.getOwner(directory);
            AclEntry entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                .setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT).build();
            view.setAcl(Collections.singletonList(entry));
        } else {
            PosixFileAttributeView posix = Files.getFileAttributeView(directory, PosixFileAttributeView.class);
            if (posix != null) posix.setPermissions(PosixFilePermissions.fromString("rwx------"));
        }
    }
}
