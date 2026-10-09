# Atlas

<!-- version: v2.4.0 -->
![Version](https://img.shields.io/badge/version-v2.4.0-blue)

A distributed rider-driver dispatch system in Java/Spring Boot. Three gRPC
microservices: **dispatch-service** (orchestrator, REST + live grid index +
quad-tree matching), **pricing-service** (pure calculator), and
**trip-service** (system of record, MySQL). Deployed to Kubernetes (Kind),
load-tested with k6, observed with Prometheus/Grafana, and chaos-tested by
killing a live pod mid-load.

See `.claude/CLAUDE.md` (not tracked in git) for the full design spec. This
file covers running the current build, either as local processes or as a
full Kubernetes deployment.

## Current stage

All application logic (three services) and all shared infrastructure (Docker,
Kubernetes/Kind, k6 load test, Prometheus/Grafana, chaos test) are built and
verified against a real local Kind cluster. See `.claude/CLAUDE.md`'s
Phase 10 changelog entry for what was found and fixed getting there.

v2 Phase 1 (live driver index) is built and verified: a
simulated heartbeat generator keeps driver positions moving, a grid-bucket
index narrows candidates per Dispatch replica, and both replicas stay
consistent via a periodic MySQL poll.

v2 Phase 1 close-out: the OSRM call in dispatch-service is now
non-blocking (WebClient/reactive), backed by a bounded admission-control layer
(a semaphore gate plus a fail-fast, zero-queue executor) instead of a bigger
thread pool. This fixed a real regression found along the way — an early async
attempt let far more requests in than the system could actually finish,
causing silent multi-second queueing instead of fast failure. Under sustained
real load past the system's actual capacity, it now rejects fast (503,
milliseconds) instead of hanging (10s+). See `.claude/context/decisions.md`
for the full story, including the wrong turn and how it was found and fixed.

v2 Phases 2-4 (on `main`):
- `POST /rides` takes an optional `Idempotency-Key` header, so a retry after a
  dispatch pod kill resolves to the same trip. A chaos run with the key reused on
  retry produced 75,074 keyed trips and zero duplicate keys.
- A local OSRM stub and `load-testing/stub-load-test.sh` measure Atlas without the
  public OSRM demo API.
- v2 Phase 5 (throughput to spec): the ride path now meets 500 req/s sustained against
  the stub (0.05-0.31% failed, p99 116-187 ms over 3 minutes, warm) after removing
  wasted database work and throttles: one `IN` query instead of 20 `SELECT`s per
  ride's candidate lookup, insert-first and single-statement updates in trip-service,
  a 40-connection trip pool, MySQL without the unused binary log, and removing a CPU
  and listen-backlog bottleneck in the stub itself. Ceiling is between 500 and 600
  req/s here.
- Trips left in `REQUESTED` by a crash are failed to `SYSTEM_ERROR` by a scheduled
  sweep (after 60s), and MySQL now runs on a persistent volume. A forced pod kill
  mid-burst left 81 stuck rows and the sweep resolved all 81.

## Architecture

```
Rider (REST) ──> dispatch-service ──gRPC──> pricing-service (no DB)
                       │
                       ├──gRPC──> trip-service (own MySQL DB: atlas_trip)
                       │
                       └── own Driver DB (MySQL: atlas_driver,
                           grid index + quad-tree scan, live heartbeats)
```

In Kubernetes: dispatch-service runs 2 replicas behind a Service, pricing and
trip run 1 each, MySQL runs in-cluster (one instance, two schemas, on a persistent volume), and
Prometheus/Grafana scrape all three services' Actuator endpoints.

## Running it in Kubernetes (Kind) — recommended

### Prerequisites

```
brew install colima docker kind kubectl k6
colima start --cpu 6 --memory 12 --disk 60
```

Java 26 and Maven are also required for the build step (see "Running it
locally" below for details on those). On Apple Silicon the Maven build also needs
Rosetta (`softwareupdate --install-rosetta`), because the grpc-java protoc plugin
from Maven Central is an x86_64 binary. The Kind setup runs 2 dispatch replicas, trip,
pricing, MySQL, Prometheus and Grafana, and the 500 req/s load runs need the 6 CPUs
configured above.

### One command

```
./scripts/start.sh
```

Builds all three jars, builds Docker images, creates (or reuses) the `atlas`
Kind cluster, deploys MySQL/dispatch/pricing/trip/Prometheus/Grafana, and
smoke-tests `POST /rides`. Fails loud (non-zero exit) on any step failure.

### Using it

```
kubectl -n atlas port-forward svc/dispatch-service 8080:8080
curl -X POST http://localhost:8080/rides \
  -H "Content-Type: application/json" \
  -d '{"riderId":"R-001","pickup":{"lat":37.7749,"lng":-122.4194},"drop":{"lat":37.7849,"lng":-122.4094}}'
```

### Grafana dashboard

```
kubectl -n atlas port-forward svc/grafana 3000:3000
```
Open `http://localhost:3000` (anonymous access enabled, no login needed).
The **Atlas Overview** dashboard is provisioned automatically: request
throughput, p50/p99 latency, error rate, and dispatch pod count over time.

### Prometheus (raw metrics / scrape targets)

```
kubectl -n atlas port-forward svc/prometheus 9090:9090
```
Open `http://localhost:9090/targets` to confirm all three services are `UP`.

### Load test

```
BASE_URL=http://localhost:8080 k6 run load-testing/rides-load-test.js
```
Ramps to 500 req/sec sustained against `POST /rides` with the public OSRM demo API
(`router.project-osrm.org`) as the routing source. That API runs 1.4-1.9s per call under
concurrency and was never meant to be load-tested, so this default profile measures the
demo server as much as Atlas, and it is not the profile to quote for throughput. Use the
stub profile below for Atlas's own numbers.

### Load test against a local OSRM stub

```
./load-testing/stub-load-test.sh 60s 150 200 250
```
Points dispatch-service at a local OSRM stand-in (`k8s/08-osrm-stub.yaml`, fixed
route, 50ms latency by default) and runs one fixed-rate k6 Job per rate, printing
failure percentage and p99 for each (after a discarded warm-up pass). This measures
Atlas itself instead of the public OSRM demo API. The override is removed on exit, so
public OSRM stays the default.

Measured on the local single-node Kind cluster (2 dispatch replicas, 1 pricing, 1 trip):

| Scenario | Rate | Failed | p99 |
|---|---|---|---|
| Stub, 50ms, warm, 3 min | 500 req/s | 0.05%, 0.21%, 0.31% (3 runs) | 116-187 ms |
| Stub, first run after a fresh rollout (cold JVMs) | 500 req/s | 3.27% | 1.87 s |
| Stub, 800ms (every call times out at 500ms and falls back), 60s | 500 req/s | 0.84% | 262 ms |
| Stub, 60s | 600 req/s | 65.07% | 4.07 s |
| Stub, 60s | 800 req/s | 85.76% | 2.02 s |

So 500 req/s sustained is met against the stub, and the ceiling sits between 500 and
600 req/s on this hardware. It is **not** measured against the public OSRM demo server at
that rate, on purpose (fair-use limits, and its latency is not Atlas's). Against the real
public default at a polite 10 req/s the run showed 1.97% failed, p99 615 ms.

### Chaos test

```
./load-testing/chaos-test.sh                 # public OSRM (default)
USE_OSRM_STUB=1 ./load-testing/chaos-test.sh # local OSRM stub, the clean measurement
```
Runs the load test as an in-cluster k6 Job, kills a dispatch-service pod
20 seconds in, and shows requests continuing to be served by the surviving
replica. Watch the Grafana "Dispatch Pod Count Over Time" panel live to see
the dip and recovery. Reverified after the v2 Phase 1 close-out's async OSRM
refactor: only the intentionally killed pod cycles, the survivor stays
healthy throughout, 0 restarts on either side. On the stub path (ramp to 500 req/s,
dispatch pod killed 20s in, client retry reusing the `Idempotency-Key`): 101,277
requests, 0.03% failed, p99 106 ms, zero duplicate trip keys, and no REQUESTED trip
left older than 60 seconds.

Trips left in `REQUESTED` by a crash are resolved by a scheduled sweep in
trip-service: rows older than 60 seconds are marked `SYSTEM_ERROR` (checked every
30 seconds). They are failed, not re-matched, because the original client has
already given up. A client retrying with the same `Idempotency-Key` still gets
the ride matched.

## Running it locally (no Kubernetes)

### Prerequisites

- Java 26 (`java -version` to check)
- MySQL, running locally

  ```
  brew install mysql
  brew services start mysql
  mysql -u root -e "CREATE DATABASE IF NOT EXISTS atlas_driver; CREATE DATABASE IF NOT EXISTS atlas_trip;"
  ```

  (dispatch-service and trip-service both also auto-create their database on
  startup via `createDatabaseIfNotExist=true`, so this step is a safety net,
  not strictly required.)

- Rosetta, if you're on Apple Silicon. The Maven build compiles `.proto`
  files with `protoc-gen-grpc-java`, and every published `osx-aarch_64`
  build of that plugin is actually an x86_64 binary (a grpc-java packaging
  quirk, confirmed across versions) — it needs Rosetta to run at all.

  ```
  softwareupdate --install-rosetta --agree-to-license
  ```

No other manual installs — each service's Maven wrapper (`./mvnw`) downloads
everything else it needs (protoc, the gRPC plugin, all dependencies) on
first build.

### Environment

Each service reads its config from env vars with built-in defaults matching
local MySQL (`root`, no password) — so you can run everything below with zero
setup. If you want to override anything, each service has its own `.env` /
`.env.example` at its root (`dispatch-service/.env`, etc.) documenting the
variables it reads. Note: Spring Boot does not auto-load `.env` files — export
the variables yourself if you want to override a default, e.g.:

```
export OSRM_TIMEOUT_MS=1000
```

### Three terminals

Each service is its own Maven project. Start each in its own terminal tab,
in this order (pricing and trip have no dependencies on each other; dispatch
depends on both being up):

**Terminal 1 — pricing-service** (gRPC on :9090, actuator on :8090)
```
cd pricing-service
./mvnw spring-boot:run
```

**Terminal 2 — trip-service** (gRPC on :9091, actuator on :8091)
```
cd trip-service
./mvnw spring-boot:run
```

**Terminal 3 — dispatch-service** (REST on :8080)
```
cd dispatch-service
./mvnw spring-boot:run
```

Each service seeds/connects on startup: dispatch-service seeds 999 drivers
(`D-001`...`D-999`) into `atlas_driver` on first run. Wait for each terminal
to print `Started ...Application in N seconds` before moving to the next.

### Logs

There's no separate log file — with the commands above, each service's logs
print directly to the terminal tab it's running in. That's where to watch
the grid-narrowed quad-tree ranking output, gRPC call logs, and any errors,
live, per service. `Ctrl+C` in a tab stops that service.

If you'd rather background them and tail logs from files instead:
```
./mvnw spring-boot:run > /tmp/dispatch.log 2>&1 &
tail -f /tmp/dispatch.log
```

## Trying it

### Interactive demo

```
./scripts/demo-ride.sh
```
Prompts for rider ID, pickup lat/lng, and drop lat/lng, then shows the exact
request JSON sent to dispatch-service and the response JSON back, followed
by a plain-English summary (driver matched, price, ETA) and an option to
cancel the trip it just created.

### Raw curl

```
curl -X POST http://localhost:8080/rides \
  -H "Content-Type: application/json" \
  -d '{"riderId":"R-001","pickup":{"lat":37.7749,"lng":-122.4194},"drop":{"lat":37.7849,"lng":-122.4094}}'

curl -X POST http://localhost:8080/trips/T-00000001/cancel
```

`POST /rides` accepts an optional `Idempotency-Key` header. Retrying with the
same key returns the same trip instead of creating a second one, including
after a dispatch pod is killed mid-request:

```
curl -X POST http://localhost:8080/rides \
  -H "Content-Type: application/json" -H "Idempotency-Key: my-key-1" \
  -d '{"riderId":"R-001","pickup":{"lat":37.7749,"lng":-122.4194},"drop":{"lat":37.7849,"lng":-122.4094}}'
```

A replay of a matched ride returns the stored result without the ETA fields. A
key reused with a different body returns the first request's result.

San Francisco bounding box (`37.70-37.83` lat, `-122.51`--`-122.36` lng) is
where all seeded drivers live, so pick coordinates in that range to see a
close match.

### Health checks

```
curl http://localhost:8080/actuator/health   # dispatch
curl http://localhost:8090/actuator/health   # pricing
curl http://localhost:8091/actuator/health   # trip
```

## Known limitation: local same-port restarts of pricing/trip

Every gRPC call from dispatch-service carries a 2s deadline
(`PricingClient`/`TripClient`), so a downstream outage always fails fast into
`SYSTEM_ERROR` instead of hanging.

One remaining quirk, local-dev only (running services as plain processes,
not in Kubernetes): if you kill and restart pricing-service or trip-service
on the same port while dispatch-service keeps running, dispatch's gRPC
channel to it can get stuck failing with `"Too many transparent retries.
Might be a bug in gRPC"` — a documented grpc-java issue tied to reconnecting
to the exact same host:port in quick succession. Workaround: restart
dispatch-service too after restarting a downstream dependency locally.

**Confirmed not an issue in Kubernetes**: the chaos test kills and replaces
dispatch-service pods repeatedly with no channel-wedge errors, because a
restarted pod gets a new IP and traffic goes through a Service rather than
reconnecting to the same socket.

## Running tests

```
cd dispatch-service && ./mvnw test
cd pricing-service && ./mvnw test
cd trip-service && ./mvnw test
```

## Changelog
- **v2.4.0** (2026-10-09): minor bump
- **v2.3.0** (2026-10-09): minor bump
- **v2.2.0** (2026-09-28): minor bump
- **v2.1.3** (2026-09-25): patch bump
- **v2.1.2** (2026-09-25): patch bump
- **v2.1.1** (2026-09-25): patch bump
- **v2.1.0** (2026-08-18) — minor bump
- **v2.0.0** (2026-08-15) — major bump
- **v1.3.0** (2026-08-13) — minor bump
- **v1.2.0** (2026-07-24) — minor bump
- **v1.1.0** (2026-07-24) — minor bump
