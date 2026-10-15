import { environment } from '../../../environments/environment';

/** Opens UI development locally without creating an authenticated session. */
export function isLocalPreview(hostname = window.location.hostname): boolean {
  return !environment.production && environment.localPreview &&
    ['localhost', '127.0.0.1', '[::1]'].includes(hostname);
}

export function hasLocalPreviewAccess(): boolean {
  return isLocalPreview() && sessionStorage.getItem('cb_local_preview') === 'active';
}

export function enterLocalPreview(): void {
  if (isLocalPreview()) sessionStorage.setItem('cb_local_preview', 'active');
}

export function exitLocalPreview(): void {
  sessionStorage.removeItem('cb_local_preview');
}
