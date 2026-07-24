# Graph Report - .  (2026-07-24)

## Corpus Check
- Corpus is ~14,270 words - fits in a single context window. You may not need a graph.

## Summary
- 381 nodes · 757 edges · 25 communities (17 shown, 8 thin omitted)
- Extraction: 86% EXTRACTED · 14% INFERRED · 0% AMBIGUOUS · INFERRED: 105 edges (avg confidence: 0.8)
- Token cost: 70,870 input · 0 output

## Community Hubs (Navigation)
- [[_COMMUNITY_Driver Domain & Seeding|Driver Domain & Seeding]]
- [[_COMMUNITY_Trip gRPC Service|Trip gRPC Service]]
- [[_COMMUNITY_OSRM & Trip Client Wiring|OSRM & Trip Client Wiring]]
- [[_COMMUNITY_Pricing gRPC Service|Pricing gRPC Service]]
- [[_COMMUNITY_Quad-Tree Matching|Quad-Tree Matching]]
- [[_COMMUNITY_Ride REST API|Ride REST API]]
- [[_COMMUNITY_Trip Persistence Layer|Trip Persistence Layer]]
- [[_COMMUNITY_Kubernetes & Observability Manifests|Kubernetes & Observability Manifests]]
- [[_COMMUNITY_Pricing Client (Dispatch side)|Pricing Client (Dispatch side)]]
- [[_COMMUNITY_Road Distance Estimator|Road Distance Estimator]]
- [[_COMMUNITY_Ride Validation & Error Handling|Ride Validation & Error Handling]]
- [[_COMMUNITY_Dispatch App Entrypoint|Dispatch App Entrypoint]]
- [[_COMMUNITY_k6 Load Test Script|k6 Load Test Script]]
- [[_COMMUNITY_Pricing App Entrypoint|Pricing App Entrypoint]]
- [[_COMMUNITY_Pricing App Test|Pricing App Test]]
- [[_COMMUNITY_Trip App Entrypoint|Trip App Entrypoint]]
- [[_COMMUNITY_OSRM Route Response Model|OSRM Route Response Model]]
- [[_COMMUNITY_Service HELP.md Boilerplate|Service HELP.md Boilerplate]]
- [[_COMMUNITY_start.sh Setup Script|start.sh Setup Script]]
- [[_COMMUNITY_Distance Source Enum|Distance Source Enum]]
- [[_COMMUNITY_chaos-test.sh|chaos-test.sh]]
- [[_COMMUNITY_demo-ride.sh|demo-ride.sh]]
- [[_COMMUNITY_dispatch-service Package|dispatch-service Package]]
- [[_COMMUNITY_pricing-service Package|pricing-service Package]]
- [[_COMMUNITY_trip-service Package|trip-service Package]]

## God Nodes (most connected - your core abstractions)
1. `Trip` - 29 edges
2. `Driver` - 24 edges
3. `Coordinate` - 21 edges
4. `RideService` - 18 edges
5. `RideServiceTest` - 16 edges
6. `MatchingService` - 15 edges
7. `DistanceResult` - 14 edges
8. `TripRecordService` - 14 edges
9. `DriverRepository` - 13 edges
10. `TripClient` - 13 edges

## Surprising Connections (you probably didn't know these)
- `dispatch-service HELP.md (Spring Boot Getting Started)` --semantically_similar_to--> `pricing-service HELP.md (Spring Boot Getting Started)`  [INFERRED] [semantically similar]
  dispatch-service/HELP.md → pricing-service/HELP.md
- `dispatch-service HELP.md (Spring Boot Getting Started)` --semantically_similar_to--> `trip-service HELP.md (Spring Boot Getting Started)`  [INFERRED] [semantically similar]
  dispatch-service/HELP.md → trip-service/HELP.md
- `pricing-service HELP.md (Spring Boot Getting Started)` --semantically_similar_to--> `trip-service HELP.md (Spring Boot Getting Started)`  [INFERRED] [semantically similar]
  pricing-service/HELP.md → trip-service/HELP.md
- `Version Bump GitHub Actions Workflow` --references--> `Atlas README`  [EXTRACTED]
  .github/workflows/version_bump.yml → README.md
- `Atlas README` --references--> `Kind Cluster Config (atlas)`  [EXTRACTED]
  README.md → k8s/kind-config.yaml

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **Shared Kubernetes Deployment Pattern (RollingUpdate, Actuator probes, Prometheus scrape annotations)** — k8s_02_dispatch_dispatchdeployment, k8s_03_pricing_pricingdeployment, k8s_04_trip_tripdeployment [INFERRED 0.85]
- **Dispatch and Trip Share One In-Cluster MySQL Instance (two schemas)** — k8s_01_mysql_mysqldeployment, k8s_02_dispatch_dispatchdeployment, k8s_04_trip_tripdeployment [EXTRACTED 1.00]
- **Prometheus + Grafana Observability Stack for Atlas** — k8s_05_prometheus_prometheusdeployment, k8s_06_grafana_grafanadeployment, prometheus_prometheusyml_scrapeconfig, datasources_prometheusyml_datasource [EXTRACTED 1.00]

## Communities (25 total, 8 thin omitted)

### Community 0 - "Driver Domain & Seeding"
Cohesion: 0.06
Nodes (30): CommandLineRunner, Entity, Instant, String, Table, Service, String, Transactional (+22 more)

### Community 1 - "Trip gRPC Service"
Cohesion: 0.06
Nodes (27): CancelTripRequest, TripGrpcService, TripGrpcServiceTest, PostPersist, RecordTripRequest, DistanceSource, CancelTripResponse, DistanceSource (+19 more)

### Community 2 - "OSRM & Trip Client Wiring"
Cohesion: 0.13
Nodes (17): Component, Logger, String, Component, String, Throwable, BeforeEach, ExtendWith (+9 more)

### Community 3 - "Pricing gRPC Service"
Cohesion: 0.12
Nodes (17): ConfigurationProperties, EnableConfigurationProperties, PricingGrpcService, PricingGrpcServiceTest, PricingCalculator, PricingCalculatorTest, PricingProperties, GrpcService (+9 more)

### Community 4 - "Quad-Tree Matching"
Cohesion: 0.15
Nodes (9): List, String, Test, BoundingBox, GeoMath, IndexedPoint, Node, QuadTree (+1 more)

### Community 5 - "Ride REST API"
Cohesion: 0.13
Nodes (13): String, String, Logger, Service, String, PostMapping, RideController, RestController (+5 more)

### Community 6 - "Trip Persistence Layer"
Cohesion: 0.13
Nodes (14): JpaRepository, Optional, DistanceSource, Service, String, Transactional, Long, String (+6 more)

### Community 7 - "Kubernetes & Observability Manifests"
Cohesion: 0.10
Nodes (25): Grafana Dashboard File Provider Config, Grafana Prometheus Datasource Config, Atlas Kubernetes Namespace, MySQL emptyDir Drop-and-Reseed Rationale, MySQL In-Cluster Deployment (atlas_driver + atlas_trip), dispatch-service Kubernetes Deployment (2 replicas), dispatch-service PodDisruptionBudget (minAvailable 1), pricing-service Kubernetes Deployment (1 replica) (+17 more)

### Community 8 - "Pricing Client (Dispatch side)"
Cohesion: 0.14
Nodes (11): Component, Logger, QuoteRequest, Throwable, PricingClient, PricingUnavailableException, PricingServiceBlockingStub, RuntimeException (+3 more)

### Community 9 - "Road Distance Estimator"
Cohesion: 0.31
Nodes (3): Test, RoadDistanceEstimator, RoadDistanceEstimatorTest

### Community 10 - "Ride Validation & Error Handling"
Cohesion: 0.43
Nodes (6): ExceptionHandler, MethodArgumentNotValidException, ResponseStatus, RideExceptionHandler, ValidationErrorResponse, RestControllerAdvice

### Community 11 - "Dispatch App Entrypoint"
Cohesion: 0.50
Nodes (3): SpringBootApplication, String, DispatchServiceApplication

### Community 13 - "Pricing App Entrypoint"
Cohesion: 0.50
Nodes (3): SpringBootApplication, String, PricingServiceApplication

### Community 14 - "Pricing App Test"
Cohesion: 0.60
Nodes (3): Test, PricingServiceApplicationTests, SpringBootTest

### Community 15 - "Trip App Entrypoint"
Cohesion: 0.50
Nodes (3): SpringBootApplication, String, TripServiceApplication

### Community 16 - "OSRM Route Response Model"
Cohesion: 0.83
Nodes (3): JsonIgnoreProperties, OsrmRouteResponse, Route

### Community 17 - "Service HELP.md Boilerplate"
Cohesion: 1.00
Nodes (3): dispatch-service HELP.md (Spring Boot Getting Started), pricing-service HELP.md (Spring Boot Getting Started), trip-service HELP.md (Spring Boot Getting Started)

## Knowledge Gaps
- **13 isolated node(s):** `com.atlas:dispatch-service`, `DistanceSource`, `chaos-test.sh script`, `options`, `com.atlas:pricing-service` (+8 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **8 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `Coordinate` connect `OSRM & Trip Client Wiring` to `Driver Domain & Seeding`, `Pricing Client (Dispatch side)`, `Ride REST API`?**
  _High betweenness centrality (0.180) - this node is a cross-community bridge._
- **Why does `Trip` connect `Trip gRPC Service` to `Pricing Client (Dispatch side)`, `Pricing gRPC Service`, `Trip Persistence Layer`?**
  _High betweenness centrality (0.106) - this node is a cross-community bridge._
- **Why does `DriverRepository` connect `Driver Domain & Seeding` to `Trip Persistence Layer`?**
  _High betweenness centrality (0.105) - this node is a cross-community bridge._
- **What connects `com.atlas:dispatch-service`, `DistanceSource`, `chaos-test.sh script` to the rest of the system?**
  _16 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Driver Domain & Seeding` be split into smaller, more focused modules?**
  _Cohesion score 0.06433566433566433 - nodes in this community are weakly interconnected._
- **Should `Trip gRPC Service` be split into smaller, more focused modules?**
  _Cohesion score 0.06298701298701298 - nodes in this community are weakly interconnected._
- **Should `OSRM & Trip Client Wiring` be split into smaller, more focused modules?**
  _Cohesion score 0.12955465587044535 - nodes in this community are weakly interconnected._