import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  AdminPermission, AdminRole, AdminUser, PageResponse, PermissionPayload, RolePayload, UserFilter, UserPayload
} from '../models/access.model';

/** API de administración de usuarios, roles y permisos (/api/v1/admin/**). */
@Injectable({ providedIn: 'root' })
export class AccessAdminService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/admin`;

  // ---------- usuarios ----------

  listUsers(filter: UserFilter, page: number, size: number): Observable<PageResponse<AdminUser>> {
    let params = new HttpParams().set('page', page).set('size', size);
    for (const key of ['username', 'name', 'email', 'role'] as const) {
      const value = filter[key]?.trim();
      if (value) params = params.set(key, value);
    }
    if (filter.active !== null && filter.active !== undefined) params = params.set('active', filter.active);
    return this.http.get<PageResponse<AdminUser>>(`${this.base}/users`, { params });
  }

  getUser(id: string): Observable<AdminUser> {
    return this.http.get<AdminUser>(`${this.base}/users/${id}`);
  }

  createUser(payload: UserPayload): Observable<AdminUser> {
    return this.http.post<AdminUser>(`${this.base}/users`, payload);
  }

  updateUser(id: string, payload: UserPayload): Observable<AdminUser> {
    const { username: _username, ...body } = payload;
    return this.http.put<AdminUser>(`${this.base}/users/${id}`, body);
  }

  changeUserStatus(id: string, active: boolean, version?: number | null): Observable<AdminUser> {
    return this.http.patch<AdminUser>(`${this.base}/users/${id}/status`, { active, version });
  }

  replaceUserRoles(id: string, roleIds: string[]): Observable<AdminUser> {
    return this.http.put<AdminUser>(`${this.base}/users/${id}/roles`, { roleIds });
  }

  deleteUser(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/users/${id}`);
  }

  // ---------- roles ----------

  listRoles(): Observable<AdminRole[]> {
    return this.http.get<AdminRole[]>(`${this.base}/roles`);
  }

  getRole(id: string): Observable<AdminRole> {
    return this.http.get<AdminRole>(`${this.base}/roles/${id}`);
  }

  createRole(payload: RolePayload): Observable<AdminRole> {
    return this.http.post<AdminRole>(`${this.base}/roles`, payload);
  }

  updateRole(id: string, payload: RolePayload): Observable<AdminRole> {
    return this.http.put<AdminRole>(`${this.base}/roles/${id}`, payload);
  }

  replaceRolePermissions(id: string, permissionIds: string[]): Observable<AdminRole> {
    return this.http.put<AdminRole>(`${this.base}/roles/${id}/permissions`, { permissionIds });
  }

  deleteRole(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/roles/${id}`);
  }

  // ---------- permisos ----------

  listPermissions(): Observable<AdminPermission[]> {
    return this.http.get<AdminPermission[]>(`${this.base}/permissions`);
  }

  getPermission(id: string): Observable<AdminPermission> {
    return this.http.get<AdminPermission>(`${this.base}/permissions/${id}`);
  }

  createPermission(payload: PermissionPayload): Observable<AdminPermission> {
    return this.http.post<AdminPermission>(`${this.base}/permissions`, payload);
  }

  updatePermission(id: string, payload: PermissionPayload): Observable<AdminPermission> {
    return this.http.put<AdminPermission>(`${this.base}/permissions/${id}`, payload);
  }

  deletePermission(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/permissions/${id}`);
  }
}
