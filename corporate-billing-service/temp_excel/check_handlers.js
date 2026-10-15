const fs = require('fs');

const html = fs.readFileSync('index.html', 'utf8');

// Find all onclick handlers
const onclickMatches = html.matchAll(/onclick="([^"]+)"/g);
const handlerNames = new Set();
for (const m of onclickMatches) {
  const expr = m[1];
  // extract function names
  const fnMatches = expr.matchAll(/([a-zA-Z_$][a-zA-Z0-9_$]*)\s*\(/g);
  for (const f of fnMatches) {
    if (!['if', 'for', 'while', 'catch', 'Boolean', 'String', 'Number', 'alert'].includes(f[1])) {
      handlerNames.add(f[1]);
    }
  }
}

console.log('Unique onclick function names found:', Array.from(handlerNames));

// Check which are defined in the scripts
const scriptMatches = html.match(/<script(?![^>]*src=)[^>]*>([\s\S]*?)<\/script>/gi) || [];
const allJs = scriptMatches.join('\n');

const missing = [];
for (const fn of handlerNames) {
  const isDefined = new RegExp(`function\\s+${fn}\\b|const\\s+${fn}\\b|let\\s+${fn}\\b|var\\s+${fn}\\b|window\\.${fn}\\b`).test(allJs);
  if (!isDefined) {
    missing.push(fn);
  }
}

console.log('Missing or undefined handlers:', missing);
