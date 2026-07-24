# Atlas

A distributed rider-driver dispatch system in Java/Spring Boot. Three gRPC
microservices: **dispatch-service** (orchestrator, REST + quad-tree matching),
**pricing-service** (pure calculator), and **trip-service** (system of
record, MySQL).

See `.claude/CLAUDE.md` (not tracked in git) for the full design spec. This
file covers local setup and running the current build.

## Current stage

Application logic for all three services is done and tested. Docker/Kubernetes
deployment, the load test, and the chaos-test/Grafana demo (root spec's
"Phase 10") are **not built yet** — everything below runs the three services
as plain local Spring Boot processes.

## Architecture

```
Rider (REST) ──> dispatch-service ──gRPC──> pricing-service (no DB)
                       │
                       ├──gRPC──> trip-service (own MySQL DB: atlas_trip)
                       │
                       └── own Driver DB (MySQL: atlas_driver, quad-tree scan)
```

## Prerequisites

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

## Environment

Each service reads its config from env vars with built-in defaults matching
local MySQL (`root`, no password) — so you can run everything below with zero
setup. If you want to override anything, each service has its own `.env` /
`.env.example` at its root (`dispatch-service/.env`, etc.) documenting the
variables it reads. Note: Spring Boot does not auto-load `.env` files — export
the variables yourself if you want to override a default, e.g.:

```
export OSRM_TIMEOUT_MS=1000
```

## Running it: three terminals

Each service is its own Maven project. Start each in its own terminal tab,
in this order (pricing and trip have no dependencies on each other; dispatch
depends on both being up):

**Terminal 1 — pricing-service** (gRPC on :9090)
```
cd pricing-service
./mvnw spring-boot:run
```

**Terminal 2 — trip-service** (gRPC on :9091, HTTP actuator on :8091)
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
curl http://localhost:8091/actuator/health   # trip
```
(pricing-service has no HTTP port — it's gRPC-only. Check it's up via
`lsof -i :9090` or just watch its terminal for the "Started" line.)

## Known issue: gRPC client channels don't self-heal on restart

If you restart pricing-service or trip-service while dispatch-service keeps
running, dispatch's gRPC client channel to that service can get stuck
(`GOAWAY` / stale HTTP/2 connection) instead of reconnecting — the request
either fails or, if it's trip-service without a request deadline configured,
hangs. Workaround: restart dispatch-service too after restarting a
downstream dependency. Root cause and a real deadline/timeout fix are still
open, flagged for follow-up before the chaos-test phase (killing pods and
relying on the client to recover is the actual point of that demo, so this
needs solving before then, not glossed over).

## Running tests

```
cd dispatch-service && ./mvnw test
cd pricing-service && ./mvnw test
cd trip-service && ./mvnw test
```
