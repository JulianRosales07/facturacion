import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatIconModule } from '@angular/material/icon';
import { TaxService } from '../../../../core/services/tax.service';
import {
  TaxConceptValidity,
  TaxConceptCode,
  ServiceClassification,
  TaxConceptValidityRequest,
  CreateNewVersionRequest
} from '../../../../core/models/tax.model';
import { CopCurrencyPipe } from '../../../../shared/pipes/cop-currency.pipe';
import { AppDatePipe } from '../../../../shared/pipes/app-date.pipe';
import { UiCardComponent } from '../../../../shared/components/ui-card/ui-card.component';
import { UiBadgeComponent } from '../../../../shared/components/ui-badge/ui-badge.component';
import { UiModalComponent } from '../../../../shared/components/ui-modal/ui-modal.component';
import { UiSpinnerComponent } from '../../../../shared/components/ui-spinner/ui-spinner.component';
import { ErrorBannerComponent } from '../../../../shared/components/error-banner/error-banner.component';
import { NotificationService } from '../../../../core/services/notification.service';

/** Estado de una vigencia a la fecha de hoy, a partir de su estado y sus fechas. */
export type ValidityState = 'VIGENTE' | 'PROGRAMADA' | 'CERRADA' | 'INACTIVA';

/** Fecha local (yyyy-MM-dd); toISOString() usaría UTC y en la noche de Colombia daría mañana. */
function today(): string {
  const now = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
}

@Component({
  selector: 'app-tax-catalog',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatIconModule,
    CopCurrencyPipe,
    AppDatePipe,
    UiCardComponent,
    UiBadgeComponent,
    UiModalComponent,
    UiSpinnerComponent,
    ErrorBannerComponent
  ],
  templateUrl: './tax-catalog.component.html',
  styleUrls: ['./tax-catalog.component.scss']
})
export class TaxCatalogComponent implements OnInit {
  private readonly taxService = inject(TaxService);
  private readonly notification = inject(NotificationService);

  readonly validities = signal<TaxConceptValidity[]>([]);
  readonly loading = signal<boolean>(true);
  readonly errorMessage = signal<string | null>(null);

  filterCode = '';
  filterOnlyCurrent = true;

  // Formulario de concepto: crear uno nuevo o editar (corregir) una vigencia existente
  readonly showFormModal = signal(false);
  readonly isSaving = signal(false);
  editingValidity: TaxConceptValidity | null = null;
  formConceptCode: TaxConceptCode = 'RETEFUENTE';
  formName = '';
  formClassification: ServiceClassification | '' = '';
  formMunicipality = '';
  formMinBase = 0;
  formPercentage: number | null = null;
  formValidFrom = today();
  formValidTo = '';

  // Nueva versión: cambio normativo a partir de una fecha (cierra la vigencia anterior)
  readonly showVersionModal = signal(false);
  selectedValidityForVersion: TaxConceptValidity | null = null;
  versionMinBase = 0;
  versionPercentage = 0;
  versionEffectiveFrom = today();
  readonly isProcessingVersion = signal(false);

  ngOnInit(): void {
    this.loadValidities();
  }

  loadValidities(): void {
    this.loading.set(true);
    this.errorMessage.set(null);

    this.taxService.listValidities().subscribe({
      next: (data) => {
        const order: TaxConceptCode[] = ['RETEFUENTE', 'RETEICA', 'RETEIVA', 'ESTAMPILLA'];
        this.validities.set([...data].sort((a, b) =>
          order.indexOf(a.conceptCode) - order.indexOf(b.conceptCode)
          || (a.nombre ?? '').localeCompare(b.nombre ?? '')
          || b.validFrom.localeCompare(a.validFrom)));
        this.loading.set(false);
      },
      error: (err) => {
        this.loading.set(false);
        this.errorMessage.set(err?.error?.mensaje || 'Error al consultar el catálogo de impuestos.');
      }
    });
  }

  stateOf(v: TaxConceptValidity): ValidityState {
    const now = today();
    if (!v.active) return 'INACTIVA';
    if (v.validTo && v.validTo < now) return 'CERRADA';
    if (v.validFrom > now) return 'PROGRAMADA';
    return 'VIGENTE';
  }

  stateVariant(state: ValidityState): 'success' | 'info' | 'warning' | 'neutral' {
    switch (state) {
      case 'VIGENTE': return 'success';
      case 'PROGRAMADA': return 'info';
      case 'CERRADA': return 'warning';
      default: return 'neutral';
    }
  }

  get filteredValidities(): TaxConceptValidity[] {
    let list = this.validities();
    if (this.filterOnlyCurrent) {
      // Lo que hoy se ofrece en la pasarela: vigentes y programadas (no cerradas ni inactivas).
      list = list.filter(v => ['VIGENTE', 'PROGRAMADA'].includes(this.stateOf(v)));
    }
    if (this.filterCode) {
      list = list.filter(v => v.conceptCode === this.filterCode);
    }
    return list;
  }

  get isEditing(): boolean {
    return this.editingValidity !== null;
  }

  openCreateModal(): void {
    this.editingValidity = null;
    this.formConceptCode = 'RETEFUENTE';
    this.formName = '';
    this.formClassification = '';
    this.formMunicipality = '';
    this.formMinBase = 0;
    this.formPercentage = null;
    this.formValidFrom = today();
    this.formValidTo = '';
    this.showFormModal.set(true);
  }

  openEditModal(v: TaxConceptValidity): void {
    this.editingValidity = v;
    this.formConceptCode = v.conceptCode;
    this.formName = v.nombre ?? '';
    this.formClassification = v.classification ?? '';
    this.formMunicipality = v.municipality ?? '';
    this.formMinBase = v.minimumBase;
    this.formPercentage = v.porcentajeMaximo;
    this.formValidFrom = v.validFrom;
    this.formValidTo = v.validTo ?? '';
    this.showFormModal.set(true);
  }

  closeFormModal(): void {
    this.showFormModal.set(false);
    this.editingValidity = null;
  }

  get formValid(): boolean {
    return !!this.formName.trim()
      && this.formPercentage !== null && this.formPercentage > 0 && this.formPercentage <= 100
      && this.formMinBase !== null && this.formMinBase >= 0
      && !!this.formValidFrom
      && (!this.formValidTo || this.formValidTo >= this.formValidFrom);
  }

  submitForm(): void {
    if (!this.formValid || this.isSaving()) return;

    const editing = this.editingValidity;
    const req: TaxConceptValidityRequest = {
      conceptCode: this.formConceptCode,
      nombre: this.formName.trim(),
      numeroVersion: editing?.numeroVersion,
      classification: this.formClassification || undefined,
      municipality: this.formMunicipality.trim() || undefined,
      minimumBase: this.formMinBase,
      percentage: this.formPercentage!,
      validFrom: this.formValidFrom,
      validTo: this.formValidTo || null,
      active: editing ? editing.active : true
    };

    this.isSaving.set(true);
    const request$ = editing
      ? this.taxService.updateValidity(editing.id, req)
      : this.taxService.createValidity(req);

    request$.subscribe({
      next: () => {
        this.isSaving.set(false);
        this.notification.success(editing ? 'Concepto actualizado correctamente.' : 'Concepto de retención creado exitosamente.');
        this.closeFormModal();
        this.loadValidities();
      },
      error: (err) => {
        this.isSaving.set(false);
        this.notification.error(err?.error?.mensaje || (editing ? 'Error al actualizar el concepto' : 'Error al crear el concepto'));
      }
    });
  }

  openVersionModal(v: TaxConceptValidity): void {
    this.selectedValidityForVersion = v;
    this.versionMinBase = v.minimumBase;
    this.versionPercentage = v.porcentajeMaximo;
    this.versionEffectiveFrom = today();
    this.showVersionModal.set(true);
  }

  closeVersionModal(): void {
    this.showVersionModal.set(false);
    this.selectedValidityForVersion = null;
  }

  submitNewVersion(): void {
    if (!this.selectedValidityForVersion) return;

    this.isProcessingVersion.set(true);
    const req: CreateNewVersionRequest = {
      newMinimumBase: this.versionMinBase,
      newPercentage: this.versionPercentage,
      effectiveFrom: this.versionEffectiveFrom
    };

    this.taxService.createNewVersion(this.selectedValidityForVersion.id, req).subscribe({
      next: (created) => {
        this.isProcessingVersion.set(false);
        this.notification.success(`Nueva versión V${created.numeroVersion} creada correctamente.`);
        this.closeVersionModal();
        this.loadValidities();
      },
      error: (err) => {
        this.isProcessingVersion.set(false);
        this.notification.error(err?.error?.mensaje || 'Error al crear nueva versión');
      }
    });
  }

  deactivateValidity(id: string): void {
    if (confirm('¿Está seguro de desactivar esta vigencia? No aparecerá para liquidación.')) {
      this.taxService.deactivateValidity(id).subscribe({
        next: () => {
          this.notification.success('Vigencia desactivada con éxito.');
          this.loadValidities();
        },
        error: (err) => {
          this.notification.error(err?.error?.mensaje || 'Error al desactivar');
        }
      });
    }
  }
}
