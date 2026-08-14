# Graph Report - atlas  (2026-08-14)

## Corpus Check
- 67 files · ~16,169 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 499 nodes · 1078 edges · 32 communities (22 shown, 10 thin omitted)
- Extraction: 82% EXTRACTED · 18% INFERRED · 0% AMBIGUOUS · INFERRED: 193 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `22e275ab`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- [[_COMMUNITY_Driver Domain & Seeding|Driver Domain & Seeding]]
- [[_COMMUNITY_Trip gRPC Service|Trip gRPC Service]]
- [[_COMMUNITY_OSRM & Trip Client Wiring|OSRM & Trip Client Wiring]]
- [[_COMMUNITY_Pricing gRPC Service|Pricing gRPC Service]]
- [[_COMMUNITY_Quad-Tree Matching|Quad-Tree Matching]]
- [[_COMMUNITY_Ride REST API|Ride REST API]]
- [[_COMMUNITY_Trip Persistence Layer|Trip Persistence Layer]]
- [[_COMMUNITY_Kubernetes & Observability Manifests|Kubernetes & Observability Manifests]]
- [[_COMMUNITY_Community 8|Community 8]]
- [[_COMMUNITY_Community 9|Community 9]]
- [[_COMMUNITY_Ride Validation & Error Handling|Ride Validation & Error Handling]]
- [[_COMMUNITY_Dispatch App Entrypoint|Dispatch App Entrypoint]]
- [[_COMMUNITY_k6 Load Test Script|k6 Load Test Script]]
- [[_COMMUNITY_Community 13|Community 13]]
- [[_COMMUNITY_Pricing App Test|Pricing App Test]]
- [[_COMMUNITY_Community 15|Community 15]]
- [[_COMMUNITY_OSRM Route Response Model|OSRM Route Response Model]]
- [[_COMMUNITY_Service HELP.md Boilerplate|Service HELP.md Boilerplate]]
- [[_COMMUNITY_start.sh Setup Script|start.sh Setup Script]]
- [[_COMMUNITY_Distance Source Enum|Distance Source Enum]]
- [[_COMMUNITY_chaos-test.sh|chaos-test.sh]]
- [[_COMMUNITY_demo-ride.sh|demo-ride.sh]]
- [[_COMMUNITY_dispatch-service Package|dispatch-service Package]]
- [[_COMMUNITY_pricing-service Package|pricing-service Package]]
- [[_COMMUNITY_trip-service Package|trip-service Package]]
- [[_COMMUNITY_Community 25|Community 25]]
- [[_COMMUNITY_Community 26|Community 26]]
- [[_COMMUNITY_Community 27|Community 27]]
- [[_COMMUNITY_Community 28|Community 28]]
- [[_COMMUNITY_Community 29|Community 29]]
- [[_COMMUNITY_Community 30|Community 30]]
- [[_COMMUNITY_Community 31|Community 31]]

## God Nodes (most connected - your core abstractions)
1. `Trip` - 39 edges
2. `Driver` - 28 edges
3. `GridIndex` - 20 edges
4. `RideService` - 19 edges
5. `RideServiceTest` - 19 edges
6. `Coordinate` - 18 edges
7. `MatchingService` - 17 edges
8. `TripRecordService` - 15 edges
9. `MatchingServiceTest` - 14 edges
10. `DistanceResult` - 14 edges

## Surprising Connections (you probably didn't know these)
- `dispatch-service HELP.md (Spring Boot Getting Started)` --semantically_similar_to--> `pricing-service HELP.md (Spring Boot Getting Started)`  [INFERRED] [semantically similar]
  dispatch-service/HELP.md → pricing-service/HELP.md
- `dispatch-service HELP.md (Spring Boot Getting Started)` --semantically_similar_to--> `trip-service HELP.md (Spring Boot Getting Started)`  [INFERRED] [semantically similar]
  dispatch-service/HELP.md → trip-service/HELP.md
- `pricing-service HELP.md (Spring Boot Getting Started)` --semantically_similar_to--> `trip-service HELP.md (Spring Boot Getting Started)`  [INFERRED] [semantically similar]
  pricing-service/HELP.md → trip-service/HELP.md
- `Atlas` --references--> `Kind Cluster Config (atlas)`  [EXTRACTED]
  README.md → k8s/kind-config.yaml
- `Version Bump GitHub Actions Workflow` --references--> `Atlas`  [EXTRACTED]
  .github/workflows/version_bump.yml → README.md

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **Shared Kubernetes Deployment Pattern (RollingUpdate, Actuator probes, Prometheus scrape annotations)** — k8s_02_dispatch_dispatchdeployment, k8s_03_pricing_pricingdeployment, k8s_04_trip_tripdeployment [INFERRED 0.85]
- **Dispatch and Trip Share One In-Cluster MySQL Instance (two schemas)** — k8s_01_mysql_mysqldeployment, k8s_02_dispatch_dispatchdeployment, k8s_04_trip_tripdeployment [EXTRACTED 1.00]
- **Prometheus + Grafana Observability Stack for Atlas** — k8s_05_prometheus_prometheusdeployment, k8s_06_grafana_grafanadeployment, prometheus_prometheusyml_scrapeconfig, datasources_prometheusyml_datasource [EXTRACTED 1.00]

## Communities (32 total, 10 thin omitted)

### Community 0 - "Driver Domain & Seeding"
Cohesion: 0.07
Nodes (30): CandidateScore, SpringBootApplication, String, Entity, Instant, String, Table, Scheduled (+22 more)

### Community 1 - "Trip gRPC Service"
Cohesion: 0.13
Nodes (9): QuoteRequest, PostPersist, DistanceSource, Entity, Instant, Long, String, Table (+1 more)

### Community 2 - "OSRM & Trip Client Wiring"
Cohesion: 0.06
Nodes (41): Service, String, Transactional, Component, Logger, String, Component, Logger (+33 more)

### Community 3 - "Pricing gRPC Service"
Cohesion: 0.12
Nodes (17): ConfigurationProperties, EnableConfigurationProperties, PricingGrpcService, PricingGrpcServiceTest, PricingCalculator, PricingCalculatorTest, PricingProperties, GrpcService (+9 more)

### Community 4 - "Quad-Tree Matching"
Cohesion: 0.10
Nodes (12): List, String, Test, Test, BoundingBox, GeoMath, RoadDistanceEstimator, RoadDistanceEstimatorTest (+4 more)

### Community 5 - "Ride REST API"
Cohesion: 0.25
Nodes (5): Throwable, PricingUnavailableException, RuntimeException, String, TripNotFoundException

### Community 6 - "Trip Persistence Layer"
Cohesion: 0.33
Nodes (8): Component, DriverRepository, GridIndex, Logger, Scheduled, GridIndex, GridIndexRefresher, Scheduled

### Community 7 - "Kubernetes & Observability Manifests"
Cohesion: 0.05
Nodes (41): Grafana Dashboard File Provider Config, Grafana Prometheus Datasource Config, Atlas Kubernetes Namespace, MySQL emptyDir Drop-and-Reseed Rationale, MySQL In-Cluster Deployment (atlas_driver + atlas_trip), dispatch-service Kubernetes Deployment (2 replicas), dispatch-service PodDisruptionBudget (minAvailable 1), pricing-service Kubernetes Deployment (1 replica) (+33 more)

### Community 8 - "Community 8"
Cohesion: 0.13
Nodes (22): BeforeEach, BoundingBox, Component, Logger, BeforeEach, Coordinate, DriverRepository, ExtendWith (+14 more)

### Community 9 - "Community 9"
Cohesion: 0.24
Nodes (6): Optional, String, BeforeEach, ExtendWith, Test, TripRecordServiceTest

### Community 10 - "Ride Validation & Error Handling"
Cohesion: 0.43
Nodes (6): ExceptionHandler, MethodArgumentNotValidException, ResponseStatus, RideExceptionHandler, ValidationErrorResponse, RestControllerAdvice

### Community 11 - "Dispatch App Entrypoint"
Cohesion: 0.16
Nodes (13): CancelTripRequest, TripGrpcService, RecordTripRequest, DistanceSource, CancelTripResponse, DistanceSource, GrpcService, Override (+5 more)

### Community 13 - "Community 13"
Cohesion: 0.10
Nodes (23): BoundingBox, CommandLineRunner, Component, BoundingBox, Component, DriverRepository, Logger, Component (+15 more)

### Community 14 - "Pricing App Test"
Cohesion: 0.60
Nodes (3): Test, PricingServiceApplicationTests, SpringBootTest

### Community 16 - "OSRM Route Response Model"
Cohesion: 0.83
Nodes (3): JsonIgnoreProperties, OsrmRouteResponse, Route

### Community 17 - "Service HELP.md Boilerplate"
Cohesion: 1.00
Nodes (3): dispatch-service HELP.md (Spring Boot Getting Started), pricing-service HELP.md (Spring Boot Getting Started), trip-service HELP.md (Spring Boot Getting Started)

### Community 26 - "Community 26"
Cohesion: 0.40
Nodes (4): Getting Started, Guides, Maven Parent overrides, Reference Documentation

### Community 27 - "Community 27"
Cohesion: 0.40
Nodes (4): Getting Started, Guides, Maven Parent overrides, Reference Documentation

### Community 28 - "Community 28"
Cohesion: 0.40
Nodes (4): Getting Started, Guides, Maven Parent overrides, Reference Documentation

### Community 29 - "Community 29"
Cohesion: 0.29
Nodes (6): DistanceSource, Service, String, Transactional, TripRecordService, TripStatus

### Community 30 - "Community 30"
Cohesion: 0.29
Nodes (8): TripGrpcServiceTest, BeforeEach, CancelTripResponse, ExtendWith, RecordTripResponse, StreamObserver, Test, UpdateTripStatusResponse

### Community 31 - "Community 31"
Cohesion: 0.40
Nodes (3): JpaRepository, Long, TripRepository

## Knowledge Gaps
- **42 isolated node(s):** `Current stage`, `Architecture`, `Prerequisites`, `One command`, `Using it` (+37 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **10 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `Coordinate` connect `OSRM & Trip Client Wiring` to `Driver Domain & Seeding`, `Community 8`, `Trip gRPC Service`?**
  _High betweenness centrality (0.121) - this node is a cross-community bridge._
- **Why does `Trip` connect `Trip gRPC Service` to `Pricing gRPC Service`, `Community 9`, `Dispatch App Entrypoint`, `Community 29`, `Community 30`, `Community 31`?**
  _High betweenness centrality (0.098) - this node is a cross-community bridge._
- **Why does `DriverRepository` connect `Driver Domain & Seeding` to `Community 8`, `OSRM & Trip Client Wiring`, `Community 13`, `Community 31`?**
  _High betweenness centrality (0.074) - this node is a cross-community bridge._
- **Are the 3 inferred relationships involving `GridIndex` (e.g. with `.densAreaStaysLocalAndExcludesFarDrivers()` and `.rebuildReplacesPreviousState()`) actually correct?**
  _`GridIndex` has 3 INFERRED edges - model-reasoned connections that need verification._
- **What connects `Current stage`, `Architecture`, `Prerequisites` to the rest of the system?**
  _44 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Driver Domain & Seeding` be split into smaller, more focused modules?**
  _Cohesion score 0.07402031930333818 - nodes in this community are weakly interconnected._
- **Should `Trip gRPC Service` be split into smaller, more focused modules?**
  _Cohesion score 0.12698412698412698 - nodes in this community are weakly interconnected._