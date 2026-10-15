import { exitLocalPreview, hasLocalPreviewAccess } from './local-preview';
import { Injectable, signal, computed, inject } from '@angular/core';
import { HttpBackend, HttpClient, HttpParams } from '@angular/common/http';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { User } from '../models/user.model';
import { ALL_PERMISSION_CODES, CurrentUserAccess, roleLabel } from '../models/access.model';
import { environment } from '../../../environments/environment';

interface AuthConfig { authorizationEndpoint: string; tokenEndpoint: string; clientId: string; scope: string; }
interface SessionInfo { subject: string; username?: string; name?: string; email?: string; expiresAt: string; roles: string[]; }
interface Session { token: string; expiresAt: number; user: User; }
interface LoginAttempt { state: string; verifier: string; returnUrl: string; createdAt: number; }

export function safeReturnUrl(value: string): string {
  return /^\/(?!\/)/.test(value) && !value.includes('\\') && !value.startsWith('/auth/') ? value : '/dashboard';
}

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly router = inject(Router);
  private readonly http = new HttpClient(inject(HttpBackend));
  private readonly sessionKey = 'cb_sso_session';
  private readonly localUserKey = 'cb_local_profile';
  private readonly attemptKey = 'cb_sso_attempt';
  private readonly session = signal<Session | null>(this.restore());
  private readonly localUser = signal<User | null>(this.restoreLocalUser());
  /** Vista local: permisos que devuelve el backend de desarrollo (null mientras no responda). */
  private readonly localAccess = signal<CurrentUserAccess | null>(null);

  readonly currentUser = computed(() => {
    const s = this.session()?.user;
    if (s) return s;
    if (hasLocalPreviewAccess()) {
      const base = this.localUser() ?? this.defaultLocalUser();
      const access = this.localAccess();
      return { ...base, roles: access?.roles ?? ['SUPER_ADMIN'], permissions: access?.permissions ?? ALL_PERMISSION_CODES };
    }
    return null;
  });
  readonly isAuthenticated = computed(() => !!this.session() && this.session()!.expiresAt > Date.now());
  readonly userRole = computed(() => this.currentUser()?.role.code ?? null);
  readonly token = computed(() => this.session()?.token ?? null);
  /** Permisos efectivos (solo para adaptar la interfaz: el backend vuelve a validarlos en cada petición). */
  readonly permissions = computed(() => new Set(this.currentUser()?.permissions ?? []));
  readonly roles = computed(() => this.currentUser()?.roles ?? []);

  constructor() {
    localStorage.removeItem('cb_auth_token');
    localStorage.removeItem('cb_auth_user');
  }

  getToken(): string | null {
    if (!this.session() || this.session()!.expiresAt <= Date.now()) {
      this.session.set(null);
      sessionStorage.removeItem(this.sessionKey);
      return null;
    }
    return this.session()!.token;
  }
  getCurrentUser(): User | null { return this.currentUser(); }
  hasRole(role: string): boolean { return this.roles().includes(role); }
  hasPermission(code: string): boolean { return this.permissions().has(code); }
  /** Sin códigos no exige nada; con varios basta uno. */
  hasAnyPermission(codes: readonly string[]): boolean { return codes.length === 0 || codes.some(code => this.permissions().has(code)); }

  /**
   * Vuelve a consultar roles y permisos (tras un aviso de cambio por WebSocket o al reconectar).
   * Devuelve false si el usuario dejó de tener acceso al panel.
   */
  async refreshPermissions(): Promise<boolean> {
    const session = this.session();
    if (!session && !hasLocalPreviewAccess()) return false;
    const headers: Record<string, string> = session ? { Authorization: `Bearer ${session.token}` } : {};
    let access: CurrentUserAccess;
    try {
      access = await firstValueFrom(this.http.get<CurrentUserAccess>(`${environment.apiUrl}/auth/me`, { headers }));
    } catch {
      return true; // sin respuesta se conserva lo último conocido; el backend sigue validando cada petición
    }
    if (!session) {
      this.localAccess.set(access);
      return true;
    }
    const updated: Session = { ...session, user: { ...session.user, ...this.accessFields(access) } };
    sessionStorage.setItem(this.sessionKey, JSON.stringify(updated));
    this.session.set(updated);
    return access.registered && access.permissions.length > 0;
  }

  async configuration(): Promise<AuthConfig> {
    const config = await firstValueFrom(this.http.get<AuthConfig>('/auth-config.json'));
    if (!config.clientId || !config.authorizationEndpoint || !config.tokenEndpoint)
      throw new Error('SSO pendiente de configuración. Solicite a TI el registro de esta aplicación.');
    for (const endpoint of [config.authorizationEndpoint, config.tokenEndpoint]) {
      const url = new URL(endpoint);
      if (url.protocol !== 'https:' && !(url.protocol === 'http:' && ['localhost', '127.0.0.1'].includes(url.hostname)))
        throw new Error('Los endpoints de SSO deben utilizar HTTPS.');
      if (url.username || url.password || url.hash) throw new Error('Configuración SSO inválida.');
    }
    return config;
  }

  async startLogin(returnUrl = '/dashboard'): Promise<void> {
    const config = await this.configuration();
    const state = this.randomValue(), verifier = this.randomValue();
    const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier));
    const challenge = this.base64(new Uint8Array(digest));
    const attempt: LoginAttempt = { state, verifier, returnUrl: safeReturnUrl(returnUrl), createdAt: Date.now() };
    sessionStorage.setItem(this.attemptKey, JSON.stringify(attempt));
    const url = new URL(config.authorizationEndpoint);
    const params: Record<string, string> = { response_type: 'code', client_id: config.clientId,
      redirect_uri: this.redirectUri(), scope: config.scope, state, code_challenge: challenge, code_challenge_method: 'S256' };
    Object.entries(params).forEach(([key, value]) => url.searchParams.set(key, value));
    window.location.assign(url.toString());
  }

  async completeLogin(query: URLSearchParams): Promise<string> {
    const raw = sessionStorage.getItem(this.attemptKey);
    sessionStorage.removeItem(this.attemptKey);
    const attempt: LoginAttempt | null = raw ? JSON.parse(raw) : null;
    if (!attempt || query.get('state') !== attempt.state || Date.now() - attempt.createdAt > 600000)
      throw new Error('La solicitud de acceso venció o no corresponde a esta sesión. Vuelva a ingresar.');
    if (query.has('error') || !query.get('code')) throw new Error('El proveedor no autorizó el acceso.');
    const config = await this.configuration();
    const body = new HttpParams().set('grant_type', 'authorization_code').set('client_id', config.clientId)
      .set('redirect_uri', this.redirectUri()).set('code', query.get('code')!).set('code_verifier', attempt.verifier);
    const tokens = await firstValueFrom(this.http.post<{ access_token: string; token_type: string }>(config.tokenEndpoint, body));
    if (!tokens.access_token || tokens.token_type?.toLowerCase() !== 'bearer') throw new Error('Respuesta SSO inválida.');
    // El backend verifica firma, emisor, audiencia y permisos. No se confía en claims decodificados en el navegador.
    const profile = await firstValueFrom(this.http.get<SessionInfo>(`${environment.apiUrl}/sesion`,
      { headers: { Authorization: `Bearer ${tokens.access_token}` } }));
    const expiresAt = Date.parse(profile.expiresAt);
    if (!Number.isFinite(expiresAt) || expiresAt <= Date.now()) throw new Error('El token recibido está vencido.');
    // Roles y permisos salen de la base de datos de este servicio, no del token.
    const access = await firstValueFrom(this.http.get<CurrentUserAccess>(`${environment.apiUrl}/auth/me`,
      { headers: { Authorization: `Bearer ${tokens.access_token}` } }));
    if (!access.registered || access.permissions.length === 0)
      throw new Error('Su usuario no tiene acceso al panel. Solicite a un administrador que lo registre y le asigne un rol.');
    const username = access.username || profile.username || profile.subject;
    const user: User = { id: access.id ?? profile.subject, username, email: access.email || profile.email || '',
      fullName: access.name || profile.name || username, isActive: true, createdAt: '', ...this.accessFields(access) };
    const session = { token: tokens.access_token, expiresAt, user };
    sessionStorage.setItem(this.sessionKey, JSON.stringify(session));
    this.session.set(session);
    return safeReturnUrl(attempt.returnUrl);
  }

  updateProfile(updated: Partial<User>): User {
    const current = this.currentUser() ?? this.defaultLocalUser();
    const newUser: User = {
      ...current,
      ...updated,
      updatedAt: new Date().toISOString()
    };

    const sess = this.session();
    if (sess) {
      const newSession: Session = { ...sess, user: newUser };
      sessionStorage.setItem(this.sessionKey, JSON.stringify(newSession));
      this.session.set(newSession);
    } else {
      this.localUser.set(newUser);
      localStorage.setItem(this.localUserKey, JSON.stringify(newUser));
    }
    return newUser;
  }

  logout(redirect = true): void {
    exitLocalPreview();
    sessionStorage.removeItem(this.attemptKey);
    this.session.set(null);
    this.localUser.set(null);
    this.localAccess.set(null);
    localStorage.removeItem(this.localUserKey);
    sessionStorage.removeItem(this.sessionKey);
    if (redirect) void this.router.navigate(['/auth/login'], { replaceUrl: true, queryParams: { returnUrl: safeReturnUrl(this.router.url) } });
  }

  /** Roles y permisos del backend, más el rol principal que muestran el sidebar y el perfil. */
  private accessFields(access: CurrentUserAccess): Pick<User, 'role' | 'roles' | 'permissions'> {
    const main = access.roles[0] ?? 'SIN_ROL';
    return {
      roles: access.roles,
      permissions: access.permissions,
      role: { id: main, code: access.roles.includes('SUPER_ADMIN') ? 'ADMIN' : 'ANALYST',
        name: access.roles.length ? access.roles.map(roleLabel).join(', ') : 'Sin rol', description: '', permissions: [] }
    };
  }

  private defaultLocalUser(): User {
    return {
      id: 'sp-user-01',
      username: 'jpalacios',
      email: 'j.palacios@sistemaspalacios.com',
      fullName: 'Julián Palacios',
      phone: '+57 (601) 745-8900',
      department: 'Tecnología y Operaciones',
      position: 'Administrador Corporativo de Facturación',
      location: 'Sede Principal - Bogotá, D.C.',
      company: 'Sistemas Palacios S.A.S.',
      role: {
        id: 'ROL_ADMINISTRADOR',
        code: 'ADMIN',
        name: 'Super admin',
        description: 'Acceso total',
        permissions: []
      },
      isActive: true,
      avatarUrl: 'assets/layout/images/julian_avatar.jpg',
      createdAt: '2024-01-15T08:00:00Z',
      lastLoginAt: new Date().toISOString()
    };
  }

  private restoreLocalUser(): User | null {
    try {
      const raw = localStorage.getItem(this.localUserKey);
      return raw ? JSON.parse(raw) as User : null;
    } catch {
      return null;
    }
  }

  private restore(): Session | null {
    try {
      const session = JSON.parse(sessionStorage.getItem('cb_sso_session') || 'null') as Session | null;
      return session?.token && session?.user && session.expiresAt > Date.now() ? session : null;
    } catch { return null; }
  }
  private redirectUri(): string { return `${window.location.origin}/auth/callback`; }
  private randomValue(): string { return this.base64(crypto.getRandomValues(new Uint8Array(32))); }
  private base64(bytes: Uint8Array): string { return btoa(String.fromCharCode(...bytes)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, ''); }
}
