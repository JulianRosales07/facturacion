const xlsx = require('xlsx');

console.log('=== SEARCHING IN saldos_de_cartera.xlsx ===');
try {
  const wb = xlsx.readFile('Docs/archivosplanos/saldos_de_cartera.xlsx');
  wb.SheetNames.forEach(name => {
    const ws = wb.Sheets[name];
    const data = xlsx.utils.sheet_to_json(ws, { header: 1 });
    data.forEach((row, idx) => {
      const rowStr = JSON.stringify(row);
      if (rowStr.includes('522823')) {
        console.log(`Sheet "${name}" Row ${idx}:`, row);
      }
    });
  });
} catch (e) {
  console.error(e.message);
}

console.log('=== SEARCHING IN movimientocontableFACSEP.xlsx ===');
try {
  const wb2 = xlsx.readFile('Docs/archivosplanos/movimientocontableFACSEP.xlsx');
  wb2.SheetNames.forEach(name => {
    const ws = wb2.Sheets[name];
    const data = xlsx.utils.sheet_to_json(ws, { header: 1 });
    data.forEach((row, idx) => {
      const rowStr = JSON.stringify(row);
      if (rowStr.includes('522823')) {
        console.log(`Sheet "${name}" Row ${idx}:`, row);
      }
    });
  });
} catch (e) {
  console.error(e.message);
}
