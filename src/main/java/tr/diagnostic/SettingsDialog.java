package tr.diagnostic;

import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.util.*;
import java.util.function.Consumer;

final class SettingsDialog extends JDialog {
    private static final String[] SOURCE_HELP={"logs.path","logs.base","logs.destination","logs.recursive","logs.maxFileSizeMb","logs.modifiedWithinHours","logs.extensions"};
    private final JTextField host=new JTextField(28),installation=new JTextField(28),application=new JTextField(28),output=new JTextField(28);
    private final DefaultTableModel rows=new DefaultTableModel(new String[]{"Kaynak dizin","Yolun başlangıcı","ZIP içindeki klasör","Alt dizinler","Dosya sınırı (MB)","Son kaç saat (0: tümü)","Uzantılar (boş: tümü)"},0) {
        @Override public Class<?> getColumnClass(int column){return column==3?Boolean.class:String.class;}
    };
    private final JTable table=new JTable(rows){
        @Override public String getToolTipText(MouseEvent event){int column=columnAtPoint(event.getPoint());return column<0?null:SettingsHelp.text(SOURCE_HELP[convertColumnIndexToModel(column)]);}
        @Override public Component prepareEditor(TableCellEditor editor,int row,int column){Component input=super.prepareEditor(editor,row,column);if(input instanceof JComponent)SettingsHelp.install((JComponent)input,SOURCE_HELP[convertColumnIndexToModel(column)]);return input;}
    };
    private final java.util.List<CollectorConfiguration.LogDirectory> sources=new ArrayList<>();
    private final JButton save=new JButton("Kaydet"),cancel=new JButton("Vazgeç"),add=new JButton("Dizin Ekle"),remove=new JButton("Seçileni Sil"),browse=new JButton("Klasör Seç");
    private final JLabel message=new JLabel(" ");
    private final AdvancedSettingsFields advanced;
    private boolean saving;
    private boolean localMode;
    SettingsDialog(CollectorFrame owner,CollectorConfiguration config,ConfigurationFile file,boolean local,Consumer<CollectorConfiguration> onSaved) {
        super(owner,"Ayarlar",true);setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        localMode=local;
        addWindowListener(new java.awt.event.WindowAdapter(){@Override public void windowClosing(java.awt.event.WindowEvent event){if(!saving)dispose();}});
        host.setName("settingsHost");installation.setName("settingsInstallation");application.setName("settingsApplication");output.setName("settingsOutput");table.setName("settingsSources");
        DirectorySettings values=DirectorySettings.from(config);host.setText(values.host);installation.setText(values.installation);application.setText(values.application);output.setText(values.output);
        SettingsHelp.install(host,"remote.host");SettingsHelp.install(installation,"installationDirectory");SettingsHelp.install(application,"applicationDirectory");SettingsHelp.install(output,"outputDirectory");
        SettingsHelp.install(add,"add");SettingsHelp.install(remove,"remove");SettingsHelp.install(browse,"browse");SettingsHelp.install(save,"save");SettingsHelp.install(cancel,"cancel");
        JPanel general=new JPanel(new GridBagLayout());
        field(general,0,"Sunucu IP / adı:",host);field(general,1,"Kurulum dizini:",installation);field(general,2,"Uygulama dizini (auto veya yol):",application);field(general,3,"Rapor çıktı klasörü:",output);
        JComboBox<String> bases=new JComboBox<>(new String[]{"config","installation","application"});
        bases.setRenderer(new DefaultListCellRenderer(){
            @Override public Component getListCellRendererComponent(JList<?> list,Object value,int index,boolean selected,boolean focus){return super.getListCellRendererComponent(list,baseLabel(value,localMode),index,selected,focus);}
        });
        table.getColumnModel().getColumn(1).setCellEditor(new DefaultCellEditor(bases));
        table.getColumnModel().getColumn(1).setCellRenderer(new javax.swing.table.DefaultTableCellRenderer(){
            @Override protected void setValue(Object value){super.setValue(baseLabel(value,localMode));}
        });
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);table.putClientProperty("terminateEditOnFocusLost",Boolean.TRUE);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        int[] widths={260,160,170,90,130,150,170};for(int i=0;i<widths.length;i++)table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        table.setTableHeader(new JTableHeader(table.getColumnModel()){
            @Override public String getToolTipText(MouseEvent event){int column=columnAtPoint(event.getPoint());return column<0?null:SettingsHelp.text(SOURCE_HELP[table.convertColumnIndexToModel(column)]);}
        });
        for(CollectorConfiguration.LogDirectory source:values.logs)addSource(source);
        add.addActionListener(event->{CollectorConfiguration.LogDirectory source=new CollectorConfiguration.LogDirectory();source.sourcePath="";source.base="installation";addSource(source);table.setRowSelectionInterval(rows.getRowCount()-1,rows.getRowCount()-1);});
        remove.addActionListener(event->{int row=table.getSelectedRow();if(row>=0){if(table.isEditing())table.getCellEditor().cancelCellEditing();rows.removeRow(row);sources.remove(row);}});
        browse.setVisible(local);browse.addActionListener(event->{if(!localMode)return;int row=table.getSelectedRow();if(row<0){message.setText("Önce bir kaynak satırı seçin.");return;}JFileChooser chooser=new JFileChooser();chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);if(chooser.showOpenDialog(this)==JFileChooser.APPROVE_OPTION){rows.setValueAt(chooser.getSelectedFile().getAbsolutePath(),row,0);rows.setValueAt("config",row,1);}});
        JPanel tools=new JPanel(new FlowLayout(FlowLayout.LEFT));tools.add(add);tools.add(remove);tools.add(browse);
        JPanel directories=new JPanel(new BorderLayout(8,8));directories.add(new JScrollPane(table),BorderLayout.CENTER);directories.add(tools,BorderLayout.SOUTH);
        JLabel hint=new JLabel(local?"Kaynaklar bu bilgisayardan kopyalanır.":"Kaynak yollarını sunucudaki konumuyla girin; bu bilgisayardaki klasörleri seçmeyin.");directories.add(hint,BorderLayout.NORTH);
        JTabbedPane tabs=new JTabbedPane();tabs.addTab("Sunucu ve dizinler",general);tabs.addTab("Kopyalanacak dizinler",directories);
        advanced=new AdvancedSettingsFields(tabs,config,local);
        advanced.onModeChanged(()->{
            localMode=advanced.localMode();browse.setVisible(localMode);
            hint.setText(localMode?"Kaynaklar bu bilgisayardan kopyalanır.":"Kaynak yollarını sunucudaki konumuyla girin; bu bilgisayardaki klasörleri seçmeyin.");
            table.repaint();bases.repaint();revalidate();
        });
        cancel.addActionListener(event->dispose());save.addActionListener(event->{
            if(table.isEditing()&&!table.getCellEditor().stopCellEditing())return;
            final DirectorySettings edited;
            try{edited=readSettings();}catch(IllegalArgumentException invalid){message.setForeground(new Color(0xA02020));message.setText(invalid.getMessage());return;}
            saving(true);message.setForeground(UIManager.getColor("Label.foreground"));message.setText("Ayarlar kaydediliyor...");
            new SwingWorker<CollectorConfiguration,Void>(){
                @Override protected CollectorConfiguration doInBackground() throws Exception{return file.save(edited,localMode);}
                @Override protected void done(){try{CollectorConfiguration saved=get();onSaved.accept(saved);dispose();}catch(Exception e){saving(false);Throwable cause=e.getCause()==null?e:e.getCause();message.setForeground(new Color(0xA02020));message.setText("Kaydedilemedi: "+cause.getMessage());}}
            }.execute();
        });
        JPanel actions=new JPanel(new FlowLayout(FlowLayout.RIGHT));actions.add(cancel);actions.add(save);
        JPanel bottom=new JPanel(new BorderLayout(6,6));JLabel location=new JLabel("Konfigürasyon: "+file.path());location.setToolTipText(file.path().toString());bottom.add(location,BorderLayout.NORTH);bottom.add(message,BorderLayout.CENTER);bottom.add(actions,BorderLayout.SOUTH);
        JPanel root=new JPanel(new BorderLayout(10,10));root.setBorder(BorderFactory.createEmptyBorder(12,12,12,12));root.add(tabs,BorderLayout.CENTER);root.add(bottom,BorderLayout.SOUTH);setContentPane(root);
        setSize(1180,720);setMinimumSize(new Dimension(900,500));setLocationRelativeTo(owner);getRootPane().setDefaultButton(save);
    }
    private DirectorySettings readSettings(){
        DirectorySettings edited=new DirectorySettings();edited.host=host.getText();edited.installation=installation.getText();edited.application=application.getText();edited.output=output.getText();
        for(int i=0;i<rows.getRowCount();i++){
            CollectorConfiguration.LogDirectory source=DirectorySettings.copy(sources.get(i));source.sourcePath=cell(i,0);source.base=cell(i,1);source.destination=cell(i,2);source.recursive=Boolean.TRUE.equals(rows.getValueAt(i,3));
            source.maxFileSizeBytes=rowNumber(i,4,1,10240,"Dosya boyutu sınırı")*1024L*1024;
            source.modifiedWithinHours=rowNumber(i,5,0,87600,"Tarih filtresi");source.extensions.clear();
            for(String extension:cell(i,6).split("[,;\\s]+")){String value=extension.trim().toLowerCase(Locale.ROOT);if(!value.isEmpty())source.extensions.add(value.startsWith(".")?value:"."+value);}
            edited.logs.add(source);
        }
        advanced.read(edited);return edited;
    }
    private int rowNumber(int row,int column,int min,int max,String label){try{int value=Integer.parseInt(cell(row,column).trim());if(value>=min&&value<=max)return value;}catch(NumberFormatException ignored){}throw new IllegalArgumentException((row+1)+". satır: "+label+" "+min+"–"+max+" aralığında olmalı.");}
    private void addSource(CollectorConfiguration.LogDirectory source){sources.add(source);rows.addRow(new Object[]{source.sourcePath,source.base,source.destination==null?"":source.destination,source.recursive,Long.toString(source.maxFileSizeBytes/(1024*1024)),Integer.toString(source.modifiedWithinHours),String.join(", ",source.extensions)});}
    private String cell(int row,int column){Object value=rows.getValueAt(row,column);return value==null?"":value.toString();}
    private static String baseLabel(Object value,boolean local){return "application".equals(value)?"Uygulama dizini":"config".equals(value)&&local?"Konfigürasyon klasörü":"Kurulum dizini";}
    private void saving(boolean value){saving=value;for(JComponent component:new JComponent[]{host,installation,application,output,table,save,cancel,add,remove,browse})component.setEnabled(!value);advanced.enabled(!value);}
    @Override public void dispose(){if(advanced!=null)advanced.clearSecret();super.dispose();}
    private static void field(JPanel panel,int row,String label,JComponent component){GridBagConstraints c=new GridBagConstraints();c.gridy=row;c.gridx=0;c.anchor=GridBagConstraints.WEST;c.insets=new Insets(8,8,8,8);JLabel caption=new JLabel(label);caption.setLabelFor(component);caption.setToolTipText(component.getToolTipText());panel.add(caption,c);c.gridx=1;c.weightx=1;c.fill=GridBagConstraints.HORIZONTAL;panel.add(component,c);}
}
