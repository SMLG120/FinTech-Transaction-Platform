import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { RequireRole } from './guards';

vi.mock('../features/auth/AuthContext', () => ({
  useAuth: () => ({ hasRole: (...wanted: string[]) => wanted.includes('CUSTOMER') }),
}));

describe('RequireRole', () => {
  it('renders children when the role is held', () => {
    render(
      <MemoryRouter>
        <RequireRole roles={['CUSTOMER']}>
          <p>allowed</p>
        </RequireRole>
      </MemoryRouter>,
    );
    expect(screen.getByText('allowed')).toBeInTheDocument();
  });

  it('renders a 403 explanation when the role is missing', () => {
    render(
      <MemoryRouter>
        <RequireRole roles={['PLATFORM_ADMIN']}>
          <p>allowed</p>
        </RequireRole>
      </MemoryRouter>,
    );
    expect(screen.queryByText('allowed')).not.toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent(/not permitted/i);
  });
});
