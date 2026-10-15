import { TestBed } from '@angular/core/testing';
import { ThemeService } from './theme.service';

describe('ThemeService', () => {
  beforeEach(() => {
    localStorage.clear();
    document.documentElement.classList.remove('dark');
    TestBed.resetTestingModule();
  });

  it('applies and remembers the chosen mode', () => {
    const theme = TestBed.inject(ThemeService);
    theme.setMode('dark');
    expect(document.documentElement.classList.contains('dark')).toBe(true);
    expect(theme.isDark()).toBe(true);
    expect(localStorage.getItem('cb_theme')).toBe('dark');

    theme.toggle();
    expect(document.documentElement.classList.contains('dark')).toBe(false);
    expect(localStorage.getItem('cb_theme')).toBe('light');
  });

  it('restores the saved mode on startup', () => {
    localStorage.setItem('cb_theme', 'dark');
    const theme = TestBed.inject(ThemeService);
    expect(theme.mode()).toBe('dark');
    expect(document.documentElement.classList.contains('dark')).toBe(true);
  });
});
