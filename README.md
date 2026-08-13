# Atlas

<!-- version: v1.3.0 -->
![Version](https://img.shields.io/badge/version-v1.3.0-blue)

A distributed rider-driver dispatch system in Java/Spring Boot. Three gRPC
microservices: **dispatch-service** (orchestrator, REST + quad-tree matching),
**pricing-service** (pure calculator), and **trip-service** (system of
record, MySQL). Deployed to Kubernetes (Kind), load-tested with k6, observed
with Prometheus/Grafana, and chaos-tested by killing a live pod mid-load.

See `.claude/CLAUDE.md` (not tracked in git) for the full design spec. This
file covers running the current build, either as local processes or as a
full Kubernetes deployment.

## Current stage

All application logic (three services) and all shared infrastructure (Docker,
Kubernetes/Kind, k6 load test, Prometheus/Grafana, chaos test) are built and
verified against a real local Kind cluster. See `.claude/CLAUDE.md`'s
Phase 10 changelog entry for what was found and fixed getting there.

## Architecture

```
Rider (REST) ──> dispatch-service ──gRPC──> pricing-service (no DB)
                       │
                       ├──gRPC──> trip-service (own MySQL DB: atlas_trip)
                       │
                       └── own Driver DB (MySQL: atlas_driver, quad-tree scan)
```

In Kubernetes: dispatch-service runs 2 replicas behind a Service, pricing and
trip run 1 each, MySQL runs in-cluster (one instance, two schemas), and
Prometheus/Grafana scrape all three services' Actuator endpoints.

## Running it in Kubernetes (Kind) — recommended

### Prerequisites

```
brew install colima docker kind kubectl k6
colima start --cpu 6 --memory 12 --disk 60
```

Java 26 and Maven are also required for the build step (see "Running it
locally" below for details on those).

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
Ramps to 500 req/sec sustained against `POST /rides`. On this local single-
node Kind setup, sustained 500 req/sec saturates the system past the JVM's
default connection-pool sizing — see `.claude/CLAUDE.md` Phase 10 entry for
the honest result and why it's flagged as app-level follow-up, not an infra
problem.

### Chaos test

```
./load-testing/chaos-test.sh
```
Runs the load test as an in-cluster k6 Job, kills a dispatch-service pod
20 seconds in, and shows requests continuing to be served by the surviving
replica. Watch the Grafana "Dispatch Pod Count Over Time" panel live to see
the dip and recovery.

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
the quad-tree ranking output, gRPC call logs, and any errors, live, per
service. `Ctrl+C` in a tab stops that service.

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
- **v1.3.0** (2026-08-13) — minor bump
- **v1.2.0** (2026-07-24) — minor bump
- **v1.1.0** (2026-07-24) — minor bump
