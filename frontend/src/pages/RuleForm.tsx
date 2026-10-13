import type { Cabin } from '../api/types';
import { CABINS } from '../api/types';
import { CodeListInput } from '../components/CodeListInput';
import type { RuleFormState } from './ruleForm';
import { ROUNDING_STEPS } from './ruleForm';

interface Props {
  form: RuleFormState;
  onChange: (f: RuleFormState) => void;
  errors: Record<string, string>;
  readOnly: boolean;
}

/** Screen 2 body: scope, rates (UC-03), limits and dates. Field errors come straight from the API. */
export function RuleForm({ form, onChange, errors, readOnly }: Props) {
  const set = <K extends keyof RuleFormState>(key: K, value: RuleFormState[K]) => onChange({ ...form, [key]: value });
  const setRate = (i: number, patch: Partial<RuleFormState['rates'][number]>) =>
    set(
      'rates',
      form.rates.map((r, j) => (j === i ? { ...r, ...patch } : r)),
    );
  const toggleCabin = (c: Cabin) =>
    set('cabins', form.cabins.includes(c) ? form.cabins.filter((x) => x !== c) : [...form.cabins, c]);

  return (
    <fieldset className="rule-form" disabled={readOnly}>
      <label className="field wide">
        <span>Rule name</span>
        <input aria-label="Rule name" value={form.name} onChange={(e) => set('name', e.target.value)} className={errors.name ? 'invalid' : undefined} />
        {errors.name && <small className="error-text">{errors.name}</small>}
      </label>

      <h3>Scope</h3>
      <div className="grid">
        <CodeListInput label="Origins" placeholder="SYD, MEL" value={form.origins} onChange={(v) => set('origins', v)} error={errors['scope.origins']} disabled={readOnly} />
        <CodeListInput label="Destinations" placeholder="PER" value={form.destinations} onChange={(v) => set('destinations', v)} error={errors['scope.destinations']} disabled={readOnly} />
        <CodeListInput label="Fare brands" placeholder="CHOICE, LITE" value={form.fareBrands} onChange={(v) => set('fareBrands', v)} error={errors['scope.fareBrands']} disabled={readOnly} />
        <CodeListInput label="Bundles" value={form.bundles} onChange={(v) => set('bundles', v)} error={errors['scope.bundles']} disabled={readOnly} />
        <CodeListInput label="Points of sale" placeholder="AU" value={form.pointsOfSale} onChange={(v) => set('pointsOfSale', v)} error={errors['scope.pointsOfSale']} disabled={readOnly} />
        <div className="field">
          <span>Cabins</span>
          <div className="checks">
            {CABINS.map((c) => (
              <label key={c} className="check">
                <input type="checkbox" checked={form.cabins.includes(c)} onChange={() => toggleCabin(c)} />
                {c.replace('_', ' ').toLowerCase()}
              </label>
            ))}
          </div>
          <small className="hint">None ticked = all cabins</small>
        </div>
      </div>

      <h3>Conversion rates</h3>
      {errors.rates && <p className="error-text">{errors.rates}</p>}
      <table className="rates">
        <thead>
          <tr>
            <th>Fare brand (blank = default)</th>
            <th>Points per unit</th>
            <th>Currency</th>
            <th>Round up to</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {form.rates.map((r, i) => (
            <tr key={i}>
              <td>
                <input aria-label={`Rate ${i + 1} fare brand`} value={r.fareBrand} onChange={(e) => setRate(i, { fareBrand: e.target.value.toUpperCase() })} className={errors[`rates[${i}].fareBrand`] ? 'invalid' : undefined} />
                {errors[`rates[${i}].fareBrand`] && <small className="error-text">{errors[`rates[${i}].fareBrand`]}</small>}
              </td>
              <td>
                <input aria-label={`Rate ${i + 1} points per unit`} inputMode="decimal" value={r.pointsPerUnit} onChange={(e) => setRate(i, { pointsPerUnit: e.target.value })} className={errors[`rates[${i}].pointsPerUnit`] ? 'invalid' : undefined} />
                {errors[`rates[${i}].pointsPerUnit`] && <small className="error-text">{errors[`rates[${i}].pointsPerUnit`]}</small>}
              </td>
              <td>
                <input aria-label={`Rate ${i + 1} currency`} maxLength={3} value={r.currency} onChange={(e) => setRate(i, { currency: e.target.value.toUpperCase() })} className={errors[`rates[${i}].currency`] ? 'invalid' : undefined} />
              </td>
              <td>
                <select aria-label={`Rate ${i + 1} rounding`} value={r.roundingStep} onChange={(e) => setRate(i, { roundingStep: Number(e.target.value) })}>
                  {ROUNDING_STEPS.map((s) => (
                    <option key={s} value={s}>
                      {s}
                    </option>
                  ))}
                </select>
              </td>
              <td>
                {form.rates.length > 1 && (
                  <button type="button" className="link" onClick={() => set('rates', form.rates.filter((_, j) => j !== i))}>
                    Remove
                  </button>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {!readOnly && (
        <button type="button" className="secondary" onClick={() => set('rates', [...form.rates, { fareBrand: '', pointsPerUnit: '', currency: form.rates[0]?.currency ?? 'AUD', roundingStep: 100 }])}>
          Add rate
        </button>
      )}

      <h3>Limits and dates</h3>
      <div className="grid">
        <label className="field">
          <span>Max redeem % of balance</span>
          <input aria-label="Max redeem percent" type="number" min={0} max={100} value={form.maxRedeemPercent} onChange={(e) => set('maxRedeemPercent', e.target.value)} className={errors['limits.maxRedeemPercent'] ? 'invalid' : undefined} />
          {errors['limits.maxRedeemPercent'] && <small className="error-text">{errors['limits.maxRedeemPercent']}</small>}
        </label>
        <label className="field">
          <span>Minimum points</span>
          <input aria-label="Minimum points" type="number" min={0} value={form.minPoints} onChange={(e) => set('minPoints', e.target.value)} />
        </label>
        <label className="field">
          <span>Effective from</span>
          <input aria-label="Effective from" type="date" value={form.effectiveFrom} onChange={(e) => set('effectiveFrom', e.target.value)} />
        </label>
        <label className="field">
          <span>Effective to (optional)</span>
          <input aria-label="Effective to" type="date" value={form.effectiveTo} onChange={(e) => set('effectiveTo', e.target.value)} className={errors.effectiveTo ? 'invalid' : undefined} />
          {errors.effectiveTo && <small className="error-text">{errors.effectiveTo}</small>}
        </label>
      </div>
    </fieldset>
  );
}
