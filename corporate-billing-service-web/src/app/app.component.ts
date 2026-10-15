import { Component, inject, signal } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { ThemeService } from './core/services/theme.service';

@Component({
  imports: [RouterOutlet],
  selector: 'app-root',
  styleUrl: './app.component.scss',
  templateUrl: './app.component.html',
})
export class App {
  protected readonly title = signal('corporate-billing-web');
  // Se crea al arrancar para que el tema se aplique en todas las rutas, incluido el login.
  private readonly theme = inject(ThemeService);
}
