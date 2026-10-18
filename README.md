# NotaDinle

Basılı nota sayfasının fotoğrafını veya PDF/görsel dosyasını seçip müziği piyano ya da gitar sesiyle dinlemeyi hedefleyen Android uygulaması prototipi.

## Bu prototipte

- Kamera ile nota fotoğrafı çekme
- Cihazdan PDF veya görsel seçme
- Seçilen görsel için küçük önizleme gösterme
- Piyano veya gitar tınısıyla uygulama içinde üretilen kısa Do Majör örneğini çalma
- Örnek parçanın temposunu 60–140 BPM arasında değiştirme
- Nota tanımanın henüz hazır olmadığını uygulamada açıkça belirtme

**Önemli:** Bu sürüm yüklenen notayı henüz okumaz veya çalmaz. Çal düğmesi, uygulamanın yerleşik kısa örnek dizisini çalar. Gerçek nota tanıma (OMR), tanınan notaları düzeltme ve seçilen sayfayı çalma sonraki geliştirme adımlarıdır. PDF seçilebilir; PDF önizlemesi henüz yoktur.

## Teknik yapı

- Kotlin, Jetpack Compose ve Material 3
- Cihazda örnek ses üretimi için Android `AudioTrack`
- Paket adı: `com.notadinle.app`
- Android API 26 ve üzeri; derleme SDK'sı API 37
- Android Gradle Plugin 9.2.1, Kotlin Compose plugin 2.3.10

## Android Studio'da açma

Proje klasörünü güncel Android Studio'da açın. Gradle JDK olarak JDK 17 veya üzerini seçin ve Android SDK Platform 37 kurulu olsun. Bu prototipte Gradle Wrapper dosyaları henüz eklenmedi; sonraki adımda projeye sabitlenmiş Gradle dağıtımıyla wrapper eklenmelidir.

## Önerilen geliştirme sırası

1. Tek porte içeren temiz basılı notalarda optik nota tanımayı (OMR) eklemek.
2. Tanınan notaları çalmadan önce düzenlenebilir bir ekranda göstermek.
3. PDF sayfalarını önizlemek ve çok sayfalı belgeleri desteklemek.
4. Daha doğal piyano/gitar sesleri ve çalma kontrolleri eklemek.

Belirsiz nota okumaları, kullanıcıya düzenleme şansı verilmeden sessizce çalınmamalıdır.
