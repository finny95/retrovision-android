// Downloads the web bundle that Base44 published (the same zip phones get over the air)
// and unpacks it into www/ so it ships inside the APK as the starting version.
//
// Usage: node scripts/fetch-bundle.mjs [manifestUrl]
import { createHash } from "node:crypto";
import { appendFileSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { unzipSync } from "fflate";

const root = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const config = JSON.parse(readFileSync(path.join(root, "ota.config.json"), "utf8"));
const manifestUrl = process.argv[2] || process.env.OTA_MANIFEST_URL || config.manifestUrl;
const outDir = path.join(root, "www");

async function get(url) {
  const res = await fetch(url, { headers: { "Cache-Control": "no-cache" } });
  if (!res.ok) throw new Error(`${url} answered ${res.status}`);
  return res;
}

const res = await get(`${manifestUrl}?t=${Date.now()}`);
const type = res.headers.get("content-type") || "";
if (!type.includes("json")) {
  throw new Error(
    `${manifestUrl} did not return JSON (got ${type}). Publish the Base44 app once so the OTA bundle exists.`
  );
}
const manifest = await res.json();
for (const key of ["version", "url", "checksum"]) {
  if (typeof manifest[key] !== "string" || !manifest[key]) throw new Error(`Manifest is missing "${key}"`);
}

const zipUrl = new URL(manifest.url, manifestUrl).href;
const zip = new Uint8Array(await (await get(zipUrl)).arrayBuffer());
const checksum = createHash("sha256").update(zip).digest("hex");
if (checksum !== manifest.checksum) {
  throw new Error(`Checksum mismatch for ${zipUrl}: expected ${manifest.checksum}, got ${checksum}`);
}

rmSync(outDir, { recursive: true, force: true });
const files = unzipSync(zip);
for (const [name, data] of Object.entries(files)) {
  if (name.endsWith("/")) continue;
  const target = path.join(outDir, name);
  if (!target.startsWith(outDir + path.sep)) throw new Error(`Refusing to write outside www/: ${name}`);
  mkdirSync(path.dirname(target), { recursive: true });
  writeFileSync(target, data);
}

console.log(`Bundle ${manifest.version} (${Object.keys(files).length} files, ${zip.length} bytes) unpacked into www/`);
if (process.env.GITHUB_OUTPUT) {
  appendFileSync(process.env.GITHUB_OUTPUT, `version=${manifest.version}\nbuilt_at=${manifest.builtAt || ""}\n`);
}
