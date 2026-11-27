import Decimal from 'decimal.js';
import { desktopRequest } from './desktop';

interface ProjectRow {
  id: string;
  name: string;
  code: string;
}

interface ReceiptRow {
  id: string;
  project_id: string | null;
  receipt_date: string;
  created_at: string;
  discount: string | number;
  vat: string | number;
  additional_charges: string | number;
  receipt_items: Array<{
    quantity: string | number;
    unit_price: string | number;
  }> | null;
}

export interface ProjectSummary {
  id: string;
  name: string;
  code: string;
  receiptCount: number;
  spend: string;
}

export interface DashboardData {
  totalSpend: string;
  monthSpend: string;
  monthReceiptCount: number;
  totalReceiptCount: number;
  projectCount: number;
  projects: ProjectSummary[];
}

function decimal(value: string | number | null | undefined): Decimal {
  return new Decimal(value ?? 0);
}

export function receiptGrandTotal(receipt: ReceiptRow): Decimal {
  const subtotal = (receipt.receipt_items ?? []).reduce(
    (sum, item) => sum.plus(decimal(item.quantity).mul(decimal(item.unit_price))
      .toDecimalPlaces(2, Decimal.ROUND_HALF_UP)),
    new Decimal(0),
  ).toDecimalPlaces(2, Decimal.ROUND_HALF_UP);

  return subtotal
    .minus(decimal(receipt.discount))
    .plus(decimal(receipt.vat))
    .plus(decimal(receipt.additional_charges))
    .toDecimalPlaces(2, Decimal.ROUND_HALF_UP);
}

function localMonthKey(date: Date): string {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`;
}

export function summarizeDashboard(
  receipts: ReceiptRow[],
  projects: ProjectRow[],
  today = new Date(),
): DashboardData {
  let totalSpend = new Decimal(0);
  let monthSpend = new Decimal(0);
  let monthReceiptCount = 0;
  const monthKey = localMonthKey(today);
  const projectTotals = new Map<string, { receiptCount: number; spend: Decimal }>(
    projects.map((project) => [project.id, { receiptCount: 0, spend: new Decimal(0) }]),
  );

  for (const receipt of receipts) {
    const total = receiptGrandTotal(receipt);
    totalSpend = totalSpend.plus(total);

    if (receipt.receipt_date.slice(0, 7) === monthKey) {
      monthReceiptCount += 1;
      monthSpend = monthSpend.plus(total);
    }

    if (receipt.project_id) {
      const projectTotal = projectTotals.get(receipt.project_id);
      if (projectTotal) {
        projectTotal.receiptCount += 1;
        projectTotal.spend = projectTotal.spend.plus(total);
      }
    }
  }

  return {
    totalSpend: totalSpend.toFixed(2),
    monthSpend: monthSpend.toFixed(2),
    monthReceiptCount,
    totalReceiptCount: receipts.length,
    projectCount: projects.length,
    projects: projects.map((project) => {
      const totals = projectTotals.get(project.id);
      return {
        ...project,
        receiptCount: totals?.receiptCount ?? 0,
        spend: (totals?.spend ?? new Decimal(0)).toFixed(2),
      };
    }),
  };
}

export async function loadDashboardData(): Promise<DashboardData> {
 return desktopRequest<DashboardData>('/api/v1/dashboard');
}
