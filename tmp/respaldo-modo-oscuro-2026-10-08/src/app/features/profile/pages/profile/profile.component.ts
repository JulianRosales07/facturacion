import { Component, OnInit, inject, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterModule } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatTooltipModule } from '@angular/material/tooltip';
import { AuthService } from '../../../../core/services/auth.service';
import { NotificationService } from '../../../../core/services/notification.service';
import { User } from '../../../../core/models/user.model';
import { hasLocalPreviewAccess } from '../../../../core/services/local-preview';

type TabType = 'info' | 'permissions' | 'security' | 'preferences';

interface SessionItem {
  device: string;
  location: string;
  ip: string;
  lastActive: string;
  current: boolean;
  browserIcon: string;
}

@Component({
  selector: 'app-profile',
  standalone: true,
  imports: [
    CommonModule,
    ReactiveFormsModule,
    RouterModule,
    MatIconModule,
    MatButtonModule,
    MatTooltipModule
  ],
  templateUrl: './profile.component.html',
  styleUrls: ['./profile.component.scss']
})
export class ProfileComponent implements OnInit {
  private readonly authService = inject(AuthService);
  private readonly notification = inject(NotificationService);
  private readonly fb = inject(FormBuilder);

  readonly currentUser = this.authService.currentUser;
  readonly isLocalPreview = hasLocalPreviewAccess();
  readonly activeTab = signal<TabType>('info');
  readonly isSaving = signal(false);
  readonly isChangingPassword = signal(false);
  readonly isDarkMode = signal(document.documentElement.classList.contains('dark'));
  readonly avatarFailed = signal(false);

  // Avatar selector options
  readonly availableAvatars = [
    { label: 'Julián Palacios', url: 'assets/layout/images/julian_avatar.jpg' },
    { label: 'Juanito Palacios', url: 'assets/layout/images/juanito_avatar.png' },
    { label: 'Inicial Corporativa', url: '' }
  ];
  readonly selectedAvatarUrl = signal<string>('');

  // Notification Preferences
  readonly prefSyncSuccess = signal(true);
  readonly prefSyncWarning = signal(true);
  readonly prefBatchAlerts = signal(true);
  readonly prefOverdueCartera = signal(false);
  readonly prefEmailReports = signal(true);

  // Password Strength computation
  readonly newPasswordValue = signal('');
  readonly passwordStrength = computed(() => {
    const pwd = this.newPasswordValue();
    if (!pwd) return { score: 0, label: 'No ingresada', color: '#94a3b8' };
    let score = 0;
    if (pwd.length >= 8) score += 25;
    if (/[A-Z]/.test(pwd)) score += 25;
    if (/[0-9]/.test(pwd)) score += 25;
    if (/[^A-Za-z0-9]/.test(pwd)) score += 25;

    if (score <= 25) return { score, label: 'Débil', color: '#ef4444' };
    if (score <= 50) return { score, label: 'Regular', color: '#f59e0b' };
    if (score <= 75) return { score, label: 'Buena', color: '#3b82f6' };
    return { score, label: 'Muy Segura', color: '#10b981' };
  });

  // Recent Sessions
  readonly sessions: SessionItem[] = [
    {
      device: 'Google Chrome en Windows 11',
      location: 'Bogotá, Colombia',
      ip: '192.168.1.45 (Red Local SP)',
      lastActive: 'Sesión activa ahora',
      current: true,
      browserIcon: 'laptop'
    },
    {
      device: 'Mozilla Firefox en Windows 10',
      location: 'Bogotá, Colombia',
      ip: '181.129.84.12',
      lastActive: 'Hace 2 días · 17:40',
      current: false,
      browserIcon: 'devices'
    },
    {
      device: 'Mobile Safari en iPhone 14',
      location: 'Medellín, Colombia',
      ip: '190.158.42.88',
      lastActive: 'Hace 5 días · 09:15',
      current: false,
      browserIcon: 'smartphone'
    }
  ];

  profileForm!: FormGroup;
  passwordForm!: FormGroup;

  ngOnInit(): void {
    const user = this.currentUser();
    this.selectedAvatarUrl.set(user?.avatarUrl || (this.isLocalPreview ? 'assets/layout/images/julian_avatar.jpg' : ''));

    this.profileForm = this.fb.group({
      fullName: [user?.fullName || 'Julián Palacios', [Validators.required, Validators.minLength(3)]],
      email: [user?.email || 'j.palacios@sistemaspalacios.com', [Validators.required, Validators.email]],
      username: [{ value: user?.username || 'jpalacios', disabled: true }],
      phone: [user?.phone || '+57 (601) 745-8900', [Validators.required]],
      department: [user?.department || 'Tecnología y Operaciones', [Validators.required]],
      position: [user?.position || 'Administrador Corporativo de Facturación', [Validators.required]],
      location: [user?.location || 'Sede Principal - Bogotá, D.C.', [Validators.required]],
      company: [{ value: user?.company || 'Sistemas Palacios S.A.S.', disabled: true }],
      nit: [{ value: '900.123.456-7', disabled: true }]
    });

    this.passwordForm = this.fb.group({
      currentPassword: ['', [Validators.required]],
      newPassword: ['', [Validators.required, Validators.minLength(8)]],
      confirmPassword: ['', [Validators.required]]
    });
  }

  setTab(tab: TabType): void {
    this.activeTab.set(tab);
  }

  selectAvatar(url: string): void {
    this.selectedAvatarUrl.set(url);
    this.avatarFailed.set(false);
  }

  onSaveProfile(): void {
    if (this.profileForm.invalid) {
      this.profileForm.markAllAsTouched();
      this.notification.warning('Por favor revisa los campos obligatorios del formulario.');
      return;
    }

    this.isSaving.set(true);
    const formVals = this.profileForm.getRawValue();

    setTimeout(() => {
      const updatedUser = this.authService.updateProfile({
        fullName: formVals.fullName,
        email: formVals.email,
        phone: formVals.phone,
        department: formVals.department,
        position: formVals.position,
        location: formVals.location,
        avatarUrl: this.selectedAvatarUrl()
      });

      this.isSaving.set(false);
      this.notification.success(`Perfil de ${updatedUser.fullName} actualizado correctamente.`);
    }, 600);
  }

  onResetProfile(): void {
    const user = this.currentUser();
    this.profileForm.patchValue({
      fullName: user?.fullName || 'Julián Palacios',
      email: user?.email || 'j.palacios@sistemaspalacios.com',
      phone: user?.phone || '+57 (601) 745-8900',
      department: user?.department || 'Tecnología y Operaciones',
      position: user?.position || 'Administrador Corporativo de Facturación',
      location: user?.location || 'Sede Principal - Bogotá, D.C.'
    });
    this.selectedAvatarUrl.set(user?.avatarUrl || '');
    this.notification.info('Cambios descartados.');
  }

  onPasswordInput(event: Event): void {
    const target = event.target as HTMLInputElement;
    this.newPasswordValue.set(target.value);
  }

  onChangePassword(): void {
    if (this.passwordForm.invalid) {
      this.passwordForm.markAllAsTouched();
      this.notification.warning('Completa todos los requisitos de seguridad de la contraseña.');
      return;
    }

    const { newPassword, confirmPassword } = this.passwordForm.value;
    if (newPassword !== confirmPassword) {
      this.notification.error('La nueva contraseña y la confirmación no coinciden.');
      return;
    }

    this.isChangingPassword.set(true);
    setTimeout(() => {
      this.isChangingPassword.set(false);
      this.passwordForm.reset();
      this.newPasswordValue.set('');
      this.notification.success('Contraseña actualizada exitosamente. En el próximo inicio deberás utilizarla.');
    }, 750);
  }

  toggleTheme(dark: boolean): void {
    this.isDarkMode.set(dark);
    if (dark) {
      document.documentElement.classList.add('dark');
    } else {
      document.documentElement.classList.remove('dark');
    }
    this.notification.info(`Modo ${dark ? 'Oscuro' : 'Claro'} activado`);
  }

  terminateOtherSessions(): void {
    this.notification.success('Se han cerrado todas las demás sesiones activas en otros navegadores.');
  }

  downloadSecuritySummary(): void {
    const user = this.currentUser();
    const content = `========================================================\n` +
      `SISTEMAS PALACIOS - FICHA DE IDENTIDAD Y SEGURIDAD\n` +
      `========================================================\n` +
      `Usuario: ${user?.username}\n` +
      `Nombre Completo: ${user?.fullName}\n` +
      `Correo Corporativo: ${user?.email}\n` +
      `Rol Asignado: ${user?.role.name} (${user?.role.code})\n` +
      `Departamento: ${user?.department || 'Tecnología y Operaciones'}\n` +
      `Cargo: ${user?.position || 'Administrador Corporativo de Facturación'}\n` +
      `Empresa: ${user?.company || 'Sistemas Palacios S.A.S.'}\n` +
      `Protocolo: OAuth 2.0 / OpenID Connect + PKCE SHA-256\n` +
      `Estado: ACTIVO / VERIFICADO\n` +
      `Fecha de emisión: ${new Date().toLocaleString()}\n` +
      `========================================================\n`;

    const blob = new Blob([content], { type: 'text/plain;charset=utf-8' });
    const url = window.URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `ficha-seguridad-${user?.username || 'usuario'}.txt`;
    a.click();
    window.URL.revokeObjectURL(url);
    this.notification.info('Ficha de seguridad descargada.');
  }
}
