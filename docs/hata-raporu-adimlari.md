# Hata Raporu Oluştur — Adım Adım

Bu açıklama **Uzak Sunucu** modu içindir. Java tanılama araçları, ping ve BAT dosyası **sunucuda çalıştırılır**. VxWorks komutları, sunucunun COM portları üzerinden bağlı cihaza gönderilir ve **VxWorks cihazında çalışır**. Dosyaları indirme, analiz ve ZIP hazırlama işlemleri bu bilgisayarda yapılır.

**Hata Raporu Oluştur** butonuna basınca:

1. **Sunucuya bağlanır.** Ekranda girdiğin kullanıcı adı ve parola kullanılır. Sunucu adresi ve JDK klasörü, JAR içine gömülü config'ten okunur.

2. **Geçici çalışma klasörü açar.** Sunucuda önce `C:\Temp`, alan veya erişim uygun değilse `D:\Temp` denenir. Her rapor için ayrı bir iş klasörü oluşturulur. Config'teki başlangıç beklemesi uygulanır; mevcut değer 10 saniyedir.

3. **Sunucudaki Java süreçlerini bulur.** PID, uygulama adı, kullanıcı ve oturum bilgileri kaydedilir. Tanınan Collector süreçleri rapor hedefinden çıkarılır.

4. **Sunucunun sistem bilgilerini toplar.** CPU, RAM ve disk bilgileri alınır; config'teki süre boyunca CPU/RAM kullanımı izlenir.

5. **Java bilgilerini ve thread dump'larını alır.** Sunucudaki JDK ile `jcmd PID VM.version` ve etkinse `jstat -gcutil PID 1000 1` denenir. Her hedef Java için üç kez `jcmd PID Thread.print -l` çalıştırılır; başarısızsa `jstack -l PID` denenir. Dump girişimleri arasında en az 5 saniye beklenir. Her işlemde süre sınırı vardır.

6. **Config'teki IP ağına sunucudan ping atar.** `remote.ping.subnet: '15.30.9.0/24'` ile `.1–.254` arasındaki 254 hedefi, aynı anda en fazla 16 IP olacak şekilde tarar. IP başına 1 istek, 1 saniye zaman aşımı kullanılır; bunların tamamı config'ten alınır. `addresses` ile ek IP tanımlanabilir. Yanıt veren/vermeyen sayıları ve gecikmeler `ping/SONUC.txt`, tablo `ping/results.csv`, denemeler `ping/results.json` içinde yer alır; 254 ayrı dosya üretilmez. Ping yanıtı cihazın VxWorks olduğunu göstermez.

7. **Sunucudaki COM portlarını otomatik bulur ve sırayla bağlanmayı dener.** Port numaralarını tek tek girmen gerekmez. Seri bağlantı ayarları config'ten alınır. VxWorks konsolu doğrulanırsa önce `ifconfig`, gerekirse `ifShow` ile cihazın IP/MAC bilgisi okunur ve COM portuyla eşleştirilir. Ardından `TatlYaz` bir kez gönderilir; çıktısı `vxworks/ports/port-COM*/TatlYaz.txt` içine girer. İmajda komut yoksa hata kaydedilip devam edilir; istem dönmezse o portta yeni komut gönderilmez. Sonra `i` ile task listesi alınır; listedeki her TASKID için önce `tt 0xTASKID` ile stack trace, ardından `ti 0xTASKID` ile task/stack/register ayrıntısı istenir. Tekrar `i` alınır. Ardından ağ/socket, bellek ve NFS bilgileri için `inetstatShow`, gerektiğinde `netstat`, `memShow 0` ve `nfsDevShow` gibi tanılama komutları denenir. Ham çıktılar port ve task bazında saklanır. Config'te otomatik keşif kapatılırsa yalnız config'teki port listesi kullanılır.

   COM isteminden sonra artık önce `version` alınır; VxWorks sürümü doğrulanamazsa o porta başka komut gönderilmez. Task görüntülerinden sonra `checkStack 0`, `taskShow 0,2`, `devs`, `whoami`, `w` ve ilk listede PEND durumundaki task'lar için `tw 0xTASKID` denenir. Bunlar sırasıyla stack kullanımı, tüm task bilgileri, aygıtlar, kullanıcı ve beklenen nesneler hakkında veri verir. `memShow 0` bellek adımında tek kez çalışır. Komut sonuçları aynı `vxworks/SONUC.txt` içinde, ham yanıtları portun `diagnostics/` ve `tasks/tw-*.txt` yollarındadır. İmaja göre desteklenmeyenler kaydedilir; mevcut süre sınırları aşılmaz.

8. **Config'te belirtilen BAT dosyasını sunucuda çalıştırır.** Yol, `remote.beforeLogCopyBatch` alanından alınır ve sunucudaki uygulama klasörüne göre çözülür; mevcut config değeri `Log/kaydal.bat` şeklindedir. Başarıyla biterse `remote.beforeLogCopyDelaySeconds` kadar beklenir; mevcut değer 30 saniyedir. BAT başarısız olursa veya config'teki süre sınırını aşarsa log kopyalama atlanır ve nedeni kaydedilir.

9. **Config'teki log klasörlerini sunucudan kopyalar.** Alt klasör, uzantı, tarih ve dosya boyutu filtreleri config'e göre uygulanır. Collector kaynak dosyaları silmez veya taşımaz. Önceki adımda çalıştırılan BAT, kendi içeriğine göre kaynaklarda işlem yapabilir.

10. **Toplanan dosyaları bu bilgisayara indirir.** Dosya boyutları ve SHA-256 değerleri kontrol edilir. Sunucudaki işin bittiği doğrulandıktan sonra yalnız o rapora ait geçici klasör temizlenir; temizlik başarısızsa uyarı verilir.

11. **Özetleri ve hata kayıtlarını hazırlar.** Java dump analizleri, ping sonuçları, COM/task/socket listeleri ve `Manifest.json` oluşturulur. Başarılı ve başarısız işlemler rapora yazılır.

12. **Tek şifreli ZIP oluşturur.** Bütün veriler bu bilgisayardaki `C:\aselsan` klasörüne AES-256 şifreli ZIP olarak yazılır. ZIP içeriği tekrar okunarak doğrulanır.

Bir JVM'den veya COM portundan veri alınamazsa nedeni kaydedilir ve diğer hedefler denenir. Eksik veri içeren rapor **PARTIAL** olarak işaretlenebilir. Hedef Java uygulamaları sonlandırılmaz, yeniden başlatılmaz ve zorla GC çalıştırılmaz. İşlemler arka planda yürütülür; arayüz kullanılabilir kalır.

**Diğer düğmeler:** “Java Süreçlerini Listele” yalnız keşif yapar; ping, seri port ve BAT işlemlerini çalıştırmaz. “Çıktı Klasörünü Aç” bu bilgisayardaki rapor klasörünü açar. “Logları Temizle” yalnız ekrandaki günlüğü ve süreç tablosunu temizler.
