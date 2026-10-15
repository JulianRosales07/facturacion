import { Injectable, inject } from '@angular/core';
import { MatSnackBar, MatSnackBarConfig } from '@angular/material/snack-bar';

@Injectable({
  providedIn: 'root'
})
export class NotificationService {
  private readonly snackBar = inject(MatSnackBar);

  private readonly defaultConfig: MatSnackBarConfig = {
    duration: 4000,
    horizontalPosition: 'right',
    verticalPosition: 'top'
  };

  success(message: string, action = 'Cerrar'): void {
    this.snackBar.open(message, action, {
      ...this.defaultConfig,
      panelClass: ['cb-snack-success']
    });
  }

  error(message: string, action = 'Cerrar'): void {
    this.snackBar.open(message, action, {
      ...this.defaultConfig,
      duration: 6000,
      panelClass: ['cb-snack-error']
    });
  }

  warning(message: string, action = 'Cerrar'): void {
    this.snackBar.open(message, action, {
      ...this.defaultConfig,
      panelClass: ['cb-snack-warning']
    });
  }

  info(message: string, action = 'Cerrar'): void {
    this.snackBar.open(message, action, {
      ...this.defaultConfig,
      panelClass: ['cb-snack-info']
    });
  }
}
