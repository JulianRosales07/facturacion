$excel = New-Object -ComObject Excel.Application
$excel.Visible = $false
try {
    Write-Host "=== READING saldos_de_cartera.xlsx ==="
    $wb = $excel.Workbooks.Open("C:\Users\Usuario\Downloads\corporate-billing-service\corporate-billing-service\Docs\archivosplanos\saldos_de_cartera.xlsx")
    $ws = $wb.Sheets.Item(1)
    $usedRange = $ws.UsedRange
    $rows = $usedRange.Rows.Count
    $cols = $usedRange.Columns.Count
    Write-Host "Total rows: $rows, cols: $cols"

    for ($r = 1; $r -le [Math]::Min($rows, 100); $r++) {
        $rowText = ""
        for ($c = 1; $c -le [Math]::Min($cols, 20); $c++) {
            $val = $ws.Cells.Item($r, $c).Text
            $rowText += $val + " | "
        }
        if ($rowText -match "522823" -or $r -le 5) {
            Write-Host "R$r : $rowText"
        }
    }
    $wb.Close($false)

    Write-Host "=== READING movimientocontableFACSEP.xlsx ==="
    $wb2 = $excel.Workbooks.Open("C:\Users\Usuario\Downloads\corporate-billing-service\corporate-billing-service\Docs\archivosplanos\movimientocontableFACSEP.xlsx")
    $ws2 = $wb2.Sheets.Item(1)
    $usedRange2 = $ws2.UsedRange
    $rows2 = $usedRange2.Rows.Count
    $cols2 = $usedRange2.Columns.Count
    Write-Host "Total rows in movimientocontable: $rows2, cols: $cols2"

    for ($r = 1; $r -le [Math]::Min($rows2, 100); $r++) {
        $rowText = ""
        for ($c = 1; $c -le [Math]::Min($cols2, 20); $c++) {
            $val = $ws2.Cells.Item($r, $c).Text
            $rowText += $val + " | "
        }
        if ($rowText -match "522823" -or $r -le 5) {
            Write-Host "R$r : $rowText"
        }
    }
    $wb2.Close($false)
} finally {
    $excel.Quit()
    [System.Runtime.Interopservices.Marshal]::ReleaseComObject($excel) | Out-Null
}
