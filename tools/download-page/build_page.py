#!/usr/bin/env python3
"""Builds the Yadino download page (index.html at the repository root) from release/.

The page offers the APKs listed in release/SHA256SUMS.txt with a Persian installation guide,
a QR code for opening the page on a phone, what to test, how to report problems and the
file details (size, SHA-256, signing certificate). Version and date come from
tools/offline-build/build.py and CHANGELOG.md, the highlights from the "امکانات تازه"
sections of CHANGELOG.md for the current minor version.

    python3 tools/download-page/build_page.py              # writes index.html
    python3 tools/download-page/build_page.py --url URL    # QR code for another address

The QR code needs the pure-Python package `segno` (pip install segno); without it the QR
block is left out. serve.py uses the same renderer for a local preview.
"""
from __future__ import annotations

import argparse
import html
import os
import re
import subprocess
import sys
import urllib.parse
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
REPO = "hhajtalebi/Yadino"
PAGES_URL = "https://hhajtalebi.github.io/Yadino/"
PACKAGE = "ir.yadino.app"
# Certificate of tools/offline-build/debug.keystore, the public repository key that signs the
# APKs in release/ (see docs/BUILD.md). Checked with apksigner when the toolchain is present.
REPO_KEY_SHA256 = "d8f4542747b7bcecd8adb4e1f0d92686f83c8d3fbe1e583d8a252bb2e356fb6e"
FONT_REGULAR = "app/src/main/res/font/vazirmatn_regular.ttf"
FONT_BOLD = "app/src/main/res/font/vazirmatn_bold.ttf"

FA_DIGITS = str.maketrans("0123456789", "۰۱۲۳۴۵۶۷۸۹")


def fa(text: str) -> str:
    """Persian digits, and the Persian decimal separator between digits (1.3.1 -> ۱٫۳٫۱)."""
    return re.sub(r"(?<=\d)\.(?=\d)", "٫", str(text)).translate(FA_DIGITS)


def esc(text: str) -> str:
    return html.escape(str(text), quote=True)


@dataclass
class ApkFile:
    name: str
    size: int
    sha256: str

    @property
    def is_release(self) -> bool:
        return self.name.endswith("-release.apk")

    @property
    def size_text(self) -> str:
        return fa(f"{self.size / (1024 * 1024):.1f}") + " مگابایت"


@dataclass
class ReleaseInfo:
    version: str
    version_code: int
    files: list[ApkFile]

    @property
    def primary(self) -> ApkFile:
        return next((f for f in self.files if f.is_release), self.files[0])

    @property
    def others(self) -> list[ApkFile]:
        return [f for f in self.files if f is not self.primary]


def release_info(root: Path = ROOT) -> ReleaseInfo:
    build_py = (root / "tools/offline-build/build.py").read_text(encoding="utf-8")
    version = re.search(r'^VERSION_NAME = "([^"]+)"', build_py, re.M).group(1)
    code = int(re.search(r"^VERSION_CODE = (\d+)", build_py, re.M).group(1))
    files = []
    for line in (root / "release/SHA256SUMS.txt").read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        digest, name = line.split(None, 1)
        name = name.strip().lstrip("*")
        path = root / "release" / name
        if name.endswith(".apk") and path.is_file():
            files.append(ApkFile(name, path.stat().st_size, digest.lower()))
    if not files:
        raise SystemExit("no APK listed in release/SHA256SUMS.txt")
    return ReleaseInfo(version, code, files)


def changelog(version: str, root: Path = ROOT) -> tuple[str | None, list[tuple[str, list[str]]]]:
    """Release date of [version] and the highlights of every version of the same minor series."""
    text = (root / "CHANGELOG.md").read_text(encoding="utf-8")
    to_ascii = str.maketrans("۰۱۲۳۴۵۶۷۸۹٫", "0123456789.")
    minor = ".".join(version.split(".")[:2])
    date = None
    highlights: list[tuple[str, list[str]]] = []
    for section in re.split(r"^## ", text, flags=re.M)[1:]:
        head, _, body = section.partition("\n")
        m = re.match(r"نسخه\s+([۰-۹0-9٫.]+)\s+—\s+(.+)", head.strip())
        if not m:
            continue
        v = m.group(1).translate(to_ascii)
        if v == version:
            date = m.group(2).strip()
        if ".".join(v.split(".")[:2]) != minor:
            continue
        new = re.search(r"^### امکانات تازه\n(.*?)(?=^### |\Z)", body, re.M | re.S)
        items = re.findall(r"^- \*\*(.+?)\*\*", new.group(1), re.M) if new else []
        if items:
            highlights.append((v, items))
    return date, highlights


def signing_certificate(apk: Path) -> str | None:
    """SHA-256 of the APK's signing certificate via apksigner, if the offline toolchain is installed."""
    tc = Path(os.environ.get("YADINO_TC", Path.home() / ".cache" / "yadino-toolchain"))
    java, signer = tc / "jdk17/linux-x86/bin/java", tc / "sdk/tools/linux/lib/apksigner.jar"
    if not (java.exists() and signer.exists()):
        return None
    env = {k: v for k, v in os.environ.items() if k != "LD_LIBRARY_PATH"}
    try:
        out = subprocess.run([str(java), "-jar", str(signer), "verify", "--print-certs", str(apk)],
                             capture_output=True, text=True, timeout=120, env=env).stdout
    except (OSError, subprocess.SubprocessError):
        return None
    m = re.search(r"certificate SHA-256 digest: ([0-9a-f]{64})", out)
    return m.group(1) if m else None


def git_ref(root: Path = ROOT) -> str:
    try:
        ref = subprocess.run(["git", "rev-parse", "--abbrev-ref", "HEAD"], cwd=root,
                             capture_output=True, text=True, timeout=10).stdout.strip()
    except (OSError, subprocess.SubprocessError):
        ref = ""
    return ref if ref and ref != "HEAD" else "main"


def qr_svg(url: str) -> str | None:
    try:
        import segno
    except ImportError:
        return None
    return segno.make(url, error="m").svg_inline(scale=6, border=2, dark="#1F5C45", light="#FFFFFF", omitsize=True, title="QR")


# The launcher icon (res/drawable/ic_launcher_background.xml + ic_launcher_foreground.xml),
# cropped to the visible 72dp of the 108dp adaptive icon.
ICON_SVG = (
    '<svg xmlns="http://www.w3.org/2000/svg" viewBox="18 18 72 72" role="img" aria-label="یادینو">'
    '<defs><clipPath id="r"><rect x="18" y="18" width="72" height="72" rx="16"/></clipPath></defs>'
    '<g clip-path="url(#r)">'
    '<path fill="#1F5C45" d="M0,0h108v108h-108z"/><path fill="#246A50" d="M0,0h108v54h-108z"/>'
    '<path fill="#FBF6E9" d="M39,33 H69 A8,8 0 0 1 77,41 V71 A8,8 0 0 1 69,79 H39 A8,8 0 0 1 31,71 V41 A8,8 0 0 1 39,33 Z"/>'
    '<path fill="#C9A227" d="M39,33 H69 A8,8 0 0 1 77,41 V46 H31 V41 A8,8 0 0 1 39,33 Z"/>'
    '<path fill="#FBF6E9" d="M41,26 H44 A2,2 0 0 1 46,28 V37 A2,2 0 0 1 44,39 H41 A2,2 0 0 1 39,37 V28 A2,2 0 0 1 41,26 Z"/>'
    '<path fill="#FBF6E9" d="M64,26 H67 A2,2 0 0 1 69,28 V37 A2,2 0 0 1 67,39 H64 A2,2 0 0 1 62,37 V28 A2,2 0 0 1 64,26 Z"/>'
    '<path fill="none" stroke="#1F5C45" stroke-width="6" stroke-linecap="round" stroke-linejoin="round" d="M42,62 L50,70 L66,54"/>'
    "</g></svg>"
)

CSS = """
@font-face{font-family:Vazirmatn;src:url(%(regular)s) format("truetype");font-weight:400;font-display:swap}
@font-face{font-family:Vazirmatn;src:url(%(bold)s) format("truetype");font-weight:700;font-display:swap}
:root{--green:#1F5C45;--green2:#246A50;--cream:#FBF6E9;--gold:#C9A227;--gold-ink:#2B2000;--ink:#1B1F1C;
--muted:#5B635E;--card:#FFFDF7;--line:#E9E3D2;--soft:#F5EEDD;--link:#1F5C45}
@media (prefers-color-scheme:dark){:root{--cream:#0F1A15;--card:#16231D;--ink:#E3E9E4;--muted:#A9B7AE;
--line:#2F3C36;--soft:#1A2721;--link:#8FD3B0}}
*{box-sizing:border-box}
html{-webkit-text-size-adjust:100%%}
body{margin:0;font-family:Vazirmatn,Tahoma,"Segoe UI",sans-serif;background:var(--cream);color:var(--ink);line-height:1.9;font-size:16px}
a{color:var(--link)}
.hero{background:linear-gradient(160deg,#246A50,#1F5C45 60%%,#174836);color:#FBF6E9;text-align:center;padding:36px 16px 72px}
.hero .icon{width:104px;height:104px;border-radius:26px;box-shadow:0 10px 30px rgba(0,0,0,.25);display:block;margin:0 auto 12px}
.hero .icon svg{width:100%%;height:100%%;display:block}
.hero h1{margin:0;font-size:2.2rem;line-height:1.4}
.hero .tagline{margin:4px 0 10px;font-size:1.05rem;opacity:.95}
.hero .meta{display:inline-block;background:rgba(251,246,233,.14);border-radius:999px;padding:2px 14px;font-size:.92rem}
main{max-width:760px;margin:-48px auto 0;padding:0 14px 40px}
.card{background:var(--card);border:1px solid var(--line);border-radius:20px;padding:20px 22px;margin-bottom:16px;box-shadow:0 2px 10px rgba(0,0,0,.04)}
.card h2{margin:0 0 10px;font-size:1.2rem;color:var(--link)}
.download{text-align:center;padding-top:26px}
.btn{display:inline-flex;flex-direction:column;align-items:center;justify-content:center;min-width:260px;min-height:64px;
padding:10px 32px;background:var(--gold);color:var(--gold-ink);border-radius:999px;text-decoration:none;font-weight:700;
font-size:1.25rem;line-height:1.5;box-shadow:0 6px 16px rgba(201,162,39,.35)}
.btn small{font-weight:400;font-size:.85rem;opacity:.85}
.btn:active{transform:translateY(1px)}
.alt{margin-top:12px;font-size:.92rem;color:var(--muted)}
.note{margin:16px 0 0;background:var(--soft);border-radius:14px;padding:12px 14px;font-size:.93rem;text-align:start}
.qr{display:flex;gap:18px;align-items:center}
.qr .code{flex:0 0 auto;background:#fff;border-radius:12px;padding:6px;line-height:0}
.qr .code svg{width:150px;height:150px}
@media (max-width:640px){.qr{display:none}}
ol.steps{padding-inline-start:22px;margin:0}
ol.steps li{margin-bottom:8px}
ul{padding-inline-start:22px;margin:0}
ul.check{list-style:none;padding-inline-start:0}
ul.check li{padding-inline-start:30px;position:relative;margin-bottom:6px}
ul.check li::before{content:"";position:absolute;inset-inline-start:2px;top:.55em;width:16px;height:16px;border:2px solid var(--link);border-radius:5px}
.mono{font-family:ui-monospace,Menlo,Consolas,monospace;direction:ltr;unicode-bidi:isolate;word-break:break-all;font-size:.8rem}
table{width:100%%;border-collapse:collapse;font-size:.93rem}
td,th{border-top:1px solid var(--line);padding:8px 4px;text-align:start;vertical-align:top}
th{color:var(--muted);font-weight:400;white-space:nowrap;width:7.5em}
.kbd{background:var(--soft);border-radius:6px;padding:0 6px;white-space:nowrap}
footer{text-align:center;color:var(--muted);font-size:.9rem;padding:8px 16px 32px}
footer a{margin:0 6px}
"""


def render(page_url: str = PAGES_URL, ref: str | None = None, root: Path = ROOT, check_signature: bool = False) -> str:
    info = release_info(root)
    date, highlights = changelog(info.version, root)
    ref = ref or git_ref(root)
    blob = f"https://github.com/{REPO}/blob/{ref}"
    primary = info.primary
    cert = REPO_KEY_SHA256
    if check_signature:
        found = signing_certificate(root / "release" / primary.name)
        if found and found != REPO_KEY_SHA256:
            print(f"warning: {primary.name} is not signed with the repository key ({found})", file=sys.stderr)
            cert = found
    version_fa = fa(info.version)
    minor_fa = fa(".".join(info.version.split(".")[:2]))
    qr = qr_svg(page_url)
    css = CSS % {"regular": FONT_REGULAR, "bold": FONT_BOLD}
    icon_uri = "data:image/svg+xml," + urllib.parse.quote(ICON_SVG)

    meta = [f"نسخه {version_fa}"] + ([esc(date)] if date else []) + ["اندروید ۸ و بالاتر"]
    others = "".join(
        f'<div class="alt"><a href="release/{esc(f.name)}" download>نسخه اشکال‌زدایی برای توسعه‌دهندگان</a> · {f.size_text}'
        "<br>بزرگ‌تر و کندتر؛ با همان کلید امضا شده و روی همین نسخه هم نصب می‌شود.</div>"
        for f in info.others
    )
    qr_block = "" if not qr else f"""
    <section class="card qr" aria-label="کد QR">
      <div class="code">{qr}</div>
      <div>
        <h2>روی گوشی باز کنید</h2>
        <p style="margin:0 0 6px">این کد را با دوربین گوشی اسکن کنید تا همین صفحه روی گوشی باز شود، بعد «دانلود یادینو» را بزنید.</p>
        <div class="mono">{esc(page_url)}</div>
      </div>
    </section>"""
    # items of a patch release (1.3.1) are labelled with their version; those of 1.3.0 are not
    news = "".join(
        f"<li>{esc(item)}" + ("" if v.endswith(".0") else f" <span style='color:var(--muted)'>(نسخه {fa(v)})</span>") + "</li>"
        for v, items in highlights for item in items
    )
    news_block = "" if not news else f"""
    <section class="card">
      <h2>تازه‌های نسخه {minor_fa}</h2>
      <ul>{news}</ul>
      <p style="margin:10px 0 0;font-size:.92rem"><a href="{blob}/CHANGELOG.md">فهرست کامل تغییرات</a></p>
    </section>"""
    file_rows = "".join(
        f"<tr><th>{'نسخه اصلی' if f is primary else 'اشکال‌زدایی'}</th><td><div>{esc(f.name)} · {f.size_text}</div>"
        f"<div class='mono'>SHA-256: {esc(f.sha256)}</div></td></tr>"
        for f in info.files
    )

    return f"""<!DOCTYPE html>
<!-- Generated by tools/download-page/build_page.py from release/ — edit the generator, not this file. -->
<html lang="fa" dir="rtl">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex">
<meta name="theme-color" content="#1F5C45">
<title>دریافت یادینو {version_fa} — برنامه‌ریز فارسی</title>
<link rel="icon" href="{icon_uri}">
<style>{css}</style>
</head>
<body>
<header class="hero">
  <span class="icon">{ICON_SVG}</span>
  <h1>یادینو</h1>
  <p class="tagline">برنامه‌ریز شخصی فارسی و آفلاین با تقویم شمسی</p>
  <span class="meta">{" · ".join(meta)}</span>
</header>
<main>
  <section class="card download">
    <a class="btn" href="release/{esc(primary.name)}" download>دانلود یادینو<small>فایل APK · {primary.size_text}</small></a>
    {others}
    <p class="note"><b>نسخه آزمایشی:</b> یادینو با آزمون‌های خودکار بررسی شده، اما هنوز روی گوشی واقعی اجرا نشده است و ممکن است جایی خطا داشته باشد. اگر برنامه متوقف شد، صفحه «گزارش خطا» باز می‌شود؛ راهنمای آن پایین همین صفحه است.</p>
  </section>
{qr_block}
  <section class="card">
    <h2>نصب در چهار قدم</h2>
    <ol class="steps">
      <li>روی «دانلود یادینو» بزنید. اگر مرورگر پرسید «این فایل ممکن است آسیب‌زا باشد»، «دانلود» را انتخاب کنید.</li>
      <li>وقتی دانلود تمام شد، روی اعلان دانلود یا روی فایل <span class="mono">{esc(primary.name)}</span> در برنامه «فایل‌ها» (پوشه Download) بزنید.</li>
      <li>اگر پیام «نصب برنامه از این منبع مجاز نیست» آمد، «تنظیمات» را بزنید، «اجازه از این منبع» را روشن کنید و برگردید.</li>
      <li>«نصب» را بزنید. اگر Google Play Protect هشدار داد، «جزئیات بیشتر» و بعد «در هر صورت نصب شود» را بزنید. این هشدار برای همه برنامه‌هایی که بیرون از Google Play نصب می‌شوند نمایش داده می‌شود.</li>
    </ol>
    <p style="margin:10px 0 0">بعد از نصب، یادینو را از فهرست برنامه‌ها باز کنید. صفحه خوش‌آمد چند انتخاب اولیه دارد و برای یادآورها اجازه نمایش اعلان می‌خواهد.</p>
  </section>

  <section class="card">
    <h2>به‌روزرسانی</h2>
    <p style="margin:0 0 8px">نسخه‌های بعدی را از همین صفحه بگیرید و همین‌طور نصب کنید. نسخه تازه روی نسخه فعلی نصب می‌شود و اطلاعات شما سر جایش می‌ماند.</p>
    <p style="margin:0">اگر قبلاً یکی از <b>نسخه‌های انتشار ۱٫۰ تا ۱٫۳٫۰</b> را نصب کرده‌اید، کلید امضای آن‌ها با این نسخه فرق دارد و اندروید نصب روی آن را نمی‌پذیرد. یک بار این کار را بکنید: در برنامه فعلی از <span class="kbd">تنظیمات ← اطلاعات و پشتیبان‌گیری</span> پشتیبان فایل بگیرید، برنامه را حذف کنید، این نسخه را نصب کنید و پشتیبان را بازیابی کنید.</p>
  </section>

  <section class="card">
    <h2>چه چیزهایی را امتحان کنیم؟</h2>
    <ul class="check">
      <li>یک کار با یادآور برای چند دقیقه بعد بسازید، منتظر اعلان بمانید و از خود اعلان «انجام شد» را بزنید.</li>
      <li>تقویم: ماه‌ها را ورق بزنید، یک روز را باز کنید و در نمای روزانه یک کار را نگه دارید و بکشید.</li>
      <li>یادداشتی با «فهرست تیک‌دار» بنویسید، چند مورد را تیک بزنید و برنامه را ببندید و دوباره باز کنید.</li>
      <li>یک هدف با چند مرحله و یک عادت روزانه بسازید و انجام عادت را ثبت کنید.</li>
      <li>از منوی ⋮ تقویم، برنامه امروز را به‌عنوان قالب ذخیره کنید و آن را روی فردا اعمال کنید.</li>
      <li>ابزارک‌ها: صفحه اصلی گوشی را نگه دارید، «ابزارک‌ها» را بزنید و یکی از ابزارک‌های یادینو را اضافه کنید.</li>
      <li>در <span class="kbd">تنظیمات ← اطلاعات و پشتیبان‌گیری</span> یک پشتیبان فایل بگیرید و آن را بازیابی کنید.</li>
      <li>در تنظیمات، پوسته تاریک، اندازه متن و ارقام لاتین را امتحان کنید.</li>
    </ul>
  </section>

  <section class="card">
    <h2>اگر برنامه متوقف شد یا ایرادی دیدید</h2>
    <ul>
      <li>اگر یادینو ناگهان بسته شود، صفحه «یادینو با خطا متوقف شد» باز می‌شود. «کپی گزارش» را بزنید و متن آن را برای توسعه‌دهنده بفرستید، یا در <a href="https://github.com/{REPO}/issues">بخش Issues گیت‌هاب</a> ثبت کنید.</li>
      <li>خطاهای دیگر در <span class="kbd">تنظیمات ← پیشرفته ← اشتراک گزارش خطا</span> جمع می‌شوند.</li>
      <li>گزارش‌ها فقط نسخه برنامه و اندروید، مدل گوشی و محل خطا در کد را دارند. متن کارها و یادداشت‌های شما هرگز در آن‌ها نیست.</li>
      <li>برای ایرادهای ظاهری، یک تصویر از صفحه (اسکرین‌شات) همراه یک توضیح کوتاه بهترین گزارش است.</li>
    </ul>
  </section>
{news_block}
  <section class="card">
    <h2>مشخصات فایل‌ها</h2>
    <table>
      {file_rows}
      <tr><th>گواهی امضا</th><td><div class="mono">SHA-256: {esc(cert)}</div>
        <div style="font-size:.88rem;color:var(--muted)">هر دو فایل با کلید عمومی مخزن امضا شده‌اند تا نسخه‌های بعدی روی آن‌ها نصب شوند. این کلید برای آزمایش است؛ فایل‌ها را فقط از همین صفحه یا مخزن گیت‌هاب یادینو بگیرید.</div></td></tr>
      <tr><th>سازگاری</th><td>اندروید ۸٫۰ (API {fa(26)}) و بالاتر · هدف اندروید ۱۵ (API {fa(35)}) · شناسه بسته <span class="mono">{PACKAGE}</span> · کد نسخه {fa(info.version_code)}</td></tr>
    </table>
  </section>
</main>
<footer>
  یادینو هیچ داده‌ای جمع‌آوری یا ارسال نمی‌کند.<br>
  <a href="https://github.com/{REPO}/tree/{ref}">کد منبع</a>·<a href="{blob}/CHANGELOG.md">تغییرات نسخه‌ها</a>·<a href="{blob}/docs/TEST_REPORT.md">گزارش آزمون</a>·<a href="{blob}/ROADMAP.md">نقشه راه</a>
</footer>
</body>
</html>
"""


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--url", default=PAGES_URL, help="address encoded in the QR code (default: GitHub Pages)")
    parser.add_argument("--ref", default=None, help="git branch for links to documents (default: current branch)")
    parser.add_argument("--out", default=str(ROOT / "index.html"))
    args = parser.parse_args()
    page = render(args.url, args.ref, check_signature=True)
    Path(args.out).write_text(page, encoding="utf-8")
    nojekyll = ROOT / ".nojekyll"  # GitHub Pages: serve the files as they are
    if not nojekyll.exists():
        nojekyll.write_text("", encoding="utf-8")
    print(f"wrote {args.out} ({len(page.encode('utf-8'))} bytes){'' if qr_svg(args.url) else ' — without QR code (pip install segno)'}")


if __name__ == "__main__":
    main()
