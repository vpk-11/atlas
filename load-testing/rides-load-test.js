import http from "k6/http";
import { check } from "k6";

// Target: 500 req/sec sustained against POST /rides.
// BASE_URL defaults to a port-forwarded dispatch-service (see scripts/start.sh).
const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";

// RATE set: constant-arrival-rate at RATE req/s for DURATION, no thresholds, one
// RESULT line at the end (used by stub-load-test.sh). RATE unset: the ramp below.
const RATE = __ENV.RATE ? Number(__ENV.RATE) : null;

export function handleSummary(data) {
  if (RATE === null) return {};
  const m = data.metrics;
  const dropped = m.dropped_iterations ? m.dropped_iterations.values.count : 0;
  return {
    stdout: `RESULT rate=${RATE} requests=${m.http_reqs.values.count} ` +
      `failed_pct=${(m.http_req_failed.values.rate * 100).toFixed(2)} ` +
      `p99_ms=${m.http_req_duration.values["p(99)"].toFixed(0)} dropped=${dropped}\n`,
  };
}

const rateOptions = {
  summaryTrendStats: ["avg", "p(50)", "p(90)", "p(95)", "p(99)", "max"],
  scenarios: {
    fixed_rate: {
      executor: "constant-arrival-rate",
      rate: RATE,
      timeUnit: "1s",
      duration: __ENV.DURATION || "60s",
      preAllocatedVUs: 200,
      maxVUs: 1000,
    },
  },
};

const rampOptions = {
  scenarios: {
    sustained_load: {
      executor: "ramping-arrival-rate",
      startRate: 0,
      timeUnit: "1s",
      preAllocatedVUs: 200,
      maxVUs: 1000,
      stages: [
        { target: 500, duration: "30s" },
        { target: 500, duration: "3m" },
        { target: 0, duration: "15s" },
      ],
    },
  },
  thresholds: {
    http_req_failed: ["rate<0.05"],
    http_req_duration: ["p(99)<2000"],
  },
};

export const options = RATE === null ? rampOptions : rateOptions;

// San Francisco bounding box, matches the seeded driver coordinate range.
const LAT_MIN = 37.70;
const LAT_MAX = 37.82;
const LNG_MIN = -122.51;
const LNG_MAX = -122.38;

function randomCoord() {
  return {
    lat: LAT_MIN + Math.random() * (LAT_MAX - LAT_MIN),
    lng: LNG_MIN + Math.random() * (LNG_MAX - LNG_MIN),
  };
}

function randomRiderId() {
  const n = 1 + Math.floor(Math.random() * 999);
  return `R-${String(n).padStart(3, "0")}`;
}

// Client-side retry: mirrors what the root CLAUDE.md chaos test expects from
// "the load generator" when a request lands on a pod that's mid-shutdown.
// The retry goes back through the k8s Service, which by then routes only to
// surviving endpoints, so a single retry is enough to prove failover works.
// The Idempotency-Key is generated once per iteration and reused on the retry, so a
// retry after a pod kill resolves to the same trip row instead of creating a second.
function postRide(payload) {
  const params = {
    headers: {
      "Content-Type": "application/json",
      "Idempotency-Key": `${__VU}-${__ITER}-${Date.now()}`,
    },
    timeout: "10s",
  };
  let res = http.post(`${BASE_URL}/rides`, payload, params);
  if (res.status === 0 || res.status >= 500) {
    res = http.post(`${BASE_URL}/rides`, payload, params);
  }
  return res;
}

export default function () {
  const payload = JSON.stringify({
    riderId: randomRiderId(),
    pickup: randomCoord(),
    drop: randomCoord(),
  });

  const res = postRide(payload);

  check(res, {
    "status is 200": (r) => r.status === 200,
    "status is not 5xx": (r) => r.status < 500,
  });
}
