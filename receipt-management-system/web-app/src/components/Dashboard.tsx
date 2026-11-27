import { useEffect, useMemo, useState } from 'react';
import { format } from 'date-fns';
import {
  Area,
  AreaChart,
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
import { AlertCircle, RefreshCw } from 'lucide-react';
import { loadDashboardData, type DashboardData } from '../api/dashboard';

type DashboardState =
  | { status: 'loading' }
  | { status: 'error' }
  | { status: 'ready'; data: DashboardData };

type RangeView = 'monthly' | 'quarterly' | 'yearly';

function formatPeso(value: string | number | null | undefined): string {
  const amount = Number(value ?? 0);
  return new Intl.NumberFormat('en-PH', {
    style: 'currency',
    currency: 'PHP',
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(Number.isFinite(amount) ? amount : 0);
}

function formatCompactPeso(value: number): string {
  return new Intl.NumberFormat('en-PH', {
    style: 'currency',
    currency: 'PHP',
    notation: 'compact',
    maximumFractionDigits: 1,
  }).format(value);
}

function number(value: string | number | null | undefined): number {
  const parsed = Number(value ?? 0);
  return Number.isFinite(parsed) ? parsed : 0;
}

function ChartTooltip({ active, payload, label }: {
  active?: boolean;
  payload?: Array<{ name?: string; value?: number | string; color?: string }>;
  label?: string | number;
}) {
  if (!active || !payload?.length) return null;
  return (
    <div className="chart-tooltip">
      {label != null && <strong>{label}</strong>}
      {payload.map((item, index) => (
        <span key={`${item.name}-${index}`}>
          <i style={{ background: item.color }} />
          {item.name}: {formatPeso(item.value)}
        </span>
      ))}
    </div>
  );
}

function EmptyState({ message }: { message: string }) {
  return <div className="chart-empty">{message}</div>;
}

function aggregateRange(
  monthly: DashboardData['monthly'],
  view: RangeView,
) {
  const rows = (monthly ?? []).map((row) => ({
    month: row.month,
    revenue: number(row.revenue),
    expenses: number(row.expenses),
    profit: number(row.profit),
    cashFlow: number(row.cashFlow),
  }));
  if (view === 'monthly') return rows;

  if (view === 'yearly') {
    const map = new Map<string, { month: string; revenue: number; expenses: number; profit: number; cashFlow: number }>();
    rows.forEach((row) => {
      const key = row.month.slice(0, 4);
      const current = map.get(key) ?? { month: key, revenue: 0, expenses: 0, profit: 0, cashFlow: 0 };
      current.revenue += row.revenue;
      current.expenses += row.expenses;
      current.profit += row.profit;
      current.cashFlow += row.cashFlow;
      map.set(key, current);
    });
    return Array.from(map.values());
  }

  const map = new Map<string, { month: string; revenue: number; expenses: number; profit: number; cashFlow: number }>();
  rows.forEach((row) => {
    const [year, month] = row.month.split('-').map(Number);
    const quarter = Math.floor(((month ?? 1) - 1) / 3) + 1;
    const key = `${year}-Q${quarter}`;
    const current = map.get(key) ?? { month: key, revenue: 0, expenses: 0, profit: 0, cashFlow: 0 };
    current.revenue += row.revenue;
    current.expenses += row.expenses;
    current.profit += row.profit;
    current.cashFlow += row.cashFlow;
    map.set(key, current);
  });
  return Array.from(map.values());
}

export function Dashboard() {
  const [attempt, setAttempt] = useState(0);
  const [view, setView] = useState<RangeView>('monthly');
  const [state, setState] = useState<DashboardState>({ status: 'loading' });

  useEffect(() => {
    let current = true;
    setState({ status: 'loading' });
    void loadDashboardData().then(
      (data) => {
        if (current) setState({ status: 'ready', data });
      },
      () => {
        if (current) setState({ status: 'error' });
      },
    );
    return () => {
      current = false;
    };
  }, [attempt]);

  const data = state.status === 'ready' ? state.data : null;
  const rangeRows = useMemo(() => aggregateRange(data?.monthly, view), [data, view]);
  const expenseRows = (data?.expenseBreakdown ?? []).map((row) => ({
    name: row.name,
    value: number(row.amount),
  }));
  const projectRows = (data?.projectMetrics ?? []).map((row) => ({
    name: row.name,
    budget: number(row.budget),
    actual: number(row.actual),
    progress: number(row.progress),
  }));

  const stats = data ? [
    { label: 'Total Revenue', value: formatPeso(data.totalRevenue ?? 0) },
    { label: 'Total Expenses', value: formatPeso(data.totalExpenses ?? data.totalSpend) },
    { label: 'Net Profit', value: formatPeso(data.netProfit ?? 0), trend: number(data.netProfit) >= 0 },
    { label: 'Outstanding Payables', value: formatPeso(data.outstandingPayables ?? 0) },
    { label: 'Outstanding Receivables', value: formatPeso(data.outstandingReceivables ?? 0) },
    { label: 'Active Projects', value: (data.projectCount ?? 0).toLocaleString('en-PH') },
    { label: 'Total Receipts', value: (data.totalReceiptCount ?? 0).toLocaleString('en-PH') },
    { label: 'Total Suppliers', value: (data.supplierCount ?? 0).toLocaleString('en-PH') },
  ] : [
    { label: 'Total Revenue', value: '–' },
    { label: 'Total Expenses', value: '–' },
    { label: 'Net Profit', value: '–' },
    { label: 'Outstanding Payables', value: '–' },
    { label: 'Outstanding Receivables', value: '–' },
    { label: 'Active Projects', value: '–' },
    { label: 'Total Receipts', value: '–' },
    { label: 'Total Suppliers', value: '–' },
  ];

  return (
    <section className="dashboard-page" aria-labelledby="dashboard-title">
      <header className="page-heading dashboard-heading">
        <div>
          <p className="eyebrow">WORKSPACE</p>
          <h1 id="dashboard-title">Dashboard</h1>
          <p className="page-subtitle">Authorized financial and construction overview.</p>
        </div>
        <div className="dashboard-range">
          {(['monthly', 'quarterly', 'yearly'] as RangeView[]).map((option) => (
            <button
              key={option}
              type="button"
              className={view === option ? 'range-button is-active' : 'range-button'}
              onClick={() => setView(option)}
            >
              {option.charAt(0).toUpperCase() + option.slice(1)}
            </button>
          ))}
        </div>
      </header>

      {state.status === 'error' ? (
        <div className="notice notice-error" role="alert">
          <AlertCircle aria-hidden="true" size={20} />
          <div>
            <strong>Dashboard data is unavailable.</strong>
            <p>Check the BRED desktop service, then retry.</p>
          </div>
          <button className="button button-secondary" type="button" onClick={() => setAttempt((value) => value + 1)}>
            <RefreshCw aria-hidden="true" size={16} />
            Retry
          </button>
        </div>
      ) : (
        <>
          <div className="stat-grid enterprise-stat-grid" aria-live="polite">
            {stats.map((stat) => (
              <article className="stat-card" key={stat.label}>
                <span className="stat-label">{stat.label}</span>
                <strong className={stat.trend === true ? 'stat-value profit' : stat.trend === false ? 'stat-value loss' : 'stat-value'}>
                  {stat.value}
                </strong>
                {stat.trend !== undefined && (
                  <span className={stat.trend ? 'trend trend-profit' : 'trend trend-loss'}>
                    {stat.trend ? '↑ Profit' : '↓ Loss'}
                  </span>
                )}
              </article>
            ))}
          </div>

          <div className="analytics-grid">
            <section className="panel chart-panel chart-wide">
              <div className="panel-heading">
                <div>
                  <h2>Monthly Revenue vs Expenses</h2>
                  <p>Revenue, spending, and net movement.</p>
                </div>
              </div>
              {rangeRows.length === 0 ? <EmptyState message="No financial records for this period." /> : (
                <div className="chart-box">
                  <ResponsiveContainer width="100%" height="100%">
                    <BarChart data={rangeRows}>
                      <CartesianGrid stroke="var(--line)" strokeDasharray="3 3" vertical={false} />
                      <XAxis dataKey="month" tick={{ fill: 'var(--muted)', fontSize: 11 }} />
                      <YAxis tickFormatter={formatCompactPeso} tick={{ fill: 'var(--muted)', fontSize: 11 }} />
                      <Tooltip content={<ChartTooltip />} />
                      <Legend />
                      <Bar dataKey="revenue" name="Revenue" fill="var(--chart-1)" radius={[4, 4, 0, 0]} />
                      <Bar dataKey="expenses" name="Expenses" fill="var(--chart-4)" radius={[4, 4, 0, 0]} />
                    </BarChart>
                  </ResponsiveContainer>
                </div>
              )}
            </section>

            <section className="panel chart-panel">
              <div className="panel-heading">
                <div><h2>Expense Breakdown</h2><p>Recorded receipt spending by document type.</p></div>
              </div>
              {expenseRows.length === 0 ? <EmptyState message="No expense records." /> : (
                <div className="chart-box chart-box-pie">
                  <ResponsiveContainer width="100%" height="100%">
                    <PieChart>
                      <Pie data={expenseRows} dataKey="value" nameKey="name" innerRadius="58%" outerRadius="82%" paddingAngle={2}>
                        {expenseRows.map((row, index) => (
                          <Cell key={row.name} fill={`var(--chart-${(index % 5) + 1})`} />
                        ))}
                      </Pie>
                      <Tooltip formatter={(value) =>
                        formatPeso(typeof value === 'number' || typeof value === 'string' ? value : 0)
                      } />
                      <Legend />
                    </PieChart>
                  </ResponsiveContainer>
                </div>
              )}
            </section>

            <section className="panel chart-panel">
              <div className="panel-heading">
                <div><h2>Revenue Trend</h2><p>Period-over-period revenue.</p></div>
              </div>
              {rangeRows.length === 0 ? <EmptyState message="No revenue records." /> : (
                <div className="chart-box">
                  <ResponsiveContainer width="100%" height="100%">
                    <LineChart data={rangeRows}>
                      <CartesianGrid stroke="var(--line)" strokeDasharray="3 3" vertical={false} />
                      <XAxis dataKey="month" tick={{ fill: 'var(--muted)', fontSize: 11 }} />
                      <YAxis tickFormatter={formatCompactPeso} tick={{ fill: 'var(--muted)', fontSize: 11 }} />
                      <Tooltip content={<ChartTooltip />} />
                      <Line
                        type="monotone"
                        dataKey="revenue"
                        name="Revenue"
                        stroke="var(--chart-1)"
                        strokeWidth={2.5}
                        dot={rangeRows.length === 1 ? { r: 3, fill: 'var(--chart-1)' } : false}
                      />
                    </LineChart>
                  </ResponsiveContainer>
                </div>
              )}
            </section>

            <section className="panel chart-panel">
              <div className="panel-heading">
                <div><h2>Cash Flow</h2><p>Collections less approved supplier payments.</p></div>
              </div>
              {rangeRows.length === 0 || !data?.cashFlowAvailable ? <EmptyState message="No cash-flow records." /> : (
                <div className="chart-box">
                  <ResponsiveContainer width="100%" height="100%">
                    <AreaChart data={rangeRows}>
                      <CartesianGrid stroke="var(--line)" strokeDasharray="3 3" vertical={false} />
                      <XAxis dataKey="month" tick={{ fill: 'var(--muted)', fontSize: 11 }} />
                      <YAxis tickFormatter={formatCompactPeso} tick={{ fill: 'var(--muted)', fontSize: 11 }} />
                      <Tooltip content={<ChartTooltip />} />
                      <Area
                        type="monotone"
                        dataKey="cashFlow"
                        name="Net cash flow"
                        stroke="var(--chart-2)"
                        fill="var(--chart-2)"
                        fillOpacity={0.18}
                        dot={rangeRows.length === 1 ? { r: 3, fill: 'var(--chart-2)' } : false}
                      />
                    </AreaChart>
                  </ResponsiveContainer>
                </div>
              )}
            </section>

            <section className="panel chart-panel">
              <div className="panel-heading">
                <div><h2>Profit and Loss</h2><p>Billed revenue less recorded receipt expenses.</p></div>
              </div>
              {rangeRows.length === 0 ? <EmptyState message="No profit and loss records." /> : (
                <div className="chart-box">
                  <ResponsiveContainer width="100%" height="100%">
                    <BarChart data={rangeRows}>
                      <CartesianGrid stroke="var(--line)" strokeDasharray="3 3" vertical={false} />
                      <XAxis dataKey="month" tick={{ fill: 'var(--muted)', fontSize: 11 }} />
                      <YAxis tickFormatter={formatCompactPeso} tick={{ fill: 'var(--muted)', fontSize: 11 }} />
                      <Tooltip content={<ChartTooltip />} />
                      <Bar dataKey="profit" name="Net Profit" radius={[4, 4, 0, 0]}>
                        {rangeRows.map((row) => (
                          <Cell key={row.month} fill={row.profit >= 0 ? 'var(--success)' : 'var(--danger)'} />
                        ))}
                      </Bar>
                    </BarChart>
                  </ResponsiveContainer>
                </div>
              )}
            </section>

            <section className="panel chart-panel chart-wide">
              <div className="panel-heading">
                <div><h2>Project Budget vs Actual</h2><p>Construction cost position by project.</p></div>
              </div>
              {projectRows.length === 0 ? <EmptyState message="No project cost records." /> : (
                <div className="chart-box">
                  <ResponsiveContainer width="100%" height="100%">
                    <BarChart data={projectRows} layout="vertical">
                      <CartesianGrid stroke="var(--line)" strokeDasharray="3 3" horizontal={false} />
                      <XAxis type="number" tickFormatter={formatCompactPeso} tick={{ fill: 'var(--muted)', fontSize: 11 }} />
                      <YAxis type="category" dataKey="name" width={120} tick={{ fill: 'var(--muted)', fontSize: 11 }} />
                      <Tooltip content={<ChartTooltip />} />
                      <Legend />
                      <Bar dataKey="budget" name="Budget" fill="var(--chart-1)" radius={[0, 4, 4, 0]} />
                      <Bar dataKey="actual" name="Actual" fill="var(--chart-3)" radius={[0, 4, 4, 0]} />
                    </BarChart>
                  </ResponsiveContainer>
                </div>
              )}
            </section>

            <section className="panel chart-panel">
              <div className="panel-heading">
                <div><h2>Project Progress</h2><p>Actual spend relative to BOQ budget.</p></div>
              </div>
              {projectRows.length === 0 ? <EmptyState message="No project progress records." /> : (
                <div className="progress-list">
                  {projectRows.map((project) => (
                    <div className="progress-item" key={project.name}>
                      <div><span>{project.name}</span><strong>{project.progress.toFixed(1)}%</strong></div>
                      <div className="progress-track"><span style={{ width: `${Math.min(project.progress, 100)}%` }} /></div>
                    </div>
                  ))}
                </div>
              )}
            </section>
          </div>

          <div className="analytics-tables">
            <section className="panel table-panel">
              <div className="panel-heading"><div><h2>Recent Receipts</h2><p>Newest authorized receipt activity.</p></div></div>
              {(data?.recentReceipts?.length ?? 0) === 0 ? <EmptyState message="No recent receipts." /> : (
                <div className="table-scroll">
                  <table>
                    <thead><tr><th>Receipt</th><th>Date</th><th>Status</th><th>Amount</th></tr></thead>
                    <tbody>{data?.recentReceipts?.map((row) => (
                      <tr key={row.id}>
                        <th scope="row">{row.receiptNo}</th>
                        <td>{row.date ? format(new Date(`${row.date}T00:00:00`), 'MMM d, yyyy') : '—'}</td>
                        <td><span className="status-tag">{row.status}</span></td>
                        <td>{formatPeso(row.amount)}</td>
                      </tr>
                    ))}</tbody>
                  </table>
                </div>
              )}
            </section>

            <section className="panel table-panel">
              <div className="panel-heading"><div><h2>Active Projects</h2><p>Project cost and receipt position.</p></div></div>
              {(data?.projects?.length ?? 0) === 0 ? <EmptyState message="No projects." /> : (
                <div className="table-scroll">
                  <table>
                    <thead><tr><th>Project</th><th>Receipts</th><th>Spend</th></tr></thead>
                    <tbody>{data?.projects.map((project) => (
                      <tr key={project.id}>
                        <th scope="row">{project.name}<small>{project.code}</small></th>
                        <td>{project.receiptCount}</td>
                        <td>{formatPeso(project.spend)}</td>
                      </tr>
                    ))}</tbody>
                  </table>
                </div>
              )}
            </section>

            <section className="panel table-panel">
              <div className="panel-heading"><div><h2>Upcoming Deadlines</h2><p>Billing and milestone dates.</p></div></div>
              {(data?.deadlines?.length ?? 0) === 0 ? <EmptyState message="No upcoming deadlines." /> : (
                <div className="table-scroll"><table><thead><tr><th>Item</th><th>Date</th><th>Status</th></tr></thead>
                  <tbody>{data?.deadlines?.map((row) => (
                    <tr key={`${row.id}-${row.title}`}><th>{row.title}</th><td>{row.date || '—'}</td><td>{row.status}</td></tr>
                  ))}</tbody></table></div>
              )}
            </section>

            <section className="panel table-panel">
              <div className="panel-heading"><div><h2>Procurement Overview</h2><p>Purchase order commitments.</p></div></div>
              {(data?.procurement?.length ?? 0) === 0 ? <EmptyState message="No procurement records." /> : (
                <div className="table-scroll"><table><thead><tr><th>PO</th><th>Status</th><th>Amount</th></tr></thead>
                  <tbody>{data?.procurement?.map((row) => (
                    <tr key={row.name}><th>{row.name}</th><td>{row.status}</td><td>{formatPeso(row.amount)}</td></tr>
                  ))}</tbody></table></div>
              )}
            </section>

            <section className="panel table-panel">
              <div className="panel-heading"><div><h2>Inventory Overview</h2><p>Recorded material stock.</p></div></div>
              {(data?.inventory?.length ?? 0) === 0 ? <EmptyState message="No inventory records." /> : (
                <div className="table-scroll"><table><thead><tr><th>Item</th><th>Quantity</th><th>Value</th></tr></thead>
                  <tbody>{data?.inventory?.map((row) => (
                    <tr key={row.name}><th>{row.name}</th><td>{row.quantity}</td><td>{formatPeso(row.value)}</td></tr>
                  ))}</tbody></table></div>
              )}
            </section>

            <section className="panel table-panel">
              <div className="panel-heading"><div><h2>Supplier Payments</h2><p>Recorded payment and withholding totals.</p></div></div>
              {(data?.supplierPayments?.length ?? 0) === 0 ? <EmptyState message="No supplier payments." /> : (
                <div className="table-scroll"><table><thead><tr><th>Supplier</th><th>Paid</th><th>Withholding</th></tr></thead>
                  <tbody>{data?.supplierPayments?.map((row) => (
                    <tr key={row.name}><th>{row.name}</th><td>{formatPeso(row.paid)}</td><td>{formatPeso(row.withholding)}</td></tr>
                  ))}</tbody></table></div>
              )}
            </section>

            <section className="panel table-panel">
              <div className="panel-heading"><div><h2>Workforce Summary</h2><p>Recorded labor-cost periods.</p></div></div>
              {(data?.workforce?.length ?? 0) === 0 ? <EmptyState message="No workforce records." /> : (
                <div className="table-scroll"><table><thead><tr><th>Period</th><th>Status</th><th>Total Pay</th></tr></thead>
                  <tbody>{data?.workforce?.map((row) => (
                    <tr key={`${row.name}-${row.amount}`}><th>{row.name}</th><td>{row.status}</td><td>{formatPeso(row.amount)}</td></tr>
                  ))}</tbody></table></div>
              )}
            </section>

            <section className="panel table-panel">
              <div className="panel-heading"><div><h2>Equipment Summary</h2><p>Recorded equipment costs.</p></div></div>
              {(data?.equipment?.length ?? 0) === 0 ? <EmptyState message="No equipment cost records." /> : (
                <div className="table-scroll"><table><thead><tr><th>Equipment</th><th>Status</th><th>Total Cost</th></tr></thead>
                  <tbody>{data?.equipment?.map((row) => (
                    <tr key={`${row.name}-${row.amount}`}><th>{row.name}</th><td>{row.status}</td><td>{formatPeso(row.amount)}</td></tr>
                  ))}</tbody></table></div>
              )}
            </section>

            <section className="panel table-panel">
              <div className="panel-heading"><div><h2>Project Locations</h2><p>Verified map markers.</p></div></div>
              {(data?.locations?.length ?? 0) === 0 ? <EmptyState message="No project locations." /> : (
                <div className="table-scroll"><table><thead><tr><th>Location</th><th>Status</th><th>Coordinates</th></tr></thead>
                  <tbody>{data?.locations?.map((row) => (
                    <tr key={row.id}><th>{row.name}</th><td>{row.status}</td><td>{row.latitude}, {row.longitude}</td></tr>
                  ))}</tbody></table></div>
              )}
            </section>

            <section className="panel table-panel">
              <div className="panel-heading"><div><h2>Recent Activity</h2><p>Authorized system events.</p></div></div>
              {(data?.activity?.length ?? 0) === 0 ? <EmptyState message="No recent activity." /> : (
                <div className="activity-list">{data?.activity?.slice(0, 8).map((row) => (
                  <div className="activity-row" key={row.id}>
                    <strong>{row.action}</strong>
                    <span>{row.actor || 'System'}</span>
                    <time>{row.at}</time>
                  </div>
                ))}</div>
              )}
            </section>
          </div>
        </>
      )}
    </section>
  );
}
