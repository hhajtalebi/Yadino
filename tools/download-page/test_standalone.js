// Checks the single-file download page (release/yadino-<version>-download.html) in jsdom:
// pressing the button must hand the browser a Blob that is byte-for-byte the release APK,
// with the APK MIME type and file name, and a page that was cut short on the way must show
// an error instead of producing a broken APK.
//
//   npm install jsdom@24        (in any directory on NODE_PATH, e.g. a temporary one)
//   node tools/download-page/test_standalone.js
"use strict";
const fs = require("fs");
const path = require("path");
const crypto = require("crypto");
const assert = require("assert");
const { JSDOM, VirtualConsole } = require("jsdom");

const release = path.join(__dirname, "..", "..", "release");
const page = fs.readdirSync(release).find((f) => f.endsWith("-download.html"));
assert(page, "no *-download.html in release/ (python3 tools/download-page/build_page.py --standalone)");
const version = page.replace(/^yadino-/, "").replace(/-download\.html$/, "");
const apkName = `yadino-${version}-release.apk`;
const apk = fs.readFileSync(path.join(release, apkName));
const sha = (b) => crypto.createHash("sha256").update(b).digest("hex");

async function press(html) {
  const virtualConsole = new VirtualConsole();
  virtualConsole.on("jsdomError", (e) => {
    if (!/navigation/i.test(String(e.message))) throw e; // following the blob link is not implemented in jsdom
  });
  const dom = new JSDOM(html, { runScripts: "dangerously", virtualConsole, url: "file:///sdcard/Download/" + page });
  const w = dom.window;
  const blobs = [];
  w.URL.createObjectURL = (b) => { blobs.push(b); return "blob:file:///yadino"; };
  const button = w.document.getElementById("dl");
  for (let i = 0; i < 2; i++) button.dispatchEvent(new w.MouseEvent("click", { bubbles: true, cancelable: true }));
  const error = w.document.getElementById("dl-error");
  const bytes = blobs.length === 0 ? null : await new Promise((resolve, reject) => {
    const reader = new w.FileReader();
    reader.onload = () => resolve(Buffer.from(reader.result));
    reader.onerror = reject;
    reader.readAsArrayBuffer(blobs[0]);
  });
  return { button, error, blobs, bytes };
}

(async () => {
  const html = fs.readFileSync(path.join(release, page), "utf8");

  const ok = await press(html);
  assert.strictEqual(ok.blobs.length, 1, "the second press must reuse the prepared file");
  assert.strictEqual(ok.blobs[0].type, "application/vnd.android.package-archive");
  assert.strictEqual(ok.button.getAttribute("download"), apkName);
  assert.strictEqual(ok.bytes.length, apk.length);
  assert.strictEqual(sha(ok.bytes), sha(apk), "the downloaded file differs from " + apkName);
  assert.ok(ok.error.hidden);

  const start = html.indexOf('<script id="apk"');
  const cut = await press(html.slice(0, start + 200000) + html.slice(start + 300000));
  assert.strictEqual(cut.blobs.length, 0, "a truncated page must not produce a file");
  assert.ok(!cut.error.hidden && cut.error.textContent.length > 0, "a truncated page must explain the problem");

  console.log(`ok: ${page} gives ${apkName} (${apk.length} bytes, sha256 ${sha(apk)}); truncated copy refused`);
})().catch((e) => { console.error(e); process.exit(1); });
