const fs = require('fs');

const content = fs.readFileSync('index.html', 'utf8');

// Extract all scripts
const scriptMatches = content.match(/<script(?![^>]*src=)[^>]*>([\s\S]*?)<\/script>/gi) || [];

console.log(`Found ${scriptMatches.length} inline scripts`);

// Create a DOM mock
const domMock = {
  getElementById: (id) => ({
    id,
    value: '',
    textContent: '',
    innerText: '',
    innerHTML: '',
    style: {},
    classList: { add: () => {}, remove: () => {}, toggle: () => false, contains: () => false },
    addEventListener: () => {},
    setAttribute: () => {},
    getAttribute: () => null,
    appendChild: () => {},
    options: [{ value: 'Bogotá' }],
    tagName: 'DIV',
    focus: () => {}
  }),
  querySelectorAll: () => [],
  querySelector: () => null,
  createElement: (tag) => ({
    tagName: tag.toUpperCase(),
    className: '',
    innerHTML: '',
    style: {},
    appendChild: () => {},
    onclick: null
  }),
  body: { appendChild: () => {} }
};

const storageMock = {
  getItem: () => null,
  setItem: () => {},
  removeItem: () => {},
  clear: () => {}
};

const context = {
  window: {
    innerWidth: 1200,
    addEventListener: () => {},
    localStorage: storageMock,
    sessionStorage: storageMock
  },
  document: domMock,
  localStorage: storageMock,
  sessionStorage: storageMock,
  console: console,
  setTimeout: (fn) => fn(),
  clearTimeout: () => {},
  setInterval: () => {},
  clearInterval: () => {},
  alert: console.log
};

// Test running the code
scriptMatches.forEach((s, idx) => {
  const code = s.replace(/<script[^>]*>/i, '').replace(/<\/script>/i, '');
  try {
    const fn = new Function('window', 'document', 'localStorage', 'sessionStorage', 'console', code);
    fn(context.window, context.document, storageMock, storageMock, console);
    console.log(`Script ${idx} executed successfully without top-level errors.`);
  } catch (err) {
    console.error(`Script ${idx} runtime error:`, err);
  }
});
