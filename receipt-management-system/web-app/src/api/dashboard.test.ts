import { describe, expect, it } from 'vitest';
import { receiptGrandTotal, summarizeDashboard } from './dashboard';

describe('dashboard totals', () => {
  it('matches Java receipt line rounding and summarizes current-month/project data', () => {
    const project = { id: 'project-1', name: 'North Wing', code: 'NW-01' };
    const receipts = [
      {
        id: 'receipt-current',
        project_id: project.id,
        receipt_date: '2026-11-20',
        created_at: '2026-11-20T08:00:00Z',
        discount: '0.01',
        vat: '0.36',
        additional_charges: '0.00',
        receipt_items: [
          { quantity: '1.500', unit_price: '2.01' },
          { quantity: '1.000', unit_price: '0.01' },
        ],
      },
      {
        id: 'receipt-old',
        project_id: null,
        receipt_date: '2026-10-10',
        created_at: '2026-10-10T08:00:00Z',
        discount: '0.00',
        vat: '0.00',
        additional_charges: '0.00',
        receipt_items: [{ quantity: '2.000', unit_price: '10.00' }],
      },
    ];

    expect(receiptGrandTotal(receipts[0]!).toFixed(2)).toBe('3.38');

    const summary = summarizeDashboard(receipts, [project], new Date(2026, 10, 27));
    expect(summary).toMatchObject({
      totalSpend: '23.38',
      monthSpend: '3.38',
      monthReceiptCount: 1,
      totalReceiptCount: 2,
      projectCount: 1,
    });
    expect(summary.projects).toEqual([
      { ...project, receiptCount: 1, spend: '3.38' },
    ]);
  });
});
