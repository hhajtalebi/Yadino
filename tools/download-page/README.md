<div dir="rtl">

# صفحه دریافت یادینو

`build_page.py` صفحه دانلود (`index.html` در ریشه مخزن) را از روی پوشه `release/` می‌سازد:

- دکمه دانلود APK اصلی و پیوند نسخه اشکال‌زدایی
- راهنمای نصب و به‌روزرسانی به فارسی
- کد QR برای باز کردن صفحه روی گوشی (نیازمند بسته `segno`؛ بدون آن، کد QR حذف می‌شود)
- فهرست موارد قابل آزمون و راهنمای گزارش خطا
- تازه‌های نسخه (از بخش «امکانات تازه» در `CHANGELOG.md`)
- مشخصات فایل‌ها: حجم، SHA-256 و گواهی امضا (با apksigner بررسی می‌شود، اگر ابزار ساخت آفلاین نصب باشد)

```bash
pip install segno                                  # اختیاری، برای کد QR
python3 tools/download-page/build_page.py          # index.html + .nojekyll
python3 tools/download-page/serve.py --port 8080   # پیش‌نمایش محلی
```

`serve.py` فقط صفحه، APKهای فهرست‌شده در `release/SHA256SUMS.txt`، همین فایل و دو فونت را برمی‌گرداند؛ نه بقیه مخزن را. کد QR صفحه پیش‌نمایش به همان نشانی‌ای اشاره می‌کند که صفحه با آن باز شده است.

برای انتشار با GitHub Pages، منبع را «Deploy from a branch» و پوشه `/ (root)` بگذارید (جزئیات در [docs/BUILD.md](../../docs/BUILD.md)).

</div>
