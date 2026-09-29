import { motion } from 'framer-motion';
import {
  ArrowLeftRight,
  Bell,
  CreditCard,
  FileWarning,
  Landmark,
  LayoutDashboard,
  LogOut,
  Menu,
  ScrollText,
  ShieldAlert,
  UserRound,
  Users,
  Wallet,
  X,
} from 'lucide-react';
import { useState } from 'react';
import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import { Button } from '../components/ui/Button';
import { useAuth } from '../features/auth/AuthContext';
import type { Role } from '../features/auth/roles';

interface NavItem {
  to: string;
  label: string;
  icon: typeof LayoutDashboard;
  roles: Role[] | 'all';
  section: string;
}

const NAV: NavItem[] = [
  { to: '/', label: 'Dashboard', icon: LayoutDashboard, roles: 'all', section: 'Overview' },
  { to: '/transactions', label: 'Transactions', icon: ArrowLeftRight, roles: 'all', section: 'Money' },
  { to: '/cards', label: 'Cards', icon: CreditCard, roles: 'all', section: 'Money' },
  {
    to: '/pay',
    label: 'Pay & Fund',
    icon: Wallet,
    roles: ['CUSTOMER', 'SUPPORT_AGENT', 'PLATFORM_ADMIN'],
    section: 'Money',
  },
  {
    to: '/disputes',
    label: 'Disputes',
    icon: FileWarning,
    roles: ['CUSTOMER', 'SUPPORT_AGENT', 'PLATFORM_ADMIN'],
    section: 'Money',
  },
  {
    to: '/fraud',
    label: 'Fraud & Security',
    icon: ShieldAlert,
    roles: ['FRAUD_ANALYST', 'COMPLIANCE_OFFICER', 'AUDITOR', 'PLATFORM_ADMIN'],
    section: 'Risk & Compliance',
  },
  {
    to: '/audit',
    label: 'Audit Logs',
    icon: ScrollText,
    roles: ['AUDITOR', 'COMPLIANCE_OFFICER', 'PLATFORM_ADMIN'],
    section: 'Risk & Compliance',
  },
  {
    to: '/settlement',
    label: 'Settlement',
    icon: Landmark,
    roles: ['SETTLEMENT_OPERATOR', 'COMPLIANCE_OFFICER', 'AUDITOR', 'PLATFORM_ADMIN'],
    section: 'Risk & Compliance',
  },
  {
    to: '/customers',
    label: 'Customers',
    icon: Users,
    roles: ['SUPPORT_AGENT', 'PLATFORM_ADMIN'],
    section: 'Operations',
  },
  {
    to: '/notifications',
    label: 'Notifications',
    icon: Bell,
    roles: ['SUPPORT_AGENT', 'PLATFORM_ADMIN'],
    section: 'Operations',
  },
  {
    to: '/profile',
    label: 'Profile & Settings',
    icon: UserRound,
    roles: ['CUSTOMER', 'SUPPORT_AGENT', 'PLATFORM_ADMIN'],
    section: 'Operations',
  },
];

function initials(name: string | null): string {
  if (!name) return '••';
  const local = name.split('@')[0] ?? name;
  const parts = local.split(/[._-]+/).filter(Boolean);
  if (parts.length >= 2) return (parts[0][0] + parts[1][0]).toUpperCase();
  return local.slice(0, 2).toUpperCase();
}

export function AppShell() {
  const { username, roles, logout, hasRole } = useAuth();
  const navigate = useNavigate();
  const [navOpen, setNavOpen] = useState(false);

  const visible = NAV.filter(
    (item) => item.roles === 'all' || hasRole(...(item.roles as Role[])),
  );
  const sections = [...new Set(visible.map((item) => item.section))];

  const signOut = () => {
    logout();
    navigate('/login', { replace: true });
  };

  const primaryRole = roles[0]?.replace(/_/g, ' ') ?? 'No roles';
  const roleLabel =
    primaryRole.length > 24 ? `${primaryRole.slice(0, 24)}…` : primaryRole;
  const extraRoles = roles.length > 1 ? ` +${roles.length - 1}` : '';

  return (
    <div className={`shell${navOpen ? ' nav-open' : ''}`}>
      <a className="skip-link" href="#main-content">
        Skip to main content
      </a>
      {navOpen ? (
        <button
          type="button"
          className="nav-scrim"
          aria-label="Close navigation"
          onClick={() => setNavOpen(false)}
        />
      ) : null}
      <aside className="sidebar" aria-label="Primary">
        <div className="sidebar-brand">
          <span className="brand-mark" aria-hidden="true">
            F$
          </span>
          <div>
            <strong>Meridian Bank</strong>
            <span>Secure fintech ops</span>
          </div>
        </div>
        <nav aria-label="Sections">
          {sections.map((section) => (
            <div key={section}>
              <p className="nav-section-label">{section}</p>
              {visible
                .filter((item) => item.section === section)
                .map((item) => {
                  const Icon = item.icon;
                  return (
                    <NavLink
                      key={item.to}
                      to={item.to}
                      end={item.to === '/'}
                      className={({ isActive }) => (isActive ? 'nav-link active' : 'nav-link')}
                      onClick={() => setNavOpen(false)}
                    >
                      <Icon size={17} aria-hidden="true" />
                      {item.label}
                    </NavLink>
                  );
                })}
            </div>
          ))}
        </nav>
        <div className="sidebar-foot">
          <div className="user-chip">
            <span className="avatar" aria-hidden="true">
              {initials(username)}
            </span>
            <div className="user-chip-meta">
              <div className="user-chip-name" title={username ?? 'Signed in'}>
                {username ?? 'Signed in'}
              </div>
              <div className="user-chip-role" title={roles.join(', ')}>
                {roleLabel}
                {extraRoles}
              </div>
            </div>
          </div>
          <button type="button" className="signout-btn" onClick={signOut}>
            <LogOut size={15} aria-hidden="true" />
            Sign out
          </button>
        </div>
      </aside>
      <div className="main-col">
        <header className="topbar">
          <Button
            variant="ghost"
            size="sm"
            className="menu-btn btn"
            onClick={() => setNavOpen((open) => !open)}
            aria-expanded={navOpen}
            aria-label={navOpen ? 'Close navigation' : 'Open navigation'}
          >
            {navOpen ? <X size={20} /> : <Menu size={20} />}
          </Button>
          <p className="topbar-title">
            Secure FinTech Platform <span className="topbar-env">Synthetic data · no real money</span>
          </p>
          <div className="topbar-spacer" />
          <span className="topbar-user" title={username ?? ''}>
            {username}
          </span>
          <Button variant="ghost" size="sm" onClick={signOut}>
            Sign out
          </Button>
        </header>
        <motion.main
          id="main-content"
          className="content"
          initial={{ opacity: 0, y: 6 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.18 }}
        >
          <Outlet />
        </motion.main>
      </div>
    </div>
  );
}
