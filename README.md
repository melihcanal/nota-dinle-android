# NotaDinle

Basılı nota sayfası fotoğrafını veya bir PDF/görsel dosyasını cihaz üzerinde okuyup piyano ya da gitar sesiyle dinlemeyi sağlayan Android uygulaması.

## Özellikler

- Kamera ile tam çözünürlüklü nota fotoğrafı çekme
- Cihazdan görsel veya PDF seçme; PDF'in ilk sayfasını gösterme
- Çevrimdışı, temel nota tanıma
- Tanınan notaları çalmadan önce listeleme ve yarım ses yukarı/aşağı düzeltme
- Tanınan melodiyi piyano veya gitar sesiyle, ayarlanabilir tempoda çalma
- Kısa örnek diziyi çalma

## Nota tanımanın kapsamı

Bu ilk sürüm temiz, yatay, tek sol anahtarı portesi ve tek sesli melodiler için tasarlanmıştır. Nota başlarının porte üzerindeki yüksekliğinden doğal sesleri (C–B) tanır. Ritim şimdilik eşit dörtlük kabul edilir. Diyez/bemol ve donanım, sus işaretleri, akorlar, birden fazla porte, el yazısı ve eğik/perspektifli sayfalar henüz desteklenmiyor. Tanınan her notayı çalmadan önce ekranda kontrol edip yarım ses düzeltmesi yapabilirsin.

Nota görüntüsü sunucuya gönderilmez; tanıma cihazda yapılır.

## Android Studio'da açma

Projeyi Android Studio'da açıp Gradle eşitlemesinin tamamlanmasını bekle. Android SDK Platform 37 ve JDK 17 veya üzeri gerekir. Projede Gradle Wrapper bulunur; komut satırından Windows'ta `gradlew.bat assembleDebug` ile debug APK oluşturabilirsin.

APK çıktısı: `app/build/outputs/apk/debug/app-debug.apk`

## Teknik yapı

- Kotlin, Jetpack Compose ve Material 3
- Basit porte ve nota başı algılama, Android Bitmap işleme ile çevrimdışı yapılır
- PDF'in ilk sayfası Android `PdfRenderer` ile görüntülenir
- `AudioTrack` ile piyano ve gitar için ayrı sentez
- Paket adı: `com.notadinle.app`
- Android API 26 ve üzeri, compile SDK 37
