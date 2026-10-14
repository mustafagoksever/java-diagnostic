package tr.diagnostic;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.FileHeader;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class ZipEncryptionServiceTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Test public void encryptsEveryEntryAndRejectsWrongPasswordDigestAndCorruptPayload() throws Exception {
        Path root = temp.getRoot().toPath(), source = Files.createDirectory(root.resolve("source"));
        Files.write(source.resolve("Manifest.json"), "{\"fixture\":true}".getBytes(StandardCharsets.UTF_8));
        Path nested = Files.createDirectory(source.resolve("nested"));
        byte[] payload = new byte[65536]; new Random(1).nextBytes(payload); Files.write(nested.resolve("app.log"), payload);
        Map<String, String> hashes = new LinkedHashMap<>(); hashes.put("Manifest.json", FileSupport.sha256(source.resolve("Manifest.json"))); hashes.put("nested/app.log", FileSupport.sha256(nested.resolve("app.log")));
        Path archive = root.resolve("report.zip"); char[] password = ZipEncryptionService.generatePassword();
        ZipEncryptionService service = new ZipEncryptionService(); service.createAndValidate(source, archive, password);
        service.validate(archive, password, hashes);
        expectInvalid(service, archive, "wrongpassword123".toCharArray(), hashes);
        Map<String, String> wrongHashes = new LinkedHashMap<>(hashes); wrongHashes.put("nested/app.log", "wrong"); expectInvalid(service, archive, password, wrongHashes);
        long offset;
        try (ZipFile zip = new ZipFile(archive.toFile(), password)) {
            FileHeader h = zip.getFileHeader("nested/app.log"); Assert.assertTrue(h.isEncrypted()); offset = h.getOffsetLocalHeader();
        }
        try (RandomAccessFile file = new RandomAccessFile(archive.toFile(), "rw")) {
            file.seek(offset + 26); int nameSize = Short.toUnsignedInt(Short.reverseBytes(file.readShort())); int extraSize = Short.toUnsignedInt(Short.reverseBytes(file.readShort()));
            long encryptedPayload = offset + 30 + nameSize + extraSize + 16 + 2 + 100; file.seek(encryptedPayload); int value = file.read(); file.seek(encryptedPayload); file.write(value ^ 1);
        }
        expectInvalid(service, archive, password, hashes); Arrays.fill(password, '\0');
    }
    @Test public void failedCreationDoesNotPublishOrLeavePartialArchive() throws Exception {
        Path root = temp.getRoot().toPath(); Path empty = Files.createDirectory(root.resolve("empty")); Path zip = root.resolve("invalid.zip");
        try { new ZipEncryptionService().createAndValidate(empty, zip, "validPassword12".toCharArray()); Assert.fail(); } catch (IOException expected) { }
        Assert.assertFalse(Files.exists(zip));
        try (java.util.stream.Stream<Path> files = Files.list(root)) { Assert.assertEquals(1, files.count()); }
    }
    @Test public void passwordGenerationHasExpectedFormatAndIndependentValues() {
        char[] first = ZipEncryptionService.generatePassword(), second = ZipEncryptionService.generatePassword();
        Assert.assertTrue(new String(first).matches("[A-Za-z0-9_-]{32}")); Assert.assertFalse(Arrays.equals(first, second));
        Arrays.fill(first, '\0'); Arrays.fill(second, '\0');
    }
    private void expectInvalid(ZipEncryptionService service, Path zip, char[] password, Map<String, String> expected) throws Exception {
        try { service.validate(zip, password, expected); Assert.fail("Invalid archive accepted"); } catch (IOException failure) { }
    }
}
