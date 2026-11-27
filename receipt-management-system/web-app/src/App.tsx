import { useState } from 'react';
import { Dashboard } from './components/Dashboard';
import { Login } from './components/Login';
import { Sidebar, SIDEBAR_WIDTH_PX } from './components/Sidebar';
import { ModuleWorkspace } from './components/ModuleWorkspace';
import { MessagesWorkspace } from './components/MessagesWorkspace';
import { DuckyWorkspace } from './components/DuckyWorkspace';
import { ProfileAvatar } from './components/IdentityAvatar';
import { useAuth } from './auth/AuthProvider';
import type { ModuleKey } from './types';

function LoadingScreen() {
  return (
    <main className="loading-screen" role="status" aria-label="Loading BRED workspace">
      <span className="loading-mark" aria-hidden="true">BRED</span>
      <span className="loading-skeleton-line loading-skeleton-short" aria-hidden="true" />
      <section className="loading-skeleton-content" aria-hidden="true">
        <div className="loading-skeleton-heading">
          <span className="loading-skeleton-line loading-skeleton-title" />
          <span className="loading-skeleton-line loading-skeleton-copy" />
        </div>
        <div className="loading-skeleton-stats">
          {Array.from({ length: 4 }, (_, index) => (
            <div className="loading-skeleton-card" key={index}>
              <span className="loading-skeleton-line loading-skeleton-label" />
              <span className="loading-skeleton-line loading-skeleton-value" />
              <span className="loading-skeleton-line loading-skeleton-copy" />
            </div>
          ))}
        </div>
        <div className="loading-skeleton-panels">
          <div className="loading-skeleton-panel">
            <span className="loading-skeleton-line loading-skeleton-title" />
            <span className="loading-skeleton-chart" />
          </div>
          <div className="loading-skeleton-panel">
            <span className="loading-skeleton-line loading-skeleton-title" />
            <span className="loading-skeleton-chart" />
          </div>
        </div>
      </section>
      <span className="visually-hidden">Loading your BRED workspace…</span>
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
        email={profile.email}
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
            <ProfileAvatar className="header-profile-avatar" name={profile.fullName} email={profile.email} />
            <span className="header-user">{profile.fullName}</span>
            <span className="role-pill">{profile.role}</span>
            <button className="button button-quiet" type="button" onClick={() => void signOut()}>
              Sign out
            </button>
          </div>
        </header>

        <main className="workspace-content">
          {activeModule === 'DASHBOARD'
            ? <Dashboard />
            : activeModule === 'MESSAGES'
            ? <MessagesWorkspace currentUserId={profile.id} currentUserRole={profile.role} />
              : activeModule !== 'DUCKY'
                ? <ModuleWorkspace module={activeModule} />
                : null}
          <DuckyWorkspace
            active={activeModule === 'DUCKY'}
            userId={profile.id}
            userName={profile.fullName}
            userEmail={profile.email}
          />
        </main>
      </div>
    </div>
  );
}
