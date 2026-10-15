import { Component, input } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { PaymentWithholdingSnapshot } from '../../../core/models/payment.model';
import { CHANNEL_LABELS } from '../../../core/models/withholding-audit.model';
import { CopCurrencyPipe } from '../../pipes/cop-currency.pipe';
import { BOGOTA_OFFSET } from '../withholding-audit-panel/withholding-audit-panel.component';

/**
 * Retenciones con que se liquidó un pago, en una tabla compacta que cabe en el modal y en el
 * detalle: cada celda agrupa datos relacionados (concepto + factura + versión, % + máximo,
 * base + mínima) e incluye cuándo se seleccionó y aplicó cada retención.
 */
@Component({
  selector: 'app-payment-withholdings-table',
  standalone: true,
  imports: [DatePipe, DecimalPipe, CopCurrencyPipe],
  templateUrl: './payment-withholdings-table.component.html',
  styleUrls: ['./payment-withholdings-table.component.scss']
})
export class PaymentWithholdingsTableComponent {
  readonly withholdings = input.required<PaymentWithholdingSnapshot[]>();
  readonly offset = BOGOTA_OFFSET;

  channel(r: PaymentWithholdingSnapshot): string {
    return r.canal ? CHANNEL_LABELS[r.canal] : 'Canal no registrado';
  }

  /** Solo se muestra el ingresado cuando difiere del aplicado. */
  enteredDiffers(r: PaymentWithholdingSnapshot): boolean {
    return r.porcentajeIngresado != null && Number(r.porcentajeIngresado) !== Number(r.porcentaje);
  }
}
