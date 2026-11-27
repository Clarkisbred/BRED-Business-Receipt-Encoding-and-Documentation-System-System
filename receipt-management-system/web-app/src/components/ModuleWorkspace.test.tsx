import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ModuleWorkspace } from './ModuleWorkspace';
import type { ModulePageData } from '../api/modules';

const { loadModulePage, saveModuleRecord, searchMapLocations } = vi.hoisted(() => ({
  loadModulePage: vi.fn(),
  saveModuleRecord: vi.fn(),
  searchMapLocations: vi.fn(),
}));

vi.mock('../api/modules', () => ({ loadModulePage, saveModuleRecord, searchMapLocations }));

const projectPage: ModulePageData = {
  key: 'PROJECTS',
  title: 'Projects',
  description: 'Manage project records.',
  metrics: [{ label: 'Total projects', value: '1' }],
  columns: [{ key: 'name', label: 'Project' }, { key: 'code', label: 'Code' }],
  fields: [{ key: 'name', label: 'Project name', type: 'text', required: true }],
  rows: [{ id: 'project-1', values: { name: 'North site', code: 'N-01' } }],
  canCreate: true,
  canEdit: true,
  canDelete: false,
  primaryActionLabel: 'New project',
  submitLabel: 'Save project',
  createAction: 'save',
};

const mapPage: ModulePageData = {
  key: 'LIVE_MAP',
  title: 'Live Project Map',
  description: 'Review project locations.',
  metrics: [],
  columns: [{ key: 'markerName', label: 'Location' }],
  fields: [{
    key: 'projectId',
    label: 'Project',
    type: 'select',
    options: [{ label: 'North site', value: 'project-1' }],
  }],
  rows: [],
  canCreate: true,
  canEdit: false,
  canDelete: false,
  primaryActionLabel: 'Add location',
  submitLabel: 'Save location',
  createAction: 'save',
};

const receiptPage: ModulePageData = {
  key: 'RECEIPTS',
  title: 'Receipts',
  description: 'Search encoded receipts.',
  metrics: [{ label: 'Total receipts', value: '2' }],
  columns: [
    { key: 'receiptNo', label: 'Receipt no.' },
    { key: 'date', label: 'Date' },
    { key: 'supplier', label: 'Supplier' },
    { key: 'project', label: 'Project' },
    { key: 'total', label: 'Total', numeric: true },
  ],
  fields: [],
  rows: [
    {
      id: 'receipt-1',
      values: {
        receiptNo: 'R-100',
        date: '2026-03-10',
        supplier: 'North Supply',
        supplierTin: '123-456',
        project: 'North site',
        itemDescription: 'Cement',
        attachmentNames: 'scan.pdf',
        total: '₱1,000.00',
      },
    },
    {
      id: 'receipt-2',
      values: {
        receiptNo: 'R-200',
        date: '2026-02-01',
        supplier: 'South Supply',
        project: 'South site',
        itemDescription: 'Steel',
        total: '₱2,000.00',
      },
    },
  ],
  canCreate: false,
  canEdit: false,
  canDelete: true,
  primaryActionLabel: '',
  submitLabel: '',
  createAction: 'save',
};

const constructionPage: ModulePageData = {
  key: 'CONSTRUCTION',
  title: 'Construction',
  description: 'Monitor project operations.',
  metrics: [],
  columns: [
    { key: 'recordType', label: 'Area' },
    { key: 'reference', label: 'Record' },
    { key: 'amount', label: 'Amount' },
  ],
  fields: [],
  rows: [
    { id: 'milestone-1', values: { recordType: 'Project milestones', reference: 'Foundation', amount: '₱1,000.00' } },
    { id: 'payable-1', values: { recordType: 'Project payables', reference: 'Concrete invoice', amount: '₱2,000.00' } },
  ],
  canCreate: false,
  canEdit: false,
  canDelete: false,
  primaryActionLabel: '',
  submitLabel: '',
  createAction: 'save',
};

const reportPage: ModulePageData = {
  key: 'REPORTS',
  title: 'Reports',
  description: 'Review grouped receipt totals.',
  metrics: [],
  columns: [
    { key: 'report', label: 'Report' },
    { key: 'label', label: 'Group' },
    { key: 'amount', label: 'Total', numeric: true },
  ],
  fields: [],
  rows: [
    { id: 'project', values: { report: 'By project', label: 'North site', amount: '₱1,000.00' } },
    { id: 'supplier', values: { report: 'By supplier', label: 'North Supply', amount: '₱2,000.00' } },
    { id: 'month', values: { report: 'By month', label: '2026-03', amount: '₱3,000.00' } },
  ],
  canCreate: false,
  canEdit: false,
  canDelete: false,
  primaryActionLabel: '',
  submitLabel: '',
  createAction: 'save',
};

const settingsPage: ModulePageData = {
  key: 'SETTINGS',
  title: 'Settings',
  description: 'Manage your BRED account, appearance, and support requests.',
  metrics: [
    { label: 'Account', value: 'Clark Hilario' },
    { label: 'Role', value: 'Creator' },
    { label: 'Company', value: 'All companies' },
    { label: 'Installed version', value: '1.3.0' },
    { label: 'Latest release', value: '1.3.0' },
    { label: 'Bug report submissions', value: 'Available' },
    { label: 'Release publishing', value: 'Available' },
  ],
  columns: [],
  fields: [
    {
      key: 'appearance',
      label: 'Theme preset',
      type: 'select',
      required: true,
      options: [
        { label: 'Modern Glass', value: 'MODERN_GLASS' },
        { label: 'Clean Light', value: 'CLEAN_LIGHT' },
      ],
      value: 'MODERN_GLASS',
    },
    { key: 'dark', label: 'Dark appearance', type: 'checkbox', value: false },
    { key: 'newPassword', label: 'New password (optional)', type: 'password' },
    { key: 'confirmPassword', label: 'Confirm new password', type: 'password' },
  ],
  rows: [],
  canCreate: false,
  canEdit: false,
  canDelete: false,
  primaryActionLabel: '',
  submitLabel: 'Save preferences',
  createAction: 'save',
};

describe('ModuleWorkspace', () => {
  afterEach(() => cleanup());

  beforeEach(() => {
    loadModulePage.mockReset().mockResolvedValue(projectPage);
    searchMapLocations.mockReset().mockResolvedValue([]);
    saveModuleRecord.mockReset().mockResolvedValue({
      ...projectPage,
      rows: [...projectPage.rows, { id: 'project-2', values: { name: 'South site', code: 'S-02' } }],
    });
  });

  it('loads real workspace rows and filters them without altering the source data', async () => {
    render(<ModuleWorkspace module="PROJECTS" />);

    expect(await screen.findAllByText('North site')).not.toHaveLength(0);
    fireEvent.change(screen.getByRole('textbox', { name: 'Search records' }), {
      target: { value: 'south' },
    });

    expect(screen.getByText('No matching records')).toBeInTheDocument();
    expect(screen.queryByText('North site')).not.toBeInTheDocument();
  });

  it('submits a new record through the Java-backed module API', async () => {
    render(<ModuleWorkspace module="PROJECTS" />);
    await screen.findByText('North site');

    fireEvent.click(screen.getByRole('button', { name: 'New project' }));
    fireEvent.change(screen.getByLabelText(/Project name/), {
      target: { value: 'South site' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Save project' }));

    await waitFor(() => expect(saveModuleRecord).toHaveBeenCalledWith(
      'PROJECTS',
      'save',
      { name: 'South site' },
      undefined,
    ));
    expect(await screen.findByText('South site')).toBeInTheDocument();
  });

  it('offers retry when the module data request fails', async () => {
    loadModulePage.mockRejectedValueOnce(new Error('Service unavailable.'));
    render(<ModuleWorkspace module="PROJECTS" />);

    expect(await screen.findByText('Service unavailable.')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /Retry/ }));

    expect(await screen.findByText('North site')).toBeInTheDocument();
    expect(loadModulePage).toHaveBeenCalledTimes(2);
  });

  it('searches Nominatim through the Java API and saves a selected place as a marker', async () => {
    loadModulePage.mockResolvedValue(mapPage);
    searchMapLocations.mockResolvedValue([{
      displayName: 'Quezon City, Metro Manila, Philippines',
      latitude: '14.6760',
      longitude: '121.0437',
      type: 'city',
    }]);
    saveModuleRecord.mockResolvedValue({
      ...mapPage,
      rows: [{ id: 'marker-1', values: { markerName: 'Quezon City, Metro Manila, Philippines' } }],
    });
    render(<ModuleWorkspace module="LIVE_MAP" />);

    await screen.findByText('Live Project Map');
    fireEvent.change(screen.getByRole('textbox', { name: 'Address or place' }), {
      target: { value: 'Quezon City' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Search places' }));

    expect(await screen.findByText('Quezon City, Metro Manila, Philippines')).toBeInTheDocument();
    expect(searchMapLocations).toHaveBeenCalledWith('Quezon City');
    fireEvent.click(screen.getByRole('button', { name: 'Add marker' }));
    await waitFor(() => expect(saveModuleRecord).toHaveBeenCalledWith(
      'LIVE_MAP',
      'save',
      {
        markerName: 'Quezon City, Metro Manila, Philippines',
        latitude: '14.6760',
        longitude: '121.0437',
        projectId: '',
        status: 'Verified',
      },
    ));
    expect(await screen.findByText('Quezon City, Metro Manila, Philippines')).toBeInTheDocument();
  });

  it('filters receipts by date and supplier and opens real receipt details', async () => {
    loadModulePage.mockResolvedValueOnce(receiptPage);
    render(<ModuleWorkspace module="RECEIPTS" />);

    expect(await screen.findByRole('button', { name: 'View receipt R-100' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'View receipt R-200' })).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText('Receipt date from'), {
      target: { value: '2026-03-01' },
    });
    fireEvent.change(screen.getByRole('combobox', { name: 'Filter by supplier' }), {
      target: { value: 'North Supply' },
    });

    expect(screen.getByRole('button', { name: 'View receipt R-100' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'View receipt R-200' })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'View receipt R-100' }));
    expect(screen.getByRole('heading', { name: 'R-100' })).toBeInTheDocument();
    expect(screen.getByText('Cement')).toBeInTheDocument();
    expect(screen.getByText('scan.pdf')).toBeInTheDocument();
  });

  it('filters construction records by their Java workspace area', async () => {
    loadModulePage.mockResolvedValueOnce(constructionPage);
    render(<ModuleWorkspace module="CONSTRUCTION" />);

    expect(await screen.findByText('Foundation')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /Project payables/ }));

    expect(screen.getByText('Concrete invoice')).toBeInTheDocument();
    expect(screen.queryByText('Foundation')).not.toBeInTheDocument();
  });

  it('switches report groupings without changing the source report records', async () => {
    loadModulePage.mockResolvedValueOnce(reportPage);
    render(<ModuleWorkspace module="REPORTS" />);

    expect(await screen.findAllByText('North site')).not.toHaveLength(0);
    fireEvent.click(screen.getByRole('button', { name: 'By supplier' }));

    expect(screen.getAllByText('North Supply')).not.toHaveLength(0);
    expect(screen.queryByText('North site')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Export CSV' })).toBeEnabled();
  });

  it('saves the selected theme preset and light or dark mode from Settings', async () => {
    loadModulePage.mockResolvedValueOnce(settingsPage);
    saveModuleRecord.mockResolvedValue(settingsPage);
    render(<ModuleWorkspace module="SETTINGS" />);

    expect(await screen.findByText('Installed version')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'clarkhilario.bread@gmail.com' })).toHaveAttribute(
      'href',
      'mailto:clarkhilario.bread@gmail.com',
    );
    fireEvent.change(screen.getByRole('combobox', { name: 'Theme preset' }), {
      target: { value: 'CLEAN_LIGHT' },
    });
    fireEvent.click(screen.getByRole('checkbox', { name: 'Dark mode' }));
    fireEvent.click(screen.getByRole('button', { name: 'Save appearance' }));

    await waitFor(() => expect(saveModuleRecord).toHaveBeenCalledWith(
      'SETTINGS',
      'save',
      {
        appearance: 'CLEAN_LIGHT',
        dark: true,
        newPassword: '',
        confirmPassword: '',
      },
    ));
    expect(await screen.findByText('Your preferences were saved.')).toBeInTheDocument();
  });

  it('submits a bug report and shows Creator-only submitted reports', async () => {
    loadModulePage.mockResolvedValueOnce(settingsPage);
    saveModuleRecord.mockResolvedValue({
      ...settingsPage,
      rows: [{
        id: 'report-1',
        values: {
          title: 'Settings issue',
          company: 'Northstar',
          reporter: 'Clark Hilario',
          email: 'creator@example.test',
          description: 'The theme did not save.',
          createdAt: '2026-11-28T00:00:00Z',
        },
      }],
    });
    render(<ModuleWorkspace module="SETTINGS" />);

    await screen.findByRole('heading', { name: 'Report a problem' });
    fireEvent.change(screen.getByLabelText('Report title'), {
      target: { value: 'Settings issue' },
    });
    fireEvent.change(screen.getByLabelText('What happened?'), {
      target: { value: 'The theme did not save.' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Submit bug report' }));

    await waitFor(() => expect(saveModuleRecord).toHaveBeenCalledWith(
      'SETTINGS',
      'report',
      { title: 'Settings issue', description: 'The theme did not save.' },
    ));
    expect(await screen.findByText('Your report was sent to the BRED Creator.')).toBeInTheDocument();
    expect(screen.getByText('Submitted reports')).toBeInTheDocument();
    expect(screen.getByText('The theme did not save.')).toBeInTheDocument();
  });

  it('lets the Creator publish a validated application version', async () => {
    loadModulePage.mockResolvedValueOnce(settingsPage);
    saveModuleRecord.mockResolvedValue(settingsPage);
    render(<ModuleWorkspace module="SETTINGS" />);

    expect(await screen.findByRole('heading', { name: 'Application version' })).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('Publish version'), {
      target: { value: '1.4.0' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Publish version' }));

    await waitFor(() => expect(saveModuleRecord).toHaveBeenCalledWith(
      'SETTINGS',
      'publish-release',
      { version: '1.4.0' },
    ));
    expect(await screen.findByText('Version 1.4.0 was published.')).toBeInTheDocument();
  });
});
