import { useCallback, useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { api } from '../api/client';
import type { Rule } from '../api/types';
import { hasRole, session } from '../auth/session';
import { ErrorBanner, fieldErrorsOf } from '../components/ErrorBanner';
import { StatusBadge } from '../components/StatusBadge';
import { HistoryPanel } from './HistoryPanel';
import { PreviewForm } from './PreviewForm';
import { RuleForm } from './RuleForm';
import type { RuleFormState } from './ruleForm';
import { emptyForm, formFromRule, toRequest } from './ruleForm';

/**
 * Screen 2: create or edit a rule, and move it through its lifecycle. Every write sends the
 * ETag from the last read, so two people editing the same rule can't overwrite each other.
 */
export function RuleEditorPage() {
  const { ruleId } = useParams();
  const isNew = !ruleId;
  const navigate = useNavigate();
  const user = session.get();
  const isAnalyst = hasRole(user, 'ANALYST');
  const isApprover = hasRole(user, 'APPROVER');

  const [rule, setRule] = useState<Rule | null>(null);
  const [etag, setEtag] = useState('');
  const [form, setForm] = useState<RuleFormState>(emptyForm());
  const [dirty, setDirty] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [rejecting, setRejecting] = useState(false);
  const [reason, setReason] = useState('');
  const [refreshKey, setRefreshKey] = useState(0);

  const show = useCallback((r: Rule, tag: string) => {
    setRule(r);
    setEtag(tag);
    setForm(formFromRule(r));
    setDirty(false);
    setRefreshKey((k) => k + 1);
  }, []);

  const load = useCallback(async () => {
    if (!ruleId) return;
    const { data, etag: tag } = await api.getRule(ruleId);
    show(data, tag);
  }, [ruleId, show]);

  useEffect(() => {
    load().catch(setError);
  }, [load]);

  // Approved or retiring: DPE delivery is asynchronous, so poll until it settles.
  useEffect(() => {
    if (rule?.status !== 'APPROVED' && rule?.status !== 'RETIRE_PENDING') return;
    const timer = setInterval(() => load().catch(() => undefined), 3000);
    return () => clearInterval(timer);
  }, [rule?.status, load]);

  async function run(action: () => Promise<void>, message?: string) {
    setError(null);
    setNotice(null);
    setBusy(true);
    try {
      await action();
      if (message) setNotice(message);
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  }

  const save = () =>
    run(async () => {
      if (isNew) {
        const { data } = await api.createRule(toRequest(form));
        navigate(`/rules/${data.ruleId}`, { replace: true });
      } else {
        const { data, etag: tag } = await api.updateRule(ruleId!, toRequest(form), etag);
        show(data, tag);
      }
    }, 'Saved');

  const status = rule?.status;
  const editable = isNew || (isAnalyst && (status === 'DRAFT' || status === 'PUBLISHED'));
  const ownSubmission = !!rule?.submittedBy && rule.submittedBy === user?.subject;
  const fieldErrors = fieldErrorsOf(error);

  return (
    <section>
      <div className="page-head">
        <div>
          <Link to="/rules" className="muted">
            ← All rules
          </Link>
          <h1>
            {isNew ? 'New rule' : rule?.name ?? 'Loading…'}{' '}
            {rule && (
              <>
                <span className="muted">v{rule.version}</span> <StatusBadge status={rule.status} />
              </>
            )}
          </h1>
          {rule && (
            <p className="muted">
              {rule.ruleId} · specificity {rule.specificity} · created by {rule.createdBy}
              {rule.approvedBy && ` · approved by ${rule.approvedBy}`}
            </p>
          )}
        </div>
        <div className="actions" role="toolbar">
          {editable && (
            <button onClick={save} disabled={busy || (!isNew && !dirty)}>
              {status === 'PUBLISHED' ? 'Save as new version' : 'Save draft'}
            </button>
          )}
          {isAnalyst && status === 'DRAFT' && (
            <button className="secondary" disabled={busy || dirty} title={dirty ? 'Save first' : undefined}
              onClick={() => run(async () => { const r = await api.submit(ruleId!, etag); show(r.data, r.etag); }, 'Submitted for approval')}>
              Submit for approval
            </button>
          )}
          {isApprover && status === 'PENDING_APPROVAL' && (
            <>
              <button disabled={busy || ownSubmission} title={ownSubmission ? 'You submitted this rule; another approver must approve it' : undefined}
                onClick={() => run(async () => { const r = await api.approve(ruleId!, etag); show(r.data, r.etag); }, 'Approved and queued for DPE')}>
                Approve
              </button>
              <button className="secondary" disabled={busy} onClick={() => setRejecting(true)}>
                Reject
              </button>
            </>
          )}
          {isApprover && status === 'PUBLISHED' && (
            <button className="danger" disabled={busy}
              onClick={() => window.confirm('Retire this rule? DPE stops offering points with it after the next publish.') &&
                run(async () => { const r = await api.retire(ruleId!); show(r.data, r.etag); }, 'Retirement queued for DPE')}>
              Retire
            </button>
          )}
          {isAnalyst && rule && (
            <button className="secondary" disabled={busy}
              onClick={() => run(async () => { const r = await api.cloneRule(ruleId!); navigate(`/rules/${r.data.ruleId}`); })}>
              Clone
            </button>
          )}
        </div>
      </div>

      {notice && <div className="alert alert-ok" role="status">{notice}</div>}
      <ErrorBanner error={error} />
      {rule?.rejectionReason && status === 'DRAFT' && (
        <div className="alert alert-warn">Rejected: {rule.rejectionReason}</div>
      )}
      {status === 'APPROVED' && <div className="alert alert-info">Approved. Publishing to DPE… this page refreshes when it's live.</div>}
      {status === 'PUBLISHED' && isAnalyst && (
        <div className="alert alert-info">Live. Saving changes creates version {rule!.version + 1} as a draft; this version stays live until the new one is published.</div>
      )}

      {rejecting && (
        <div className="card">
          <label className="field wide">
            <span>Why are you rejecting this rule?</span>
            <textarea aria-label="Rejection reason" value={reason} onChange={(e) => setReason(e.target.value)} rows={3} />
          </label>
          <button disabled={!reason.trim() || busy}
            onClick={() => run(async () => { const r = await api.reject(ruleId!, reason.trim()); show(r.data, r.etag); setRejecting(false); setReason(''); }, 'Rejected; returned to the analyst')}>
            Confirm reject
          </button>{' '}
          <button className="link" onClick={() => setRejecting(false)}>Cancel</button>
        </div>
      )}

      <div className={isNew ? '' : 'two-col'}>
        <div className="card">
          <RuleForm form={form} errors={fieldErrors} readOnly={!editable}
            onChange={(f) => { setForm(f); setDirty(true); }} />
        </div>
        {!isNew && ruleId && (
          <div className="side">
            <div className="card">
              <h3>Preview this rule</h3>
              <PreviewForm defaultRuleSet="DRAFT" initial={{
                origin: rule?.scope.origins[0], destination: rule?.scope.destinations[0],
                cabin: rule?.scope.cabins[0], fareBrand: rule?.scope.fareBrands[0],
                pointOfSale: rule?.scope.pointsOfSale[0], currency: rule?.rates[0]?.currency,
              }} key={rule?.revision} />
            </div>
            <HistoryPanel ruleId={ruleId} refreshKey={refreshKey} />
          </div>
        )}
      </div>
    </section>
  );
}
