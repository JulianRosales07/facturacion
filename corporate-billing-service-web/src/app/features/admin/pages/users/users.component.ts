import { Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DatePipe, UpperCasePipe } from '@angular/common';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Subject, debounceTime } from 'rxjs';
import { AccessAdminService } from '../../../../core/services/access-admin.service';
import { AuthService } from '../../../../core/services/auth.service';
import { NotificationService } from '../../../../core/services/notification.service';
import { SecurityRealtimeService } from '../../../../core/services/security-realtime.service';
import { AdminRole, AdminUser, UserFilter, roleLabel } from '../../../../core/models/access.model';
import {
  PAGE_ACCESS_ITEMS, PAGE_MODULES, PageAccessItem, getUserAccessiblePages, resolveRoleIdsForPageKeys
} from '../../../../core/models/page-access.model';
import { HasPermissionDirective } from '../../../../shared/directives/has-permission.directive';
import { UiModalComponent } from '../../../../shared/components/ui-modal/ui-modal.component';
import { ConfirmDialogComponent } from '../../../../shared/components/confirm-dialog/confirm-dialog.component';

type Confirmation = { kind: 'status' | 'delete'; user: AdminUser };

@Component({
  selector: 'app-admin-users',
  standalone: true,
  imports: [ReactiveFormsModule, DatePipe, UpperCasePipe, HasPermissionDirective, UiModalComponent, ConfirmDialogComponent],
  templateUrl: './users.component.html',
  styleUrls: ['../../admin-page.scss']
})
export class UsersComponent implements OnInit {
  private readonly api = inject(AccessAdminService);
  private readonly auth = inject(AuthService);
  private readonly notification = inject(NotificationService);
  private readonly security = inject(SecurityRealtimeService);
  private readonly fb = inject(FormBuilder);
  private readonly destroyRef = inject(DestroyRef);
  private readonly filterChanges = new Subject<void>();

  readonly pageSize = 20;
  readonly roleLabel = roleLabel;
  readonly users = signal<AdminUser[]>([]);
  readonly roles = signal<AdminRole[]>([]);
  readonly loading = signal(true);
  readonly page = signal(0);
  readonly totalPages = signal(0);
  readonly totalItems = signal(0);
  readonly filter = signal<UserFilter>({ active: null });

  readonly formOpen = signal(false);
  readonly editing = signal<AdminUser | null>(null);
  readonly saving = signal(false);
  readonly selectedRoles = signal<Set<string>>(new Set());
  readonly viewing = signal<AdminUser | null>(null);
  readonly confirmation = signal<Confirmation | null>(null);
  readonly confirming = signal(false);

  // Gestión directa de accesos a páginas
  readonly pageAccessModalOpen = signal(false);
  readonly selectedUserForAccess = signal<AdminUser | null>(null);
  readonly selectedUserPageKeys = signal<Set<string>>(new Set());
  readonly savingAccess = signal(false);
  readonly pageAccessItems = PAGE_ACCESS_ITEMS;
  readonly pageModules = PAGE_MODULES;
  readonly getUserAccessiblePages = getUserAccessiblePages;

  readonly canUpdate = computed(() => this.auth.hasPermission('USERS_UPDATE'));
  readonly canDelete = computed(() => this.auth.hasPermission('USERS_DELETE'));
  /** Editar el propio usuario puede dejarlo sin acceso administrativo. */
  readonly editingSelf = computed(() => {
    const user = this.editing();
    return !!user && user.username.toLowerCase() === (this.auth.currentUser()?.username ?? '').toLowerCase();
  });

  readonly form = this.fb.nonNullable.group({
    username: ['', [Validators.required, Validators.minLength(3), Validators.maxLength(100), Validators.pattern(/^[A-Za-z0-9._@-]+$/)]],
    firstName: ['', [Validators.required, Validators.maxLength(100)]],
    lastName: ['', [Validators.required, Validators.maxLength(100)]],
    email: ['', [Validators.required, Validators.email, Validators.maxLength(255)]],
    active: [true]
  });

  ngOnInit(): void {
    this.filterChanges.pipe(debounceTime(300), takeUntilDestroyed(this.destroyRef)).subscribe(() => {
      this.page.set(0);
      this.load();
    });
    // Otro administrador cambió usuarios o roles: se vuelve a consultar.
    this.security.accessChanges$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => this.load(true));
    this.load();
    this.loadRoles();
  }

  load(silent = false): void {
    if (!silent) this.loading.set(true);
    this.api.listUsers(this.filter(), this.page(), this.pageSize).subscribe({
      next: page => {
        this.users.set(page.items);
        this.totalPages.set(page.totalPages);
        this.totalItems.set(page.totalItems);
        this.loading.set(false);
      },
      error: () => this.loading.set(false)
    });
  }

  loadRoles(): void {
    this.api.listRoles().subscribe({ next: roles => this.roles.set(roles), error: () => this.roles.set([]) });
  }

  setFilter(key: keyof UserFilter, value: string): void {
    const parsed = key === 'active' ? (value === '' ? null : value === 'true') : value;
    this.filter.update(current => ({ ...current, [key]: parsed }));
    this.filterChanges.next();
  }

  goTo(page: number): void {
    if (page < 0 || page >= this.totalPages()) return;
    this.page.set(page);
    this.load();
  }

  openCreate(): void {
    this.editing.set(null);
    this.form.reset({ username: '', firstName: '', lastName: '', email: '', active: true });
    this.form.controls.username.enable();
    this.selectedRoles.set(new Set());
    this.formOpen.set(true);
  }

  openEdit(user: AdminUser): void {
    this.editing.set(user);
    this.form.reset({ username: user.username, firstName: user.firstName, lastName: user.lastName, email: user.email, active: user.active });
    this.form.controls.username.disable();
    this.selectedRoles.set(new Set(user.roles.map(role => role.id)));
    this.formOpen.set(true);
  }

  openView(user: AdminUser): void {
    this.viewing.set(user);
    this.api.getUser(user.id).subscribe({ next: detail => this.viewing.set(detail) });
  }

  toggleRole(roleId: string, checked: boolean): void {
    this.selectedRoles.update(current => {
      const next = new Set(current);
      if (checked) next.add(roleId); else next.delete(roleId);
      return next;
    });
  }

  openPageAccess(user: AdminUser): void {
    this.selectedUserForAccess.set(user);
    const accessible = this.getUserAccessiblePages(user);
    this.selectedUserPageKeys.set(new Set(accessible.map(p => p.key)));
    this.pageAccessModalOpen.set(true);
  }

  toggleUserPageKey(key: string, checked: boolean): void {
    this.selectedUserPageKeys.update(current => {
      const next = new Set(current);
      if (checked) next.add(key); else next.delete(key);
      return next;
    });
  }

  selectAllPagesForUser(): void {
    this.selectedUserPageKeys.set(new Set(this.pageAccessItems.map(p => p.key)));
  }

  selectOperationsPagesForUser(): void {
    this.selectedUserPageKeys.set(new Set(
      this.pageAccessItems.filter(p => p.module !== 'Administración').map(p => p.key)
    ));
  }

  clearAllPagesForUser(): void {
    this.selectedUserPageKeys.set(new Set());
  }

  saveUserPageAccess(): void {
    const user = this.selectedUserForAccess();
    if (!user) return;
    this.savingAccess.set(true);
    const roleIds = resolveRoleIdsForPageKeys(this.selectedUserPageKeys(), this.roles());
    this.api.replaceUserRoles(user.id, roleIds).subscribe({
      next: updated => {
        this.savingAccess.set(false);
        this.pageAccessModalOpen.set(false);
        this.notification.success(`Accesos a páginas actualizados para ${updated.username}.`);
        this.load(true);
      },
      error: () => this.savingAccess.set(false)
    });
  }

  getPageItemsForModule(module: string): PageAccessItem[] {
    return this.pageAccessItems.filter(p => p.module === module);
  }

  invalid(control: 'username' | 'firstName' | 'lastName' | 'email'): boolean {
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
      username: value.username.trim(),
      email: value.email.trim(),
      firstName: value.firstName.trim(),
      lastName: value.lastName.trim(),
      active: value.active,
      roleIds: [...this.selectedRoles()],
      version: editing?.version ?? null
    };
    this.saving.set(true);
    const request = editing ? this.api.updateUser(editing.id, payload) : this.api.createUser(payload);
    request.subscribe({
      next: user => {
        this.saving.set(false);
        this.formOpen.set(false);
        this.notification.success(editing ? `Usuario ${user.username} actualizado.` : `Usuario ${user.username} creado.`);
        this.load(true);
      },
      error: () => this.saving.set(false)
    });
  }

  askStatus(user: AdminUser): void {
    if (!user.active) {
      this.applyStatus(user);
      return;
    }
    this.confirmation.set({ kind: 'status', user });
  }

  askDelete(user: AdminUser): void {
    this.confirmation.set({ kind: 'delete', user });
  }

  confirm(): void {
    const pending = this.confirmation();
    if (!pending) return;
    if (pending.kind === 'status') this.applyStatus(pending.user);
    else this.applyDelete(pending.user);
  }

  confirmationMessage(): string {
    const pending = this.confirmation();
    if (!pending) return '';
    return pending.kind === 'delete'
      ? `¿Eliminar al usuario ${pending.user.username}? Perderá todo acceso; el registro se conserva para auditoría.`
      : `¿Desactivar al usuario ${pending.user.username}? No podrá usar el panel hasta que se active de nuevo.`;
  }

  isSelf(user: AdminUser): boolean {
    return user.username.toLowerCase() === (this.auth.currentUser()?.username ?? '').toLowerCase();
  }

  private applyStatus(user: AdminUser): void {
    this.confirming.set(true);
    this.api.changeUserStatus(user.id, !user.active, user.version).subscribe({
      next: updated => {
        this.finishConfirmation();
        this.notification.success(`Usuario ${updated.username} ${updated.active ? 'activado' : 'desactivado'}.`);
        this.load(true);
      },
      error: () => this.finishConfirmation()
    });
  }

  private applyDelete(user: AdminUser): void {
    this.confirming.set(true);
    this.api.deleteUser(user.id).subscribe({
      next: () => {
        this.finishConfirmation();
        this.notification.success(`Usuario ${user.username} eliminado.`);
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
