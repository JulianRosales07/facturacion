import { isLocalPreview } from '../services/local-preview';
import { HttpInterceptorFn, HttpErrorResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { NotificationService } from '../services/notification.service';
import { AuthService } from '../services/auth.service';

export const errorInterceptor: HttpInterceptorFn = (req, next) => {
  const notificationService = inject(NotificationService);
  const authService = inject(AuthService);

  return next(req).pipe(
    catchError((error: unknown) => {
      if (error instanceof HttpErrorResponse) {
        // Ignore HTML responses returned by dev-server SPA fallback
        if (
          (error.status === 200 && error.url?.includes('/api/')) ||
          (typeof error.error === 'string' && error.error.toLowerCase().includes('<!doctype')) ||
          error.message?.includes('is not valid JSON')
        ) {
          return throwError(() => error);
        }

        switch (error.status) {
          case 401:
            if (isLocalPreview()) {
              notificationService.warning('Vista local: el servidor requiere autenticación para consultar datos.');
              break;
            }
            authService.logout();
            notificationService.warning('Sesión expirada. Por favor ingresa nuevamente.');
            break;
          case 403:
            notificationService.error('No tienes permisos suficientes para realizar esta acción.');
            break;
          case 400:
          case 409:
            // Validación o conflicto: el backend explica la causa (duplicado, versión desactualizada, último administrador…).
            notificationService.warning(error.error?.mensaje || error.error?.message || 'La solicitud no es válida.');
            break;
          case 404:
            notificationService.warning('El recurso solicitado no fue encontrado.');
            break;
          case 500:
          default:
            notificationService.error(
              error.error?.mensaje || error.error?.message || 'Ocurrió un error inesperado al comunicarse con el servidor.'
            );
            break;
        }
      }
      return throwError(() => error);
    })
  );
};
