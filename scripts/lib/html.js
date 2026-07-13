import { readdirSync, statSync, readFileSync } from "node:fs";
import { join, extname } from "node:path";

const IGNORED_DIRS = new Set([".git", "node_modules", "openspec", ".idea", ".github", ".claude", ".gemini"]);
const VOID_ELEMENTS = new Set([
  "area", "base", "br", "col", "embed", "hr", "img", "input",
  "link", "meta", "param", "source", "track", "wbr",
]);

export function findHtmlFiles(root) {
  let out = [];
  for (const entry of readdirSync(root, { withFileTypes: true })) {
    if (entry.name.startsWith(".")) continue;
    const path = join(root, entry.name);
    if (entry.isDirectory()) {
      if (!IGNORED_DIRS.has(entry.name)) out = out.concat(findHtmlFiles(path));
    } else if (extname(entry.name) === ".html") {
      out.push(path);
    }
  }
  return out;
}

// Minimal tag-balance parser. Not a full HTML5 parser — it checks that every
// non-void, non-self-closing tag opened is closed, in the correct order.
export function checkTagBalance(html) {
  const errors = [];
  const stack = [];
  const tagPattern = /<(\/?)([a-zA-Z][a-zA-Z0-9-]*)((?:\s+[^<>]*?)?)(\/?)>/g;
  let match;
  let inScriptOrStyle = null;

  while ((match = tagPattern.exec(html))) {
    const [, closing, rawName, , selfClosing] = match;
    const name = rawName.toLowerCase();

    if (inScriptOrStyle) {
      if (closing && name === inScriptOrStyle) {
        inScriptOrStyle = null;
        stack.pop();
      }
      continue;
    }

    if (closing) {
      if (VOID_ELEMENTS.has(name)) continue;
      const top = stack.pop();
      if (!top || top !== name) {
        errors.push(`unexpected closing tag </${name}>, expected ${top ? `</${top}>` : "none open"}`);
        if (top) stack.push(top); // best-effort recovery
      }
    } else {
      if (VOID_ELEMENTS.has(name) || selfClosing === "/") continue;
      stack.push(name);
      if (name === "script" || name === "style") inScriptOrStyle = name;
    }
  }

  if (stack.length > 0) errors.push(`unclosed tag(s): ${stack.join(", ")}`);
  return errors;
}

// Extracts local (root-relative or relative) href/src targets, excluding
// external, mailto/tel, and fragment-only links.
export function extractLocalLinks(html) {
  const links = [];
  const attrPattern = /\b(?:href|src)\s*=\s*"([^"]*)"/g;
  let match;
  while ((match = attrPattern.exec(html))) {
    const value = match[1];
    if (!value || value.startsWith("#")) continue;
    if (/^(https?:)?\/\//.test(value)) continue;
    if (/^(mailto|tel|data|javascript):/.test(value)) continue;
    links.push(value);
  }
  return links;
}

export { readFileSync, statSync };
