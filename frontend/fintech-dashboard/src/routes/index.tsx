import { Route, Routes } from 'react-router-dom';
import { LoginPage } from '../features/auth/LoginPage';
import { RegistrationPage } from '../features/auth/RegistrationPage';
import { CardsPage } from '../features/cards/CardsPage';
import { CustomersPage } from '../features/customers/CustomersPage';
import { ProfilePage } from '../features/customers/ProfilePage';
import { DashboardPage } from '../features/dashboard/DashboardPage';
import { DisputeDetailPage } from '../features/disputes/DisputeDetailPage';
import { DisputesPage } from '../features/disputes/DisputesPage';
import { AlertDetailPage } from '../features/fraud/AlertDetailPage';
import { FraudPage } from '../features/fraud/FraudPage';
import { AuditPage } from '../features/audit/AuditPage';
import { CycleDetailPage } from '../features/settlement/CycleDetailPage';
import { SettlementPage } from '../features/settlement/SettlementPage';
import { NotificationDetailPage } from '../features/notifications/NotificationDetailPage';
import { NotificationsPage } from '../features/notifications/NotificationsPage';
import { PayPage } from '../features/payments/PayPage';
import { TransactionDetailPage } from '../features/transactions/TransactionDetailPage';
import { TransactionsPage } from '../features/transactions/TransactionsPage';
import { AppShell } from '../layouts/AppShell';
import {
  NotFoundPage,
} from '../pages/placeholders';
import { RequireAuth, RequireRole } from './guards';

export function AppRoutes() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/register" element={<RegistrationPage />} />
      <Route
        element={
          <RequireAuth>
            <AppShell />
          </RequireAuth>
        }
      >
        <Route index element={<DashboardPage />} />
        <Route path="transactions" element={<TransactionsPage />} />
        <Route path="transactions/:id" element={<TransactionDetailPage />} />
        <Route path="cards" element={<CardsPage />} />
        <Route
          path="pay"
          element={
            <RequireRole roles={['CUSTOMER', 'SUPPORT_AGENT', 'PLATFORM_ADMIN']}>
              <PayPage />
            </RequireRole>
          }
        />
        <Route
          path="customers"
          element={
            <RequireRole roles={['SUPPORT_AGENT', 'PLATFORM_ADMIN']}>
              <CustomersPage />
            </RequireRole>
          }
        />
        <Route
          path="profile"
          element={
            <RequireRole roles={['CUSTOMER', 'SUPPORT_AGENT', 'PLATFORM_ADMIN']}>
              <ProfilePage />
            </RequireRole>
          }
        />
        <Route
          path="disputes"
          element={
            <RequireRole roles={['CUSTOMER', 'SUPPORT_AGENT', 'PLATFORM_ADMIN']}>
              <DisputesPage />
            </RequireRole>
          }
        />
        <Route
          path="disputes/:id"
          element={
            <RequireRole roles={['CUSTOMER', 'SUPPORT_AGENT', 'PLATFORM_ADMIN']}>
              <DisputeDetailPage />
            </RequireRole>
          }
        />
        <Route
          path="fraud"
          element={
            <RequireRole roles={['FRAUD_ANALYST', 'COMPLIANCE_OFFICER', 'AUDITOR', 'PLATFORM_ADMIN']}>
              <FraudPage />
            </RequireRole>
          }
        />
        <Route
          path="fraud/alerts/:id"
          element={
            <RequireRole roles={['FRAUD_ANALYST', 'COMPLIANCE_OFFICER', 'AUDITOR', 'PLATFORM_ADMIN']}>
              <AlertDetailPage />
            </RequireRole>
          }
        />
        <Route
          path="audit"
          element={
            <RequireRole roles={['AUDITOR', 'COMPLIANCE_OFFICER', 'PLATFORM_ADMIN']}>
              <AuditPage />
            </RequireRole>
          }
        />
        <Route
          path="settlement"
          element={
            <RequireRole
              roles={['SETTLEMENT_OPERATOR', 'COMPLIANCE_OFFICER', 'AUDITOR', 'PLATFORM_ADMIN']}
            >
              <SettlementPage />
            </RequireRole>
          }
        />
        <Route
          path="settlement/cycles/:reference"
          element={
            <RequireRole
              roles={['SETTLEMENT_OPERATOR', 'COMPLIANCE_OFFICER', 'AUDITOR', 'PLATFORM_ADMIN']}
            >
              <CycleDetailPage />
            </RequireRole>
          }
        />
        <Route
          path="notifications"
          element={
            <RequireRole roles={['SUPPORT_AGENT', 'PLATFORM_ADMIN']}>
              <NotificationsPage />
            </RequireRole>
          }
        />
        <Route
          path="notifications/:id"
          element={
            <RequireRole roles={['SUPPORT_AGENT', 'PLATFORM_ADMIN']}>
              <NotificationDetailPage />
            </RequireRole>
          }
        />
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  );
}
