<div dir="rtl">

# راهنمای ساخت، اجرا و انتشار یادینو

## پیش‌نیازها

| ابزار | نسخه |
|---|---|
| JDK | ۱۷ یا جدیدتر (JBR داخل Android Studio کافی است) |
| Android Studio | نسخه‌های جدید (Ladybug یا بالاتر) |
| Android SDK | Platform 35 (Android 15)، Build-Tools 35 |
| Gradle | 8.10.2 (از طریق Gradle Wrapper خودکار دریافت می‌شود) |
| Android Gradle Plugin | 8.7.3 |
| Kotlin | 2.0.21 (به همراه افزونه Compose Compiler همان نسخه) |
| KSP | 2.0.21-1.0.28 |

نسخه همه کتابخانه‌ها در [`gradle/libs.versions.toml`](../gradle/libs.versions.toml) ثبت شده است:

| کتابخانه | نسخه |
|---|---|
| Compose BOM | 2025.05.01 (Compose 1.8.2، Material 3 1.3.2) |
| Activity Compose | 1.10.1 |
| Core KTX | 1.16.0 |
| Lifecycle | 2.8.7 |
| Room | 2.7.1 |
| WorkManager | 2.10.1 |
| DataStore Preferences | 1.1.7 |
| Browser (Custom Tabs) | 1.8.0 |
| kotlinx.coroutines | 1.9.0 |

## ساخت با Android Studio

1. مخزن را کلون کنید و پوشه `Yadino` را در Android Studio باز کنید (`File › Open`).
2. اجازه دهید همگام‌سازی Gradle کامل شود.
3. پیکربندی `app` را روی دستگاه یا شبیه‌ساز اجرا کنید.

## ساخت از خط فرمان

```bash
./gradlew :app:assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew test                        # آزمون‌های واحد JVM هر سه ماژول
./gradlew :app:lintDebug              # بررسی Lint
./gradlew :app:connectedAndroidTest   # آزمون‌های ابزاری (نیازمند دستگاه یا شبیه‌ساز)
./gradlew :app:assembleRelease        # نسخه انتشار (در صورت تنظیم کلید، امضاشده)
```

نصب روی دستگاه متصل:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## تنظیمات محلی (`local.properties`)

این فایل در Git ثبت نمی‌شود. کلیدهای قابل استفاده:

```properties
sdk.dir=/path/to/Android/sdk

# اختیاری: پشتیبان‌گیری در گوگل‌درایو (راهنما: docs/GOOGLE_DRIVE_SETUP.md)
yadino.drive.clientId=1234567890-abcdefg.apps.googleusercontent.com

# اختیاری: امضای نسخه انتشار
yadino.keystore=/home/me/keys/yadino-release.jks
yadino.keystore.password=********
yadino.key.alias=yadino
yadino.key.password=********
```

همین مقادیر را می‌توان با متغیرهای محیطی هم داد (حروف بزرگ و `.` به `_`)، مثلاً `YADINO_KEYSTORE_PASSWORD` — مناسب CI.

اگر `yadino.drive.clientId` تنظیم نشود، برنامه بدون مشکل ساخته می‌شود و بخش گوگل‌درایو در تنظیمات با توضیح «پیکربندی نشده» نمایش داده می‌شود؛ پشتیبان فایل همچنان کار می‌کند.

## امضای نسخه انتشار

ساخت کلید جدید (یک بار؛ کلید و رمز را در جای امن نگه دارید — بدون آن انتشار به‌روزرسانی ممکن نیست):

```bash
keytool -genkeypair -v -keystore yadino-release.jks -storetype PKCS12 \
  -alias yadino -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=Yadino, O=Yadino, C=IR"
```

سپس مسیر و رمز را در `local.properties` بنویسید و `./gradlew :app:assembleRelease` (یا `bundleRelease` برای Google Play) را اجرا کنید. نسخه انتشار با R8 کوچک‌سازی و مبهم‌سازی می‌شود؛ قواعد لازم در `app/proguard-rules.pro` و قواعد کتابخانه‌ها آمده است.

### APK‌های پوشه `release/` (نسخه‌های آزمایشی)

از نسخه ۱٫۳٫۱، هر دو APK موجود در `release/` (انتشار و اشکال‌زدایی) با **کلید عمومی مخزن** امضا می‌شوند: `tools/offline-build/debug.keystore` (رمز `android`، نام مستعار `androiddebugkey`، اثر انگشت SHA-256 گواهی `d8f45427…56fb6e`).

- **چرا؟** کلید خصوصی‌ای که فقط در محیط ساخت نگه‌داری شود، با بازسازی آن محیط از دست می‌رود؛ این اتفاق برای نسخه‌های ۱٫۰ تا ۱٫۲ افتاد و نسخه ۱٫۳٫۰ ناچار با کلید دیگری امضا شد. کلید ثبت‌شده در مخزن همیشه در دسترس است، پس هر نسخه آزمایشی تازه روی نسخه قبلی نصب می‌شود و اطلاعات آزمایش‌کننده حفظ می‌شود.
- **محدودیت:** این کلید عمومی است و هر کسی می‌تواند با آن APK بسازد. پس فقط برای آزمایش مناسب است و آزمایش‌کننده باید فایل‌ها را فقط از صفحه دریافت یا همین مخزن بگیرد.
- **ساخت با کلید مخزن:**

```bash
YADINO_KEYSTORE=tools/offline-build/debug.keystore YADINO_KEYSTORE_PASSWORD=android \
YADINO_KEY_ALIAS=androiddebugkey python3 tools/offline-build/build.py release
```

### انتشار عمومی

برای انتشار در فروشگاه‌ها (مثلاً کافه‌بازار، مایکت یا Google Play) یا انتشار عمومی، یک بار کلید خصوصی خودتان را بسازید (دستور بالا)، آن را همراه رمزش در جای امنی بیرون از مخزن نگه دارید و همه نسخه‌های بعدی را با همان کلید امضا کنید. در CI می‌توانید کلید را به‌صورت Base64 در GitHub Secrets بگذارید و با متغیرهای `YADINO_KEYSTORE*` به ساخت بدهید (نمونه در [docs/ci/README.md](ci/README.md)). برنامه‌ای که با کلید آزمایشی نصب شده، با کلید خصوصی شما به‌روزرسانی نمی‌شود؛ کاربران آزمایشی یک بار باید پشتیبان بگیرند، برنامه را حذف و دوباره نصب کنند و پشتیبان را بازیابی کنند.

## صفحه دریافت (`index.html`)

صفحه‌ای فارسی برای دانلود و نصب APK روی گوشی، همراه راهنمای نصب، کد QR، فهرست موارد قابل آزمون، راهنمای گزارش خطا و مشخصات فایل‌ها. این صفحه از روی `release/SHA256SUMS.txt`، شماره نسخه `tools/offline-build/build.py` و بخش «امکانات تازه» در `CHANGELOG.md` ساخته می‌شود:

```bash
pip install segno                                   # فقط برای کد QR (اختیاری)
python3 tools/download-page/build_page.py           # ساخت index.html و .nojekyll در ریشه مخزن
python3 tools/download-page/serve.py --port 8080    # پیش‌نمایش محلی (فقط صفحه، APKها و فونت‌ها)
python3 tools/download-page/build_page.py --standalone   # release/yadino-<نسخه>-download.html
```

- **صفحه تک‌فایلی** (`--standalone`): یک فایل HTML که APK اصلی و فونت‌ها را به‌صورت Base64 در خود دارد. دکمه دانلود، فایل را در مرورگر به APK برمی‌گرداند (Blob)؛ به سرور یا اینترنت نیازی نیست. اگر اندازه داده درون فایل درست نباشد (فایل در راه ناقص شده باشد)، پیام خطا نمایش داده می‌شود. `tools/download-page/test_standalone.js` این رفتار را در jsdom بررسی می‌کند.

- **انتشار با GitHub Pages**: در تنظیمات مخزن ← Pages، منبع را «Deploy from a branch»، شاخه‌ای که `index.html` در آن است و پوشه `/ (root)` بگذارید. نشانی صفحه `https://hhajtalebi.github.io/Yadino/` است و APKها از همان نشانی (`release/…`) دانلود می‌شوند. فایل `.nojekyll` باعث می‌شود فایل‌ها همان‌طور که هستند منتشر شوند.
- پس از هر انتشار و به‌روز کردن `release/`، صفحه را دوباره بسازید.

## پایگاه داده و مهاجرت‌ها

- طرح‌واره (Schema) هر نسخه پایگاه داده با KSP در `app/schemas/` خروجی گرفته و در Git ثبت می‌شود.
- نسخه فعلی طرح‌واره **۲** است (از یادینو ۱٫۳؛ جدول‌های قالب برنامه). مهاجرت ۱ به ۲ در `data/local/Migrations.kt` تعریف شده است.
- با هر تغییر طرح‌واره: `YadinoDatabase.VERSION` را افزایش دهید، SQL مهاجرت را در `MigrationSql` بنویسید، `Migration` آن را به `Migrations.ALL` اضافه کنید و آزمون‌ها را اجرا کنید:
  - `RoomSchemaTest` (JVM) بررسی می‌کند که برای هر نسخه فایل طرح‌واره وجود دارد و با `VERSION` یکی است؛
  - `DatabaseMigrationTest` (JVM) SQL مهاجرت را روی SQLite واقعی و پایگاه داده‌ای با داده نمونه اجرا می‌کند و نتیجه را با طرح‌واره خروجی نسخه مقصد مقایسه می‌کند (جدول‌ها، ستون‌ها، کلیدها، کلیدهای خارجی و ایندکس‌ها)؛
  - `MigrationTest` (ابزاری) همه مهاجرت‌ها را روی دستگاه اجرا و با اعتبارسنجی خود Room مقایسه می‌کند.
- حالت «حذف و ساخت مجدد» (`fallbackToDestructiveMigration`) عمداً فعال نیست تا اطلاعات کاربر هرگز بی‌صدا پاک نشود.

## ساخت بدون اینترنت و بدون Gradle (ابزار `tools/offline-build`)

نسخه‌های موجود در `release/` در محیطی ساخته شده‌اند که به Maven Central / Google Maven دسترسی نداشت. برای همین یک خط ساخت مستقل (hermetic) نوشته شد که همان مراحل Gradle را با ابزارهای رسمی انجام می‌دهد:

```
aapt2 (منابع) → kapt + Room (کد پایگاه داده) → kotlinc + Compose Compiler → javac
→ R8 (dex، کوچک‌سازی) → zipalign → apksigner (امضای v2/v3)
(جداگانه: Android Lint رسمی از بسته cmdline-tools)
```

همه ورودی‌ها از آینه‌های گیت‌هاب AOSP و مخازن رسمی با نسخه‌های قفل‌شده دریافت می‌شوند:

```bash
tools/offline-build/fetch-toolchain.sh    # JDK 17، Kotlin 2.0.21، SDK 35، AndroidX، R8 و …
tools/offline-build/build-deps.sh         # کامپایل coroutines، okio، kotlinpoet و … از سورس
python3 tools/offline-build/build.py test        # آزمون‌های JVM
python3 tools/offline-build/build.py apk         # APK اشکال‌زدایی
python3 tools/offline-build/build.py androidtest # کامپایل آزمون‌های ابزاری
python3 tools/offline-build/build.py linkcheck   # بررسی سازگاری باینری کتابخانه‌ها
python3 tools/offline-build/build.py lint        # Android Lint (گزارش‌ها در build/offline/lint)
YADINO_KEYSTORE=… YADINO_KEYSTORE_PASSWORD=… python3 tools/offline-build/build.py release   # کلید مخزن یا کلید خودتان (بخش امضا)
```

این ابزار فقط برای محیط‌های محدود است؛ مسیر رسمی و پیشنهادی ساخت، Gradle و Android Studio است. تفاوت‌ها:

| | Gradle | ابزار آفلاین |
|---|---|---|
| پردازش Room | KSP (کد Kotlin) | kapt (کد Java) |
| نسخه AndroidX | نسخه‌های پایدار کاتالوگ | نسخه‌های AOSP (Compose 1.9.0-alpha01، Room 2.7.0-beta01 و …) |
| ادغام Manifest | ادغام‌گر رسمی AGP | ادغام‌گر ساده‌شده (کافی برای کتابخانه‌های پروژه) |

کد برنامه فقط از APIهایی استفاده می‌کند که در هر دو مجموعه نسخه موجودند.

## رشته‌های فارسی

همه متن‌های رابط کاربری در `app/src/main/res/values/strings.xml` هستند. این فایل از جدول‌های `tools/i18n/strings_fa_part*.py` تولید می‌شود:

```bash
python3 tools/i18n/gen_strings.py   # تولید strings.xml و بررسی کلیدهای استفاده‌نشده یا جاافتاده
```

</div>
