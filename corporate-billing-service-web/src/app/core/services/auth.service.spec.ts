import { environment } from '../../../environments/environment';
import { enterLocalPreview, hasLocalPreviewAccess } from './local-preview';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { AuthService, safeReturnUrl } from './auth.service';

describe('Corporate SSO',()=>{
 let auth:AuthService; let http:HttpTestingController;
 beforeEach(()=>{sessionStorage.clear();localStorage.clear();TestBed.configureTestingModule({providers:[provideRouter([]),provideHttpClient(),provideHttpClientTesting()]});auth=TestBed.inject(AuthService);http=TestBed.inject(HttpTestingController);});
 afterEach(()=>{http.verify();sessionStorage.clear();});
 it('does not authenticate without a verified session',()=>{expect(auth.getToken()).toBeNull();expect(auth.isAuthenticated()).toBe(false);});
 it('blocks login until TI supplies the SSO registration',async()=>{
  const pending=auth.configuration();const assertion=expect(pending).rejects.toThrow('SSO pendiente');
  http.expectOne('/auth-config.json').flush({clientId:'',authorizationEndpoint:'',tokenEndpoint:''});await assertion;
  expect(auth.getToken()).toBeNull();
 });
 it('rejects mismatched callback state before exchanging a code',async()=>{
  sessionStorage.setItem('cb_sso_attempt',JSON.stringify({state:'expected',verifier:'verifier',createdAt:Date.now(),returnUrl:'/dashboard'}));
  await expect(auth.completeLogin(new URLSearchParams('code=x&state=attacker'))).rejects.toThrow('no corresponde');
  expect(sessionStorage.getItem('cb_sso_attempt')).toBeNull();expect(auth.getToken()).toBeNull();
 });
 async function loginUntilProfile(){
  sessionStorage.setItem('cb_sso_attempt',JSON.stringify({state:'expected',verifier:'pkce-test-value',createdAt:Date.now(),returnUrl:'/consultar-facturas?page=1'}));
  const pending=auth.completeLogin(new URLSearchParams('code=code-from-provider&state=expected'));
  http.expectOne('/auth-config.json').flush({clientId:'public-client',authorizationEndpoint:'https://sso.example.test/authorize',tokenEndpoint:'https://sso.example.test/token',scope:'openid'});
  await new Promise(resolve=>setTimeout(resolve,0));
  http.expectOne('https://sso.example.test/token').flush({access_token:'opaque-test-access-token',token_type:'Bearer'});
  await new Promise(resolve=>setTimeout(resolve,0));
  http.expectOne(req=>req.url.endsWith('/sesion')).flush({subject:'operator-1',name:'Operador de prueba',expiresAt:new Date(Date.now()+60000).toISOString(),roles:[]});
  await new Promise(resolve=>setTimeout(resolve,0));
  return pending;
 }
 it('rejects an SSO identity that is not registered or has no permissions in this system',async()=>{
  sessionStorage.setItem('cb_sso_attempt',JSON.stringify({state:'expected',verifier:'v',createdAt:Date.now(),returnUrl:'/dashboard'}));
  const login=auth.completeLogin(new URLSearchParams('code=c&state=expected'));
  const assertion=expect(login).rejects.toThrow('no tiene acceso al panel');
  http.expectOne('/auth-config.json').flush({clientId:'public-client',authorizationEndpoint:'https://sso.example.test/authorize',tokenEndpoint:'https://sso.example.test/token',scope:'openid'});
  await new Promise(resolve=>setTimeout(resolve,0));
  http.expectOne('https://sso.example.test/token').flush({access_token:'t',token_type:'Bearer'});
  await new Promise(resolve=>setTimeout(resolve,0));
  http.expectOne(req=>req.url.endsWith('/sesion')).flush({subject:'x',expiresAt:new Date(Date.now()+60000).toISOString(),roles:[]});
  await new Promise(resolve=>setTimeout(resolve,0));
  http.expectOne(req=>req.url.endsWith('/auth/me')).flush({id:null,username:'desconocido',name:null,email:null,registered:false,roles:[],permissions:[]});
  await assertion;expect(auth.getToken()).toBeNull();
 });
 it('exposes the effective permissions from /auth/me and refreshes them without a new login',async()=>{
  const pending=loginUntilProfile();
  await new Promise(resolve=>setTimeout(resolve,0));await new Promise(resolve=>setTimeout(resolve,0));
  http.expectOne(req=>req.url.endsWith('/auth/me')).flush({id:'u1',username:'analista1',name:'Ana',email:'a@x.co',registered:true,
   roles:['ANALISTA_CARTERA'],permissions:['INVOICES_VIEW','PAYMENTS_VIEW']});
  await pending;
  expect(auth.hasPermission('INVOICES_VIEW')).toBe(true);expect(auth.hasPermission('USERS_VIEW')).toBe(false);
  expect(auth.hasAnyPermission(['USERS_VIEW','PAYMENTS_VIEW'])).toBe(true);expect(auth.hasRole('ANALISTA_CARTERA')).toBe(true);
  expect(auth.getCurrentUser()?.role.name).toBe('Analista cartera');

  const refreshed=auth.refreshPermissions();
  const me=http.expectOne(req=>req.url.endsWith('/auth/me'));
  expect(me.request.headers.get('Authorization')).toBe('Bearer opaque-test-access-token');
  me.flush({id:'u1',username:'analista1',name:'Ana',email:'a@x.co',registered:true,roles:['ANALISTA_CARTERA'],permissions:['PAYMENTS_VIEW']});
  expect(await refreshed).toBe(true);
  expect(auth.hasPermission('INVOICES_VIEW')).toBe(false);
 });
 it('accepts a token only after the backend verifies the identity and role',async()=>{
  sessionStorage.setItem('cb_sso_attempt',JSON.stringify({state:'expected',verifier:'pkce-test-value',createdAt:Date.now(),returnUrl:'/consultar-facturas?page=1'}));
  const pending=auth.completeLogin(new URLSearchParams('code=code-from-provider&state=expected'));
  http.expectOne('/auth-config.json').flush({clientId:'public-client',authorizationEndpoint:'https://sso.example.test/authorize',tokenEndpoint:'https://sso.example.test/token',scope:'openid'});
  await new Promise(resolve=>setTimeout(resolve,0));
  const token=http.expectOne('https://sso.example.test/token');expect(token.request.body.get('code_verifier')).toBe('pkce-test-value');
  token.flush({access_token:'opaque-test-access-token',token_type:'Bearer'});await new Promise(resolve=>setTimeout(resolve,0));
  expect(auth.getToken()).toBeNull();
  const profile=http.expectOne(req=>req.url.endsWith('/sesion'));
  expect(profile.request.headers.get('Authorization')).toBe('Bearer opaque-test-access-token');
  profile.flush({subject:'operator-1',name:'Operador de prueba',expiresAt:new Date(Date.now()+60000).toISOString(),roles:[]});
  await new Promise(resolve=>setTimeout(resolve,0));
  // Los permisos salen de /auth/me (base de datos), no de los roles del token.
  const me=http.expectOne(req=>req.url.endsWith('/auth/me'));
  expect(me.request.headers.get('Authorization')).toBe('Bearer opaque-test-access-token');
  me.flush({id:'operator-1',username:'operator',name:'Operador de prueba',email:null,registered:true,roles:['FINANCIERA'],permissions:['INVOICES_VIEW']});
  expect(await pending).toBe('/consultar-facturas?page=1');expect(auth.currentUser()?.role.code).toBe('ANALYST');
  expect(auth.currentUser()?.permissions).toEqual(['INVOICES_VIEW']);
  expect(localStorage.getItem('cb_auth_token')).toBeNull();
 });
 it('ends local preview on logout and does not reopen it on token checks',()=>{
  const original={...environment};
  try {
   Object.assign(environment,{production:false,localPreview:true});
   enterLocalPreview();expect(hasLocalPreviewAccess()).toBe(true);
   expect(auth.getToken()).toBeNull();expect(hasLocalPreviewAccess()).toBe(true);
   auth.logout(false);
   expect(hasLocalPreviewAccess()).toBe(false);
   expect(auth.getToken()).toBeNull();expect(hasLocalPreviewAccess()).toBe(false);
   expect(sessionStorage.getItem('cb_sso_session')).toBeNull();
  } finally {Object.assign(environment,original);}
 });
 it('prevents external return URLs',()=>{expect(safeReturnUrl('//evil.example')).toBe('/dashboard');expect(safeReturnUrl('https://evil.example')).toBe('/dashboard');expect(safeReturnUrl('/consultar-facturas?page=2')).toContain('page=2');});
});
