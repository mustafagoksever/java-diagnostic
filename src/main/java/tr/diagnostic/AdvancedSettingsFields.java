package tr.diagnostic;

import javax.swing.*;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.function.Supplier;

/** Typed controls for every non-directory YAML setting. */
final class AdvancedSettingsFields {
    private final Map<String,Supplier<Object>> values=new LinkedHashMap<>();
    private final List<JComponent> controls=new ArrayList<>();
    private final JPasswordField zipPassword=new JPasswordField(24);
    private final JCheckBox clearPassword=new JCheckBox("Kayıtlı ZIP parolasını kaldır");
    private final JComboBox<String> mode=new JComboBox<>(new String[]{"remote","local"});
    AdvancedSettingsFields(JTabbedPane tabs,CollectorConfiguration c,boolean local) {
        JPanel general=panel();
        text(general,"applicationTitle","Pencere başlığı",c.applicationTitle);
        mode.setSelectedItem(local?"local":"remote");register(general,"mode","Çalışma modu",mode,mode::getSelectedItem);
        text(general,"remote.jdkHome","Uzak JDK yolu (auto: otomatik)",c.remoteJdkHome);
        text(general,"remote.workShare","Uzak çalışma paylaşımı (auto: otomatik)",c.remoteWorkShare);
        list(general,"jdkHomes","Yerel JDK dizinleri (her satıra bir yol)",paths(c.jdkHomes));
        list(general,"additionalProcessNames","Ek süreç adları (her satıra bir .exe)",c.additionalProcessNames);
        bool(general,"collectGcStats","GC istatistiklerini topla",c.collectGcStats);
        bool(general,"remote.windowsDiagnostics","Ayrıntılı Windows tanılamasını topla",c.remoteWindowsDiagnostics);
        zipPassword.setName("setting.zipPassword");clearPassword.setName("setting.clearZipPassword");
        SettingsHelp.install(zipPassword,"zipPassword");SettingsHelp.install(clearPassword,"clearZipPassword");
        field(general,"Yeni ZIP parolası (boşsa mevcut korunur)",zipPassword);field(general,"",clearPassword);controls.add(zipPassword);controls.add(clearPassword);
        clearPassword.addActionListener(event->{zipPassword.setEnabled(!clearPassword.isSelected());if(clearPassword.isSelected())zipPassword.setText("");});
        tab(tabs,"Genel ve JDK",general);

        JPanel timing=panel();
        number(timing,"collectionDelaySeconds","Toplama öncesi bekleme (sn)",c.collectionDelaySeconds,0,300);
        number(timing,"resourceMonitoringSeconds","CPU/RAM izleme süresi (sn; 0: kapalı)",c.resourceMonitoringSeconds,0,300);
        number(timing,"resourceSampleIntervalSeconds","Kaynak örnekleme aralığı (sn)",c.resourceSampleIntervalSeconds,1,10);
        number(timing,"commandTimeoutSeconds","Komut zaman aşımı (sn)",c.commandTimeoutSeconds,1,300);
        number(timing,"scanTimeoutSeconds","Süreç keşif zaman aşımı (sn)",c.scanTimeoutSeconds,1,600);
        number(timing,"remote.timeoutSeconds","Uzak toplama / yerel ek tanılama limiti (sn)",c.remoteTimeoutSeconds,60,7200);
        text(timing,"remote.beforeLogCopyBatch","Log hazırlama BAT yolu (boş: kapalı)",c.remoteBeforeLogCopyBatch);
        number(timing,"remote.beforeLogCopyTimeoutSeconds","BAT zaman aşımı (sn)",c.remoteBeforeLogCopyTimeoutSeconds,1,600);
        number(timing,"remote.beforeLogCopyDelaySeconds","BAT sonrası bekleme (sn)",c.remoteBeforeLogCopyDelaySeconds,0,300);
        tab(tabs,"Süreler ve BAT",timing);

        JPanel ping=panel();
        bool(ping,"remote.ping.enabled","Ping taraması etkin",c.ping.enabled);
        text(ping,"remote.ping.subnet","Alt ağ (örnek: 192.0.2.0/24; boş olabilir)",c.ping.subnet);
        list(ping,"remote.ping.addresses","Ek IPv4 hedefleri (her satıra bir adres)",c.ping.configuredAddresses==null?c.ping.addresses:c.ping.configuredAddresses);
        number(ping,"remote.ping.parallelism","Eş zamanlı hedef sayısı",c.ping.parallelism,1,32);
        number(ping,"remote.ping.attempts","Hedef başına deneme sayısı",c.ping.attempts,1,10);
        number(ping,"remote.ping.timeoutMillis","Tek ping zaman aşımı (ms)",c.ping.timeoutMillis,100,5000);
        number(ping,"remote.ping.intervalMillis","Denemeler arası bekleme (ms)",c.ping.intervalMillis,0,1000);
        tab(tabs,"Ping",ping);

        JPanel serial=panel();
        bool(serial,"remote.vxworks.enabled","VxWorks tanılaması etkin",c.vxworks.enabled);
        bool(serial,"remote.vxworks.autoDiscoverPorts","COM portlarını otomatik bul",c.vxworks.autoDiscoverPorts);
        list(serial,"remote.vxworks.ports","Manuel portlar (her satıra bir COM adı)",c.vxworks.ports);
        number(serial,"remote.vxworks.baudRate","Seri hız (baud)",c.vxworks.baudRate,110,921600);
        number(serial,"remote.vxworks.dataBits","Veri bitleri",c.vxworks.dataBits,5,8);
        number(serial,"remote.vxworks.stopBits","Durdurma bitleri",c.vxworks.stopBits,1,2);
        choice(serial,"remote.vxworks.parity","Parite",c.vxworks.parity,"None","Odd","Even","Mark","Space");
        choice(serial,"remote.vxworks.flowControl","Akış kontrolü",c.vxworks.flowControl,"None");
        number(serial,"remote.vxworks.portTimeoutSeconds","Port başına zaman aşımı (sn)",c.vxworks.portTimeoutSeconds,15,600);
        number(serial,"remote.vxworks.commandTimeoutSeconds","Seri komut zaman aşımı (sn)",c.vxworks.commandTimeoutSeconds,1,60);
        number(serial,"remote.vxworks.maxOutputMb","Port başına çıktı sınırı (MB)",c.vxworks.maxOutputMb,1,64);
        text(serial,"remote.vxworks.customCommand","Özel cihaz komutu (boş: kapalı)",c.vxworks.customCommand);
        number(serial,"remote.vxworks.customCommandDelaySeconds","Özel komut sonrası okuma (sn)",c.vxworks.customCommandDelaySeconds,0,10);
        tab(tabs,"VxWorks / COM",serial);
    }
    void read(DirectorySettings settings) {
        for(Map.Entry<String,Supplier<Object>> value:values.entrySet())settings.options.put(value.getKey(),value.getValue().get());
        char[] secret=zipPassword.getPassword();try{
            if(clearPassword.isSelected())settings.options.put("zipPassword",null);
            else if(secret.length>0)settings.options.put("zipPassword",new String(secret));
        }finally{Arrays.fill(secret,'\0');}
    }
    boolean localMode(){return "local".equals(mode.getSelectedItem());}
    void onModeChanged(Runnable listener){mode.addActionListener(event->listener.run());}
    void enabled(boolean enabled){for(JComponent control:controls)control.setEnabled(enabled);if(enabled&&clearPassword.isSelected())zipPassword.setEnabled(false);}
    void clearSecret(){zipPassword.setText("");}
    private static JPanel panel(){return new JPanel(new GridBagLayout());}
    private static void tab(JTabbedPane tabs,String title,JPanel panel){JPanel holder=new JPanel(new BorderLayout());holder.add(panel,BorderLayout.NORTH);JScrollPane scroll=new JScrollPane(holder);scroll.getVerticalScrollBar().setUnitIncrement(18);tabs.addTab(title,scroll);}
    private void text(JPanel panel,String key,String label,String initial){
        JTextField input=new JTextField(initial==null?"":initial,28);register(panel,key,label,input,()->{
            String value=input.getText().trim();
            return value.isEmpty()&&"remote.ping.subnet".equals(key)?null:value;
        });
    }
    private void number(JPanel panel,String key,String label,int initial,int min,int max){
        JSpinner input=new JSpinner(new SpinnerNumberModel(initial,min,max,1));
        register(panel,key,label+" ("+min+"–"+max+")",input,()->{try{input.commitEdit();}catch(java.text.ParseException e){throw new IllegalArgumentException(label+": geçerli bir sayı girin.");}return ((Number)input.getValue()).intValue();});
    }
    private void bool(JPanel panel,String key,String label,boolean initial){JCheckBox input=new JCheckBox();input.setSelected(initial);register(panel,key,label,input,input::isSelected);}
    private void choice(JPanel panel,String key,String label,String initial,String... choices){JComboBox<String> input=new JComboBox<>(choices);input.setSelectedItem(initial);register(panel,key,label,input,input::getSelectedItem);}
    private void list(JPanel panel,String key,String label,List<String> initial){
        JTextArea input=new JTextArea(String.join("\n",initial),3,28);input.setName("setting."+key);controls.add(input);JScrollPane scroll=new JScrollPane(input);
        SettingsHelp.install(scroll,key);
        field(panel,label,scroll);values.put(key,()->{List<String> result=new ArrayList<>();for(String line:input.getText().split("[\\r\\n]+")){String entry=line.trim();if(!entry.isEmpty())result.add(entry);}return result;});
    }
    private void register(JPanel panel,String key,String label,JComponent input,Supplier<Object> read){input.setName("setting."+key);SettingsHelp.install(input,key);controls.add(input);values.put(key,read);field(panel,label,input);}
    private static List<String> paths(List<java.nio.file.Path> paths){List<String> result=new ArrayList<>();for(java.nio.file.Path path:paths)result.add(path.toString());return result;}
    private static void field(JPanel panel,String label,JComponent input){int row=panel.getComponentCount()/2;GridBagConstraints c=new GridBagConstraints();c.gridx=0;c.gridy=row;c.anchor=GridBagConstraints.NORTHWEST;c.insets=new Insets(7,8,7,8);JLabel caption=new JLabel(label+ (label.isEmpty()?"":":"));caption.setLabelFor(input);caption.setToolTipText(input.getToolTipText());panel.add(caption,c);c.gridx=1;c.weightx=1;c.fill=GridBagConstraints.HORIZONTAL;panel.add(input,c);}
}
