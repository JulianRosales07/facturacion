import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule, ReactiveFormsModule, FormBuilder, FormGroup, Validators } from '@angular/forms';
import { MatIconModule } from '@angular/material/icon';
import {
  BillingAdminService,
  ClientBillingRuleItem,
  BillingIncidentItem,
  BillingCalendarSummary
} from '../../../../core/services/billing-admin.service';
import { NotificationService } from '../../../../core/services/notification.service';
import { UiModalComponent } from '../../../../shared/components/ui-modal/ui-modal.component';

@Component({
  selector: 'app-billing-rules-admin',
  standalone: true,
  imports: [CommonModule, FormsModule, ReactiveFormsModule, MatIconModule, UiModalComponent],
  templateUrl: './billing-rules.component.html',
  styleUrls: ['./billing-rules.component.scss', '../../admin-page.scss']
})
export class BillingRulesAdminComponent implements OnInit {
  private readonly billingAdminService = inject(BillingAdminService);
  private readonly notification = inject(NotificationService);
  private readonly fb = inject(FormBuilder);

  readonly activeTab = signal<'rules' | 'calendar' | 'incidents'>('rules');

  // Tab 1: Clientes y Reglas
  readonly clients = signal<ClientBillingRuleItem[]>([]);
  readonly loadingClients = signal<boolean>(false);
  readonly editingClient = signal<ClientBillingRuleItem | null>(null);
  readonly clientModalOpen = signal<boolean>(false);
  readonly savingClient = signal<boolean>(false);
  clientForm!: FormGroup;

  // Tab 2: Calendario Mensual
  readonly selectedPeriod = signal<string>('2026-11');
  readonly calendarSummary = signal<BillingCalendarSummary | null>(null);
  readonly loadingCalendar = signal<boolean>(false);

  // Tab 3: Incidencias y Alertas
  readonly incidents = signal<BillingIncidentItem[]>([]);
  readonly loadingIncidents = signal<boolean>(false);
  readonly filterIncidentStatus = signal<string>('');
  readonly resolvingIncident = signal<BillingIncidentItem | null>(null);
  readonly resolveModalOpen = signal<boolean>(false);
  readonly assigningIncident = signal<BillingIncidentItem | null>(null);
  readonly assignModalOpen = signal<boolean>(false);
  readonly resolutionText = signal<string>('');
  readonly assignedPerson = signal<string>('');
  readonly followUpText = signal<string>('');
  readonly runningDetection = signal<boolean>(false);

  ngOnInit(): void {
    this.initForm();
    this.loadClients();
    this.loadCalendar();
    this.loadIncidents();
  }

  private initForm(): void {
    this.clientForm = this.fb.group({
      reglaFacturacion: ['ORDINARIA', Validators.required],
      diaCorteCorporativo: [null, [Validators.min(1), Validators.max(28)]],
      detalleReglaCorporativa: ['']
    });

    this.clientForm.get('reglaFacturacion')?.valueChanges.subscribe(rule => {
      const cutoffCtrl = this.clientForm.get('diaCorteCorporativo');
      if (rule === 'ESPECIAL_CORPORATIVA') {
        cutoffCtrl?.setValidators([Validators.required, Validators.min(1), Validators.max(28)]);
      } else {
        cutoffCtrl?.clearValidators();
        cutoffCtrl?.setValidators([Validators.min(1), Validators.max(28)]);
      }
      cutoffCtrl?.updateValueAndValidity();
    });
  }

  // --- Reglas de Clientes ---
  loadClients(): void {
    this.loadingClients.set(true);
    this.billingAdminService.listClientRules().subscribe({
      next: data => {
        this.clients.set(data);
        this.loadingClients.set(false);
      },
      error: err => {
        this.notification.error('Error al cargar clientes y reglas de facturación');
        this.loadingClients.set(false);
      }
    });
  }

  openEditClient(client: ClientBillingRuleItem): void {
    this.editingClient.set(client);
    this.clientForm.patchValue({
      reglaFacturacion: client.reglaFacturacion,
      diaCorteCorporativo: client.diaCorteCorporativo ?? (client.reglaFacturacion === 'ORDINARIA' ? 2 : null),
      detalleReglaCorporativa: client.detalleReglaCorporativa ?? ''
    });
    this.clientModalOpen.set(true);
  }

  closeClientModal(): void {
    this.clientModalOpen.set(false);
    this.editingClient.set(null);
  }

  saveClientRule(): void {
    const client = this.editingClient();
    if (!client || this.clientForm.invalid) {
      this.clientForm.markAllAsTouched();
      return;
    }

    this.savingClient.set(true);
    const formVal = this.clientForm.value;
    this.billingAdminService.updateClientBillingRule(client.id, {
      reglaFacturacion: formVal.reglaFacturacion,
      diaCorteCorporativo: formVal.diaCorteCorporativo,
      detalleReglaCorporativa: formVal.detalleReglaCorporativa
    }).subscribe({
      next: updated => {
        this.notification.success(`Regla actualizada para ${updated.razonSocial}`);
        this.savingClient.set(false);
        this.closeClientModal();
        this.loadClients();
        this.loadCalendar();
      },
      error: err => {
        this.notification.error(err.error?.message ?? 'Error al actualizar regla del cliente');
        this.savingClient.set(false);
      }
    });
  }

  // --- Calendario Mensual ---
  loadCalendar(): void {
    this.loadingCalendar.set(true);
    this.billingAdminService.getBillingCalendar(this.selectedPeriod()).subscribe({
      next: summary => {
        this.calendarSummary.set(summary);
        this.loadingCalendar.set(false);
      },
      error: () => {
        this.notification.error('Error al cargar el calendario de facturación');
        this.loadingCalendar.set(false);
      }
    });
  }

  onPeriodChange(period: string): void {
    this.selectedPeriod.set(period);
    this.loadCalendar();
  }

  // --- Incidencias ---
  loadIncidents(): void {
    this.loadingIncidents.set(true);
    const status = this.filterIncidentStatus() || undefined;
    this.billingAdminService.listIncidents(this.selectedPeriod(), status).subscribe({
      next: data => {
        this.incidents.set(data);
        this.loadingIncidents.set(false);
      },
      error: () => {
        this.notification.error('Error al cargar incidencias de facturación');
        this.loadingIncidents.set(false);
      }
    });
  }

  runDetection(): void {
    this.runningDetection.set(true);
    this.billingAdminService.triggerDetection(this.selectedPeriod()).subscribe({
      next: result => {
        this.notification.success(`Detección completada: ${result.length} incidencias registradas/activas.`);
        this.runningDetection.set(false);
        this.loadIncidents();
        this.loadCalendar();
      },
      error: err => {
        this.notification.error('Error al ejecutar la detección de incidencias');
        this.runningDetection.set(false);
      }
    });
  }

  openResolveModal(inc: BillingIncidentItem): void {
    this.resolvingIncident.set(inc);
    this.resolutionText.set('');
    this.resolveModalOpen.set(true);
  }

  confirmResolve(): void {
    const inc = this.resolvingIncident();
    if (!inc || !this.resolutionText().trim()) {
      this.notification.warning('Debe ingresar las observaciones de resolución');
      return;
    }

    this.billingAdminService.resolveIncident(inc.id, this.resolutionText().trim(), 'OPERADOR_ADMIN').subscribe({
      next: () => {
        this.notification.success('Incidencia marcada como resuelta');
        this.resolveModalOpen.set(false);
        this.resolvingIncident.set(null);
        this.loadIncidents();
      },
      error: err => {
        this.notification.error('Error al resolver incidencia');
      }
    });
  }

  openAssignModal(inc: BillingIncidentItem): void {
    this.assigningIncident.set(inc);
    this.assignedPerson.set(inc.responsableAsignado ?? '');
    this.followUpText.set(inc.observacionesSeguimiento ?? '');
    this.assignModalOpen.set(true);
  }

  confirmAssign(): void {
    const inc = this.assigningIncident();
    if (!inc || !this.assignedPerson().trim()) {
      this.notification.warning('Debe indicar el nombre del responsable');
      return;
    }

    this.billingAdminService.assignIncident(inc.id, this.assignedPerson().trim(), this.followUpText().trim()).subscribe({
      next: () => {
        this.notification.success('Responsable asignado correctamente');
        this.assignModalOpen.set(false);
        this.assigningIncident.set(null);
        this.loadIncidents();
      },
      error: err => {
        this.notification.error('Error al asignar responsable');
      }
    });
  }
}
