import { Component, Input, Output, EventEmitter, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterModule } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatMenuModule } from '@angular/material/menu';
import { AuthService } from '../../../core/services/auth.service';
import { NotificationService } from '../../../core/services/notification.service';

export interface NavItem {
  path: string;
  label: string;
  icon: string;
  badge?: string;
  badgeType?: 'success' | 'muted' | 'default';
  disabled?: boolean;
}

export interface NavSection {
  title?: string;
  items: NavItem[];
}

@Component({
  selector: 'app-sidebar',
  standalone: true,
  imports: [CommonModule, RouterModule, MatIconModule, MatButtonModule, MatMenuModule],
  templateUrl: './sidebar.component.html',
  styleUrls: ['./sidebar.component.scss']
})
export class SidebarComponent {
  private readonly authService = inject(AuthService);
  private readonly notification = inject(NotificationService);

  private readonly router = inject(Router);

  @Input() collapsed = false;
  @Output() navigate = new EventEmitter<void>();
  @Output() toggleCollapse = new EventEmitter<void>();

  readonly currentUser = this.authService.currentUser;
  readonly isDarkMode = signal(false);
  readonly avatarFailed = signal(false);

  get avatarUrl(): string | undefined {
    return this.currentUser()?.avatarUrl;
  }

  readonly navSections: NavSection[] = [
    { title: 'Recepci\u00f3n de facturas', items: [
      {path:'/dashboard',label:'Resumen de recepci\u00f3n',icon:'grid'},
      {path:'/consultar-facturas',label:'Facturas recibidas',icon:'file-text'},
      {path:'/sincronizacion',label:'Sincronizaci\u00f3n e historial',icon:'sync'}
    ]},
    { title: 'Gesti\u00f3n de facturaci\u00f3n', items: [
      {path:'/integracion-ordenes',label:'Importaci\u00f3n de \u00f3rdenes',icon:'upload'}
    ]},
    { title: 'Cartera y tributos', items: [
      {path:'/cartera',label:'Cartera',icon:'wallet'},
      {path:'/pagos',label:'Pagos y retenciones',icon:'dollar'},
      {path:'/catalogo-tributario',label:'Cat\u00e1logo tributario',icon:'activity'}
    ]}
  ];

  onNavClick(): void {
    this.navigate.emit();
  }

  onToggle(): void {
    this.toggleCollapse.emit();
  }

  toggleTheme(dark: boolean): void {
    this.isDarkMode.set(dark);
    if (dark) {
      document.documentElement.classList.add('dark');
    } else {
      document.documentElement.classList.remove('dark');
    }
  }

  logout(): void {
    this.authService.logout();
    this.notification.info('Sesión finalizada correctamente');
  }

  onProfile(): void {
    void this.router.navigate(['/perfil']);
  }

  onSettings(): void {
    this.notification.info('Configuración del sistema');
  }
}
