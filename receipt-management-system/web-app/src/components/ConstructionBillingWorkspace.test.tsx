import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { ModulePageData } from '../api/modules';
import { ConstructionBillingWorkspace } from './ConstructionBillingWorkspace';

const page: ModulePageData = {
  key: 'CONSTRUCTION',
  title: 'Billings',
  description: 'Project billing transactions.',
  metrics: [],
  columns: [],
  fields: [
    { key: 'project_id', label: 'Project', type: 'select', options: [{ label: 'Bridge Alpha', value: 'project-1' }] },
    { key: 'contract_id', label: 'Contract', type: 'select', options: [{ label: 'Contract A', value: 'contract-1', description: 'project-1' }] },
  ],
  rows: [],
  canCreate: true,
  canEdit: true,
  canDelete: false,
  primaryActionLabel: 'New billing',
  submitLabel: 'Create billing',
  createAction: 'save',
  billingConfigurations: [],
  billingRetentionReleases: [],
};

describe('ConstructionBillingWorkspace', () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it('previews configured VAT and withholding amounts and creates an independent billing record', async () => {
    const onAction = vi.fn().mockResolvedValue(page);
    render(<ConstructionBillingWorkspace page={page} saving={false} onAction={onAction} />);

    fireEvent.change(screen.getByLabelText('Project'), { target: { value: 'project-1' } });
    fireEvent.click(screen.getByRole('button', { name: /New billing/ }));
    expect(screen.getByRole('dialog', { name: 'Create billing' })).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('Amount (PHP) *'), { target: { value: '1174587.71' } });
    fireEvent.change(screen.getByLabelText('% Withholding of Final VAT'), { target: { value: '5' } });
    fireEvent.change(screen.getByLabelText('% Withholding of Income Tax'), { target: { value: '2' } });

    expect(screen.getByText('− ₱52,436.95')).toBeInTheDocument();
    expect(screen.getByText('− ₱20,974.78')).toBeInTheDocument();
    expect(screen.getByText('− ₱73,411.73')).toBeInTheDocument();
    expect(screen.getAllByText('₱1,101,175.98').length).toBeGreaterThan(0);

    fireEvent.click(screen.getByRole('button', { name: 'Create billing record' }));

    await waitFor(() => expect(onAction).toHaveBeenCalledWith(
      'create-billing',
      expect.objectContaining({
        project_id: 'project-1',
        gross_amount: 1174587.71,
        withholding_vat_rate: 5,
        withholding_income_tax_rate: 2,
        adjustments: [],
      }),
      undefined,
    ));
  });

  it('subtracts approved prior billings once in cumulative mode', async () => {
    const historyPage: ModulePageData = {
      ...page,
      rows: [{
        id: 'billing-1',
        values: {
          project_id: 'project-1',
          contract_id: 'contract-1',
          billing_number: 'BILL-001',
          gross_amount: 1000,
          status: 'Approved',
        },
      }],
    };
    const onAction = vi.fn().mockResolvedValue(historyPage);
    render(<ConstructionBillingWorkspace page={historyPage} saving={false} onAction={onAction} />);

    fireEvent.change(screen.getByLabelText('Project'), { target: { value: 'project-1' } });
    fireEvent.click(screen.getByRole('button', { name: /New billing/ }));
    fireEvent.change(screen.getByLabelText('Contract'), { target: { value: 'contract-1' } });
    fireEvent.change(screen.getByLabelText('Billing mode'), { target: { value: 'cumulative' } });
    fireEvent.change(screen.getByLabelText('Amount (PHP) *'), { target: { value: '5000' } });

    expect(screen.getByText('Billing Amount').parentElement)
      .toHaveTextContent('₱4,000.00');
    fireEvent.click(screen.getByRole('button', { name: 'Create billing record' }));

    await waitFor(() => expect(onAction).toHaveBeenCalledWith(
      'create-billing',
      expect.objectContaining({
        billing_mode: 'cumulative',
        gross_amount: 5000,
      }),
      undefined,
    ));
  });

  it('filters and sorts billing history by search, status, date, and amount', () => {
    const historyPage: ModulePageData = {
      ...page,
      rows: [
        { id: 'billing-1', values: {
          project_id: 'project-1', billing_number: 'BILL-001', billing_date: '2025-01-10',
          description: 'Foundation work', status: 'Approved', payment_status: 'Partial', net_amount: 5000,
        } },
        { id: 'billing-2', values: {
          project_id: 'project-1', billing_number: 'BILL-002', billing_date: '2025-02-10',
          description: 'Roof work', status: 'Draft', payment_status: 'Unpaid', net_amount: 9000,
        } },
      ],
    };
    render(<ConstructionBillingWorkspace page={historyPage} saving={false} onAction={vi.fn()} />);

    fireEvent.change(screen.getByLabelText('Project'), { target: { value: 'project-1' } });
    expect(screen.getByText('BILL-001')).toBeInTheDocument();
    expect(screen.getByText('BILL-002')).toBeInTheDocument();
    expect(within(screen.getByRole('table')).getAllByRole('row')[1]).toHaveTextContent('BILL-002');

    fireEvent.change(screen.getByLabelText('Sort by'), { target: { value: 'net-asc' } });
    expect(within(screen.getByRole('table')).getAllByRole('row')[1]).toHaveTextContent('BILL-001');

    fireEvent.change(screen.getByLabelText('Search billings'), { target: { value: 'foundation' } });
    expect(screen.getByText('BILL-001')).toBeInTheDocument();
    expect(screen.queryByText('BILL-002')).not.toBeInTheDocument();

    fireEvent.change(screen.getByLabelText('Search billings'), { target: { value: '' } });
    fireEvent.change(screen.getByLabelText('Billing status'), { target: { value: 'Draft' } });
    fireEvent.change(screen.getByLabelText('Billing date from'), { target: { value: '2025-02-01' } });
    expect(screen.getByText('BILL-002')).toBeInTheDocument();
    expect(screen.queryByText('BILL-001')).not.toBeInTheDocument();
    expect(screen.getByText('1 of 2 records')).toBeInTheDocument();
  });

  it('allows authorized reversal for an approved unpaid billing', async () => {
    const historyPage: ModulePageData = {
      ...page,
      rows: [{
        id: 'billing-approved',
        values: {
          project_id: 'project-1',
          billing_number: 'BILL-001',
          billing_date: '2025-01-10',
          status: 'Approved',
          payment_status: 'Unpaid',
          gross_amount: 1000,
          net_amount: 1000,
          collected_amount: 0,
        },
      }],
    };
    const onAction = vi.fn().mockResolvedValue(historyPage);
    render(<ConstructionBillingWorkspace page={historyPage} saving={false} onAction={onAction} />);

    fireEvent.change(screen.getByLabelText('Project'), { target: { value: 'project-1' } });
    fireEvent.click(screen.getByRole('button', { name: 'BILL-001' }));
    fireEvent.click(screen.getByRole('button', { name: 'Reverse unpaid billing' }));
    fireEvent.change(screen.getByLabelText('Reason for reversal *'), { target: { value: 'Incorrect billing period' } });
    fireEvent.click(screen.getByRole('button', { name: 'Reverse unpaid billing' }));

    await waitFor(() => expect(onAction).toHaveBeenCalledWith(
      'reverse-billing',
      { reason: 'Incorrect billing period' },
      'billing-approved',
    ));
  });

  it('opens an authorized draft for amendment and submits the amended values', async () => {
    const draftPage: ModulePageData = {
      ...page,
      rows: [{
        id: 'billing-draft',
        values: {
          project_id: 'project-1',
          contract_id: 'contract-1',
          billing_number: 'BILL-001',
          billing_date: '2025-01-10',
          billing_type: 'Progress',
          billing_mode: 'period',
          description: 'Draft foundation billing',
          gross_amount: 1000,
          cumulative_gross_amount: 1000,
          vat_rate: 12,
          vat_base: 1000,
          withholding_vat_rate: 0,
          withholding_vat_base: 0,
          withholding_income_tax_rate: 0,
          withholding_income_tax_base: 0,
          other_tax_withholdings: 0,
          status: 'Draft',
          collected_amount: 0,
          adjustments: [],
        },
      }],
    };
    const onAction = vi.fn().mockResolvedValue(draftPage);
    render(<ConstructionBillingWorkspace page={draftPage} saving={false} onAction={onAction} />);

    fireEvent.change(screen.getByLabelText('Project'), { target: { value: 'project-1' } });
    fireEvent.click(screen.getByRole('button', { name: 'BILL-001' }));
    fireEvent.click(screen.getByRole('button', { name: 'Edit draft billing' }));
    fireEvent.change(screen.getByLabelText('Amount (PHP) *'), { target: { value: '1250' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save draft amendment' }));

    await waitFor(() => expect(onAction).toHaveBeenCalledWith(
      'amend-billing',
      expect.objectContaining({
        project_id: 'project-1',
        gross_amount: 1250,
        amendment_reason: 'Authorized billing record amendment.',
      }),
      'billing-draft',
    ));
  });

  it('records an authorized refund against a paid billing', async () => {
    const paidPage: ModulePageData = {
      ...page,
      rows: [{
        id: 'billing-paid',
        values: {
          project_id: 'project-1',
          billing_number: 'BILL-001',
          billing_date: '2025-01-10',
          status: 'Paid',
          payment_status: 'Paid',
          gross_amount: 1000,
          net_amount: 1000,
          collected_amount: 1000,
          adjustments: [],
        },
      }],
    };
    const onAction = vi.fn().mockResolvedValue(paidPage);
    render(<ConstructionBillingWorkspace page={paidPage} saving={false} onAction={onAction} />);

    fireEvent.change(screen.getByLabelText('Project'), { target: { value: 'project-1' } });
    fireEvent.click(screen.getByRole('button', { name: 'BILL-001' }));
    fireEvent.click(screen.getByRole('button', { name: 'Record refund' }));
    fireEvent.change(screen.getByLabelText('Refund amount (PHP) *'), { target: { value: '250' } });
    fireEvent.change(screen.getByLabelText('Reason *'), { target: { value: 'Authorized overpayment refund' } });
    fireEvent.click(screen.getByRole('button', { name: 'Record refund' }));

    await waitFor(() => expect(onAction).toHaveBeenCalledWith(
      'refund-billing-payment',
      expect.objectContaining({
        amount: 250,
        reason: 'Authorized overpayment refund',
      }),
      'billing-paid',
    ));
  });

  it('passes supporting billing documents through the upload workflow', async () => {
    const onAction = vi.fn().mockResolvedValue(page);
    render(<ConstructionBillingWorkspace page={page} saving={false} onAction={onAction} />);

    fireEvent.change(screen.getByLabelText('Project'), { target: { value: 'project-1' } });
    fireEvent.click(screen.getByRole('button', { name: /New billing/ }));
    fireEvent.change(screen.getByLabelText('Amount (PHP) *'), { target: { value: '1000' } });
    fireEvent.change(screen.getByLabelText('Add billing documents'), {
      target: { files: [new File(['report'], 'progress.pdf', { type: 'application/pdf' })] },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Create billing record' }));

    await waitFor(() => expect(onAction).toHaveBeenCalledWith(
      'create-billing',
      expect.objectContaining({
        attachments: [expect.objectContaining({
          name: 'progress.pdf',
          data: expect.any(String),
          idempotency_key: expect.any(String),
        })],
      }),
      undefined,
    ));
  });

  it('previews the net payable conditionally and keeps proposed adjustments pending approval', async () => {
    const configuredPage: ModulePageData = {
      ...page,
      billingConfigurations: [{
        project_id: 'project-1',
        contract_id: null,
        settings: {
          mobilization_advance_amount: 20000,
          retention_cap_amount: 5000,
          enabled_adjustments: { mobilization_recovery: true },
        },
      }],
    };
    const onAction = vi.fn().mockResolvedValue(configuredPage);
    const { rerender } = render(<ConstructionBillingWorkspace page={page} saving={false} onAction={onAction} />);

    fireEvent.change(screen.getByLabelText('Project'), { target: { value: 'project-1' } });
    rerender(<ConstructionBillingWorkspace page={configuredPage} saving={false} onAction={onAction} />);
    fireEvent.click(screen.getByRole('button', { name: /New billing/ }));
    fireEvent.change(screen.getByLabelText('Amount (PHP) *'), { target: { value: '100000' } });
    fireEvent.click(screen.getByText('Advanced Billing Adjustments'));
    fireEvent.click(screen.getByRole('button', { name: 'Add adjustment' }));
    fireEvent.change(screen.getByLabelText('Amount (PHP)'), { target: { value: '5000' } });

    expect(within(screen.getByText('Final Payable').parentElement as HTMLElement)
      .getByText('₱88,750.00')).toBeInTheDocument();
    expect(screen.getByText('Preview: ₱5,000.00 · Pending approval')).toBeInTheDocument();

    vi.spyOn(window, 'confirm').mockReturnValue(true);
    fireEvent.click(screen.getByRole('button', { name: 'Create billing record' }));

    await waitFor(() => expect(onAction).toHaveBeenCalledWith(
      'create-billing',
      expect.objectContaining({
        gross_amount: 100000,
        adjustments: [expect.objectContaining({
          adjustment_type: 'mobilization_recovery',
          amount: 5000,
          calculation_type: 'fixed',
        })],
      }),
      undefined,
    ));
  });
});
