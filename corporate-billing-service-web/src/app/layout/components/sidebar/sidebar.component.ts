import { Component, Input, Output, EventEmitter, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterModule } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatMenuModule } from '@angular/material/menu';
import { AuthService } from '../../../core/services/auth.service';
import { NotificationService } from '../../../core/services/notification.service';
import { ThemeService } from '../../../core/services/theme.service';

export interface NavItem {
  path: string;
  label: string;
  icon: string;
  badge?: string;
  badgeType?: 'success' | 'muted' | 'default';
  disabled?: boolean;
  /** Basta con uno de estos permisos para mostrar la opción (solo interfaz; el backend valida). */
  permissions?: string[];
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
  private readonly theme = inject(ThemeService);
  readonly isDarkMode = this.theme.isDark;
  readonly avatarFailed = signal(false);

  get avatarUrl(): string | undefined {
    return this.currentUser()?.avatarUrl;
  }

  static readonly NAV_SECTIONS: NavSection[] = [
    { title: 'Operaci\u00f3n diaria', items: [
      {path:'/dashboard',label:'Resumen de recepci\u00f3n',icon:'grid',permissions:['INVOICES_VIEW']},
      {path:'/consultar-facturas',label:'Facturas recibidas',icon:'file-text',permissions:['INVOICES_VIEW']},
      {path:'/integracion-ordenes',label:'Importaci\u00f3n de \u00f3rdenes',icon:'upload',permissions:['IMPORTS_VIEW']},
      {path:'/sincronizacion',label:'Sincronizaci\u00f3n e historial',icon:'sync',permissions:['SYNC_VIEW']}
    ]},
    { title: 'Cartera y tributos', items: [
      {path:'/cartera',label:'Cartera',icon:'wallet',permissions:['CARTERA_VIEW']},
      {path:'/pagos',label:'Pagos y retenciones',icon:'dollar',permissions:['PAYMENTS_VIEW']},
      {path:'/auditoria-retenciones',label:'Auditoría de retenciones',icon:'fact_check',permissions:['WITHHOLDINGS_AUDIT_VIEW']},
      {path:'/catalogo-tributario',label:'Cat\u00e1logo tributario',icon:'activity',permissions:['TAX_CONCEPTS_VIEW']}
    ]},
    { title: 'Administraci\u00f3n', items: [
      {path:'/admin/billing-rules',label:'Reglas e incidencias',icon:'rule',permissions:['BILLING_RULES_VIEW']},
      {path:'/admin/users',label:'Usuarios',icon:'users',permissions:['USERS_VIEW']},
      {path:'/admin/roles',label:'Roles',icon:'admin_panel_settings',permissions:['ROLES_VIEW']},
      {path:'/admin/permissions',label:'Permisos',icon:'vpn_key',permissions:['PERMISSIONS_VIEW']}
    ]}
  ];

  /** Solo las opciones permitidas; las secciones vac\u00edas desaparecen. Se recalcula si cambian los permisos. */
  readonly navSections = computed(() => SidebarComponent.NAV_SECTIONS
    .map(section => ({ ...section, items: section.items.filter(item => this.authService.hasAnyPermission(item.permissions ?? [])) }))
    .filter(section => section.items.length > 0));

  onNavClick(): void {
    this.navigate.emit();
  }

  onToggle(): void {
    this.toggleCollapse.emit();
  }

  toggleTheme(dark: boolean): void {
    this.theme.setMode(dark ? 'dark' : 'light');
  }

  logout(): void {
    this.authService.logout();
  }

  onProfile(): void {
    void this.router.navigate(['/perfil']);
  }

  onSettings(): void {
    this.notification.info('Configuración del sistema');
  }
}
