package tr.diagnostic;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Both modes use jdk-discovery.ps1 for inventory and compatibility ranking. */
public final class ToolResolver {
    public static final class Choice {
        public String tool;
        public Path executable;
        public String home, architecture, javaVersion, vendor;
    }
    private final CollectorConfiguration config;
    private final CommandExecutor executor;
    private JsonObject inventory;
    public ToolResolver(CollectorConfiguration config) { this(config, new ProcessCommandExecutor()); }
    ToolResolver(CollectorConfiguration config, CommandExecutor executor) { this.config=config; this.executor=executor; }
    void prepare(List<JavaProcessInfo> targets) throws IOException, InterruptedException {
        if(inventory!=null)return;
        Path folder=Files.createTempDirectory("diagnostic-jdk-");
        try {
            FileSupport.restrictDirectory(folder);
            List<String> roots=new ArrayList<>();for(Path root:config.jdkHomes)roots.add(root.toString());
            roots.add(System.getProperty("java.home"));
            Gson gson=new Gson();String data=gson.toJson(roots),processes=gson.toJson(targets);
            String setup="$jdkRoots=ConvertFrom-Json ([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('"+encoded(data)+"')));"
                +"$jdkTargets=ConvertFrom-Json ([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('"+encoded(processes)+"')));";
            String body="$ErrorActionPreference='Stop';$env:PSModulePath=[IO.Path]::Combine($PSHOME,'Modules');\r\n"+setup+PowerShell.resource("jdk-discovery.ps1")+"\r\nGet-JdkInventory $jdkRoots $jdkTargets|ConvertTo-Json -Depth 12 -Compress";
            Path script=folder.resolve("discover.ps1");Files.write(script,("\ufeff"+body).getBytes(StandardCharsets.UTF_8));
            CommandResult result=new PowerShell(executor).run("& '"+script.toString().replace("'","''")+"'",Math.min(15,config.scanTimeoutSeconds)*1000L);
            if(!result.success()||result.outputTruncated)throw new IOException(result.error()==null?"JDK discovery output unavailable":result.error());
            JsonObject parsed=JsonParser.parseString(result.stdout.trim()).getAsJsonObject();
            if(!parsed.has("candidates")||!parsed.has("plans"))throw new IOException("Invalid JDK discovery result");
            inventory=parsed;
        }catch(IOException|RuntimeException e){
            inventory=new JsonObject();inventory.add("candidates",new JsonArray());inventory.add("plans",new JsonArray());
            JsonArray notes=new JsonArray();notes.add("JDK_DISCOVERY_FAILED: "+e.getMessage());inventory.add("notes",notes);
        }finally{FileSupport.deleteTree(folder);}
    }
    public Optional<Path> find(String tool,JavaProcessInfo target)throws IOException,InterruptedException {
        List<Choice> choices=choices(Collections.singletonList(tool),target);
        return choices.isEmpty()?Optional.empty():Optional.of(choices.get(0).executable);
    }
    List<Choice> choices(List<String> tools,JavaProcessInfo target)throws IOException,InterruptedException {
        for(String tool:tools)if(!Arrays.asList("jcmd","jstack","jstat","jps").contains(tool))throw new IllegalArgumentException("Unsupported tool");
        prepare(target==null?Collections.emptyList():Collections.singletonList(target));
        JsonObject plan=null;
        for(JsonElement element:ResourceMonitor.array(inventory,"plans")){
            JsonObject row=element.getAsJsonObject();
            if(target==null?row.get("pid").getAsLong()==0:row.get("pid").getAsLong()==target.pid && Objects.equals(text(row,"startTicks"),target.startTicks)){plan=row;break;}
        }
        List<Choice> result=new ArrayList<>();if(plan==null)return result;
        if(target!=null)target.architecture=text(plan,"architecture");
        Set<String> seen=new HashSet<>();int count=0;
        for(JsonElement element:ResourceMonitor.array(plan,"candidates")){
            JsonObject ranked=element.getAsJsonObject();if(ranked.has("reason")&&!ranked.get("reason").isJsonNull())continue;
            boolean added=false;
            for(JsonElement entry:ResourceMonitor.array(inventory,"candidates")){
                JsonObject candidate=entry.getAsJsonObject();if(!Objects.equals(text(candidate,"home"),text(ranked,"home")))continue;
                JsonObject executables=candidate.getAsJsonObject("tools");
                for(String tool:tools){String file=text(executables,tool);if(file==null||!seen.add(file.toLowerCase(Locale.ROOT)))continue;
                    Choice choice=new Choice();choice.tool=tool;choice.executable=Paths.get(file);choice.home=text(candidate,"home");
                    choice.architecture=text(candidate,"architecture");choice.javaVersion=text(candidate,"javaVersion");choice.vendor=text(candidate,"vendor");result.add(choice);added=true;
                }
            }
            if(added&&++count>=3)break;
        }
        return result;
    }
    JsonObject inventory(){return inventory;}
    private static String text(JsonObject root,String key){JsonElement e=root==null?null:root.get(key);return e==null||e.isJsonNull()?null:e.getAsString();}
    private static String encoded(String value){return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));}
}
