import { Injectable, computed, signal } from '@angular/core';

export type ThemeMode = 'light' | 'dark';

/** Misma clave que lee el script de index.html para aplicar el tema antes de que cargue Angular. */
const STORAGE_KEY = 'cb_theme';

/**
 * Fuente única del tema claro/oscuro. Pone o quita la clase `dark` en <html>, de la que dependen
 * las variables de color de styles.scss y theme-tokens.scss, y recuerda la elección del usuario.
 * Sin elección guardada sigue la preferencia del sistema operativo.
 */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  private readonly systemQuery = typeof matchMedia === 'function' ? matchMedia('(prefers-color-scheme: dark)') : null;

  readonly mode = signal<ThemeMode>(this.storedMode() ?? this.systemMode());
  readonly isDark = computed(() => this.mode() === 'dark');

  constructor() {
    this.apply(this.mode());
    this.systemQuery?.addEventListener('change', (event) => {
      if (!this.storedMode()) this.setMode(event.matches ? 'dark' : 'light', false);
    });
  }

  setMode(mode: ThemeMode, remember = true): void {
    this.mode.set(mode);
    this.apply(mode);
    if (remember) {
      try { localStorage.setItem(STORAGE_KEY, mode); } catch { /* almacenamiento bloqueado: el tema dura la sesión */ }
    }
  }

  toggle(): void {
    this.setMode(this.isDark() ? 'light' : 'dark');
  }

  private apply(mode: ThemeMode): void {
    document.documentElement.classList.toggle('dark', mode === 'dark');
  }

  private storedMode(): ThemeMode | null {
    try {
      const value = localStorage.getItem(STORAGE_KEY);
      return value === 'dark' || value === 'light' ? value : null;
    } catch {
      return null;
    }
  }

  private systemMode(): ThemeMode {
    return this.systemQuery?.matches ? 'dark' : 'light';
  }
}
