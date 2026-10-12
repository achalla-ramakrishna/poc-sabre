# Rewards IQ

Rewards IQ lets airline loyalty teams decide how points can be redeemed on flights, and supplies
those rules to the Dynamic Pricing Engine (DPE) so travellers see prices like
"AUD 900 or 43,100 points" while they shop.

- **Analysts** create redemption rules over REST: scope (route, cabin, fare brand, bundle, point of
  sale), points-per-currency rates, a cap on how much of a member's balance can be used, and
  effective dates.
- **Approvers** review them (maker-checker). Approval writes the rule and an **outbox** row in one
  MongoDB transaction.
- A **publisher worker** delivers approved rules to DPE over **gRPC**, with retries and idempotency
  keys. Plan B: a CSV snapshot file instead (`PUBLISH_MODE=BATCH`).
- **DPE prices offers** by calling the **gRPC** `RedemptionPricing` service, which answers from an
  in-memory rules cache and never touches the database on the shopping path.

Stack: Java 17 · Spring Boot 3.3 · Spring Security (OAuth2 resource server / JWT) · MongoDB ·
gRPC + Protocol Buffers · JUnit 5 · Mockito · Spock · Testcontainers.

## Run it locally with your own MongoDB

### 1. Make MongoDB a replica set (one time)

Approving a rule uses a multi-document transaction, and MongoDB only supports transactions on a
replica set. A single-node replica set is enough:

1. Add this to your `mongod.conf`:
   ```yaml
   replication:
     replSetName: rs0
   ```
   The file is usually at:
   - macOS (Homebrew): `/opt/homebrew/etc/mongod.conf` (Apple silicon) or `/usr/local/etc/mongod.conf`
   - Windows: `C:\Program Files\MongoDB\Server\<version>\bin\mongod.cfg`
   - Linux: `/etc/mongod.conf`
2. Restart MongoDB:
   - macOS: `brew services restart mongodb-community`
   - Windows: restart the **MongoDB** service in Services
   - Linux: `sudo systemctl restart mongod`
3. Initiate the replica set once: `mongosh --eval 'rs.initiate()'`
4. Check it: `mongosh --eval 'rs.status().ok'` prints `1`.

Your existing databases are untouched.

### 2. Start the backend

```bash
cd backend
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

The `dev` profile:
- connects to `mongodb://localhost:27017/rewardsiq?directConnection=true` (override with `MONGODB_URI`)
- issues local test tokens at `POST /dev/token` (no identity provider needed)
- starts a **stub DPE** on port 9191, so publishing works end to end
- writes Plan B batch files to `./exports` every 5 minutes

Ports: REST on **8080**, gRPC pricing on **9090**. Readiness:
`curl localhost:8080/actuator/health/readiness` returns `UP` once MongoDB is reachable and the rules
cache has loaded.

### 3. Try the flow

Open `api-clients/bruno/RewardsIQ` in [Bruno](https://www.usebruno.com/), pick the `local`
environment, and run the requests in order:

1. Auth: Analyst token, Approver token
2. Rules: 1 Create, 2 Submit, 3 Approve (returns 202, queued for DPE)
3. A few seconds later, 4 Get rule shows `PUBLISHED`, and 5 History shows the DPE acknowledgement
4. Preview: SYD to PER, AUD 900, shows **43,100 points**

Or with curl:

```bash
TOKEN=$(curl -s localhost:8080/dev/token -H 'Content-Type: application/json' \
  -d '{"subject":"u-analyst","airline":"VA","roles":["ANALYST"]}' | jq -r .accessToken)
curl -s localhost:8080/api/v1/rules -H "Authorization: Bearer $TOKEN"
```

Call the gRPC pricing API as DPE would (needs a token with the `rewardsiq.pricing` scope):

```bash
SVC=$(curl -s localhost:8080/dev/token -H 'Content-Type: application/json' \
  -d '{"subject":"dpe-service","scope":"rewardsiq.pricing"}' | jq -r .accessToken)
grpcurl -plaintext -import-path backend/src/main/proto -proto rewardsiq/pricing/v1/pricing.proto \
  -H "authorization: Bearer $SVC" \
  -d '{"airline":"VA","member_balance":100000,"offers":[{"offer_id":"of-1","origin":"SYD","destination":"PER",
       "travel_date":"2026-11-14","cabin":"ECONOMY","fare_brand":"CHOICE","point_of_sale":"AU",
       "fare":{"amount":"900.00","currency":"AUD"}}]}' \
  localhost:9090 rewardsiq.pricing.v1.RedemptionPricing/PriceOffers
```

No local MongoDB? `docker compose up --build` starts a replica-set MongoDB and the backend together.

## Tests

```bash
cd backend
mvn test     # 60 unit, web-slice, gRPC in-process and Spock tests
mvn verify   # + RewardsIqFlowIT end to end against MongoDB in Docker (skipped without Docker) + JaCoCo
```

## API

| Method | Path | Role | Purpose |
| --- | --- | --- | --- |
| GET | `/api/v1/rules` | any | Rule list (latest version + live version per rule) |
| GET | `/api/v1/rules/{id}` | any | Latest version, with `ETag` |
| GET | `/api/v1/rules/{id}/versions[/{v}]` | any | All versions / one version |
| POST | `/api/v1/rules` | ANALYST | Create (DRAFT v1) |
| PUT | `/api/v1/rules/{id}` | ANALYST | Edit draft, or create next version of a published rule. `If-Match` |
| POST | `/api/v1/rules/{id}/clone` | ANALYST | Copy into a new rule |
| POST | `/api/v1/rules/{id}/submit` | ANALYST | DRAFT to PENDING_APPROVAL. `If-Match` |
| POST | `/api/v1/rules/{id}/approve` | APPROVER | Approve and queue for DPE (202). Not by the submitter. `If-Match` |
| POST | `/api/v1/rules/{id}/reject` | APPROVER | Back to DRAFT with a reason |
| POST | `/api/v1/rules/{id}/retire` | APPROVER | Remove from DPE at next publish (202) |
| GET | `/api/v1/rules/{id}/history` | any | Audit trail + DPE publication status |
| POST | `/api/v1/preview` | any | Points price for a sample offer, live or draft rules |
| gRPC | `rewardsiq.pricing.v1.RedemptionPricing/PriceOffers` | service token, scope `rewardsiq.pricing` | Shopping-time pricing for DPE |

The airline always comes from the token's `airline` claim, never from the request. Errors are
RFC 9457 Problem Details with `errorCode`, `fieldErrors` and `correlationId`.

## Code map

```
backend/src/main/java/com/rewardsiq
├── rule       RedemptionRule (versioned document + state machine), validation, service, REST
├── pricing    PointsCalculator (BR-03/04), RuleIndex (BR-05 matching), RulesCache, preview, gRPC service
├── publish    Outbox (RulePublication), PublisherWorker, backoff
│   ├── dpe    gRPC client to DPE, proto mapper (anti-corruption layer), local stub DPE
│   └── batch  Plan B CSV snapshot export
├── audit      Append-only audit events
├── security   JWT resource server, roles, airline tenancy, dev token endpoint
├── grpc       gRPC server lifecycle, service-token interceptor
└── common     Error handling, correlation id filter, typed configuration, Mongo transactions
backend/src/main/proto   pricing.proto (we serve), publish.proto (DPE serves; proposed contract)
```

## Configuration

| Variable | Default | Meaning |
| --- | --- | --- |
| `MONGODB_URI` | `mongodb://localhost:27017/rewardsiq?directConnection=true` | Must be a replica set |
| `PUBLISH_MODE` | `REALTIME` | `BATCH` switches to the Plan B file export |
| `DPE_TARGET` | `dpe.internal:443` | DPE gRPC endpoint (`localhost:9191` in dev) |
| `DPE_SERVICE_TOKEN` | empty | Bearer token sent to DPE |
| `OIDC_ISSUER_URI` | empty | Identity provider for real environments |
| `DEV_JWT_SECRET` | set in `dev` profile only | HMAC key for `/dev/token` |
| `BATCH_EXPORT_ENABLED`, `BATCH_EXPORT_DIR` | `false`, `./exports` | Plan B snapshot files |

## Assumptions to confirm with Sabre / DPE

- `publish.proto` is our proposal for DPE's contract; swap in theirs when shared.
- The redemption cap applies to the member's balance (BR-04).
- Rules are in force by **sale date** (effective dates), not travel date.
- Maker-checker approval is required.
