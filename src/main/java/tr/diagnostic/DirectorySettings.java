package tr.diagnostic;

import java.util.*;

/** Editable settings only; connection passwords never enter this model. */
final class DirectorySettings {
    String host, installation, application, output;
    final Map<String,Object> options=new LinkedHashMap<>();
    final List<CollectorConfiguration.LogDirectory> logs=new ArrayList<>();
    static DirectorySettings from(CollectorConfiguration c) {
        DirectorySettings settings=new DirectorySettings();settings.host=c.remoteHost;
        settings.installation=c.installationPath;settings.application=c.applicationPath;
        settings.output=c.outputDirectory==null?"reports":c.outputDirectory.toString();
        for(CollectorConfiguration.LogDirectory source:c.logs)settings.logs.add(copy(source));
        return settings;
    }
    static CollectorConfiguration.LogDirectory copy(CollectorConfiguration.LogDirectory source) {
        CollectorConfiguration.LogDirectory copy=new CollectorConfiguration.LogDirectory();
        copy.sourcePath=source.sourcePath==null?(source.path==null?"":source.path.toString()):source.sourcePath;
        copy.base=source.base;copy.destination=source.destination;copy.recursive=source.recursive;
        copy.extensions=new ArrayList<>(source.extensions);copy.modifiedWithinHours=source.modifiedWithinHours;copy.maxFileSizeBytes=source.maxFileSizeBytes;
        return copy;
    }
    static void apply(CollectorConfiguration target,CollectorConfiguration saved) {
        String username=target.remoteUsername;
        try {
            for(java.lang.reflect.Field field:CollectorConfiguration.class.getFields())field.set(target,field.get(saved));
        } catch(IllegalAccessException impossible){throw new AssertionError(impossible);}
        target.remoteUsername=username;
        char[] password=saved.zipPasswordCopy();try{target.setZipPassword(new String(password));}finally{Arrays.fill(password,'\0');}
    }
}
