/**
 * Version consistency guard.
 *
 * The app carries its version in five places, and a release is only honest if
 * all five agree — a past release shipped installers named after a tag while
 * the manifests said something else. This test fails the moment they drift, and
 * also proves the built APK really carries the version it claims.
 *
 * Usage:  node test/versioncheck.mjs
 */
import { readFileSync, existsSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const read = (p) => readFileSync(path.join(ROOT, p), 'utf8');

const results = [];
const record = (name, ok, detail) => {
  results.push({ name, ok });
  console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${detail !== undefined ? ' — ' + JSON.stringify(detail) : ''}`);
};

const pkg = JSON.parse(read('package.json')).version;
const desktop = JSON.parse(read('desktop/package.json')).version;
const manifest = read('android/app/src/main/AndroidManifest.xml');
const manifestName = (manifest.match(/android:versionName="([^"]+)"/) || [])[1];
const manifestCode = (manifest.match(/android:versionCode="([^"]+)"/) || [])[1];
const buildConfig = (read('android/app/src/main/java/ai/zyraxon/assistant/BuildConfig.java')
  .match(/VERSION = "([^"]+)"/) || [])[1];

record('package.json has a version', !!pkg, { version: pkg });
record('the Android manifest agrees with package.json',
  manifestName === pkg, { manifest: manifestName, package: pkg });
record('the desktop manifest agrees with package.json',
  desktop === pkg, { desktop, package: pkg });
record('the native BuildConfig agrees with package.json',
  buildConfig === pkg, { buildConfig, package: pkg });
record('versionCode is a real integer', /^\d+$/.test(manifestCode || ''), { versionCode: manifestCode });
// A versionCode that does not grow makes Android refuse to install an update.
// Each dotted component is padded to two digits: 19.0.6 -> 190006.
const derivedCode = pkg.split('.').map((n) => String(Number(n)).padStart(2, '0')).join('').slice(0, 9);
record('versionCode encodes the version so updates can install',
  Number(manifestCode) === Number(derivedCode),
  { versionCode: manifestCode, derived: derivedCode });

// The badge must exist in the page the APK bundles, and the code that fills it
// must be in the bundle too — a badge with no filler would show "v—" forever.
const html = read('public/index.html');
const appJs = read('public/js/app.js');
record('the version badge is in the shipped page',
  /id="app-version"/.test(html));
record('the code that fills the badge is in the shipped bundle',
  /showVersion/.test(appJs) && /api\/version/.test(appJs));

// If an APK has been built, read its real manifest back and compare. This is
// the only check that proves the artifact itself, not the sources.
const APK = path.join(ROOT, 'android/zyraxon.apk');
const aapt2 = process.env.ANDROID_HOME
  ? path.join(process.env.ANDROID_HOME, 'build-tools/36.0.0/aapt2')
  : '';
if (existsSync(APK) && aapt2 && existsSync(aapt2)) {
  const r = spawnSync(aapt2, ['dump', 'badging', APK], { encoding: 'utf8' });
  const out = r.stdout || '';
  const apkName = (out.match(/versionName='([^']+)'/) || [])[1];
  const apkCode = (out.match(/versionCode='([^']+)'/) || [])[1];
  record('the built APK carries the current version',
    apkName === pkg, { apk: apkName, package: pkg });
  record('the built APK carries the current versionCode',
    apkCode === manifestCode, { apk: apkCode, manifest: manifestCode });

  // And the version badge must be inside the APK's own assets, not just public/.
  const unzip = spawnSync('unzip', ['-p', APK, 'assets/index.html'], { encoding: 'utf8' });
  record('the built APK contains the version badge',
    /id="app-version"/.test(unzip.stdout || ''));
} else {
  console.log('SKIP the built-APK checks (no APK, or aapt2 not on ANDROID_HOME)');
}

const failed = results.filter((r) => !r.ok);
console.log('');
console.log(failed.length
  ? `${failed.length} VERSION CHECK(S) FAILED: ${failed.map((f) => f.name).join('; ')}`
  : 'ALL VERSION CHECKS PASSED');
process.exit(failed.length ? 1 : 0);
