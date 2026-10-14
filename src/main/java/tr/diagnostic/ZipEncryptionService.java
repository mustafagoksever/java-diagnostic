package tr.diagnostic;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.*;
import net.lingala.zip4j.model.enums.*;
import java.io.*;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;

public final class ZipEncryptionService {
    public static char[] generatePassword() {
        // 32 uniformly selected symbols from 64 symbols = 192 bits of entropy.
        char[] alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();
        SecureRandom random = new SecureRandom(); char[] result = new char[32];
        for (int i = 0; i < result.length; i++) result[i] = alphabet[random.nextInt(alphabet.length)];
        return result;
    }
    public void createAndValidate(Path source, Path destination, char[] password) throws IOException {
        if (password == null || password.length < 12) throw new IOException("ZIP parolası en az 12 karakter olmalı.");
        if (Files.exists(destination)) throw new FileAlreadyExistsException(destination.toString());
        Path partial = Files.createTempFile(destination.getParent(), ".diagnostic-", ".partial");
        try {
            Files.delete(partial); // Zip4j needs a non-existing ZIP, randomized path prevents collisions.
            Map<String, String> expected = new LinkedHashMap<>();
            List<Path> files = new ArrayList<>();
            try (java.util.stream.Stream<Path> stream = Files.walk(source)) {
                stream.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).sorted().forEach(files::add);
            }
            if (files.isEmpty()) throw new IOException("Boş rapor arşivlenemez.");
            try (ZipFile zip = new ZipFile(partial.toFile(), password)) {
                for (Path file : files) {
                    String entry = DiagnosticReportGenerator.relative(source, file);
                    expected.put(entry, FileSupport.sha256(file));
                    ZipParameters params = new ZipParameters();
                    params.setEncryptFiles(true); params.setEncryptionMethod(EncryptionMethod.AES);
                    params.setAesKeyStrength(AesKeyStrength.KEY_STRENGTH_256);
                    params.setCompressionMethod(CompressionMethod.DEFLATE);
                    params.setFileNameInZip(entry);
                    zip.addFile(file.toFile(), params);
                }
            }
            validate(partial, password, expected);
            // Do not replace an existing report. Same-volume rename publishes only validated archives.
            Files.move(partial, destination);
        } finally { Files.deleteIfExists(partial); }
    }
    public void validate(Path archive, char[] password, Map<String, String> expected) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile(), password)) {
            if (!zip.isValidZipFile()) throw new IOException("ZIP başlık doğrulaması başarısız.");
            Set<String> seen = new HashSet<>();
            for (FileHeader h : zip.getFileHeaders()) {
                if (h.isDirectory()) continue;
                if (!h.isEncrypted() || h.getEncryptionMethod() != EncryptionMethod.AES || h.getAesExtraDataRecord() == null || h.getAesExtraDataRecord().getAesKeyStrength() != AesKeyStrength.KEY_STRENGTH_256) throw new IOException("AES-256 olmayan ZIP girdisi.");
                String name = h.getFileName();
                if (!seen.add(name) || !expected.containsKey(name)) throw new IOException("Beklenmeyen/tekrar eden ZIP girdisi: " + name);
                try (InputStream input = zip.getInputStream(h)) {
                    // Read through EOF: Zip4j verifies the AES authentication tag.
                    if (!expected.get(name).equals(FileSupport.digest(input))) throw new IOException("ZIP SHA-256 uyuşmazlığı: " + name);
                }
            }
            if (!seen.equals(expected.keySet())) throw new IOException("ZIP dosya listesi eksik.");
        }
    }
}
