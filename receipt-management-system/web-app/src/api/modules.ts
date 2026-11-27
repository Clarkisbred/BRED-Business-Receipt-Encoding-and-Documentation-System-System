import { desktopRequest } from './desktop';
import type { ModuleKey } from '../types';

export interface ModuleOption {
  label: string;
  value: string;
}

export interface ModuleField {
  key: string;
  label: string;
  type: 'text' | 'email' | 'number' | 'date' | 'select' | 'textarea' | 'checkbox' | 'password' | 'color';
  required?: boolean;
  options?: ModuleOption[];
  value?: string | number | boolean;
}

export interface ModuleColumn {
  key: string;
  label: string;
  numeric?: boolean;
}

export interface ModuleMetric {
  label: string;
  value: string;
  note?: string;
}

export interface ModuleRecord {
  id: string;
  values: Record<string, unknown>;
}

export interface ModulePageData {
  key: ModuleKey;
  title: string;
  description: string;
  metrics: ModuleMetric[];
  columns: ModuleColumn[];
  fields: ModuleField[];
  rows: ModuleRecord[];
  canCreate: boolean;
  canEdit: boolean;
  canDelete: boolean;
  primaryActionLabel: string;
  submitLabel: string;
  createAction: 'save' | 'send' | 'ask';
}

export interface MapSearchResult {
  displayName: string;
  latitude: string;
  longitude: string;
  type: string;
}

export async function loadModulePage(module: ModuleKey): Promise<ModulePageData> {
  return desktopRequest<ModulePageData>(`/api/v1/modules/${encodeURIComponent(module)}`);
}

export async function searchMapLocations(query: string): Promise<MapSearchResult[]> {
  return desktopRequest<MapSearchResult[]>(`/api/v1/map/search?q=${encodeURIComponent(query)}`);
}

export async function saveModuleRecord(
  module: ModuleKey,
  action: 'save' | 'delete' | 'send' | 'ask' | 'report' | 'publish-release',
  values: Record<string, unknown>,
  id?: string,
): Promise<ModulePageData> {
  return desktopRequest<ModulePageData>(`/api/v1/modules/${encodeURIComponent(module)}`, {
    method: 'POST',
    body: { action, id, values },
  });
}
