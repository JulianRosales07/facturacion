import { Component, inject } from '@angular/core';
import { Router } from '@angular/router';
import { AuthService } from '../../../../core/services/auth.service';
import { HOME_ROUTES } from '../../../../core/guards/permission.guard';

/** 403: la sesión es válida pero el usuario no tiene permiso para la pantalla solicitada. */
@Component({
  selector: 'app-forbidden',
  standalone: true,
  template: `
    <section class="forbidden">
      <span class="forbidden__code">403</span>
      <h1 class="forbidden__title">Sin permisos</h1>
      <p class="forbidden__text">
        Tu usuario no tiene permiso para ver esta pantalla. Si lo necesitas, solicítalo a un administrador:
        en cuanto te lo asigne, el panel se actualiza sin cerrar sesión.
      </p>
      <div class="forbidden__actions">
        @if (home) {
          <button type="button" class="btn btn--primary-red" (click)="goHome()">Ir a {{ home.label }}</button>
        }
        <button type="button" class="btn btn--outline" (click)="router.navigateByUrl('/perfil')">Mi perfil</button>
      </div>
    </section>
  `,
  styles: [`
    .forbidden { max-width: 520px; margin: 8vh auto 0; text-align: center; padding: 2rem 1.5rem;
      background: var(--bg-ffffff); border: 1px solid var(--bd-e2e8f0); border-radius: 18px; }
    .forbidden__code { font-size: 0.75rem; font-weight: 800; letter-spacing: 0.1em; color: var(--tx-c41230); }
    .forbidden__title { margin: 0.35rem 0 0.6rem; font-size: 1.5rem; font-weight: 800; color: var(--tx-0f172a); }
    .forbidden__text { margin: 0 0 1.25rem; font-size: 0.85rem; line-height: 1.55; color: var(--tx-64748b); }
    .forbidden__actions { display: flex; justify-content: center; gap: 0.5rem; flex-wrap: wrap; }
  `]
})
export class ForbiddenComponent {
  protected readonly router = inject(Router);
  private readonly auth = inject(AuthService);
  private static readonly LABELS: Record<string, string> = {
    '/dashboard': 'el resumen', '/pagos': 'pagos', '/cartera': 'cartera',
    '/admin/users': 'usuarios', '/admin/roles': 'roles', '/admin/permissions': 'permisos'
  };

  readonly home = (() => {
    const route = HOME_ROUTES.find(r => this.auth.hasAnyPermission(r.permissions));
    return route ? { path: route.path, label: ForbiddenComponent.LABELS[route.path] ?? 'inicio' } : null;
  })();

  goHome(): void {
    if (this.home) void this.router.navigateByUrl(this.home.path);
  }
}
