import { ApplicationConfig, inject, provideBrowserGlobalErrorListeners } from '@angular/core';
import { AuthService } from './core/services/auth.service';
import { REALTIME_TOKEN_PROVIDER } from './core/services/realtime.service';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideAnimationsAsync } from '@angular/platform-browser/animations/async';
import { routes } from './app.routes';
import { authInterceptor } from './core/interceptors/auth.interceptor';
import { errorInterceptor } from './core/interceptors/error.interceptor';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes, withComponentInputBinding()),
    provideHttpClient(withInterceptors([authInterceptor, errorInterceptor])),
    provideAnimationsAsync(),
    // El CONNECT del WebSocket usa el mismo token que el API REST.
    { provide: REALTIME_TOKEN_PROVIDER, useFactory: () => { const auth = inject(AuthService); return () => auth.getToken(); } }
  ]
};
