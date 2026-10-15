import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { AuthService } from '../../core/services/auth.service';
import { fakeAuth } from '../../../testing/fake-auth';
import { HasPermissionDirective } from './has-permission.directive';

@Component({
  standalone: true,
  imports: [HasPermissionDirective],
  template: `
    <button id="create" *hasPermission="'USERS_CREATE'">Nuevo usuario</button>
    <button id="any" *hasPermission="['ROLES_UPDATE', 'PERMISSIONS_ASSIGN']">Permisos</button>
  `
})
class HostComponent {}

describe('HasPermissionDirective', () => {
  it('muestra u oculta según el permiso y reacciona en vivo cuando cambia', () => {
    const auth = fakeAuth(['USERS_VIEW']);
    TestBed.configureTestingModule({ imports: [HostComponent], providers: [{ provide: AuthService, useValue: auth }] });
    const fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('#create')).toBeNull();
    expect(el.querySelector('#any')).toBeNull();

    auth.setPermissions(['USERS_VIEW', 'USERS_CREATE', 'PERMISSIONS_ASSIGN']);
    fixture.detectChanges();
    expect(el.querySelector('#create')).not.toBeNull();
    expect(el.querySelector('#any')).not.toBeNull();

    auth.setPermissions(['USERS_VIEW']);
    fixture.detectChanges();
    expect(el.querySelector('#create')).toBeNull();
  });
});
