import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { loadDashboardData } from '../api/dashboard';
import { Dashboard } from './Dashboard';

vi.mock('../api/dashboard', () => ({ loadDashboardData: vi.fn() }));

describe('Dashboard analytics', () => {
  it('renders chart panels from populated backend analytics instead of empty states', async () => {
    vi.mocked(loadDashboardData).mockResolvedValue({
      totalSpend: '167.00',
      monthSpend: '167.00',
      monthReceiptCount: 2,
      totalReceiptCount: 2,
      projectCount: 1,
      projects: [],
      totalRevenue: '300.00',
      totalExpenses: '167.00',
      netProfit: '133.00',
      outstandingPayables: '60.00',
      outstandingReceivables: '180.00',
      supplierCount: 1,
      monthly: [{
        month: '2026-03',
        revenue: '300.00',
        expenses: '167.00',
        profit: '133.00',
        cashFlow: '70.00',
      }],
      cashFlowAvailable: true,
      expenseBreakdown: [{ name: 'Official Receipt', amount: '167.00' }],
      projectMetrics: [{
        id: 'project-1',
        name: 'Bridge Alpha',
        budget: '1000.00',
        actual: '1250.00',
        progress: '125.00',
      }],
    });

    render(<Dashboard />);

    expect(await screen.findByText('Monthly Revenue vs Expenses')).toBeInTheDocument();
    expect(screen.queryByText('No financial records for this period.')).not.toBeInTheDocument();
    expect(screen.queryByText('No expense records.')).not.toBeInTheDocument();
    expect(screen.queryByText('No cash-flow records.')).not.toBeInTheDocument();
    expect(screen.queryByText('No project cost records.')).not.toBeInTheDocument();
    expect(screen.queryByText('No project progress records.')).not.toBeInTheDocument();
  });
});
