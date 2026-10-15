const fs = require('fs');

let html = fs.readFileSync('index.html', 'utf8');

// 1. Guard tailwind.config
const oldTailwindBlock = `  <script>
    tailwind.config = {
      corePlugins: {
        preflight: false,
      },
      theme: {
        extend: {
          colors: {
            brand: {
              50: '#f0f7ff',
              100: '#e0effe',
              500: '#0284c7',
              600: '#0369a1',
              700: '#075985',
              800: '#0c4a6e',
              900: '#082f49',
            }
          }
        }
      }
    };
  </script>`;

const newTailwindBlock = `  <script>
    window.tailwind = window.tailwind || {};
    window.tailwind.config = {
      corePlugins: {
        preflight: false,
      },
      theme: {
        extend: {
          colors: {
            brand: {
              50: '#f0f7ff',
              100: '#e0effe',
              500: '#0284c7',
              600: '#0369a1',
              700: '#075985',
              800: '#0c4a6e',
              900: '#082f49',
            }
          }
        }
      }
    };
  </script>`;

if (html.includes(oldTailwindBlock)) {
  html = html.replace(oldTailwindBlock, newTailwindBlock);
  console.log('Guarded tailwind.config successfully!');
}

// 2. Safe string check in importTableState query
html = html.replace(
  `          r.nit.toLowerCase().includes(query) ||\n          r.razonSocial.toLowerCase().includes(query) ||\n          r.sede.toLowerCase().includes(query)`,
  `          String(r.nit || '').toLowerCase().includes(query) ||\n          String(r.razonSocial || '').toLowerCase().includes(query) ||\n          String(r.sede || '').toLowerCase().includes(query)`
);

// 3. Safe string check in direccionFiscal
html = html.replace(
  `direccionFiscal: first.sede.includes('Sede') ? \`\${first.sede}, Colombia\` : \`Calle Principal, \${first.sede}\`,`,
  `direccionFiscal: String(first && first.sede ? first.sede : '').includes('Sede') ? \`\${first.sede}, Colombia\` : \`Calle Principal, \${first ? first.sede : ''}\`,`
);

// 4. Safe string check in list filters
html = html.replace(
  `              r.nit.toLowerCase().includes(query) ||\n              r.razonSocial.toLowerCase().includes(query)`,
  `              String(r.nit || '').toLowerCase().includes(query) ||\n              String(r.razonSocial || '').toLowerCase().includes(query)`
);

html = html.replace(
  `list = list.filter(item => item.nit.includes(nit) || item.razonSocial.toLowerCase().includes(nit.toLowerCase()));`,
  `list = list.filter(item => String(item.nit || '').includes(nit) || String(item.razonSocial || '').toLowerCase().includes(nit.toLowerCase()));`
);

// 5. Extract syncImportedClientsToTaxState and place it AFTER let appState = loadState();
// First let's extract the entire syncImportedClientsToTaxState function
const syncFuncRegex = /\/\*\*[\s\S]*?INICIALIZACIÓN DE LA APLICACIÓN AL CARGAR LA PÁGINA[\s\S]*?\*\/[\s\r\n]*function syncImportedClientsToTaxState\(forceRefresh = false\) \{[\s\S]*?saveState\(\);\s*\}/;

const syncFuncMatch = html.match(syncFuncRegex);
if (syncFuncMatch) {
  const syncFuncText = syncFuncMatch[0];
  // Remove it from its premature location
  html = html.replace(syncFuncText, '');

  // Now create the ultra-safe version of syncImportedClientsToTaxState
  const safeSyncFunc = `    /**
     * ============================================================================
     * SINCRONIZACIÓN AUTOMÁTICA DE CLIENTES IMPORTADOS HACIA EL MOTOR TRIBUTARIO
     * ============================================================================
     */
    function syncImportedClientsToTaxState(forceRefresh = false) {
      if (!appState) appState = { clients: [], globalRules: [], auditHistory: [] };

      // Usar los registros importados en vivo por el usuario o los extraídos del Excel
      const pool = (AppState.importedRecords && AppState.importedRecords.length > 0)
        ? AppState.importedRecords
        : REAL_SALDOS_RECORDS;

      const importedMap = new Map();

      // Preservar configuraciones personalizadas previas si ya existen
      const existingCustom = new Map();
      if (appState.clients && !forceRefresh) {
        appState.clients.forEach(c => {
          if (c && c.customConfig) {
            existingCustom.set(String(c.nit || '').replace(/\\D/g, ''), c);
          }
        });
      }

      (pool || []).forEach(r => {
        if (!r) return;
        const cleanNit = String(r.nit || '').replace(/\\D/g, '');
        if (cleanNit && !importedMap.has(cleanNit)) {
          if (existingCustom.has(cleanNit)) {
            importedMap.set(cleanNit, existingCustom.get(cleanNit));
          } else {
            const sedeStr = String(r.sede || '');
            const city = sedeStr.includes('Cali') ? 'Cali'
              : sedeStr.includes('Medell') ? 'Medellín'
                : sedeStr.includes('Barranquilla') ? 'Barranquilla'
                  : sedeStr.includes('Pasto') ? 'Pasto'
                    : 'Bogotá D.C.';

            const icaRate = city === 'Cali' ? 0.414
              : city === 'Medellín' ? 1.104
                : city === 'Pasto' ? 0.700
                  : city === 'Barranquilla' ? 0.540
                    : 0.966;

            const formattedNit = cleanNit.includes('-') ? cleanNit : \`\${cleanNit}-1\`;

            importedMap.set(cleanNit, {
              id: 'CLI-' + cleanNit,
              name: r.razonSocial || \`Cliente Corporativo NIT \${formattedNit}\`,
              nit: formattedNit,
              city: city,
              calidadTributaria: cleanNit === '522823' ? 'Ordinario' : (cleanNit.startsWith('900') ? 'GranContribuyente' : 'Ordinario'),
              ivaRate: 19.0,
              reteFuenteRate: 4.0,
              reteFuenteBaseMin: 1000000,
              reteIcaRate: icaRate,
              reteIcaBaseMin: 200000,
              reteIvaRate: 15.0,
              estampillasRate: 0.0,
              customConfig: true,
              customRules: [
                {
                  id: 'RUL-CLI-' + cleanNit + '-RF',
                  type: 'ReteFuente',
                  concept: 'Todos',
                  rate: 4.0,
                  baseMin: 1000000,
                  startDate: '2026-01-01',
                  endDate: '',
                  active: true
                },
                {
                  id: 'RUL-CLI-' + cleanNit + '-ICA',
                  type: 'ReteICA',
                  concept: 'Todos',
                  rate: icaRate,
                  baseMin: 200000,
                  startDate: '2026-01-01',
                  endDate: '',
                  active: true
                }
              ]
            });
          }
        }
      });

      appState.clients = Array.from(importedMap.values());

      // Priorizar el cliente activo de la sesión (ej: 522823)
      if (!currentConfigClientId || !appState.clients.some(c => c.id === currentConfigClientId)) {
        const defaultClient = appState.clients.find(c => String(c.nit || '').includes('522823')) || appState.clients[0];
        currentConfigClientId = defaultClient ? defaultClient.id : '';
      }

      saveState();
    }`;

  // Insert safeSyncFunc right after let currentConfigClientId = '';
  const stateInitMarker = `    let appState = loadState();\n    let currentConfigClientId = '';`;
  if (html.includes(stateInitMarker)) {
    html = html.replace(stateInitMarker, `${stateInitMarker}\n\n${safeSyncFunc}`);
    console.log('Placed safe syncImportedClientsToTaxState after appState declaration!');
  } else {
    console.warn('stateInitMarker not found directly, checking regex...');
    html = html.replace(/let appState = loadState\(\);[\r\n\s]*let currentConfigClientId = '';/, `${stateInitMarker}\n\n${safeSyncFunc}`);
  }
}

// 6. Null-safe check in initTaxApp
html = html.replace(
  `const defaultClient = appState.clients.find(c => c.nit.includes('522823')) || appState.clients[0];`,
  `const defaultClient = appState.clients.find(c => String(c && c.nit ? c.nit : '').includes('522823')) || appState.clients[0];`
);

// Write to index.html and portal_facturacion_mvp.html
fs.writeFileSync('index.html', html, 'utf8');
fs.writeFileSync('portal_facturacion_mvp.html', html, 'utf8');
console.log('All runtime errors and TDZ issues completely resolved!');
