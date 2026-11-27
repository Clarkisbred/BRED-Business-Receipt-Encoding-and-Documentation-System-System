export type AppRole = 'creator' | 'admin' | 'encoder';

export type ModuleKey =
  | 'DASHBOARD'
  | 'MESSAGES'
  | 'DUCKY'
  | 'RECEIPTS'
  | 'PROJECTS'
  | 'LIVE_MAP'
  | 'CONSTRUCTION'
  | 'SUPPLIERS'
  | 'REPORTS'
  | 'USERS'
  | 'ACTIVITY_LOG'
  | 'COMPANIES'
  | 'HISTORY'
  | 'SETTINGS';

export interface UserProfile {
  id: string;
  fullName: string;
  email: string;
  role: AppRole;
  companyName: string;
}
