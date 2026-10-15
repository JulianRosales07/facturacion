import { Routes } from '@angular/router';
import { MainLayoutComponent } from './layout/main-layout/main-layout.component';
import { authGuard } from './core/guards/auth.guard';
import { homeRedirect, permissionGuard } from './core/guards/permission.guard';
const login = () => import('./features/auth/pages/login/login.component').then(m=>m.LoginComponent);
// Cada pantalla exige su permiso (solo experiencia de usuario: el backend valida cada petición).
export const routes: Routes = [
 {path:'auth/login',loadComponent:login},{path:'auth/callback',redirectTo:'auth/login',pathMatch:'full'},{path:'login',redirectTo:'auth/login',pathMatch:'full'},
 {path:'',component:MainLayoutComponent,canActivate:[authGuard],canActivateChild:[authGuard],children:[
  {path:'',redirectTo:homeRedirect,pathMatch:'full'},
  {path:'dashboard',canActivate:[permissionGuard('INVOICES_VIEW')],loadComponent:()=>import('./features/dashboard/pages/dashboard/dashboard.component').then(m=>m.DashboardComponent)},
  {path:'consultar-facturas',canActivate:[permissionGuard('INVOICES_VIEW')],loadComponent:()=>import('./features/invoices/pages/invoice-list/invoice-list.component').then(m=>m.InvoiceListComponent)},
  {path:'consultar-facturas/:id',canActivate:[permissionGuard('INVOICES_VIEW')],loadComponent:()=>import('./features/invoices/pages/invoice-detail/invoice-detail.component').then(m=>m.InvoiceDetailComponent)},
  {path:'consultar-facturas/:id/pdf',canActivate:[permissionGuard('INVOICES_VIEW')],loadComponent:()=>import('./features/invoices/pages/invoice-graphic/invoice-graphic.component').then(m=>m.InvoiceGraphicComponent)},
  {path:'consultar-facturas/:id/representacion-grafica',redirectTo:'consultar-facturas/:id/pdf',pathMatch:'full'},
  {path:'sincronizacion',canActivate:[permissionGuard('SYNC_VIEW')],loadComponent:()=>import('./features/sync/pages/sync-status/sync-status.component').then(m=>m.SyncStatusComponent)},
  {path:'integracion-ordenes',canActivate:[permissionGuard('IMPORTS_VIEW')],loadComponent:()=>import('./features/import-orders/pages/import-orders/import-orders.component').then(m=>m.ImportOrdersComponent)},
  {path:'cartera',canActivate:[permissionGuard('CARTERA_VIEW')],loadComponent:()=>import('./features/cartera/pages/cartera/cartera.component').then(m=>m.CarteraComponent)},
  {path:'pagos',canActivate:[permissionGuard('PAYMENTS_VIEW')],loadComponent:()=>import('./features/payments/pages/payment-settlement/payment-settlement.component').then(m=>m.PaymentSettlementComponent)},
  {path:'auditoria-retenciones',canActivate:[permissionGuard('WITHHOLDINGS_AUDIT_VIEW')],loadComponent:()=>import('./features/payments/pages/withholding-audit/withholding-audit.component').then(m=>m.WithholdingAuditComponent)},
  {path:'catalogo-tributario',canActivate:[permissionGuard('TAX_CONCEPTS_VIEW')],loadComponent:()=>import('./features/taxes/pages/tax-catalog/tax-catalog.component').then(m=>m.TaxCatalogComponent)},
  {path:'admin/billing-rules',canActivate:[permissionGuard('BILLING_RULES_VIEW')],loadComponent:()=>import('./features/admin/pages/billing-rules/billing-rules.component').then(m=>m.BillingRulesAdminComponent)},
  {path:'admin/users',canActivate:[permissionGuard('USERS_VIEW')],loadComponent:()=>import('./features/admin/pages/users/users.component').then(m=>m.UsersComponent)},
  {path:'admin/roles',canActivate:[permissionGuard('ROLES_VIEW')],loadComponent:()=>import('./features/admin/pages/roles/roles.component').then(m=>m.RolesComponent)},
  {path:'admin/permissions',canActivate:[permissionGuard('PERMISSIONS_VIEW')],loadComponent:()=>import('./features/admin/pages/permissions/permissions.component').then(m=>m.PermissionsComponent)},
  {path:'sin-permisos',loadComponent:()=>import('./features/admin/pages/forbidden/forbidden.component').then(m=>m.ForbiddenComponent)},
  {path:'perfil',loadComponent:()=>import('./features/profile/pages/profile/profile.component').then(m=>m.ProfileComponent)},
  {path:'profile',redirectTo:'perfil',pathMatch:'full'},
  {path:'sync',redirectTo:'sincronizacion',pathMatch:'full'},
  {path:'invoices',redirectTo:'consultar-facturas',pathMatch:'full'},
  {path:'resumen-general',redirectTo:'dashboard',pathMatch:'full'},
  {path:'**',redirectTo:homeRedirect}
 ]},
 {path:'**',redirectTo:''}
];
