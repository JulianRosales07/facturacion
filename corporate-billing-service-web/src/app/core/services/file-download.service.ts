import { Injectable, inject } from '@angular/core';
import { ApiService } from './api.service';
import { NotificationService } from './notification.service';

@Injectable({ providedIn: 'root' })
export class FileDownloadService {
  private readonly api = inject(ApiService);
  private readonly notifications = inject(NotificationService);

  download(endpoint: string, filename: string): void {
    this.api.getBlob(endpoint.replace(/^\/api\/v1/, '')).subscribe({
      next: blob => {
        const url = URL.createObjectURL(blob);
        const anchor = document.createElement('a');
        anchor.href = url;
        anchor.download = filename;
        anchor.click();
        setTimeout(() => URL.revokeObjectURL(url), 1000);
      },
      error: err => this.notifications.error(err.status === 404 ? 'El documento aún no está disponible. Revise la sincronización.' : 'No se pudo descargar el archivo. Verifique su sesión y permisos.')
    });
  }
}
