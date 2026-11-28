import { useEffect, useMemo, useState, type FormEvent } from 'react';
import { Check, ChevronDown, CircleDollarSign, FileText, Plus, Printer, X } from 'lucide-react';
import type { ModulePageData } from '../api/modules';
import { ModalDialog } from './ModalDialog';
import { MoneyInput } from './MoneyInput';

type BillingAction =
  | 'create-billing'
  | 'amend-billing'
  | 'save-billing-configuration'
  | 'decide-billing-adjustment'
  | 'decide-billing'
  | 'reverse-billing'
  | 'attach-billing-documents'
  | 'record-billing-payment'
  | 'refund-billing-payment'
  | 'record-withholding-remittance'
  | 'release-billing-retention';

type AdjustmentType =
  | 'mobilization_recovery'
  | 'retention'
  | 'previous_billing_adjustment'
  | 'materials_on_site'
  | 'liquidated_damages'
  | 'backcharge'
  | 'other_deduction'
  | 'other_addition'
  | 'discount'
  | 'other_withholding';

interface AdjustmentDraft {
  adjustment_type: AdjustmentType;
  calculation_type: 'fixed' | 'percentage' | 'manual';
  calculation_basis: 'current_period' | 'cumulative' | 'contract_amount' | 'custom' | 'not_applicable';
  basis_amount: string;
  rate: string;
  amount: string;
  description: string;
  supporting_document_path: string;
  previous_billing_id: string;
  quantity: string;
  unit: string;
  unit_price: string;
  days: string;
  daily_rate: string;
  category: string;
  adjustment_date: string;
  extra: string;
}

interface Props {
  page: ModulePageData;
  saving: boolean;
  onAction: (action: BillingAction, values: Record<string, unknown>, id?: string) => Promise<ModulePageData>;
}

interface BillingDocumentDraft {
  file: File;
  idempotencyKey: string;
}

const adjustmentTypes: Array<{ value: AdjustmentType; label: string; addition?: boolean }> = [
  { value: 'mobilization_recovery', label: 'Mobilization recovery' },
  { value: 'retention', label: 'Retention' },
  { value: 'previous_billing_adjustment', label: 'Previous billing adjustment' },
  { value: 'materials_on_site', label: 'Materials on site', addition: true },
  { value: 'liquidated_damages', label: 'Liquidated damages' },
  { value: 'backcharge', label: 'Backcharge' },
  { value: 'other_deduction', label: 'Other deduction' },
  { value: 'other_addition', label: 'Other addition / credit', addition: true },
  { value: 'discount', label: 'Discount' },
  { value: 'other_withholding', label: 'Other withholding' },
];

const emptyAdjustment = (): AdjustmentDraft => ({
  adjustment_type: 'mobilization_recovery',
  calculation_type: 'fixed',
  calculation_basis: 'current_period',
  basis_amount: '',
  rate: '',
  amount: '',
  description: '',
  supporting_document_path: '',
  previous_billing_id: '',
  quantity: '',
  unit: '',
  unit_price: '',
  days: '',
  daily_rate: '',
  category: '',
  adjustment_date: currentDate(),
  extra: '',
});

function text(value: unknown): string {
  if (value === null || value === undefined || value === '') return '';
  return String(value);
}

function amount(value: unknown): number {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : 0;
}

function money(value: unknown): string {
  return new Intl.NumberFormat('en-PH', {
    style: 'currency',
    currency: 'PHP',
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(amount(value));
}

function records(value: unknown): Array<Record<string, unknown>> {
  return Array.isArray(value)
    ? value.filter((item): item is Record<string, unknown> => Boolean(item) && typeof item === 'object')
    : [];
}

function currentDate(): string {
  return new Date().toISOString().slice(0, 10);
}

function requestKey(): string {
  return crypto.randomUUID();
}

function billingAmountCents(value: unknown): number {
  const normalized = String(value ?? '').replace(/,/g, '').trim();
  if (!normalized) return 0;
  const parsed = Number(normalized);
  return Number.isFinite(parsed) ? Math.round(parsed * 100) : 0;
}

function calculateBillingWithholding(amountCents: number, vatRate: unknown, incomeRate: unknown) {
  const normalizeRate = (value: unknown) => {
    const parsed = Number(value);
    return Number.isFinite(parsed) ? Math.max(0, Math.min(100, parsed)) : 0;
  };
  const vatExclusiveBaseCents = amountCents === 0 ? 0 : Math.round(amountCents * 100 / 112);
  const percentCents = (baseCents: number, rate: number) =>
    Math.round(baseCents * rate / 100);
  const finalVatCents = percentCents(vatExclusiveBaseCents, normalizeRate(vatRate));
  const incomeTaxCents = percentCents(vatExclusiveBaseCents, normalizeRate(incomeRate));
  const totalCents = finalVatCents + incomeTaxCents;
  return {
    vatExclusiveBaseCents,
    finalVatCents,
    incomeTaxCents,
    totalCents,
    netCents: Math.max(0, amountCents - totalCents),
  };
}

function centsMoney(cents: number): string {
  return money(cents / 100);
}

function fileAsBase64(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onerror = () => reject(new Error(`Could not read ${file.name}. Choose it again.`));
    reader.onload = () => {
      if (typeof reader.result !== 'string') {
        reject(new Error(`Could not read ${file.name}. Choose it again.`));
        return;
      }
      resolve(reader.result.slice(reader.result.indexOf(',') + 1));
    };
    reader.readAsDataURL(file);
  });
}

function adjustmentAmount(adjustment: AdjustmentDraft): number {
  if (adjustment.adjustment_type === 'materials_on_site') {
    return Math.round(amount(adjustment.quantity) * amount(adjustment.unit_price) * 100) / 100;
  }
  if (adjustment.adjustment_type === 'liquidated_damages'
      && amount(adjustment.days) > 0 && amount(adjustment.daily_rate) > 0) {
    return Math.round(amount(adjustment.days) * amount(adjustment.daily_rate) * 100) / 100;
  }
  if (adjustment.calculation_type === 'percentage') {
    return Math.round(amount(adjustment.basis_amount) * amount(adjustment.rate)) / 100;
  }
  return amount(adjustment.amount);
}

export function ConstructionBillingWorkspace({ page, saving, onAction }: Props) {
  const projectOptions = page.fields.find((field) => field.key === 'project_id')?.options ?? [];
  const contractOptions = page.fields.find((field) => field.key === 'contract_id')?.options ?? [];
  const [projectId, setProjectId] = useState('');
  const [contractId, setContractId] = useState('');
  const [selectedBillingId, setSelectedBillingId] = useState('');
  const [billingSearch, setBillingSearch] = useState('');
  const [billingStatusFilter, setBillingStatusFilter] = useState('');
  const [paymentStatusFilter, setPaymentStatusFilter] = useState('');
  const [billingDateFrom, setBillingDateFrom] = useState('');
  const [billingDateTo, setBillingDateTo] = useState('');
  const [billingSort, setBillingSort] = useState('date-desc');
  const [showCreate, setShowCreate] = useState(false);
  const [showConfiguration, setShowConfiguration] = useState(false);
  const [createIdempotencyKey, setCreateIdempotencyKey] = useState(requestKey);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [billingDate, setBillingDate] = useState(currentDate);
  const [periodStart, setPeriodStart] = useState('');
  const [periodEnd, setPeriodEnd] = useState('');
  const [dueDate, setDueDate] = useState('');
  const [billingType, setBillingType] = useState('Progress');
  const [billingMode, setBillingMode] = useState('period');
  const [description, setDescription] = useState('');
  const [gross, setGross] = useState('');
  const [withholdingVatRate, setWithholdingVatRate] = useState('5');
  const [withholdingIncomeRate, setWithholdingIncomeRate] = useState('2');
  const [adjustments, setAdjustments] = useState<AdjustmentDraft[]>([]);
  const [advanceAmount, setAdvanceAmount] = useState('');
  const [retentionCap, setRetentionCap] = useState('');
  const [retentionCapType, setRetentionCapType] = useState('fixed');
  const [retentionCapBasis, setRetentionCapBasis] = useState('contract_amount');
  const [retentionCapRate, setRetentionCapRate] = useState('');
  const [retentionCapBasisAmount, setRetentionCapBasisAmount] = useState('');
  const [enabledAdjustments, setEnabledAdjustments] = useState<Set<AdjustmentType>>(new Set());
  const [paymentAmount, setPaymentAmount] = useState('');
  const [paymentDate, setPaymentDate] = useState(currentDate);
  const [paymentMethod, setPaymentMethod] = useState('');
  const [paymentReference, setPaymentReference] = useState('');
  const [paymentDocument, setPaymentDocument] = useState('');
  const [releaseAmount, setReleaseAmount] = useState('');
  const [releaseDate, setReleaseDate] = useState(currentDate);
  const [releaseReference, setReleaseReference] = useState('');
  const [releaseDocument, setReleaseDocument] = useState('');
  const [reversalReason, setReversalReason] = useState('');
  const [showReverseBilling, setShowReverseBilling] = useState(false);
  const [editingBillingId, setEditingBillingId] = useState('');
  const [showRefund, setShowRefund] = useState(false);
  const [refundAmount, setRefundAmount] = useState('');
  const [refundDate, setRefundDate] = useState(currentDate);
  const [refundReference, setRefundReference] = useState('');
  const [refundReason, setRefundReason] = useState('');
  const [remittanceAmount, setRemittanceAmount] = useState('');
  const [remittanceDate, setRemittanceDate] = useState(currentDate);
  const [remittanceReference, setRemittanceReference] = useState('');
  const [remittanceProof, setRemittanceProof] = useState('');
  const [billingDocuments, setBillingDocuments] = useState<BillingDocumentDraft[]>([]);

  const projectRows = useMemo(() => page.rows.filter((row) =>
    !projectId || text(row.values.project_id) === projectId), [page.rows, projectId]);
  const visibleBillingRows = useMemo(() => projectRows
    .filter((row) => {
      const search = billingSearch.trim().toLocaleLowerCase();
      const matchesSearch = !search || [
        row.values.billing_number, row.values.description, row.values.billing_type,
      ].some((value) => text(value).toLocaleLowerCase().includes(search));
      const date = text(row.values.billing_date);
      return matchesSearch
        && (!billingStatusFilter || text(row.values.status) === billingStatusFilter)
        && (!paymentStatusFilter || text(row.values.payment_status) === paymentStatusFilter)
        && (!billingDateFrom || date >= billingDateFrom)
        && (!billingDateTo || date <= billingDateTo);
    })
    .sort((left, right) => {
      switch (billingSort) {
        case 'date-asc': return text(left.values.billing_date).localeCompare(text(right.values.billing_date));
        case 'number-asc': return text(left.values.billing_number).localeCompare(text(right.values.billing_number));
        case 'number-desc': return text(right.values.billing_number).localeCompare(text(left.values.billing_number));
        case 'net-desc': return amount(right.values.net_amount) - amount(left.values.net_amount);
        case 'net-asc': return amount(left.values.net_amount) - amount(right.values.net_amount);
        default: return text(right.values.billing_date).localeCompare(text(left.values.billing_date));
      }
    }), [projectRows, billingSearch, billingStatusFilter, paymentStatusFilter,
    billingDateFrom, billingDateTo, billingSort]);
  const selectedBilling = page.rows.find((row) => row.id === selectedBillingId) ?? null;
  const selectedAdjustments = records(selectedBilling?.values.adjustments);
  const selectedPayments = records(selectedBilling?.values.payments);
  const selectedWithholdingRemittances = records(selectedBilling?.values.withholding_remittances);
  const selectedAttachments = records(selectedBilling?.values.attachments);
  const selectedEvents = records(selectedBilling?.values.activity);
  const priorApprovedBillings = projectRows
    .filter((row) => ['Approved', 'Certified', 'Paid', 'Overdue'].includes(text(row.values.status)))
    .filter((row) => !contractId || text(row.values.contract_id) === contractId)
    .reduce((total, row) => total + amount(row.values.gross_amount), 0);
  const configRows = page.billingConfigurations ?? [];
  const activeConfiguration = configRows.find((row) =>
    text(row.project_id) === projectId && text(row.contract_id) === contractId)
    ?? configRows.find((row) => text(row.project_id) === projectId && !text(row.contract_id));
  const settings = activeConfiguration?.settings && typeof activeConfiguration.settings === 'object'
    ? activeConfiguration.settings as Record<string, unknown> : {};

  useEffect(() => {
    setAdvanceAmount(text(settings.mobilization_advance_amount));
    setRetentionCap(text(settings.retention_cap_amount));
    setRetentionCapType(text(settings.retention_cap_type) || 'fixed');
    setRetentionCapBasis(text(settings.retention_cap_basis) || 'contract_amount');
    setRetentionCapRate(text(settings.retention_cap_rate));
    setRetentionCapBasisAmount(text(settings.retention_cap_basis_amount));
    const enabled = settings.enabled_adjustments && typeof settings.enabled_adjustments === 'object'
      ? settings.enabled_adjustments as Record<string, unknown> : {};
    setEnabledAdjustments(new Set(adjustmentTypes
      .filter((item) => enabled[item.value] === true)
      .map((item) => item.value)));
  }, [activeConfiguration, projectId, contractId]);

  const projectTotals = useMemo(() => {
    const relevant = projectRows.filter((row) =>
      ['Approved', 'Certified', 'Paid', 'Overdue'].includes(text(row.values.status)));
    const legacyRetention = (page.billingLegacyRetention ?? []).filter((row) =>
      !projectId || text(row.project_id) === projectId);
    const legacyMobilization = (page.billingMobilizationBalances ?? []).filter((row) =>
      !projectId || text(row.project_id) === projectId);
    let grossTotal = 0;
    let netTotal = 0;
    let totalWithholding = 0;
    let netBillingTotal = 0;
    let finalPayableTotal = 0;
    let withholdingRemitted = 0;
    let collected = 0;
    let mobilizationRecovered = 0;
    let retentionWithheld = 0;
    let liquidatedDamages = 0;
    let backcharges = 0;
    let otherDeductions = 0;
    let additions = 0;
    const latestContractBilling = new Map<string, ModulePageData['rows'][number]>();
    relevant.forEach((row) => {
      grossTotal += amount(row.values.gross_amount);
      netTotal += amount(row.values.net_amount);
      totalWithholding += amount(row.values.total_withholding);
      netBillingTotal += amount(row.values.net_billing);
      finalPayableTotal += amount(row.values.final_payable);
      withholdingRemitted += amount(row.values.withholding_remitted);
      collected += amount(row.values.collected_amount);
      const contractKey = text(row.values.contract_id) || 'project-contract';
      const prior = latestContractBilling.get(contractKey);
      if (!prior || text(row.values.billing_date) >= text(prior.values.billing_date)) {
        latestContractBilling.set(contractKey, row);
      }
      records(row.values.adjustments).forEach((adjustment) => {
        if (text(adjustment.approval_status) !== 'Approved') return;
        const adjustmentValue = amount(adjustment.amount);
        switch (text(adjustment.adjustment_type)) {
          case 'mobilization_recovery': mobilizationRecovered += adjustmentValue; break;
          case 'retention': retentionWithheld += adjustmentValue; break;
          case 'liquidated_damages': liquidatedDamages += adjustmentValue; break;
          case 'backcharge': backcharges += adjustmentValue; break;
          case 'materials_on_site':
          case 'other_addition': additions += adjustmentValue; break;
          case 'other_deduction':
          case 'other_withholding':
          case 'discount':
          case 'previous_billing_adjustment': otherDeductions += adjustmentValue; break;
          default: break;
        }
      });
    });
    const releases = (page.billingRetentionReleases ?? [])
      .filter((release) => !projectId || text(release.project_id) === projectId)
      .reduce((total, release) => total + amount(release.amount), 0)
      + legacyRetention.reduce((total, row) => total + amount(row.released_amount), 0);
    const legacyRetentionWithheld = legacyRetention
      .reduce((total, row) => total + amount(row.held_amount), 0);
    const legacyAdvanceAmount = legacyMobilization
      .reduce((total, row) => total + amount(row.advance_amount), 0);
    const legacyAdvanceRecovered = legacyMobilization
      .reduce((total, row) => total + amount(row.recovered_amount), 0);
    const remainingContractBalance = [...latestContractBilling.values()]
      .reduce((total, row) => total + amount(row.values.remaining_contract_balance), 0);
    return {
      grossTotal, netTotal,
      totalWithholding, netBillingTotal, finalPayableTotal, withholdingRemitted,
      billingCollections: collected,
      totalCollections: collected + releases,
      mobilizationRecovered: mobilizationRecovered + legacyAdvanceRecovered,
      retentionWithheld: retentionWithheld + legacyRetentionWithheld,
      releases,
      mobilizationRemaining: Math.max(0,
        (amount(settings.mobilization_advance_amount) || legacyAdvanceAmount)
          - mobilizationRecovered - legacyAdvanceRecovered),
      retentionOutstanding: Math.max(0, retentionWithheld - releases),
      liquidatedDamages, backcharges, otherDeductions, additions, remainingContractBalance,
    };
  }, [projectRows, page.billingRetentionReleases, page.billingLegacyRetention,
    page.billingMobilizationBalances, projectId, contractId, settings]);

  const currentPeriodGross = billingMode === 'cumulative'
    ? Math.max(0, amount(gross) - priorApprovedBillings)
    : amount(gross);
  const billingCalculation = calculateBillingWithholding(
    billingAmountCents(currentPeriodGross),
    withholdingVatRate,
    withholdingIncomeRate,
  );
  const grossNet = billingCalculation.netCents / 100;
  const pendingAdjustmentTotal = adjustments.reduce((total, item) => {
    const direction = adjustmentTypes.find((type) => type.value === item.adjustment_type)?.addition ? 1 : -1;
    return total + direction * adjustmentAmount(item);
  }, 0);
  const previewNet = Math.max(0, grossNet + pendingAdjustmentTotal);

  function addBillingDocuments(files: FileList | null) {
    if (!files?.length) return;
    const incoming = Array.from(files);
    if (incoming.some((file) => !/\.(png|jpe?g|pdf)$/i.test(file.name))) {
      setError('Billing documents must be PNG, JPG, or PDF files.');
      return;
    }
    const next = [...billingDocuments, ...incoming.map((file) => ({
      file,
      idempotencyKey: requestKey(),
    }))];
    if (next.reduce((total, item) => total + item.file.size, 0) > 10 * 1024 * 1024) {
      setError('Billing documents must total 10 MB or less.');
      return;
    }
    setError(null);
    setBillingDocuments(next);
  }

  async function serializedBillingDocuments() {
    return Promise.all(billingDocuments.map(async ({ file, idempotencyKey }) => ({
      name: file.name,
      data: await fileAsBase64(file),
      idempotency_key: idempotencyKey,
    })));
  }

  async function runAction(action: BillingAction, values: Record<string, unknown>, id?: string) {
    setError(null);
    setNotice(null);
    try {
      await onAction(action, values, id);
      setNotice('Billing transaction saved.');
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'BRED could not complete the billing transaction.');
      throw reason;
    }
  }

  async function submitConfiguration(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!projectId) {
      setError('Choose a project before configuring billing.');
      return;
    }
    try {
      await runAction('save-billing-configuration', {
        project_id: projectId,
        contract_id: contractId,
        settings: {
          mobilization_advance_amount: amount(advanceAmount),
          retention_cap_amount: amount(retentionCap),
          retention_cap_type: retentionCapType,
          retention_cap_basis: retentionCapBasis,
          retention_cap_rate: amount(retentionCapRate),
          retention_cap_basis_amount: amount(retentionCapBasisAmount),
          enabled_adjustments: Object.fromEntries(adjustmentTypes.map((item) =>
            [item.value, enabledAdjustments.has(item.value)])),
        },
      });
      setShowConfiguration(false);
    } catch { /* The global service error is shown above this form. */ }
  }

  function startBillingEdit() {
    if (!selectedBilling) return;
    setEditingBillingId(selectedBilling.id);
    setProjectId(text(selectedBilling.values.project_id));
    setContractId(text(selectedBilling.values.contract_id));
    setBillingDate(text(selectedBilling.values.billing_date) || currentDate());
    setPeriodStart(text(selectedBilling.values.period_start));
    setPeriodEnd(text(selectedBilling.values.period_end));
    setDueDate(text(selectedBilling.values.due_date));
    setBillingType(text(selectedBilling.values.billing_type) || 'Progress');
    setBillingMode(text(selectedBilling.values.billing_mode) || 'period');
    setDescription(text(selectedBilling.values.description));
    setGross(text(selectedBilling.values.billing_mode) === 'cumulative'
      ? text(selectedBilling.values.cumulative_gross_amount)
      : text(selectedBilling.values.gross_amount));
    setWithholdingVatRate(text(selectedBilling.values.withholding_vat_rate) || '5');
    setWithholdingIncomeRate(text(selectedBilling.values.withholding_income_tax_rate) || '2');
    setAdjustments(selectedAdjustments.map((adjustment) => {
      const details = adjustment.details && typeof adjustment.details === 'object'
        ? adjustment.details as Record<string, unknown> : {};
      return {
        ...emptyAdjustment(),
        adjustment_type: text(adjustment.adjustment_type) as AdjustmentType,
        calculation_type: text(adjustment.calculation_type) as AdjustmentDraft['calculation_type'],
        calculation_basis: text(adjustment.calculation_basis) as AdjustmentDraft['calculation_basis'],
        basis_amount: text(adjustment.basis_amount),
        rate: text(adjustment.rate),
        amount: text(adjustment.amount),
        description: text(details.description),
        supporting_document_path: text(details.supporting_document_path),
        previous_billing_id: text(details.previous_billing_id),
        quantity: text(details.quantity),
        unit: text(details.unit),
        unit_price: text(details.unit_price),
        days: text(details.days),
        daily_rate: text(details.daily_rate),
        category: text(details.category),
        adjustment_date: text(details.date) || currentDate(),
        extra: '',
      };
    }));
    setBillingDocuments([]);
    setShowCreate(true);
  }

  async function submitBilling(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!projectId || currentPeriodGross <= 0) {
      setError('Choose a project and enter a gross billing amount greater than zero.');
      return;
    }
    if (adjustments.some((item) => item.adjustment_type === 'previous_billing_adjustment'
      && !item.previous_billing_id)) {
      setError('Choose the previous billing record for each previous-billing adjustment.');
      return;
    }
    if (adjustments.some((item) => item.adjustment_type === 'materials_on_site'
      && !item.supporting_document_path.trim() && billingDocuments.length === 0)) {
      setError('Attach or identify supporting documentation before billing materials on site.');
      return;
    }
    const deductions = adjustments.reduce((total, item) => total
      + (adjustmentTypes.find((type) => type.value === item.adjustment_type)?.addition ? 0 : adjustmentAmount(item)), 0);
    if (deductions > 0 && !window.confirm(
      `${money(deductions)} in proposed construction deductions will remain excluded until each adjustment is approved. Continue?`,
    )) return;
    try {
      const payloadAdjustments = adjustments.map((item) => {
        const { extra, ...fields } = item;
        let details: Record<string, unknown> = {
          description: item.description,
          supporting_document_path: item.supporting_document_path,
        };
        if (item.previous_billing_id) details.previous_billing_id = item.previous_billing_id;
        if (item.adjustment_type === 'materials_on_site') {
          details = {
            ...details,
            quantity: amount(item.quantity),
            unit: item.unit,
            unit_price: amount(item.unit_price),
            total_material_value: adjustmentAmount(item),
          };
        }
        if (item.adjustment_type === 'liquidated_damages') {
          details = {
            ...details,
            days: amount(item.days),
            daily_rate: amount(item.daily_rate),
            total_liquidated_damages: adjustmentAmount(item),
          };
        }
        if (item.adjustment_type === 'backcharge') {
          details = { ...details, category: item.category, date: item.adjustment_date };
        }
        if (extra.trim()) {
          let parsed: unknown;
          try {
            parsed = JSON.parse(extra) as unknown;
          } catch {
            throw new Error('Additional adjustment details must be a valid JSON object.');
          }
          if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
            throw new Error('Additional adjustment details must be a JSON object.');
          }
          details = { ...(parsed as Record<string, unknown>), ...details };
        }
        return { ...fields, amount: adjustmentAmount(item), details, idempotency_key: requestKey() };
      });
      const billingPayload = {
        project_id: projectId,
        contract_id: contractId,
        billing_date: billingDate,
        period_start: periodStart,
        period_end: periodEnd,
        due_date: dueDate,
        billing_type: billingType,
        billing_mode: billingMode,
        description,
        gross_amount: amount(gross),
        withholding_vat_rate: amount(withholdingVatRate),
        withholding_income_tax_rate: amount(withholdingIncomeRate),
        adjustments: payloadAdjustments,
        attachments: await serializedBillingDocuments(),
        idempotency_key: createIdempotencyKey,
      };
      await runAction(editingBillingId ? 'amend-billing' : 'create-billing',
        editingBillingId ? { ...billingPayload, amendment_reason: 'Authorized billing record amendment.' }
          : billingPayload,
        editingBillingId || undefined);
      setShowCreate(false);
      setSelectedBillingId('');
      setEditingBillingId('');
      setAdjustments([]);
      setGross('');
      setBillingDocuments([]);
      setCreateIdempotencyKey(requestKey());
      setNotice('Billing created as an independent record. Proposed adjustments are pending approval.');
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'BRED could not create the billing record.');
    }
  }

  async function decideAdjustment(adjustmentId: string, approve: boolean) {
    try {
      await runAction('decide-billing-adjustment', {
        adjustment_id: adjustmentId,
        approve,
        note: approve ? 'Approved in Construction billing.' : 'Rejected in Construction billing.',
      });
    } catch { /* The global service error is shown above this form. */ }
  }

  async function decideBilling(approve: boolean) {
    if (!selectedBilling) return;
    try {
      await runAction('decide-billing', {
        table: 'project_billings',
        approve,
        note: approve ? 'Approved in Construction billing.' : 'Voided before payment.',
      }, selectedBilling.id);
    } catch { /* The global service error is shown above this form. */ }
  }

  async function submitBillingReversal(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!selectedBilling || !reversalReason.trim()) {
      setError('Enter a reason for reversing this billing.');
      return;
    }
    try {
      await runAction('reverse-billing', { reason: reversalReason.trim() }, selectedBilling.id);
      setShowReverseBilling(false);
      setReversalReason('');
    } catch { /* The global service error is shown above this form. */ }
  }

  async function attachBillingDocuments() {
    if (!selectedBilling || billingDocuments.length === 0) {
      setError('Choose at least one billing document.');
      return;
    }
    try {
      await runAction('attach-billing-documents', {
        attachments: await serializedBillingDocuments(),
      }, selectedBilling.id);
      setBillingDocuments([]);
    } catch { /* The global service error is shown above this form. */ }
  }

  async function submitPayment(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!selectedBilling || amount(paymentAmount) <= 0 || !paymentMethod.trim()) {
      setError('Enter a positive payment amount and select a payment method.');
      return;
    }
    try {
      await runAction('record-billing-payment', {
        amount: amount(paymentAmount),
        payment_date: paymentDate,
        payment_method: paymentMethod,
        reference_number: paymentReference,
        supporting_document_path: paymentDocument,
        idempotency_key: requestKey(),
      }, selectedBilling.id);
      setPaymentAmount('');
    } catch { /* The global service error is shown above this form. */ }
  }

  async function submitRefund(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!selectedBilling || amount(refundAmount) <= 0 || !refundReason.trim()) {
      setError('Enter a positive refund amount and a reason.');
      return;
    }
    if (amount(refundAmount) > amount(selectedBilling.values.collected_amount)) {
      setError('The refund cannot exceed the amount collected.');
      return;
    }
    try {
      await runAction('refund-billing-payment', {
        amount: amount(refundAmount),
        refund_date: refundDate,
        reference_number: refundReference,
        reason: refundReason.trim(),
        idempotency_key: requestKey(),
      }, selectedBilling.id);
      setShowRefund(false);
      setRefundAmount('');
      setRefundReference('');
      setRefundReason('');
    } catch { /* The global service error is shown above this form. */ }
  }

  async function submitWithholdingRemittance(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!selectedBilling || amount(remittanceAmount) <= 0) {
      setError('Enter a positive withholding remittance amount.');
      return;
    }
    try {
      await runAction('record-withholding-remittance', {
        amount: amount(remittanceAmount),
        remittance_date: remittanceDate,
        payment_reference: remittanceReference,
        proof_document_path: remittanceProof,
        idempotency_key: requestKey(),
      }, selectedBilling.id);
      setRemittanceAmount('');
      setRemittanceReference('');
      setRemittanceProof('');
    } catch { /* The global service error is shown above this form. */ }
  }

  async function submitRetentionRelease(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!projectId || amount(releaseAmount) <= 0) {
      setError('Choose a project and enter a positive retention release amount.');
      return;
    }
    try {
      await runAction('release-billing-retention', {
        project_id: projectId,
        contract_id: contractId,
        amount: amount(releaseAmount),
        release_date: releaseDate,
        reference_number: releaseReference,
        document_path: releaseDocument,
        idempotency_key: requestKey(),
      });
      setReleaseAmount('');
    } catch { /* The global service error is shown above this form. */ }
  }

  return (
    <div className="construction-billing">
      {error && !showCreate && <div className="notice notice-error" role="alert">{error}</div>}
      {notice && <p className="construction-notice-success" role="status">{notice}</p>}

      <section className="panel construction-billing-overview" aria-label="Project billing overview">
        <div className="panel-heading">
          <div>
            <p className="eyebrow">PROJECT FINANCIAL OVERVIEW</p>
            <h3>Billing balances</h3>
          </div>
          <label className="module-field">
            <span>Project</span>
            <select value={projectId} onChange={(event) => {
              setProjectId(event.target.value);
              setContractId('');
              setSelectedBillingId('');
              setAdjustments([]);
              setBillingDocuments([]);
            }}>
              <option value="">All projects</option>
              {projectOptions.map((option) => <option value={option.value} key={option.value}>{option.label}</option>)}
            </select>
          </label>
        </div>
        <div className="construction-billing-balance-grid">
          <div><span>Total gross billings</span><strong>{money(projectTotals.grossTotal)}</strong></div>
          <div><span>Total withholding</span><strong>{money(projectTotals.totalWithholding)}</strong></div>
          <div><span>Withholding remitted</span><strong>{money(projectTotals.withholdingRemitted)}</strong></div>
          <div><span>Total net billing</span><strong>{money(projectTotals.netBillingTotal)}</strong></div>
          <div><span>Total final payables</span><strong>{money(projectTotals.finalPayableTotal)}</strong></div>
          <div><span>Total net payable</span><strong>{money(projectTotals.netTotal)}</strong></div>
          <div><span>Total collected (including retention releases)</span><strong>{money(projectTotals.totalCollections)}</strong></div>
          <div><span>Outstanding receivables</span><strong>{money(Math.max(0,
            projectTotals.netTotal - projectTotals.billingCollections + projectTotals.retentionOutstanding))}</strong></div>
          <div><span>Mobilization recovered</span><strong>{money(projectTotals.mobilizationRecovered)}</strong></div>
          <div><span>Mobilization balance</span><strong>{money(projectTotals.mobilizationRemaining)}</strong></div>
          <div><span>Retention withheld</span><strong>{money(projectTotals.retentionWithheld)}</strong></div>
          <div><span>Retention released</span><strong>{money(projectTotals.releases)}</strong></div>
          <div><span>Retention outstanding</span><strong>{money(projectTotals.retentionOutstanding)}</strong></div>
          <div><span>Liquidated damages</span><strong>{money(projectTotals.liquidatedDamages)}</strong></div>
          <div><span>Backcharges</span><strong>{money(projectTotals.backcharges)}</strong></div>
          <div><span>Other deductions</span><strong>{money(projectTotals.otherDeductions)}</strong></div>
          <div><span>Approved additions</span><strong>{money(projectTotals.additions)}</strong></div>
          <div><span>Remaining contract balance</span><strong>{money(projectTotals.remainingContractBalance)}</strong></div>
        </div>
        <div className="construction-billing-actions">
          <button className="button button-primary" type="button" onClick={() => {
            setBillingDocuments([]);
            setShowCreate(true);
          }} disabled={!page.canCreate || saving || !projectId}>
            <Plus aria-hidden="true" size={16} /> New billing
          </button>
          <button className="button button-secondary" type="button" onClick={() => setShowConfiguration((open) => !open)} disabled={!page.canEdit || saving || !projectId}>
            <ChevronDown aria-hidden="true" size={16} /> Billing configuration
          </button>
        </div>
        {showConfiguration && (
          <form className="module-form construction-billing-config" onSubmit={(event) => void submitConfiguration(event)}>
            <h4>Contract-specific limits and enabled adjustments</h4>
            <label className="module-field">
              <span>Contract</span>
              <select value={contractId} onChange={(event) => setContractId(event.target.value)}>
                <option value="">Project-level configuration</option>
                {contractOptions.filter((option) => !option.description || option.description === projectId).map((option) => (
                  <option value={option.value} key={option.value}>{option.label}</option>
                ))}
              </select>
            </label>
            <label className="module-field">
              <span>Original mobilization advance</span>
              <MoneyInput min={0} value={advanceAmount} onChange={setAdvanceAmount} />
            </label>
            <label className="module-field">
              <span>Retention cap method</span>
              <select value={retentionCapType} onChange={(event) => setRetentionCapType(event.target.value)}>
                <option value="fixed">Fixed amount</option>
                <option value="percentage">Percentage of configured basis</option>
              </select>
            </label>
            {retentionCapType === 'fixed' ? (
              <label className="module-field">
                <span>Maximum retention cap</span>
                <MoneyInput min={0} value={retentionCap} onChange={setRetentionCap} />
              </label>
            ) : (
              <>
                <label className="module-field">
                  <span>Cap basis</span>
                  <select value={retentionCapBasis} onChange={(event) => setRetentionCapBasis(event.target.value)}>
                    <option value="contract_amount">Contract amount including approved revisions</option>
                    <option value="custom">Custom basis amount</option>
                  </select>
                </label>
                <label className="module-field">
                  <span>Maximum retention rate (%)</span>
                  <input type="number" min="0" max="100" step="0.001" value={retentionCapRate}
                    onChange={(event) => setRetentionCapRate(event.target.value)} />
                </label>
                {retentionCapBasis === 'custom' && (
                  <label className="module-field">
                    <span>Custom cap basis amount</span>
                    <MoneyInput min={0.01} value={retentionCapBasisAmount} onChange={setRetentionCapBasisAmount} />
                  </label>
                )}
              </>
            )}
            <fieldset className="construction-billing-adjustment-options">
              <legend>Enabled adjustment types</legend>
              {adjustmentTypes.map((item) => (
                <label key={item.value}>
                  <input
                    type="checkbox"
                    checked={enabledAdjustments.has(item.value)}
                    onChange={(event) => setEnabledAdjustments((previous) => {
                      const next = new Set(previous);
                      if (event.target.checked) next.add(item.value);
                      else next.delete(item.value);
                      return next;
                    })}
                  />
                  {item.label}
                </label>
              ))}
            </fieldset>
            <button className="button button-primary" type="submit" disabled={saving}>Save configuration</button>
          </form>
        )}
      </section>

      <section className="panel construction-billing-history" aria-labelledby="billing-records-title">
        <div className="panel-heading">
          <div>
            <p className="eyebrow">INDIVIDUAL TRANSACTIONS</p>
            <h3 id="billing-records-title">Billing records</h3>
          </div>
          <span>{visibleBillingRows.length} of {projectRows.length} records</span>
        </div>
        <div className="module-form construction-billing-filters" role="search" aria-label="Filter billing records">
          <label className="module-field">
            <span>Search billings</span>
            <input type="search" value={billingSearch} onChange={(event) => setBillingSearch(event.target.value)}
              placeholder="Billing number or description" />
          </label>
          <label className="module-field">
            <span>Billing status</span>
            <select value={billingStatusFilter} onChange={(event) => setBillingStatusFilter(event.target.value)}>
              <option value="">All statuses</option>
              {['Draft', 'Submitted', 'Under Review', 'Approved', 'Certified', 'Paid', 'Overdue', 'Cancelled']
                .map((status) => <option key={status} value={status}>{status}</option>)}
            </select>
          </label>
          <label className="module-field">
            <span>Payment status</span>
            <select value={paymentStatusFilter} onChange={(event) => setPaymentStatusFilter(event.target.value)}>
              <option value="">All payment statuses</option>
              {['Unpaid', 'Partial', 'Paid'].map((status) => <option key={status} value={status}>{status}</option>)}
            </select>
          </label>
          <label className="module-field">
            <span>Billing date from</span>
            <input type="date" value={billingDateFrom} onChange={(event) => setBillingDateFrom(event.target.value)} />
          </label>
          <label className="module-field">
            <span>Billing date to</span>
            <input type="date" value={billingDateTo} onChange={(event) => setBillingDateTo(event.target.value)} />
          </label>
          <label className="module-field">
            <span>Sort by</span>
            <select value={billingSort} onChange={(event) => setBillingSort(event.target.value)}>
              <option value="date-desc">Date: newest first</option>
              <option value="date-asc">Date: oldest first</option>
              <option value="number-asc">Billing number: A–Z</option>
              <option value="number-desc">Billing number: Z–A</option>
              <option value="net-desc">Net payable: highest first</option>
              <option value="net-asc">Net payable: lowest first</option>
            </select>
          </label>
        </div>
        {projectRows.length ? (
          <div className="table-scroll">
            <table>
              <thead><tr>
                <th>Billing No.</th><th>Date</th><th>Type</th><th className="is-numeric">Gross</th>
                <th className="is-numeric">Deductions</th><th className="is-numeric">Net payable</th>
                <th className="is-numeric">Collected</th><th className="is-numeric">Balance</th><th>Status</th>
              </tr></thead>
              <tbody>
                {visibleBillingRows.map((row) => (
                  <tr key={row.id} className={selectedBillingId === row.id ? 'is-selected' : undefined}>
                    <td><button className="construction-billing-link" type="button" onClick={() => {
                      setBillingDocuments([]);
                      setSelectedBillingId(row.id);
                    }}>
                      {text(row.values.billing_number) || 'Billing record'}
                    </button></td>
                    <td>{text(row.values.billing_date)}</td>
                    <td>{text(row.values.billing_type) || 'Progress'}</td>
                    <td className="is-numeric">{money(row.values.gross_amount)}</td>
                    <td className="is-numeric">{money(amount(row.values.total_tax_deductions)
                      + amount(row.values.total_construction_deductions) + amount(row.values.total_other_deductions))}</td>
                    <td className="is-numeric">{money(row.values.net_amount)}</td>
                    <td className="is-numeric">{money(row.values.collected_amount)}</td>
                    <td className="is-numeric">{money(amount(row.values.net_amount) - amount(row.values.collected_amount))}</td>
                    <td>{text(row.values.status)} / {text(row.values.payment_status) || 'Unpaid'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : <p className="construction-billing-empty">No billing records for this project yet.</p>}
        {projectRows.length > 0 && visibleBillingRows.length === 0 && (
          <p className="construction-billing-empty">No billing records match these filters.</p>
        )}
      </section>

      {showCreate && (
        <ModalDialog
          labelledBy="new-billing-title"
          onClose={() => {
            if (saving) return;
            setShowCreate(false);
            setEditingBillingId('');
            setBillingDocuments([]);
          }}
        >
        <section className="panel construction-billing-form-panel" aria-labelledby="new-billing-title">
          <div className="panel-heading">
            <div><p className="eyebrow">{editingBillingId ? 'AUTHORIZED DRAFT AMENDMENT' : 'NEW INDEPENDENT RECORD'}</p>
              <h3 id="new-billing-title">{editingBillingId ? 'Edit draft billing' : 'Create billing'}</h3></div>
            <button className="icon-button" type="button" aria-label="Close billing form" disabled={saving} onClick={() => {
              setShowCreate(false);
              setEditingBillingId('');
              setBillingDocuments([]);
            }}>
              <X aria-hidden="true" size={18} />
            </button>
          </div>
          <form className="module-form construction-billing-form" onSubmit={(event) => void submitBilling(event)}>
            <label className="module-field"><span>Project *</span><select required value={projectId} onChange={(event) => {
              setProjectId(event.target.value); setContractId('');
              setAdjustments([]); setBillingDocuments([]);
            }}><option value="">Select a project</option>
              {projectOptions.map((option) => <option value={option.value} key={option.value}>{option.label}</option>)}
            </select></label>
            <label className="module-field"><span>Contract</span><select value={contractId} onChange={(event) => setContractId(event.target.value)}>
              <option value="">Project-level billing</option>              {contractOptions.filter((option) => !option.description || option.description === projectId).map((option) => (
                <option value={option.value} key={option.value}>{option.label}</option>
              ))}
            </select></label>
            <label className="module-field"><span>Billing type</span><select value={billingType} onChange={(event) => setBillingType(event.target.value)}>
              {['Progress', 'Mobilization', 'Final', 'Other'].map((value) => <option key={value}>{value}</option>)}
            </select></label>
            <label className="module-field"><span>Billing mode</span><select value={billingMode} onChange={(event) => setBillingMode(event.target.value)}>
              <option value="period">Period billing</option><option value="cumulative">Cumulative billing</option>
            </select></label>
            <p className="construction-billing-mode-note">
              {billingMode === 'cumulative'
                ? `Enter cumulative gross-to-date. Previously approved billings (${money(priorApprovedBillings)}) are subtracted once to calculate this period's billing.`
                : "Enter this period's gross billing. Cumulative totals are tracked separately and are not deducted."}
            </p>
            <label className="module-field"><span>Billing date *</span><input type="date" required value={billingDate} onChange={(event) => setBillingDate(event.target.value)} /></label>
            <label className="module-field"><span>Period start</span><input type="date" value={periodStart} onChange={(event) => setPeriodStart(event.target.value)} /></label>
            <label className="module-field"><span>Period end</span><input type="date" value={periodEnd} onChange={(event) => setPeriodEnd(event.target.value)} /></label>
            <label className="module-field"><span>Due date</span><input type="date" value={dueDate} onChange={(event) => setDueDate(event.target.value)} /></label>
            <label className="module-field module-field-wide"><span>Description</span><input value={description} onChange={(event) => setDescription(event.target.value)} /></label>
            <label className="module-field"><span>Amount (PHP) *</span><MoneyInput required min={0.01} value={gross} onChange={setGross} /></label>
            <label className="module-field"><span>% Withholding of Final VAT</span><input type="number" min="0" max="100" step="0.000001" value={withholdingVatRate} onChange={(event) => setWithholdingVatRate(event.target.value)} /></label>
            <label className="module-field"><span>% Withholding of Income Tax</span><input type="number" min="0" max="100" step="0.000001" value={withholdingIncomeRate} onChange={(event) => setWithholdingIncomeRate(event.target.value)} /></label>
            <section className="construction-billing-document-picker" aria-label="Billing documents">
              <label className="module-field">
                <span>Supporting documents (PNG, JPG, PDF; 10 MB total)</span>
                <input aria-label="Add billing documents" type="file" accept=".png,.jpg,.jpeg,.pdf" multiple
                  onChange={(event) => { addBillingDocuments(event.target.files); event.currentTarget.value = ''; }} />
              </label>
              {billingDocuments.map((item, index) => (
                <div className="construction-billing-document-draft" key={item.idempotencyKey}>
                  <span>{item.file.name} · {(item.file.size / 1024).toFixed(0)} KB</span>
                  <button className="text-button text-button-danger" type="button"
                    aria-label={`Remove billing document ${index + 1}`}
                    onClick={() => setBillingDocuments((previous) => previous.filter((file) => file.idempotencyKey !== item.idempotencyKey))}>
                    Remove
                  </button>
                </div>
              ))}
            </section>

            <details className="construction-billing-advanced">
              <summary><ChevronDown aria-hidden="true" size={16} /> Advanced Billing Adjustments</summary>
              <p>Adjustment amounts remain pending and are excluded from payable totals until individually approved.</p>
              {adjustments.map((item, index) => (
                <fieldset className="construction-billing-adjustment" key={`${index}-${item.adjustment_type}`}>
                  <legend>{adjustmentTypes.find((type) => type.value === item.adjustment_type)?.label ?? 'Adjustment'}</legend>
                  <button className="icon-button" type="button" aria-label={`Remove adjustment ${index + 1}`}
                    onClick={() => setAdjustments((previous) => previous.filter((_, itemIndex) => itemIndex !== index))}>
                    <X aria-hidden="true" size={16} />
                  </button>
                  <label className="module-field"><span>Adjustment type</span><select value={item.adjustment_type}
                    onChange={(event) => setAdjustments((previous) => previous.map((entry, itemIndex) =>
                      itemIndex === index ? { ...entry, adjustment_type: event.target.value as AdjustmentType } : entry))}>
                    {adjustmentTypes.filter((type) => enabledAdjustments.has(type.value))
                      .map((type) => <option value={type.value} key={type.value}>{type.label}</option>)}
                  </select></label>
                  <label className="module-field"><span>Calculation</span><select value={item.calculation_type}
                    onChange={(event) => setAdjustments((previous) => previous.map((entry, itemIndex) =>
                      itemIndex === index ? { ...entry, calculation_type: event.target.value as AdjustmentDraft['calculation_type'] } : entry))}>
                    <option value="fixed">Fixed amount</option><option value="percentage">Percentage</option><option value="manual">Manual override</option>
                  </select></label>
                  <label className="module-field"><span>Calculation basis</span><select value={item.calculation_basis}
                    onChange={(event) => setAdjustments((previous) => previous.map((entry, itemIndex) =>
                      itemIndex === index ? { ...entry, calculation_basis: event.target.value as AdjustmentDraft['calculation_basis'] } : entry))}>
                    {['current_period', 'cumulative', 'contract_amount', 'custom', 'not_applicable'].map((value) => (
                      <option key={value} value={value}>{value.replaceAll('_', ' ')}</option>
                    ))}
                  </select></label>
                  {item.calculation_type === 'percentage' ? (
                    <>
                      <label className="module-field"><span>Basis amount</span><MoneyInput min={0} value={item.basis_amount}
                        onChange={(value) => setAdjustments((previous) => previous.map((entry, itemIndex) =>
                          itemIndex === index ? { ...entry, basis_amount: value } : entry))} /></label>
                      <label className="module-field"><span>Rate (%)</span><input type="number" min="0" max="100" step="0.000001" value={item.rate}
                        onChange={(event) => setAdjustments((previous) => previous.map((entry, itemIndex) =>
                          itemIndex === index ? { ...entry, rate: event.target.value } : entry))} /></label>
                    </>
                  ) : <label className="module-field"><span>Amount (PHP)</span><MoneyInput min={0} value={item.amount}
                    onChange={(value) => setAdjustments((previous) => previous.map((entry, itemIndex) =>
                      itemIndex === index ? { ...entry, amount: value } : entry))} /></label>}
                  <label className="module-field"><span>Description / reason</span><input value={item.description}
                    onChange={(event) => setAdjustments((previous) => previous.map((entry, itemIndex) =>
                      itemIndex === index ? { ...entry, description: event.target.value } : entry))} /></label>
                  {item.adjustment_type === 'previous_billing_adjustment' && (
                    <label className="module-field"><span>Previous billing to deduct *</span>
                      <select required value={item.previous_billing_id}
                        onChange={(event) => setAdjustments((previous) => previous.map((entry, itemIndex) =>
                          itemIndex === index ? { ...entry, previous_billing_id: event.target.value } : entry))}>
                        <option value="">Select a billing record</option>
                        {projectRows.filter((row) => text(row.values.project_id) === projectId
                          && ['Approved', 'Certified', 'Paid', 'Overdue'].includes(text(row.values.status))
                          && (!contractId || text(row.values.contract_id) === contractId))
                          .map((row) => <option value={row.id} key={row.id}>{text(row.values.billing_number)}</option>)}
                      </select>
                    </label>
                  )}
                  {item.adjustment_type === 'materials_on_site' && (
                    <>
                      <label className="module-field"><span>Quantity *</span><input required type="number" min="0.001" step="0.001" value={item.quantity}
                        onChange={(event) => setAdjustments((previous) => previous.map((entry, itemIndex) =>
                          itemIndex === index ? { ...entry, quantity: event.target.value } : entry))} /></label>
                      <label className="module-field"><span>Unit *</span><input required value={item.unit}
                        onChange={(event) => setAdjustments((previous) => previous.map((entry, itemIndex) =>
                          itemIndex === index ? { ...entry, unit: event.target.value } : entry))} /></label>
                      <label className="module-field"><span>Unit price (PHP) *</span><MoneyInput required min={0} value={item.unit_price}
                        onChange={(value) => setAdjustments((previous) => previous.map((entry, itemIndex) =>
                          itemIndex === index ? { ...entry, unit_price: value } : entry))} /></label>
                    </>
                  )}
                  {item.adjustment_type === 'liquidated_damages' && (
                    <>
                      <label className="module-field"><span>Delay days</span><input type="number" min="0" step="1" value={item.days}
                        onChange={(event) => setAdjustments((previous) => previous.map((entry, itemIndex) =>
                          itemIndex === index ? { ...entry, days: event.target.value } : entry))} /></label>
                      <label className="module-field"><span>Amount per day (PHP)</span><MoneyInput min={0} value={item.daily_rate}
                        onChange={(value) => setAdjustments((previous) => previous.map((entry, itemIndex) =>
                          itemIndex === index ? { ...entry, daily_rate: value } : entry))} /></label>
                    </>
                  )}
                  {item.adjustment_type === 'backcharge' && (
                    <>
                      <label className="module-field"><span>Backcharge category</span><input value={item.category}
                        onChange={(event) => setAdjustments((previous) => previous.map((entry, itemIndex) =>
                          itemIndex === index ? { ...entry, category: event.target.value } : entry))} /></label>
                      <label className="module-field"><span>Backcharge date</span><input type="date" value={item.adjustment_date}
                        onChange={(event) => setAdjustments((previous) => previous.map((entry, itemIndex) =>
                          itemIndex === index ? { ...entry, adjustment_date: event.target.value } : entry))} /></label>
                    </>
                  )}
                  <label className="module-field"><span>Supporting document path</span><input value={item.supporting_document_path}
                    onChange={(event) => setAdjustments((previous) => previous.map((entry, itemIndex) =>
                      itemIndex === index ? { ...entry, supporting_document_path: event.target.value } : entry))} /></label>
                  <label className="module-field module-field-wide"><span>Additional details (JSON object)</span><textarea rows={2} value={item.extra}
                    onChange={(event) => setAdjustments((previous) => previous.map((entry, itemIndex) =>
                      itemIndex === index ? { ...entry, extra: event.target.value } : entry))} placeholder='{"quantity": 1, "unit": "lot"}' /></label>
                  <strong className="construction-billing-adjustment-preview">Preview: {money(adjustmentAmount(item))} · Pending approval</strong>
                </fieldset>
              ))}
              <button className="button button-secondary" type="button" disabled={enabledAdjustments.size === 0}
                onClick={() => setAdjustments((previous) => [...previous, {
                  ...emptyAdjustment(),
                  adjustment_type: adjustmentTypes.find((type) => enabledAdjustments.has(type.value))?.value
                    ?? 'mobilization_recovery',
                }])}>
                <Plus aria-hidden="true" size={15} /> Add adjustment
              </button>
            </details>

            <section className="construction-billing-preview" aria-live="polite">
              <h4><CircleDollarSign aria-hidden="true" size={18} /> Calculation preview</h4>
              {billingMode === 'cumulative' && <div><span>Cumulative gross to date</span><strong>{money(gross)}</strong></div>}
              <div><span>Billing Amount</span><strong>{centsMoney(billingAmountCents(currentPeriodGross))}</strong></div>
              <div><span>{withholdingVatRate || '0'}% Withholding of Final VAT</span><strong>− {centsMoney(billingCalculation.finalVatCents)}</strong></div>
              <div><span>{withholdingIncomeRate || '0'}% Withholding of Income Tax</span><strong>− {centsMoney(billingCalculation.incomeTaxCents)}</strong></div>
              <div><span>Total Withholding</span><strong>− {centsMoney(billingCalculation.totalCents)}</strong></div>
              <div><span>Net Billing</span><strong>{centsMoney(billingCalculation.netCents)}</strong></div>
              <div><span>Pending advanced adjustments (not applied)</span><strong>{money(pendingAdjustmentTotal)}</strong></div>
              <div className="construction-billing-preview-total"><span>Final Payable</span><strong>{money(previewNet)}</strong></div>
            </section>
            {error && <div className="notice notice-error" role="alert">{error}</div>}
            <div className="module-form-actions">
              <button className="button button-secondary" type="button" disabled={saving} onClick={() => {
                setShowCreate(false);
                setBillingDocuments([]);
              }}>Cancel</button>
              <button className="button button-primary" type="submit" disabled={saving}>
                {saving ? 'Saving…' : editingBillingId ? 'Save draft amendment' : 'Create billing record'}
              </button>
            </div>
          </form>
        </section>
        </ModalDialog>
      )}

      {selectedBilling && (
        <section className="panel construction-billing-detail" aria-labelledby="billing-detail-title">
          <div className="panel-heading">
            <div><p className="eyebrow">BILLING DETAILS</p><h3 id="billing-detail-title">{text(selectedBilling.values.billing_number)}</h3></div>
            <div className="construction-billing-actions">
              <button className="button button-secondary" type="button" onClick={() => window.print()}><Printer aria-hidden="true" size={16} /> Print / Export PDF</button>
              <button className="icon-button" type="button" aria-label="Close billing details" onClick={() => setSelectedBillingId('')}><X aria-hidden="true" size={18} /></button>
            </div>
          </div>
          <dl className="construction-billing-detail-grid">
            <div><dt>Billing date</dt><dd>{text(selectedBilling.values.billing_date)}</dd></div>
            <div><dt>Billing period</dt><dd>{text(selectedBilling.values.period_start)} – {text(selectedBilling.values.period_end)}</dd></div>
            <div><dt>Type / mode</dt><dd>{text(selectedBilling.values.billing_type)} / {text(selectedBilling.values.billing_mode)}</dd></div>
            <div><dt>Gross billing</dt><dd>{money(selectedBilling.values.gross_amount)}</dd></div>
            <div><dt>Previously billed</dt><dd>{money(selectedBilling.values.previous_billed)}</dd></div>
            <div><dt>Cumulative gross to date</dt><dd>{money(selectedBilling.values.cumulative_gross_amount)}</dd></div>
            <div><dt>VAT</dt><dd>{money(selectedBilling.values.vat_amount)}</dd></div>
            <div><dt>Net of VAT</dt><dd>{money(selectedBilling.values.net_of_vat)}</dd></div>
            <div><dt>VAT-exclusive base</dt><dd>{money(selectedBilling.values.vat_exclusive_base)}</dd></div>
            <div><dt>Withholding of Final VAT</dt><dd>{money(selectedBilling.values.withholding_vat_amount)}</dd></div>
            <div><dt>Withholding of Income Tax</dt><dd>{money(selectedBilling.values.withholding_income_tax_amount)}</dd></div>
            <div><dt>Total withholding</dt><dd>{money(selectedBilling.values.total_withholding)}</dd></div>
            <div><dt>Net billing</dt><dd>{money(selectedBilling.values.net_billing)}</dd></div>
            <div><dt>Final payable</dt><dd>{money(selectedBilling.values.final_payable)}</dd></div>
            <div><dt>Tax deductions</dt><dd>{money(selectedBilling.values.total_tax_deductions)}</dd></div>
            <div><dt>Construction deductions</dt><dd>{money(selectedBilling.values.total_construction_deductions)}</dd></div>
            <div><dt>Other deductions</dt><dd>{money(selectedBilling.values.total_other_deductions)}</dd></div>
            <div><dt>Net payable</dt><dd>{money(selectedBilling.values.net_amount)}</dd></div>
            <div><dt>Collected</dt><dd>{money(selectedBilling.values.collected_amount)}</dd></div>
            <div><dt>Remaining balance</dt><dd>{money(amount(selectedBilling.values.net_amount) - amount(selectedBilling.values.collected_amount))}</dd></div>
            <div><dt>Approval</dt><dd>{text(selectedBilling.values.status)} · {text(selectedBilling.values.approved_by)}</dd></div>
          </dl>
          <h4>Adjustments</h4>
          {selectedAdjustments.length ? (
            <div className="table-scroll"><table><thead><tr><th>Type</th><th>Description</th><th>Basis</th><th className="is-numeric">Amount</th><th>Status</th><th>Action</th></tr></thead>
              <tbody>{selectedAdjustments.map((adjustment) => (
                <tr key={text(adjustment.id)}>
                  <td>{text(adjustment.adjustment_type).replaceAll('_', ' ')}</td>
                  <td>{text((adjustment.details as Record<string, unknown> | undefined)?.description)}</td>
                  <td>{text(adjustment.calculation_basis)} · {text(adjustment.rate)}%</td>
                  <td className="is-numeric">{money(adjustment.amount)}</td>
                  <td>{text(adjustment.approval_status)}</td>
                  <td>{text(adjustment.approval_status) === 'Pending' && page.canEdit ? (
                    <div className="construction-billing-actions">
                      <button className="button button-secondary" type="button" onClick={() => void decideAdjustment(text(adjustment.id), true)} disabled={saving}>
                        <Check aria-hidden="true" size={14} /> Approve
                      </button>
                      <button className="button button-secondary" type="button" onClick={() => void decideAdjustment(text(adjustment.id), false)} disabled={saving}>Reject</button>
                    </div>
                  ) : '—'}</td>
                </tr>
              ))}</tbody></table></div>
          ) : <p className="construction-billing-empty">No advanced adjustments are attached to this billing.</p>}
          {['Draft', 'Submitted', 'Under Review'].includes(text(selectedBilling.values.status)) && page.canEdit && (
            <div className="construction-billing-actions">
              {text(selectedBilling.values.status) === 'Draft' && (
                <button className="button button-secondary" type="button" onClick={startBillingEdit} disabled={saving}>
                  Edit draft billing
                </button>
              )}
              <button className="button button-primary" type="button" onClick={() => void decideBilling(true)} disabled={saving}>
                <Check aria-hidden="true" size={15} /> Approve billing
              </button>
              {text(selectedBilling.values.status) === 'Draft' && (
                <button className="button button-secondary" type="button" onClick={() => {
                  if (window.confirm('Void this unpaid draft billing? The record and audit history will be retained.')) void decideBilling(false);
                }} disabled={saving}>Void draft</button>
              )}
            </div>
          )}
          {['Approved', 'Certified'].includes(text(selectedBilling.values.status)) && page.canEdit && (
            <div className="construction-billing-reversal">
              {amount(selectedBilling.values.collected_amount) > 0 ? (
                <p className="construction-billing-empty">
                  This billing has collections and cannot be reversed until those payments are resolved.
                </p>
              ) : showReverseBilling ? (
                <form className="module-form" onSubmit={(event) => void submitBillingReversal(event)}>
                  <label className="module-field module-field-wide">
                    <span>Reason for reversal *</span>
                    <textarea required minLength={5} value={reversalReason}
                      onChange={(event) => setReversalReason(event.target.value)} />
                  </label>
                  <div className="module-form-actions">
                    <button className="button button-secondary" type="button" onClick={() => setShowReverseBilling(false)}>Keep billing</button>
                    <button className="button button-secondary" type="submit" disabled={saving}>Reverse unpaid billing</button>
                  </div>
                </form>
              ) : (
                <button className="button button-secondary" type="button" onClick={() => setShowReverseBilling(true)}>
                  Reverse unpaid billing
                </button>
              )}
            </div>
          )}
          {['Approved', 'Certified', 'Paid'].includes(text(selectedBilling.values.status)) && (
            <form className="module-form construction-billing-payment" onSubmit={(event) => void submitPayment(event)}>
              <h4><CircleDollarSign aria-hidden="true" size={17} /> Record payment</h4>
              <label className="module-field"><span>Amount (PHP)</span><MoneyInput required min={0.01}
                max={Math.max(0, amount(selectedBilling.values.net_amount) - amount(selectedBilling.values.collected_amount))}
                value={paymentAmount} onChange={setPaymentAmount} /></label>
              <label className="module-field"><span>Payment date</span><input required type="date" value={paymentDate} onChange={(event) => setPaymentDate(event.target.value)} /></label>
              <label className="module-field"><span>Method</span><input required value={paymentMethod} onChange={(event) => setPaymentMethod(event.target.value)} /></label>
              <label className="module-field"><span>Reference</span><input value={paymentReference} onChange={(event) => setPaymentReference(event.target.value)} /></label>
              <label className="module-field"><span>Supporting document path</span><input value={paymentDocument} onChange={(event) => setPaymentDocument(event.target.value)} /></label>
              <button className="button button-primary" type="submit" disabled={saving}>Record payment</button>
            </form>
          )}
          {amount(selectedBilling.values.collected_amount) > 0 && page.canEdit && (
            <div className="construction-billing-refund">
              {showRefund ? (
                <form className="module-form" onSubmit={(event) => void submitRefund(event)}>
                  <h4>Record payment refund</h4>
                  <label className="module-field"><span>Refund amount (PHP) *</span>
                    <MoneyInput required min={0.01} max={amount(selectedBilling.values.collected_amount)}
                      value={refundAmount} onChange={setRefundAmount} /></label>
                  <label className="module-field"><span>Refund date *</span>
                    <input required type="date" value={refundDate} onChange={(event) => setRefundDate(event.target.value)} /></label>
                  <label className="module-field"><span>Reference</span>
                    <input value={refundReference} onChange={(event) => setRefundReference(event.target.value)} /></label>
                  <label className="module-field module-field-wide"><span>Reason *</span>
                    <textarea required minLength={5} value={refundReason}
                      onChange={(event) => setRefundReason(event.target.value)} /></label>
                  <div className="module-form-actions">
                    <button className="button button-secondary" type="button" onClick={() => setShowRefund(false)}>Cancel</button>
                    <button className="button button-primary" type="submit" disabled={saving}>Record refund</button>
                  </div>
                </form>
              ) : (
                <button className="button button-secondary" type="button" onClick={() => setShowRefund(true)}>
                  Record refund
                </button>
              )}
            </div>
          )}
          <h4>Payment history</h4>
          {selectedPayments.length ? (
            <div className="table-scroll"><table><thead><tr><th>Date</th><th>Method</th><th>Reference</th><th className="is-numeric">Amount</th><th>Document</th></tr></thead>
              <tbody>{selectedPayments.map((payment) => (
                <tr key={text(payment.id)}><td>{text(payment.collection_date)}</td><td>{text(payment.payment_method)}</td>
                  <td>{text(payment.reference_number)}</td><td className="is-numeric">{money(payment.amount)}</td>
                  <td>{text(payment.supporting_document_path)}</td></tr>
              ))}</tbody></table></div>
          ) : <p className="construction-billing-empty">No payments have been recorded.</p>}
          <h4>Tax withholding remittance</h4>
          <div className="construction-billing-detail-grid">
            <div><dt>Status</dt><dd>{text(selectedBilling.values.withholding_status) || 'Pending'}</dd></div>
            <div><dt>Total withheld</dt><dd>{money(selectedBilling.values.total_withholding)}</dd></div>
            <div><dt>Amount remitted</dt><dd>{money(selectedBilling.values.withholding_remitted)}</dd></div>
            <div><dt>Remaining balance</dt><dd>{money(selectedBilling.values.withholding_remaining)}</dd></div>
          </div>
          {page.canEdit && text(selectedBilling.values.withholding_status) !== 'Paid' && (
            <form className="module-form construction-billing-payment" onSubmit={(event) => void submitWithholdingRemittance(event)}>
              <h4>Record withholding remittance</h4>
              <label className="module-field"><span>Amount (PHP)</span>
                <MoneyInput required min={0.01} max={amount(selectedBilling.values.withholding_remaining)}
                  value={remittanceAmount} onChange={setRemittanceAmount} /></label>
              <label className="module-field"><span>Remittance date</span>
                <input required type="date" value={remittanceDate} onChange={(event) => setRemittanceDate(event.target.value)} /></label>
              <label className="module-field"><span>Payment reference</span>
                <input value={remittanceReference} onChange={(event) => setRemittanceReference(event.target.value)} /></label>
              <label className="module-field"><span>Proof of payment path</span>
                <input value={remittanceProof} onChange={(event) => setRemittanceProof(event.target.value)} /></label>
              <button className="button button-primary" type="submit" disabled={saving}>Record remittance</button>
            </form>
          )}
          {selectedWithholdingRemittances.length > 0 && (
            <div className="table-scroll"><table><thead><tr><th>Date</th><th>Reference</th><th className="is-numeric">Amount</th><th>Proof</th></tr></thead>
              <tbody>{selectedWithholdingRemittances.map((item) => (
                <tr key={text(item.id)}>
                  <td>{text(item.remittance_date)}</td><td>{text(item.payment_reference)}</td>
                  <td className="is-numeric">{money(item.amount)}</td><td>{text(item.proof_document_path)}</td>
                </tr>
              ))}</tbody></table></div>
          )}
          <h4><FileText aria-hidden="true" size={17} /> Supporting documents</h4>
          {selectedAttachments.length ? selectedAttachments.map((file) => (
            <p key={text(file.id)}>{text(file.attachment_name)} · {text(file.document_path)}</p>
          )) : <p className="construction-billing-empty">No billing attachments are recorded. Supporting document paths entered on adjustments remain in their approval details.</p>}
          {text(selectedBilling.values.status) !== 'Cancelled' && page.canEdit && <div className="construction-billing-document-picker">
            <label className="module-field">
              <span>Attach documents (PNG, JPG, PDF; 10 MB total)</span>
              <input aria-label="Add billing documents" type="file" accept=".png,.jpg,.jpeg,.pdf" multiple
                onChange={(event) => { addBillingDocuments(event.target.files); event.currentTarget.value = ''; }} />
            </label>
            {billingDocuments.map((item, index) => (
              <div className="construction-billing-document-draft" key={item.idempotencyKey}>
                <span>{item.file.name} · {(item.file.size / 1024).toFixed(0)} KB</span>
                <button className="text-button text-button-danger" type="button"
                  aria-label={`Remove billing document ${index + 1}`}
                  onClick={() => setBillingDocuments((previous) => previous.filter((file) => file.idempotencyKey !== item.idempotencyKey))}>
                  Remove
                </button>
              </div>
            ))}
            <button className="button button-secondary" type="button" disabled={saving || billingDocuments.length === 0}
              onClick={() => void attachBillingDocuments()}>Attach to billing</button>
          </div>}
          <h4>Activity history</h4>
          {selectedEvents.length ? selectedEvents.map((event) => (
            <p className="construction-billing-event" key={text(event.id)}>
              <strong>{text(event.event_type).replaceAll('_', ' ')}</strong> · {text(event.created_at)} · {text(event.actor_id)}
            </p>
          )) : <p className="construction-billing-empty">No activity history is available.</p>}
        </section>
      )}

      {projectId && (
        <section className="panel construction-billing-release" aria-labelledby="retention-release-title">
          <div className="panel-heading"><div><p className="eyebrow">PROJECT FINANCIAL CONTROLS</p><h3 id="retention-release-title">Retention release</h3></div></div>
          <p>Outstanding retained amount for the selected project: <strong>{money(projectTotals.retentionOutstanding)}</strong></p>
          <form className="module-form" onSubmit={(event) => void submitRetentionRelease(event)}>
            <label className="module-field"><span>Release amount</span><MoneyInput required min={0.01}
              max={projectTotals.retentionOutstanding} value={releaseAmount} onChange={setReleaseAmount} /></label>
            <label className="module-field"><span>Release date</span><input required type="date" value={releaseDate} onChange={(event) => setReleaseDate(event.target.value)} /></label>
            <label className="module-field"><span>Reference</span><input value={releaseReference} onChange={(event) => setReleaseReference(event.target.value)} /></label>
            <label className="module-field"><span>Supporting document path</span><input value={releaseDocument} onChange={(event) => setReleaseDocument(event.target.value)} /></label>
            <button className="button button-secondary" type="submit" disabled={saving || projectTotals.retentionOutstanding <= 0}>Record release</button>
          </form>
        </section>
      )}
    </div>
  );
}
