#!/usr/bin/env node
/**
 * ZYRAXON web build.
 *
 * Every browser module is authored in TypeScript under src/web and transpiled
 * to plain ES2020 in public/js, which is what the server, the APK and the
 * desktop app all serve. The files are independent IIFEs that talk through the
 * shared `window.ZY` namespace, so each one is transpiled on its own rather
 * than bundled — the script tags in index.html and the load order stay exactly
 * as they were.
 *
 *     node scripts/build-web.mjs            one-shot build
 *     node scripts/build-web.mjs --watch    rebuild on change
 */
import { build, context } from 'esbuild';
import { readdirSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const SRC = path.join(ROOT, 'src', 'web');
const OUT = path.join(ROOT, 'public', 'js');

const entries = readdirSync(SRC)
  .filter((f) => f.endsWith('.ts'))
  .map((f) => path.join(SRC, f));

if (!entries.length) {
  console.error('[build-web] no TypeScript sources in src/web');
  process.exit(1);
}

const options = {
  entryPoints: entries,
  outdir: OUT,
  outbase: SRC,
  bundle: false,
  format: 'iife',
  target: ['es2020', 'chrome90', 'firefox90', 'safari15'],
  platform: 'browser',
  legalComments: 'none',
  logLevel: 'info',
  sourcemap: false,
  minify: process.env.ZYRAXON_MINIFY === '1',
};

if (process.argv.includes('--watch')) {
  const ctx = await context(options);
  await ctx.watch();
  console.log(`[build-web] watching ${entries.length} modules -> public/js`);
} else {
  await build(options);
  console.log(`[build-web] built ${entries.length} modules -> public/js`);
}
