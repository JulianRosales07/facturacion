import { hasLocalPreviewAccess } from '../../../core/services/local-preview';
import { Component, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterModule } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatMenuModule } from '@angular/material/menu';
import { AuthService } from '../../../core/services/auth.service';
import { NotificationService } from '../../../core/services/notification.service';

@Component({
  selector: 'app-topbar',
  standalone: true,
  imports: [CommonModule, RouterModule, MatIconModule, MatButtonModule, MatMenuModule],
  templateUrl: './topbar.component.html',
  styleUrls: ['./topbar.component.scss']
})
export class TopbarComponent {
  private readonly authService = inject(AuthService);
  private readonly notification = inject(NotificationService);
  private readonly router = inject(Router);

  readonly currentUser = this.authService.currentUser;
  readonly avatarFailed = signal(false);
  readonly localPreview = hasLocalPreviewAccess();
  /** Iniciales para el menú cuando no hay foto: "Julián Palacios" → "JP". */
  readonly initials = computed(() => {
    const words = (this.currentUser()?.fullName || 'Julián Palacios').trim().split(/\s+/);
    return (words[0][0] + (words.length > 1 ? words[words.length - 1][0] : '')).toUpperCase();
  });
  get avatarUrl(): string | undefined {
    return this.currentUser()?.avatarUrl || (this.localPreview ? 'assets/layout/images/julian_avatar.jpg' : undefined);
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
