# Review düzeltmeleri ve tek JAR dağıtımı

## 14 Ekim 2026 — BAT oturumu ve VxWorks tt/ti

1. Etkileşimli BAT adayları önce seçilen PID'lerle sınırlandırılır. Adaylar tek kullanıcı oturumunda olmalı; OSGi tercihi yalnız bu oturum içinde uygulanır. Seçilen hedef uygun değilse başka uygulama/oturuma geçilmez.
2. Seri komutun yanıt tamponu Write öncesinde sıfırlanır. Write başarısızsa önceki tt yanıtı ti dosyasına yazılmaz; boş yanıt dosyası da oluşturulmaz. Tamamlanmış hata sonucu COLLECTING durumunda bırakılmaz.
3. tt timeout/yazma hatası task.error içine kaydedilir; task-index.json ve task-listesi.csv neden bilgisini korur. ti hatası başarıyla alınmış tt sonucunu değiştirmez.
4. ti void dönüşlüdür; interpreter'ın value=-1 veya unsigned all-ones değeri tek başına hata kabul edilmez. Açık native hata, aynı TASKID'nin TCB satırı ve istem dönüşü kontrol edilmeye devam eder.

Java 8 ile 45 unit + 27 Windows testi geçti (RemoteExtrasIT 14, RemoteBeforeLogCopyIT 13). Regresyonlar: seçilen PID/oturum, çoklu oturum belirsizliği, boş/eksik/başka kullanıcı/Collector hedefleri, ti Write hatasında önceki yanıtı kullanmama, tt timeout'unun index/CSV'ye aktarımı, geçerli TCB ile signed/unsigned interpreter değerleri. Mevcut native hata, yanlış TASKID, timeout, BAT başarı/hata/30 saniye bekleme, gerçek yerel InteractiveToken görev temizliği ve iptal testleri de geçti. Seri adapter fixture'dır; fiziksel COM ve canlı sunucu testi yapılmadı. BAT fixture'ı aynı seçilmiş test PID'sini kimlik doğrulamak için kullanır; bulunmayan JDK ile attach yapılmaz ve yalnız bu fixture'da başarısız dump denemeleri arasındaki bekleme kaldırılır. Üretim dump aralıkları korunur.

13 Ekim 2026 incelemesindeki beş bulgu giderildi:

1. Uzak tüm-süreçler seçimi için boş PID döngüsü çalıştırılmıyor. Eksik PID mesajı tek metin olarak ekleniyor; Java geçersiz hata öğesini kontrollü kayıtla PARTIAL rapora dönüştürüyor.
2. Yardımcı süreç başlatma durumu ayrı tutuluyor. Başlatılamayan süreçte HasExited/Kill çağrılmıyor; temizlik ilk hatayı maskelemiyor. Geçersiz jcmd executable ile gerçek jstack'in üç dump alması ve log kopyasının devamı test edildi.
3. Uzak CIM/WMI keşfi ve sistem bilgisi ayrı PowerShell yardımcılarında scanTimeoutSeconds ile sınırlı. PID kimlik sorguları commandTimeoutSeconds kullanıyor. Kalan toplam süre de uygulanıyor; takılan sorguda yalnız oluşturulan yardımcı kapatılıyor. Yavaş sorgu ve yardımcı PID'nin çıkışı test edildi.
4. Swing başlangıç config hatası görünür Türkçe JOptionPane ile EDT'de gösteriliyor. CLI stderr/exit kodu korunuyor. GUI hata metni sunucu IP/JDK bilgisini göstermiyor.
5. Worker entegrasyon testi ilgisiz süreçlerin modül erişim uyarısına izin veriyor; diğer hatalar, üç dump, dosya kaynağının korunması, filtreleme, ölçüm ve SHA-256 kontrolleri zorunlu.

Kaynak config.yaml Maven kaynak tanımıyla internal/config.yaml olarak JAR'a gömülür. Varsayılan çalışma yanındaki YAML'ı okumaz; açık --config operatör override'ı korunur. Kullanıcıya yalnız JAR verilir. Ayarlar JAR arşivinden çıkarılabilir; gömme erişim güvenliği değildir. Sunucu kimlik bilgileri gömülmez veya diske yazılmaz.

Rapor çıktısı collector bilgisayarında C:/aselsan'dır. Yerel modda çıktı kökü uygulama kaynaklarının üstündeyse bütün log ağacı dışlanmaz; uygulama logları korunur, staging ve collector'ın önceki raporları atlanır. Bu yol yerleşimi için regresyon testi eklendi.

## Derleme ve dağıtım

Java 8 ile:

```powershell
mvn -B -ntp -Pwindows-it verify
.\scripts\Package-Collector.ps1 -SkipBuild
```

Tam test yerine normal unit doğrulamasıyla paketlemek için Package-Collector.ps1 tek başına kullanılabilir. Script Maven başarısızsa yayınlamaz. -SkipBuild yalnız başarılı derlemeden sonra kullanılmalıdır.

Kullanıcıya java-diagnostic-collector.jar veya target/diagnostic-collector-single-jar.zip verilir. ZIP'te yalnız JAR vardır. Harici config, PS1 ve kaynak kod verilmez. Başlatma:

```cmd
java -jar java-diagnostic-collector.jar
```

Java 8 kurulumu ve Windows JAR ilişkilendirmesi varsa çift tıklama kullanılabilir. Hedef sunucuya bağlantı olmadığı için yeni uzak backend'in gerçek sunucuda uçtan uca saha doğrulaması bu turda yapılmadı; yerel Windows fixture testleri gerçek jcmd/jstack ve worker yardımcı süreçlerini kullanır.

## 14.10.2026 — ping ve VxWorks değişikliklerinin commit öncesi incelemesi

Yeni toplama yardımcıları, yapılandırma doğrulaması, manifest/özet aktarımı, uzak oturum seçimi ve kaynak teslimi incelendi. Bu turda üç hata ayrı regresyonlarla düzeltme öncesinde tekrarlandı:

1. **P2 — bozuk ek tanılama kaydı bütün raporu durdurabiliyordu.** Ping sonuçlarında veya port/diagnostics/task/socket listelerinde null ya da nesne olmayan satır, kullanıcı özetinde exception üretip mevcut Java/log verilerinin paketlenmesini engelliyordu. Geçersiz satırlar şimdi hata kaydıyla atlanır; geçerli satırlar ve diğer bölümler yazılır. Bu hatalar manifestte kaldığından rapor PARTIAL olur.
2. **P2 — tek okumada sınırı aşan seri yanıtın kısmi dosyası kayboluyordu.** Yanıt tamponu güncellenmeden 1 MiB sınır hatası oluşabiliyordu. Hata verilmeden önce ilk 1 MiB saklanır; komut sonucu başarısız kalır ve başka komut gönderilmez. Büyük ilk okuma senaryosu gerçek PowerShell ve taklit seri adapter ile doğrulanır.
3. **P2 — ping gönderim/yanıt exception'ı yanıtsız cihaz gibi etiketleniyordu.** Sistem/ICMP hataları artık ERROR durumundadır. NO_ECHO_REPLY yalnız hata olmadan tamamlanmış, Echo yanıtı alınmamış denemeler içindir. Genel ping sonucu hata halinde başarısız kalır; diğer IP'ler denenir.

Bu inceleme turu sunucudaki BAT veya cihaz komutlarını yeniden çalıştırmaz; önceki canlı kayıtlar Windows test günlüğünde ayrı kapsamla korunur. target/ içindeki canlı deneme kodu ve çıktılar, .tools/ bağımlılıkları ve rapor arşivleri commit kapsamına alınmaz.

Son doğrulama: Java 8 ile tam `mvn -B -ntp -Pwindows-it verify` başarılı; 53 unit ve 71 Windows entegrasyon testi geçti. Gerçek sunucu ve başka kullanıcı/servis JVM'si için özel parametre isteyen 2 test koşullu atlandı. Dağıtım JAR'ı başarılı derlemeden yeniden paketlendi; kaynak yardımcılar/gömülü config ve JAR içeriği eşleştirilir.
