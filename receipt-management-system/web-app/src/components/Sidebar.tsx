import {
  Activity,
  Building2,
  ChartNoAxesCombined,
  ClipboardList,
  Cloud,
  FolderKanban,
  HardHat,
  History,
  LayoutDashboard,
  MapPinned,
  MessageSquareText,
  ReceiptText,
  Settings,
  Truck,
  Users,
  type LucideIcon,
} from 'lucide-react';
import type { AppRole, ModuleKey } from '../types';

export const SIDEBAR_WIDTH_PX = 252;

interface NavItem {
  id: ModuleKey;
  label: string;
  icon: LucideIcon;
  allowed: readonly AppRole[];
}

interface NavGroup {
  label: string;
  items: readonly NavItem[];
}

const EVERY_ROLE: readonly AppRole[] = ['creator', 'admin', 'encoder'];
const COMPANY_ADMIN: readonly AppRole[] = ['creator', 'admin'];
const CREATOR: readonly AppRole[] = ['creator'];

const NAV_GROUPS: readonly NavGroup[] = [
  {
    label: 'WORKSPACE',
    items: [
      { id: 'DASHBOARD', label: 'Dashboard', icon: LayoutDashboard, allowed: EVERY_ROLE },
      { id: 'MESSAGES', label: 'Custom Messages', icon: MessageSquareText, allowed: EVERY_ROLE },
      { id: 'DUCKY', label: 'Ducky', icon: Activity, allowed: EVERY_ROLE },
      { id: 'RECEIPTS', label: 'Receipts', icon: ReceiptText, allowed: EVERY_ROLE },
    ],
  },
  {
    label: 'PROJECT MANAGEMENT',
    items: [
      { id: 'PROJECTS', label: 'Projects', icon: FolderKanban, allowed: EVERY_ROLE },
      { id: 'LIVE_MAP', label: 'Live Project Map', icon: MapPinned, allowed: EVERY_ROLE },
      { id: 'CONSTRUCTION', label: 'Construction', icon: HardHat, allowed: EVERY_ROLE },
    ],
  },
  {
    label: 'FINANCIAL & OPERATIONS',
    items: [
      { id: 'SUPPLIERS', label: 'Suppliers', icon: Truck, allowed: EVERY_ROLE },
      { id: 'REPORTS', label: 'Reports', icon: ChartNoAxesCombined, allowed: EVERY_ROLE },
    ],
  },
  {
    label: 'SYSTEM',
    items: [
      { id: 'USERS', label: 'Users', icon: Users, allowed: COMPANY_ADMIN },
      { id: 'ACTIVITY_LOG', label: 'Activity Log', icon: ClipboardList, allowed: COMPANY_ADMIN },
      { id: 'COMPANIES', label: 'Companies', icon: Building2, allowed: CREATOR },
      { id: 'HISTORY', label: 'History', icon: History, allowed: CREATOR },
      { id: 'SETTINGS', label: 'Settings', icon: Settings, allowed: EVERY_ROLE },
    ],
  },
];

export interface VisibleNavGroup {
  label: string;
  items: NavItem[];
}

export function visibleNavigation(role: AppRole): VisibleNavGroup[] {
  return NAV_GROUPS
    .map((group) => ({
      label: group.label,
      items: group.items.filter((item) => item.allowed.includes(role)),
    }))
    .filter((group) => group.items.length > 0);
}

interface SidebarProps {
  role: AppRole;
  fullName: string;
  companyName: string;
  activeModule: ModuleKey;
  onNavigate: (module: ModuleKey) => void;
}

export function Sidebar({
  role,
  fullName,
  companyName,
  activeModule,
  onNavigate,
}: SidebarProps) {
  const groups = visibleNavigation(role);
  const companyLabel = companyName || (role === 'creator' ? 'All companies' : 'BRED workspace');

  return (
    <aside className="sidebar" style={{ '--sidebar-width': `${SIDEBAR_WIDTH_PX}px` } as React.CSSProperties}>
      <div className="brand-block">
        <span className="brand-name">BRED</span>
        <span className="brand-description">
          Business Receipt Encoding
          <br />
          and Documentation System
        </span>
      </div>

      <nav className="sidebar-navigation" aria-label="Main navigation">
        {groups.map((group) => (
          <section className="nav-group" key={group.label} aria-label={group.label}>
            <h2 className="nav-group-title">{group.label}</h2>
            <ul className="nav-list">
              {group.items.map(({ id, label, icon: Icon }) => (
                <li key={id}>
                  <button
                    className={`nav-item${activeModule === id ? ' is-active' : ''}`}
                    type="button"
                    aria-current={activeModule === id ? 'page' : undefined}
                    onClick={() => onNavigate(id)}
                  >
                    <Icon aria-hidden="true" size={18} strokeWidth={1.8} />
                    <span>{label}</span>
                  </button>
                </li>
              ))}
            </ul>
          </section>
        ))}
      </nav>

      <div className="sidebar-profile">
        <div className="profile-mark" aria-hidden="true">
          {fullName.trim().charAt(0).toUpperCase() || 'B'}
        </div>
        <div className="profile-copy">
          <strong title={fullName}>{fullName || 'BRED user'}</strong>
          <span title={companyLabel}>{companyLabel}</span>
        </div>
        <Cloud className="profile-cloud" aria-label="Connected to Supabase" size={16} />
      </div>
    </aside>
  );
}
