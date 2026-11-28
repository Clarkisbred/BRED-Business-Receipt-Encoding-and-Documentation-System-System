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

    expect(screen.getByText('John')).toBeInTheDocument();
    expect(screen.getByText('2025')).toBeInTheDocument();
    expect(screen.getByText('March')).toBeInTheDocument();
    fireEvent.click(screen.getByText('March'));

    const table = screen.getByRole('table');
    expect(within(table).getByText('Warehouse Construction')).toBeInTheDocument();
    expect(within(table).getAllByText('₱7,636.48')).toHaveLength(2);
    expect(within(table).getAllByText('₱5,090.98')).toHaveLength(2);
  });

  it('previews the reference calculation and saves a standalone pending royalty record', async () => {
    const onAction = vi.fn().mockResolvedValue(page);
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    render(<RoyaltiesWorkspace page={page} saving={false} onAction={onAction} />);

    fireEvent.click(screen.getByRole('button', { name: 'New royalty record' }));
    fireEvent.change(screen.getByLabelText('Client / payee name *'), { target: { value: 'John' } });
    fireEvent.change(screen.getByLabelText('Billing amount (PHP) *'), { target: { value: '254549.21' } });
    fireEvent.change(screen.getByLabelText('Billing VAT treatment *'), { target: { value: 'NON_VAT' } });

    expect(screen.getByText('− ₱7,636.48')).toBeInTheDocument();
    expect(screen.getByText('− ₱5,090.98')).toBeInTheDocument();
    expect(screen.getByText('₱241,821.75')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Save royalty record' }));

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
    expect(screen.getByText('John')).toBeInTheDocument();
  });
});
