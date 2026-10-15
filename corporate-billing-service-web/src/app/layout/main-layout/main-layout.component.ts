import { Component, signal, HostListener, effect, inject } from '@angular/core';
import { AdminRealtimeService } from '../../core/services/admin-realtime.service';
import { SecurityRealtimeService } from '../../core/services/security-realtime.service';
import { AuthService } from '../../core/services/auth.service';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { SidebarComponent } from '../components/sidebar/sidebar.component';
import { TopbarComponent } from '../components/topbar/topbar.component';

@Component({
  selector: 'app-main-layout',
  standalone: true,
  imports: [CommonModule, RouterModule, SidebarComponent, TopbarComponent],
  templateUrl: './main-layout.component.html',
  styleUrls: ['./main-layout.component.scss']
})
export class MainLayoutComponent {
  readonly isSidebarCollapsed = signal(false);
  readonly isMobileDrawerOpen = signal(false);

  constructor() {
    // Una sola conexión WebSocket para todo el panel, abierta al entrar al área autenticada.
    // El canal de seguridad mantiene los permisos al día; el administrativo solo con permiso para verlo
    // (el servidor rechazaría la suscripción y cerraría la conexión).
    inject(SecurityRealtimeService).start();
    const adminRealtime = inject(AdminRealtimeService);
    const auth = inject(AuthService);
    effect(() => {
      if (auth.hasAnyPermission(['INVOICES_VIEW', 'PAYMENTS_VIEW'])) adminRealtime.start();
      else adminRealtime.stop();
    });
  }

  @HostListener('window:resize')
  onResize(): void {
    if (window.innerWidth >= 1024) {
      this.isMobileDrawerOpen.set(false);
    }
  }

  toggleSidebar(): void {
    if (window.innerWidth < 1024) {
      this.isMobileDrawerOpen.update((open) => !open);
    } else {
      this.isSidebarCollapsed.update((collapsed) => !collapsed);
    }
  }

  closeMobileDrawer(): void {
    this.isMobileDrawerOpen.set(false);
  }
}
