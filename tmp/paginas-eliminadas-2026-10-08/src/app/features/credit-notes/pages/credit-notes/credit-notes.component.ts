import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatIconModule } from '@angular/material/icon';
import { InvoiceService } from '../../../../core/services/invoice.service';
import { CreditNoteService } from '../../../../core/services/credit-note.service';
import { NotaCreditoResponseDto } from '../../../../core/models/credit-note.model';
import { CopCurrencyPipe } from '../../../../shared/pipes/cop-currency.pipe';
import { AppDatePipe } from '../../../../shared/pipes/app-date.pipe';
import { UiCardComponent } from '../../../../shared/components/ui-card/ui-card.component';
import { UiButtonComponent } from '../../../../shared/components/ui-button/ui-button.component';
import { UiBadgeComponent } from '../../../../shared/components/ui-badge/ui-badge.component';
import { UiSpinnerComponent } from '../../../../shared/components/ui-spinner/ui-spinner.component';
import { ErrorBannerComponent } from '../../../../shared/components/error-banner/error-banner.component';
import { NotificationService } from '../../../../core/services/notification.service';

@Component({
  selector: 'app-credit-notes',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatIconModule,
    CopCurrencyPipe,
    AppDatePipe,
    UiCardComponent,
    UiBadgeComponent,
    UiSpinnerComponent,
    ErrorBannerComponent
  ],
  templateUrl: './credit-notes.component.html',
  styleUrls: ['./credit-notes.component.scss']
})
export class CreditNotesComponent implements OnInit {
  private readonly invoiceService = inject(InvoiceService);
  private readonly creditNoteService = inject(CreditNoteService);
  private readonly notification = inject(NotificationService);

  searchInvoiceId = '';
  readonly loading = signal<boolean>(false);
  readonly errorMessage = signal<string | null>(null);
  readonly creditNotes = signal<NotaCreditoResponseDto[]>([]);

  ngOnInit(): void {
    // Cargar notas crédito de facturas recientes si aplica
  }

  onSearchByInvoice(): void {
    if (!this.searchInvoiceId.trim()) {
      this.notification.warning('Ingrese el UUID o identificador de la factura.');
      return;
    }

    this.loading.set(true);
    this.errorMessage.set(null);

    this.invoiceService.getCreditNotesByInvoice(this.searchInvoiceId.trim()).subscribe({
      next: (data) => {
        this.creditNotes.set(data);
        this.loading.set(false);
      },
      error: (err) => {
        this.loading.set(false);
        this.errorMessage.set(err?.error?.mensaje || 'Error al consultar notas crédito de la factura.');
      }
    });
  }
}
