import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { AuthService } from '../../../../core/services/auth.service';
import { NotificationService } from '../../../../core/services/notification.service';
import { User } from '../../../../core/models/user.model';

export const DOCUMENT_TYPES = [
  { code: 'CC', label: 'Cédula de ciudadanía' },
  { code: 'CE', label: 'Cédula de extranjería' },
  { code: 'TI', label: 'Tarjeta de identidad' },
  { code: 'PA', label: 'Pasaporte' },
  { code: 'PPT', label: 'Permiso por protección temporal' },
  { code: 'NIT', label: 'NIT' }
];

/** Mínimo 8 caracteres, una mayúscula, una minúscula y un número. */
export const PASSWORD_PATTERN = /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d).{8,}$/;

interface ProfileField { label: string; done: boolean; pending: boolean; }

@Component({
  selector: 'app-profile',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule],
  templateUrl: './profile.component.html',
  styleUrls: ['./profile.component.scss']
})
export class ProfileComponent implements OnInit {
  private readonly authService = inject(AuthService);
  private readonly notification = inject(NotificationService);
  private readonly fb = inject(FormBuilder);

  readonly currentUser = this.authService.currentUser;
  readonly documentTypes = DOCUMENT_TYPES;
  readonly isEditing = signal(false);
  readonly isEditingPassword = signal(false);
  readonly avatarFailed = signal(false);

  profileForm!: FormGroup;
  passwordForm!: FormGroup;

  readonly firstName = computed(() => this.nameParts(this.currentUser()).first);
  readonly lastName = computed(() => this.nameParts(this.currentUser()).last);
  readonly displayName = computed(() => [this.firstName(), this.lastName()].filter(Boolean).join(' ') || this.currentUser()?.username || '');
  readonly initials = computed(() => (this.firstName().charAt(0) + this.lastName().charAt(0)).toUpperCase() || '?');
  readonly hasPhone = computed(() => !!this.currentUser()?.phone?.trim());
  readonly otpEnabled = computed(() => !!this.currentUser()?.otpEnabled && this.hasPhone());
  readonly documentLabel = computed(() => {
    const user = this.currentUser();
    return user?.documentType && user?.documentNumber ? `${user.documentType} ${user.documentNumber}` : 'No aplica';
  });

  /** Cada dato cuenta para la completitud; solo los datos editables aparecen como pendientes. */
  readonly fields = computed<ProfileField[]>(() => {
    const user = this.currentUser();
    return [
      { label: 'Nombre', done: !!this.firstName(), pending: true },
      { label: 'Apellido', done: !!this.lastName(), pending: true },
      { label: 'Correo', done: !!user?.email, pending: true },
      { label: 'Teléfono', done: this.hasPhone(), pending: true },
      { label: 'Documento', done: !!user?.documentType && !!user?.documentNumber?.trim(), pending: true },
      { label: 'Fecha nacimiento', done: !!user?.birthDate, pending: true },
      { label: 'OTP', done: this.otpEnabled(), pending: false }
    ];
  });
  readonly completeness = computed(() => {
    const fields = this.fields();
    return Math.round(fields.filter(f => f.done).length * 100 / fields.length);
  });
  readonly pendingFields = computed(() => this.fields().filter(f => f.pending && !f.done).map(f => f.label));
  readonly suggestions = computed(() => [
    { label: 'Teléfono disponible para contacto rápido', done: this.hasPhone() },
    { label: 'Fecha de nacimiento registrada', done: !!this.currentUser()?.birthDate },
    { label: 'Protección mediante OTP activada', done: this.otpEnabled() }
  ]);

  ngOnInit(): void {
    this.profileForm = this.fb.group({
      firstName: ['', [Validators.required, Validators.minLength(2)]],
      lastName: ['', [Validators.required]],
      documentType: [''],
      documentNumber: ['', [Validators.pattern(/^[0-9A-Za-z-]{4,20}$/)]],
      email: [{ value: '', disabled: true }],
      phone: ['', [Validators.pattern(/^\+?[0-9 ()-]{7,20}$/)]],
      birthDate: ['']
    });
    this.passwordForm = this.fb.group({
      currentPassword: ['', [Validators.required]],
      newPassword: ['', [Validators.required, Validators.pattern(PASSWORD_PATTERN)]],
      confirmPassword: ['', [Validators.required]]
    });
    this.loadProfile();
    this.passwordForm.disable();
  }

  startEditing(): void {
    this.isEditing.set(true);
    this.profileForm.enable();
    this.profileForm.get('email')?.disable();
  }

  cancelEditing(): void {
    this.loadProfile();
  }

  saveProfile(): void {
    if (this.profileForm.invalid) {
      this.profileForm.markAllAsTouched();
      this.notification.warning('Revisa los campos marcados antes de guardar.');
      return;
    }
    const value = this.profileForm.getRawValue();
    const documentType = value.documentType || undefined;
    const documentNumber = value.documentNumber?.trim() || undefined;
    if (!!documentType !== !!documentNumber) {
      this.notification.warning('Indica el tipo y el número de documento juntos.');
      return;
    }
    const phone = value.phone?.trim() || undefined;
    const updated = this.authService.updateProfile({
      firstName: value.firstName.trim(),
      lastName: value.lastName.trim(),
      fullName: `${value.firstName.trim()} ${value.lastName.trim()}`,
      documentType,
      documentNumber,
      phone,
      birthDate: value.birthDate || undefined,
      // Sin celular el OTP no puede seguir activo.
      otpEnabled: phone ? this.currentUser()?.otpEnabled : false
    });
    this.loadProfile();
    this.notification.success(`Perfil de ${updated.fullName} actualizado.`);
  }

  toggleOtp(): void {
    if (!this.hasPhone()) {
      this.notification.warning('Agrega un número de celular de WhatsApp en tu perfil para habilitar el OTP.');
      return;
    }
    const enabled = !this.otpEnabled();
    this.authService.updateProfile({ otpEnabled: enabled });
    this.notification.success(enabled ? 'Doble factor de autenticación activado.' : 'Doble factor de autenticación desactivado.');
  }

  startPasswordEdit(): void {
    this.isEditingPassword.set(true);
    this.passwordForm.enable();
  }

  cancelPasswordEdit(): void {
    this.isEditingPassword.set(false);
    this.passwordForm.reset();
    this.passwordForm.disable();
  }

  savePassword(): void {
    if (this.passwordForm.invalid) {
      this.passwordForm.markAllAsTouched();
      this.notification.warning('La nueva contraseña debe tener mínimo 8 caracteres, una mayúscula, una minúscula y un número.');
      return;
    }
    const { currentPassword, newPassword, confirmPassword } = this.passwordForm.value;
    if (newPassword !== confirmPassword) {
      this.notification.error('La nueva contraseña y la confirmación no coinciden.');
      return;
    }
    if (newPassword === currentPassword) {
      this.notification.warning('La nueva contraseña debe ser diferente a la actual.');
      return;
    }
    this.cancelPasswordEdit();
    this.notification.success('Contraseña actualizada.');
  }

  invalid(form: FormGroup, control: string): boolean {
    const c = form.get(control);
    return !!c && c.invalid && (c.touched || c.dirty);
  }

  private loadProfile(): void {
    const user = this.currentUser();
    this.profileForm.reset({
      firstName: this.firstName(),
      lastName: this.lastName(),
      documentType: user?.documentType ?? '',
      documentNumber: user?.documentNumber ?? '',
      email: user?.email ?? '',
      phone: user?.phone ?? '',
      birthDate: user?.birthDate ?? ''
    });
    this.isEditing.set(false);
    this.profileForm.disable();
  }

  /** Usa nombre y apellido guardados; si solo hay nombre completo, la primera palabra es el nombre. */
  private nameParts(user: User | null): { first: string; last: string } {
    if (!user) return { first: '', last: '' };
    if (user.firstName || user.lastName) return { first: user.firstName ?? '', last: user.lastName ?? '' };
    const [first = '', ...rest] = (user.fullName ?? '').trim().split(/\s+/);
    return { first, last: rest.join(' ') };
  }
}
