import { Component, inject, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { enterLocalPreview, isLocalPreview } from '../../../../core/services/local-preview';
import { safeReturnUrl } from '../../../../core/services/auth.service';

@Component({
  selector: 'app-login', standalone: true,
  templateUrl: './login.component.html',
  styleUrls: ['./login.component.scss']
})
export class LoginComponent {
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  readonly showPassword = signal(false);
  readonly error = signal('');

  async login(event: Event): Promise<void> {
    event.preventDefault();
    const form = event.target as HTMLFormElement;
    const username = form.elements.namedItem('username') as HTMLInputElement;
    const password = form.elements.namedItem('password') as HTMLInputElement;
    if (!username.value.trim() || !password.value.trim()) {
      this.error.set('Completa el usuario y la contrase\u00f1a.');
      return;
    }
    if (!isLocalPreview()) {
      this.error.set('El inicio de sesi\u00f3n estar\u00e1 disponible al integrar la autenticaci\u00f3n del sistema existente.');
      return;
    }
    // Temporary local UI access only: never send or persist these credentials.
    password.value = '';
    this.showPassword.set(false);
    this.error.set('');
    enterLocalPreview();
    await this.router.navigateByUrl(safeReturnUrl(this.route.snapshot.queryParamMap.get('returnUrl') || '/dashboard'), { replaceUrl: true });
  }
}
