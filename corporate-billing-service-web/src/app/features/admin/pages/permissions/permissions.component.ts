import { Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { UpperCasePipe } from '@angular/common';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { AccessAdminService } from '../../../../core/services/access-admin.service';
import { AuthService } from '../../../../core/services/auth.service';
import { NotificationService } from '../../../../core/services/notification.service';
import { SecurityRealtimeService } from '../../../../core/services/security-realtime.service';
import { AdminPermission, AdminRole, AdminUser, roleLabel } from '../../../../core/models/access.model';
import {
  PAGE_ACCESS_ITEMS, PAGE_MODULES, PageAccessItem, getUserAccessiblePages, resolveRoleIdsForPageKeys
} from '../../../../core/models/page-access.model';
import { HasPermissionDirective } from '../../../../shared/directives/has-permission.directive';
import { UiModalComponent } from '../../../../shared/components/ui-modal/ui-modal.component';
import { ConfirmDialogComponent } from '../../../../shared/components/confirm-dialog/confirm-dialog.component';

type Confirmation = { kind: 'status' | 'delete'; permission: AdminPermission };

@Component({
  selector: 'app-admin-permissions',
  standalone: true,
  imports: [ReactiveFormsModule, UpperCasePipe, HasPermissionDirective, UiModalComponent, ConfirmDialogComponent],
  templateUrl: './permissions.component.html',
  styleUrls: ['../../admin-page.scss']
})
export class PermissionsComponent implements OnInit {
  private readonly api = inject(AccessAdminService);
  private readonly auth = inject(AuthService);
  private readonly notification = inject(NotificationService);
  private readonly security = inject(SecurityRealtimeService);
  private readonly fb = inject(FormBuilder);
  private readonly destroyRef = inject(DestroyRef);

  readonly roleLabel = roleLabel;
  readonly permissions = signal<AdminPermission[]>([]);
  readonly users = signal<AdminUser[]>([]);
  readonly roles = signal<AdminRole[]>([]);
  readonly loading = signal(true);
  readonly search = signal('');
  readonly moduleFilter = signal('');
  readonly modules = computed(() => [...new Set(this.permissions().map(p => p.module))].sort((a, b) => a.localeCompare(b, 'es')));
  readonly visible = computed(() => {
    const text = this.search().trim().toLowerCase();
    const module = this.moduleFilter();
    return this.permissions().filter(p => (!module || p.module === module)
      && (!text || p.code.toLowerCase().includes(text) || p.name.toLowerCase().includes(text)
        || (p.description ?? '').toLowerCase().includes(text)));
  });

  // Gestión de accesos a páginas por usuario
  readonly pageAccessItems = PAGE_ACCESS_ITEMS;
  readonly pageModules = PAGE_MODULES;
  readonly selectedUserId = signal<string>('');
  readonly selectedUser = computed(() => this.users().find(u => u.id === this.selectedUserId()) ?? null);
  readonly selectedUserPageKeys = signal<Set<string>>(new Set());
  readonly savingAccess = signal(false);
  readonly showTechnicalCatalog = signal(false);

  readonly pageSearch = signal('');
  readonly pageModuleFilter = signal('');
  readonly filteredPages = computed(() => {
    const text = this.pageSearch().trim().toLowerCase();
    const module = this.pageModuleFilter();
    return this.pageAccessItems.filter(p =>
      (!module || p.module === module) &&
      (!text || p.name.toLowerCase().includes(text) || p.route.toLowerCase().includes(text) || p.description.toLowerCase().includes(text))
    );
  });

  readonly formOpen = signal(false);
  readonly editing = signal<AdminPermission | null>(null);
  readonly saving = signal(false);
  readonly viewing = signal<AdminPermission | null>(null);
  readonly confirmation = signal<Confirmation | null>(null);
  readonly confirming = signal(false);
  readonly canManage = computed(() => this.auth.hasPermission('PERMISSIONS_MANAGE'));
  readonly canUpdateUsers = computed(() => this.auth.hasPermission('USERS_UPDATE'));

  readonly form = this.fb.nonNullable.group({
    code: ['', [Validators.required, Validators.maxLength(80), Validators.pattern(/^[A-Za-z][A-Za-z0-9_]{2,79}$/)]],
    name: ['', [Validators.required, Validators.maxLength(120)]],
    module: ['', [Validators.required, Validators.maxLength(60)]],
    description: ['', [Validators.maxLength(255)]],
    active: [true]
  });

  ngOnInit(): void {
    this.security.accessChanges$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => {
      this.load(true);
      this.loadUsers();
      this.loadRoles();
    });
    this.load();
    this.loadUsers();
    this.loadRoles();
  }

  load(silent = false): void {
    if (!silent) this.loading.set(true);
    this.api.listPermissions().subscribe({
      next: list => { this.permissions.set(list); this.loading.set(false); },
      error: () => this.loading.set(false)
    });
  }

  loadUsers(): void {
    this.api.listUsers({ active: null }, 0, 100).subscribe({
      next: res => {
        this.users.set(res.items);
        // Si hay un usuario seleccionado, refrescar sus accesos
        const current = this.selectedUser();
        if (current) {
          const updated = res.items.find(u => u.id === current.id);
          if (updated) {
            const accessible = getUserAccessiblePages(updated);
            this.selectedUserPageKeys.set(new Set(accessible.map(p => p.key)));
          }
        }
      },
      error: () => this.users.set([])
    });
  }

  loadRoles(): void {
    this.api.listRoles().subscribe({
      next: roles => this.roles.set(roles),
      error: () => this.roles.set([])
    });
  }

  onSelectUser(userId: string): void {
    this.selectedUserId.set(userId);
    const user = this.users().find(u => u.id === userId);
    if (user) {
      const accessible = getUserAccessiblePages(user);
      this.selectedUserPageKeys.set(new Set(accessible.map(p => p.key)));
    } else {
      this.selectedUserPageKeys.set(new Set());
    }
  }

  togglePageKey(key: string, checked: boolean): void {
    this.selectedUserPageKeys.update(current => {
      const next = new Set(current);
      if (checked) next.add(key); else next.delete(key);
      return next;
    });
  }

  selectAllPages(): void {
    this.selectedUserPageKeys.set(new Set(this.pageAccessItems.map(p => p.key)));
  }

  selectOperationsPages(): void {
    this.selectedUserPageKeys.set(new Set(
      this.pageAccessItems.filter(p => p.module !== 'Administración').map(p => p.key)
    ));
  }

  clearAllPages(): void {
    this.selectedUserPageKeys.set(new Set());
  }

  saveUserAccess(): void {
    const user = this.selectedUser();
    if (!user) return;
    this.savingAccess.set(true);
    const roleIds = resolveRoleIdsForPageKeys(this.selectedUserPageKeys(), this.roles());
    this.api.replaceUserRoles(user.id, roleIds).subscribe({
      next: updated => {
        this.savingAccess.set(false);
        this.notification.success(`Accesos a páginas guardados con éxito para ${updated.fullName || updated.username}.`);
        this.loadUsers();
      },
      error: () => this.savingAccess.set(false)
    });
  }

  getPageItemsForModule(module: string): PageAccessItem[] {
    return this.pageAccessItems.filter(p => p.module === module);
  }

  getUserCountForPage(pageKey: string): number {
    return this.users().filter(u => getUserAccessiblePages(u).some(p => p.key === pageKey)).length;
  }

  openCreate(): void {
    this.editing.set(null);
    this.form.reset({ code: '', name: '', module: '', description: '', active: true });
    this.form.controls.code.enable();
    this.formOpen.set(true);
  }

  openEdit(permission: AdminPermission): void {
    this.editing.set(permission);
    this.form.reset({ code: permission.code, name: permission.name, module: permission.module,
      description: permission.description ?? '', active: permission.active });
    if (permission.systemPermission) this.form.controls.code.disable(); else this.form.controls.code.enable();
    this.formOpen.set(true);
  }

  openView(permission: AdminPermission): void {
    this.viewing.set(permission);
    this.api.getPermission(permission.id).subscribe({ next: detail => this.viewing.set(detail) });
  }

  invalid(control: 'code' | 'name' | 'module'): boolean {
    const c = this.form.controls[control];
    return c.invalid && (c.touched || c.dirty);
  }

  save(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const value = this.form.getRawValue();
    const editing = this.editing();
    const payload = {
      code: value.code.trim().toUpperCase(),
      name: value.name.trim(),
      module: value.module.trim(),
      description: value.description.trim() || null,
      active: value.active,
      version: editing?.version ?? null
    };
    this.saving.set(true);
    const request = editing ? this.api.updatePermission(editing.id, payload) : this.api.createPermission(payload);
    request.subscribe({
      next: permission => {
        this.saving.set(false);
        this.formOpen.set(false);
        this.notification.success(editing ? `Permiso ${permission.code} actualizado.` : `Permiso ${permission.code} creado.`);
        this.load(true);
      },
      error: () => this.saving.set(false)
    });
  }

  askStatus(permission: AdminPermission): void {
    if (!permission.active) {
      this.applyStatus(permission);
      return;
    }
    this.confirmation.set({ kind: 'status', permission });
  }

  askDelete(permission: AdminPermission): void {
    this.confirmation.set({ kind: 'delete', permission });
  }

  confirm(): void {
    const pending = this.confirmation();
    if (!pending) return;
    if (pending.kind === 'status') this.applyStatus(pending.permission); else this.applyDelete(pending.permission);
  }

  confirmationMessage(): string {
    const pending = this.confirmation();
    if (!pending) return '';
    return pending.kind === 'delete'
      ? `¿Eliminar el permiso ${pending.permission.code}? Se quitará de ${pending.permission.roleCount} rol(es).`
      : `¿Desactivar el permiso ${pending.permission.code}? Dejará de otorgarse en sus ${pending.permission.roleCount} rol(es).`;
  }

  private applyStatus(permission: AdminPermission): void {
    this.confirming.set(true);
    this.api.updatePermission(permission.id, {
      code: permission.code, name: permission.name, module: permission.module, description: permission.description,
      active: !permission.active, version: permission.version
    }).subscribe({
      next: updated => {
        this.finishConfirmation();
        this.notification.success(`Permiso ${updated.code} ${updated.active ? 'activado' : 'desactivado'}.`);
        this.load(true);
      },
      error: () => this.finishConfirmation()
    });
  }

  private applyDelete(permission: AdminPermission): void {
    this.confirming.set(true);
    this.api.deletePermission(permission.id).subscribe({
      next: () => {
        this.finishConfirmation();
        this.notification.success(`Permiso ${permission.code} eliminado.`);
        this.load(true);
      },
      error: () => this.finishConfirmation()
    });
  }

  private finishConfirmation(): void {
    this.confirming.set(false);
    this.confirmation.set(null);
  }
}
