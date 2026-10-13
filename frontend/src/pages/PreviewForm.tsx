import { FormEvent, useState } from 'react';
import { api } from '../api/client';
import type { Cabin, PreviewRequest, PreviewResponse } from '../api/types';
import { CABINS } from '../api/types';
import { ErrorBanner } from '../components/ErrorBanner';

/** UC-04: what would a traveller see? Shared by the Preview page and the rule editor. */
export function PreviewForm({ initial, defaultRuleSet }: { initial?: Partial<PreviewRequest>; defaultRuleSet: 'LIVE' | 'DRAFT' }) {
  const [req, setReq] = useState<PreviewRequest>({
    ruleSet: defaultRuleSet,
    origin: initial?.origin ?? 'SYD',
    destination: initial?.destination ?? 'PER',
    cabin: initial?.cabin ?? 'ECONOMY',
    fareBrand: initial?.fareBrand ?? '',
    pointOfSale: initial?.pointOfSale ?? 'AU',
    fareAmount: '900.00',
    currency: initial?.currency ?? 'AUD',
    memberBalance: 100000,
  });
  const [result, setResult] = useState<PreviewResponse | null>(null);
  const [error, setError] = useState<unknown>(null);
  const set = <K extends keyof PreviewRequest>(k: K, v: PreviewRequest[K]) => setReq({ ...req, [k]: v });

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    try {
      setResult(await api.preview({ ...req, fareBrand: req.fareBrand || undefined, pointOfSale: req.pointOfSale || undefined }));
    } catch (err) {
      setResult(null);
      setError(err);
    }
  }

  return (
    <form onSubmit={onSubmit} className="preview">
      <div className="grid compact">
        <label className="field">
          <span>Rules</span>
          <select aria-label="Rule set" value={req.ruleSet} onChange={(e) => set('ruleSet', e.target.value as 'LIVE' | 'DRAFT')}>
            <option value="LIVE">Live rules</option>
            <option value="DRAFT">Latest drafts</option>
          </select>
        </label>
        <label className="field">
          <span>From</span>
          <input aria-label="From" maxLength={3} value={req.origin} onChange={(e) => set('origin', e.target.value.toUpperCase())} />
        </label>
        <label className="field">
          <span>To</span>
          <input aria-label="To" maxLength={3} value={req.destination} onChange={(e) => set('destination', e.target.value.toUpperCase())} />
        </label>
        <label className="field">
          <span>Cabin</span>
          <select aria-label="Cabin" value={req.cabin} onChange={(e) => set('cabin', e.target.value as Cabin)}>
            {CABINS.map((c) => (
              <option key={c}>{c}</option>
            ))}
          </select>
        </label>
        <label className="field">
          <span>Fare brand</span>
          <input aria-label="Fare brand" value={req.fareBrand} onChange={(e) => set('fareBrand', e.target.value.toUpperCase())} />
        </label>
        <label className="field">
          <span>Point of sale</span>
          <input aria-label="Point of sale" maxLength={2} value={req.pointOfSale} onChange={(e) => set('pointOfSale', e.target.value.toUpperCase())} />
        </label>
        <label className="field">
          <span>Fare</span>
          <input aria-label="Fare amount" inputMode="decimal" value={req.fareAmount} onChange={(e) => set('fareAmount', e.target.value)} />
        </label>
        <label className="field">
          <span>Currency</span>
          <input aria-label="Currency" maxLength={3} value={req.currency} onChange={(e) => set('currency', e.target.value.toUpperCase())} />
        </label>
        <label className="field">
          <span>Member balance</span>
          <input aria-label="Member balance" type="number" min={0} value={req.memberBalance ?? ''} onChange={(e) => set('memberBalance', e.target.value === '' ? undefined : Number(e.target.value))} />
        </label>
      </div>
      <button type="submit">Preview price</button>
      <ErrorBanner error={error} />
      {result && (
        <div className={`result ${result.cashOnly ? 'cash' : 'points'}`} data-testid="preview-result">
          <div className="big">{result.message}</div>
          {!result.cashOnly && (
            <div>
              Usable from this balance: <strong>{result.pointsUsable.toLocaleString()} points</strong> · rule {result.ruleName} v{result.ruleVersion}
            </div>
          )}
          {result.tiedWith.length > 0 && <div className="error-text">Tied with {result.tiedWith.join(', ')}: publish would reject this overlap.</div>}
        </div>
      )}
    </form>
  );
}
