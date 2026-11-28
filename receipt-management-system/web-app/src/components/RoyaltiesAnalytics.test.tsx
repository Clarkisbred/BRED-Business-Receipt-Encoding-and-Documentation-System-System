import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import type { ModuleRecord } from '../api/modules';
import { RoyaltiesAnalytics } from './RoyaltiesAnalytics';

const records: ModuleRecord[] = [
  {
    id: 'royalty-feb-1',
    values: {
      client_name: 'Acme',
      billing_date: '2026-02-10',
      billing_amount: '2000.00',
      royalty_amount: '60.00',
      returned_amount: '10.00',
    },
  },
  {
    id: 'royalty-feb-2',
    values: {
      client_name: 'Acme',
      billing_date: '2026-02-18',
      billing_amount: '1000.00',
      royalty_amount: '30.00',
      returned_amount: '20.00',
    },
  },
  {
    id: 'royalty-mar',
    values: {
      client_name: 'Beta',
      billing_date: '2026-03-05',
      billing_amount: '4000.00',
      royalty_amount: '120.00',
      returned_amount: '40.00',
    },
  },
];

describe('RoyaltiesAnalytics', () => {
  afterEach(cleanup);

  it('shows a market trend with monthly statistics and an alternate bar graph', () => {
    render(<RoyaltiesAnalytics records={records} />);

    const analytics = screen.getByRole('region', { name: 'Royalty analytics' });
    expect(within(analytics).getByRole('img', {
      name: /Feb 26, royalty accrued ₱90\.00, returns ₱30\.00/,
    })).toBeInTheDocument();
    expect(within(analytics).getByText('Months with records').nextElementSibling).toHaveTextContent('2');
    expect(within(analytics).getByText('Gross billing').nextElementSibling).toHaveTextContent('₱7,000.00');
    expect(within(analytics).getByText('Average per active month').nextElementSibling).toHaveTextContent('₱105.00');
    expect(within(analytics).getByText('Peak royalty month').nextElementSibling).toHaveTextContent('Mar 26 · ₱120.00');

    fireEvent.click(within(analytics).getByRole('button', { name: 'Monthly bars' }));
    expect(within(analytics).getByRole('img', { name: /Monthly royalty comparison chart/ })).toBeInTheDocument();
  });

  it('switches to client distribution and handles records without amounts or dates', () => {
    const { unmount } = render(<RoyaltiesAnalytics records={records} />);
    const analytics = screen.getByRole('region', { name: 'Royalty analytics' });

    fireEvent.click(within(analytics).getByRole('button', { name: 'Client mix' }));
    const legend = within(analytics).getByRole('list', { name: 'Royalty amounts by client' });
    expect(within(legend).getByText('Acme')).toBeInTheDocument();
    expect(within(legend).getByText('₱90.00')).toBeInTheDocument();
    expect(within(analytics).getByRole('img', { name: /Royalty distribution chart: .*Acme/ })).toBeInTheDocument();

    unmount();
    const noBillingDate = { ...records[0]!, id: 'legacy-record', values: { ...records[0]!.values, billing_date: '', royalty_amount: '0' } };
    const { rerender } = render(<RoyaltiesAnalytics records={[noBillingDate]} />);
    expect(screen.getByText('Add royalty records with billing dates to see monthly trends.')).toBeInTheDocument();

    rerender(<RoyaltiesAnalytics records={[]} />);
    fireEvent.click(screen.getByRole('button', { name: 'Client mix' }));
    expect(screen.getByText('Client distribution will appear after royalty amounts are saved.')).toBeInTheDocument();
  });
});
