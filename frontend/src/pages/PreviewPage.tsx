import { PreviewForm } from './PreviewForm';

/** Screen 3: price any sample offer against live rules or everyone's latest drafts. */
export function PreviewPage() {
  return (
    <section>
      <h1>Preview points price</h1>
      <p className="muted">Check what a traveller would see before and after you publish.</p>
      <div className="card">
        <PreviewForm defaultRuleSet="LIVE" />
      </div>
    </section>
  );
}
