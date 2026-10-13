/**
 * A comma-separated list of codes ("SYD, MEL"). Empty means "all" for that scope dimension,
 * which the hint says explicitly so analysts don't leave it empty by accident.
 */
export function CodeListInput(props: {
  label: string;
  value: string;
  onChange: (v: string) => void;
  placeholder?: string;
  error?: string;
  disabled?: boolean;
}) {
  return (
    <label className="field">
      <span>{props.label}</span>
      <input
        aria-label={props.label}
        value={props.value}
        placeholder={props.placeholder}
        disabled={props.disabled}
        onChange={(e) => props.onChange(e.target.value.toUpperCase())}
        className={props.error ? 'invalid' : undefined}
      />
      <small className={props.error ? 'error-text' : 'hint'}>{props.error ?? 'Comma-separated. Empty = all'}</small>
    </label>
  );
}

export const toCodes = (s: string) =>
  s
    .split(',')
    .map((v) => v.trim().toUpperCase())
    .filter(Boolean);

export const fromCodes = (codes: string[] | undefined) => (codes ?? []).join(', ');
