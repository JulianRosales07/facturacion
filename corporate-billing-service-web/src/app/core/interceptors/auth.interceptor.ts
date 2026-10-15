import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { environment } from '../../../environments/environment';
import { AuthService } from '../services/auth.service';

export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const authService = inject(AuthService);
  const token = authService.getToken();

  const api = new URL(environment.apiUrl, window.location.origin);
  const target = new URL(req.url, window.location.origin);
  const belongsToApi = target.origin === api.origin && (target.pathname === api.pathname || target.pathname.startsWith(api.pathname + '/'));
  if (token && belongsToApi) {
    const authReq = req.clone({
      setHeaders: {
        Authorization: `Bearer ${token}`
      }
    });
    return next(authReq);
  }

  return next(req);
};
