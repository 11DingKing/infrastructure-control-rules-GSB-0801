# Critical Facility Control-Rules Service

Explains **why** a facility is placed under a control action and **which versioned
rule** decided it. Rules stack from a `default` layer up to a `manual` override
layer; evaluation is a pure, side-effect-free function; persistence and
notification only *consume* the result — they never take part in the judgement.

Built with **Kotlin**, **Ktor**, **Exposed** and **SQLite**.

## Domain scenarios

Governs tunnels, waterlogged roads, schools and temporary structures. Risk
metrics: hourly rainfall (mm), wind force level (Beaufort), water depth (cm).

## Design guarantees

| Requirement | Where it lives |
|---|---|
| Pure, side-effect-free evaluation | [RuleEvaluator.kt](src/main/kotlin/com/gsb/control/domain/RuleEvaluator.kt) — no I/O, no clock reads, all inputs explicit |
| Conflict order `MANUAL > FACILITY > REGION > DEFAULT` | [RuleLayer.kt](src/main/kotlin/com/gsb/control/domain/RuleLayer.kt) priorities; highest layer with a fired rule decides |
| Same priority → stricter action | [Action.kt](src/main/kotlin/com/gsb/control/domain/Action.kt) `strictness`; ties broken deterministically by `versionRef` |
| No `!!` in public API | Enforced by review; `sealed` result types + `?:` everywhere |
| Enumerable failure reasons | [ReasonCode.kt](src/main/kotlin/com/gsb/control/domain/ReasonCode.kt); parsing → [ParseError](src/main/kotlin/com/gsb/control/api/Mappers.kt); service → [ServiceError](src/main/kotlin/com/gsb/control/app/ControlService.kt) |
| Persistence/notification consume results only | [ControlService.kt](src/main/kotlin/com/gsb/control/app/ControlService.kt) runs the pure evaluator *first*, then persists/notifies |
| Every result pins hit versions, input snapshot, full explanation chain | [EvaluationResult.kt](src/main/kotlin/com/gsb/control/domain/EvaluationResult.kt) + [EvaluationResultRepository.kt](src/main/kotlin/com/gsb/control/persistence/EvaluationResultRepository.kt) |
| Byte-identical determinism | `canonicalString()` on [EvaluationResult.kt](src/main/kotlin/com/gsb/control/domain/EvaluationResult.kt) / [RiskInput.kt](src/main/kotlin/com/gsb/control/domain/RiskInput.kt), SHA-256 [Digest.kt](src/main/kotlin/com/gsb/control/persistence/Digest.kt) |
| History cannot peek at later rules | `asOf` filter in [RuleEvaluator.kt](src/main/kotlin/com/gsb/control/domain/RuleEvaluator.kt); rules with `publishedAt > asOf` → `NOT_PUBLISHED_AS_OF` |
| Concurrent same-version publish | UNIQUE `(rule_key, version)` index + serialized writes in [Database.kt](src/main/kotlin/com/gsb/control/persistence/Database.kt); exactly one winner, rest get `RULE_VERSION_CONFLICT` |
| Batch keeps reproducible baseline, no domain bypass | `/evaluate/batch` runs the identical pure path per input; returns a `batchDigest` |

### Rule validity model

Validity is a half-open interval `[validFrom, validUntil)`:
- before `validFrom` → `NOT_YET_EFFECTIVE`
- at or after `validUntil` (exclusive) → `EXPIRED` — so "exactly at expiry" is
  deterministically expired
- `validUntil = null` → never expires

### Versioning

A logical rule (`ruleKey`) has monotonically increasing `version`s. For a given
evaluation the newest published + valid version is active; older versions report
`SUPERSEDED_BY_NEWER_VERSION`. Revisions are immutable (append-only) — history is
never rewritten.

## Layout

```
src/main/kotlin/com/gsb/control/
  domain/        # pure core: Action, RuleLayer, ReasonCode, Rule, Condition,
                 # RiskInput, EvaluationResult, RuleEvaluator  (no frameworks)
  persistence/   # Exposed tables, migrations, repositories, DomainCodec, Digest
  app/           # ControlService (orchestration), Seed, EvaluationNotifier
  api/           # Ktor DTOs, Mappers (enumerable ParseError), Routes
  Application.kt # Ktor module + main()
src/main/resources/openapi.yaml   # OpenAPI 3.0 spec (served at /openapi.yaml)
```

## Seed

On boot the service seeds facility `tunnel-17` (region `440800`) and four
layered rules — `default`, `region:440800`, `facility:tunnel-17`, and an expiring
`manual:tunnel-17` override. See [Seed.kt](src/main/kotlin/com/gsb/control/app/Seed.kt).

With the reference input (**72 mm/h rainfall, wind force 7, 18 cm water**) all four
rules fire; the manual `RESTRICT` override wins over the facility `CLOSE`. Once the
manual rule expires (6h), the facility `CLOSE` becomes the decision.

## Build, test, run (native Gradle)

Prerequisites: JDK 17 (the build pins a Kotlin JVM toolchain to 17). The Gradle
wrapper (8.14) is checked in.

```bash
# Build (compile + assemble)
./gradlew build

# Run the full table-driven + integration + API test suite
./gradlew test

# Start the HTTP server (defaults: port 8080, db at ./data/control.db)
./gradlew run

# Override port / db path
CONTROL_PORT=8099 CONTROL_DB_PATH=/tmp/control.db ./gradlew run
```

## HTTP API

| Method & path | Purpose |
|---|---|
| `GET  /health` | liveness |
| `GET  /openapi.yaml` | OpenAPI 3.0 spec |
| `POST /facilities` | create/update a facility |
| `GET  /facilities/{id}` | fetch a facility |
| `GET  /facilities/{id}/results` | stored results for a facility |
| `POST /rules` | publish an immutable versioned rule revision (`409` on version conflict) |
| `GET  /rules/{ruleKey}` | list all revisions of a logical rule |
| `POST /evaluate` | evaluate, persist and notify; returns decision + full explanation |
| `POST /evaluate/batch` | evaluate many inputs through the identical pure path; returns reproducible `batchDigest` |
| `GET  /results/{id}` | a stored evaluation result |
| `GET  /results/{id}/explanation` | just the explanation chain (audit) |

### Example

```bash
curl -s -X POST http://localhost:8080/evaluate \
  -H 'Content-Type: application/json' \
  -d '{
        "facilityId": "tunnel-17",
        "input": {"hourly_rainfall_mm": 72, "wind_force_level": 7, "water_depth_cm": 18},
        "evaluatedAt": "2026-08-01T00:00:00Z",
        "asOf": "2026-08-01T00:00:00Z"
      }'
```

Returns `decision: RESTRICT`, `decidingLayer: MANUAL`, the fired version refs, a
`canonicalDigest`, and a four-line explanation chain showing the lower layers as
`SUPERSEDED_BY_HIGHER_LAYER`.

### Historical replay

Pass an `asOf` earlier than a rule's `publishedAt` to reproduce a past decision;
rules published later are invisible and reported as `NOT_PUBLISHED_AS_OF`.
