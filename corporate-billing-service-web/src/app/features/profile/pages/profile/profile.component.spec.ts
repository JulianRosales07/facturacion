import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ProfileComponent } from './profile.component';
import { provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { AuthService } from '../../../../core/services/auth.service';
import { signal, WritableSignal } from '@angular/core';
import { User } from '../../../../core/models/user.model';

describe('ProfileComponent', () => {
  let component: ProfileComponent;
  let fixture: ComponentFixture<ProfileComponent>;
  let user: WritableSignal<User>;
  let updateProfile: ReturnType<typeof vi.fn>;

  beforeEach(async () => {
    user = signal<User>({
      id: 'usr-1',
      username: 'jrosales',
      email: 'juliandrosalesp@gmail.com',
      fullName: 'julian rosales',
      role: { id: 'ROL_ADMINISTRADOR', code: 'ADMIN', name: 'Administrador', description: '', permissions: [] },
      isActive: true,
      avatarUrl: '',
      createdAt: '2024-01-01'
    } as User);
    updateProfile = vi.fn().mockImplementation((changes: Partial<User>) => {
      user.set({ ...user(), ...changes });
      return user();
    });

    await TestBed.configureTestingModule({
      imports: [ProfileComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: { currentUser: user, updateProfile, logout: vi.fn() } }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(ProfileComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('separa nombre y apellido del nombre completo y deja el formulario en solo lectura', () => {
    expect(component.profileForm.get('firstName')?.value).toBe('julian');
    expect(component.profileForm.get('lastName')?.value).toBe('rosales');
    expect(component.profileForm.disabled).toBe(true);
    expect((fixture.nativeElement as HTMLElement).querySelector('.badge')?.textContent).toContain('Solo lectura');
  });

  it('calcula completitud y pendientes como en el resumen (43 %, 3 pendientes)', () => {
    expect(component.completeness()).toBe(43);
    expect(component.pendingFields()).toEqual(['Teléfono', 'Documento', 'Fecha nacimiento']);
    expect(component.documentLabel()).toBe('No aplica');
  });

  it('guarda los datos editados y mantiene el correo fuera de edición', () => {
    component.startEditing();
    expect(component.profileForm.get('email')?.disabled).toBe(true);
    component.profileForm.patchValue({
      documentType: 'CC', documentNumber: '1085285659', phone: '3001234567', birthDate: '1995-04-12'
    });
    component.saveProfile();

    expect(updateProfile).toHaveBeenCalledWith(expect.objectContaining({
      firstName: 'julian', lastName: 'rosales', fullName: 'julian rosales',
      documentType: 'CC', documentNumber: '1085285659', phone: '3001234567', birthDate: '1995-04-12'
    }));
    expect(component.isEditing()).toBe(false);
    expect(component.pendingFields()).toEqual([]);
    expect(component.documentLabel()).toBe('CC 1085285659');
  });

  it('exige tipo y número de documento juntos', () => {
    component.startEditing();
    component.profileForm.patchValue({ documentNumber: '1085285659' });
    component.saveProfile();
    expect(updateProfile).not.toHaveBeenCalled();
  });

  it('el OTP solo se puede activar con un teléfono registrado', () => {
    component.toggleOtp();
    expect(updateProfile).not.toHaveBeenCalled();
    expect(component.otpEnabled()).toBe(false);

    user.set({ ...user(), phone: '3001234567' });
    component.toggleOtp();
    expect(component.otpEnabled()).toBe(true);
    expect(component.completeness()).toBe(71);
  });

  it('la nueva contraseña debe cumplir mayúscula, minúscula, número y 8 caracteres', () => {
    component.startPasswordEdit();
    component.passwordForm.setValue({ currentPassword: 'Actual123', newPassword: 'debil', confirmPassword: 'debil' });
    expect(component.passwordForm.get('newPassword')?.invalid).toBe(true);
    component.passwordForm.patchValue({ newPassword: 'Segura123', confirmPassword: 'Segura123' });
    expect(component.passwordForm.valid).toBe(true);
    component.savePassword();
    expect(component.isEditingPassword()).toBe(false);
  });
});
