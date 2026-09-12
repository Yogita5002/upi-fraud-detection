# UPI Fraud Detection

A student portfolio project that screens UPI-style payment transactions for fraud risk. A Spring Boot service scores each transaction with a 10-rule engine, stores results in PostgreSQL, and serves a browser console. A separate Python/NetworkX service reads the same database and looks for structural fraud-ring patterns (cycles, fan-out, fan-in).

This is a learning / demo system, not a production fraud platform.

## Key features

- Interactive HTML/CSS/JavaScript console for screening a single transaction
- 10-rule fraud score (0–100) with a per-rule triggered/not-triggered breakdown
- LOW / MEDIUM / HIGH risk labels from the total score
- PostgreSQL persistence of screened transactions and a history API
- CSV import (batch screen via the same API) and CSV export from the console
- Graph-based ring detection over stored payer/payee VPAs (Python + NetworkX)
- Optional script to insert synthetic cycle / fan-out / fan-in examples

## Architecture

```
  Browser (index.html)
       |                                      |
       | POST /api/v1/screen                  | GET http://localhost:5001/findings
       | GET/DELETE /api/v1/transactions      |
       v                                      v
  Spring Boot :8080                      Flask :5001
  FraudEngineService                     build_graph + detect_rings
  (10 rules, in-memory velocity)         (NetworkX MultiDiGraph)
       |                                      |
       | JPA persist / load                   | SELECT payer_vpa, payee_vpa, amount, saved_at
       +----------------> PostgreSQL :5433 <--+
                          (Docker Compose)
```

The Java engine scores one transaction at request time. Velocity and “new device” state live in memory for the JVM process. The Python service does not rescore those rules; it builds a directed multigraph from rows already in PostgreSQL.

## Fraud detection rules

Implemented in `FraudEngineService`. Matching rules add their points; the total is capped at 100.

| Rule | When it fires | Score |
|---|---|---|
| Large value transaction | Amount ≥ ₹10,000 | 10 if ₹10,000–₹24,999; 15 if ₹25,000–₹49,999; 22 if ₹50,000–₹99,999; 30 if ≥ ₹1,00,000 |
| Off-hours transaction | Hour is 22:00–05:59 | 20 |
| Unverified counterparty | Payee VPA is not a known merchant **and** amount > ₹5,000 | 18 |
| Velocity breach | ≥ 3 transactions from the same payer VPA in a 5-minute window (window is relative to the current transaction timestamp) | `min(25, vel * 6)` |
| High-risk jurisdiction | Location is `Unknown` or `Cross-Border` | 14 |
| High-risk merchant category | MCC is `4829`, `6012`, or `7995` | 16 |
| Authentication bypass | `auth` is `NONE` | 20 |
| New device fingerprint | Device ID not seen before in this JVM **and** amount > ₹10,000 | 12 |
| Compromised device (rooted) | `rooted` is `Y` | 15 |
| Collect request pattern | `type` is `COLLECT` | 8 |

Known-merchant matching is a case-insensitive substring check against: AMAZON, FLIPKART, ZOMATO, SWIGGY, NETFLIX, UBER, OLA, IRCTC, MYNTRA, BIGBASKET, NYKAA, GPAY, PHONEPE.

## Risk classification

After summing (and capping at 100):

| Score | Tier |
|---|---|
| 0–29 | LOW |
| 30–59 | MEDIUM |
| 60–100 | HIGH |

## Graph-based fraud-ring detection

Python code in `python/` loads `payer_vpa`, `payee_vpa`, `amount`, and `saved_at` and builds a NetworkX `MultiDiGraph` (one node per VPA, one directed edge per transaction).

`detect_rings.py` then runs three rule-based detectors (no ML):

- **CYCLE** — directed cycles of length up to 10 on a collapsed `DiGraph` (`nx.simple_cycles`)
- **FAN_OUT** — node with out-degree ≥ 5 and out/in ratio ≥ 4.0 (mule-style spray)
- **FAN_IN** — node with in-degree ≥ 5 (collector-style inbound)

`python/app.py` exposes these findings over HTTP. `python/seed_fraud_rings.py` can insert synthetic `frd.*` VPAs that match those patterns. Detectors can also be run from the CLI (`python detect_rings.py`).

Flask and Flask-CORS are used by `app.py`. They are not listed in `python/requirements.txt` (that file currently has `psycopg2-binary` and `networkx==3.3` only), so install them before starting the graph API.

## Tech stack

- Java 17, Spring Boot 3.2.5, Spring Data JPA, Maven
- PostgreSQL 16 via Docker Compose
- Python, NetworkX, psycopg2, Flask
- HTML, CSS, vanilla JavaScript (static files served by Spring Boot)

## API endpoints

### Spring Boot (`http://localhost:8080`)

| Method | Path | Behaviour |
|---|---|---|
| POST | `/api/v1/screen` | Score one transaction, persist it, return `{ dbId, score, tier, rules, ts_display }`. Returns 400 if `payer_vpa` / `payee_vpa` is blank or `amount` is missing or ≤ 0. |
| GET | `/api/v1/transactions` | History, most recent first |
| DELETE | `/api/v1/transactions` | Delete all stored transactions |
| GET | `/api/v1/health` | `{ "status": "UP" }` |

The UI is served from the same origin (`src/main/resources/static/index.html`).

### Flask (`http://localhost:5001`)

| Method | Path | Behaviour |
|---|---|---|
| GET | `/health` | `{ "status": "ok" }` |
| GET | `/findings` | Builds the graph from PostgreSQL and returns `{ status, graph: { nodes, edges }, findings }` |

The console calls `http://localhost:5001/findings` directly.

## Running locally

**1. Database**

```bash
docker compose up -d
```

Compose starts PostgreSQL 16, publishes host port **5433**, and reads `DB_NAME`, `DB_USER`, `DB_PASSWORD`, and `DB_PORT` when set. Defaults match `.env.example`:

```
DB_HOST=localhost
DB_PORT=5433
DB_NAME=frauddb
DB_USER=fraud_user
DB_PASSWORD=fraud_pass
```

Copy those values into your shell if you want (do not commit a real `.env` with secrets). Python uses `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, and `DB_PASSWORD`. Spring Boot uses `DATABASE_URL`, `DB_USERNAME`, and `DB_PASSWORD`, with local defaults `jdbc:postgresql://localhost:5433/frauddb`, `fraud_user`, and `fraud_pass`.

**2. Java API and UI**

Requires JDK 17.

```bash
mvn spring-boot:run
```

Open http://localhost:8080

**3. Graph API (optional)**

```bash
cd python
pip install -r requirements.txt
pip install flask flask-cors   # imported by app.py; not in requirements.txt
python app.py                  # listens on 0.0.0.0:5001
```

Optional sample rings:

```bash
python seed_fraud_rings.py            # insert
python seed_fraud_rings.py --dry-run  # print only
```

## Testing

`FraudEngineService` has JUnit 5 unit tests in `src/test/java/com/frauddetection/service/FraudEngineServiceTest.java`. They cover individual rules, score summing, LOW/MEDIUM/HIGH cutoffs, the 100-point cap, and the 5-minute velocity window.

```bash
mvn -Dtest=FraudEngineServiceTest test
```

`mvn test` runs the same suite (it is currently the only test class).

## Example screen request

`POST /api/v1/screen` with a daytime, PIN-authenticated PAY to a known merchant (this combination triggers no rules in the current engine):

```json
{
  "payer_vpa": "user@okhdfc",
  "payee_vpa": "amazon@upi",
  "amount": 500,
  "auth": "PIN",
  "type": "PAY",
  "mcc": "5411",
  "location": "Mumbai",
  "device_id": "dev-001",
  "rooted": "N",
  "timestamp": "2026-05-25T14:00"
}
```

Successful responses also include `dbId` (the PostgreSQL id) and `ts_display` (save time formatted as `hh:mm:ss a` in `Asia/Kolkata`). For the request above, the engine returns score `0` and tier `LOW`, with every rule untriggered:

```json
{
  "score": 0,
  "tier": "LOW",
  "rules": [
    { "name": "Large value transaction", "triggered": false, "score": 0 },
    { "name": "Off-hours transaction", "triggered": false, "score": 0 },
    { "name": "Unverified counterparty", "triggered": false, "score": 0 },
    { "name": "Velocity breach", "triggered": false, "score": 0 },
    { "name": "High-risk jurisdiction", "triggered": false, "score": 0 },
    { "name": "High-risk merchant category", "triggered": false, "score": 0 },
    { "name": "Authentication bypass", "triggered": false, "score": 0 },
    { "name": "New device fingerprint", "triggered": false, "score": 0 },
    { "name": "Compromised device (rooted)", "triggered": false, "score": 0 },
    { "name": "Collect request pattern", "triggered": false, "score": 0 }
  ]
}
```

Each rule object also includes `detail` and `threshold` strings produced by the engine. The full `rules` array always contains these 10 entries.

## Limitations and future work

These are gaps, not current features:

- Velocity and new-device state are in-memory only (lost on restart, not shared across processes).
- Graph detectors are simple degree/cycle heuristics; high-traffic merchants can look like FAN_IN.
- The HTTP APIs have no authentication.
- Flask is not declared in `python/requirements.txt`.
- Python and Spring Boot use slightly different database environment variable names (`DB_USER` vs `DB_USERNAME`).
