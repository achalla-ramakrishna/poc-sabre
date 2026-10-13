import { useEffect, useState } from 'react';
import { api } from '../api/client';
import type { History } from '../api/types';

/** UC-09: who did what, and whether DPE has it. */
export function HistoryPanel({ ruleId, refreshKey }: { ruleId: string; refreshKey: number }) {
  const [history, setHistory] = useState<History | null>(null);

  useEffect(() => {
    api.history(ruleId).then(setHistory).catch(() => setHistory(null));
  }, [ruleId, refreshKey]);

  if (!history) return null;
  return (
    <aside className="card">
      <h3>DPE publications</h3>
      {history.publications.length === 0 ? (
        <p className="muted">Not sent to DPE yet.</p>
      ) : (
        <ul className="plain" data-testid="publications">
          {history.publications.map((p) => (
            <li key={p.id}>
              <span className={`pill pill-${p.status.toLowerCase()}`}>{p.status}</span> v{p.version} {p.operation.toLowerCase()} · {p.mode.toLowerCase()}
              {p.dpeReference && <span className="muted"> · {p.dpeReference}</span>}
              {p.lastError && <div className="error-text">{p.lastError}</div>}
            </li>
          ))}
        </ul>
      )}
      <h3>History</h3>
      <ul className="plain timeline">
        {history.events.map((e) => (
          <li key={e.id}>
            <strong>{e.action.replace('_', ' ').toLowerCase()}</strong> v{e.version} by {e.actor}
            <div className="muted">{new Date(e.at).toLocaleString()}</div>
          </li>
        ))}
      </ul>
    </aside>
  );
}
