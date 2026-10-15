export type UserRole = 'ADMIN' | 'ANALYST' | 'VIEWER';

export interface Permission {
  id: string;
  code: string;
  name: string;
  description?: string;
  module: string;
}

export interface Role {
  id: string;
  code: UserRole;
  name: string;
  description: string;
  permissions: Permission[];
}
