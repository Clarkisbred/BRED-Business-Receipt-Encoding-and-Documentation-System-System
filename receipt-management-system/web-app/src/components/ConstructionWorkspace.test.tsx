import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ConstructionWorkspace } from './ConstructionWorkspace';
import type { ModulePageData } from '../api/modules';

const { loadConstructionPage, saveModuleRecord } = vi.hoisted(() => ({
  loadConstructionPage: vi.fn(),
  saveModuleRecord: vi.fn(),
}));

vi.mock('../api/modules', () => ({ loadConstructionPage, saveModuleRecord }));

const categories = [
  {
    title: 'Projects and Planning',
    tabs: [{ title: 'Contracts', table: 'project_contracts' }],
  },
  {
    title: 'Billing and Finance',
    tabs: [
      { title: 'Billings', table: 'project_billings' },
      { title: 'Payables', table: 'project_payables' },
      { title: 'Royalties', table: 'royalty_records' },
    ],
  },
];

const contractsPage: ModulePageData = {
  key: 'CONSTRUCTION',
  title: 'Contracts',
  description: 'Project contracts.',
  metrics: [
    {
      label: 'Gross billings',
      value: '₱125,000.00',
      note: 'By project',
      chart: [{ label: 'Bridge Alpha', value: 125000 }],
    },
    { label: 'Collected', value: '₱0.00', note: 'By project', chart: [] },
  ],
  columns: [{ key: 'contract_name', label: 'Contract name' }],
  fields: [{ key: 'contract_name', label: 'Contract name', type: 'text', required: true }],
  rows: [],
  canCreate: true,
  canEdit: true,
  canDelete: true,
  primaryActionLabel: 'Add record',
  submitLabel: 'Save record',
  createAction: 'save',
  constructionCategories: categories,
};

const payablesPage: ModulePageData = {
  ...contractsPage,
  title: 'Payables',
  description: 'Project payables.',
  columns: [{ key: 'reference', label: 'Reference' }, { key: 'amount', label: 'Amount' }],
  fields: [],
  rows: [{ id: 'payable-1', values: { reference: 'Concrete invoice', amount: null } }],
};

const billingPage: ModulePageData = {
  ...contractsPage,
  title: 'Billings',
  description: 'Manage project billings.',
  columns: [],
  fields: [],
  rows: [],
};

const royaltiesPage: ModulePageData = {
  ...payablesPage,
  title: 'Royalties',
  description: 'Manage royalty records.',
  columns: [],
  fields: [],
  rows: [],
};

describe('ConstructionWorkspace', () => {
  afterEach(() => cleanup());

  beforeEach(() => {
    loadConstructionPage.mockReset().mockImplementation((table: string) => Promise.resolve(
      table === 'project_billings' ? billingPage
        : table === 'project_payables' ? payablesPage
          : table === 'royalty_records' ? royaltiesPage : contractsPage,
    ));
    saveModuleRecord.mockReset().mockResolvedValue({
      ...contractsPage,
      rows: [{ id: 'contract-1', values: { contract_name: 'North site contract' } }],
    });
  });

  it('switches between Java construction categories and loads the selected table', async () => {
    render(<ConstructionWorkspace />);

    expect(await screen.findByText('No contracts records yet')).toBeInTheDocument();
    expect(screen.getByRole('img', { name: /Gross billings By project: Bridge Alpha/ })).toBeInTheDocument();
    expect(screen.getByRole('img', { name: 'Collected: no breakdown data' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Billing and Finance' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Projects and Planning/ })).toBeInTheDocument();
  });

  it('formats raw financial summary values as Philippine pesos', async () => {
    loadConstructionPage.mockResolvedValueOnce({
      ...contractsPage,
      metrics: [{ ...contractsPage.metrics[0]!, value: '125000' }],
    });

    render(<ConstructionWorkspace />);

    expect(await screen.findByText('₱125,000.00')).toBeInTheDocument();
  });

  it('creates a construction record using the selected table and entered fields', async () => {
    render(<ConstructionWorkspace />);
    await screen.findByText('No contracts records yet');
    fireEvent.click(screen.getByRole('button', { name: 'Add record' }));
    expect(screen.getByRole('dialog', { name: 'New Contracts' })).toBeInTheDocument();
    fireEvent.change(screen.getByRole('textbox', { name: /Contract name/ }), {
      target: { value: 'North site contract' },
    });

    fireEvent.click(screen.getByRole('button', { name: 'Save record' }));

    await waitFor(() => expect(saveModuleRecord).toHaveBeenCalledWith(
      'CONSTRUCTION',
      'save',
      { contract_name: 'North site contract', table: 'project_contracts' },
      undefined,
    ));
    expect(await screen.findByText('North site contract')).toBeInTheDocument();
  });

  it('opens Billing & Finance directly with its sections and no nested Royalties entry', async () => {
    render(<ConstructionWorkspace view="billing" />);

    expect(await screen.findByText('No billing records for this project yet.')).toBeInTheDocument();
    expect(loadConstructionPage).toHaveBeenLastCalledWith('project_billings');
    const financialSections = screen.getByRole('navigation', { name: 'Billing and Finance sections' });
    expect(within(financialSections).getByRole('button', { name: 'Billings' })).toHaveAttribute('aria-current', 'page');
    expect(within(financialSections).getByRole('button', { name: 'Payables' })).toBeInTheDocument();
    expect(within(financialSections).queryByRole('button', { name: 'Royalties' })).not.toBeInTheDocument();

    fireEvent.click(within(financialSections).getByRole('button', { name: 'Payables' }));
    expect(await screen.findByText('Concrete invoice')).toBeInTheDocument();
    expect(loadConstructionPage).toHaveBeenLastCalledWith('project_payables');
  });

  it('opens Royalties as its own financial destination', async () => {
    render(<ConstructionWorkspace view="royalties" />);

    expect(await screen.findByRole('heading', { name: 'Royalties' })).toBeInTheDocument();
    expect(loadConstructionPage).toHaveBeenLastCalledWith('royalty_records');
    expect(screen.getByRole('button', { name: 'Company settings' })).toBeInTheDocument();
    expect(screen.queryByRole('navigation', { name: 'Construction categories' })).not.toBeInTheDocument();
  });

  it('renders an explicit recoverable load error', async () => {
    loadConstructionPage.mockRejectedValueOnce(new Error('Construction service unavailable.'));
    render(<ConstructionWorkspace />);

    expect(await screen.findByRole('alert')).toHaveTextContent('Construction service unavailable.');
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument();
  });
});
