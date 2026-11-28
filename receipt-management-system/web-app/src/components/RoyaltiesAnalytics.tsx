import { useMemo, useState } from 'react';
import {
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  Legend,
  Line,
  LineChart,
  Pie,
  PieChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import type { ModuleRecord } from '../api/modules';

type ChartMode = 'trend' | 'monthly-bars' | 'clients';
type MonthlyPoint = {
  key: string;
  label: string;
  records: number;
  billing: number;
  royalty: number;
  returned: number;
};
type ClientPoint = { name: string; value: number };

function valueText(value: unknown): string {
  return value == null ? '' : String(value);
}

function amount(value: unknown): number {
  const parsed = Number(valueText(value).replace(/[,\s₱]/g, ''));
  return Number.isFinite(parsed) ? parsed : 0;
}

function formatPeso(value: string | number): string {
  return new Intl.NumberFormat('en-PH', {
    style: 'currency',
    currency: 'PHP',
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(Number(value));
}

function formatCompactPeso(value: number): string {
  return new Intl.NumberFormat('en-PH', {
    style: 'currency',
    currency: 'PHP',
    notation: 'compact',
    maximumFractionDigits: 1,
  }).format(value);
}

function monthLabel(key: string): string {
  const [yearPart, monthPart] = key.split('-');
  const year = Number(yearPart);
  const month = Number(monthPart);
  return new Intl.DateTimeFormat('en-PH', {
    month: 'short',
    year: '2-digit',
    timeZone: 'UTC',
  }).format(new Date(Date.UTC(year, month - 1, 1)));
}

function RoyaltyChartTooltip({
  active,
  payload,
  label,
}: {
  active?: boolean;
  payload?: Array<{ name?: string; value?: number | string; color?: string }>;
  label?: string | number;
}) {
  if (!active || !payload?.length) return null;
  return (
    <div className="chart-tooltip">
      {label != null && <strong>{label}</strong>}
      {payload.map((point, index) => (
        <span key={`${point.name}-${index}`}>
          <i style={{ background: point.color }} />
          {point.name}: {formatPeso(typeof point.value === 'number' || typeof point.value === 'string'
            ? point.value
            : 0)}
        </span>
      ))}
    </div>
  );
}

function trendPoints(records: ModuleRecord[]): MonthlyPoint[] {
  const totals = new Map<string, MonthlyPoint>();
  for (const record of records) {
    const date = valueText(record.values.billing_date);
    const key = /^\d{4}-(0[1-9]|1[0-2])/.exec(date)?.[0];
    if (!key) continue;
    const point = totals.get(key) ?? {
      key,
      label: monthLabel(key),
      records: 0,
      billing: 0,
      royalty: 0,
      returned: 0,
    };
    point.records += 1;
    point.billing += amount(record.values.billing_amount);
    point.royalty += amount(record.values.royalty_amount);
    point.returned += amount(record.values.returned_amount);
    totals.set(key, point);
  }
  const sorted = Array.from(totals.values()).sort((left, right) => left.key.localeCompare(right.key));
  const latest = sorted[sorted.length - 1];
  if (!latest) return [];
  const [yearPart, monthPart] = latest.key.split('-');
  const year = Number(yearPart);
  const month = Number(monthPart);
  return Array.from({ length: 12 }, (_, index) => {
    const date = new Date(Date.UTC(year, month - 1 - (11 - index), 1));
    const key = `${date.getUTCFullYear()}-${String(date.getUTCMonth() + 1).padStart(2, '0')}`;
    return totals.get(key) ?? {
      key,
      label: monthLabel(key),
      records: 0,
      billing: 0,
      royalty: 0,
      returned: 0,
    };
  });
}

function clientPoints(records: ModuleRecord[]): ClientPoint[] {
  const totals = new Map<string, ClientPoint>();
  for (const record of records) {
    const royalty = amount(record.values.royalty_amount);
    if (royalty <= 0) continue;
    const name = valueText(record.values.client_name).trim() || 'Unnamed client';
    const key = name.toLocaleLowerCase();
    const existing = totals.get(key);
    totals.set(key, { name: existing?.name ?? name, value: (existing?.value ?? 0) + royalty });
  }
  const clients = Array.from(totals.values())
    .sort((left, right) => right.value - left.value);
  if (clients.length <= 6) return clients;
  return [
    ...clients.slice(0, 5),
    { name: 'Other clients', value: clients.slice(5).reduce((sum, client) => sum + client.value, 0) },
  ];
}

export function RoyaltiesAnalytics({ records }: { records: ModuleRecord[] }) {
  const [mode, setMode] = useState<ChartMode>('trend');
  const monthly = useMemo(() => trendPoints(records), [records]);
  const clients = useMemo(() => clientPoints(records), [records]);
  const recordedMonths = monthly.filter((point) => point.records > 0);
  const totalBilling = recordedMonths.reduce((sum, point) => sum + point.billing, 0);
  const totalRoyalties = recordedMonths.reduce((sum, point) => sum + point.royalty, 0);
  const averageMonthlyRoyalty = recordedMonths.length ? totalRoyalties / recordedMonths.length : 0;
  const peakMonth = recordedMonths.reduce<MonthlyPoint | null>(
    (peak, point) => !peak || point.royalty > peak.royalty ? point : peak,
    null,
  );

  const chartModes: Array<{ value: ChartMode; label: string }> = [
    { value: 'trend', label: 'Market trend' },
    { value: 'monthly-bars', label: 'Monthly bars' },
    { value: 'clients', label: 'Client mix' },
  ];

  return (
    <section className="royalties-chart-panel" aria-labelledby="royalties-chart-title">
      <div className="panel-heading">
        <div>
          <h3 id="royalties-chart-title">Royalty analytics</h3>
          <p>Most recent 12 billing months, royalty returns, and client distribution from saved records.</p>
        </div>
        <div className="royalties-chart-switcher" role="group" aria-label="Royalty chart type">
          {chartModes.map((chartMode) => (
            <button
              className={`button button-secondary${mode === chartMode.value ? ' is-selected' : ''}`}
              type="button"
              key={chartMode.value}
              aria-pressed={mode === chartMode.value}
              onClick={() => setMode(chartMode.value)}
            >
              {chartMode.label}
            </button>
          ))}
        </div>
      </div>

      {mode === 'clients' ? (
        clients.length === 0 ? (
          <div className="chart-empty">Client distribution will appear after royalty amounts are saved.</div>
        ) : (
          <div className="royalties-chart-content">
            <div
              className="chart-box chart-box-pie"
              role="img"
              aria-label={`Royalty distribution chart: ${clients
                .map((client) => `${client.name}, ${formatPeso(client.value)}`)
                .join('; ')}`}
            >
              <ResponsiveContainer width="100%" height="100%">
                <PieChart>
                  <Pie
                    data={clients}
                    dataKey="value"
                    nameKey="name"
                    innerRadius="54%"
                    outerRadius="78%"
                    paddingAngle={2}
                  >
                    {clients.map((client, index) => (
                      <Cell key={client.name} fill={`var(--chart-${(index % 5) + 1})`} />
                    ))}
                  </Pie>
                  <Tooltip content={<RoyaltyChartTooltip />} />
                </PieChart>
              </ResponsiveContainer>
            </div>
            <ul className="royalties-chart-legend" aria-label="Royalty amounts by client">
              {clients.map((client, index) => (
                <li key={client.name}>
                  <span>
                    <i
                      aria-hidden="true"
                      style={{ backgroundColor: `var(--chart-${(index % 5) + 1})` }}
                    />
                    {client.name}
                  </span>
                  <strong>{formatPeso(client.value)}</strong>
                </li>
              ))}
            </ul>
          </div>
        )
      ) : monthly.length === 0 ? (
        <div className="chart-empty">Add royalty records with billing dates to see monthly trends.</div>
      ) : (
        <>
          <div className="royalties-trend-stats" aria-label="Royalty trend statistics">
            <article>
              <span>Months with records</span>
              <strong>{recordedMonths.length}</strong>
            </article>
            <article>
              <span>Gross billing</span>
              <strong>{formatPeso(totalBilling)}</strong>
            </article>
            <article>
              <span>Average per active month</span>
              <strong>{formatPeso(averageMonthlyRoyalty)}</strong>
            </article>
            <article>
              <span>Peak royalty month</span>
              <strong>{peakMonth ? `${peakMonth.label} · ${formatPeso(peakMonth.royalty)}` : '—'}</strong>
            </article>
          </div>
          <div
            className="chart-box royalties-trend-chart"
            role="img"
            aria-label={`${mode === 'trend' ? 'Monthly royalty market trend' : 'Monthly royalty comparison chart'}: ${monthly
              .map((point) => `${point.label}, royalty accrued ${formatPeso(point.royalty)}, returns ${formatPeso(point.returned)}`)
              .join('; ')}`}
          >
            <ResponsiveContainer width="100%" height="100%">
              {mode === 'trend' ? (
                <LineChart data={monthly} margin={{ top: 12, right: 12, left: 8, bottom: 4 }}>
                  <CartesianGrid stroke="var(--line)" strokeDasharray="3 3" vertical={false} />
                  <XAxis dataKey="label" tick={{ fill: 'var(--muted)', fontSize: 11 }} tickLine={false} axisLine={{ stroke: 'var(--line)' }} />
                  <YAxis width={74} tickFormatter={formatCompactPeso} tick={{ fill: 'var(--muted)', fontSize: 10 }} tickLine={false} axisLine={false} />
                  <Tooltip content={<RoyaltyChartTooltip />} />
                  <Legend wrapperStyle={{ fontSize: 12 }} />
                  <Line type="monotone" dataKey="royalty" name="Royalty accrued" stroke="var(--chart-1)" strokeWidth={2.5} dot={{ r: 3 }} activeDot={{ r: 5 }} />
                  <Line type="monotone" dataKey="returned" name="Amount returned" stroke="var(--chart-2)" strokeWidth={2} dot={{ r: 3 }} activeDot={{ r: 5 }} />
                </LineChart>
              ) : (
                <BarChart data={monthly} margin={{ top: 12, right: 12, left: 8, bottom: 4 }}>
                  <CartesianGrid stroke="var(--line)" strokeDasharray="3 3" vertical={false} />
                  <XAxis dataKey="label" tick={{ fill: 'var(--muted)', fontSize: 11 }} tickLine={false} axisLine={{ stroke: 'var(--line)' }} />
                  <YAxis width={74} tickFormatter={formatCompactPeso} tick={{ fill: 'var(--muted)', fontSize: 10 }} tickLine={false} axisLine={false} />
                  <Tooltip content={<RoyaltyChartTooltip />} />
                  <Legend wrapperStyle={{ fontSize: 12 }} />
                  <Bar dataKey="royalty" name="Royalty accrued" fill="var(--chart-1)" radius={[4, 4, 0, 0]} />
                  <Bar dataKey="returned" name="Amount returned" fill="var(--chart-2)" radius={[4, 4, 0, 0]} />
                </BarChart>
              )}
            </ResponsiveContainer>
          </div>
        </>
      )}
    </section>
  );
}
