# Hata Raporu Oluştur — Adım Adım

Bu açıklama **Uzak Sunucu** modu içindir. Java tanılama araçları, ping ve BAT dosyası **sunucuda çalıştırılır**. VxWorks komutları, sunucunun COM portları üzerinden bağlı cihaza gönderilir ve **VxWorks cihazında çalışır**. Dosyaları indirme, analiz ve ZIP hazırlama işlemleri bu bilgisayarda yapılır.

**Hata Raporu Oluştur** butonuna basınca:

1. **Sunucuya bağlanır.** Ekranda girdiğin kullanıcı adı ve parola kullanılır. Sunucu adresi ve JDK klasörü, JAR içine gömülü config'ten okunur.

2. **Geçici çalışma klasörü açar.** Sunucuda önce `C:\Temp`, alan veya erişim uygun değilse `D:\Temp` denenir. Her rapor için ayrı bir iş klasörü oluşturulur. Config'teki başlangıç beklemesi uygulanır; mevcut değer 10 saniyedir.

3. **Sunucudaki Java süreçlerini bulur.** PID, uygulama adı, kullanıcı ve oturum bilgileri kaydedilir. Tanınan Collector süreçleri rapor hedefinden çıkarılır.

4. **Sunucunun sistem bilgilerini toplar.** CPU, RAM ve disk bilgileri alınır; config'teki süre boyunca CPU/RAM kullanımı izlenir.

5. **Sunucudaki uygun JDK'yı otomatik bulup Java bilgilerini ve thread dump'larını alır.** Config'teki `remote.jdkHome: auto` ile hedef Java klasörü, JAVA_HOME, PATH, kayıt defteri ve yaygın kurulum dizinleri incelenir. PID'nin sürüm ve 32/64 bit mimarisine göre araçlar sıralanır. `jcmd PID VM.version` ve etkinse `jstat -gcutil PID 1000 1` denenir. Her hedef Java için üç dump örneği alınır: önce `jcmd PID Thread.print -l`, başarısızsa `jstack -l PID`, gerekirse sonraki uygun JDK. Bir örnekte ilk geçerli dump'ta durulur; başarılı araç kalan örneklerde tercih edilir. Örnekler arasında en az 5 saniye beklenir. Aday sayısı ve toplam deneme süresi sınırlıdır. Seçilen araç `java-surecleri.txt`, adaylar ve elenme nedenleri `debug/jdk-discovery.json`, ham hatalar manifest/dump dosyalarındadır.

6. **Config'teki IP ağına sunucudan ping atar.** `remote.ping.subnet: '15.30.9.0/24'` ile `.1–.254` arasındaki 254 hedefi, aynı anda en fazla 16 IP olacak şekilde tarar. IP başına 1 istek, 1 saniye zaman aşımı kullanılır; bunların tamamı config'ten alınır. `addresses` ile ek IP tanımlanabilir. Yanıt veren/vermeyen sayıları ve gecikmeler `ping/SONUC.txt`, tablo `ping/results.csv`, denemeler `ping/results.json` içinde yer alır; 254 ayrı dosya üretilmez. Ping yanıtı cihazın VxWorks olduğunu göstermez.

7. **Sunucudaki COM portlarını bulur ve bağlı VxWorks cihazlarından bilgi alır.** `remote.vxworks.autoDiscoverPorts: true` olduğunda sunucuda kayıtlı bütün portları sırayla dener; kapalıysa config'teki `ports` listesi kullanılır. Mevcut bağlantı ayarları **115200 baud, 8 veri biti, 1 stop biti, parity yok, flow control yok** şeklindedir. Kullanımda olan port devralınmaz. Konsol istemi geldikten sonra her cihazda aşağıdaki “VxWorks komut sırası” uygulanır. Bu adım da Hata Raporu Oluştur düğmesine dahildir; ayrı komut girmen gerekmez.

8. **Config'te belirtilen BAT dosyasını sunucuda çalıştırır.** Yol, `remote.beforeLogCopyBatch` alanından alınır ve sunucudaki uygulama klasörüne göre çözülür; mevcut config değeri `Log/kaydal.bat` şeklindedir. Başarıyla biterse `remote.beforeLogCopyDelaySeconds` kadar beklenir; mevcut değer 30 saniyedir. BAT başarısız olursa veya config'teki süre sınırını aşarsa log kopyalama atlanır ve nedeni kaydedilir.

9. **Config'teki log klasörlerini sunucudan kopyalar.** Alt klasör, uzantı, tarih ve dosya boyutu filtreleri config'e göre uygulanır. Collector kaynak dosyaları silmez veya taşımaz. Önceki adımda çalıştırılan BAT, kendi içeriğine göre kaynaklarda işlem yapabilir.

10. **Toplanan dosyaları bu bilgisayara indirir.** Dosya boyutları ve SHA-256 değerleri kontrol edilir. Sunucudaki işin bittiği doğrulandıktan sonra yalnız o rapora ait geçici klasör temizlenir; temizlik başarısızsa uyarı verilir.

11. **Özetleri ve hata kayıtlarını hazırlar.** Java dump analizleri, ping sonuçları, COM/task/socket listeleri ve `Manifest.json` oluşturulur. Başarılı ve başarısız işlemler rapora yazılır. ZIP kökündeki **`rapor-zamanlari.txt`**, Collector'ın açılışını, toplama başlangıcını ve ZIP öncesi hazırlık bitişini; sunucunun ve bu bilgisayarın saatlerini, saat dilimlerini ve geçen sürelerini gösterir. Saatler UTC ve yerel ofsetleriyle ayrılır; aynı veriler `Manifest.json/timing` içinde bulunur.

12. **Tek şifreli ZIP oluşturur.** Bütün veriler bu bilgisayardaki `C:\aselsan` klasörüne AES-256 şifreli ZIP olarak yazılır. ZIP içeriği tekrar okunarak doğrulanır.

Bir JVM'den veya COM portundan veri alınamazsa nedeni kaydedilir ve diğer hedefler denenir. Eksik veri içeren rapor **PARTIAL** olarak işaretlenebilir. Hedef Java uygulamaları sonlandırılmaz, yeniden başlatılmaz ve zorla GC çalıştırılmaz. İşlemler arka planda yürütülür; arayüz kullanılabilir kalır.

**Diğer düğmeler:** “Java Süreçlerini Listele” yalnız keşif yapar; ping, seri port ve BAT işlemlerini çalıştırmaz. “Çıktı Klasörünü Aç” bu bilgisayardaki rapor klasörünü açar. “Logları Temizle” yalnız ekrandaki günlüğü ve süreç tablosunu temizler.

## Windows sunucusunda ek sorgular

Java dump'larından sonra, ping/COM adımlarından önce `remote.windowsDiagnostics: true` ise aşağıdaki sorgular **bağlanılan sunucuda** çalışır. Önce ZIP içindeki **`server/SONUC.txt`** dosyasını aç. Her sorgunun süresi, sonucu ve hatası `Manifest.json/operations` içinde bulunur. Tek sorgu en fazla 15 saniye, WMI/olay sorguları ayrıca `scanTimeoutSeconds` ile sınırlıdır; toplam uzak iş limiti geçerlidir. Bir komut hatası kalan sorguları engellemez; eksik veri PARTIAL olarak görünür. Java Süreçlerini Listele düğmesi bu sorguları çalıştırmaz.

| Bilgi | Sunucuda çalıştırılan sorgu | ZIP konumu |
| --- | --- | --- |
| IP, MAC, DNS, adaptör | `ipconfig /all` | `server/network/ipconfig.txt` |
| Açık TCP/UDP portları ve PID | `netstat -ano` | `server/network/netstat-ano.txt` |
| TCP/UDP/IP sayaçları | `netstat -s` | `server/network/netstat-statistics.txt` |
| Rota ve ARP | `route print`, `arp -a` | `server/network/` |
| Yardımcı hesabın SMB bağlantıları | `net use` | `server/network/share-connections.txt` |
| Süreçler ve servis eşleşmeleri | `tasklist /v /fo csv`, `tasklist /svc /fo csv`, `Win32_Process`, `Win32_Service` | `server/diagnostics/` |
| Sürücüler, donanım, Windows sürümü | `driverquery /v /fo csv`, `systeminfo /fo list` | `server/diagnostics/` |
| Yardımcı hesabı, oturumlar, saat servisi | `whoami /all`, `query user`, `w32tm /query /status` | `server/diagnostics/` |
| Son 48 saatin kritik/hata/uyarı olayları | `Get-WinEvent`, System ve Application günlüklerinden bağımsız olarak en fazla 100'er kayıt | `server/diagnostics/events.json`; ayrı sonuçlar `events-system.json`, `events-application.json` |
| CPU, RAM, disk | Mevcut Windows sistem ve kaynak izleme sorguları | ZIP kökündeki `sunucu-kaynak-kullanimi.txt`, `kaynak-izleme-ozeti.txt`, `disk-bilgileri.txt` |

Ham Windows EXE çıktıları sunucunun OEM kodlamasında, JSON ve özet UTF-8'dir. Olayların 48 saatlik penceresi **sunucu saatine** göre hesaplanır. `whoami`/`net use`, WMI ile başlatılan toplama hesabının oturumunu gösterir; diğer etkileşimli kullanıcıların sürücü eşlemelerinin tamamını göstermez. Olay günlüklerine erişim reddedilirse hata korunur. `w32tm` yalnız durum okur; saat eşitlemez. VxWorks `tt`, `ti`, `checkStack` ve `TatlYaz` komutlarının birebir Windows karşılığı yoktur; Java yığınları mevcut JVM dump dosyalarındadır.

Aktarım sonrası yalnız o raporun UUID içeren geçici klasörü silinir. Kısa süreli dosya/SMB kilitlerinde temizlik beş saniyeye kadar yeniden denenir. Kalıcı kilit veya yetki hatası varsa gerçek hata ve klasör yolu raporlanır; başka uygulamanın dosya tutamacı zorla kapatılmaz. Çocuk yardımcılar Windows dizininde başlatılır ve seri port kapanış hatası dosya akışlarının kapanmasını engellemez.

System ve Application ayrı işlemlerdir. Biri erişim hatası veya timeout verirse diğerinin kayıtları korunur ve diğer sorgu yine denenir. `events.json` alınabilen kayıtların birleşimidir; tek kanalın hatası `server/SONUC.txt` ve manifestte görünür. Olay bulunmayan başarılı kanalda geçerli boş JSON listesi (`[]`) yazılır.

## VxWorks komut sırası

Komutlar bu bilgisayardaki Windows CMD üzerinde çalışmaz. Collector, **sunucudaki COM portuna** bağlanır; komutu seri hat üzerinden gönderir ve **bağlı VxWorks cihazının** yanıtını kaydeder. Aşağıdaki sıra her doğrulanmış port için tekrarlanır. `0xTASKID`, ilk `i` çıktısından okunan gerçek task kimliğidir; elle yazılmaz.

| Sıra | Gönderilen komut | Ne için alınır? |
| --- | --- | --- |
| 1 | `version` | VxWorks sürümünü doğrular. Sürüm başlığı doğrulanmazsa o portta başka komut gönderilmez. |
| 2 | `ifconfig`; bilgi alınamazsa `ifShow` | IP/MAC ve ağ arayüzlerinden hangi donanıma bağlanıldığını anlamaya yardımcı olur; COM portuyla eşleştirilir. |
| 3 | `TatlYaz` | Bir kez çağrılır; cihazın verdiği ham çıktı saklanır. İlk konsol istemi döndükten sonra **3 saniye daha veri okunur**. Geç gelen loglar da aynı `TatlYaz.txt` dosyasına eklenir; bu süre bitmeden `i` gönderilmez. Komutun uygulamaya özel iç etkileri varsayılmaz. |
| 4 | `i` | Başlangıçtaki task listesini alır. Tanınan “Type <CR> to continue” isteminde otomatik Enter göndererek sonraki sayfaları da okur. |
| 5 | Her task için `tt 0xTASKID`, ardından `ti 0xTASKID` | Önce task'ın çağrı yığını, sonra task/stack/register ayrıntıları alınır. Bir task'ın TT/TI çifti bitince sonraki task'a geçilir. |
| 6 | `i` | İkinci task görüntüsünü alır; ilk listeye göre eklenen ve kaybolan task kimlikleri kaydedilir. |
| 7 | `checkStack 0` | Task'ların stack kullanımı ve kalan payını incelemek için SIZE/CUR/HIGH/MARGIN tablosu istenir. Stack belleğini taradığından cihazda ek yük oluşturabilir. |
| 8 | `taskShow 0,2` | Task'ların ayrıntılı durum bilgileri istenir. |
| 9 | `devs` | Cihazda kayıtlı I/O aygıtlarını listeler. |
| 10 | `whoami` | Konsolun kullanıcı bilgisini ister. |
| 11 | `w` | Bekleme durumlarına ilişkin bilgi ister. |
| 12 | `tw 0xTASKID` | **İlk listede PEND durumundaki task'lar** için beklenen nesne/semafor ayrıntılarını ister. Task askıya alma komutu gönderilmez. |
| 13 | `inetstatShow` | Socket/bağlantı ve açık port bilgilerini ister. |
| 14 | `tcpstatShow 0` | TCP istatistiklerini ister. |
| 15 | `udpstatShow 0` | UDP istatistiklerini ister. |
| 16 | `ipstatShow 0` | IP istatistiklerini ister. |
| 17 | `routeShow` | Yönlendirme tablosunu ister. |
| 18 | `nfsDevShow` | NFS aygıt/bağlantı bilgilerini ister. |
| 19 | `memShow 0` | Bellek kullanımını ister; bu akışta bir kez çalıştırılır. |
| 20 | Gerektiğinde `lkup "netstat"`, ardından `netstat "-a -n"` | Önceki ağ yanıtından socket satırı çıkarılamazsa alternatif denenir. `netstat` seçenek hatası döndürürse ayrıca `netstat "-a"` çalıştırılır. |

Her komutun her VxWorks imajında bulunması garanti değildir. `unknown symbol` gibi yanıtlar ham çıktısıyla kaydedilir; istem geri geldiyse sonraki adıma geçilir. İstem dönmezse, port süresi dolarsa veya bağlantı hatası oluşursa o portta yeni komut gönderilmez; alınan kısmi veriler korunur ve kalan portlar toplam iş süresi elverdiğince denenir.

Mevcut config'te tek komut limiti **10 saniye**, bir portun toplam limiti **180 saniye**, bütün uzak işin limiti **1800 saniye**dir. TatlYaz'ın ilk isteminden sonraki üç saniyelik okuma için komut okuma bütçesi gerektiğinde uzatılır; port/toplam iş sınırları korunur. Yanıt başına 1 MiB, en fazla 100 sayfalama devamı ve başlangıç listesinde 4096 task sınırı vardır. Bu sınırlar nedeniyle çok büyük veya yavaş bir cihazın bütün task'larına ulaşılamayabilir; raporda nedeni görünür. Reset, yeniden başlatma ve bağımsız `.` komutu gönderilmez.

## Çıktıda nereye bakılır?

Önce raporun ana özetini, sonra **`vxworks/SONUC.txt`** dosyasını aç. Bu dosya COM portlarını, bulunan cihaz kimliklerini ve başarılı/başarısız komutları topluca gösterir. Ayrıntı gerektiğinde ilgili `vxworks/ports/port-COM…/` klasörüne gir:

| Dosya / klasör | İçerik |
| --- | --- |
| `TatlYaz.txt` | TatlYaz yanıtı ve üç saniyelik ek okuma sırasında gelen loglar. |
| `i-before.txt`, `i-after.txt` | Önceki ve sonraki task listelerinin ham çıktıları. |
| `tasks/tt-0x….txt`, `tasks/ti-0x….txt`, `tasks/tw-0x….txt` | İlgili task'ın trace, ayrıntı ve varsa bekleme nesnesi yanıtı. |
| `diagnostics/` | version, checkStack, taskShow, devs, whoami ve w çıktıları. |
| `network/` | Cihaz kimliği, socket, TCP/UDP/IP, route, NFS ve bellek yanıtları. |
| `commands.json`, `console.txt` | Komut bazında süre/başarı/hata kayıtları ve seri konsol dökümü. |

`TatlYaz` için yalnız `value = 0` görülmesi, cihazdaki uygulamanın ayrıca bir log dosyası ürettiğinin kanıtı değildir. Ham yanıt ve bekleme sırasında gelen veriler olduğu gibi saklanır. Ekrandaki ilerleme mesajları bazı ara komutları göstermeyebilir; hangi komutun gerçekten denendiği `commands.json` üzerinden kontrol edilir.
