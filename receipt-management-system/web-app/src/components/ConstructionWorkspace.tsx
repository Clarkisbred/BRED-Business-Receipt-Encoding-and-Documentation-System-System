import { useEffect, useMemo, useState, type ChangeEvent, type FormEvent } from 'react';
import {
  AlertCircle,
  ClipboardList,
  Files,
  FolderKanban,
  HardHat,
  Pencil,
  Plus,
  ReceiptText,
  RefreshCw,
  Search,
  ShieldCheck,
  ShoppingCart,
  Trash2,
  UsersRound,
  X,
  type LucideIcon,
} from 'lucide-react';
import {
  loadConstructionPage,
  saveModuleRecord,
  type ConstructionCategory,
  type ModuleField,
  type ModulePageData,
  type ModuleRecord,
} from '../api/modules';
import { booleanValue, displayValue, inputValue } from '../utils/moduleValues';

const categoryIcons: Record<string, LucideIcon> = {
  'Projects and Planning': FolderKanban,
  'Billing and Finance': ReceiptText,
  'Procurement and Materials': ShoppingCart,
  'Workforce and Equipment': UsersRound,
  'Safety and Quality': ShieldCheck,
  'Documents and Closeout': Files,
};

const defaultTable = 'project_contracts';

function formatMetricPoint(metricLabel: string, value: number): string {
  if (/records$/i.test(metricLabel)) {
    return new Intl.NumberFormat('en-PH', { maximumFractionDigits: 0 }).format(value);
  }
  return new Intl.NumberFormat('en-PH', {
    style: 'currency',
    currency: 'PHP',
    maximumFractionDigits: 2,
  }).format(value);
}

function fieldDefault(field: ModuleField): string | boolean {
  if (field.type === 'checkbox') return booleanValue(field.value);
  return inputValue(field.value);
}

export function ConstructionWorkspace() {
  const [page, setPage] = useState<ModulePageData | null>(null);
  const [categories, setCategories] = useState<ConstructionCategory[]>([]);
  const [selectedCategory, setSelectedCategory] = useState('Projects and Planning');
  const [selectedTable, setSelectedTable] = useState(defaultTable);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [refreshKey, setRefreshKey] = useState(0);
  const [sectionQuery, setSectionQuery] = useState('');
  const [recordQuery, setRecordQuery] = useState('');
  const [editingId, setEditingId] = useState<string | null>(null);
  const [formValues, setFormValues] = useState<Record<string, string | boolean>>({});

  useEffect(() => {
    let active = true;
    setLoading(true);
    setError(null);
    void loadConstructionPage(selectedTable).then(
      (data) => {
        if (!active) return;
        setPage(data);
        const nextCategories = data.constructionCategories ?? [];
        setCategories(nextCategories);
        const selected = nextCategories.find((category) =>
          category.tabs.some((tab) => tab.table === selectedTable));
        if (selected) setSelectedCategory(selected.title);
      },
      (reason: unknown) => {
        if (active) setError(reason instanceof Error ? reason.message : 'BRED could not load construction records.');
      },
    ).finally(() => {
      if (active) setLoading(false);
    });
    return () => {
      active = false;
    };
  }, [refreshKey, selectedTable]);

  const activeCategory = categories.find((category) => category.title === selectedCategory)
    ?? categories.find((category) => category.tabs.some((tab) => tab.table === selectedTable))
    ?? categories[0];
  const visibleCategories = useMemo(() => {
    const query = sectionQuery.trim().toLocaleLowerCase();
    if (!query) return categories;
    return categories.filter((category) => category.title.toLocaleLowerCase().includes(query)
      || category.tabs.some((tab) => tab.title.toLocaleLowerCase().includes(query)));
  }, [categories, sectionQuery]);
  const visibleTabs = (activeCategory?.tabs ?? []).filter((tab) => {
    const query = sectionQuery.trim().toLocaleLowerCase();
    return !query || tab.title.toLocaleLowerCase().includes(query)
      || activeCategory?.title.toLocaleLowerCase().includes(query);
  });
  const visibleRows = useMemo(() => {
    const query = recordQuery.trim().toLocaleLowerCase();
    if (!query) return page?.rows ?? [];
    return (page?.rows ?? []).filter((row) =>
      Object.values(row.values).some((value) => displayValue(value).toLocaleLowerCase().includes(query)));
  }, [page, recordQuery]);

  function chooseCategory(category: ConstructionCategory) {
    const firstTab = category.tabs[0];
    if (!firstTab) return;
    setSelectedCategory(category.title);
    setSelectedTable(firstTab.table);
    setRecordQuery('');
    setEditingId(null);
    setFormValues({});
    setError(null);
    setNotice(null);
  }

  function chooseTab(table: string) {
    setSelectedTable(table);
    setRecordQuery('');
    setEditingId(null);
    setFormValues({});
    setError(null);
    setNotice(null);
  }

  function beginCreate() {
    if (!page) return;
    setEditingId(null);
    setFormValues(Object.fromEntries(page.fields.map((field) => [field.key, fieldDefault(field)])));
    setError(null);
    setNotice(null);
  }

  function beginEdit(row: ModuleRecord) {
    if (!page) return;
    setEditingId(row.id);
    setFormValues(Object.fromEntries(page.fields.map((field) => [
      field.key,
      field.type === 'checkbox' ? booleanValue(row.values[field.key]) : inputValue(row.values[field.key]),
    ])));
    setError(null);
    setNotice(null);
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!page) return;
    setSaving(true);
    setError(null);
    setNotice(null);
    try {
      const updated = await saveModuleRecord('CONSTRUCTION', 'save',
        { ...formValues, table: selectedTable }, editingId ?? undefined);
      setPage(updated);
      setEditingId(null);
      setFormValues({});
      setNotice('Construction record saved.');
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'BRED could not save this construction record.');
    } finally {
      setSaving(false);
    }
  }

  async function deleteRecord(row: ModuleRecord) {
    if (!window.confirm(`Delete this ${page?.title.toLocaleLowerCase()} record?`)) return;
    setSaving(true);
    setError(null);
    setNotice(null);
    try {
      setPage(await saveModuleRecord('CONSTRUCTION', 'delete', { table: selectedTable }, row.id));
      setNotice('Construction record deleted.');
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'BRED could not delete this construction record.');
    } finally {
      setSaving(false);
    }
  }

  function renderField(field: ModuleField) {
    const value = formValues[field.key] ?? fieldDefault(field);
    const common = {
      id: `construction-field-${field.key}`,
      name: field.key,
      required: field.required,
      disabled: saving,
    };
    if (field.type === 'checkbox') {
      return (
        <input
          {...common}
          type="checkbox"
          checked={booleanValue(value)}
          onChange={(event) => setFormValues((previous) => ({
            ...previous,
            [field.key]: event.target.checked,
          }))}
        />
      );
    }
    if (field.type === 'select') {
      return (
        <select
          {...common}
          value={inputValue(value)}
          onChange={(event) => setFormValues((previous) => ({
            ...previous,
            [field.key]: event.target.value,
          }))}
        >
          <option value="">Select…</option>
          {(field.options ?? []).map((option) => (
            <option value={option.value} key={option.value}>{option.label}</option>
          ))}
        </select>
      );
    }
    const inputType = ['text', 'number', 'date'].includes(field.type) ? field.type : 'text';
    const shared = {
      ...common,
      value: inputValue(value),
      onChange: (event: ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) =>
        setFormValues((previous) => ({ ...previous, [field.key]: event.target.value })),
    };
    return field.type === 'textarea'
      ? <textarea {...shared} rows={4} />
      : <input {...shared} type={inputType} />;
  }

  return (
    <section className="module-page construction-page" aria-labelledby="construction-title">
      <header className="page-heading module-page-heading construction-heading">
        <div>
          <p className="eyebrow">BRED WORKSPACE</p>
          <h2 id="construction-title"><HardHat aria-hidden="true" size={23} /> Construction</h2>
          <p className="page-subtitle">Projects, finance, procurement, workforce, quality and closeout.</p>
        </div>
        <button
          className="button button-secondary"
          type="button"
          onClick={() => setRefreshKey((key) => key + 1)}
          disabled={loading || saving}
        >
          <RefreshCw aria-hidden="true" size={16} /> Refresh
        </button>
      </header>

      {error && (
        <div className="notice notice-error construction-notice" role="alert">
          <AlertCircle aria-hidden="true" size={18} />
          <span>{error}</span>
          <button className="button button-secondary" type="button" onClick={() => setRefreshKey((key) => key + 1)}>
            Try again
          </button>
        </div>
      )}
      {notice && <p className="construction-notice-success" role="status">{notice}</p>}

      <div className="module-metric-grid construction-metrics">
        {(page?.metrics ?? []).map((metric) => {
          const points = metric.chart ?? [];
          const largestValue = Math.max(0, ...points.map((point) => Math.abs(point.value)));
          return (
            <article className="panel module-metric-card construction-metric-card" key={metric.label}>
              <span>{metric.label}</span>
              <strong>{metric.value}</strong>
              {points.length ? (
                <div
                  className="construction-metric-chart"
                  role="img"
                  aria-label={`${metric.label} ${metric.note ?? 'breakdown'}: ${points
                    .map((point) => `${point.label}, ${formatMetricPoint(metric.label, point.value)}`)
                    .join('; ')}`}
                >
                  {points.map((point, index) => {
                    const height = largestValue === 0
                      ? 0
                      : Math.max(8, Math.abs(point.value) / largestValue * 100);
                    return (
                      <span
                        className="construction-metric-bar"
                        key={point.label}
                        title={`${point.label}: ${formatMetricPoint(metric.label, point.value)}`}
                        aria-hidden="true"
                        style={{
                          height: `${height}%`,
                          backgroundColor: point.value < 0
                            ? 'var(--danger)'
                            : `var(--chart-${(index % 5) + 1})`,
                        }}
                      />
                    );
                  })}
                </div>
              ) : (
                <div
                  className="construction-metric-chart-empty"
                  role="img"
                  aria-label={`${metric.label}: no breakdown data`}
                >
                  No breakdown data
                </div>
              )}
              {metric.note && <small className="construction-metric-note">{metric.note}</small>}
            </article>
          );
        })}
        {loading && !page && Array.from({ length: 5 }, (_, index) => (
          <article className="panel module-metric-card construction-metric-loading" key={index} aria-hidden="true" />
        ))}
      </div>

      <div className="construction-workspace-layout">
        <aside className="panel construction-navigation">
          <label className="construction-section-search">
            <Search aria-hidden="true" size={16} />
            <span className="visually-hidden">Search construction sections</span>
            <input
              value={sectionQuery}
              onChange={(event) => setSectionQuery(event.target.value)}
              placeholder="Find a section"
              aria-label="Search construction sections"
            />
          </label>
          <nav aria-label="Construction categories">
            {visibleCategories.map((category) => {
              const Icon = categoryIcons[category.title] ?? ClipboardList;
              return (
                <button
                  type="button"
                  className={`construction-category${activeCategory?.title === category.title ? ' is-selected' : ''}`}
                  key={category.title}
                  aria-current={activeCategory?.title === category.title ? 'page' : undefined}
                  onClick={() => chooseCategory(category)}
                >
                  <Icon aria-hidden="true" size={17} />
                  <span>{category.title}</span>
                  <small>{category.tabs.length}</small>
                </button>
              );
            })}
            {visibleCategories.length === 0 && <p className="construction-navigation-empty">No sections match.</p>}
          </nav>
        </aside>

        <div className="construction-main">
          {activeCategory && (
            <nav className="construction-tabs" aria-label={`${activeCategory.title} sections`}>
              {visibleTabs.map((tab) => (
                <button
                  type="button"
                  className={`construction-tab${selectedTable === tab.table ? ' is-selected' : ''}`}
                  key={tab.table}
                  aria-pressed={selectedTable === tab.table}
                  onClick={() => chooseTab(tab.table)}
                >
                  <ClipboardList aria-hidden="true" size={15} />
                  {tab.title}
                </button>
              ))}
            </nav>
          )}

          <section className="panel construction-record-panel" aria-labelledby="construction-record-title">
            <div className="panel-heading construction-record-heading">
              <div>
                <p className="eyebrow">{activeCategory?.title ?? 'CONSTRUCTION'}</p>
                <h3 id="construction-record-title">{page?.title ?? 'Construction records'}</h3>
                <p>{loading ? 'Loading authorized records…' : `${visibleRows.length} of ${page?.rows.length ?? 0} shown`}</p>
              </div>
              <div className="construction-record-actions">
                <label className="module-search">
                  <Search aria-hidden="true" size={16} />
                  <span className="visually-hidden">Search records in this section</span>
                  <input
                    value={recordQuery}
                    onChange={(event) => setRecordQuery(event.target.value)}
                    placeholder="Search records"
                    aria-label="Search records in this section"
                  />
                </label>
                {page?.canCreate && (
                  <button className="button button-primary" type="button" onClick={beginCreate} disabled={saving}>
                    <Plus aria-hidden="true" size={16} /> Add record
                  </button>
                )}
              </div>
            </div>
            {loading ? (
              <div className="module-table-skeleton" aria-label="Loading records" role="status">
                {Array.from({ length: 4 }, (_, index) => <span className="loading-skeleton-line" key={index} />)}
              </div>
            ) : visibleRows.length === 0 ? (
              <div className="module-empty-state construction-empty-state">
                <ClipboardList aria-hidden="true" size={25} />
                <strong>{page?.rows.length ? 'No matching records' : `No ${page?.title.toLocaleLowerCase()} records yet`}</strong>
                <span>{page?.rows.length ? 'Try another search.' : 'Records added to this section will appear here.'}</span>
                {page?.canCreate && page.fields.length > 0 && (
                  <button className="button button-secondary" type="button" onClick={beginCreate}>
                    <Plus aria-hidden="true" size={16} /> Add the first record
                  </button>
                )}
              </div>
            ) : (
              <div className="table-scroll construction-table-scroll">
                <table>
                  <thead>
                    <tr>
                      {page?.columns.map((column) => (
                        <th className={column.numeric ? 'is-numeric' : undefined} key={column.key}>{column.label}</th>
                      ))}
                      {(page?.canEdit || page?.canDelete) && <th className="module-actions-heading">Actions</th>}
                    </tr>
                  </thead>
                  <tbody>
                    {visibleRows.map((row) => (
                      <tr key={row.id}>
                        {page?.columns.map((column) => (
                          <td className={column.numeric ? 'is-numeric' : undefined} key={column.key}>
                            {displayValue(row.values[column.key])}
                          </td>
                        ))}
                        {(page?.canEdit || page?.canDelete) && (
                          <td className="module-row-actions">
                            {page.canEdit && (
                              <button
                                className="icon-button construction-row-action"
                                type="button"
                                aria-label={`Edit ${page.title} record`}
                                onClick={() => beginEdit(row)}
                                disabled={saving}
                              >
                                <Pencil aria-hidden="true" size={15} />
                              </button>
                            )}
                            {page.canDelete && (
                              <button
                                className="icon-button construction-row-action is-danger"
                                type="button"
                                aria-label={`Delete ${page.title} record`}
                                onClick={() => void deleteRecord(row)}
                                disabled={saving}
                              >
                                <Trash2 aria-hidden="true" size={15} />
                              </button>
                            )}
                          </td>
                        )}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>

          {page?.fields.length && (editingId !== null || Object.keys(formValues).length > 0) ? (
            <section className="panel construction-form-panel" aria-labelledby="construction-form-title">
              <div className="panel-heading">
                <div>
                  <h3 id="construction-form-title">{editingId ? `Edit ${page.title}` : `New ${page.title}`}</h3>
                  <p>Changes are validated and saved by BRED.</p>
                </div>
                <button
                  className="icon-button"
                  type="button"
                  aria-label="Close construction form"
                  onClick={() => { setEditingId(null); setFormValues({}); }}
                >
                  <X aria-hidden="true" size={18} />
                </button>
              </div>
              <form className="module-form construction-form" onSubmit={(event) => void submit(event)}>
                {page.fields.map((field) => (
                  <label className={`module-field${field.type === 'textarea' ? ' module-field-wide' : ''}`} key={field.key}>
                    <span>{field.label}{field.required && <strong aria-hidden="true"> *</strong>}</span>
                    {renderField(field)}
                  </label>
                ))}
                <div className="module-form-actions">
                  <button
                    className="button button-secondary"
                    type="button"
                    onClick={() => { setEditingId(null); setFormValues({}); }}
                  >
                    Cancel
                  </button>
                  <button className="button button-primary" type="submit" disabled={saving}>
                    {saving ? 'Saving…' : 'Save record'}
                  </button>
                </div>
              </form>
            </section>
          ) : null}
        </div>
      </div>
    </section>
  );
}
