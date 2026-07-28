# UPI Fraud Detection System

A full-stack transaction fraud screening system built for UPI payment risk analysis. Analysts enter transaction details and receive a real-time fraud risk score (0–100) with a rule-by-rule breakdown.

## Tech Stack

- **Backend**: Java 17, Spring Boot 3.2.5, Spring Data JPA
- **Database**: PostgreSQL 16 (Docker)
- **Frontend**: HTML, CSS, Vanilla JavaScript
- **Graph Analysis**: Python 3.12, NetworkX, psycopg2

## Features

- 10-rule fraud scoring engine (velocity, device, MCC, auth, location signals)
- Risk classification: LOW / MEDIUM / HIGH
- Transaction history stored in PostgreSQL
- Batch CSV import and export
- Graph-based fraud ring detection (cycles, fan-out mules, fan-in collectors)

## Setup

```bash
docker compose up -d
mvn spring-boot:run
```

Open `http://localhost:8080`