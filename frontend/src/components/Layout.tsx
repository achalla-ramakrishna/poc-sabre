import type { ReactNode } from 'react';
import { NavLink, useNavigate } from 'react-router-dom';
import { session } from '../auth/session';

export function Layout({ children }: { children: ReactNode }) {
  const navigate = useNavigate();
  const user = session.get();
  return (
    <>
      <header className="topbar">
        <div className="brand">Rewards IQ</div>
        {user && (
          <nav>
            <NavLink to="/rules">Rules</NavLink>
            <NavLink to="/preview">Preview</NavLink>
          </nav>
        )}
        {user && (
          <div className="user">
            <span>
              {user.subject} · {user.airline} · {user.roles.join(', ')}
            </span>
            <button
              className="link"
              onClick={() => {
                session.clear();
                navigate('/login');
              }}
            >
              Sign out
            </button>
          </div>
        )}
      </header>
      <main>{children}</main>
    </>
  );
}
