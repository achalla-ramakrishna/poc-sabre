import { FormEvent, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api } from '../api/client';
import { session } from '../auth/session';
import { ErrorBanner } from '../components/ErrorBanner';

const PERSONAS = [
  { id: 'u-analyst', label: 'Loyalty analyst', roles: ['ANALYST', 'VIEWER'] },
  { id: 'u-approver', label: 'Loyalty approver', roles: ['APPROVER', 'VIEWER'] },
  { id: 'u-viewer', label: 'Viewer (read only)', roles: ['VIEWER'] },
];

/**
 * Local sign-in: picks a persona and asks the backend's dev profile for a token. In a real
 * environment this page is replaced by an OIDC redirect to the identity provider.
 */
export function LoginPage() {
  const navigate = useNavigate();
  const [persona, setPersona] = useState(PERSONAS[0].id);
  const [airline, setAirline] = useState('VA');
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setBusy(true);
    try {
      const p = PERSONAS.find((x) => x.id === persona)!;
      session.start(await api.devToken(p.id, airline, p.roles));
      navigate('/rules');
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="card narrow" onSubmit={onSubmit}>
      <h1>Sign in</h1>
      <p className="muted">Local development sign-in. Analysts create and submit rules; approvers approve them.</p>
      <fieldset className="personas">
        {PERSONAS.map((p) => (
          <label key={p.id} className="radio">
            <input type="radio" name="persona" checked={persona === p.id} onChange={() => setPersona(p.id)} />
            {p.label}
          </label>
        ))}
      </fieldset>
      <label className="field">
        <span>Airline</span>
        <input aria-label="Airline" value={airline} maxLength={2} onChange={(e) => setAirline(e.target.value.toUpperCase())} />
      </label>
      <button type="submit" disabled={busy}>
        {busy ? 'Signing in…' : 'Sign in'}
      </button>
      <ErrorBanner error={error} />
    </form>
  );
}
