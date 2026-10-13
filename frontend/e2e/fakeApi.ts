import type { Page, Route } from '@playwright/test';

/** Minimal in-memory imitation of the Rewards IQ REST API, enough to drive every screen. */
type Json = Record<string, any>;

const b64url = (o: object) => Buffer.from(JSON.stringify(o)).toString('base64url');
const token = (sub: string, roles: string[]) =>
  `${b64url({ alg: 'none' })}.${b64url({ sub, airline: 'VA', roles, exp: Math.floor(Date.now() / 1000) + 3600 })}.sig`;

export class FakeApi {
  rules = new Map<string, Json>();
  events: Json[] = [];
  publications: Json[] = [];
  private seq = 1;

  async install(page: Page) {
    if (process.env.E2E_BASE_URL) return; // real backend
    await page.route('**/dev/token', (route) => {
      const body = route.request().postDataJSON();
      return route.fulfill({ json: { accessToken: token(body.subject, body.roles), tokenType: 'Bearer' } });
    });
    await page.route('**/api/v1/**', (route) => this.handle(route));
  }

  private user(route: Route) {
    const auth = route.request().headers()['authorization'] ?? '';
    return JSON.parse(Buffer.from(auth.replace('Bearer ', '').split('.')[1], 'base64url').toString());
  }

  private send(route: Route, status: number, rule: Json) {
    return route.fulfill({ status, json: rule, headers: { ETag: `"${rule.revision}"` } });
  }

  private problem(route: Route, status: number, errorCode: string, detail: string, fieldErrors?: Json) {
    return route.fulfill({ status, contentType: 'application/problem+json',
      json: { status, errorCode, detail, fieldErrors, correlationId: 'cid-test-123' } });
  }

  private bump(rule: Json, patch: Json, action: string, actor: string) {
    Object.assign(rule, patch, { revision: rule.revision + 1 });
    this.events.unshift({ id: String(this.seq++), action, actor, version: rule.version, at: new Date().toISOString() });
    return rule;
  }

  private async handle(route: Route) {
    const req = route.request();
    const url = new URL(req.url());
    const path = url.pathname.replace('/api/v1', '');
    const method = req.method();
    const me = this.user(route);

    if (path === '/rules' && method === 'GET') {
      return route.fulfill({ json: [...this.rules.values()].map((r) => ({ ruleId: r.ruleId, name: r.name,
        latestVersion: r.version, latestStatus: r.status, liveVersion: r.status === 'PUBLISHED' ? r.version : undefined,
        effectiveFrom: r.effectiveFrom, effectiveTo: r.effectiveTo, specificity: r.specificity })) });
    }
    if (path === '/rules' && method === 'POST') {
      const body = req.postDataJSON();
      const errors: Json = {};
      if (Number(body.limits.maxRedeemPercent) > 100) errors['limits.maxRedeemPercent'] = 'must be between 0 and 100';
      if (body.scope.origins.some((o: string) => !/^[A-Z]{3}$/.test(o))) errors['scope.origins'] = '3-letter IATA airport codes';
      if (Object.keys(errors).length) return this.problem(route, 422, 'RULE_INVALID', 'The rule has invalid fields', errors);
      const ruleId = `rul_${String(this.seq++).padStart(4, '0')}`;
      const s = body.scope;
      const specificity = [s.origins, s.destinations, s.cabins, s.fareBrands, s.bundles, s.pointsOfSale].filter((x: string[]) => x.length).length;
      const rule = { ...body, ruleId, version: 1, status: 'DRAFT', airline: 'VA', specificity, createdBy: me.sub,
        createdAt: new Date().toISOString(), revision: 0 };
      this.rules.set(ruleId, rule);
      this.events.unshift({ id: String(this.seq++), action: 'CREATE', actor: me.sub, version: 1, at: rule.createdAt });
      return this.send(route, 201, rule);
    }

    const m = path.match(/^\/rules\/([^/]+)(?:\/(\w+))?$/);
    if (m) {
      const rule = this.rules.get(m[1]);
      if (!rule) return this.problem(route, 404, 'NOT_FOUND', `Rule '${m[1]}' was not found`);
      const action = m[2];
      const ifMatch = req.headers()['if-match'];
      if (['submit', 'approve'].includes(action ?? '') && ifMatch !== `"${rule.revision}"`) {
        return this.problem(route, 412, 'CONCURRENT_MODIFICATION', 'Reload and try again');
      }
      if (!action && method === 'GET') {
        // The publisher worker runs between polls: an approved rule goes live on the next read.
        if (rule.status === 'APPROVED') {
          this.publications.unshift({ id: String(this.seq++), version: rule.version, operation: 'UPSERT', mode: 'REALTIME',
            status: 'ACKED', attempts: 1, dpeReference: 'dpe-7001', createdAt: new Date().toISOString() });
          this.bump(rule, { status: 'PUBLISHED', publishedAt: new Date().toISOString() }, 'PUBLISHED', 'system');
        }
        return this.send(route, 200, rule);
      }
      if (action === 'submit') return this.send(route, 200, this.bump(rule, { status: 'PENDING_APPROVAL', submittedBy: me.sub }, 'SUBMIT', me.sub));
      if (action === 'approve') {
        if (rule.submittedBy === me.sub) return this.problem(route, 409, 'SELF_APPROVAL', "The person who submitted a rule can't approve it");
        return this.send(route, 202, this.bump(rule, { status: 'APPROVED', approvedBy: me.sub }, 'APPROVE', me.sub));
      }
      if (action === 'reject') {
        return this.send(route, 200, this.bump(rule, { status: 'DRAFT', rejectionReason: req.postDataJSON().reason }, 'REJECT', me.sub));
      }
      if (action === 'history') return route.fulfill({ json: { events: this.events, publications: this.publications } });
    }

    if (path === '/preview' && method === 'POST') {
      const body = req.postDataJSON();
      const live = [...this.rules.values()].find((r) => (body.ruleSet === 'DRAFT' || r.status === 'PUBLISHED')
        && r.scope.origins.includes(body.origin) && r.scope.destinations.includes(body.destination));
      if (!live) return route.fulfill({ json: { cashOnly: true, pointsRequired: 0, pointsUsable: 0, tiedWith: [], message: 'Cash only: no redemption rule covers this offer' } });
      const required = Math.ceil((Number(body.fareAmount) * Number(live.rates[0].pointsPerUnit)) / 100) * 100;
      const cap = Math.floor((body.memberBalance * live.limits.maxRedeemPercent) / 100 / 100) * 100;
      return route.fulfill({ json: { cashOnly: false, pointsRequired: required, pointsUsable: Math.min(required, cap),
        ruleId: live.ruleId, ruleVersion: live.version, ruleName: live.name, tiedWith: [],
        message: `${body.currency} ${body.fareAmount} or ${required.toLocaleString('en-US')} points` } });
    }
    return this.problem(route, 404, 'NOT_FOUND', `No fake for ${method} ${path}`);
  }
}
