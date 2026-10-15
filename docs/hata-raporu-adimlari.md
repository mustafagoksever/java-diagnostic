# Hata Raporu Oluştur — İşlem Adımları

Bu belge, **Hata Raporu Oluştur** düğmesine basıldığında programın hangi bilgileri topladığını ve işlemlerin nerede yapıldığını açıklar. Kullanıcının ayrıca komut girmesi gerekmez. Ping, VxWorks, kaynak izleme ve log hazırlama gibi isteğe bağlı adımlar, önceden yapılan ayarlarda etkinse çalışır.

## İşlemler nerede yapılır?

**Uzak Sunucu** modunda inceleme, bağlantı kurulan sunucu üzerinde yapılır. **Yerel Bilgisayar** modunda programın açık olduğu bilgisayar incelenir. Program doğrudan sunucu üzerinde yerel modda açılırsa o sunucunun kendi bilgileri toplanır.

| İşlem | Uzak Sunucu modu | Yerel Bilgisayar modu |
| --- | --- | --- |
| Java uygulamalarının ve Windows bilgilerinin incelenmesi | Sunucuda yapılır. | Programın açık olduğu bilgisayarda yapılır. |
| Ağdaki cihazlara ping gönderilmesi | Sunucudan gönderilir. | Programın açık olduğu bilgisayardan gönderilir. |
| Seri bağlantı için COM portu kullanılması | Sunucunun COM portları kullanılır. | Programın açık olduğu bilgisayarın COM portları kullanılır. |
| VxWorks bilgilerinin okunması | Sunucuya seri kanaldan bağlı VxWorks cihazından okunur. | Bu bilgisayara seri kanaldan bağlı VxWorks cihazından okunur. |
| Uygulama loglarının hazırlanması ve alınması | Sunucudaki kaynaklardan alınır. | Bu bilgisayardaki kaynaklardan alınır. |
| Sonuçların analiz edilmesi ve ZIP raporunun hazırlanması | Programın açık olduğu bilgisayarda yapılır. | Programın açık olduğu bilgisayarda yapılır. |

Aşağıdaki adımlarda **hedef bilgisayar**, seçilen moda göre sunucuyu veya yerel bilgisayarı ifade eder.

## 1. Kullanıcı rapor oluşturmayı başlatır

Uygulamayı açın. Kullanıcı adı ve parola alanları görünüyorsa size verilen sunucu kullanıcı adı ve parolasını girin. Yerel çalışmada sunucu giriş bilgileri istenmez.

**Hata Raporu Oluştur** düğmesine basın. İşlem tamamlanana kadar pencereyi kapatmayın. Yüzde göstergesi tamamlanan aşamaları gösterir; kalan süre tahmini değildir. Bazı aşamalar diğerlerinden daha uzun sürebilir.

## 2. Hedef bilgisayarda toplama hazırlığı yapılır

Uzak modda girilen bilgilerle sunucuya bağlantı kurulur. Yerel modda işlemler doğrudan bu bilgisayarda başlar.

Rapor için gerekli çalışma alanı hazırlanır. Başlangıç beklemesi ayarlanmışsa bu süre beklendikten sonra bilgi toplamaya geçilir.

## 3. Çalışan Java uygulamaları bulunur

Hedef bilgisayardaki Java uygulamaları belirlenir. Uygulama adı, süreç numarası, çalıştıran kullanıcı ve oturum gibi bilgiler kaydedilir.

Rapor hazırlayan programın kendisi inceleme hedeflerinin dışında tutulur. Birden fazla hedef Java uygulaması varsa her biri için ayrı bilgi toplanır. Java uygulaması bulunamazsa bu durum belirtilir; alınabilen diğer bilgiler raporda korunur.

## 4. Sistem ve kaynak kullanımı bilgileri alınır

Hedef bilgisayarın Windows, işlemci, bellek ve disk bilgileri toplanır.

Kaynak izleme etkinse belirlenen süre boyunca hedef Java uygulamalarının CPU ve RAM kullanımı aralıklı olarak ölçülür. Böylece rapor, tek bir anlık değerin yanında ölçüm süresince gözlenen kaynak kullanımını da içerir. Bu ölçümler diğer toplama işlemleriyle aynı anda devam edebilir.

## 5. Java uygulamalarının çalışma durumu incelenir

Her hedef Java uygulamasının sürüm bilgisi alınmaya çalışılır. Etkinse bellek temizleme faaliyetlerine ilişkin istatistikler de okunur.

Ardından her uygulamadan, örnekler arasında en az beş saniye bırakılarak **üç ayrı iş parçacığı görüntüsü (thread dump)** alınmaya çalışılır. Bu görüntüler, iş parçacıklarının o anda ne yaptığını, hangi işlemlerde beklediğini ve hangi kilitleri beklediğini incelemek için kullanılır.

Birden fazla görüntünün karşılaştırılması, aynı beklemenin devam edip etmediğini görmeye yardımcı olur. Alınamayan görüntüler ve nedenleri ayrıca belirtilir.

## 6. Ayrıntılı Windows tanılama bilgileri toplanır

Bu adım etkinse hedef bilgisayardan şu bilgiler alınır:

- Ağ adaptörleri, IP, MAC ve DNS bilgileri.
- TCP/UDP bağlantıları, port bilgileri ve ağ sayaçları.
- Ağ yönlendirme ve adres eşleştirme bilgileri.
- Çalışan süreçler, servisler ve sürücüler.
- Kullanıcı oturumları ve saat servisi durumu.
- Son 48 saatteki kritik Windows olayları, hata ve uyarılar; Sistem ve Uygulama günlüklerinden en fazla 100'er kayıt.

Uzak modda bu bilgiler sunucuya, yerel modda programın açık olduğu bilgisayara aittir. Bir bilgiye erişilemezse alınabilen diğer sonuçlar korunur.

## 7. Ağdaki cihazlara erişim kontrol edilir

Ping adımı etkinse önceden belirlenen IP adreslerine veya IP ağına ping gönderilir.

Her hedef için yanıt alınıp alınmadığı, başarılı deneme sayısı, paket kaybı ve yanıt süresi kaydedilir. Sonuçlar toplu bir liste ve özet halinde hazırlanır.

**Uzak modda ping sunucudan gönderilir.** Bu nedenle sonuç, sunucunun ilgili cihaza erişimini gösterir. Yerel modda ise bu bilgisayarın erişimi ölçülür. Ping yanıtı alınamaması cihazın kapalı olduğunu tek başına göstermez.

## 8. COM portundan VxWorks cihazına seri bağlantı kurulur

VxWorks toplama etkinse hedef bilgisayardaki COM portları kullanılır. Ayara göre portlar otomatik bulunur veya önceden belirlenen port listesi denenir. Birden fazla port varsa sırayla işlem yapılır.

Her port için şu hazırlıklar yapılır:

1. Cihazla uyumlu seri bağlantı ayarları kullanılarak port açılmaya çalışılır.
2. Seri kanaldan cihazın konsol yanıtı beklenir.
3. Cihazın VxWorks olduğu ve sürümü doğrulanır.
4. Cihazın ağ arayüzü, IP ve MAC bilgileri alınmaya çalışılır.
5. Kuruluma özel bir cihaz komutu tanımlanmışsa bu komutun çıktısı ayrıca alınır.

VxWorks komutları, **COM portu üzerinden seri kanala bağlı cihazda** çalışır. Sunucu veya yerel bilgisayar bu seri bağlantıyı sağlar ve gelen yanıtları kaydeder.

Port açılamazsa, kullanımda ise veya cihaz doğrulanamazsa neden kaydedilir. VxWorks olduğu doğrulanmayan cihazda sonraki tanılama komutlarına geçilmez. Diğer portlar, kalan çalışma süresi içinde denenir.

## 9. VxWorks task'ları tek tek incelenir

Önce cihazdan güncel **task listesi** alınır. Bu listede cihazın sunduğu task adları, task kimlikleri, öncelikleri ve çalışma durumları bulunur.

**İlk listede görülen bütün task'lar için sırayla inceleme denenir.** Kullanıcının task seçmesine veya her task için ayrı komut girmesine gerek yoktur.

| İnceleme | Yapılan işlem | Elde edilmek istenen bilgi |
| --- | --- | --- |
| Task listesi | `i` ile ilk liste alınır. | O anda mevcut task'lar ve durumları. |
| Her task'ın çağrı yığını | Her task kimliği için `tt` denenir. | Task'ın hangi çağrı veya işlem üzerinde bulunduğu. |
| Her task'ın ayrıntıları | Her task kimliği için `ti` denenir. | Cihazın sağladığı task, yığın ve işlemci kayıt bilgileri. |
| Liste değişiklikleri | Task incelemesinden sonra `i` yeniden alınır. | İlk listeye göre eklenen veya kaybolan task'lar. |
| Bekleyen task'lar | İlk listede bekleme durumunda görülen task'lar için `tw` denenir. | Task'ın beklediği nesne veya kaynağa ilişkin bilgi. |

Her task için alınabilen çıktılar ayrı saklanır. Bir task'ın yığın bilgisi alınamazsa, bağlantı ve süre sınırları elverdiğinde task'ın ayrıntıları ve diğer task'lar da denenir.

**Bütün task'ların bilgisinin mutlaka alınacağı garanti edilmez.** Cihazın desteklemediği işlemler, yanıt vermemesi, bağlantı sorunu, süre veya çıktı sınırı nedeniyle inceleme eksik kalabilir. Kaç task bulunduğu, kaçının denendiği ve kaçından bilgi alınabildiği raporda gösterilir.

## 10. VxWorks cihazının ek durumu kaydedilir

Task incelemesine ek olarak cihazın desteklediği ölçüde şu bilgiler alınmaya çalışılır:

- Saat/zaman bilgisi ve yüklü modüller.
- Task'ların yığın kullanımı ve genel task özeti.
- Cihaz/aygıt listesi ve kullanıcı/oturum bilgileri.
- TCP/UDP bağlantıları ve dinlenen portlar.
- Ağ protokollerinin istatistikleri ve yönlendirme bilgileri.
- Ağ dosya sistemi bağlantıları ve bellek kullanımı.

Seri konsol yanıtları ve task çıktıları, ilgili COM portuna göre ayrı kaydedilir. Böylece hangi bilginin hangi porttaki cihazdan geldiği takip edilebilir.

Alınamayan veya desteklenmeyen bilgiler ayrıca belirtilir. Örneğin port tablosu okunamadığında bu sonuç, cihazda açık port olmadığı şeklinde değerlendirilmez.

## 11. Uygulama logları kopyalama için hazırlanır

Kurulumda bir log hazırlama veya dışa aktarma işlemi tanımlanmışsa hedef bilgisayarda çalıştırılır. Uzak modda sunucuda, yerel modda bu bilgisayarda yürütülür.

Hazırlık işleminin tamamlanması beklenir. Başarılı olursa logların yazılabilmesi için ayarlanan ek bekleme süresi de uygulanır. Böyle bir hazırlık tanımlanmamışsa doğrudan log toplamaya geçilir.

Hazırlık başarısız olursa veya süresinde tamamlanmazsa uygulama loglarının kopyalanması atlanır; daha önce alınmış tanılama bilgileri raporda korunur. Hazırlık işlemi açık bir masaüstü oturumu gerektiriyorsa ilgili oturumun açık ve kilitsiz olması gerekir.

## 12. Belirlenen log dosyaları alınır

Önceden seçilmiş klasörler ve tek dosya kaynakları taranır. Ayarlara göre alt klasörlere de bakılır.

Hangi dosyaların alınacağı şu seçimlere bağlıdır:

- Dosya uzantısı.
- Son değiştirilme zamanı.
- Dosya başına boyut sınırı.
- Alt klasörlerin dahil edilip edilmemesi.

Boyut sınırı tek bir dosya için uygulanır. Sınırı aşan dosyalar ve erişilemeyen kaynaklar raporda belirtilir.

Uzak modda sunucudan alınan dosyalar programın açık olduğu bilgisayara aktarılır. Yerel modda bu bilgisayardaki dosyalar rapora kopyalanır. Kopyalama sırasında dosyada değişiklik tespit edilirse bu da belirtilir.

## 13. Sonuçlar birleştirilir ve rapor hazırlanır

Toplanan bilgiler programın açık olduğu bilgisayarda bir araya getirilir. Java iş parçacıklarındaki beklemeler ve kilitlenme belirtileri, loglardaki hata kayıtları, ping sonuçları ve VxWorks bulguları için özetler hazırlanır.

Rapor; alınabilen dosyaları, başarılı ve başarısız adımları, başlangıç/bitiş zamanlarını ve geçen süreleri içerir. Özetler incelemeye yardımcı olur; tek başına arızanın kesin nedenini belirlediği anlamına gelmez.

Bütün bilgiler **tek bir şifreli ZIP dosyasında** toplanır ve dosyanın içeriği doğrulanır. Rapor her iki modda da programın açık olduğu bilgisayardaki rapor klasörüne kaydedilir. Tamamlanan işe ait geçici çalışma dosyaları temizlenir; temizlik tamamlanamazsa uyarı verilir.

Destek ekibi rapor içinde şu özetlerden yararlanabilir:

| Dosya | İçerik |
| --- | --- |
| `analiz-ozeti.txt` | Java iş parçacığı incelemesinin özeti. |
| `log-hata-ozeti.txt` | Alınan uygulama loglarında bulunan hata kayıtlarının özeti. |
| `server/SONUC.txt` | Ayrıntılı Windows tanılama sonuçlarının özeti; ilgili adım etkinse bulunur. |
| `ping/SONUC.txt` | Ping erişim sonuçlarının özeti; ping etkinse bulunur. |
| `vxworks/SONUC.txt` | COM portları, cihazlar ve task incelemelerinin özeti; VxWorks toplama etkinse bulunur. |
| `vxworks/task-listesi.csv` | Task'lar ve her task için bilgi alınma durumu. |
| `vxworks/socket-port-listesi.csv` | Cihazlardan alınabilen bağlantı ve port bilgileri. |
| `rapor-zamanlari.txt` | Raporun zaman bilgileri ve süreleri. |

## 14. Kullanıcı raporu destek ekibiyle paylaşır

Rapor hazır mesajından sonra **Çıktı Klasörünü Aç** düğmesine basın. Oluşan ZIP dosyasını destek ekibine iletin.

“Bazı bilgiler alınamadı” uyarısı varsa oluşan raporu yine paylaşın. Bu, bazı adımların eksik kaldığını gösterir; alınabilen bilgiler raporda bulunur.

İşlem başarısız olur ve rapor oluşmazsa ekranda belirtilen hata kaydı dosyasını destek ekibine iletin.

**Ayarlar menüsü yalnızca admin ekranındadır.** Sunucu adresi, kaynak klasörler, COM portları veya diğer seçimlerin değişmesi gerekiyorsa sorumlu kişiye başvurun.
