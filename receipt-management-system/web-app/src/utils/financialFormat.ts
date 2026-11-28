const countMetricLabel = /\b(?:records?|count)\b|^\s*(?:total\s+)?receipts?\s*$/i;
const currencyMetricLabel = /\b(?:amount|billing|billings|collected|income|payables?|spend|expenses?|revenue|royalt(?:y|ies)|withheld|withholding|ewt|retention|vat|tax|returns?|profit|loss|payment|receivable|budget|contract)\b/i;

export function formatPesoAmount(value: string | number): string {
  const raw = String(value).trim();
  const normalized = raw.replace(/[₱,\s]/g, '');
  const amount = Number(normalized);
  if (!normalized || !Number.isFinite(amount)) return raw;
  return new Intl.NumberFormat('en-PH', {
    style: 'currency',
    currency: 'PHP',
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(amount);
}

export function formatFinancialMetric(label: string, value: string): string {
  if (countMetricLabel.test(label) || !currencyMetricLabel.test(label)) return value;
  return formatPesoAmount(value);
}
