import { Directive, TemplateRef, ViewContainerRef, effect, inject, input } from '@angular/core';
import { AuthService } from '../../core/services/auth.service';

/**
 * Muestra el contenido solo si el usuario tiene el permiso (o alguno de los permisos) indicado.
 * Reacciona en vivo cuando los permisos cambian. Es solo experiencia de usuario: el backend
 * sigue siendo la autoridad.
 *
 *   <button *hasPermission="'USERS_CREATE'">Nuevo usuario</button>
 *   <section *hasPermission="['ROLES_UPDATE', 'PERMISSIONS_ASSIGN']">…</section>
 */
@Directive({ selector: '[hasPermission]', standalone: true })
export class HasPermissionDirective {
  private readonly auth = inject(AuthService);
  private readonly template = inject(TemplateRef<unknown>);
  private readonly container = inject(ViewContainerRef);
  private rendered = false;

  readonly hasPermission = input.required<string | readonly string[]>();

  constructor() {
    effect(() => {
      const required = this.hasPermission();
      const codes = typeof required === 'string' ? [required] : required;
      const allowed = this.auth.hasAnyPermission(codes) && codes.length > 0;
      if (allowed && !this.rendered) {
        this.container.createEmbeddedView(this.template);
        this.rendered = true;
      } else if (!allowed && this.rendered) {
        this.container.clear();
        this.rendered = false;
      }
    });
  }
}
