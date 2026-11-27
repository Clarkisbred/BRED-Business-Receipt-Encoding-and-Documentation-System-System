import { useEffect, useState } from 'react';
import { AlertCircle, RefreshCw } from 'lucide-react';
import { loadDashboardData, type DashboardData } from '../api/dashboard';

type DashboardState =
  | { status: 'loading' }
  | { status: 'error' }
  | { status: 'ready'; data: DashboardData };

function formatPeso(value: string): string {
  return new Intl.NumberFormat('en-PH', {
    style: 'currency',
    currency: 'PHP',
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(Number(value));
}

function todayLabel(): string {
  return new Intl.DateTimeFormat('en-PH', {
    weekday: 'long',
    month: 'long',
    day: 'numeric',
    year: 'numeric',
  }).format(new Date());
}

export function Dashboard() {
  const [attempt, setAttempt] = useState(0);
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

  return (
    <section className="dashboard-page" aria-labelledby="dashboard-title">
      <header className="page-heading">
        <div>
          <p className="eyebrow">WORKSPACE</p>
          <h1 id="dashboard-title">Dashboard</h1>
          <p className="page-subtitle">A current view of receipts, spend, and project activity.</p>
        </div>
        <time className="date-label" dateTime={new Date().toISOString().slice(0, 10)}>
          {todayLabel()}
        </time>
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
          <div className="stat-grid" aria-live="polite">
            <article className="stat-card">
              <span className="stat-label">Total expenses</span>
              <strong className="stat-value">
                {state.status === 'ready' ? formatPeso(state.data.totalSpend) : '–'}
              </strong>
              <span className="stat-note">All receipts visible to this account</span>
            </article>
            <article className="stat-card">
              <span className="stat-label">Spend this month</span>
              <strong className="stat-value">
                {state.status === 'ready' ? formatPeso(state.data.monthSpend) : '–'}
              </strong>
              <span className="stat-note">
                {state.status === 'ready' ? `${state.data.monthReceiptCount} receipts this month` : 'Loading authorized records'}
              </span>
            </article>
            <article className="stat-card">
              <span className="stat-label">Total receipts</span>
              <strong className="stat-value">
                {state.status === 'ready' ? state.data.totalReceiptCount.toLocaleString('en-PH') : '–'}
              </strong>
              <span className="stat-note">Current BRED workspace</span>
            </article>
            <article className="stat-card">
              <span className="stat-label">Projects</span>
              <strong className="stat-value">
                {state.status === 'ready' ? state.data.projectCount.toLocaleString('en-PH') : '–'}
              </strong>
              <span className="stat-note">Projects in this workspace</span>
            </article>
          </div>

          <section className="panel project-panel" aria-labelledby="project-heading">
            <div className="panel-heading">
              <div>
                <h2 id="project-heading">Project overview</h2>
                <p>Receipt count and spend from the connected BRED data store.</p>
              </div>
            </div>

            {state.status === 'loading' ? (
              <div className="table-state" role="status">Loading authorized dashboard data…</div>
            ) : state.data.projects.length === 0 ? (
              <div className="table-state">
                <strong>No projects yet.</strong>
                <span>Add the first project in the desktop app while the web migration is in progress.</span>
              </div>
            ) : (
              <div className="table-scroll">
                <table>
                  <thead>
                    <tr>
                      <th scope="col">Project</th>
                      <th scope="col">Receipts</th>
                      <th scope="col">Spend</th>
                    </tr>
                  </thead>
                  <tbody>
                    {state.data.projects.map((project) => (
                      <tr key={project.id}>
                        <th scope="row">
                          <span className="project-name">{project.name}</span>
                          <span className="project-code">{project.code}</span>
                        </th>
                        <td>{project.receiptCount.toLocaleString('en-PH')}</td>
                        <td>{formatPeso(project.spend)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>
        </>
      )}
    </section>
  );
}
