import { useMemo, useState, type FormEvent } from 'react';
import Decimal from 'decimal.js-light';
import { CircleDollarSign, FileClock, Pencil, Plus, Settings2, Undo2, X } from 'lucide-react';
import { ModalDialog } from './ModalDialog';
import type { ModulePageData, ModuleRecord } from '../api/modules';

type RoyaltyAction = 'save-royalty' | 'save-royalty-configuration' | 'record-royalty-return';
type ConfigValues = {
  default_vat_rate: string;
  default_royalty_rate: string;
  default_royalty_basis: string;
  default_withholding_rate: string;
  default_withholding_basis: string;
  default_additional_withholding_rate: string;
  default_additional_withholding_basis: string;
  default_ewt_rate: string;
  default_ewt_basis: string;
  default_retention_rate: string;
  default_retention_basis: string;
  deduction_confirmation_threshold: string;
};
type RecordValues = {
  supplier_id: string;
  supplier_tax_classification: string;
  client_name: string;
  project_id: string;
  contract_id: string;
  contract_name: string;
  contract_amount: string;
  billing_date: string;
  billing_amount: string;
  vat_treatment: string;
  vat_amount: string;
  vat_rate: string;
  royalty_rate: string;
  royalty_basis: string;
  withholding_rate: string;
  withholding_basis: string;
  additional_withholding_rate: string;
  additional_withholding_basis: string;
  ewt_rate: string;
  ewt_basis: string;
  retention_rate: string;
  retention_basis: string;
  remarks: string;
};
type ReturnValues = { amount: string; return_date: string; reference: string; remarks: string };
type Preview = {
  gross: Decimal;
  vat: Decimal;
  vatExclusive: Decimal;
  royalty: Decimal;
  withholding: Decimal;
  additional: Decimal;
  ewt: Decimal;
  retention: Decimal;
  deductions: Decimal;
  initialNet: Decimal;
  valid: boolean;
};

const EMPTY_CONFIG: ConfigValues = {
  default_vat_rate: '',
  default_royalty_rate: '3',
  default_royalty_basis: 'VAT_EXCLUSIVE',
  default_withholding_rate: '2',
  default_withholding_basis: 'VAT_EXCLUSIVE',
  default_additional_withholding_rate: '',
  default_additional_withholding_basis: 'VAT_EXCLUSIVE',
  default_ewt_rate: '',
  default_ewt_basis: 'VAT_EXCLUSIVE',
  default_retention_rate: '',
  default_retention_basis: 'VAT_EXCLUSIVE',
  deduction_confirmation_threshold: '10000',
};

const BASIS_OPTIONS = [
  { value: 'VAT_EXCLUSIVE', label: 'VAT-exclusive amount' },
  { value: 'BILLING_AMOUNT', label: 'Billing amount' },
];

const STATUS_OPTIONS = [
  { value: 'all', label: 'All statuses' },
  { value: 'pending', label: 'Pending return' },
  { value: 'completed', label: 'Completed' },
];

function text(value: unknown, fallback = ''): string {
  return value == null ? fallback : String(value);
}

function decimal(value: unknown): Decimal | null {
  const raw = text(value).trim();
  if (!/^(?:\d+(?:\.\d*)?|\.\d+)$/.test(raw)) return null;
  try {
    const parsed = new Decimal(raw);
    return parsed.gte(0) ? parsed : null;
  } catch {
    return null;
  }
}

function money(value: unknown): string {
  const parsed = decimal(value);
  if (!parsed) return '—';
  const [whole, fraction] = parsed.toDecimalPlaces(2, Decimal.ROUND_HALF_UP).toFixed(2).split('.');
  return `₱${whole!.replace(/\B(?=(\d{3})+(?!\d))/g, ',')}.${fraction}`;
}

function percentage(base: Decimal, rate: unknown): Decimal | null {
  const parsedRate = decimal(rate);
  if (!parsedRate || parsedRate.gt(100)) return null;
  return base.mul(parsedRate).div(100).toDecimalPlaces(2, Decimal.ROUND_HALF_UP);
}

function calculate(values: RecordValues): Preview | null {
  const billing = decimal(values.billing_amount);
  const vatRate = decimal(values.vat_rate);
  const declaredVat = decimal(values.vat_amount) ?? new Decimal(0);
  const treatment = values.vat_treatment;
  if (!billing || (treatment === 'VAT_INCLUSIVE' && !vatRate)) return null;
  let gross = billing;
  let vat = new Decimal(0);
  let vatExclusive = billing;
  if (treatment === 'VAT_INCLUSIVE') {
    vatExclusive = billing.div(new Decimal(1).plus(vatRate!.div(100)))
      .toDecimalPlaces(2, Decimal.ROUND_HALF_UP);
    vat = billing.minus(vatExclusive);
  } else if (treatment === 'VAT_EXCLUSIVE') {
    vat = declaredVat;
    gross = billing.plus(vat);
  }
  const selectedBase = (basis: string) => basis === 'BILLING_AMOUNT' ? billing : vatExclusive;
  const royalty = percentage(selectedBase(values.royalty_basis), values.royalty_rate);
  const withholding = percentage(selectedBase(values.withholding_basis), values.withholding_rate);
  const additional = values.additional_withholding_rate.trim()
    ? percentage(selectedBase(values.additional_withholding_basis), values.additional_withholding_rate)
    : new Decimal(0);
  const ewt = values.ewt_rate.trim()
    ? percentage(selectedBase(values.ewt_basis), values.ewt_rate)
    : new Decimal(0);
  const retention = values.retention_rate.trim()
    ? percentage(selectedBase(values.retention_basis), values.retention_rate)
    : new Decimal(0);
  if (!royalty || !withholding || !additional || !ewt || !retention) return null;
  const deductions = royalty.plus(withholding).plus(additional).plus(ewt).plus(retention);
  return {
    gross,
    vat,
    vatExclusive,
    royalty,
    withholding,
    additional,
    ewt,
    retention,
    deductions,
    initialNet: gross.minus(deductions),
    valid: deductions.lte(gross),
  };
}

function rowValue(row: ModuleRecord, key: string, fallback = ''): string {
  return text(row.values[key], fallback);
}

function configValues(config?: Record<string, unknown>): ConfigValues {
  return {
    default_vat_rate: text(config?.default_vat_rate, EMPTY_CONFIG.default_vat_rate),
    default_royalty_rate: text(config?.default_royalty_rate, EMPTY_CONFIG.default_royalty_rate),
    default_royalty_basis: text(config?.default_royalty_basis, EMPTY_CONFIG.default_royalty_basis),
    default_withholding_rate: text(config?.default_withholding_rate, EMPTY_CONFIG.default_withholding_rate),
    default_withholding_basis: text(config?.default_withholding_basis, EMPTY_CONFIG.default_withholding_basis),
    default_additional_withholding_rate: text(
      config?.default_additional_withholding_rate, EMPTY_CONFIG.default_additional_withholding_rate,
    ),
    default_additional_withholding_basis: text(
      config?.default_additional_withholding_basis, EMPTY_CONFIG.default_additional_withholding_basis,
    ),
    default_ewt_rate: text(config?.default_ewt_rate, EMPTY_CONFIG.default_ewt_rate),
    default_ewt_basis: text(config?.default_ewt_basis, EMPTY_CONFIG.default_ewt_basis),
    default_retention_rate: text(config?.default_retention_rate, EMPTY_CONFIG.default_retention_rate),
    default_retention_basis: text(config?.default_retention_basis, EMPTY_CONFIG.default_retention_basis),
    deduction_confirmation_threshold: text(
      config?.deduction_confirmation_threshold, EMPTY_CONFIG.deduction_confirmation_threshold,
    ),
  };
}

function recordValues(row: ModuleRecord | null, config: ConfigValues): RecordValues {
  const now = new Date();
  const today = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
  if (!row) {
    return {
      supplier_id: '',
      supplier_tax_classification: 'UNCLASSIFIED',
      client_name: '',
      project_id: '',
      contract_id: '',
      contract_name: '',
      contract_amount: '',
      billing_date: today,
      billing_amount: '',
      vat_treatment: '',
      vat_amount: '0',
      vat_rate: config.default_vat_rate,
      royalty_rate: config.default_royalty_rate,
      royalty_basis: config.default_royalty_basis,
      withholding_rate: config.default_withholding_rate,
      withholding_basis: config.default_withholding_basis,
      additional_withholding_rate: config.default_additional_withholding_rate,
      additional_withholding_basis: config.default_additional_withholding_basis,
      ewt_rate: config.default_ewt_rate,
      ewt_basis: config.default_ewt_basis,
      retention_rate: config.default_retention_rate,
      retention_basis: config.default_retention_basis,
      remarks: '',
    };
  }
  return {
    ...recordValues(null, config),
    supplier_id: text(row.values.supplier_id),
    supplier_tax_classification: text(row.values.supplier_tax_classification, 'UNCLASSIFIED'),
    client_name: text(row.values.client_name),
    project_id: text(row.values.project_id),
    contract_id: text(row.values.contract_id),
    contract_name: text(row.values.contract_name),
    contract_amount: text(row.values.contract_amount),
    billing_date: text(row.values.billing_date),
    billing_amount: text(row.values.billing_amount),
    vat_treatment: text(row.values.vat_treatment),
    vat_rate: text(row.values.vat_rate),
    royalty_rate: text(row.values.royalty_rate),
    royalty_basis: text(row.values.royalty_basis, 'VAT_EXCLUSIVE'),
    withholding_rate: text(row.values.withholding_rate),
    withholding_basis: text(row.values.withholding_basis, 'VAT_EXCLUSIVE'),
    additional_withholding_rate: text(row.values.additional_withholding_rate),
    additional_withholding_basis: text(row.values.additional_withholding_basis, 'VAT_EXCLUSIVE'),
    ewt_rate: text(row.values.ewt_rate),
    ewt_basis: text(row.values.ewt_basis, 'VAT_EXCLUSIVE'),
    retention_rate: text(row.values.retention_rate),
    retention_basis: text(row.values.retention_basis, 'VAT_EXCLUSIVE'),
    vat_amount: text(row.values.vat_amount, '0'),
    remarks: text(row.values.remarks),
  };
}

function royaltyStatus(row: ModuleRecord): 'pending' | 'completed' {
  return text(row.values.status).toLocaleLowerCase() === 'completed' ? 'completed' : 'pending';
}

function amountSum(rows: ModuleRecord[], key: string): Decimal {
  return rows.reduce((sum, row) => sum.plus(decimal(row.values[key]) ?? 0), new Decimal(0));
}

function basisLabel(value: unknown): string {
  return text(value) === 'BILLING_AMOUNT' ? 'Billing amount' : 'VAT-exclusive amount';
}

export interface RoyaltiesWorkspaceProps {
  page: ModulePageData;
  saving: boolean;
  onAction: (action: RoyaltyAction, values: Record<string, unknown>, id?: string) => Promise<ModulePageData>;
}

export function RoyaltiesWorkspace({ page, saving, onAction }: RoyaltiesWorkspaceProps) {
  const config = useMemo(() => configValues(page.royaltyConfiguration), [page.royaltyConfiguration]);
  const [dialog, setDialog] = useState<'record' | 'config' | 'return' | null>(null);
  const [editing, setEditing] = useState<ModuleRecord | null>(null);
  const [selectedId, setSelectedId] = useState('');
  const [recordRequestKey, setRecordRequestKey] = useState(() => requestKey());
  const [formValues, setFormValues] = useState<RecordValues>(() => recordValues(null, config));
  const [configForm, setConfigForm] = useState<ConfigValues>(config);
  const [returnValues, setReturnValues] = useState<ReturnValues>({
    amount: '',
    return_date: '',
    reference: '',
    remarks: '',
  });
  const [search, setSearch] = useState('');
  const [statusFilter, setStatusFilter] = useState('all');
  const [fromDate, setFromDate] = useState('');
  const [toDate, setToDate] = useState('');
  const [sort, setSort] = useState('oldest');
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const records = page.rows;
  const selectedRecord = records.find((row) => row.id === selectedId) ?? null;
  const preview = useMemo(() => calculate(formValues), [formValues]);
  const filtered = useMemo(() => {
    const query = search.trim().toLocaleLowerCase();
    return records.filter((row) => {
      const date = rowValue(row, 'billing_date');
      const state = royaltyStatus(row);
      const matchesStatus = statusFilter === 'all' || statusFilter === state;
      const matchesDates = (!fromDate || date >= fromDate) && (!toDate || date <= toDate);
      const matchesText = !query || Object.values(row.values).some((value) =>
        text(value).toLocaleLowerCase().includes(query));
      return matchesStatus && matchesDates && matchesText;
    }).sort((left, right) => {
      if (sort === 'highest-royalty') {
        return (decimal(right.values.royalty_amount) ?? new Decimal(0))
          .comparedTo(decimal(left.values.royalty_amount) ?? new Decimal(0));
      }
      const dateOrder = rowValue(left, 'billing_date').localeCompare(rowValue(right, 'billing_date'));
      return sort === 'newest' ? -dateOrder : dateOrder;
    });
  }, [records, search, statusFilter, fromDate, toDate, sort]);

  const clientGroups = useMemo(() => {
    const clients = new Map<string, { name: string; years: Map<string, Map<string, ModuleRecord[]>> }>();
    for (const row of filtered) {
      const clientName = rowValue(row, 'client_name', 'Unnamed client').trim() || 'Unnamed client';
      const clientKey = clientName.toLocaleLowerCase();
      const year = rowValue(row, 'billing_date').slice(0, 4) || 'Unknown year';
      const month = rowValue(row, 'billing_date').slice(5, 7) || '00';
      if (!clients.has(clientKey)) clients.set(clientKey, { name: clientName, years: new Map() });
      const years = clients.get(clientKey)!.years;
      if (!years.has(year)) years.set(year, new Map());
      const months = years.get(year)!;
      if (!months.has(month)) months.set(month, []);
      months.get(month)!.push(row);
    }
    return Array.from(clients.entries()).map(([key, client]) => ({
      key,
      name: client.name,
      years: Array.from(client.years.entries()).sort(([left], [right]) => left.localeCompare(right)).map(([year, months]) => ({
        year,
        months: Array.from(months.entries()).sort(([left], [right]) => left.localeCompare(right))
          .map(([month, monthRecords]) => ({ month, records: monthRecords })),
      })),
    })).sort((left, right) => left.name.localeCompare(right.name));
  }, [filtered]);

  function beginCreate() {
    setEditing(null);
    setRecordRequestKey(requestKey());
    setFormValues(recordValues(null, config));
    setError('');
    setNotice('');
    setDialog('record');
  }

  function beginEdit(row: ModuleRecord) {
    setEditing(row);
    setRecordRequestKey('');
    setFormValues(recordValues(row, config));
    setError('');
    setNotice('');
    setSelectedId('');
    setDialog('record');
  }

  function closeDialog() {
    if (saving) return;
    setDialog(null);
    setEditing(null);
    setError('');
  }

  function updateForm(key: string, value: string) {
    setFormValues((current) => ({ ...current, [key]: value }));
  }

  function chooseSupplier(id: string) {
    const supplier = (page.royaltySuppliers ?? []).find((item) => text(item.id) === id);
    const classification = text(supplier?.tax_classification, 'UNCLASSIFIED').toLocaleUpperCase();
    const treatment = classification === 'VAT_EXEMPT' ? 'VAT_EXEMPT'
      : classification === 'NON_VAT' ? 'NON_VAT'
        : classification === 'VAT_REGISTERED' ? '' : 'UNCLASSIFIED';
    setFormValues((current) => ({
      ...current,
      supplier_id: id,
      supplier_tax_classification: classification,
      client_name: text(supplier?.business_name, current.client_name),
      vat_treatment: treatment,
    }));
  }

  function chooseContract(id: string) {
    const contract = (page.royaltyContracts ?? []).find((item) => text(item.id) === id);
    setFormValues((current) => ({
      ...current,
      contract_id: id,
      contract_name: text(contract?.contract_name, current.contract_name),
      contract_amount: text(contract?.contract_amount, current.contract_amount),
      project_id: text(contract?.project_id, current.project_id),
      client_name: text(contract?.client_name, current.client_name),
    }));
  }

  async function submitRecord(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError('');
    setNotice('');
    if (!formValues.client_name.trim()) {
      setError('Enter the client or payee name.');
      return;
    }
    if (!formValues.billing_date || Number(formValues.billing_date.slice(0, 4)) < 1500) {
      setError('Choose a billing date from year 1500 onward.');
      return;
    }
    if (!preview) {
      setError('Enter valid non-negative amounts and percentages to preview the calculation.');
      return;
    }
    if (!preview.valid) {
      setError('Total deductions cannot exceed the gross billing amount.');
      return;
    }
    if (editing && (royaltyStatus(editing) === 'completed'
      || (decimal(editing.values.returned_amount) ?? new Decimal(0)).gt(0))) {
      setError('A record with returns cannot be amended. Preserve its audit history and create a correcting record instead.');
      return;
    }
    const optionalDeduction = preview.additional.plus(preview.ewt).plus(preview.retention).gt(0);
    const threshold = decimal(config.deduction_confirmation_threshold) ?? new Decimal(10000);
    const significant = optionalDeduction || preview.deductions.gte(threshold)
      || formValues.royalty_rate !== config.default_royalty_rate
      || formValues.withholding_rate !== config.default_withholding_rate;
    if (significant && !window.confirm('This record includes a significant or non-standard deduction. Review the calculation before saving. Continue?')) {
      return;
    }
    try {
      const payload: Record<string, unknown> = {
        ...formValues,
        client_name: formValues.client_name.trim(),
        vat_amount: formValues.vat_amount || '0',
        ...(editing ? {} : { idempotency_key: recordRequestKey }),
      };
      await onAction('save-royalty', payload, editing?.id);
      setDialog(null);
      setEditing(null);
      setNotice(editing ? 'Royalty record updated.' : 'Royalty record saved.');
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'BRED could not save this royalty record.');
    }
  }

  async function submitConfiguration(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError('');
    try {
      await onAction('save-royalty-configuration', configForm);
      setDialog(null);
      setNotice('Company royalty settings saved. Existing records keep their saved rates.');
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'BRED could not save royalty settings.');
    }
  }

  function beginReturn(row: ModuleRecord) {
    const now = new Date();
    setSelectedId('');
    setReturnValues({
      amount: rowValue(row, 'outstanding_return', '0'),
      return_date: `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`,
      reference: '',
      remarks: '',
    });
    setError('');
    setDialog('return');
    setSelectedId(row.id);
  }

  async function submitReturn(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!selectedRecord) return;
    const amount = decimal(returnValues.amount);
    const outstanding = decimal(selectedRecord.values.outstanding_return);
    if (!amount || !outstanding || amount.lte(0) || amount.gt(outstanding)) {
      setError('Enter a return amount greater than zero and no more than the outstanding return balance.');
      return;
    }
    if (!returnValues.return_date || Number(returnValues.return_date.slice(0, 4)) < 1500) {
      setError('Choose an actual return date from year 1500 onward.');
      return;
    }
    setError('');
    try {
      const idempotencyKey = globalThis.crypto?.randomUUID?.()
        ?? `${Date.now()}-${Math.random().toString(36).slice(2)}`;
      await onAction('record-royalty-return', {
        record_id: selectedRecord.id,
        amount: amount.toFixed(2),
        return_date: returnValues.return_date,
        reference: returnValues.reference.trim(),
        remarks: returnValues.remarks.trim(),
        idempotency_key: idempotencyKey,
      });
      setDialog(null);
      setSelectedId('');
      setNotice('Actual withholding return recorded.');
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'BRED could not record this return.');
    }
  }

  function submitConfigFromHeader() {
    setConfigForm(config);
    setError('');
    setDialog('config');
  }

  const selectedReturns = (page.royaltyReturns ?? [])
    .filter((item) => text(item.record_id) === selectedId)
    .sort((left, right) => text(left.return_date).localeCompare(text(right.return_date)));
  const selectedEvents = (page.royaltyEvents ?? [])
    .filter((item) => text(item.record_id) === selectedId)
    .sort((left, right) => text(left.created_at).localeCompare(text(right.created_at)));
  const suppliers = page.royaltySuppliers ?? [];
  const contracts = page.royaltyContracts ?? [];

  return (
    <div className="royalties-workspace">
      <section className="panel royalties-overview" aria-label="Royalty overview">
        <div className="panel-heading">
          <div>
            <p className="eyebrow">BILLING &amp; FINANCE</p>
            <h2>Royalties</h2>
            <p>Track royalty calculations and actual withholding returns by client and billing period.</p>
          </div>
          <div className="construction-billing-actions">
            {page.canEdit && (
              <button className="button button-secondary" type="button" onClick={() => void submitConfigFromHeader()}>
                <Settings2 aria-hidden="true" size={16} /> Company settings
              </button>
            )}
            {page.canCreate && (
              <button className="button button-primary" type="button" onClick={beginCreate}>
                <Plus aria-hidden="true" size={16} /> New royalty record
              </button>
            )}
          </div>
        </div>
        <div className="royalties-metrics">
          {page.metrics.map((metric) => (
            <article key={metric.label}>
              <span>{metric.label}</span>
              <strong>{metric.value}</strong>
              {metric.note && <small>{metric.note}</small>}
            </article>
          ))}
        </div>
      </section>

      {notice && <p className="notice notice-success" role="status">{notice}</p>}
      {error && !dialog && <p className="notice notice-error" role="alert">{error}</p>}

      <section className="panel royalties-history" aria-labelledby="royalties-history-title">
        <div className="panel-heading">
          <div>
            <h3 id="royalties-history-title">Royalty records</h3>
            <p>Client, year, and month totals come from saved transactions.</p>
          </div>
        </div>
        <div className="royalties-filters">
          <label className="module-field">
            <span>Search records</span>
            <input value={search} onChange={(event) => setSearch(event.target.value)} placeholder="Client, contract, reference…" />
          </label>
          <label className="module-field">
            <span>Status</span>
            <select value={statusFilter} onChange={(event) => setStatusFilter(event.target.value)}>
              {STATUS_OPTIONS.map((option) => <option value={option.value} key={option.value}>{option.label}</option>)}
            </select>
          </label>
          <label className="module-field">
            <span>From</span>
            <input type="date" min="1500-01-01" value={fromDate} onChange={(event) => setFromDate(event.target.value)} />
          </label>
          <label className="module-field">
            <span>To</span>
            <input type="date" min="1500-01-01" value={toDate} onChange={(event) => setToDate(event.target.value)} />
          </label>
          <label className="module-field">
            <span>Sort by</span>
            <select value={sort} onChange={(event) => setSort(event.target.value)}>
              <option value="oldest">Oldest billing first</option>
              <option value="newest">Newest billing first</option>
              <option value="highest-royalty">Highest royalty first</option>
            </select>
          </label>
        </div>

        {clientGroups.length === 0 ? (
          <div className="royalties-empty">
            <CircleDollarSign aria-hidden="true" size={22} />
            <p>{records.length === 0 ? 'No royalty records yet.' : 'No records match these filters.'}</p>
            {records.length > 0 && <button className="button button-secondary" type="button" onClick={() => {
              setSearch(''); setStatusFilter('all'); setFromDate(''); setToDate('');
            }}>Clear filters</button>}
          </div>
        ) : (
          <div className="royalties-groups">
            {clientGroups.map((client) => (
              <details className="royalties-client" key={client.key} open>
                <summary>
                  <span>{client.name}</span>
                  <small>{client.years.reduce((count, year) => count + year.months.reduce((sum, month) => sum + month.records.length, 0), 0)} records</small>
                </summary>
                {client.years.map((year) => (
                  <details className="royalties-year" key={year.year} open={year.year === new Date().getFullYear().toString()}>
                    <summary>{year.year}</summary>
                    {year.months.map(({ month, records: monthRecords }) => {
                      const monthDate = month === '00' ? null : new Date(Number(year.year), Number(month) - 1, 1);
                      const monthLabel = monthDate?.toLocaleString('en', { month: 'long' }) ?? 'Unknown month';
                      return (
                        <details className="royalties-month" key={month} open={year.year === new Date().getFullYear().toString()
                          && month === String(new Date().getMonth() + 1).padStart(2, '0')}>
                          <summary>
                            <span>{monthLabel}</span>
                            <small>{monthRecords.length} billings · {money(amountSum(monthRecords, 'billing_amount'))} gross · {money(amountSum(monthRecords, 'royalty_amount'))} royalties</small>
                          </summary>
                          <div className="table-scroll">
                            <table>
                              <thead><tr>
                                <th>Billing date</th><th>Contract</th><th className="is-numeric">Billing</th>
                                <th className="is-numeric">Royalty</th><th className="is-numeric">Withheld</th>
                                <th className="is-numeric">Initial net</th><th className="is-numeric">Returned</th>
                                <th className="is-numeric">Final net</th><th>Status</th><th>Actions</th>
                              </tr></thead>
                              <tbody>
                                {monthRecords.map((row) => (
                                  <tr key={row.id}>
                                    <td>{rowValue(row, 'billing_date')}</td>
                                    <td>{rowValue(row, 'contract_name', '—')}</td>
                                    <td className="is-numeric">{money(row.values.billing_amount)}</td>
                                    <td className="is-numeric">{money(row.values.royalty_amount)}</td>
                                    <td className="is-numeric">{money(row.values.withholding_amount)}</td>
                                    <td className="is-numeric">{money(row.values.initial_net_amount)}</td>
                                    <td className="is-numeric">{money(row.values.returned_amount ?? '0')}</td>
                                    <td className="is-numeric">{money(row.values.final_net_amount)}</td>
                                    <td><span className={`royalties-status is-${royaltyStatus(row)}`}>{royaltyStatus(row) === 'completed' ? 'Completed' : 'Pending'}</span></td>
                                    <td>
                                      <button className="button button-quiet" type="button" onClick={() => setSelectedId(row.id)}>Details</button>
                                    </td>
                                  </tr>
                                ))}
                                <tr className="royalties-month-total">
                                  <th colSpan={2}>Monthly total</th>
                                  <td className="is-numeric">{money(amountSum(monthRecords, 'billing_amount'))}</td>
                                  <td className="is-numeric">{money(amountSum(monthRecords, 'royalty_amount'))}</td>
                                  <td className="is-numeric">{money(amountSum(monthRecords, 'withholding_amount'))}</td>
                                  <td className="is-numeric">{money(amountSum(monthRecords, 'initial_net_amount'))}</td>
                                  <td className="is-numeric">{money(amountSum(monthRecords, 'returned_amount'))}</td>
                                  <td className="is-numeric">{money(amountSum(monthRecords, 'final_net_amount'))}</td>
                                  <td colSpan={2}></td>
                                </tr>
                              </tbody>
                            </table>
                          </div>
                        </details>
                      );
                    })}
                  </details>
                ))}
              </details>
            ))}
          </div>
        )}
      </section>

      {selectedRecord && dialog === null && (
        <ModalDialog labelledBy="royalty-detail-title" onClose={() => setSelectedId('')}>
          <section className="panel royalties-dialog" aria-labelledby="royalty-detail-title">
            <div className="panel-heading">
              <div><p className="eyebrow">ROYALTY RECORD</p><h3 id="royalty-detail-title">{rowValue(selectedRecord, 'client_name')}</h3></div>
              <button className="icon-button" type="button" aria-label="Close royalty details" onClick={() => setSelectedId('')}><X aria-hidden="true" size={18} /></button>
            </div>
            <dl className="royalties-detail-grid">
              <div><dt>Billing date</dt><dd>{rowValue(selectedRecord, 'billing_date')}</dd></div>
              <div><dt>Contract</dt><dd>{rowValue(selectedRecord, 'contract_name', '—')}</dd></div>
              <div><dt>Billing amount</dt><dd>{money(selectedRecord.values.billing_amount)}</dd></div>
              <div><dt>VAT treatment</dt><dd>{rowValue(selectedRecord, 'vat_treatment', 'Unclassified').replaceAll('_', ' ')}</dd></div>
              <div><dt>Input VAT</dt><dd>{money(selectedRecord.values.input_vat_amount ?? selectedRecord.values.vat_amount)}</dd></div>
              <div><dt>Royalty rate and base</dt><dd>{rowValue(selectedRecord, 'royalty_rate')}% · {basisLabel(selectedRecord.values.royalty_basis)}</dd></div>
              <div><dt>Royalty amount</dt><dd>{money(selectedRecord.values.royalty_amount)}</dd></div>
              <div><dt>Withholding rate and base</dt><dd>{rowValue(selectedRecord, 'withholding_rate')}% · {basisLabel(selectedRecord.values.withholding_basis)}</dd></div>
              <div><dt>Withholding amount</dt><dd>{money(selectedRecord.values.withholding_amount)}</dd></div>
              <div><dt>Other deductions</dt><dd>{money(decimal(selectedRecord.values.additional_withholding_amount)?.plus(decimal(selectedRecord.values.ewt_amount) ?? 0)
                .plus(decimal(selectedRecord.values.retention_amount) ?? 0))}</dd></div>
              <div><dt>Initial net</dt><dd>{money(selectedRecord.values.initial_net_amount)}</dd></div>
              <div><dt>Actual returned</dt><dd>{money(selectedRecord.values.returned_amount ?? '0')}</dd></div>
              <div><dt>Outstanding return</dt><dd>{money(selectedRecord.values.outstanding_return)}</dd></div>
              <div><dt>Final net</dt><dd>{money(selectedRecord.values.final_net_amount)}</dd></div>
              <div><dt>Status</dt><dd>{royaltyStatus(selectedRecord) === 'completed' ? 'Completed' : 'Pending'}</dd></div>
            </dl>
            <h4><Undo2 aria-hidden="true" size={17} /> Return history</h4>
            {selectedReturns.length ? (
              <div className="table-scroll"><table><thead><tr><th>Date</th><th className="is-numeric">Actual returned</th><th>Reference</th><th>Remarks</th></tr></thead>
                <tbody>{selectedReturns.map((item) => <tr key={text(item.id)}>
                  <td>{text(item.return_date)}</td><td className="is-numeric">{money(item.amount)}</td>
                  <td>{text(item.reference, '—')}</td><td>{text(item.remarks, '—')}</td>
                </tr>)}</tbody></table></div>
            ) : <p className="royalties-muted">No withholding returns recorded.</p>}
            <h4><FileClock aria-hidden="true" size={17} /> Activity history</h4>
            {selectedEvents.length ? selectedEvents.map((item) => (
              <p className="royalties-event" key={text(item.id)}>
                <strong>{text(item.action)}</strong> · {text(item.actor_name, 'User')} · {text(item.created_at)}
                {text(item.summary) && <span> — {text(item.summary)}</span>}
              </p>
            )) : <p className="royalties-muted">No activity history available.</p>}
            <div className="module-form-actions">
              {page.canEdit && royaltyStatus(selectedRecord) === 'pending'
                && !(decimal(selectedRecord.values.returned_amount) ?? new Decimal(0)).gt(0) && (
                  <button className="button button-secondary" type="button" onClick={() => beginEdit(selectedRecord)}>
                    <Pencil aria-hidden="true" size={15} /> Edit pending record
                  </button>
                )}
              {page.canEdit && royaltyStatus(selectedRecord) === 'pending'
                && (decimal(selectedRecord.values.outstanding_return) ?? new Decimal(0)).gt(0) && (
                  <button className="button button-primary" type="button" onClick={() => beginReturn(selectedRecord)}>
                    <Undo2 aria-hidden="true" size={15} /> Record actual return
                  </button>
                )}
            </div>
          </section>
        </ModalDialog>
      )}

      {dialog === 'record' && (
        <ModalDialog labelledBy="royalty-form-title" onClose={closeDialog}>
          <section className="panel royalties-dialog" aria-labelledby="royalty-form-title">
            <div className="panel-heading">
              <div><p className="eyebrow">ROYALTY MANAGEMENT</p><h3 id="royalty-form-title">{editing ? 'Edit pending royalty' : 'New royalty record'}</h3></div>
              <button className="icon-button" type="button" aria-label="Close royalty form" onClick={closeDialog} disabled={saving}><X aria-hidden="true" size={18} /></button>
            </div>
            <form className="module-form royalties-form" onSubmit={(event) => void submitRecord(event)}>
              <label className="module-field">
                <span>Supplier profile</span>
                <select value={formValues.supplier_id} onChange={(event) => chooseSupplier(event.target.value)} disabled={saving}>
                  <option value="">Enter a client/payee manually</option>
                  {suppliers.map((supplier) => <option key={text(supplier.id)} value={text(supplier.id)}>
                    {text(supplier.business_name)} · {text(supplier.tax_classification, 'Unclassified')}
                  </option>)}
                </select>
              </label>
              <label className="module-field">
                <span>Client / payee name <strong aria-hidden="true">*</strong></span>
                <input required value={formValues.client_name} onChange={(event) => updateForm('client_name', event.target.value)} maxLength={200} disabled={saving} />
              </label>
              <label className="module-field">
                <span>Supplier tax classification snapshot</span>
                <input value={formValues.supplier_tax_classification.replaceAll('_', ' ')} readOnly />
              </label>
              <label className="module-field">
                <span>Contract (optional)</span>
                <select value={formValues.contract_id} onChange={(event) => chooseContract(event.target.value)} disabled={saving}>
                  <option value="">No linked contract</option>
                  {contracts.map((contract) => <option key={text(contract.id)} value={text(contract.id)}>
                    {text(contract.contract_name)} · {text(contract.client_name, 'No client')}
                  </option>)}
                </select>
              </label>
              <label className="module-field">
                <span>Project UUID (optional)</span>
                <input value={formValues.project_id} onChange={(event) => updateForm('project_id', event.target.value)} disabled={saving} />
              </label>
              <label className="module-field">
                <span>Contract amount (optional)</span>
                <input type="number" min="0" step="0.01" value={formValues.contract_amount} onChange={(event) => updateForm('contract_amount', event.target.value)} disabled={saving} />
              </label>
              <label className="module-field">
                <span>Billing date <strong aria-hidden="true">*</strong></span>
                <input required type="date" min="1500-01-01" value={formValues.billing_date} onChange={(event) => updateForm('billing_date', event.target.value)} disabled={saving} />
              </label>
              <label className="module-field">
                <span>Billing amount (PHP) <strong aria-hidden="true">*</strong></span>
                <input required type="number" min="0" step="0.01" value={formValues.billing_amount} onChange={(event) => updateForm('billing_amount', event.target.value)} disabled={saving} />
              </label>
              <label className="module-field">
                <span>Billing VAT treatment <strong aria-hidden="true">*</strong></span>
                <select required value={formValues.vat_treatment} onChange={(event) => updateForm('vat_treatment', event.target.value)} disabled={saving}>
                  <option value="">Choose treatment</option>
                  <option value="VAT_INCLUSIVE">VAT-inclusive billing</option>
                  <option value="VAT_EXCLUSIVE">VAT-exclusive billing</option>
                  <option value="NON_VAT">Non-VAT</option>
                  <option value="VAT_EXEMPT">VAT-exempt</option>
                  <option value="UNCLASSIFIED">Unclassified — confirm invoice treatment</option>
                </select>
              </label>
              {['VAT_INCLUSIVE', 'VAT_EXCLUSIVE'].includes(formValues.vat_treatment) && (
                <>
                  <label className="module-field">
                    <span>VAT rate (%)</span>
                    <input type="number" min="0" max="100" step="0.01" value={formValues.vat_rate} onChange={(event) => updateForm('vat_rate', event.target.value)} disabled={saving} />
                  </label>
                  {formValues.vat_treatment === 'VAT_EXCLUSIVE' && (
                    <label className="module-field">
                      <span>Input VAT amount (PHP)</span>
                      <input type="number" min="0" step="0.01" value={formValues.vat_amount} onChange={(event) => updateForm('vat_amount', event.target.value)} disabled={saving} />
                    </label>
                  )}
                </>
              )}
              <section className="royalties-rate-group" aria-label="Royalty calculation">
                <h4>Royalty calculation</h4>
                <label className="module-field"><span>Royalty rate (%)</span>
                  <input required type="number" min="0" max="100" step="0.01" value={formValues.royalty_rate} onChange={(event) => updateForm('royalty_rate', event.target.value)} disabled={saving} />
                </label>
                <BasisField label="Royalty basis" value={formValues.royalty_basis} onChange={(value) => updateForm('royalty_basis', value)} disabled={saving} />
                <label className="module-field"><span>Standard withholding rate (%)</span>
                  <input required type="number" min="0" max="100" step="0.01" value={formValues.withholding_rate} onChange={(event) => updateForm('withholding_rate', event.target.value)} disabled={saving} />
                </label>
                <BasisField label="Withholding basis" value={formValues.withholding_basis} onChange={(value) => updateForm('withholding_basis', value)} disabled={saving} />
              </section>
              <details className="royalties-advanced">
                <summary>Optional withholdings and calculation bases</summary>
                <p>Leave a rate blank when the adjustment does not apply. Each basis is stored with this record.</p>
                <label className="module-field"><span>Additional withholding (%)</span>
                  <input type="number" min="0" max="100" step="0.01" value={formValues.additional_withholding_rate} onChange={(event) => updateForm('additional_withholding_rate', event.target.value)} disabled={saving} />
                </label>
                <BasisField label="Additional withholding basis" value={formValues.additional_withholding_basis} onChange={(value) => updateForm('additional_withholding_basis', value)} disabled={saving} />
                <label className="module-field"><span>EWT (%)</span>
                  <input type="number" min="0" max="100" step="0.01" value={formValues.ewt_rate} onChange={(event) => updateForm('ewt_rate', event.target.value)} disabled={saving} />
                </label>
                <BasisField label="EWT basis" value={formValues.ewt_basis} onChange={(value) => updateForm('ewt_basis', value)} disabled={saving} />
                <label className="module-field"><span>Retention (%)</span>
                  <input type="number" min="0" max="100" step="0.01" value={formValues.retention_rate} onChange={(event) => updateForm('retention_rate', event.target.value)} disabled={saving} />
                </label>
                <BasisField label="Retention basis" value={formValues.retention_basis} onChange={(value) => updateForm('retention_basis', value)} disabled={saving} />
              </details>
              <label className="module-field royalties-wide"><span>Remarks</span>
                <textarea rows={3} maxLength={2000} value={formValues.remarks} onChange={(event) => updateForm('remarks', event.target.value)} disabled={saving} />
              </label>
              {preview ? (
                <div className="royalties-preview" aria-live="polite">
                  <h4><CircleDollarSign aria-hidden="true" size={17} /> Live calculation preview</h4>
                  <div><span>Gross billing</span><strong>{money(preview.gross)}</strong></div>
                  <div><span>Input VAT (tracked separately)</span><strong>{money(preview.vat)}</strong></div>
                  <div><span>VAT-exclusive basis</span><strong>{money(preview.vatExclusive)}</strong></div>
                  <div><span>Royalty</span><strong>− {money(preview.royalty)}</strong></div>
                  <div><span>Standard withholding</span><strong>− {money(preview.withholding)}</strong></div>
                  {(preview.additional.gt(0) || preview.ewt.gt(0) || preview.retention.gt(0)) && (
                    <div><span>Optional deductions</span><strong>− {money(preview.additional.plus(preview.ewt).plus(preview.retention))}</strong></div>
                  )}
                  <div className="royalties-preview-total"><span>Initial net amount</span><strong>{money(preview.initialNet)}</strong></div>
                  {!preview.valid && <p role="alert">Deductions exceed gross billing. Adjust rates before saving.</p>}
                </div>
              ) : <p className="royalties-muted">Enter the billing amount and required rates to preview the saved calculation.</p>}
              {error && <p className="module-form-error royalties-wide" role="alert">{error}</p>}
              <div className="module-form-actions royalties-wide">
                <button className="button button-secondary" type="button" onClick={closeDialog} disabled={saving}>Cancel</button>
                <button className="button button-primary" type="submit" disabled={saving || !preview?.valid}>
                  {saving ? 'Saving…' : editing ? 'Save changes' : 'Save royalty record'}
                </button>
              </div>
            </form>
          </section>
        </ModalDialog>
      )}

      {dialog === 'config' && (
        <ModalDialog labelledBy="royalty-config-title" onClose={closeDialog}>
          <section className="panel royalties-dialog" aria-labelledby="royalty-config-title">
            <div className="panel-heading">
              <div><p className="eyebrow">COMPANY SETTINGS</p><h3 id="royalty-config-title">Royalty defaults</h3></div>
              <button className="icon-button" type="button" aria-label="Close royalty settings" onClick={closeDialog} disabled={saving}><X aria-hidden="true" size={18} /></button>
            </div>
            <p className="royalties-muted">Defaults apply only to new records. Existing records retain the rates and bases saved at creation.</p>
            <form className="module-form royalties-form" onSubmit={(event) => void submitConfiguration(event)}>
              <RateInput label="Default VAT rate (%)" value={configForm.default_vat_rate} onChange={(value) => setConfigForm((current) => ({ ...current, default_vat_rate: value }))} disabled={saving} />
              <RateInput label="Default royalty rate (%)" value={configForm.default_royalty_rate} onChange={(value) => setConfigForm((current) => ({ ...current, default_royalty_rate: value }))} disabled={saving} />
              <BasisField label="Default royalty basis" value={configForm.default_royalty_basis} onChange={(value) => setConfigForm((current) => ({ ...current, default_royalty_basis: value }))} disabled={saving} />
              <RateInput label="Default standard withholding (%)" value={configForm.default_withholding_rate} onChange={(value) => setConfigForm((current) => ({ ...current, default_withholding_rate: value }))} disabled={saving} />
              <BasisField label="Default withholding basis" value={configForm.default_withholding_basis} onChange={(value) => setConfigForm((current) => ({ ...current, default_withholding_basis: value }))} disabled={saving} />
              <RateInput label="Default additional withholding (%) — optional" value={configForm.default_additional_withholding_rate} onChange={(value) => setConfigForm((current) => ({ ...current, default_additional_withholding_rate: value }))} disabled={saving} />
              <BasisField label="Default additional withholding basis" value={configForm.default_additional_withholding_basis} onChange={(value) => setConfigForm((current) => ({ ...current, default_additional_withholding_basis: value }))} disabled={saving} />
              <RateInput label="Default EWT (%) — optional" value={configForm.default_ewt_rate} onChange={(value) => setConfigForm((current) => ({ ...current, default_ewt_rate: value }))} disabled={saving} />
              <BasisField label="Default EWT basis" value={configForm.default_ewt_basis} onChange={(value) => setConfigForm((current) => ({ ...current, default_ewt_basis: value }))} disabled={saving} />
              <RateInput label="Default retention (%) — optional" value={configForm.default_retention_rate} onChange={(value) => setConfigForm((current) => ({ ...current, default_retention_rate: value }))} disabled={saving} />
              <BasisField label="Default retention basis" value={configForm.default_retention_basis} onChange={(value) => setConfigForm((current) => ({ ...current, default_retention_basis: value }))} disabled={saving} />
              <label className="module-field"><span>Significant deduction confirmation threshold (PHP)</span>
                <input type="number" min="0" step="0.01" value={configForm.deduction_confirmation_threshold} onChange={(event) => setConfigForm((current) => ({ ...current, deduction_confirmation_threshold: event.target.value }))} disabled={saving} />
              </label>
              {error && <p className="module-form-error royalties-wide" role="alert">{error}</p>}
              <div className="module-form-actions royalties-wide">
                <button className="button button-secondary" type="button" onClick={closeDialog} disabled={saving}>Cancel</button>
                <button className="button button-primary" type="submit" disabled={saving}>{saving ? 'Saving…' : 'Save company settings'}</button>
              </div>
            </form>
          </section>
        </ModalDialog>
      )}

      {dialog === 'return' && selectedRecord && (
        <ModalDialog labelledBy="royalty-return-title" onClose={closeDialog}>
          <section className="panel royalties-dialog" aria-labelledby="royalty-return-title">
            <div className="panel-heading">
              <div><p className="eyebrow">WITHHOLDING RETURN</p><h3 id="royalty-return-title">Record actual return</h3></div>
              <button className="icon-button" type="button" aria-label="Close return form" onClick={closeDialog} disabled={saving}><X aria-hidden="true" size={18} /></button>
            </div>
            <p className="royalties-muted">Outstanding balance: <strong>{money(selectedRecord.values.outstanding_return)}</strong>. A full return completes this record; partial returns remain pending.</p>
            <form className="module-form royalties-form" onSubmit={(event) => void submitReturn(event)}>
              <label className="module-field"><span>Actual amount returned (PHP)</span>
                <input required type="number" min="0.01" max={rowValue(selectedRecord, 'outstanding_return')} step="0.01" value={returnValues.amount}
                  onChange={(event) => setReturnValues((current) => ({ ...current, amount: event.target.value }))} disabled={saving} />
              </label>
              <label className="module-field"><span>Actual return date</span>
                <input required type="date" min="1500-01-01" value={returnValues.return_date}
                  onChange={(event) => setReturnValues((current) => ({ ...current, return_date: event.target.value }))} disabled={saving} />
              </label>
              <label className="module-field"><span>Reference</span>
                <input maxLength={120} value={returnValues.reference} onChange={(event) => setReturnValues((current) => ({ ...current, reference: event.target.value }))} disabled={saving} />
              </label>
              <label className="module-field"><span>Remarks</span>
                <textarea rows={3} maxLength={1000} value={returnValues.remarks} onChange={(event) => setReturnValues((current) => ({ ...current, remarks: event.target.value }))} disabled={saving} />
              </label>
              {error && <p className="module-form-error royalties-wide" role="alert">{error}</p>}
              <div className="module-form-actions royalties-wide">
                <button className="button button-secondary" type="button" onClick={closeDialog} disabled={saving}>Cancel</button>
                <button className="button button-primary" type="submit" disabled={saving}>{saving ? 'Recording…' : 'Confirm and record return'}</button>
              </div>
            </form>
          </section>
        </ModalDialog>
      )}
    </div>
  );
}

function requestKey(): string {
  return crypto.randomUUID();
}

function BasisField({ label, value, onChange, disabled }: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  disabled: boolean;
}) {
  return (
    <label className="module-field">
      <span>{label}</span>
      <select value={value || 'VAT_EXCLUSIVE'} onChange={(event) => onChange(event.target.value)} disabled={disabled}>
        {BASIS_OPTIONS.map((option) => <option value={option.value} key={option.value}>{option.label}</option>)}
      </select>
    </label>
  );
}

function RateInput({ label, value, onChange, disabled }: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  disabled: boolean;
}) {
  return (
    <label className="module-field">
      <span>{label}</span>
      <input type="number" min="0" max="100" step="0.01" value={value} onChange={(event) => onChange(event.target.value)} disabled={disabled} />
    </label>
  );
}
