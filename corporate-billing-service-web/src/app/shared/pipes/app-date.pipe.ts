import { Pipe, PipeTransform } from '@angular/core';

@Pipe({
  name: 'appDate',
  standalone: true
})
export class AppDatePipe implements PipeTransform {
  transform(
    value: string | Date | null | undefined,
    format: 'short' | 'medium' | 'long' | 'datetime' = 'short'
  ): string {
    if (!value) return '-';

    // Las fechas civiles de factura no representan medianoche UTC.
    const parts = typeof value === 'string' ? /^(\d{4})-(\d{2})-(\d{2})$/.exec(value) : null;
    const date = parts ? new Date(Number(parts[1]), Number(parts[2])-1, Number(parts[3]))
      : typeof value === 'string' ? new Date(value) : value;
    if (parts && (date.getFullYear() !== Number(parts[1]) || date.getMonth() !== Number(parts[2])-1 || date.getDate() !== Number(parts[3]))) return '-';
    if (isNaN(date.getTime())) return '-';

    switch (format) {
      case 'short':
        // DD/MM/YYYY
        return new Intl.DateTimeFormat('es-CO', {
          day: '2-digit',
          month: '2-digit',
          year: 'numeric'
        }).format(date);

      case 'datetime':
        // DD/MM/YYYY hh:mm a
        return new Intl.DateTimeFormat('es-CO', {
          day: '2-digit',
          month: '2-digit',
          year: 'numeric',
          hour: '2-digit',
          minute: '2-digit',
          hour12: true
        }).format(date);

      case 'medium':
        // 22 sep 2026
        return new Intl.DateTimeFormat('es-CO', {
          day: 'numeric',
          month: 'short',
          year: 'numeric'
        }).format(date);

      case 'long':
        // 22 de septiembre de 2026
        return new Intl.DateTimeFormat('es-CO', {
          day: 'numeric',
          month: 'long',
          year: 'numeric'
        }).format(date);

      default:
        return date.toLocaleDateString('es-CO');
    }
  }
}
