package tr.diagnostic;

import org.yaml.snakeyaml.*;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** A writable YAML beside the JAR, or the explicitly selected --config file. */
final class ConfigurationFile {
    private final Path path;
    private final boolean explicit;
    ConfigurationFile() { this(defaultPath(),false); }
    ConfigurationFile(Path path) { this(path,true); }
    ConfigurationFile(Path path,boolean explicit) { this.path=path.toAbsolutePath().normalize();this.explicit=explicit; }
    Path path() { return path; }
    boolean exists() { return Files.exists(path); }
    CollectorConfiguration load() throws IOException {
        ConfigurationManager manager=new ConfigurationManager();
        return explicit||exists()?manager.load(path):manager.loadEmbedded(path.getParent());
    }
    static Path defaultPath() {
        try {
            Path code=Paths.get(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if(Files.isRegularFile(code))return code.getParent().resolve("config.yaml");
        } catch(Exception ignored) { }
        return Paths.get("config.yaml").toAbsolutePath().normalize();
    }
    CollectorConfiguration save(DirectorySettings settings,boolean local) throws IOException {
        String host=settings.host.trim();
        boolean savingLocal=settings.options.containsKey("mode")?"local".equals(settings.options.get("mode")):local;
        if(!savingLocal||!host.isEmpty())ServerEndpointSettings.validate(host);
        if(settings.output.trim().isEmpty())throw new IOException("Çıktı klasörü boş olamaz.");
        Map<String,Object> root=readDocument();
        for(Map.Entry<String,Object> option:settings.options.entrySet())setOption(root,option.getKey(),option.getValue());
        @SuppressWarnings("unchecked") Map<String,Object> remote=(Map<String,Object>)root.get("remote");
        if(remote==null){remote=new LinkedHashMap<>();root.put("remote",remote);}
        putOptional(remote,"host",host);putOptional(root,"installationDirectory",settings.installation.trim());
        putOptional(root,"applicationDirectory",settings.application.trim());root.put("outputDirectory",settings.output.trim());
        List<Map<String,Object>> logs=new ArrayList<>();
        for(CollectorConfiguration.LogDirectory source:settings.logs) {
            if(source.sourcePath==null||source.sourcePath.trim().isEmpty())throw new IOException("Kopyalanacak kaynak dizin boş olamaz.");
            Map<String,Object> log=new LinkedHashMap<>();log.put("path",source.sourcePath.trim());log.put("base",source.base);
            if(source.destination!=null&&!source.destination.trim().isEmpty())log.put("destination",source.destination.trim());
            log.put("recursive",source.recursive);log.put("extensions",new ArrayList<>(source.extensions));
            log.put("modifiedWithinHours",source.modifiedWithinHours);log.put("maxFileSizeMb",source.maxFileSizeBytes/(1024*1024));logs.add(log);
        }
        root.put("logs",logs);
        DumperOptions options=new DumperOptions();options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);options.setAllowUnicode(true);options.setIndent(2);
        String yaml=new Yaml(options).dump(root);
        Path parent=path.getParent();Files.createDirectories(parent);
        Path candidate=Files.createTempFile(parent,".diagnostic-config-",".yaml");
        try {
            Files.write(candidate,yaml.getBytes(StandardCharsets.UTF_8));
            CollectorConfiguration validated=new ConfigurationManager().load(candidate);
            boolean resultingLocal=settings.options.containsKey("mode")?"local".equals(validated.mode):local;
            if(!resultingLocal)ConfigurationManager.validateRemoteSources(validated);
            try{Files.move(candidate,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException unsupported){Files.move(candidate,path,StandardCopyOption.REPLACE_EXISTING);}
            return validated;
        } finally {Files.deleteIfExists(candidate);}
    }
    @SuppressWarnings("unchecked") private Map<String,Object> readDocument() throws IOException {
        LoaderOptions options=new LoaderOptions();options.setAllowDuplicateKeys(false);options.setMaxAliasesForCollections(0);options.setCodePointLimit(1024*1024);options.setNestingDepthLimit(20);
        try(Reader reader=exists()?Files.newBufferedReader(path,StandardCharsets.UTF_8):embeddedReader()) {
            Object value=new Yaml(new SafeConstructor(options)).load(reader);
            if(!(value instanceof Map))throw new IOException("Konfigürasyon bir YAML alan listesi olmalı.");
            return (Map<String,Object>)value;
        } catch(RuntimeException e){throw new IOException("Konfigürasyon okunamadı; mevcut dosya değiştirilmedi.");}
    }
    private static Reader embeddedReader() throws IOException {
        InputStream input=ConfigurationFile.class.getResourceAsStream("/internal/config.yaml");
        if(input==null)throw new IOException("Gömülü konfigürasyon bulunamadı.");
        return new InputStreamReader(input,StandardCharsets.UTF_8);
    }
    private static void putOptional(Map<String,Object> map,String key,String value) {if(value.isEmpty())map.remove(key);else map.put(key,value);}
    @SuppressWarnings("unchecked") private static void setOption(Map<String,Object> root,String key,Object value) {
        String[] parts=key.split("\\.");Map<String,Object> current=root;
        for(int i=0;i<parts.length-1;i++){Object child=current.get(parts[i]);if(!(child instanceof Map)){child=new LinkedHashMap<String,Object>();current.put(parts[i],child);}current=(Map<String,Object>)child;}
        if(value==null)current.remove(parts[parts.length-1]);else current.put(parts[parts.length-1],value);
    }
}
