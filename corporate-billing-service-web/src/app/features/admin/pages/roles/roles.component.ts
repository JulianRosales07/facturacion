import { Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { AccessAdminService } from '../../../../core/services/access-admin.service';
import { AuthService } from '../../../../core/services/auth.service';
import { NotificationService } from '../../../../core/services/notification.service';
import { SecurityRealtimeService } from '../../../../core/services/security-realtime.service';
import { ACCESS_ADMIN_PERMISSIONS, AdminPermission, AdminRole, roleLabel } from '../../../../core/models/access.model';
import { HasPermissionDirective } from '../../../../shared/directives/has-permission.directive';
import { UiModalComponent } from '../../../../shared/components/ui-modal/ui-modal.component';
import { ConfirmDialogComponent } from '../../../../shared/components/confirm-dialog/confirm-dialog.component';

export interface PermissionModule { name: string; permissions: AdminPermission[]; }

/** Agrupa el catálogo por módulo (orden alfabético) para no mostrar una lista plana. */
export function groupByModule(permissions: AdminPermission[]): PermissionModule[] {
  const modules = new Map<string, AdminPermission[]>();
  for (const permission of permissions) {
    const list = modules.get(permission.module) ?? [];
    list.push(permission);
    modules.set(permission.module, list);
  }
  return [...modules.entries()]
    .sort(([a], [b]) => a.localeCompare(b, 'es'))
    .map(([name, list]) => ({ name, permissions: list.sort((a, b) => a.code.localeCompare(b.code)) }));
}

type Confirmation = { kind: 'status' | 'delete'; role: AdminRole };

@Component({
  selector: 'app-admin-roles',
  standalone: true,
  imports: [ReactiveFormsModule, HasPermissionDirective, UiModalComponent, ConfirmDialogComponent],
  templateUrl: './roles.component.html',
  styleUrls: ['../../admin-page.scss']
})
export class RolesComponent implements OnInit {
  private readonly api = inject(AccessAdminService);
  protected readonly auth = inject(AuthService);
  private readonly notification = inject(NotificationService);
  private readonly security = inject(SecurityRealtimeService);
  private readonly fb = inject(FormBuilder);
  private readonly destroyRef = inject(DestroyRef);

  readonly roleLabel = roleLabel;
  readonly roles = signal<AdminRole[]>([]);
  readonly permissions = signal<AdminPermission[]>([]);
  readonly modules = computed(() => groupByModule(this.permissions()));
  readonly loading = signal(true);

  readonly formOpen = signal(false);
  /** 'details': nombre/descripción/estado (+permisos); 'permissions': solo la asignación de permisos. */
  readonly formMode = signal<'details' | 'permissions'>('details');
  readonly editing = signal<AdminRole | null>(null);
  readonly selected = signal<Set<string>>(new Set());
  readonly saving = signal(false);
  readonly viewing = signal<AdminRole | null>(null);
  readonly confirmation = signal<Confirmation | null>(null);
  readonly confirming = signal(false);

  readonly canUpdate = computed(() => this.auth.hasPermission('ROLES_UPDATE'));
  readonly canDelete = computed(() => this.auth.hasPermission('ROLES_DELETE'));
  readonly canAssign = computed(() => this.auth.hasPermission('PERMISSIONS_ASSIGN'));

  readonly form = this.fb.nonNullable.group({
    name: ['', [Validators.required, Validators.maxLength(60), Validators.pattern(/^[A-Za-z][A-Za-z0-9_ ]{2,59}$/)]],
    description: ['', [Validators.maxLength(255)]],
    active: [true]
  });
  private readonly activeValue = toSignal(this.form.controls.active.valueChanges, { initialValue: true });

  /** El administrador va a quitarse a sí mismo la capacidad de administrar accesos. */
  readonly selfLockWarning = computed(() => {
    const role = this.editing();
    if (!role || !this.auth.hasRole(role.name)) return false;
    const codes = new Set(this.permissions().filter(p => this.selected().has(p.id)).map(p => p.code));
    const losesPermissions = this.formMode() === 'permissions' || this.canAssign()
      ? ACCESS_ADMIN_PERMISSIONS.some(code => !codes.has(code)) : false;
    return losesPermissions || (this.formMode() === 'details' && !this.activeValue());
  });

  ngOnInit(): void {
    this.security.accessChanges$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => this.load(true));
    this.load();
    this.api.listPermissions().subscribe({ next: list => this.permissions.set(list), error: () => this.permissions.set([]) });
  }

  load(silent = false): void {
    if (!silent) this.loading.set(true);
    this.api.listRoles().subscribe({
      next: roles => { this.roles.set(roles); this.loading.set(false); },
      error: () => this.loading.set(false)
    });
  }

  openCreate(): void {
    this.editing.set(null);
    this.formMode.set('details');
    this.form.reset({ name: '', description: '', active: true });
    this.form.controls.name.enable();
    this.selected.set(new Set());
    this.formOpen.set(true);
  }

  openEdit(role: AdminRole, mode: 'details' | 'permissions' = 'details'): void {
    this.editing.set(role);
    this.formMode.set(mode);
    this.form.reset({ name: role.name, description: role.description ?? '', active: role.active });
    if (role.systemRole) this.form.controls.name.disable(); else this.form.controls.name.enable();
    this.selected.set(new Set());
    this.formOpen.set(true);
    // El detalle trae los permisos asignados.
    this.api.getRole(role.id).subscribe({
      next: detail => {
        this.editing.set(detail);
        this.selected.set(new Set((detail.permissions ?? []).map(p => p.id)));
      }
    });
  }

  openView(role: AdminRole): void {
    this.viewing.set(role);
    this.api.getRole(role.id).subscribe({ next: detail => this.viewing.set(detail) });
  }

  toggle(permissionId: string, checked: boolean): void {
    this.selected.update(current => {
      const next = new Set(current);
      if (checked) next.add(permissionId); else next.delete(permissionId);
      return next;
    });
  }

  /** Selecciona o quita todos los permisos de un módulo; cada permiso sigue siendo individual. */
  toggleModule(module: PermissionModule, checked: boolean): void {
    this.selected.update(current => {
      const next = new Set(current);
      module.permissions.forEach(p => checked ? next.add(p.id) : next.delete(p.id));
      return next;
    });
  }

  moduleState(module: PermissionModule): 'all' | 'some' | 'none' {
    const count = module.permissions.filter(p => this.selected().has(p.id)).length;
    return count === 0 ? 'none' : count === module.permissions.length ? 'all' : 'some';
  }

  invalidName(): boolean {
    const c = this.form.controls.name;
    return c.invalid && (c.touched || c.dirty);
  }

  save(): void {
    const editing = this.editing();
    if (this.formMode() === 'permissions' && editing) {
      this.saving.set(true);
      this.api.replaceRolePermissions(editing.id, [...this.selected()]).subscribe({
        next: role => this.saved(`Permisos de ${roleLabel(role.name)} actualizados.`),
        error: () => this.saving.set(false)
      });
      return;
    }
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const value = this.form.getRawValue();
    const payload = {
      name: value.name.trim(),
      description: value.description.trim() || null,
      active: value.active,
      // Sin PERMISSIONS_ASSIGN no se envían permisos (el backend lo exigiría).
      permissionIds: this.canAssign() ? [...this.selected()] : undefined,
      version: editing?.version ?? null
    };
    this.saving.set(true);
    const request = editing ? this.api.updateRole(editing.id, payload) : this.api.createRole(payload);
    request.subscribe({
      next: role => this.saved(editing ? `Rol ${roleLabel(role.name)} actualizado.` : `Rol ${roleLabel(role.name)} creado.`),
      error: () => this.saving.set(false)
    });
  }

  askStatus(role: AdminRole): void {
    if (!role.active) {
      this.applyStatus(role);
      return;
    }
    this.confirmation.set({ kind: 'status', role });
  }

  askDelete(role: AdminRole): void {
    this.confirmation.set({ kind: 'delete', role });
  }

  confirm(): void {
    const pending = this.confirmation();
    if (!pending) return;
    if (pending.kind === 'status') this.applyStatus(pending.role); else this.applyDelete(pending.role);
  }

  confirmationMessage(): string {
    const pending = this.confirmation();
    if (!pending) return '';
    const name = roleLabel(pending.role.name);
    return pending.kind === 'delete'
      ? `¿Eliminar el rol ${name}? Se retirará a ${pending.role.userCount} usuario(s) que lo tienen asignado.`
      : `¿Desactivar el rol ${name}? Sus ${pending.role.userCount} usuario(s) perderán los permisos que otorga.`;
  }

  private applyStatus(role: AdminRole): void {
    this.confirming.set(true);
    this.api.updateRole(role.id, { name: role.name, description: role.description, active: !role.active, version: role.version }).subscribe({
      next: updated => {
        this.finishConfirmation();
        this.notification.success(`Rol ${roleLabel(updated.name)} ${updated.active ? 'activado' : 'desactivado'}.`);
        this.load(true);
      },
      error: () => this.finishConfirmation()
    });
  }

  private applyDelete(role: AdminRole): void {
    this.confirming.set(true);
    this.api.deleteRole(role.id).subscribe({
      next: () => {
        this.finishConfirmation();
        this.notification.success(`Rol ${roleLabel(role.name)} eliminado.`);
        this.load(true);
      },
      error: () => this.finishConfirmation()
    });
  }

  private saved(message: string): void {
    this.saving.set(false);
    this.formOpen.set(false);
    this.notification.success(message);
    this.load(true);
  }

  private finishConfirmation(): void {
    this.confirming.set(false);
    this.confirmation.set(null);
  }
}
