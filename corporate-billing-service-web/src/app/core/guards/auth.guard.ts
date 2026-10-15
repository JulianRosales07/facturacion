import { hasLocalPreviewAccess } from '../services/local-preview';
import { CanActivateFn, Router } from '@angular/router';
import { inject } from '@angular/core';
import { AuthService, safeReturnUrl } from '../services/auth.service';
export const authGuard: CanActivateFn = (_route, state) => hasLocalPreviewAccess() || inject(AuthService).getToken()
  ? true : inject(Router).createUrlTree(['/auth/login'], { queryParams: { returnUrl: safeReturnUrl(state.url) } });
