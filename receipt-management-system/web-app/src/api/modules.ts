import { desktopRequest } from './desktop';
import type { ModuleKey } from '../types';

export interface ModuleOption {
  label: string;
  value: string;
  description?: string;
  taxClassification?: string;
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
  chart?: Array<{ label: string; value: number }>;
}

export interface ModuleRecord {
  id: string;
  values: Record<string, unknown>;
}

export interface ConstructionTabInfo {
  title: string;
  table: string;
}

export interface ConstructionCategory {
  title: string;
  tabs: ConstructionTabInfo[];
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
  constructionCategories?: ConstructionCategory[];
  billingConfigurations?: Array<Record<string, unknown>>;
  billingRetentionReleases?: Array<Record<string, unknown>>;
  billingLegacyRetention?: Array<Record<string, unknown>>;
  billingMobilizationBalances?: Array<Record<string, unknown>>;
  royaltyConfiguration?: Record<string, unknown>;
  royaltyContracts?: Array<Record<string, unknown>>;
  royaltySuppliers?: Array<Record<string, unknown>>;
  royaltyReturns?: Array<Record<string, unknown>>;
  royaltyEvents?: Array<Record<string, unknown>>;
  royaltyProjects?: Array<Record<string, unknown>>;
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

export async function loadConstructionPage(table: string): Promise<ModulePageData> {
  return desktopRequest<ModulePageData>(
    `/api/v1/modules/CONSTRUCTION?table=${encodeURIComponent(table)}`,
  );
}

export async function searchMapLocations(query: string): Promise<MapSearchResult[]> {
  return desktopRequest<MapSearchResult[]>(`/api/v1/map/search?q=${encodeURIComponent(query)}`);
}

export async function saveModuleRecord(
  module: ModuleKey,
  action: 'save' | 'delete' | 'send' | 'ask' | 'report' | 'publish-release' | 'send-password-reset'
    | 'create-billing' | 'amend-billing' | 'save-billing-configuration' | 'decide-billing-adjustment'
    | 'decide-billing' | 'reverse-billing' | 'attach-billing-documents'
    | 'record-billing-payment' | 'refund-billing-payment' | 'record-withholding-remittance'
    | 'release-billing-retention' | 'save-royalty' | 'save-royalty-configuration'
    | 'record-royalty-return',
  values: Record<string, unknown>,
  id?: string,
): Promise<ModulePageData> {
  return desktopRequest<ModulePageData>(`/api/v1/modules/${encodeURIComponent(module)}`, {
    method: 'POST',
    body: { action, id, values },
  });
}
