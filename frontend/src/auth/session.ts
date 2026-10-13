import type { Role } from '../api/types';

/**
 * The signed-in user, decoded from the JWT. In real environments the token comes from the
 * identity provider (OIDC); locally the backend's dev profile issues it at POST /dev/token.
 * Kept in sessionStorage: cleared when the tab closes, never shared across tabs.
 */
export interface Session {
  token: string;
  subject: string;
  airline: string;
  roles: Role[];
  expiresAt: number;
}

const KEY = 'rewardsiq.session';

function decode(token: string): Omit<Session, 'token'> {
  const payload = JSON.parse(atob(token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')));
  return {
    subject: payload.sub,
    airline: payload.airline,
    roles: payload.roles ?? [],
    expiresAt: (payload.exp ?? 0) * 1000,
  };
}

export const session = {
  get(): Session | null {
    try {
      const raw = sessionStorage.getItem(KEY);
      if (!raw) return null;
      const s = JSON.parse(raw) as Session;
      return s.expiresAt > Date.now() ? s : null;
    } catch {
      return null;
    }
  },
  start(token: string): Session {
    const s = { token, ...decode(token) };
    sessionStorage.setItem(KEY, JSON.stringify(s));
    return s;
  },
  clear() {
    sessionStorage.removeItem(KEY);
  },
};

export function hasRole(s: Session | null, role: Role): boolean {
  return !!s && (s.roles.includes(role) || s.roles.includes('ADMIN'));
}
