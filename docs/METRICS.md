# AroundU Monitoring & Observability

## Overview

AroundU uses **Spring Boot Actuator**, **Micrometer**, **Prometheus**, and **Grafana** for comprehensive observability across all environments.

---

## Quick Start

```bash
docker-compose up -d
```

| Service    | URL                             | Credentials   |
| ---------- | ------------------------------- | ------------- |
| App        | http://localhost:20232          | —             |
| Actuator   | http://localhost:20232/actuator | (see below)   |
| Prometheus | http://localhost:9090           | —             |
| Grafana    | http://localhost:3000           | admin / admin |

---

## Actuator Endpoints

### Public (no auth)

- `GET /actuator/health` — aggregated health status
- `GET /actuator/info` — application info

### Admin-only (requires ADMIN role JWT)

- `GET /actuator/prometheus` — Prometheus scrape endpoint
- `GET /actuator/metrics` — full metrics listing
- `GET /actuator/metrics/{name}` — specific metric detail
- All other actuator endpoints

### Per-Profile Exposure

| Profile | Exposed Endpoints                 | Health Details  |
| ------- | --------------------------------- | --------------- |
| dev     | `*` (all)                         | always          |
| test    | health, info                      | always          |
| preprod | health, info, prometheus, metrics | when-authorized |
| prod    | health, info, prometheus          | when-authorized |

---

## Custom Business Metrics

All custom metrics are prefixed with `aroundu.` and registered in `MetricsService`.

### Jobs

| Metric                           | Type    | Description               |
| -------------------------------- | ------- | ------------------------- |
| `aroundu.jobs.created`           | Counter | Total jobs created        |
| `aroundu.jobs.completed`         | Counter | Total jobs completed      |
| `aroundu.jobs.cancelled`         | Counter | Total jobs cancelled      |
| `aroundu.jobs.active`            | Gauge   | Current active jobs count |
| `aroundu.jobs.creation.duration` | Timer   | Job creation latency      |

### Bids

| Metric                            | Type    | Description           |
| --------------------------------- | ------- | --------------------- |
| `aroundu.bids.placed`             | Counter | Total bids placed     |
| `aroundu.bids.accepted`           | Counter | Total bids accepted   |
| `aroundu.bids.rejected`           | Counter | Total bids rejected   |
| `aroundu.bids.placement.duration` | Timer   | Bid placement latency |

### Payments

| Metric                                     | Type    | Description            |
| ------------------------------------------ | ------- | ---------------------- |
| `aroundu.payments.escrow.locked`           | Counter | Total escrow locks     |
| `aroundu.payments.escrow.released`         | Counter | Total escrow releases  |
| `aroundu.payments.failures`                | Counter | Total payment failures |
| `aroundu.payments.escrow.lock.duration`    | Timer   | Escrow lock latency    |
| `aroundu.payments.escrow.release.duration` | Timer   | Escrow release latency |

### Auth

| Metric                       | Type    | Description              |
| ---------------------------- | ------- | ------------------------ |
| `aroundu.auth.login.success` | Counter | Successful login count   |
| `aroundu.auth.login.failure` | Counter | Failed login count       |
| `aroundu.auth.registrations` | Counter | Total user registrations |

---

## Custom Health Indicators

| Indicator       | Bean Class                      | What it checks                                   |
| --------------- | ------------------------------- | ------------------------------------------------ |
| Redis           | `RedisHealthIndicator`          | `PING` response from Redis                       |
| Database        | `DatabaseHealthIndicator`       | JDBC `connection.isValid(2)` via HikariCP        |
| Payment Gateway | `PaymentGatewayHealthIndicator` | Reachability of external payment provider (stub) |

Health endpoint response includes all indicators:

```json
{
  "status": "UP",
  "components": {
    "redis": { "status": "UP", "details": { "redis": "Available" } },
    "database": { "status": "UP", "details": { "product": "PostgreSQL" } },
    "paymentGateway": { "status": "UP" },
    "diskSpace": { "status": "UP" },
    "db": { "status": "UP" }
  }
}
```

---

## Auto-Collected Metrics

Spring Boot Actuator + Micrometer automatically collect:

- **HTTP**: `http.server.requests` — count, sum, max, histogram buckets per URI/method/status
- **JVM**: `jvm.memory.*`, `jvm.gc.*`, `jvm.threads.*`, `jvm.classes.*`
- **HikariCP**: `hikaricp.connections.*` (active, idle, pending, max, timeout)
- **System**: `system.cpu.usage`, `process.cpu.usage`, `process.uptime`
- **Logback**: `logback.events` per level

---

## Prometheus

Config: [`monitoring/prometheus.yml`](../monitoring/prometheus.yml)

- Scrapes `/actuator/prometheus` on `app:20232` every 10 seconds
- Labels: `application=AroundU`, `environment=dev`

Access Prometheus UI at http://localhost:9090 to query metrics directly.

### Example PromQL Queries

```promql
# Request rate by endpoint
rate(http_server_requests_seconds_count{job="aroundu-backend"}[5m])

# p95 latency across all endpoints
histogram_quantile(0.95, rate(http_server_requests_seconds_bucket{job="aroundu-backend"}[5m]))

# Job creation rate
rate(aroundu_jobs_created_total{job="aroundu-backend"}[5m])

# Active HikariCP connections
hikaricp_connections_active{job="aroundu-backend"}
```

---

## Grafana

Access at http://localhost:3000 (default: `admin` / `admin`).

### Pre-provisioned Dashboard: "AroundU Backend Monitoring"

The dashboard auto-loads via provisioning and includes:

1. **Application Health** — UP/DOWN stat
2. **HTTP Request Rate** — requests/sec per endpoint
3. **HTTP Request Duration** — p95 latency
4. **Active Jobs** — gauge
5. **Jobs Created/Completed/Cancelled** — rate over time
6. **Job Creation Duration** — average latency
7. **Bids Placed/Accepted/Rejected** — rate over time
8. **Bid Placement Duration** — average latency
9. **Escrow Operations** — locked/released/failures rate
10. **Escrow Duration** — lock & release latency
11. **Auth Events** — login success/failure, registrations
12. **JVM Heap Memory** — used vs max per pool
13. **HikariCP Connections** — active/idle/max
14. **JVM Thread States** — by state

### Data Source

Prometheus is auto-provisioned as the default data source via `monitoring/grafana/provisioning/datasources/datasource.yml`.

---

## File Structure

```
monitoring/
├── prometheus.yml                         # Prometheus scrape config
└── grafana/
    ├── dashboards/
    │   └── aroundu-backend.json           # Pre-built Grafana dashboard
    └── provisioning/
        ├── dashboards/
        │   └── dashboard.yml              # Dashboard auto-load config
        └── datasources/
            └── datasource.yml             # Prometheus data source config
```

---

## Adding New Metrics

1. Add a new `Counter`, `Timer`, or `Gauge` field to `MetricsService.java`
2. Register it in the constructor with `Counter.builder(...)` / `Timer.builder(...)` / `registry.gauge(...)`
3. Call `metricsService.getYourCounter().increment()` from service code
4. Metrics auto-appear on `/actuator/prometheus` — no restart needed for Prometheus/Grafana to pick them up
5. Add a panel to the Grafana dashboard JSON if you want visualization
