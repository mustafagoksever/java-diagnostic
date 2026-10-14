# Review düzeltmeleri ve tek JAR dağıtımı

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
