import http from "k6/http";
import { check } from "k6";

// Target: 500 req/sec sustained against POST /rides.
// BASE_URL defaults to a port-forwarded dispatch-service (see scripts/start.sh).
const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";

export const options = {
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
function postRide(payload) {
  const params = {
    headers: { "Content-Type": "application/json" },
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
