<div dir="rtl">

# ساخت بدون اینترنت و بدون Gradle (ابزار ساخت آفلاین)

> راه رسمی ساخت یادینو **Gradle و Android Studio** است (راهنما: [`docs/BUILD.md`](../../docs/BUILD.md)).
> این پوشه یک خط ساخت جایگزین دارد. با آن، برنامه در محیطی کامپایل، آزموده و بسته‌بندی شد که به Maven Central، Google Maven و services.gradle.org دسترسی نداشت.

همه ورودی‌ها از آینه‌های عمومی گیت‌هاب برای فایل‌های آماده AOSP و از مخازن اصلی کتابخانه‌ها با برچسب (tag) دقیق دریافت می‌شوند (فهرست کامل در `fetch-toolchain.sh`):

| ورودی | منبع |
|---|---|
| JDK 17.0.4 | `platform/prebuilts/jdk/jdk17` |
| کامپایلر Kotlin 2.0.21، افزونه کامپایلر Compose و kapt | `platform/external/kotlinc` و `prebuilts/tools` (کامپایلر embeddable) |
| android.jar (API 35)، aapt2، zipalign و apksigner | `platform/prebuilts/sdk` |
| کتابخانه‌های AndroidX (Compose، Room، WorkManager، DataStore و …) | `platform/prebuilts/sdk/current/androidx` |
| R8 8.11 | `platform/prebuilts/r8` |
| AndroidX Test (فقط برای کامپایل آزمون‌های ابزاری) | `platform/prebuilts/misc` |
| kotlinx.coroutines 1.9.0، okio، kotlinpoet، javapoet، KSP API، auto-common، kotlinx.serialization 1.7.3، kotlin-metadata-jvm 2.0.21 و JUnit | کامپایل از کد منبع با `build-deps.sh` |

مراحل ساخت در `build.py`:

```
aapt2 compile/link → javac (R، BuildConfig) → kapt + پردازشگر Room → kotlinc + Compose
→ javac (کد تولیدشده Room) → R8 (dex) → zip → zipalign → apksigner
```

روش استفاده:

```bash
./fetch-toolchain.sh                   # حدود ۱٫۲ گیگابایت در ~/.cache/yadino-toolchain (متغیر YADINO_TC)
./build-deps.sh                        # کامپایل کتابخانه‌هایی که فایل آماده ندارند
python3 build.py test                  # آزمون‌های واحد JVM (تقویم، دامنه، برنامه)
python3 build.py apk                   # APK اشکال‌زدایی ← build/offline/yadino-<نسخه>-debug.apk
python3 build.py androidtest           # کامپایل آزمون‌های ابزاری (بدون نیاز به دستگاه)
python3 build.py linkcheck             # بررسی سازگاری باینری برنامه کوچک‌شده
python3 build.py lint                  # Android Lint (cmdline-tools) ← build/offline/lint/{lint.txt,lint.html,lint.xml}
YADINO_KEYSTORE=… YADINO_KEYSTORE_PASSWORD=… python3 build.py release   # APK انتشار
```

نکته‌ها:

- در محیطی تازه، پیش از `test` یک بار `apk` را اجرا کنید؛ آزمون‌های JVM برنامه به کلاس‌های کامپایل‌شده برنامه نیاز دارند. پس از تغییر ماژول‌های `core`، اول `test` را اجرا کنید تا آن ماژول‌ها دوباره ساخته شوند.
- `debug.keystore` **کلید عمومی امضای مخزن** است (رمز `android`، نام مستعار `androiddebugkey`). از نسخه ۱٫۳٫۱ هر دو APK پوشه `release/` با همین کلید امضا می‌شوند تا هر نسخه آزمایشی تازه روی نسخه قبلی نصب شود. برای انتشار عمومی از کلید خصوصی خودتان استفاده کنید (راهنما در [`docs/BUILD.md`](../../docs/BUILD.md)):

```bash
YADINO_KEYSTORE=tools/offline-build/debug.keystore YADINO_KEYSTORE_PASSWORD=android \
YADINO_KEY_ALIAS=androiddebugkey python3 tools/offline-build/build.py release
```

</div>
