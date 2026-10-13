import { ApiError } from '../api/client';

/** Shows a Problem Detail's message and correlation id (quote it to support). */
export function ErrorBanner({ error }: { error: unknown }) {
  if (!error) return null;
  const problem = error instanceof ApiError ? error.problem : null;
  const message = problem?.detail ?? (error instanceof Error ? error.message : 'Unexpected error');
  return (
    <div role="alert" className="alert alert-error">
      <strong>{message}</strong>
      {problem?.correlationId && <span className="muted"> · ref {problem.correlationId}</span>}
    </div>
  );
}

export function fieldErrorsOf(error: unknown): Record<string, string> {
  return error instanceof ApiError ? (error.problem.fieldErrors ?? {}) : {};
}
