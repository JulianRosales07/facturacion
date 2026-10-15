const fs = require('fs');

const html = fs.readFileSync('index.html', 'utf8');

// Extract script
const scriptMatches = html.match(/<script(?![^>]*src=)[^>]*>([\s\S]*?)<\/script>/gi) || [];

// Mock window and document
const elements = {};
function getOrCreateElement(id) {
  if (!elements[id]) {
    elements[id] = {
      id,
      value: '',
      textContent: '',
      innerText: '',
      innerHTML: '',
      style: {},
      classList: {
        add: () => {},
        remove: () => {},
        toggle: () => false,
        contains: () => false
      },
      addEventListener: () => {},
      setAttribute: () => {},
      getAttribute: () => null,
      appendChild: () => {},
      options: [{ value: 'Bogotá D.C.' }, { value: 'Medellín' }, { value: 'Cali' }],
      tagName: 'DIV',
      focus: () => {}
    };
  }
  return elements[id];
}

const domMock = {
  getElementById: (id) => getOrCreateElement(id),
  querySelectorAll: (selector) => [],
  querySelector: (selector) => getOrCreateElement('mock-selected'),
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

const storage = {};
const storageMock = {
  getItem: (k) => storage[k] || null,
  setItem: (k, v) => { storage[k] = v; },
  removeItem: (k) => { delete storage[k]; },
  clear: () => {}
};

const sandbox = {
  window: {
    innerWidth: 1200,
    addEventListener: () => {},
    localStorage: storageMock,
    sessionStorage: storageMock,
    lucide: { createIcons: () => {} },
    XLSX: null
  },
  document: domMock,
  localStorage: storageMock,
  sessionStorage: storageMock,
  console: console,
  setTimeout: (fn) => fn(),
  clearTimeout: () => {},
  setInterval: () => {},
  clearInterval: () => {},
  alert: console.log,
  lucide: { createIcons: () => {} }
};

// Execute script 1
const code = scriptMatches[1].replace(/<script[^>]*>/i, '').replace(/<\/script>/i, '');
const runner = new Function(
  'window', 'document', 'localStorage', 'sessionStorage', 'console', 'lucide',
  `with(window) { ${code} }`
);

try {
  runner(sandbox.window, sandbox.document, storageMock, storageMock, console, sandbox.lucide);
  console.log('Script 1 executed!');
} catch (err) {
  console.error('Error during Script 1 execution:', err);
}

// Now test calling all navigation and action functions
const testFunctions = [
  () => sandbox.window.navigate('resumen'),
  () => sandbox.window.navigate('importar'),
  () => sandbox.window.navigate('facturacion'),
  () => sandbox.window.navigate('facturas'),
  () => sandbox.window.navigate('lote-siigo'),
  () => sandbox.window.navigate('tributaria'),
  () => sandbox.window.switchTaxTab('dashboard'),
  () => sandbox.window.switchTaxTab('global'),
  () => sandbox.window.switchTaxTab('client'),
  () => sandbox.window.switchTaxTab('simulator'),
  () => sandbox.window.switchTaxTab('audit'),
  () => sandbox.window.openConfigModal(),
  () => sandbox.window.closeConfigModal(),
  () => sandbox.window.openProfileModal ? sandbox.window.openProfileModal() : null,
  () => sandbox.window.toggleSidebar(),
  () => sandbox.window.closeMobileSidebar()
];

testFunctions.forEach((fn, i) => {
  try {
    fn();
    console.log(`Function test ${i} passed!`);
  } catch (e) {
    console.error(`Function test ${i} FAILED:`, e);
  }
});
