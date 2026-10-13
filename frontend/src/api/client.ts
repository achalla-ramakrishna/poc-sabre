import { session } from '../auth/session';
import type {
  History,
  PreviewRequest,
  PreviewResponse,
  Problem,
  Rule,
  RuleRequest,
  RuleStatus,
  RuleSummary,
} from './types';

/** Every HTTP call goes through here: auth header, correlation id, ETag and error mapping. */
export class ApiError extends Error {
  constructor(public readonly problem: Problem) {
    super(problem.detail);
  }
}

/** A rule plus the ETag the next write must send back as If-Match. */
export interface Versioned<T> {
  data: T;
  etag: string;
}

async function call<T>(path: string, init: RequestInit = {}): Promise<Versioned<T>> {
  const headers = new Headers(init.headers);
  if (init.body) headers.set('Content-Type', 'application/json');
  headers.set('X-Correlation-Id', crypto.randomUUID());
  const token = session.get()?.token;
  if (token) headers.set('Authorization', `Bearer ${token}`);

  const res = await fetch(path, { ...init, headers });
  if (!res.ok) {
    const problem = (await res.json().catch(() => null)) as Problem | null;
    if (res.status === 401) session.clear();
    throw new ApiError(
      problem ?? { status: res.status, detail: res.statusText || 'Request failed', errorCode: 'HTTP_' + res.status },
    );
  }
  const data = (res.status === 204 ? undefined : await res.json()) as T;
  return { data, etag: res.headers.get('ETag') ?? '' };
}

const json = (body: unknown) => JSON.stringify(body);
const ifMatch = (etag: string) => ({ 'If-Match': etag });

export const api = {
  devToken: (subject: string, airline: string, roles: string[]) =>
    call<{ accessToken: string }>('/dev/token', { method: 'POST', body: json({ subject, airline, roles }) }).then(
      (r) => r.data.accessToken,
    ),

  listRules: (status?: RuleStatus) =>
    call<RuleSummary[]>('/api/v1/rules' + (status ? `?status=${status}` : '')).then((r) => r.data),

  getRule: (id: string) => call<Rule>(`/api/v1/rules/${encodeURIComponent(id)}`),

  createRule: (body: RuleRequest) => call<Rule>('/api/v1/rules', { method: 'POST', body: json(body) }),

  updateRule: (id: string, body: RuleRequest, etag: string) =>
    call<Rule>(`/api/v1/rules/${encodeURIComponent(id)}`, { method: 'PUT', body: json(body), headers: ifMatch(etag) }),

  submit: (id: string, etag: string) =>
    call<Rule>(`/api/v1/rules/${encodeURIComponent(id)}/submit`, { method: 'POST', headers: ifMatch(etag) }),

  approve: (id: string, etag: string) =>
    call<Rule>(`/api/v1/rules/${encodeURIComponent(id)}/approve`, { method: 'POST', headers: ifMatch(etag) }),

  reject: (id: string, reason: string) =>
    call<Rule>(`/api/v1/rules/${encodeURIComponent(id)}/reject`, { method: 'POST', body: json({ reason }) }),

  retire: (id: string) => call<Rule>(`/api/v1/rules/${encodeURIComponent(id)}/retire`, { method: 'POST' }),

  cloneRule: (id: string) => call<Rule>(`/api/v1/rules/${encodeURIComponent(id)}/clone`, { method: 'POST' }),

  history: (id: string) => call<History>(`/api/v1/rules/${encodeURIComponent(id)}/history`).then((r) => r.data),

  preview: (body: PreviewRequest) =>
    call<PreviewResponse>('/api/v1/preview', { method: 'POST', body: json(body) }).then((r) => r.data),
};
