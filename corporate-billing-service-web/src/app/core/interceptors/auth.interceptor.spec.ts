import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { authInterceptor } from './auth.interceptor';
import { AuthService } from '../services/auth.service';
import { environment } from '../../../environments/environment';
describe('API bearer isolation',()=>{
 it('sends the token only to this API, not a third-party URL or a prefix lookalike',()=>{
  TestBed.configureTestingModule({providers:[provideHttpClient(withInterceptors([authInterceptor])),provideHttpClientTesting(),{provide:AuthService,useValue:{getToken:()=> 'test-token'}}]});
  const client=TestBed.inject(HttpClient), http=TestBed.inject(HttpTestingController);
  for(const url of [`${environment.apiUrl}/facturas/recibidas`,'https://other.example.test/api/v1/facturas',`${environment.apiUrl}-other`]){
   client.get(url).subscribe();const request=http.expectOne(url);
   expect(request.request.headers.has('Authorization')).toBe(url===`${environment.apiUrl}/facturas/recibidas`);request.flush({});
  }http.verify();
 });
});
