#!/usr/bin/env node
// Post-deploy smoke check: confirms the live GitHub Pages site actually
// serves the pages it's supposed to (design.md "GitHub Pages smoke test").
const baseUrl = (process.argv[2] || "https://kzybala.pl").replace(/\/$/, "");
const paths = ["/", "/lab/"];

const errors = [];

for (const path of paths) {
  const url = `${baseUrl}${path}`;
  try {
    const res = await fetch(url, { redirect: "follow" });
    if (!res.ok) {
      errors.push(`${url} responded ${res.status}`);
      continue;
    }
    const body = await res.text();
    if (!/<html/i.test(body)) errors.push(`${url} did not return HTML`);
  } catch (err) {
    errors.push(`${url} request failed: ${err.message}`);
  }
}

if (errors.length > 0) {
  console.error(`check-pages-smoke: ${errors.length} problem(s) found\n`);
  for (const err of errors) console.error(`  - ${err}`);
  process.exit(1);
}

console.log(`check-pages-smoke: ${paths.length} path(s) OK on ${baseUrl}`);
