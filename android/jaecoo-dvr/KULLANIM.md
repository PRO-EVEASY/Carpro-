# Kaynak paketi ve kullanım

## İçerik
Java arayüz/servis, EGL/OpenGL lens düzeltme ve YUV yolu, MediaCodec/MediaMuxer DVR, kayıt oynatıcı, depolama/olay kilidi, otomasyon, Camera2/HAB adaptörleri ve kendi native C kaynaklarımız. Derlenmiş `.so`, APK, DEX/JAR, ADB aracı, kurulum yardımcısı, imza anahtarı, araç dökümü ve kayıt videosu yoktur. Alıcı kendi native kitaplığını ve APK'sını derler. Üretici sürücüsü ya da harici APK bu arşive dahil değildir.

## Derleme
1. JDK 17+, Android SDK Platform 34, Build Tools 34+ ve NDK 28 kurun.
2. `ANDROID_SDK_ROOT` ve `JAVA_HOME` ortam değişkenlerini kendi kurulumunuza ayarlayın.
3. Windows'ta `powershell -ExecutionPolicy Bypass -File build.ps1 -Unsigned` kaynakları derler. İmzalama seçenekleri BUILDING.md içindedir. Android Studio/Gradle için ayrı tarif de vardır; wrapper JAR dahil değildir.
4. Alıcı APK'yı kendi yetkili kurulum yöntemini kullanarak yükler. Paket herhangi bir ADB kilidi açma yöntemi içermez.

## İlk kullanım ve farklı araçlar
Kamera ve isteğe bağlı konum izinlerini verin. Ayarlar → Kamera kaynağı bölümünde kaynak seçilir. Otomatik seçim yalnız tanımlı DesaySV donanım kimliğinde HAB kullanır; diğer cihazlarda Camera2 denenir. Camera2 varsayılan olarak tek görünen kamerayı seçer; en fazla dört kimlik girilebilir, fakat eşzamanlı açılmaları cihazın desteğine bağlıdır.

Standart Camera2 görüntüsüne Jaecoo balıkgözü profili otomatik uygulanmaz. Kamera2 için lens düzeltmesini ayrıca etkinleştirmek ve o kameraya göre kalibre etmek gerekir. Normalizasyon 1280×960 UYVY merkez kırpma kullanır; sensör yönü keşif ekranında raporlanır ve araç adaptörü görüntü yönünü doğrulamalıdır. Yeni özel kamera protokolü için CaptureBackend uygulanır. Android bulunması AVM kameralarına erişim garantisi vermez.

HAB profili donanım H.264 kodlayıcısını kullanır. Camera2 önce donanım kodlayıcıyı, yoksa yazılım kodlayıcıyı dener; kaynak 15 FPS bildirirse kayıt hedefi 15'e iner. Bu durumda 25 FPS üretildiği iddia edilmez. Tanı ekranı kayıt ve önizleme ölçümlerini ayrı gösterir.

## Kayıt ve otomasyon
Önerilen mevcut araç profili: FOV 100°, GPU, dört kamera toplam 1920×1440, H.264 12 Mbit/s, 25 FPS hedefi, 60 saniye parça. Canlı önizleme hedefi artık 25 FPS'tir ve eski 10 FPS tercihi bu sürümde bir defa yükseltilir. Kullanıcı sonra 10/15/20/25 seçebilir.

Dahili kota 2 GiB, boş alan rezervi 512 MiB; USB kotası 8 GiB. Yalnız uygulamanın kilitsiz tamamlanmış klipleri döngüye girer. Olay düğmesi önceki, mevcut ve sonraki parçayı korur. Aniden enerji kesilirse kapanmamış MP4 zarar görebilir; daha önce tamamlanan parçalar korunur. Videolar sessizdir.

Otomatik kayıt ilk kurulumda kapalıdır. İzinleri ve kamera kaynağını doğruladıktan sonra Ayarlar → Otomatik kayıt açılır. Geçerli kontak verisi varsa esas alınır; yoksa Android uyanma/uyku sinyali açıkça yedek kaynak olarak belirtilir. Başlatma 2 sn, kapatma gecikmesi varsayılan 20 sn. P/N tek başına durdurma sebebi değildir. Gerçek kontak döngüsü hedef araçta park halinde sınanmalıdır.

Hız yalnız güncelse gösterilir; CAN hızı yoksa etiketli GPS yedeği kullanılır. Bilinmeyen vites veya hız uydurulmaz. Video saati cihazın tarih/saatini kullanır. Konum yazısı tercihe bağlıdır.

## Lisans
MIT lisansı kopyalama, değiştirme, ticari kullanım ve yeniden dağıtıma izin verir. Bu paket çoğaltmayı veya fork yapılmasını engellemez. Üretici sürücülerinin hakları devredilmez.
