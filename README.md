<div align="center">

# AroundU

**Hyperlocal Physical Freelancing Platform — Backend API**

[![Java 21](https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot 3.5](https://img.shields.io/badge/Spring%20Boot-3.5.12-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-17-4169E1?logo=postgresql&logoColor=white)](https://www.postgresql.org/)
[![Redis](https://img.shields.io/badge/Redis-7-DC382D?logo=redis&logoColor=white)](https://redis.io/)
[![License: Proprietary](https://img.shields.io/badge/License-Proprietary-blue)]()

</div>

---

## Table of Contents

- [Project Overview](#project-overview)
- [Architectural Overview](#architectural-overview)
- [Tech Stack](#tech-stack)
- [Folder Structure](#folder-structure)
- [Local Setup](#local-setup)
- [Docker Setup](#docker-setup)
- [Kubernetes Deployment (EC2 / k3s)](#kubernetes-deployment-ec2--k3s)
- [Environment Configuration](#environment-configuration)
- [Running with Different Profiles](#running-with-different-profiles)
- [API Documentation (Swagger)](#api-documentation-swagger)
- [Monitoring & Observability](#monitoring--observability)
- [Caching Strategy](#caching-strategy)
- [Geo Search](#geo-search)
- [Rate Limiting](#rate-limiting)
- [Circuit Breaker & Resilience](#circuit-breaker--resilience)
- [Background Jobs](#background-jobs)
- [Distributed Tracing & Metrics](#distributed-tracing--metrics)
- [Ranking Engine Integration](#ranking-engine-integration)
- [Future Scalability Plan](#future-scalability-plan)
- [Contributing](#contributing)
- [License](#license)
- [Production Deployment Notes](#production-deployment-notes)

---

## Project Overview

AroundU is a digital marketplace that connects **Service Seekers** (clients needing plumbing, electrical, mechanical, and other physical services) with **Service Providers** (skilled blue-collar workers) through location-aware, bid-based job allocation.

### Core Concept

```
Client posts a job ──► Workers within radius receive feed
                       ──► Workers submit competitive bids
                       ──► Client selects a worker
                       ──► Confirmation code verifies arrival
                       ──► Payment released via escrow or settled offline
```

**Key capabilities:**

- **Geolocation-based matching** — Redis `GEORADIUS` index for sub-millisecond proximity queries, validated against PostgreSQL as the source of truth.
- **Bid-based allocation** — Workers compete with customised bids; clients review, negotiate, and accept.
- **Dual payment modes** — Platform-managed escrow with confirmation codes, or direct offline settlement.
- **Trust & verification** — ITI certificate verification, review/rating system, and GPS tracking during active jobs.

---

## Architectural Overview

AroundU follows a **modular monolith** architecture. Each business domain is a self-contained package with its own controller, service, repository, DTO, mapper, and entity layers, while sharing a common infrastructure layer.

```
┌─────────────────────────────────────────────────────────────────────┐
│                         API Gateway / Controllers                   │
│    AuthController · JobController · PaymentController · ...         │
├─────────────────────────────────────────────────────────────────────┤
│                           Service Layer                             │
│  JobServiceImpl · ResilientPaymentService · BloomFilterService · …  │
├───────────┬─────────────┬──────────────┬──────────────┬─────────────┤
│   user    │    job      │   payment    │   location   │    bid      │
│  module   │   module    │   module     │   module     │   module    │
├───────────┴─────────────┴──────────────┴──────────────┴─────────────┤
│                      Infrastructure Layer                           │
│  Security · Rate Limiting · Circuit Breaker · Scheduling · Metrics  │
│  Redis Caching · Geo Index · Bloom Filters · Distributed Locking    │
├─────────────────────────────────────────────────────────────────────┤
│                         Data Layer                                  │
│            PostgreSQL (source of truth) + Redis (index/cache)       │
└─────────────────────────────────────────────────────────────────────┘
```

**Design principles:**

| Principle                   | Implementation                                                                                                    |
| --------------------------- | ----------------------------------------------------------------------------------------------------------------- |
| Single source of truth      | PostgreSQL owns all state; Redis serves as a read-optimised index/cache                                           |
| Interface-driven services   | Every service has an interface; implementations are swappable (e.g., `RedisJobGeoService` vs `NoOpJobGeoService`) |
| Resilience by default       | Payment, email, and image-upload calls are wrapped with `CircuitBreaker(Retry(call))`                             |
| Profile-aware configuration | Tuning knobs (rate limits, scheduler crons, resilience thresholds) vary per Spring profile                        |
| DTO isolation               | Entities never leak to the API layer; MapStruct handles all mapping                                               |

---

## Tech Stack

| Category             | Technology                                         | Version         |
| -------------------- | -------------------------------------------------- | --------------- |
| **Language**         | Java (Amazon Corretto)                             | 21              |
| **Framework**        | Spring Boot                                        | 3.4.5           |
| **Security**         | Spring Security + JWT (jjwt)                       | 6.x / 0.12.5    |
| **Database**         | PostgreSQL                                         | 17              |
| **ORM**              | Hibernate / Spring Data JPA                        | 6.x             |
| **Cache & Index**    | Redis + Redisson                                   | 7 / 3.25.0      |
| **Geo**              | Redis GEO commands + JTS Core                      | — / 1.18.2      |
| **Rate Limiting**    | Bucket4j (Redis backend) + custom `@RateLimit` AOP | 8.7.0           |
| **Resilience**       | Resilience4j (Circuit Breaker, Retry)              | 2.3.0           |
| **Observability**    | Micrometer + Prometheus + Grafana                  | —               |
| **Actuator**         | Spring Boot Actuator                               | 3.4.5           |
| **API Docs**         | SpringDoc OpenAPI (Swagger UI)                     | 2.7.0           |
| **Mapping**          | MapStruct + Lombok                                 | 1.6.3 / 1.18.34 |
| **Validation**       | Jakarta Bean Validation                            | —               |
| **Build**            | Maven (with Maven Wrapper)                         | 3.11.0          |
| **Code Coverage**    | JaCoCo (80 % line minimum)                         | 0.8.12          |
| **Containerisation** | Docker (multi-stage, Corretto 21 Alpine)           | —               |
| **Orchestration**    | Docker Compose / Kubernetes (k3s)                  | —               |

---

## Folder Structure

```
src/main/java/com/beingadish/AroundU/
├── AroundUApplication.java              # Application entry point
│
├── common/                              # Shared utilities & cross-cutting concerns
│   ├── constants/enums/                 #   JobStatus, PaymentStatus, Role, etc.
│   ├── dto/                             #   ApiResponse<T>, PageResponse<T>
│   ├── exception/                       #   GlobalExceptionHandler, RateLimitExceededException
│   └── util/                            #   DistanceUtils (Haversine), LoggingUtils, SortingUtils
│
├── infrastructure/                      # Technical infrastructure (non-business)
│   ├── analytics/                       #   AggregatedMetrics entity & repository
│   ├── cache/                           #   BloomFilterService, CacheService
│   ├── config/                          #   SecurityConfig, RedisConfig, RateLimitConfig,
│   │                                    #   SchedulerProperties, ResilienceConfig, AsyncConfig
│   ├── lock/                            #   LockServiceBase, RedisLockService, InMemoryLockService
│   ├── metrics/                         #   MetricsService, SchedulerMetricsService
│   ├── ratelimit/                       #   @RateLimit annotation, RateLimitAspect (AOP)
│   ├── scheduler/                       #   UserCleanup, JobExpiration, Reminder,
│   │                                    #   CacheSync, AnalyticsScheduler
│   ├── security/                        #   JwtTokenProvider, JwtAuthenticationFilter,
│   │                                    #   CustomUserDetailsService, UserPrincipal
│   └── storage/                         #   ImageStorageService + impl
│
├── user/                                # User domain module
│   ├── controller/                      #   AuthController, ClientController, WorkerController
│   ├── dto/                             #   Login, registration, profile DTOs
│   ├── entity/                          #   User (abstract), Admin, Client, Worker
│   ├── exception/                       #   ClientNotFoundException, WorkerAlreadyExistException
│   ├── mapper/                          #   ClientMapper, WorkerMapper, UserMapper
│   ├── model/                           #   ClientModel, WorkerModel, UserModel
│   ├── repository/                      #   UserRepository, ClientReadRepository, etc.
│   └── service/                         #   AuthService, ClientService, WorkerService + impls
│
├── job/                                 # Job domain module
│   ├── controller/                      #   JobController, JobCodeController
│   ├── dto/                             #   JobCreateRequest, JobDetailDTO, WorkerJobFeedRequest
│   ├── entity/                          #   Job, JobConfirmationCode
│   ├── event/                           #   JobModifiedEvent, JobExpiredEvent
│   ├── exception/                       #   JobNotFoundException, JobValidationException
│   ├── mapper/                          #   JobMapper, JobConfirmationCodeMapper
│   ├── repository/                      #   JobRepository, JobConfirmationCodeRepository
│   └── service/                         #   JobService, JobCodeService + impls
│
├── bid/                                 # Bid domain module
│   ├── controller/                      #   BidController
│   ├── dto/                             #   BidCreateRequest, BidDetailDTO, BidSummaryDTO
│   ├── entity/                          #   Bid
│   ├── mapper/                          #   BidMapper
│   ├── repository/                      #   BidRepository
│   └── service/                         #   BidService + impl
│
├── payment/                             # Payment domain module
│   ├── controller/                      #   PaymentController
│   ├── dto/                             #   PaymentLockRequest, PaymentReleaseRequest
│   ├── entity/                          #   PaymentTransaction
│   ├── mapper/                          #   PaymentTransactionMapper
│   ├── repository/                      #   PaymentTransactionRepository
│   └── service/                         #   PaymentService, ResilientPaymentService, impl
│
├── location/                            # Geolocation module
│   ├── entity/                          #   Address, FailedGeoSync
│   ├── repository/                      #   AddressRepository, FailedGeoSyncRepository
│   └── service/                         #   JobGeoService, JobGeoSyncService,
│                                        #   RedisJobGeoService, NoOpJobGeoService
│
├── notification/                        # Notification module
│   ├── entity/                          #   FailedNotification
│   ├── repository/                      #   FailedNotificationRepository
│   └── service/                         #   EmailService, NotificationService + impls
│
├── review/                              # Review & rating module
│   └── entity/                          #   Review
│
└── skill/                               # Skill / category module
    ├── entity/                          #   Skill
    ├── repository/                      #   SkillRepository
    └── service/                         #   SkillService + impl
```

```
src/test/java/                           # Mirrors main structure
├── Unit tests for services, utils, mappers
├── Controller tests with MockMvc
├── Integration tests (SecurityBoundaryIntegrationTest, etc.)
└── Resilience & geo-search specific tests
```

---

## Local Setup

### Prerequisites

| Requirement | Minimum Version                 |
| ----------- | ------------------------------- |
| Java (JDK)  | 21                              |
| Maven       | 3.8+ (or use included `./mvnw`) |
| PostgreSQL  | 14+                             |
| Redis       | 6+                              |
| Git         | 2.x                             |

### Steps

1. **Clone the repository**

   ```bash
   git clone https://github.com/beingadish/aroundu-backend.git
   cd aroundu-backend
   ```

2. **Create a PostgreSQL database**

   ```bash
   createdb aroundu_dev
   ```

3. **Configure environment variables**

   ```bash
   cp .env.example .env   # or create .env manually
   ```

   Set at minimum:

   ```dotenv
   JWT_SECRET=<your-256-bit-secret>
   DEV_DB_URL=jdbc:postgresql://localhost:5432/aroundu_dev
   DEV_DB_USERNAME=aroundu_dev
   DEV_DB_PASSWORD=devpassword
   REDIS_HOST=localhost
   REDIS_PORT=6379
   ```

4. **Build and run**

   ```bash
   ./mvnw clean install
   ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
   ```

5. **Verify**

   ```bash
   curl http://localhost:20232/actuator/health
   # → {"status":"UP", ...}
   ```

> The `dev` profile uses `ddl-auto: update`, so Hibernate creates/updates tables automatically.

---

## Docker Setup

A single `docker-compose.yml` brings up the full stack: **App**, **PostgreSQL 17**, **Redis 7**, **Prometheus**, and **Grafana**.

```bash
# Build and start all services
docker compose up --build -d

# View logs
docker compose logs -f app

# Tear down (including volumes)
docker compose down -v
```

| Service     | URL                     | Default Credentials           |
| ----------- | ----------------------- | ----------------------------- |
| Application | `http://localhost:8080` | —                             |
| PostgreSQL  | `localhost:5433`        | `aroundu_dev` / `devpassword` |
| Redis       | `localhost:6379`        | —                             |
| Prometheus  | `http://localhost:9090` | —                             |
| Grafana     | `http://localhost:3000` | `admin` / `admin`             |

The application container runs as a non-root `aroundu` user with JVM container-awareness flags (`-XX:+UseContainerSupport`, `-XX:MaxRAMPercentage=75.0`) and includes a health check via `/actuator/health`.

---

## Kubernetes Deployment (EC2 / k3s)

The `k8s/` directory contains production-grade Kubernetes manifests for deploying the full stack on a **single EC2 instance** running **k3s**. This setup replaces the system Nginx with an in-cluster NGINX Ingress Controller and includes auto-scaling (HPA) and self-healing (probes).

### Prerequisites

| Requirement | Detail |
| ----------- | ------ |
| EC2 Instance | Ubuntu 22.04+ or Amazon Linux 2023 |
| Instance Type | t3.medium or larger (4 GB+ RAM recommended) |
| Docker | Installed (`sudo apt install docker.io`) |
| k3s | Installed with Traefik disabled |
| Security Groups | Inbound: 22 (SSH), 80 (HTTP), 443 (HTTPS) |

### 1. Install k3s

```bash
# Install k3s — disable built-in Traefik (we use NGINX Ingress instead)
curl -sfL https://get.k3s.io | sh -s - --disable traefik --disable servicelb

# Configure kubectl
mkdir -p ~/.kube
sudo cp /etc/rancher/k3s/k3s.yaml ~/.kube/config
sudo chown $(id -u):$(id -g) ~/.kube/config
export KUBECONFIG=~/.kube/config
echo 'export KUBECONFIG=~/.kube/config' >> ~/.bashrc
```

### 2. Build & Import the Docker Image

Since there is no external container registry, the image is built with Docker and imported into k3s's containerd:

```bash
cd /path/to/aroundu-backend
sudo docker build -t aroundu-backend:latest .
sudo docker save aroundu-backend:latest | sudo k3s ctr images import -
```

### 3. Install NGINX Ingress Controller

```bash
kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-v1.12.2/deploy/static/provider/baremetal/deploy.yaml

# Patch to use hostNetwork so ports 80/443 bind directly to the EC2 host
kubectl patch deployment ingress-nginx-controller -n ingress-nginx \
  --type=json \
  -p='[
    {"op":"add","path":"/spec/template/spec/hostNetwork","value":true},
    {"op":"replace","path":"/spec/template/spec/containers/0/ports/0/containerPort","value":80},
    {"op":"replace","path":"/spec/template/spec/containers/0/ports/1/containerPort","value":443}
  ]'
```

### 4. Install Metrics Server (required for HPA)

```bash
kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml

# Patch for single-node k3s (disable TLS verification to kubelet)
kubectl patch deployment metrics-server -n kube-system \
  --type=json \
  -p='[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--kubelet-insecure-tls"}]'
```

### 5. Remove System Nginx

```bash
sudo systemctl stop nginx
sudo systemctl disable nginx
# Verify port 80 is free
sudo ss -tlnp | grep ':80'
```

### 6. Fill In Secrets

Edit `k8s/secrets.yaml` and replace the empty `""` values with real base64-encoded secrets:

```bash
# Encode a value
echo -n 'your-secret-value' | base64
```

> **Warning:** Never commit `secrets.yaml` with real values. It is already covered by `.gitignore`.

### 7. Apply Manifests

```bash
kubectl apply -f k8s/namespace.yaml
kubectl apply -f k8s/secrets.yaml
kubectl apply -f k8s/configmap.yaml
kubectl apply -f k8s/postgres/
kubectl apply -f k8s/redis/
kubectl apply -f k8s/app/
kubectl apply -f k8s/monitoring/
kubectl apply -f k8s/ingress/
```

### 8. Verify

```bash
kubectl get pods -n aroundu
kubectl get svc -n aroundu
kubectl get ingress -n aroundu
kubectl get hpa -n aroundu
```

### Update & Redeploy

```bash
# Pull latest code, rebuild, re-import
git pull
sudo docker build -t aroundu-backend:latest .
sudo docker save aroundu-backend:latest | sudo k3s ctr images import -

# Rolling restart (zero-downtime)
kubectl rollout restart deployment/aroundu-app -n aroundu
kubectl rollout status deployment/aroundu-app -n aroundu
```

### Manual Scaling

```bash
# Scale to 3 replicas
kubectl scale deployment/aroundu-app -n aroundu --replicas=3

# Check HPA status
kubectl get hpa -n aroundu
```

### Rollback

```bash
# View revision history
kubectl rollout history deployment/aroundu-app -n aroundu

# Roll back to previous revision
kubectl rollout undo deployment/aroundu-app -n aroundu
```

### k8s/ Folder Structure

```
k8s/
├── namespace.yaml
├── secrets.yaml                              ← base64 template (fill before applying)
├── configmap.yaml
├── postgres/
│   ├── pvc.yaml                              (5 Gi, local-path)
│   ├── deployment.yaml                       (postgres:17-alpine)
│   └── service.yaml                          (ClusterIP :5432)
├── redis/
│   ├── pvc.yaml                              (1 Gi, local-path)
│   ├── deployment.yaml                       (redis:7-alpine)
│   └── service.yaml                          (ClusterIP :6379)
├── app/
│   ├── deployment.yaml                       (RollingUpdate, probes, init containers)
│   ├── service.yaml                          (ClusterIP :8080)
│   └── hpa.yaml                              (CPU 60%, Memory 80%, 1–3 replicas)
├── monitoring/
│   ├── prometheus-configmap.yaml
│   ├── prometheus-pvc.yaml                   (2 Gi)
│   ├── prometheus-deployment.yaml
│   ├── prometheus-service.yaml               (ClusterIP :9090)
│   ├── grafana-pvc.yaml                      (1 Gi)
│   ├── grafana-deployment.yaml
│   ├── grafana-service.yaml                  (ClusterIP :3000)
│   ├── grafana-datasources-configmap.yaml
│   └── grafana-dashboard-provider-configmap.yaml
└── ingress/
    └── ingress.yaml                          (NGINX Ingress, WebSocket support)
```

---

## Environment Configuration

All configuration is externalised via environment variables. The application uses a layered YAML configuration:

| File                      | Purpose                                             |
| ------------------------- | --------------------------------------------------- |
| `application.yml`         | Shared defaults and common configuration            |
| `application-dev.yml`     | Local development (verbose logging, relaxed limits) |
| `application-test.yml`    | Automated tests (H2 in-memory database)             |
| `application-preprod.yml` | Staging environment                                 |
| `application-prod.yml`    | Production (strict limits, minimal exposure)        |
| `application-railway.yml` | Railway.app cloud deployment                        |

### Key Environment Variables

| Variable                    | Description                     | Default                                        |
| --------------------------- | ------------------------------- | ---------------------------------------------- |
| `SPRING_PROFILES_ACTIVE`    | Active Spring profile           | `dev`                                          |
| `SERVER_PORT`               | Server port                     | `20232`                                        |
| `JWT_SECRET`                | HMAC signing key for JWT tokens | **required**                                   |
| `JWT_EXPIRATION_MS`         | Access token TTL (ms)           | `86400000` (24 h)                              |
| `JWT_REFRESH_EXPIRATION_MS` | Refresh token TTL (ms)          | `604800000` (7 d)                              |
| `DEV_DB_URL`                | JDBC connection URL             | `jdbc:postgresql://localhost:5432/aroundu_dev` |
| `DEV_DB_USERNAME`           | Database user                   | `aroundu_dev`                                  |
| `DEV_DB_PASSWORD`           | Database password               | `devpassword`                                  |
| `REDIS_HOST`                | Redis hostname                  | `localhost`                                    |
| `REDIS_PORT`                | Redis port                      | `6379`                                         |
| `REDIS_PASSWORD`            | Redis authentication password   | _(empty)_                                      |
| `ADMIN_EMAIL`               | Seeded admin email              | `admin@aroundu.com`                            |
| `ADMIN_PASSWORD`            | Seeded admin password           | `arounduadmin`                                 |
| `FEATURE_ENABLE_SWAGGER`    | Toggle Swagger UI               | `true`                                         |
| `FEATURE_ENABLE_DUMMY_DATA` | Toggle test data seeding        | `false`                                        |

---

## Running with Different Profiles

| Method     | Command                                                                     |
| ---------- | --------------------------------------------------------------------------- |
| **Maven**  | `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`                     |
| **JAR**    | `java -jar -Dspring.profiles.active=prod target/AroundU-0.0.1-SNAPSHOT.jar` |
| **Docker** | `SPRING_PROFILES_ACTIVE=prod docker compose up --build`                     |
| **IDE**    | Add VM option `-Dspring.profiles.active=dev`                                |

### Profile Behaviour Summary

| Aspect                | dev                  | test          | preprod                           | prod                        |
| --------------------- | -------------------- | ------------- | --------------------------------- | --------------------------- |
| DDL mode              | `update`             | `create-drop` | `validate`                        | `validate`                  |
| SQL logging           | `DEBUG`              | off           | off                               | off                         |
| Swagger               | enabled              | disabled      | enabled                           | **disabled**                |
| Actuator exposure     | `*` (all)            | health, info  | health, info, prometheus, metrics | health, info, prometheus    |
| Rate limits           | relaxed (100/min)    | disabled      | moderate                          | strict (5–30/hr)            |
| Schedulers            | aggressive (2–5 min) | disabled      | moderate                          | conservative (daily/hourly) |
| Resilience thresholds | lenient              | —             | moderate                          | strict                      |

---

## API Documentation (Swagger)

Interactive API documentation is available via **SpringDoc OpenAPI 2.7.0** when Swagger is enabled (`feature.enable-swagger: true`).

| Endpoint                          | Description               |
| --------------------------------- | ------------------------- |
| `http://localhost:20232/docs`     | Swagger UI                |
| `http://localhost:20232/api-docs` | Raw OpenAPI 3.0 JSON spec |

All endpoints are prefixed with `/api/v1` and return a unified response envelope:

```json
{
  "success": true,
  "message": "Operation successful",
  "data": {},
  "statusCode": 200,
  "status": "OK",
  "timestamp": "2026-02-19T12:34:56.789"
}
```

Authentication uses **Bearer JWT tokens** in the `Authorization` header for all protected endpoints.

---

## Monitoring & Observability

### Architecture

```
┌──────────────┐       ┌────────────┐       ┌──────────┐
│  Spring Boot │──────►│ Prometheus │──────►│ Grafana  │
│   Actuator   │scrape │   :9090    │query  │  :3000   │
│  /actuator/  │       └────────────┘       └──────────┘
│  prometheus  │
└──────────────┘
```

### Actuator Endpoints

| Endpoint                   | Access | Description                                    |
| -------------------------- | ------ | ---------------------------------------------- |
| `/actuator/health`         | Public | Aggregated health (DB, Redis, Payment Gateway) |
| `/actuator/info`           | Public | Application metadata                           |
| `/actuator/prometheus`     | Admin  | Prometheus scrape target                       |
| `/actuator/metrics/{name}` | Admin  | Individual metric detail                       |
| `/actuator/caches`         | Admin  | Cache statistics                               |

### Custom Business Metrics

All metrics are prefixed with `aroundu.` and registered in `MetricsService`:

| Metric                            | Type    | Description              |
| --------------------------------- | ------- | ------------------------ |
| `aroundu.jobs.created`            | Counter | Total jobs created       |
| `aroundu.jobs.completed`          | Counter | Total jobs completed     |
| `aroundu.jobs.active`             | Gauge   | Current active job count |
| `aroundu.jobs.creation.duration`  | Timer   | Job creation latency     |
| `aroundu.bids.placed`             | Counter | Total bids placed        |
| `aroundu.bids.placement.duration` | Timer   | Bid placement latency    |
| `aroundu.payments.escrow.locked`  | Counter | Escrow lock events       |
| `aroundu.payments.failures`       | Counter | Payment failures         |
| `aroundu.auth.login.success`      | Counter | Successful logins        |
| `aroundu.auth.login.failure`      | Counter | Failed logins            |
| `aroundu.auth.registrations`      | Counter | New registrations        |

### Custom Health Indicators

| Indicator                       | Checks                                   |
| ------------------------------- | ---------------------------------------- |
| `RedisHealthIndicator`          | Redis `PING` response                    |
| `DatabaseHealthIndicator`       | JDBC `connection.isValid()` via HikariCP |
| `PaymentGatewayHealthIndicator` | External payment provider reachability   |

### Prometheus Configuration

Prometheus scrapes the application at `/actuator/prometheus` every 10 seconds (configured in `monitoring/prometheus.yml`). Grafana dashboards are provisioned automatically via volume mounts in Docker Compose.

---

## Caching Strategy

AroundU uses a **multi-layer caching strategy** with Redis as the primary cache and Bloom filters for fast negative lookups.

### Redis Caching

- **Geo Index** — `geo:jobs:open` sorted set stores `{jobId → (lon, lat)}` for `GEORADIUS` queries.
- **Session / Token caching** — Reduces database round-trips for authentication.
- **Eviction policy** — `allkeys-lru` with a 128 MB memory cap (configurable).

### Bloom Filters (via Redisson)

`BloomFilterService` uses probabilistic data structures for:

- Fast duplicate detection on job postings.
- Quick "does this entity exist?" checks before hitting the database.
- Reducing unnecessary DB queries for non-existent resources.

### Cache Synchronisation

`CacheSyncScheduler` periodically reconciles Redis with PostgreSQL:

- Retries failed geo-sync operations (tracked in the `FailedGeoSync` entity).
- Ensures the Redis geo index stays consistent with the database.
- Runs every 5 minutes in dev, every 30 minutes in production.

---

## Geo Search

AroundU implements a **dual-store geo search** architecture for proximity-based job discovery.

### How It Works

```
Worker requests feed ──► Redis GEORADIUS (geo:jobs:open)
                         ──► Returns candidate job IDs within radius
                         ──► PostgreSQL validates status + fetches full data
                         ──► In-memory skill filtering
                         ──► Haversine distance enrichment
                         ──► Sorted by distance / popularity
                         ──► Paginated response
```

1. **Redis as a spatial index** — Job coordinates are stored in a Redis GEO sorted set. `GEORADIUS` performs O(N+log(M)) proximity lookups.
2. **PostgreSQL as source of truth** — Every geo result is re-validated (`findByIdInAndJobStatus`) to eliminate stale entries.
3. **Fallback** — If Redis returns no results (or the worker has no location), the system falls back to skill-based matching via PostgreSQL.
4. **Sync reliability** — A `FailedGeoSync` entity plus a retry scheduler ensures eventual consistency between PostgreSQL and Redis.

### Distance Enrichment

`DistanceUtils.haversine()` calculates great-circle distances between coordinates. Each job in the feed response includes a `distanceKm` field and an optional `popularityScore` based on bid count.

> For full architectural detail, see [GEOSEARCH.md](docs/GEOSEARCH.md).

---

## Key Features & Business Logic

### Skill Management

Skills are auto-created and normalized (trimmed, lowercased, whitespace-collapsed). When a client creates a job, they can provide skill **names** instead of IDs — the system will find existing skills or create new ones (case-insensitive, unique constraint).

- **Auto-suggest endpoint**: `GET /api/v1/skills/suggest?query=plu&limit=10` — Returns matching skills via PostgreSQL `LIKE` query.
- **Optimistic concurrency**: If two requests try to create the same skill simultaneously, the second catches `DataIntegrityViolationException` and reads from DB.

### Job Lifecycle

```
CREATED → OPEN_FOR_BIDS → BID_SELECTED_AWAITING_HANDSHAKE → READY_TO_START
  → IN_PROGRESS → COMPLETED_PENDING_PAYMENT → PAYMENT_RELEASED → COMPLETED
```

Cancellation can happen from `OPEN_FOR_BIDS`, `BID_SELECTED_AWAITING_HANDSHAKE`, `READY_TO_START`, or `IN_PROGRESS`.

### OTP Verification System

Job start and release are verified by 6-digit OTPs generated via `SecureRandom`:

| Endpoint                                | Purpose                                                               |
| --------------------------------------- | --------------------------------------------------------------------- |
| `POST /api/v1/jobs/{id}/codes`          | Generate OTP pair (start + release)                                   |
| `POST /api/v1/jobs/{id}/codes/start`    | Worker verifies start code → job moves to IN_PROGRESS                 |
| `POST /api/v1/jobs/{id}/codes/release`  | Client verifies release code → job moves to COMPLETED_PENDING_PAYMENT |
| `POST /api/v1/jobs/{id}/otp/regenerate` | Invalidate old OTPs and generate new ones (rate-limited: 1/min)       |

Security features: configurable expiry (`otp.expiry-minutes`, default 30), max 5 verification attempts before lockout, brute-force protection.

### Single Active Job Enforcement

- **Workers**: Cannot place new bids if they already have a job in `BID_SELECTED_AWAITING_HANDSHAKE`, `READY_TO_START`, `IN_PROGRESS`, or `COMPLETED_PENDING_PAYMENT`.
- **Clients**: Cannot create a new job if they have one in `IN_PROGRESS`.

### Worker Cancellation Penalty

When a worker cancels an accepted/in-progress job:

1. Their `cancellationCount` increments.
2. Job reverts to `OPEN_FOR_BIDS` (worker unassigned).
3. If cancellations reach threshold (default 3, configurable via `worker.penalty.cancellation-threshold`), worker is **blocked** for N days (default 7, `worker.penalty.block-days`).
4. Blocked workers cannot place new bids.
5. `WorkerPenaltyScheduler` runs hourly to auto-unblock expired penalties.

### Profile Image Upload

- `POST /api/v1/users/{userId}/profile-image` — Upload JPEG/PNG (max 5 MB)
- `DELETE /api/v1/users/{userId}/profile-image` — Remove image
- Uses the `ImageStorageService` abstraction (S3 in prod, local in dev)

### Admin Dashboard

- `GET /api/v1/admin/overview` — Platform statistics (total clients/workers, active/open jobs, today's activity)
- Actuator endpoints (`/actuator/*`) restricted to ADMIN role except health and prometheus
- Admin seeded on startup via environment variables

### Role-Based Access Control

| Role     | Capabilities                                                                      |
| -------- | --------------------------------------------------------------------------------- |
| `CLIENT` | Create/manage jobs, accept bids, verify OTPs, upload profile image                |
| `WORKER` | Browse job feed, place bids, handshake, start/complete jobs, upload profile image |
| `ADMIN`  | All CLIENT + WORKER actions, plus admin dashboard, actuator, user management      |

---

## Rate Limiting

AroundU implements **per-user, per-endpoint rate limiting** using a custom `@RateLimit` annotation backed by **Bucket4j** with a Redis token-bucket backend.

### How It Works

1. A method-level `@RateLimit` annotation declares the bucket configuration.
2. `RateLimitAspect` (Spring AOP) intercepts the call, resolves the user identity (authenticated user ID or client IP), and attempts to consume a token.
3. If the bucket is empty, the request is rejected with **HTTP 429 Too Many Requests**.
4. Bucket state is stored in Redis for distributed consistency across instances.

### Configuration per Profile

| Endpoint      | dev         | prod         |
| ------------- | ----------- | ------------ |
| Job creation  | 100 req/min | 5 req/hr     |
| Bid placement | 100 req/min | 20 req/hr    |
| Worker feed   | 100 req/min | 30 req/min   |
| Profile view  | 100 req/min | 100 req/hr   |
| Auth (login)  | 100 req/min | 5 req/15 min |

Rate limits are fully configurable via `rate-limit.*` properties in each profile's YAML.

---

## Circuit Breaker & Resilience

External service calls are protected by **Resilience4j** circuit breakers and retries, configured per service and per profile.

### Protected Services

| Service             | Circuit Breaker                        | Retry                                  | Fallback                                        |
| ------------------- | -------------------------------------- | -------------------------------------- | ----------------------------------------------- |
| **Payment Gateway** | 50 % failure threshold, 30 s open wait | 3 attempts, 500 ms exponential backoff | Queue for manual processing + admin email alert |
| **Email Service**   | 70 % failure threshold, 60 s open wait | 5 attempts, 1 s exponential backoff    | Store in `FailedNotification` for retry         |
| **Image Upload**    | 60 % failure threshold, 30 s open wait | 2 attempts, 200 ms exponential backoff | Return error to user                            |

### ResilientPaymentService

`ResilientPaymentService` is marked `@Primary` and decorates the core `PaymentServiceImpl`:

```
CircuitBreaker ──► Retry ──► PaymentServiceImpl.lockEscrow()
                              │
                              ▼ (on total failure)
                   Log critical error
                   ──► Increment aroundu.payments.failures counter
                   ──► Queue FailedPaymentRecord for manual review
                   ──► Send admin alert email
                   ──► Return PENDING_ESCROW transaction
```

Circuit breaker and retry metrics are exported to Prometheus via `resilience4j-micrometer`.

### Profile-Specific Tuning

| Parameter                       | dev     | prod    |
| ------------------------------- | ------- | ------- |
| CB failure threshold            | 70–80 % | 50–70 % |
| CB open-state wait              | 10–15 s | 30–60 s |
| Minimum calls before evaluation | 3       | 5       |
| Retry attempts                  | 2       | 2–5     |

---

## Background Jobs

Six scheduled background jobs handle maintenance, expiration, and analytics. All are configurable via `scheduler.*` YAML properties and use **distributed locking** to ensure single execution in multi-instance deployments.

| Scheduler                | Purpose                                                                 | Default Schedule (prod) |
| ------------------------ | ----------------------------------------------------------------------- | ----------------------- |
| `UserCleanupScheduler`   | Removes users inactive for N years                                      | Daily at 02:00          |
| `JobExpirationScheduler` | Expires open jobs older than N days                                     | Hourly                  |
| `ReminderScheduler`      | Sends reminders for upcoming / overdue jobs                             | Every 6 hours           |
| `CacheSyncScheduler`     | Reconciles Redis geo index with PostgreSQL                              | Every 30 minutes        |
| `AnalyticsScheduler`     | Aggregates daily business metrics (jobs, bids, payments, growth trends) | Daily at 03:00          |
| `WorkerPenaltyScheduler` | Unblocks workers whose cancellation-penalty period has expired          | Hourly                  |

### Features

- **Distributed locking** via `LockServiceBase` (`RedisLockService` in prod, `InMemoryLockService` in test) prevents duplicate execution across multiple app instances.
- **Profile-aware** — All schedulers are disabled in the `test` profile (`@Profile("!test")`).
- **Observable** — `SchedulerMetricsService` records execution count, duration, and failures for each scheduler.

---

## Distributed Tracing & Metrics

### Micrometer + Prometheus

All HTTP requests, JVM metrics, HikariCP connection pool stats, and custom business metrics are exported via Micrometer to the `/actuator/prometheus` endpoint.

Key metric categories:

| Category         | Examples                                                                   |
| ---------------- | -------------------------------------------------------------------------- |
| **HTTP**         | `http.server.requests` (with p50, p90, p95, p99 histograms)                |
| **JVM**          | Memory usage, GC pauses, active threads, class loading                     |
| **HikariCP**     | Connection pool active / idle / pending                                    |
| **Resilience4j** | Circuit breaker state transitions, retry counts, failure rates             |
| **Business**     | `aroundu.jobs.*`, `aroundu.bids.*`, `aroundu.payments.*`, `aroundu.auth.*` |
| **Scheduler**    | Execution count, duration, and failures per scheduler                      |
| **Async**        | Thread pool active / queued / completed (via `ExecutorServiceMetrics`)     |

### Grafana Dashboards

Pre-provisioned dashboards are mounted into Grafana via Docker Compose at `monitoring/grafana/dashboards/`. Datasource and dashboard provisioning configuration is auto-loaded from `monitoring/grafana/provisioning/`.

---

## Ranking Engine Integration

The backend integrates with the [AroundU Ranking Engine](https://github.com/AroundUPlatform/aroundu-ranking-engine) — a high-performance Rust-based gRPC service that provides ML-powered job–worker matching via a multi-stage candidate pipeline.

### How It Works

```
┌─────────────────┐     gRPC (port 50052)     ┌─────────────────────┐
│  Spring Boot    │ ◄──────────────────────► │  Ranking Engine     │
│  Backend        │                           │  (Rust / tonic)     │
│                 │   GetWorkerFeed           │                     │
│  JobServiceImpl │──────────────────────────►│  Candidate Pipeline │
│                 │   RankedFeedResponse      │  ┌─Retrieval       │
│                 │◄──────────────────────────│  ├─Filtering        │
│                 │                           │  ├─Scoring          │
│                 │   RecordInteraction       │  └─Blending         │
│                 │──────────────────────────►│                     │
└─────────────────┘                           └─────────────────────┘
```

### Key Components

| Component | Path | Purpose |
|-----------|------|---------|
| Proto definition | `src/main/proto/aroundu/ranking/v1/ranking.proto` | gRPC service contract (3 RPCs) |
| gRPC config | `infrastructure/config/RankingEngineConfig.java` | Channel management, feature flag |
| gRPC client | `infrastructure/ranking/RankingEngineClient.java` | Blocking stub with 5s deadline, graceful fallback |
| Integration point | `job/service/impl/JobServiceImpl.getWorkerFeed()` | Ranking-first with local fallback |

### Configuration

```yaml
ranking-engine:
  host: ${RANKING_ENGINE_HOST:localhost}
  port: ${RANKING_ENGINE_PORT:50052}
  enabled: ${RANKING_ENGINE_ENABLED:false}   # Feature flag — false by default
```

When `enabled: false` or the ranking engine is unreachable, the backend falls back to its existing geo-search + skill-filter + sort pipeline — **zero downtime risk**.

### Kubernetes

Dedicated K8s manifests are provided in `k8s/ranking-engine/`:

- **deployment.yaml** — Single replica with init container (wait-for-postgres), resource limits (256Mi / 500m CPU)
- **service.yaml** — ClusterIP on port 50052 (`ranking-engine-service`)
- **hpa.yaml** — Auto-scales 1–3 replicas based on CPU (70%) and memory (80%)

The main app deployment (`k8s/app/deployment.yaml`) is pre-configured to connect to the ranking engine service.

---

## Future Scalability Plan

| Initiative                    | Description                                                                                                |
| ----------------------------- | ---------------------------------------------------------------------------------------------------------- |
| **Service decomposition**     | Extract `payment`, `notification`, and `analytics` into standalone microservices when traffic justifies it |
| **Event-driven architecture** | Introduce Apache Kafka or RabbitMQ for async job events and notification fanout                            |
| **Read replicas**             | PostgreSQL streaming replication for read-heavy worker feed queries                                        |
| **Redis Cluster**             | Transition from standalone Redis to a clustered topology for high availability                             |
| **CDN for static assets**     | Offload image and certificate storage to S3 + CloudFront                                                   |
| **WebSocket**                 | Real-time job status updates and in-app messaging                                                          |
| **Push notifications**        | Firebase Cloud Messaging for mobile clients                                                                |
| **Full-text search**          | Elasticsearch for skill-based and textual job search                                                       |
| **API Gateway**               | Centralised rate limiting, auth, and routing via Spring Cloud Gateway or Kong                              |
| **CI/CD pipeline**            | GitHub Actions with automated test, coverage gate (80 %+), Docker build, and deployment                    |

---

## Contributing

Contributions are welcome. Please follow these guidelines:

### Getting Started

1. Fork the repository and create a feature branch from `dev`.
2. Make your changes with appropriate test coverage.
3. Ensure all tests pass: `./mvnw clean test`
4. Ensure code coverage meets the 80 % threshold: `./mvnw verify`
5. Submit a pull request targeting `dev`.

### Code Standards

- **Style** — Standard Java naming conventions and Spring Boot idioms.
- **Commits** — [Conventional Commits](https://www.conventionalcommits.org/) (`feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `chore:`).
- **Testing** — Unit tests for services, integration tests for controllers. Minimum 80 % line coverage enforced by JaCoCo.
- **Documentation** — Update this README and add Javadoc for public APIs.

### Branch Strategy

| Branch      | Purpose                                   |
| ----------- | ----------------------------------------- |
| `main`      | Stable, production-ready releases         |
| `dev`       | Integration branch for active development |
| `feature/*` | Individual feature branches               |
| `hotfix/*`  | Critical production fixes                 |

---

## License

This project is **proprietary** and not open-source. All rights reserved. Unauthorised copying, distribution, or modification is prohibited without explicit permission from the author.

---

## Production Deployment Notes

### Pre-Deployment Checklist

- [ ] Set `SPRING_PROFILES_ACTIVE=prod`
- [ ] Use `ddl-auto: validate` — never `update` or `create` in production
- [ ] Apply database migrations via a dedicated tool (Flyway / Liquibase) **before** deployment
- [ ] Set a strong, unique `JWT_SECRET` (minimum 256-bit)
- [ ] Disable Swagger UI (`FEATURE_ENABLE_SWAGGER=false`)
- [ ] Disable debug security (`FEATURE_ENABLE_DEBUG_SECURITY=false`)
- [ ] Disable test data seeding (`FEATURE_ENABLE_DUMMY_DATA=false`)
- [ ] Configure production Redis with authentication (`PROD_REDIS_PASSWORD`)
- [ ] Enable TLS/SSL termination at the load balancer
- [ ] Restrict Actuator endpoints (only `health`, `info`, `prometheus` are exposed in prod)
- [ ] Set up log aggregation (ELK / CloudWatch / Loki)
- [ ] Configure Prometheus scrape targets for the production host
- [ ] Set up Grafana alerting rules for circuit breaker state changes and error rate spikes
- [ ] Run behind a reverse proxy (Nginx / ALB) with proper CORS and security headers

### JVM Tuning (Docker)

The Dockerfile applies container-aware JVM flags:

```
-XX:+UseContainerSupport
-XX:MaxRAMPercentage=75.0
-XX:InitialRAMPercentage=50.0
-Djava.security.egd=file:/dev/./urandom
```

### Health Checks

Both the `Dockerfile` and `docker-compose.yml` define health checks against `/actuator/health` to enable orchestrator-level readiness and liveness gating.

---

<div align="center">

**Developed by [beingadish](https://github.com/beingadish)**

</div>
