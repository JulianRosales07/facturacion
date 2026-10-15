import { Pipe, PipeTransform } from '@angular/core';

@Pipe({
  name: 'copCurrency',
  standalone: true
})
export class CopCurrencyPipe implements PipeTransform {
  transform(value: number | string | null | undefined, showSymbol = true, showCode = false): string {
    if (value === null || value === undefined || value === '') {
      return '-';
    }

    const numericValue = typeof value === 'string' ? parseFloat(value) : value;
    if (isNaN(numericValue)) {
      return '-';
    }

    const formatted = new Intl.NumberFormat('es-CO', {
      style: 'currency',
      currency: 'COP',
      minimumFractionDigits: 0,
      maximumFractionDigits: 2
    }).format(numericValue);

    let result = formatted;
    if (!showSymbol) {
      result = result.replace('$', '').trim();
    }
    if (showCode) {
      result = `${result} COP`;
    }
    return result;
  }
}
