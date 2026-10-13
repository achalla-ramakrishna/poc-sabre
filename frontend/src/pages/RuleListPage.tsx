import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import type { RuleStatus, RuleSummary } from '../api/types';
import { hasRole, session } from '../auth/session';
import { ErrorBanner } from '../components/ErrorBanner';
import { StatusBadge } from '../components/StatusBadge';

const FILTERS: (RuleStatus | '')[] = ['', 'DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'PUBLISHED', 'RETIRED'];

/** Screen 1: every rule for the airline, newest version, and which version is live. */
export function RuleListPage() {
  const [rules, setRules] = useState<RuleSummary[] | null>(null);
  const [filter, setFilter] = useState<RuleStatus | ''>('');
  const [error, setError] = useState<unknown>(null);
  const user = session.get();

  useEffect(() => {
    setError(null);
    setRules(null);
    api.listRules(filter || undefined).then(setRules).catch(setError);
  }, [filter]);

  return (
    <section>
      <div className="page-head">
        <h1>Redemption rules</h1>
        <div className="actions">
          <select aria-label="Status filter" value={filter} onChange={(e) => setFilter(e.target.value as RuleStatus | '')}>
            {FILTERS.map((f) => (
              <option key={f} value={f}>
                {f ? f.replace('_', ' ').toLowerCase() : 'all statuses'}
              </option>
            ))}
          </select>
          {hasRole(user, 'ANALYST') && (
            <Link className="button" to="/rules/new">
              New rule
            </Link>
          )}
        </div>
      </div>
      <ErrorBanner error={error} />
      {!rules && !error && <p className="empty">Loading rules…</p>}
      {rules && rules.length === 0 && <p className="empty">No rules yet.</p>}
      {rules && rules.length > 0 && (
        <table>
          <thead>
            <tr>
              <th>Name</th>
              <th>Latest</th>
              <th>Status</th>
              <th>Live</th>
              <th>Effective</th>
              <th>Specificity</th>
            </tr>
          </thead>
          <tbody>
            {rules.map((r) => (
              <tr key={r.ruleId} data-testid="rule-row">
                <td>
                  <Link to={`/rules/${r.ruleId}`}>{r.name}</Link>
                </td>
                <td>v{r.latestVersion}</td>
                <td>
                  <StatusBadge status={r.latestStatus} />
                </td>
                <td>{r.liveVersion ? `v${r.liveVersion}` : '—'}</td>
                <td>
                  {r.effectiveFrom} → {r.effectiveTo ?? 'open'}
                </td>
                <td>{r.specificity}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
