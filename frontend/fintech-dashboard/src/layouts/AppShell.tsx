import { motion } from 'framer-motion';
import {
  ArrowLeftRight,
  Bell,
  CreditCard,
  FileWarning,
  Landmark,
  LayoutDashboard,
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
}

const NAV: NavItem[] = [
  { to: '/', label: 'Dashboard', icon: LayoutDashboard, roles: 'all' },
  { to: '/transactions', label: 'Transactions', icon: ArrowLeftRight, roles: 'all' },
  { to: '/cards', label: 'Cards', icon: CreditCard, roles: 'all' },
  {
    to: '/pay',
    label: 'Pay & Fund',
    icon: Wallet,
    roles: ['CUSTOMER', 'SUPPORT_AGENT', 'PLATFORM_ADMIN'],
  },
  {
    to: '/customers',
    label: 'Customers',
    icon: Users,
    roles: ['SUPPORT_AGENT', 'PLATFORM_ADMIN'],
  },
  {
    to: '/profile',
    label: 'Profile',
    icon: UserRound,
    roles: ['CUSTOMER', 'SUPPORT_AGENT', 'PLATFORM_ADMIN'],
  },
  {
    to: '/disputes',
    label: 'Disputes',
    icon: FileWarning,
    roles: ['CUSTOMER', 'SUPPORT_AGENT', 'PLATFORM_ADMIN'],
  },
  {
    to: '/fraud',
    label: 'Fraud',
    icon: ShieldAlert,
    roles: ['FRAUD_ANALYST', 'COMPLIANCE_OFFICER', 'AUDITOR', 'PLATFORM_ADMIN'],
  },
  {
    to: '/audit',
    label: 'Audit Logs',
    icon: ScrollText,
    roles: ['AUDITOR', 'COMPLIANCE_OFFICER', 'PLATFORM_ADMIN'],
  },
  {
    to: '/settlement',
    label: 'Settlement',
    icon: Landmark,
    roles: ['SETTLEMENT_OPERATOR', 'COMPLIANCE_OFFICER', 'AUDITOR', 'PLATFORM_ADMIN'],
  },
  {
    to: '/notifications',
    label: 'Notifications',
    icon: Bell,
    roles: ['SUPPORT_AGENT', 'PLATFORM_ADMIN'],
  },
];

export function AppShell() {
  const { username, roles, logout, hasRole } = useAuth();
  const navigate = useNavigate();
  const [navOpen, setNavOpen] = useState(false);

  const visible = NAV.filter(
    (item) => item.roles === 'all' || hasRole(...(item.roles as Role[])),
  );

  const signOut = () => {
    logout();
    navigate('/login', { replace: true });
  };

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
          <strong>FinTech</strong>
          <span>Synthetic data · no real money</span>
        </div>
        <nav aria-label="Sections">
          {visible.map((item) => {
            const Icon = item.icon;
            return (
              <NavLink
                key={item.to}
                to={item.to}
                end={item.to === '/'}
                className={({ isActive }) => (isActive ? 'nav-link active' : 'nav-link')}
                onClick={() => setNavOpen(false)}
              >
                <Icon size={18} aria-hidden="true" />
                {item.label}
              </NavLink>
            );
          })}
        </nav>
        <div className="sidebar-foot">
          <div>{roles.join(', ') || 'No roles'}</div>
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
          <p className="topbar-title">Secure FinTech Platform</p>
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
