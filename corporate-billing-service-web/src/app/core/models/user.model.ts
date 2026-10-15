import { Role } from './role.model';

export interface User {
  id: string;
  username: string;
  email: string;
  fullName: string;
  firstName?: string;
  lastName?: string;
  role: Role;
  /** Roles y permisos efectivos según el backend (/api/v1/auth/me). */
  roles?: string[];
  permissions?: string[];
  isActive: boolean;
  avatarUrl?: string;
  phone?: string;
  department?: string;
  position?: string;
  location?: string;
  company?: string;
  documentType?: string;
  documentNumber?: string;
  birthDate?: string;
  otpEnabled?: boolean;
  lastLoginAt?: string;
  createdAt: string;
  updatedAt?: string;
}

export interface UserProfile {
  id: string;
  username: string;
  email: string;
  fullName: string;
  roles: string[];
  permissions: string[];
}
