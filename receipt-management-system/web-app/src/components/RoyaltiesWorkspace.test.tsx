import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { ModulePageData } from '../api/modules';
import { RoyaltiesWorkspace } from './RoyaltiesWorkspace';

const page: ModulePageData = {
  key: 'CONSTRUCTION',
  title: 'Royalties',
  description: 'Manage royalty records.',
  metrics: [
    { label: 'Royalty total', value: '₱7,636.48' },
    { label: 'Pending returns', value: '₱5,090.98' },
  ],
  columns: [],
  fields: [],
  rows: [],
  canCreate: true,
  canEdit: true,
  canDelete: false,
  primaryActionLabel: 'New royalty record',
  submitLabel: 'Save royalty record',
  createAction: 'save',
  royaltyConfiguration: {
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
  },
  royaltyContracts: [
    {
      id: 'contract-1',
      project_id: 'project-1',
      project_name: 'Warehouse Project',
      contract_name: 'Warehouse Construction',
      client_name: 'John',
      contract_amount: '1000000.50',
    },
  ],
  royaltySuppliers: [
    { id: 'supplier-1', business_name: 'John Ltd', tax_classification: 'NON_VAT' },
  ],
  royaltyProjects: [{ id: 'project-1', name: 'Warehouse Project' }],
};

const record = {
  id: 'royalty-1',
  values: {
    client_name: 'John',
    contract_name: 'Warehouse Construction',
    billing_date: '2025-03-15',
    billing_amount: '254549.21',
    vat_treatment: 'NON_VAT',
    vat_amount: '0',
    royalty_rate: '3',
    royalty_basis: 'VAT_EXCLUSIVE',
    royalty_amount: '7636.48',
    withholding_rate: '2',
    withholding_basis: 'VAT_EXCLUSIVE',
    withholding_amount: '5090.98',
    additional_withholding_amount: '0',
    ewt_amount: '0',
    retention_amount: '0',
    initial_net_amount: '241821.75',
    returned_amount: '0',
    outstanding_return: '5090.98',
    final_net_amount: '241821.75',
    status: 'pending',
  },
};

describe('RoyaltiesWorkspace', () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it('groups saved records by client, year, and month with saved monthly totals', () => {
    render(<RoyaltiesWorkspace page={{ ...page, rows: [record] }} saving={false} onAction={vi.fn()} />);

    const history = screen.getByRole('region', { name: 'Royalty records' });
    expect(within(history).getByText('John')).toBeInTheDocument();
    expect(within(history).getByText('2025')).toBeInTheDocument();
    expect(within(history).getByText('March')).toBeInTheDocument();
    fireEvent.click(within(history).getByText('March'));

    const table = within(history).getByRole('table');
    expect(within(table).getByText('Warehouse Construction')).toBeInTheDocument();
    expect(within(table).getAllByText('₱7,636.48')).toHaveLength(2);
    expect(within(table).getAllByText('₱5,090.98')).toHaveLength(2);
  });

  it('formats raw royalty summary values as Philippine pesos', () => {
    render(
      <RoyaltiesWorkspace
        page={{
          ...page,
          metrics: [
            { label: 'Royalty total', value: '7636.48' },
            { label: 'Pending returns', value: '5090.98' },
          ],
        }}
        saving={false}
        onAction={vi.fn()}
      />,
    );

    expect(screen.getByText('₱7,636.48')).toBeInTheDocument();
    expect(screen.getByText('₱5,090.98')).toBeInTheDocument();
  });

  it('shows saved royalty totals by client and provides an empty state without amounts', () => {
    const secondJohnRecord = {
      ...record,
      id: 'royalty-2',
      values: { ...record.values, royalty_amount: '100.00' },
    };
    const janeRecord = {
      ...record,
      id: 'royalty-3',
      values: { ...record.values, client_name: 'Jane', royalty_amount: '2500.00' },
    };
    const { rerender } = render(
      <RoyaltiesWorkspace
        page={{ ...page, rows: [record, secondJohnRecord, janeRecord] }}
        saving={false}
        onAction={vi.fn()}
      />,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Client mix' }));
    const chart = screen.getByRole('region', { name: 'Royalty analytics' });
    const legend = within(chart).getByRole('list', { name: 'Royalty amounts by client' });

    expect(within(legend).getByText('John')).toBeInTheDocument();
    expect(within(legend).getByText('₱7,736.48')).toBeInTheDocument();
    expect(within(legend).getByText('Jane')).toBeInTheDocument();
    expect(within(legend).getByText('₱2,500.00')).toBeInTheDocument();
    expect(within(chart).getByRole('img', { name: /Royalty distribution chart: John/ })).toBeInTheDocument();

    rerender(<RoyaltiesWorkspace page={page} saving={false} onAction={vi.fn()} />);
    expect(within(screen.getByRole('region', { name: 'Royalty analytics' }))
      .getByText('Client distribution will appear after royalty amounts are saved.')).toBeInTheDocument();
  });

  it('previews the reference calculation and saves a standalone pending royalty record', async () => {
    const onAction = vi.fn().mockResolvedValue(page);
    const nativeConfirm = vi.spyOn(window, 'confirm').mockReturnValue(true);
    render(<RoyaltiesWorkspace page={page} saving={false} onAction={onAction} />);

    fireEvent.click(screen.getByRole('button', { name: 'New royalty record' }));
    expect(screen.getByText('Basic Information')).toBeInTheDocument();
    expect(screen.getByText('Advanced Settings')).toBeInTheDocument();
    expect(screen.getByText('Advanced Settings').closest('details')).not.toHaveAttribute('open');
    expect(screen.getByRole('spinbutton', { name: /5% Withheld/ })).toHaveValue(null);
    expect(screen.getByRole('spinbutton', { name: /EWT rate/ })).toHaveValue(null);
    expect(screen.getByRole('spinbutton', { name: /Retention rate/ })).toHaveValue(null);
    expect(screen.getByLabelText('Royalty Rate (%)')).toHaveValue(3);
    expect(screen.getByLabelText('Withheld Rate (%)')).toHaveValue(2);

    fireEvent.change(screen.getByLabelText('Client Name *'), { target: { value: 'John' } });
    fireEvent.change(screen.getByLabelText('Billing Amount (PHP) *'), { target: { value: '254549.21' } });
    expect(screen.getByLabelText('Billing Amount (PHP) *')).toHaveValue('254,549.21');
    expect(screen.getByLabelText('Project Name')).toHaveValue('');
    fireEvent.click(screen.getByText('Advanced Settings'));
    fireEvent.change(screen.getByLabelText('Billing VAT treatment *'), { target: { value: 'NON_VAT' } });

    expect(screen.getByText('− ₱7,636.48')).toBeInTheDocument();
    expect(screen.getByText('− ₱5,090.98')).toBeInTheDocument();
    expect(screen.getAllByText('₱241,821.75')).toHaveLength(2);
    fireEvent.click(screen.getByRole('button', { name: 'Save royalty record' }));

    expect(nativeConfirm).not.toHaveBeenCalled();
    expect(screen.getByRole('dialog', { name: 'Confirm royalty record' })).toBeInTheDocument();
    expect(screen.getByText('₱12,727.46')).toBeInTheDocument();
    expect(screen.getByText('Confirm and save')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Confirm and save' }));

    await waitFor(() => expect(onAction).toHaveBeenCalledWith(
      'save-royalty',
      expect.objectContaining({
        client_name: 'John',
        billing_amount: '254549.21',
        vat_treatment: 'NON_VAT',
        royalty_rate: '3',
        withholding_rate: '2',
        additional_withholding_rate: '',
        ewt_rate: '',
        retention_rate: '',
        idempotency_key: expect.any(String),
      }),
      undefined,
    ));
  });

  it('lets the user return from the themed confirmation without saving', () => {
    const onAction = vi.fn();
    const nativeConfirm = vi.spyOn(window, 'confirm').mockReturnValue(true);
    render(<RoyaltiesWorkspace page={page} saving={false} onAction={onAction} />);

    fireEvent.click(screen.getByRole('button', { name: 'New royalty record' }));
    fireEvent.change(screen.getByLabelText('Client Name *'), { target: { value: 'John' } });
    fireEvent.change(screen.getByLabelText('Billing Amount (PHP) *'), { target: { value: '1000' } });
    fireEvent.change(screen.getByRole('spinbutton', { name: /EWT rate/ }), { target: { value: '1' } });
    fireEvent.click(screen.getByText('Advanced Settings'));
    fireEvent.change(screen.getByLabelText('Billing VAT treatment *'), { target: { value: 'NON_VAT' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save royalty record' }));

    expect(screen.getByRole('dialog', { name: 'Confirm royalty record' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Back to record' }));

    expect(screen.getByRole('dialog', { name: 'New royalty record' })).toBeInTheDocument();
    expect(screen.getByLabelText('Billing Amount (PHP) *')).toHaveValue('1,000');
    expect(onAction).not.toHaveBeenCalled();
    expect(nativeConfirm).not.toHaveBeenCalled();
  });

  it('keeps project and supplier selection available in advanced settings', () => {
    render(<RoyaltiesWorkspace page={page} saving={false} onAction={vi.fn()} />);
    fireEvent.click(screen.getByRole('button', { name: 'New royalty record' }));

    fireEvent.change(screen.getByLabelText('Project Name'), { target: { value: 'project-1' } });
    expect(screen.getByLabelText('Project Name')).toHaveValue('project-1');
    expect(screen.getByText('Advanced Settings').closest('details')).not.toHaveAttribute('open');

    fireEvent.click(screen.getByText('Advanced Settings'));
    fireEvent.change(screen.getByLabelText('Contract linking'), { target: { value: 'contract-1' } });
    expect(screen.getByLabelText('Project UUID (optional)')).toHaveValue('project-1');
    expect(screen.getByLabelText('Client Name *')).toHaveValue('John');
    fireEvent.change(screen.getByLabelText('Supplier selection'), { target: { value: 'supplier-1' } });
    expect(screen.getByLabelText('Supplier tax classification')).toHaveValue('NON VAT');
  });

  it('opens Advanced Settings and asks for VAT treatment instead of assuming it', () => {
    render(<RoyaltiesWorkspace page={page} saving={false} onAction={vi.fn()} />);
    fireEvent.click(screen.getByRole('button', { name: 'New royalty record' }));
    fireEvent.change(screen.getByLabelText('Client Name *'), { target: { value: 'John' } });
    fireEvent.change(screen.getByLabelText('Billing Amount (PHP) *'), { target: { value: '1000' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save royalty record' }));

    expect(screen.getByText('Advanced Settings').closest('details')).toHaveAttribute('open');
    expect(screen.getByRole('alert')).toHaveTextContent('Choose the billing VAT treatment in Advanced Settings.');
    expect(screen.getByText('Net Billing').parentElement).toHaveTextContent('—');
  });

  it('records an actual partial or full return through its own action', async () => {
    const onAction = vi.fn().mockResolvedValue(page);
    render(<RoyaltiesWorkspace page={{ ...page, rows: [record] }} saving={false} onAction={onAction} />);

    fireEvent.click(screen.getByRole('button', { name: 'Details' }));
    fireEvent.click(screen.getByRole('button', { name: 'Record actual return' }));
    expect(screen.getByRole('dialog', { name: 'Record actual return' })).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('Actual amount returned (PHP)'), { target: { value: '2000.00' } });
    fireEvent.click(screen.getByRole('button', { name: 'Confirm and record return' }));

    await waitFor(() => expect(onAction).toHaveBeenCalledWith(
      'record-royalty-return',
      expect.objectContaining({
        record_id: 'royalty-1',
        amount: '2000.00',
        return_date: expect.any(String),
        idempotency_key: expect.any(String),
      }),
    ));
  });

  it('filters records by status and offers a clear empty state', () => {
    render(<RoyaltiesWorkspace page={{ ...page, rows: [record] }} saving={false} onAction={vi.fn()} />);

    fireEvent.change(screen.getByLabelText('Status'), { target: { value: 'completed' } });

    expect(screen.getByText('No records match these filters.')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Clear filters' }));
    expect(within(screen.getByRole('region', { name: 'Royalty records' })).getByText('John')).toBeInTheDocument();
  });
});
