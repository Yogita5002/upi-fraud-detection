# UPI Fraud Detection System

A full-stack transaction screening tool that scores UPI payments for fraud risk in real time. An analyst enters transaction details (or imports a batch via CSV), and the backend evaluates each transaction against 10 fraud signals, assigns a risk score from 0–100, classifies it as Low / Medium / High risk, stores it, and displays the result on a live dashboard with searchable history.

Built for a fintech hackathon problem statement on real-time UPI fraud screening.

## Features

- Manual transaction entry with a detailed input form
- Batch CSV import — each row is screened through the backend
- Rule-based risk scoring (0–100) with a full rule-by-rule breakdown
- Risk classification: Low / Medium / High, color-coded
- KPI dashboard: total screened, risk distribution, alert rate
- Persistent screening history, read directly from the database
- CSV export of screened results

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Frontend | HTML, CSS, JavaScript |
| Backend | Java 17, Spring Boot |
| Data access | Spring Data JPA |
| Database | H2 (in-memory) |
| Build | Maven |
| Boilerplate reduction | Lombok |

## Architecture

The backend follows a standard three-layer structure:

```
Browser (HTML/JS)
      │  fetch() — JSON over HTTP
      ▼
Controller   →  receives requests, routes them
      ▼
Service      →  runs the fraud engine, orchestrates persistence
      ▼
Repository   →  reads/writes the database via JPA
      ▼
H2 Database  →  stores screened transactions
```

The frontend is served by Spring Boot itself (from `src/main/resources/static/`), so the whole app runs from a single origin (`localhost:8080`) with no cross-origin issues.

## The Fraud Rules

Each rule checks one signal and adds points to the risk score when triggered:

| Rule | Triggers when | Points |
|------|---------------|--------|
| Large value | Amount ≥ ₹10,000 | 10–30 |
| Off-hours | Transaction between 22:00–06:00 | 20 |
| Unverified counterparty | Unknown payee + amount > ₹5,000 | 18 |
| Velocity breach | Same VPA sends >3 transactions in 5 min | up to 25 |
| High-risk jurisdiction | Location Unknown or Cross-Border | 14 |
| High-risk MCC | Merchant category 4829 / 6012 / 7995 | 16 |
| Authentication bypass | No authentication used | 20 |
| New device | Unseen device + amount > ₹10,000 | 12 |
| Compromised device | Device flagged as rooted | 15 |
| Collect request | Pull-based payment type | 8 |

Score is capped at 100. Classification: **LOW** (< 30), **MEDIUM** (30–59), **HIGH** (≥ 60).

## API Endpoints

| Method | Endpoint | Purpose |
|--------|----------|---------|
| POST | `/api/v1/screen` | Score one transaction, persist it, return the result |
| GET | `/api/v1/transactions` | Fetch all stored transactions (history) |
| DELETE | `/api/v1/transactions` | Clear all stored history |
| GET | `/api/v1/health` | Health check |

## Getting Started

### Prerequisites

- Java 17
- Maven

### Run it

```bash
# from the project root
mvn spring-boot:run
```

Wait for `Started FraudDetectionApplication`, then open:

```
http://localhost:8080
```

To try it with sample data, use the **Batch Import** tab and upload `test_transactions.csv` (included in the repo) — it contains 20 transactions spanning all three risk tiers.

## Project Structure

```
fraud-detection/
├── pom.xml
├── test_transactions.csv
└── src/main/
    ├── java/com/frauddetection/
    │   ├── FraudDetectionApplication.java
    │   ├── config/CorsConfig.java
    │   ├── controller/FraudController.java
    │   ├── model/            (Transaction, FraudResult, FraudRule, etc.)
    │   ├── repository/TransactionRepository.java
    │   └── service/
    │       ├── FraudEngineService.java
    │       └── TransactionService.java
    └── resources/
        ├── application.properties
        └── static/index.html
```

## Notes & Limitations

- Scoring is rule-based — no machine learning model (yet).
- The database is H2 in-memory, so data resets when the server restarts.
- Single-user; no authentication.

## Roadmap

- [ ] Migrate from H2 to PostgreSQL (running in Docker) for persistent storage
- [ ] Add database indexes on frequently-queried columns (VPA, timestamp)
- [ ] Add a machine learning scoring layer alongside the rules
- [ ] Add dashboard charts (risk distribution, top triggered rules)
- [ ] Deploy to a live URL
