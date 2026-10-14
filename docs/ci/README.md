<div dir="rtl">

# پیکربندی CI (GitHub Actions)

فایل [`android-ci.yml`](android-ci.yml) یک گردش‌کار کامل برای گیت‌هاب است:

1. **build**: اجرای ۱۰۸ آزمون واحد JVM، ساخت APK اشکال‌زدایی و بررسی Lint؛ APK و گزارش‌ها به‌عنوان Artifact ذخیره می‌شوند.
2. **instrumented**: اجرای آزمون‌های ابزاری روی شبیه‌سازهای اندروید ۸ (API 26)، ۱۲ (API 31) و ۱۵ (API 35).

## چرا این فایل در `.github/workflows` نیست؟

حساب خودکاری که این نسخه را در مخزن ثبت کرد اجازه ایجاد یا تغییر گردش‌کارهای GitHub Actions (مجوز `workflows`) را نداشت. برای فعال‌سازی کافی است مالک مخزن یکی از این کارها را انجام دهد:

### روش ۱: از طریق وب‌سایت گیت‌هاب
1. در صفحه مخزن، روی **Add file › Create new file** بزنید.
2. نام فایل را `.github/workflows/android-ci.yml` بگذارید.
3. محتوای [`docs/ci/android-ci.yml`](android-ci.yml) را کپی و **Commit** کنید.

### روش ۲: از خط فرمان
```bash
mkdir -p .github/workflows
cp docs/ci/android-ci.yml .github/workflows/android-ci.yml
git add .github/workflows/android-ci.yml
git commit -m "Add Android CI workflow"
git push
```

## امضای نسخه انتشار در CI (اختیاری)

برای ساخت APK امضاشده در CI، کلید را به‌صورت Base64 در Secrets مخزن ذخیره کنید (`YADINO_KEYSTORE_BASE64`، `YADINO_KEYSTORE_PASSWORD`، `YADINO_KEY_ALIAS`) و پیش از `assembleRelease` آن را بازسازی کنید:

```yaml
- name: Decode keystore
  run: echo "${{ secrets.YADINO_KEYSTORE_BASE64 }}" | base64 -d > $RUNNER_TEMP/release.jks
- name: Release APK
  env:
    YADINO_KEYSTORE: ${{ runner.temp }}/release.jks
    YADINO_KEYSTORE_PASSWORD: ${{ secrets.YADINO_KEYSTORE_PASSWORD }}
    YADINO_KEY_ALIAS: ${{ secrets.YADINO_KEY_ALIAS }}
  run: ./gradlew :app:assembleRelease
```

</div>
