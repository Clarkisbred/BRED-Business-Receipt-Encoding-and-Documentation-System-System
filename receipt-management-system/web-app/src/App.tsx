import { useState } from 'react';
import { Dashboard } from './components/Dashboard';
import { Login } from './components/Login';
import { Sidebar, SIDEBAR_WIDTH_PX } from './components/Sidebar';
import { useAuth } from './auth/AuthProvider';
import type { ModuleKey } from './types';

function LoadingScreen() {
  return (
    <main className="loading-screen" role="status">
      <span className="loading-mark">BRED</span>
      <span>Checking your account…</span>
    </main>
  );
}

export function App() {
  const { status, profile, signOut } = useAuth();
  const [activeModule, setActiveModule] = useState<ModuleKey>('DASHBOARD');

  if (status === 'initializing') return <LoadingScreen />;
  if (!profile) return <Login />;

  const moduleTitle: Record<ModuleKey, string> = {
    DASHBOARD: 'Dashboard',
    MESSAGES: 'Custom Messages',
    DUCKY: 'Ducky',
    RECEIPTS: 'Receipts',
    PROJECTS: 'Projects',
    LIVE_MAP: 'Live Project Map',
    CONSTRUCTION: 'Construction',
    SUPPLIERS: 'Suppliers',
    REPORTS: 'Reports',
    USERS: 'Users',
    ACTIVITY_LOG: 'Activity Log',
    COMPANIES: 'Companies',
    HISTORY: 'History',
    SETTINGS: 'Settings',
  };

  return (
    <div className="app-shell" style={{ '--sidebar-width': `${SIDEBAR_WIDTH_PX}px` } as React.CSSProperties}>
      <Sidebar
        role={profile.role}
        fullName={profile.fullName}
        companyName={profile.companyName}
        activeModule={activeModule}
        onNavigate={setActiveModule}
      />
      <div className="workspace">
        <header className="workspace-header">
          <div>
            <p className="breadcrumb">Workspace / {moduleTitle[activeModule]}</p>
            <h1>{moduleTitle[activeModule]}</h1>
          </div>
          <div className="header-account">
            <span className="header-user">{profile.fullName}</span>
            <span className="role-pill">{profile.role}</span>
            <button className="button button-quiet" type="button" onClick={() => void signOut()}>
              Sign out
            </button>
          </div>
        </header>

        <main className="workspace-content">
          {activeModule === 'DASHBOARD' ? (
            <Dashboard />
          ) : (
            <section className="module-placeholder" aria-labelledby="placeholder-title">
              <p className="eyebrow">STAGED MIGRATION</p>
              <h2 id="placeholder-title">{moduleTitle[activeModule]}</h2>
              <p>
                This module remains available in the BRED desktop app while its web version is reviewed.
                No substitute data or write actions are provided here.
              </p>
              <button className="button button-secondary" type="button" onClick={() => setActiveModule('DASHBOARD')}>
                Return to Dashboard
              </button>
            </section>
          )}
        </main>
      </div>
    </div>
  );
}
