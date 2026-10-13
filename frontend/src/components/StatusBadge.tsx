import type { RuleStatus } from '../api/types';

const LABELS: Record<RuleStatus, string> = {
  DRAFT: 'Draft',
  PENDING_APPROVAL: 'Pending approval',
  APPROVED: 'Approved, publishing',
  PUBLISHED: 'Live',
  SUPERSEDED: 'Superseded',
  RETIRE_PENDING: 'Retiring',
  RETIRED: 'Retired',
};

export function StatusBadge({ status }: { status: RuleStatus }) {
  return <span className={`badge badge-${status.toLowerCase()}`}>{LABELS[status]}</span>;
}
