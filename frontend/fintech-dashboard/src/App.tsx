import { ToastProvider } from './components/ui/Toast';
import { AuthProvider } from './features/auth/AuthContext';
import { AppRoutes } from './routes';

export default function App() {
  return (
    <AuthProvider>
      <ToastProvider>
        <AppRoutes />
      </ToastProvider>
    </AuthProvider>
  );
}
