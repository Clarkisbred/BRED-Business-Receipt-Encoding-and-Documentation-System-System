import { describe, expect, it } from 'vitest';
import { formatFinancialMetric, formatPesoAmount } from './financialFormat';

describe('financial display formatting', () => {
  it('formats numeric peso amounts with grouping and two decimal places', () => {
    expect(formatPesoAmount('1000')).toBe('₱1,000.00');
    expect(formatPesoAmount('₱7,233.00')).toBe('₱7,233.00');
  });

  it('formats currency metrics without changing record counts or unavailable values', () => {
    expect(formatFinancialMetric('Receipt spend', '7233')).toBe('₱7,233.00');
    expect(formatFinancialMetric('Net payables', '1000')).toBe('₱1,000.00');
    expect(formatFinancialMetric('Total receipts', '3')).toBe('3');
    expect(formatFinancialMetric('Billing records', '12')).toBe('12');
    expect(formatFinancialMetric('Release publishing', 'Available')).toBe('Available');
  });
});
