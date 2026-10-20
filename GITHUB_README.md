# HIS Report Query System

A lightweight hospital-internal reporting and data-management platform built on **Spring Boot 3.5 + JDK 21 + Oracle**,
with a data-visualization dashboard and unified role-based access control.

> Rewritten on top of the early public release (v1.0.0, Spring Boot 3.2.5): the original SQL reporting
> capability is preserved, and the rewrite adds a full role/permission model, per-staff data scoping,
> a cached visualization dashboard, operation auditing, and fully self-hosted front-end assets.
>
> 中文文档：[README.md](README.md)

## Features

- **SQL report system** — a report is a YAML header plus a SQL statement; supports parameterized queries, pagination, sorting, and Chinese column aliases
- **One-click SQL import** — paste a complete script straight from your database client; it strips semicolons and `COMMIT`,
  turns hard-coded dates and `[param]` / `:param` / `&var` placeholders into real parameters, and validates the
  result read-only before storing it
- **Visualization dashboard** — outpatient volume, inpatient, revenue, gross margin, average ticket, hourly footfall,
  revenue breakdown, admission/discharge trends, and more; the backend polls on a schedule and caches the results so
  the dashboard opens instantly without hitting the database; one-click switching by community, and per-staff enablement.
  **On a fetch failure it keeps the last real data and labels it explicitly as "not updated" — it never falls back to demo numbers**
- **Multi-datasource management** — manage database connections from the web UI and bind a different datasource per report
- **User management** — staff ID + name, auto-incrementing staff IDs, and a forced password change on first login
- **Roles and permissions** — built-in and custom roles, 14 permission points mapped to menus and actions;
  front-end elements declare `data-perm`, and the back end authorizes by permission point
- **Data scoping** — staff IDs are bound to the communities they may see; report dropdowns, queries, exports, and the
  dashboard are filtered by the system layer with **zero changes to report SQL**; restricted accounts are **fail-closed**:
  if a report's SQL does not actually bind the community parameter, the query is refused outright
- **Dictionary management** — community / department / personnel dictionaries; departments and personnel can be imported
  from an HIS system in one click (idempotent)
- **Cross-database queries** — join data across servers through Oracle DB Links
- **Excel export** — streaming export, handles 20000 rows of large data, with auto-fitted Chinese column widths
- **Security hardening** — SQL injection defence, CSRF, rate limiting (`/api/**` tiered, plus per-IP login throttling),
  output-side XSS escaping, CSP (local assets, **zero CDN**), privilege-escalation and self-promotion guards, and non-forgeable audit IPs
- **Operation audit** — graded operation records, each stamped with `user` / `clientIp` / `peerAddr`, exportable
- **Health checks** — `/actuator/health` is public; the Prometheus metrics endpoint requires a login (Grafana dashboard template included)

## Tech stack

| Component | Technology |
|------|------|
| Framework | Spring Boot 3.5.16 / JDK 21 |
| Security | Spring Security (CSRF + RBAC + CORS) |
| Scheduling | Spring `@Scheduled` (dashboard polling, single-threaded, serialized) |
| Database | Oracle JDBC (ojdbc11) + HikariCP (dynamic multi-datasource management) |
| Excel | Apache POI (SXSSFWorkbook, streaming) |
| Front end | Thymeleaf + vanilla JS + RemixIcon + ECharts 5 (**fully self-hosted, zero CDN**) |
| Monitoring | Micrometer + Prometheus |
| Build | Maven 3.9+ |

## Quick start

1. Install JDK 21+
2. Copy `.env.example` to `.env` and fill in the database connection and admin password
3. On Windows double-click `启动服务.bat`; on Linux/macOS run `java -jar target/sql-report-server-<version>.jar`
4. Open <http://localhost:8090>
5. Log in with `admin` and the password you set in `.env`

```bash
# build
mvn clean package -DskipTests

# run with environment variables (Linux/macOS)
export DB_URL='jdbc:oracle:thin:@<HOST>:<PORT>:<SID>'
export DB_USERNAME='<USER>'
export DB_PASSWORD='<PASSWORD>'
export ADMIN_PASSWORD='<at least 8 characters>'
java -jar target/sql-report-server-1.5.0-20261020.jar
```

## Project layout

```
sql-report-server-open-source/
├── src/
│   ├── main/java/com/report/          # Java sources
│   │   ├── config/                    # security / datasource / audit / metrics config
│   │   ├── controller/                # REST endpoints
│   │   ├── service/                   # business services
│   │   ├── model/                     # data models
│   │   ├── interceptor/               # rate-limit interceptor
│   │   └── util/                      # utilities
│   ├── main/resources/
│   │   ├── application.yml            # main configuration
│   │   ├── templates/                 # pages (login / main UI / dashboard)
│   │   └── static/                    # front-end assets (CSS/JS/icons, all self-hosted)
│   └── test/                          # unit and integration tests
├── scripts/                           # report SQL (examples)
│   └── screen/                        # dashboard metric SQL (fixed file names)
├── pom.xml                            # Maven build file
├── .env.example                       # environment variable template
├── 启动服务.bat                        # Windows launcher
├── 启动前自检.bat                      # pre-flight environment check
├── 清理.bat                            # clean up old builds / historical logs
├── 部署说明.md                         # deployment guide (Chinese)
└── LICENSE
```

`data/` (state and configuration) and `logs/` are created at runtime and are never committed.

## Adding a report

Create a `.sql` file under `scripts/` (see [scripts/README.md](scripts/README.md)):

```sql
---
name: Report name
description: Report description
params:
  - name: start_date
    label: Start date
    type: date
    required: true
  - name: community
    label: Community
    type: select
    required: false
    default: "%"
    source: "list:A,B,C,D,E,%"
---

SELECT * FROM your_table
WHERE time_col BETWEEN to_date(:start_date || ' 00:00:00', 'yyyy-mm-dd hh24:mi:ss')
                   AND to_date(:end_date   || ' 23:59:59', 'yyyy-mm-dd hh24:mi:ss')
  AND community_col LIKE :community
```

- Parameter types: `date` / `text` / `number` / `select`
- **Community filter convention**: the parameter must be named `community` and the SQL must actually bind it —
  data isolation for restricted accounts depends on this, and a report that does not bind it is refused outright
- Only `SELECT` (including `WITH ... SELECT`) is allowed; write statements are rejected

## Datasource configuration

Real hosts, ports, and accounts are never stored in this repository — everything goes through environment
variables (see `.env.example`):

| Variable | Description |
|------|------|
| `DB_URL` | `jdbc:oracle:thin:@<HOST>:<PORT>:<SID>` or `@//<HOST>:<PORT>/<SERVICE>` |
| `DB_USERNAME` / `DB_PASSWORD` | business database account; the password may be an `enc:`-prefixed AES-256-GCM ciphertext |
| `ADMIN_USERNAME` / `ADMIN_PASSWORD` | built-in super-admin account (startup fails if `ADMIN_PASSWORD` is unset) |
| `SERVER_PORT` | service port, `8090` by default |

You can also add and switch multiple datasources from the "Datasource Management" page after logging in, and bind
one per report.

## License

[MIT License](LICENSE)

## Developer

- YAO
