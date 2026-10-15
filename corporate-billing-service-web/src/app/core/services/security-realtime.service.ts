import { Injectable, inject } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, Subject } from 'rxjs';
import { AuthService } from './auth.service';
import { RealtimeEvent, RealtimeService } from './realtime.service';
import { FORBIDDEN_PATH, requiredPermissions } from '../guards/permission.guard';

/** Avisos de cambios en usuarios, roles y permisos (solo identificadores). */
export const SECURITY_TOPIC = '/topic/security';
export const SECURITY_EVENTS = ['USER_UPDATED', 'USER_ROLES_CHANGED', 'ROLE_UPDATED', 'ROLE_PERMISSIONS_CHANGED', 'PERMISSION_UPDATED'];

/**
 * Mantiene los permisos del usuario conectado al día sin cerrar sesión: ante cada aviso de
 * seguridad (o al reconectar, por si se perdió alguno) vuelve a consultar /api/v1/auth/me y, si la
 * pantalla actual dejó de estar permitida, lleva a "Sin permisos".
 */
@Injectable({ providedIn: 'root' })
export class SecurityRealtimeService {
  private readonly realtime = inject(RealtimeService);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly changes = new Subject<RealtimeEvent | null>();
  private started = false;

  /** Cambios de configuración de accesos; null = reconexión. Las pantallas de administración recargan. */
  readonly accessChanges$: Observable<RealtimeEvent | null> = this.changes.asObservable();

  start(): void {
    if (this.started) return;
    this.started = true;
    this.realtime.subscribe(SECURITY_TOPIC, event => {
      if (SECURITY_EVENTS.includes(event.eventType)) void this.refresh(event);
    });
    this.realtime.reconnected$.subscribe(() => void this.refresh(null));
  }

  async refresh(event: RealtimeEvent | null): Promise<void> {
    const stillHasAccess = await this.auth.refreshPermissions();
    this.changes.next(event);
    if (!stillHasAccess) {
      void this.router.navigateByUrl(FORBIDDEN_PATH);
      return;
    }
    const required = requiredPermissions(this.router.routerState.snapshot.root);
    if (!this.auth.hasAnyPermission(required)) {
      void this.router.navigateByUrl(FORBIDDEN_PATH);
    }
  }
}
