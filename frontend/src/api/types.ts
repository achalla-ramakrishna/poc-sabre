// Mirrors the backend's REST shapes (RuleDtos, PreviewController, RuleController.History).

export type Cabin = 'ECONOMY' | 'PREMIUM_ECONOMY' | 'BUSINESS' | 'FIRST';
export const CABINS: Cabin[] = ['ECONOMY', 'PREMIUM_ECONOMY', 'BUSINESS', 'FIRST'];

export type RuleStatus =
  | 'DRAFT'
  | 'PENDING_APPROVAL'
  | 'APPROVED'
  | 'PUBLISHED'
  | 'SUPERSEDED'
  | 'RETIRE_PENDING'
  | 'RETIRED';

export type Role = 'VIEWER' | 'ANALYST' | 'APPROVER' | 'ADMIN';

export interface Scope {
  origins: string[];
  destinations: string[];
  cabins: Cabin[];
  fareBrands: string[];
  bundles: string[];
  pointsOfSale: string[];
}

/** pointsPerUnit is a decimal string ("47.8"): never a float. */
export interface Rate {
  fareBrand: string | null;
  pointsPerUnit: string;
  currency: string;
  roundingStep: number;
}

export interface Limits {
  maxRedeemPercent: number;
  minPoints: number;
}

export interface RuleRequest {
  name: string;
  scope: Scope;
  rates: Rate[];
  limits: Limits;
  effectiveFrom: string;
  effectiveTo: string | null;
}

export interface Rule extends RuleRequest {
  ruleId: string;
  version: number;
  status: RuleStatus;
  airline: string;
  specificity: number;
  createdBy: string;
  createdAt: string;
  submittedBy?: string;
  approvedBy?: string;
  approvedAt?: string;
  publishedAt?: string;
  rejectionReason?: string;
  revision: number;
}

export interface RuleSummary {
  ruleId: string;
  name: string;
  latestVersion: number;
  latestStatus: RuleStatus;
  liveVersion?: number;
  effectiveFrom: string;
  effectiveTo?: string;
  specificity: number;
}

export interface AuditEvent {
  id: string;
  version?: number;
  action: string;
  actor: string;
  before?: Record<string, unknown>;
  after?: Record<string, unknown>;
  correlationId?: string;
  at: string;
}

export interface Publication {
  id: string;
  version: number;
  operation: 'UPSERT' | 'RETIRE';
  mode: 'REALTIME' | 'BATCH';
  status: 'PENDING' | 'ACKED' | 'FAILED';
  attempts: number;
  nextAttemptAt?: string;
  lastError?: string;
  dpeReference?: string;
  createdAt: string;
  completedAt?: string;
}

export interface History {
  events: AuditEvent[];
  publications: Publication[];
}

export interface PreviewRequest {
  ruleSet: 'LIVE' | 'DRAFT';
  origin: string;
  destination: string;
  cabin: Cabin;
  fareBrand?: string;
  bundle?: string;
  pointOfSale?: string;
  fareAmount: string;
  currency: string;
  memberBalance?: number;
  saleDate?: string;
}

export interface PreviewResponse {
  cashOnly: boolean;
  pointsRequired: number;
  pointsUsable: number;
  ruleId?: string;
  ruleVersion?: number;
  ruleName?: string;
  tiedWith: string[];
  message: string;
}

/** RFC 9457 problem detail as produced by GlobalExceptionHandler. */
export interface Problem {
  status: number;
  title?: string;
  detail: string;
  errorCode: string;
  correlationId?: string;
  fieldErrors?: Record<string, string>;
}
