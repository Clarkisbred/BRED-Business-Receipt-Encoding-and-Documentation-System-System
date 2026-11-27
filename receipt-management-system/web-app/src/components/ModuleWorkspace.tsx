import { useEffect, useMemo, useState, type FormEvent } from 'react';
import {
  AlertCircle,
  Bug,
  CalendarDays,
  Check,
  Download,
  LockKeyhole,
  Mail,
  MapPin,
  Moon,
  Palette,
  Phone,
  Plus,
  RefreshCw,
  Search,
  Sun,
  X,
} from 'lucide-react';
import { ProfileAvatar } from './IdentityAvatar';
import { booleanValue, displayValue, inputValue } from '../utils/moduleValues';
import {
  loadModulePage,
  saveModuleRecord,
  searchMapLocations,
  type MapSearchResult,
  type ModuleField,
  type ModulePageData,
} from '../api/modules';
import type { ModuleKey } from '../types';

function uniqueValues(rows: ModulePageData['rows'], key: string): string[] {
  return [...new Set(rows.map((row) => displayValue(row.values[key])).filter(Boolean))]
    .sort((left, right) => left.localeCompare(right));
}

function csvCell(value: unknown): string {
  const text = displayValue(value);
  return `"${text.replaceAll('"', '""')}"`;
}

function rowAmount(row: ModulePageData['rows'][number]): number {
  const value = row.values.amount ?? row.values.total ?? row.values.expenses;
  const normalized = displayValue(value).replace(/[^\d.-]/g, '');
  const amount = Number(normalized);
  return Number.isFinite(amount) ? amount : 0;
}

function coordinateValue(value: unknown): number {
  if (typeof value !== 'number' && (typeof value !== 'string' || value.trim() === '')) return Number.NaN;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : Number.NaN;
}

function statusTone(value: unknown): 'success' | 'warning' | 'danger' | 'neutral' {
  const normalized = displayValue(value).toLocaleLowerCase();
  if (['active', 'approved', 'completed', 'verified', 'finalized', 'yes'].includes(normalized)) return 'success';
  if (['pending', 'draft', 'at risk', 'delayed', 'in progress'].some((state) => normalized.includes(state))) return 'warning';
  if (['inactive', 'rejected', 'stopped', 'overdue', 'no'].includes(normalized)) return 'danger';
  return 'neutral';
}

function defaultValue(field: ModuleField): string | number | boolean {
  if (field.value !== undefined && displayValue(field.value) !== '') return field.value;
  if (field.type === 'checkbox') return false;
  return '';
}

function LoadingRows() {
  return (
    <div className="module-table-skeleton" aria-label="Loading records" role="status">
      {Array.from({ length: 5 }, (_, row) => (
        <div className="module-skeleton-row" key={row}>
          {Array.from({ length: 4 }, (_, column) => (
            <span className="loading-skeleton-line" key={column} />
          ))}
        </div>
      ))}
    </div>
  );
}

type SettingsNotice = { kind: 'success' | 'error'; message: string };

export function ModuleWorkspace({ module }: { module: ModuleKey }) {
  const [data, setData] = useState<ModulePageData | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [query, setQuery] = useState('');
  const [editingId, setEditingId] = useState<string | null>(null);
  const [formValues, setFormValues] = useState<Record<string, string | number | boolean>>({});
  const [refreshKey, setRefreshKey] = useState(0);
  const [mapQuery, setMapQuery] = useState('');
  const [searchedMapQuery, setSearchedMapQuery] = useState('');
  const [mapResults, setMapResults] = useState<MapSearchResult[]>([]);
  const [mapSearching, setMapSearching] = useState(false);
  const [selectedProjectId, setSelectedProjectId] = useState('');
  const [receiptFrom, setReceiptFrom] = useState('');
  const [receiptTo, setReceiptTo] = useState('');
  const [receiptSupplier, setReceiptSupplier] = useState('');
  const [receiptProject, setReceiptProject] = useState('');
  const [receiptType, setReceiptType] = useState('');
  const [selectedReceiptId, setSelectedReceiptId] = useState<string | null>(null);
  const [selectedMapMarkerId, setSelectedMapMarkerId] = useState<string | null>(null);
  const [constructionArea, setConstructionArea] = useState('All areas');
  const [reportGroup, setReportGroup] = useState('All reports');
  const [accountStatus, setAccountStatus] = useState('All accounts');
  const [settingsAppearance, setSettingsAppearance] = useState('');
  const [settingsDark, setSettingsDark] = useState(false);
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [bugTitle, setBugTitle] = useState('');
  const [bugDescription, setBugDescription] = useState('');
  const [releaseVersion, setReleaseVersion] = useState('');
  const [settingsNotice, setSettingsNotice] = useState<SettingsNotice | null>(null);
  const [userActionNotice, setUserActionNotice] = useState<SettingsNotice | null>(null);

  useEffect(() => {
    let active = true;
    setLoading(true);
    setData(null);
    setError(null);
    void loadModulePage(module).then(
      (result) => {
        if (active) {
          setData(result);
          if (module === 'SETTINGS' && result.key === 'SETTINGS') {
            setReleaseVersion(result.metrics.find((metric) => metric.label === 'Latest release')?.value ?? '');
          }
        }
      },
      (reason: unknown) => {
        if (active) setError(reason instanceof Error ? reason.message : 'BRED could not load this workspace.');
      },
    ).finally(() => {
      if (active) setLoading(false);
    });
    return () => {
      active = false;
    };
  }, [module, refreshKey]);

  useEffect(() => {
    if (module !== 'SETTINGS' || data?.key !== 'SETTINGS') return;
    const appearance = data.fields.find((field) => field.key === 'appearance');
    const dark = data.fields.find((field) => field.key === 'dark');
    setSettingsAppearance(typeof appearance?.value === 'string' ? appearance.value : '');
    setSettingsDark(Boolean(dark?.value));
    setNewPassword('');
    setConfirmPassword('');
    setSettingsNotice(null);
  }, [data?.key, module]);

  useEffect(() => {
    setQuery('');
    setEditingId(null);
    setFormValues({});
    setReceiptFrom('');
    setReceiptTo('');
    setReceiptSupplier('');
    setReceiptProject('');
    setReceiptType('');
    setSelectedReceiptId(null);
    setSelectedMapMarkerId(null);
    setConstructionArea('All areas');
    setReportGroup('All reports');
    setAccountStatus('All accounts');
    setUserActionNotice(null);
  }, [module]);

  const rows = useMemo(() => {
    const records = data?.rows ?? [];
    const normalized = query.trim().toLocaleLowerCase();
    return records.filter((row) => {
      if (normalized && !Object.values(row.values)
        .some((value) => displayValue(value).toLocaleLowerCase().includes(normalized))) return false;
      if (module === 'RECEIPTS') {
        const date = displayValue(row.values.date);
        const supplier = displayValue(row.values.supplier);
        const project = displayValue(row.values.project);
        const type = displayValue(row.values.type);
        if (receiptFrom && date < receiptFrom) return false;
        if (receiptTo && date > receiptTo) return false;
        if (receiptSupplier && supplier !== receiptSupplier) return false;
        if (receiptProject && project !== receiptProject) return false;
        if (receiptType && type !== receiptType) return false;
      }
      if (module === 'CONSTRUCTION' && constructionArea !== 'All areas'
        && displayValue(row.values.recordType) !== constructionArea) return false;
      if (module === 'REPORTS' && reportGroup !== 'All reports'
        && displayValue(row.values.report) !== reportGroup) return false;
      if (module === 'USERS' && accountStatus !== 'All accounts') {
        const status = displayValue(row.values.accountStatus).toLocaleLowerCase();
        const isActive = row.values.active === true;
        if (accountStatus === 'Active' && !isActive) return false;
        if (accountStatus === 'Inactive' && (isActive || status.includes('pending') || status.includes('rejected'))) return false;
        if (accountStatus === 'Pending' && !status.includes('pending')) return false;
        if (accountStatus === 'Rejected' && !status.includes('rejected')) return false;
      }
      return true;
    });
  }, [accountStatus, constructionArea, data, module, query, receiptFrom, receiptProject,
    receiptSupplier, receiptTo, receiptType, reportGroup]);

  const receiptSuppliers = useMemo(() => uniqueValues(data?.rows ?? [], 'supplier'), [data]);
  const receiptProjects = useMemo(() => uniqueValues(data?.rows ?? [], 'project'), [data]);
  const receiptTypes = useMemo(() => uniqueValues(data?.rows ?? [], 'type'), [data]);
  const constructionAreas = useMemo(() => uniqueValues(data?.rows ?? [], 'recordType'), [data]);
  const reportTypes = useMemo(() => uniqueValues(data?.rows ?? [], 'report'), [data]);
  const selectedReceipt = module === 'RECEIPTS'
    ? rows.find((row) => row.id === selectedReceiptId) ?? null
    : null;
  const mapMarker = module === 'LIVE_MAP'
    ? data?.rows.find((row) => row.id === selectedMapMarkerId) ?? data?.rows[0] ?? null
    : null;
  const mapLatitude = coordinateValue(mapMarker?.values.latitude);
  const mapLongitude = coordinateValue(mapMarker?.values.longitude);
  const hasMapCoordinates = Number.isFinite(mapLatitude) && Number.isFinite(mapLongitude)
    && mapLatitude >= -90 && mapLatitude <= 90 && mapLongitude >= -180 && mapLongitude <= 180;
  const mapFrameUrl = hasMapCoordinates
    ? `https://www.openstreetmap.org/export/embed.html?bbox=${mapLongitude - 0.01}%2C${mapLatitude - 0.01}%2C${mapLongitude + 0.01}%2C${mapLatitude + 0.01}&layer=mapnik&marker=${mapLatitude}%2C${mapLongitude}`
    : 'https://www.openstreetmap.org/export/embed.html?bbox=116.9%2C4.2%2C126.6%2C21.2&layer=mapnik';
  const reportBars = module === 'REPORTS'
    ? [...rows].sort((left, right) => rowAmount(right) - rowAmount(left)).slice(0, 5)
    : [];
  const largestReportAmount = Math.max(1, ...reportBars.map(rowAmount));
  const visibleReceiptTotalCents = module === 'RECEIPTS'
    ? rows.reduce((sum, row) => sum + Math.round(rowAmount(row) * 100), 0)
    : 0;

  function beginCreate() {
    if (!data) return;
    setEditingId(null);
    setFormValues(Object.fromEntries(data.fields.map((field) => [field.key, defaultValue(field)])));
    setError(null);
  }

  function beginEdit(id: string) {
    const row = data?.rows.find((record) => record.id === id);
    if (!row || !data) return;
    setEditingId(id);
    setFormValues(Object.fromEntries(data.fields.map((field) => [
      field.key,
      field.type === 'checkbox'
        ? booleanValue(row.values[field.key])
        : displayValue(row.values[field.key]),
    ])));
    setError(null);
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!data) return;
    setSaving(true);
    setError(null);
    try {
      const updated = await saveModuleRecord(module, data.createAction, formValues, editingId ?? undefined);
      setData(updated);
      setEditingId(null);
      setFormValues({});
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'BRED could not save this record.');
    } finally {
      setSaving(false);
    }
  }

  async function saveSettings(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSaving(true);
    setSettingsNotice(null);
    try {
      setData(await saveModuleRecord('SETTINGS', 'save', {
        appearance: settingsAppearance,
        dark: settingsDark,
        newPassword,
        confirmPassword,
      }));
      setNewPassword('');
      setConfirmPassword('');
      setSettingsNotice({ kind: 'success', message: 'Your preferences were saved.' });
    } catch (reason) {
      setSettingsNotice({
        kind: 'error',
        message: reason instanceof Error ? reason.message : 'BRED could not save your settings.',
      });
    } finally {
      setSaving(false);
    }
  }

  async function submitBugReport(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSaving(true);
    setSettingsNotice(null);
    try {
      setData(await saveModuleRecord('SETTINGS', 'report', {
        title: bugTitle,
        description: bugDescription,
      }));
      setBugTitle('');
      setBugDescription('');
      setSettingsNotice({ kind: 'success', message: 'Your report was sent to the BRED Creator.' });
    } catch (reason) {
      setSettingsNotice({
        kind: 'error',
        message: reason instanceof Error ? reason.message : 'BRED could not submit your report.',
      });
    } finally {
      setSaving(false);
    }
  }

  async function publishRelease(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSaving(true);
    setSettingsNotice(null);
    try {
      setData(await saveModuleRecord('SETTINGS', 'publish-release', { version: releaseVersion }));
      setSettingsNotice({ kind: 'success', message: `Version ${releaseVersion.trim()} was published.` });
    } catch (reason) {
      setSettingsNotice({
        kind: 'error',
        message: reason instanceof Error ? reason.message : 'BRED could not publish this version.',
      });
    } finally {
      setSaving(false);
    }
  }

  async function deleteRecord(id: string) {
    if (!window.confirm('Delete this record? This cannot be undone.')) return;
    setSaving(true);
    setError(null);
    try {
      setData(await saveModuleRecord(module, 'delete', {}, id));
      if (id === selectedReceiptId) setSelectedReceiptId(null);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'BRED could not delete this record.');
    } finally {
      setSaving(false);
    }
  }

  async function sendPasswordReset(id: string, email: string) {
    if (!window.confirm(`Send a password reset email to ${email}? The recipient must open it to choose a new password.`)) return;
    setSaving(true);
    setError(null);
    setUserActionNotice(null);
    try {
      setData(await saveModuleRecord('USERS', 'send-password-reset', {}, id));
      setUserActionNotice({ kind: 'success', message: `Password reset email sent to ${email}.` });
    } catch (reason) {
      setUserActionNotice({
        kind: 'error',
        message: reason instanceof Error ? reason.message : 'BRED could not send the password reset email.',
      });
    } finally {
      setSaving(false);
    }
  }

  async function searchMap(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setMapSearching(true);
    setSearchedMapQuery(mapQuery.trim());
    setError(null);
    setMapResults([]);
    try {
      setMapResults(await searchMapLocations(mapQuery));
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'BRED could not search for that location.');
    } finally {
      setMapSearching(false);
    }
  }

  async function saveMapResult(result: MapSearchResult) {
    setSaving(true);
    setError(null);
    try {
      const updated = await saveModuleRecord(module, 'save', {
        markerName: result.displayName,
        latitude: result.latitude,
        longitude: result.longitude,
        projectId: selectedProjectId,
        status: 'Verified',
      });
      setData(updated);
      setSelectedMapMarkerId(updated.rows.find((row) => (
        displayValue(row.values.markerName) === result.displayName
      ))?.id ?? null);
      setMapResults([]);
      setMapQuery('');
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'BRED could not save this location.');
    } finally {
      setSaving(false);
    }
  }

  function exportCsv() {
    if (!data) return;
    const csv = [
      data.columns.map((column) => csvCell(column.label)).join(','),
      ...rows.map((row) => data.columns.map((column) => csvCell(row.values[column.key])).join(',')),
    ].join('\r\n');
    const url = URL.createObjectURL(new Blob([`\uFEFF${csv}`], { type: 'text/csv;charset=utf-8' }));
    const link = document.createElement('a');
    link.href = url;
    link.download = `${module.toLocaleLowerCase()}-${new Date().toISOString().slice(0, 10)}.csv`;
    document.body.append(link);
    link.click();
    link.remove();
    window.setTimeout(() => URL.revokeObjectURL(url), 0);
  }

  function clearReceiptFilters() {
    setQuery('');
    setReceiptFrom('');
    setReceiptTo('');
    setReceiptSupplier('');
    setReceiptProject('');
    setReceiptType('');
  }

  function renderField(field: ModuleField) {
    const value = formValues[field.key] ?? defaultValue(field);
    const textValue = inputValue(value);
    const common = {
      id: `module-field-${field.key}`,
      name: field.key,
      required: field.required,
      disabled: saving,
    };

    if (field.type === 'textarea') {
      return (
        <textarea
          {...common}
          value={textValue}
          onChange={(event) => setFormValues((previous) => ({ ...previous, [field.key]: event.target.value }))}
          rows={4}
        />
      );
    }
    if (field.type === 'select') {
      return (
        <select
          {...common}
          value={textValue}
          onChange={(event) => setFormValues((previous) => ({ ...previous, [field.key]: event.target.value }))}
        >
          <option value="">Select…</option>
          {(field.options ?? []).map((option) => (
            <option value={option.value} key={option.value}>{option.label}</option>
          ))}
        </select>
      );
    }
    if (field.type === 'checkbox') {
      return (
        <input
          {...common}
          type="checkbox"
          checked={booleanValue(value)}
          onChange={(event) => setFormValues((previous) => ({ ...previous, [field.key]: event.target.checked }))}
        />
      );
    }
    return (
      <input
        {...common}
        type={field.type}
        value={textValue}
        onChange={(event) => setFormValues((previous) => ({ ...previous, [field.key]: event.target.value }))}
      />
    );
  }

  if (loading && !data) {
    return (
      <section className="module-page" aria-busy="true">
        <div className="page-heading">
          <div>
            <p className="eyebrow">BRED WORKSPACE</p>
            <span className="loading-skeleton-line loading-skeleton-title" />
          </div>
        </div>
        <div className="module-metric-skeleton">
          {Array.from({ length: 3 }, (_, index) => <span className="loading-skeleton-line" key={index} />)}
        </div>
        <section className="panel module-table-panel"><LoadingRows /></section>
      </section>
    );
  }

  if (error && !data) {
    return (
      <section className="module-page">
        <div className="notice notice-error" role="alert">
          <AlertCircle aria-hidden="true" size={20} />
          <div><strong>Workspace data is unavailable.</strong><p>{error}</p></div>
          <button className="button button-secondary" type="button" onClick={() => setRefreshKey((key) => key + 1)}>
            <RefreshCw aria-hidden="true" size={16} /> Retry
          </button>
        </div>
      </section>
    );
  }

  if (!data) return null;
  const isCreator = data.metrics.find((metric) => metric.label === 'Role')?.value.toLocaleLowerCase() === 'creator';
  const appearanceField = data.fields.find((field) => field.key === 'appearance');
  const bugReportsAvailable = data.metrics.some((metric) => (
    metric.label === 'Bug report submissions' && metric.value === 'Available'
  ));
  const releasePublishingAvailable = data.metrics.some((metric) => (
    metric.label === 'Release publishing' && metric.value === 'Available'
  ));

  return (
    <section className="module-page" aria-labelledby={`module-title-${module}`}>
      <header className="page-heading module-page-heading">
        <div>
          <p className="eyebrow">BRED WORKSPACE</p>
          <h2 id={`module-title-${module}`}>{data.title}</h2>
          <p className="page-subtitle">{data.description}</p>
        </div>
        <div className="module-page-actions">
          <button className="button button-secondary" type="button" onClick={() => setRefreshKey((key) => key + 1)} disabled={loading || saving}>
            <RefreshCw aria-hidden="true" size={16} /> Refresh
          </button>
          {(module === 'RECEIPTS' || module === 'REPORTS') && (
            <button className="button button-secondary" type="button" onClick={exportCsv} disabled={rows.length === 0}>
              <Download aria-hidden="true" size={16} /> Export CSV
            </button>
          )}
          {data.canCreate && module !== 'SETTINGS' && (
            <button className="button button-primary" type="button" onClick={beginCreate}>
              <Plus aria-hidden="true" size={16} /> {data.primaryActionLabel || 'New record'}
            </button>
          )}
        </div>
      </header>

      {data.metrics.length > 0 && (
        <div className="module-metrics">
          {data.metrics.map((metric) => (
            <article className="stat-card module-metric-card" key={metric.label}>
              <span className="stat-label">{metric.label}</span>
              <strong className="stat-value">{metric.value}</strong>
              {metric.note && <span className="stat-note">{metric.note}</span>}
            </article>
          ))}
        </div>
      )}

      {error && <div className="form-message" role="alert">{error}</div>}
      {module === 'USERS' && userActionNotice && (
        <p className={`settings-notice is-${userActionNotice.kind}`} role={userActionNotice.kind === 'error' ? 'alert' : 'status'}>
          {userActionNotice.message}
        </p>
      )}

      {module === 'RECEIPTS' && (
        <section className="panel module-filter-panel" aria-label="Filter receipts">
          <div className="module-filter-title">
            <CalendarDays aria-hidden="true" size={17} />
            <strong>Receipt filters</strong>
            <span className="module-filter-summary">
              {rows.length} of {data.rows.length} receipts · ₱{(visibleReceiptTotalCents / 100).toLocaleString('en-PH', {
                minimumFractionDigits: 2,
                maximumFractionDigits: 2,
              })}
            </span>
          </div>
          <div className="module-filter-grid">
            <label className="module-filter-field">
              <span>From</span>
              <input aria-label="Receipt date from" type="date" value={receiptFrom} onChange={(event) => setReceiptFrom(event.target.value)} />
            </label>
            <label className="module-filter-field">
              <span>To</span>
              <input aria-label="Receipt date to" type="date" value={receiptTo} onChange={(event) => setReceiptTo(event.target.value)} />
            </label>
            <label className="module-filter-field">
              <span>Supplier</span>
              <select aria-label="Filter by supplier" value={receiptSupplier} onChange={(event) => setReceiptSupplier(event.target.value)}>
                <option value="">All suppliers</option>
                {receiptSuppliers.map((supplier) => <option key={supplier} value={supplier}>{supplier}</option>)}
              </select>
            </label>
            <label className="module-filter-field">
              <span>Project</span>
              <select aria-label="Filter by project" value={receiptProject} onChange={(event) => setReceiptProject(event.target.value)}>
                <option value="">All projects</option>
                {receiptProjects.map((project) => <option key={project} value={project}>{project}</option>)}
              </select>
            </label>
            <label className="module-filter-field">
              <span>Document type</span>
              <select aria-label="Filter by document type" value={receiptType} onChange={(event) => setReceiptType(event.target.value)}>
                <option value="">All types</option>
                {receiptTypes.map((type) => <option key={type} value={type}>{type}</option>)}
              </select>
            </label>
            <button className="button button-quiet module-filter-clear" type="button" onClick={clearReceiptFilters}>
              Clear filters
            </button>
          </div>
        </section>
      )}

      {module === 'CONSTRUCTION' && constructionAreas.length > 0 && (
        <nav className="module-category-filter" aria-label="Filter construction records by area">
          {['All areas', ...constructionAreas].map((area) => {
            const count = area === 'All areas'
              ? data.rows.length
              : data.rows.filter((row) => displayValue(row.values.recordType) === area).length;
            return (
              <button
                className={`module-category-button${constructionArea === area ? ' is-selected' : ''}`}
                type="button"
                key={area}
                aria-pressed={constructionArea === area}
                onClick={() => setConstructionArea(area)}
              >
                <span>{area}</span><small>{count}</small>
              </button>
            );
          })}
        </nav>
      )}

      {module === 'REPORTS' && reportTypes.length > 1 && (
        <nav className="module-report-tabs" aria-label="Report grouping">
          {['All reports', ...reportTypes].map((group) => (
            <button
              className={`module-report-tab${reportGroup === group ? ' is-selected' : ''}`}
              type="button"
              key={group}
              aria-pressed={reportGroup === group}
              onClick={() => setReportGroup(group)}
            >
              {group}
            </button>
          ))}
        </nav>
      )}

      {module === 'USERS' && (
        <label className="module-inline-filter">
          <span>Account status</span>
          <select aria-label="Filter users by account status" value={accountStatus} onChange={(event) => setAccountStatus(event.target.value)}>
            <option>All accounts</option>
            <option>Active</option>
            <option>Inactive</option>
            <option>Pending</option>
            <option>Rejected</option>
          </select>
        </label>
      )}

      {module === 'SETTINGS' && (
        <div className="settings-workspace">
          <section className="panel settings-section" aria-labelledby="settings-appearance-title">
            <div className="settings-section-heading">
              <span className="settings-section-icon"><Palette aria-hidden="true" size={18} /></span>
              <div>
                <h3 id="settings-appearance-title">Appearance</h3>
                <p>Use a BRED preset and color mode across the desktop and charts.</p>
              </div>
            </div>
            <form className="settings-appearance-form" onSubmit={(event) => void saveSettings(event)}>
              <label className="module-field">
                <span>Theme preset</span>
                <select
                  aria-label="Theme preset"
                  value={settingsAppearance}
                  onChange={(event) => setSettingsAppearance(event.target.value)}
                  disabled={saving}
                  required
                >
                  <option value="">Select a preset</option>
                  {(appearanceField?.options ?? []).map((option) => (
                    <option value={option.value} key={option.value}>{option.label}</option>
                  ))}
                </select>
              </label>
              <label className="settings-mode-control">
                <span className="settings-mode-icon">
                  {settingsDark ? <Moon aria-hidden="true" size={17} /> : <Sun aria-hidden="true" size={17} />}
                </span>
                <span className="settings-mode-copy">
                  <strong>{settingsDark ? 'Dark mode' : 'Light mode'}</strong>
                  <small>Switch the interface between dark and light.</small>
                </span>
                <input
                  type="checkbox"
                  aria-label="Dark mode"
                  checked={settingsDark}
                  onChange={(event) => setSettingsDark(event.target.checked)}
                  disabled={saving}
                />
              </label>
              <div className="settings-preview-row">
                <span>Theme colors</span>
                <div className="module-theme-swatches" aria-label="Current theme colors">
                  {['--accent', '--chart-1', '--chart-2', '--chart-3', '--chart-4', '--chart-5'].map((token) => (
                    <span key={token} style={{ backgroundColor: `var(${token})` }} title={token} />
                  ))}
                </div>
              </div>
              <button className="button button-primary" type="submit" disabled={saving}>
                <Check aria-hidden="true" size={16} /> {saving ? 'Saving…' : 'Save appearance'}
              </button>
            </form>
          </section>

          <section className="panel settings-section" aria-labelledby="settings-password-title">
            <div className="settings-section-heading">
              <span className="settings-section-icon"><LockKeyhole aria-hidden="true" size={18} /></span>
              <div>
                <h3 id="settings-password-title">Password</h3>
                <p>Change your account password. Leave both fields blank to keep it unchanged.</p>
              </div>
            </div>
            <form className="settings-password-form" onSubmit={(event) => void saveSettings(event)}>
              <label className="module-field">
                <span>New password</span>
                <input
                  type="password"
                  autoComplete="new-password"
                  value={newPassword}
                  onChange={(event) => setNewPassword(event.target.value)}
                  disabled={saving}
                />
              </label>
              <label className="module-field">
                <span>Confirm new password</span>
                <input
                  type="password"
                  autoComplete="new-password"
                  value={confirmPassword}
                  onChange={(event) => setConfirmPassword(event.target.value)}
                  disabled={saving}
                />
              </label>
              <button className="button button-secondary" type="submit" disabled={saving}>
                {saving ? 'Saving…' : 'Update password'}
              </button>
            </form>
          </section>

          <section className="panel settings-section" aria-labelledby="settings-contacts-title">
            <div className="settings-section-heading">
              <span className="settings-section-icon"><Mail aria-hidden="true" size={18} /></span>
              <div>
                <h3 id="settings-contacts-title">Creator contacts</h3>
                <p>Contact the BRED Creator for account or product support.</p>
              </div>
            </div>
            <div className="settings-contact-card">
              <ProfileAvatar className="settings-contact-avatar" name="Clark Hilario" email="clarkhilario.bread@gmail.com" />
              <div className="settings-contact-copy">
                <strong>Clark Hilario</strong>
                <a href="mailto:clarkhilario.bread@gmail.com">clarkhilario.bread@gmail.com</a>
                <a href="tel:+639944826590"><Phone aria-hidden="true" size={14} /> +63 994 482 6590</a>
              </div>
            </div>
          </section>

          <section className="panel settings-section" aria-labelledby="settings-report-title">
            <div className="settings-section-heading">
              <span className="settings-section-icon"><Bug aria-hidden="true" size={18} /></span>
              <div>
                <h3 id="settings-report-title">Report a problem</h3>
                <p>Describe what happened. Your report is sent securely to the BRED Creator.</p>
              </div>
            </div>
            <form className="settings-report-form" onSubmit={(event) => void submitBugReport(event)}>
              <label className="module-field">
                <span>Report title</span>
                <input
                  value={bugTitle}
                  onChange={(event) => setBugTitle(event.target.value)}
                  maxLength={200}
                  required
                  disabled={saving || !bugReportsAvailable}
                />
              </label>
              <label className="module-field">
                <span>What happened?</span>
                <textarea
                  value={bugDescription}
                  onChange={(event) => setBugDescription(event.target.value)}
                  maxLength={10000}
                  rows={5}
                  required
                  disabled={saving || !bugReportsAvailable}
                />
              </label>
              <button className="button button-primary" type="submit" disabled={saving || !bugReportsAvailable}>
                <Bug aria-hidden="true" size={16} /> {saving ? 'Sending…' : 'Submit bug report'}
              </button>
              {!bugReportsAvailable && <p className="settings-empty">Bug reports require a Cloud Database connection.</p>}
            </form>
          </section>

          {isCreator && (
            <section className="panel settings-section" aria-labelledby="settings-release-title">
              <div className="settings-section-heading">
                <span className="settings-section-icon"><RefreshCw aria-hidden="true" size={18} /></span>
                <div>
                  <h3 id="settings-release-title">Application version</h3>
                  <p>Publish the current release version for BRED users.</p>
                </div>
              </div>
              <div className="settings-version-summary">
                <span><small>Installed here</small><strong>{data.metrics.find((metric) => metric.label === 'Installed version')?.value ?? 'Unavailable'}</strong></span>
                <span><small>Current release</small><strong>{data.metrics.find((metric) => metric.label === 'Latest release')?.value ?? 'Unavailable'}</strong></span>
              </div>
              <form className="settings-password-form" onSubmit={(event) => void publishRelease(event)}>
                <label className="module-field">
                  <span>Publish version</span>
                  <input
                    value={releaseVersion}
                    onChange={(event) => setReleaseVersion(event.target.value)}
                    maxLength={64}
                    placeholder="1.2.3"
                    required
                    disabled={saving || !releasePublishingAvailable}
                  />
                </label>
                <button className="button button-secondary" type="submit" disabled={saving || !releasePublishingAvailable}>
                  {saving ? 'Publishing…' : 'Publish version'}
                </button>
                {!releasePublishingAvailable && <p className="settings-empty">Release publishing requires a Cloud Database connection.</p>}
              </form>
            </section>
          )}

          {isCreator && (
            <section className="panel settings-section settings-reports-inbox" aria-labelledby="settings-reports-title">
              <div className="settings-section-heading">
                <span className="settings-section-icon"><Bug aria-hidden="true" size={18} /></span>
                <div>
                  <h3 id="settings-reports-title">Submitted reports</h3>
                  <p>Creator-only inbox · {data.rows.length} report{data.rows.length === 1 ? '' : 's'}</p>
                </div>
              </div>
              {!bugReportsAvailable ? (
                <p className="settings-empty">The report inbox is unavailable without a Cloud Database connection.</p>
              ) : data.rows.length === 0 ? (
                <p className="settings-empty">No bug reports have been submitted yet.</p>
              ) : (
                <div className="settings-report-list">
                  {data.rows.map((row) => (
                    <article className="settings-report-item" key={row.id}>
                      <div>
                        <strong>{displayValue(row.values.title)}</strong>
                        <span>{displayValue(row.values.company)} · {displayValue(row.values.reporter)} ({displayValue(row.values.email)})</span>
                      </div>
                      <time>{displayValue(row.values.createdAt)}</time>
                      <p>{displayValue(row.values.description)}</p>
                    </article>
                  ))}
                </div>
              )}
            </section>
          )}

          {settingsNotice && (
            <p className={`settings-notice is-${settingsNotice.kind}`} role={settingsNotice.kind === 'error' ? 'alert' : 'status'}>
              {settingsNotice.message}
            </p>
          )}
        </div>
      )}

      {module === 'LIVE_MAP' && data.canCreate && (
        <section className="panel map-search-panel" aria-labelledby="map-search-title">
          <div className="panel-heading">
            <div>
              <h3 id="map-search-title">Find a project location</h3>
              <p>Search real places with OpenStreetMap Nominatim, then add a result to BRED.</p>
            </div>
            <MapPin aria-hidden="true" size={18} />
          </div>
          <form className="map-search-form" onSubmit={(event) => void searchMap(event)}>
            <label className="module-field">
              <span>Address or place</span>
              <input
                value={mapQuery}
                onChange={(event) => {
                  setMapQuery(event.target.value);
                  setMapResults([]);
                }}
                placeholder="e.g. Quezon City, Philippines"
                maxLength={160}
                required
              />
            </label>
            <label className="module-field">
              <span>Project</span>
              <select value={selectedProjectId} onChange={(event) => setSelectedProjectId(event.target.value)}>
                <option value="">Unassigned</option>
                {(data.fields.find((field) => field.key === 'projectId')?.options ?? []).map((option) => (
                  <option value={option.value} key={option.value}>{option.label}</option>
                ))}
              </select>
            </label>
            <button className="button button-primary" type="submit" disabled={mapSearching || saving}>
              <Search aria-hidden="true" size={16} /> {mapSearching ? 'Searching…' : 'Search places'}
            </button>
          </form>
          {mapResults.length > 0 && (
            <ul className="map-search-results" aria-label="Location search results">
              {mapResults.map((result, index) => (
                <li key={`${result.latitude},${result.longitude},${index}`}>
                  <div>
                    <strong>{result.displayName}</strong>
                    <span>{result.type || 'Place'} · {result.latitude}, {result.longitude}</span>
                  </div>
                  <button
                    className="button button-secondary"
                    type="button"
                    onClick={() => void saveMapResult(result)}
                    disabled={saving}
                  >
                    Add marker
                  </button>
                </li>
              ))}
            </ul>
          )}
          {!mapSearching && searchedMapQuery === mapQuery.trim() && searchedMapQuery
            && mapResults.length === 0 && !error && (
            <p className="map-search-empty">No places found. Try a more specific address.</p>
          )}
        </section>
      )}

      {module === 'LIVE_MAP' && (
        <section className="panel module-map-preview" aria-labelledby="module-map-title">
          <div className="panel-heading">
            <div>
              <h3 id="module-map-title">Project locations</h3>
              <p>{data.rows.length} saved marker{data.rows.length === 1 ? '' : 's'} · Map tiles from OpenStreetMap</p>
            </div>
            <MapPin aria-hidden="true" size={18} />
          </div>
          {mapFrameUrl ? (
            <div className="module-map-content">
              <iframe
                title={mapMarker && hasMapCoordinates
                  ? `OpenStreetMap location: ${displayValue(mapMarker.values.markerName)}`
                  : 'OpenStreetMap map centered on the Philippines'}
                src={mapFrameUrl}
                loading="eager"
                referrerPolicy="strict-origin-when-cross-origin"
              />
              {data.rows.length > 0 && (
                <div className="module-map-marker-list" aria-label="Saved map markers">
                  {data.rows.map((row) => (
                    <button
                      className={`module-map-marker${mapMarker?.id === row.id ? ' is-selected' : ''}`}
                      type="button"
                      key={row.id}
                      aria-pressed={mapMarker?.id === row.id}
                      onClick={() => setSelectedMapMarkerId(row.id)}
                    >
                      <strong>{displayValue(row.values.markerName)}</strong>
                      <span>{displayValue(row.values.project)} · {displayValue(row.values.status)}</span>
                    </button>
                  ))}
                </div>
              )}
              {!hasMapCoordinates && (
                <p className="module-map-empty-note">
                  {data.rows.length
                    ? 'The selected marker has no valid coordinates. The map is centered on the Philippines.'
                    : 'Map view is ready. Search for a place above to add the first project marker.'}
                </p>
              )}
              {mapMarker && hasMapCoordinates && (
                <a
                  className="text-button module-map-open"
                  href={`https://www.openstreetmap.org/?mlat=${mapLatitude}&mlon=${mapLongitude}#map=15/${mapLatitude}/${mapLongitude}`}
                  target="_blank"
                  rel="noopener noreferrer"
                >
                  Open selected location in OpenStreetMap
                </a>
              )}
            </div>
          ) : null}
        </section>
      )}

      {module === 'REPORTS' && reportBars.length > 0 && (
        <section className="panel module-report-summary" aria-labelledby="module-report-summary-title">
          <div className="panel-heading">
            <div>
              <h3 id="module-report-summary-title">Largest groups</h3>
              <p>Based on the currently selected report grouping</p>
            </div>
          </div>
          <div className="module-report-bars">
            {reportBars.map((row) => (
              <div className="module-report-bar" key={row.id}>
                <span title={displayValue(row.values.label)}>{displayValue(row.values.label)}</span>
                <div role="presentation"><i style={{ width: `${Math.max(3, rowAmount(row) / largestReportAmount * 100)}%` }} /></div>
                <strong>{displayValue(row.values.amount)}</strong>
              </div>
            ))}
          </div>
        </section>
      )}

      {module !== 'SETTINGS' && data.fields.length > 0 && (editingId !== null || Object.keys(formValues).length > 0) && (
        <section className="panel module-form-panel" aria-labelledby="module-form-title">
          <div className="panel-heading">
            <div>
              <h3 id="module-form-title">{editingId ? 'Edit record' : data.primaryActionLabel}</h3>
              <p>Changes are validated and saved by BRED.</p>
            </div>
            <button className="icon-button" type="button" aria-label="Close form" onClick={() => { setEditingId(null); setFormValues({}); }}>
              <X aria-hidden="true" size={18} />
            </button>
          </div>
          <form className="module-form" onSubmit={(event) => void submit(event)}>
            {data.fields.map((field) => (
              <label className={field.type === 'textarea' ? 'module-field module-field-wide' : 'module-field'} key={field.key}>
                <span>{field.label}{field.required && <strong aria-hidden="true"> *</strong>}</span>
                {renderField(field)}
              </label>
            ))}
            <div className="module-form-actions">
              <button className="button button-secondary" type="button" onClick={() => { setEditingId(null); setFormValues({}); }}>
                Cancel
              </button>
              <button className="button button-primary" type="submit" disabled={saving}>
                {saving ? 'Saving…' : data.submitLabel}
              </button>
            </div>
          </form>
        </section>
      )}

      {module !== 'SETTINGS' && <div className={`module-record-layout${selectedReceipt ? ' has-receipt-detail' : ''}`}>
        <section className="panel module-table-panel" aria-labelledby="module-records-title">
          <div className="panel-heading module-table-heading">
            <div>
              <h3 id="module-records-title">
                {module === 'RECEIPTS' ? 'Receipt register'
                  : module === 'USERS' ? 'Accounts'
                    : module === 'ACTIVITY_LOG' || module === 'HISTORY' ? 'Recent activity'
                      : module === 'CONSTRUCTION' ? 'Construction records' : 'Records'}
              </h3>
              <p>{rows.length} of {data.rows.length} shown</p>
            </div>
            <label className="module-search">
              <Search aria-hidden="true" size={16} />
              <span className="visually-hidden">{module === 'RECEIPTS' ? 'Search receipts' : 'Search records'}</span>
              <input
                value={query}
                onChange={(event) => setQuery(event.target.value)}
                placeholder={module === 'RECEIPTS' ? 'Search receipt, supplier, item or project' : 'Search records'}
                aria-label={module === 'RECEIPTS' ? 'Search receipts' : 'Search records'}
              />
            </label>
          </div>
          {loading ? <LoadingRows /> : rows.length === 0 ? (
            <div className="module-empty-state">
              <strong>{data.rows.length === 0 ? 'No records yet' : 'No matching records'}</strong>
              <span>{data.rows.length === 0 ? 'Records added to BRED will appear here.' : 'Try changing your search or filters.'}</span>
              {data.canCreate && data.rows.length === 0 && (
                <button className="button button-secondary" type="button" onClick={beginCreate}>Create the first record</button>
              )}
            </div>
          ) : (
            <div className="table-scroll">
              <table className={module === 'RECEIPTS' ? 'receipt-register-table' : undefined}>
                <thead>
                  <tr>
                    {data.columns.map((column) => <th className={column.numeric ? 'is-numeric' : undefined} key={column.key}>{column.label}</th>)}
                    {(data.canEdit || data.canDelete) && <th className="module-actions-heading">Actions</th>}
                  </tr>
                </thead>
                <tbody>
                  {rows.map((row) => (
                    <tr key={row.id} className={selectedReceiptId === row.id ? 'is-selected' : undefined}>
                      {data.columns.map((column) => {
                        const value = row.values[column.key];
                        const isReceiptLink = module === 'RECEIPTS' && column.key === 'receiptNo';
                        const isMapLink = module === 'LIVE_MAP' && column.key === 'markerName';
                        const isStatus = ['status', 'accountStatus', 'active'].includes(column.key);
                        return (
                          <td className={column.numeric ? 'is-numeric' : undefined} key={column.key}>
                            {isReceiptLink ? (
                              <button className="text-button module-record-link" type="button" aria-label={`View receipt ${displayValue(value)}`} onClick={() => setSelectedReceiptId(row.id)}>
                                {displayValue(value)}
                              </button>
                            ) : isMapLink ? (
                              <button className="text-button module-record-link" type="button" onClick={() => setSelectedMapMarkerId(row.id)}>
                                {displayValue(value)}
                              </button>
                            ) : isStatus ? (
                              <span className={`module-state-pill is-${statusTone(value)}`}>{displayValue(value)}</span>
                            ) : (
                              displayValue(value)
                            )}
                          </td>
                        );
                      })}
                      {(data.canEdit || data.canDelete) && (
                        <td className="module-row-actions">
                          {data.canEdit && <button className="text-button" type="button" onClick={() => beginEdit(row.id)}>Edit</button>}
                          {module === 'USERS' && data.canCreate && (
                            <button
                              className="text-button"
                              type="button"
                              onClick={() => void sendPasswordReset(row.id, displayValue(row.values.email))}
                              disabled={saving}
                            >
                              Send reset
                            </button>
                          )}
                          {data.canDelete && <button className="text-button text-button-danger" type="button" onClick={() => void deleteRecord(row.id)} disabled={saving}>Delete</button>}
                        </td>
                      )}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>

        {selectedReceipt && (
          <aside className="panel module-record-detail" aria-labelledby="receipt-detail-title">
            <div className="panel-heading">
              <div>
                <p className="eyebrow">RECEIPT DETAILS</p>
                <h3 id="receipt-detail-title">{displayValue(selectedReceipt.values.receiptNo)}</h3>
              </div>
              <button className="icon-button" type="button" aria-label="Close receipt details" onClick={() => setSelectedReceiptId(null)}>
                <X aria-hidden="true" size={18} />
              </button>
            </div>
            <dl className="module-detail-list">
              {[
                { label: 'Date', value: selectedReceipt.values.date },
                { label: 'Document type', value: selectedReceipt.values.type },
                { label: 'Supplier', value: selectedReceipt.values.supplier },
                { label: 'Supplier TIN', value: selectedReceipt.values.supplierTin },
                { label: 'Project', value: selectedReceipt.values.project },
                { label: 'Items', value: selectedReceipt.values.itemDescription },
                { label: 'Total', value: selectedReceipt.values.total },
              ].map(({ label, value }) => (
                <div key={label}>
                  <dt>{label}</dt>
                  <dd>{displayValue(value)}</dd>
                </div>
              ))}
              <div>
                <dt>Attachments</dt>
                <dd>{displayValue(selectedReceipt.values.attachmentNames) === ''
                  ? 'No attachments listed'
                  : displayValue(selectedReceipt.values.attachmentNames)}</dd>
              </div>
            </dl>
            {data.canDelete && (
              <button className="button button-secondary text-button-danger module-detail-delete" type="button" onClick={() => void deleteRecord(selectedReceipt.id)} disabled={saving}>
                Delete receipt
              </button>
            )}
          </aside>
        )}
      </div>}
    </section>
  );
}
