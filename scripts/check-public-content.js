#!/usr/bin/env node
// Public-content gate (docs/measurement-environments.md, public
// reproducibility policy): stable public pages must never link the
// author's private repository, expose internal repository paths, render a
// bare "Measured" status for developer-workstation results, or show Mac
// developer-workstation numeric result tables as canonical.
import { readFileSync, readdirSync, existsSync } from "node:fs";
import { join } from "node:path";

const ROOT = join(import.meta.dirname, "..");
const errors = [];
const fail = (msg) => errors.push(msg);

const PUBLIC_PAGES = [
  "index.html",
  "about/index.html",
  "lab/index.html",
  ...readdirSync(join(ROOT, "lab"), { withFileTypes: true })
    .filter((e) => e.isDirectory())
    .map((e) => `lab/${e.name}/index.html`)
    .filter((p) => existsSync(join(ROOT, p))),
];

const FORBIDDEN = [
  { needle: "github.com/krystianzybala/kzybala-pl", why: "links the author's private repository" },
  { needle: "site's repository", why: "implies the private repository is publicly accessible" },
  { needle: "content/labs/", why: "exposes internal repository paths as public links" },
  { needle: "clone the repository", why: "public visitors cannot clone a private repository" },
  { needle: 'disclosure-kind">Measured<', why: 'bare "Measured" badge — developer-workstation results must render as development-only, canonical results as awaiting/measured states' },
];

// Lab pages additionally must not present developer-workstation (Mac)
// numbers as results — the awaiting state replaced those tables.
const LAB_PAGE_FORBIDDEN = [
  { needle: "Apple M1 Max", why: "developer-workstation results must not render on canonical lab pages (docs/measurement-environments.md)" },
];

for (const page of PUBLIC_PAGES) {
  const html = readFileSync(join(ROOT, page), "utf8");
  for (const { needle, why } of FORBIDDEN) {
    if (html.includes(needle)) fail(`${page}: contains "${needle}" — ${why}`);
  }
  if (page.startsWith("lab/") && page !== "lab/index.html") {
    for (const { needle, why } of LAB_PAGE_FORBIDDEN) {
      if (html.includes(needle)) fail(`${page}: contains "${needle}" — ${why}`);
    }
  }
}

// The About page must use the local portrait asset with alt text — never an
// external avatar or a repository-hosted image.
const about = readFileSync(join(ROOT, "about/index.html"), "utf8");
if (!about.includes('src="/assets/images/me.jpeg"')) {
  fail("about/index.html: portrait must use the local asset /assets/images/me.jpeg");
}
if (!/img[^>]*src="\/assets\/images\/me\.jpeg"[^>]*alt="[^"]+"/.test(about)) {
  fail("about/index.html: the portrait image must carry meaningful alt text");
}
if (!existsSync(join(ROOT, "assets/images/me.jpeg"))) {
  fail("assets/images/me.jpeg is referenced but missing");
}

if (errors.length > 0) {
  console.error(`check-public-content: ${errors.length} problem(s) found\n`);
  for (const err of errors) console.error(`  - ${err}`);
  process.exit(1);
}
console.log(`check-public-content: ${PUBLIC_PAGES.length} public page(s) OK`);
