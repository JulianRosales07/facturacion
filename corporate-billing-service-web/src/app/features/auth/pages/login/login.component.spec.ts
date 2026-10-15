import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { environment } from '../../../../../environments/environment';
import { LoginComponent } from './login.component';

describe('Temporary local login', () => {
  const original = { ...environment };
  let component: LoginComponent;
  let navigate: ReturnType<typeof vi.fn>;
  beforeEach(() => {
    sessionStorage.clear();
    Object.assign(environment, { production: false, localPreview: true });
    navigate = vi.fn().mockResolvedValue(true);
    TestBed.configureTestingModule({ providers: [
      { provide: Router, useValue: { navigateByUrl: navigate } },
      { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap({}) } } }
    ] });
    component = TestBed.runInInjectionContext(() => new LoginComponent());
  });
  afterEach(() => { Object.assign(environment, original); sessionStorage.clear(); });
  function submit(username: string, password: string) {
    const form = document.createElement('form');
    form.innerHTML = '<input name="username"><input name="password" type="password">';
    (form.elements.namedItem('username') as HTMLInputElement).value = username;
    (form.elements.namedItem('password') as HTMLInputElement).value = password;
    return { form, event: { preventDefault: vi.fn(), target: form } as unknown as Event };
  }
  it('opens local preview and clears the password without saving credentials', async () => {
    const { form, event } = submit('demo', 'test-only');
    await component.login(event);
    expect(navigate).toHaveBeenCalledWith('/dashboard', { replaceUrl: true });
    expect(sessionStorage.getItem('cb_local_preview')).toBe('active');
    expect(sessionStorage.length).toBe(1);
    expect((form.elements.namedItem('password') as HTMLInputElement).value).toBe('');
  });
  it('rejects empty credentials', async () => {
    await component.login(submit(' ', ' ').event);
    expect(navigate).not.toHaveBeenCalled();
    expect(sessionStorage.length).toBe(0);
  });
  it('does not allow provisional access in production', async () => {
    environment.production = true;
    await component.login(submit('demo', 'test-only').event);
    expect(navigate).not.toHaveBeenCalled();
    expect(sessionStorage.length).toBe(0);
  });
});
