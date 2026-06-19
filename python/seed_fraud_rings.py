"""
seed_fraud_rings.py — Insert synthetic fraud-pattern transactions into the
transactions table so build_graph.py has something real to find.

Three patterns are planted:
  1. Cycle      — A → B → C → A  (money layering / circular flow)
  2. Fan-out    — one mule receives a lump sum, then sprays it to many drops
  3. Fan-in     — many small senders funnel into one collector

All planted VPAs start with "frd." so they are trivially identifiable and
can be cleaned up with:  DELETE FROM transactions WHERE payer_vpa LIKE 'frd.%'
                                                     OR payee_vpa  LIKE 'frd.%';

Run:
    python seed_fraud_rings.py            # insert for real
    python seed_fraud_rings.py --dry-run  # print rows, don't touch the DB
"""

import argparse
import sys
from datetime import datetime, timedelta, timezone

import psycopg2
import psycopg2.extras

DB_CONFIG = {
    "host": "localhost",
    "port": 5432,
    "dbname": "frauddb",
    "user": "fraud_user",
    "password": "fraud_pass",
}

# ── helpers ───────────────────────────────────────────────────────────────────

def ts(hours_ago: float, minutes_offset: int = 0) -> str:
    """Return an ISO-8601 timestamp string N hours before midnight today."""
    base = datetime.now(timezone.utc).replace(
        hour=2, minute=0, second=0, microsecond=0
    )
    t = base - timedelta(hours=hours_ago) + timedelta(minutes=minutes_offset)
    return t.strftime("%Y-%m-%dT%H:%M")


def row(
    ref: str,
    payer: str,
    payee: str,
    amount: float,
    timestamp: str,
    tx_type: str = "P2P",
    payer_bank: str = "ybl",
    payee_bank: str = "ybl",
    device_id: str = "frd-device-01",
    ip: str = "185.220.101.45",   # Tor exit node range — suspicious
    location: str = "Unknown",
    auth: str = "NONE",
    remarks: str = "",
) -> dict:
    """Build a dict whose keys match the transactions table column names."""
    return {
        "ref": ref,
        "channel": "UPI",
        "payer_vpa": payer,
        "payer_bank": payer_bank,
        "payee_vpa": payee,
        "payee_bank": payee_bank,
        "mcc": None,
        "tx_type": tx_type,
        "amount": amount,
        "currency": "INR",
        "auth": auth,
        "timestamp": timestamp,
        "location": location,
        "device_id": device_id,
        "ip": ip,
        "devtype": "mobile",
        "rooted": "true",
        "remarks": remarks,
        "screened_at": None,
        "risk_score": None,
        "risk_tier": None,
        "rules_json": None,
        "saved_at": datetime.now(timezone.utc),
    }


# ── Pattern 1: Cycle  A → B → C → A ─────────────────────────────────────────
#
# Real-world meaning — "layering" in money-laundering terms.
# Dirty money moves through a chain of accounts so the audit trail goes cold.
# Each hop typically takes a small cut (hence the decreasing amounts).
#
# Graph signature:
#   Every node in the ring has in_degree == 1 AND out_degree == 1.
#   The ring forms a Strongly Connected Component (SCC) — a subgraph where
#   you can reach any node from any other by following directed edges.
#   Clean commerce never forms SCCs: merchants don't pay their customers back.
#
A, B, C = "frd.cycle.a@ybl", "frd.cycle.b@ybl", "frd.cycle.c@ybl"

CYCLE_ROWS = [
    row("UPI/FR/CYCLE/001", A, B, 75_000.00, ts(3, 0),
        remarks="cycle-hop-1"),
    row("UPI/FR/CYCLE/002", B, C, 71_250.00, ts(3, 8),
        remarks="cycle-hop-2"),
    row("UPI/FR/CYCLE/003", C, A, 67_688.00, ts(3, 17),
        remarks="cycle-hop-3"),
]


# ── Pattern 2: Fan-out mule ───────────────────────────────────────────────────
#
# Real-world meaning — a "mule account" acts as a switching node.
# A large payment arrives (often from a scam victim or stolen account), then
# the mule immediately disperses the money to many drop accounts to make
# recovery and tracing harder.
#
# Graph signature:
#   MULE node:   in_degree = 1,  out_degree = 8  → ratio >> 1
#   DROP nodes:  in_degree = 1,  out_degree = 0  (pure receivers here)
#
# The high out-degree relative to in-degree is the tell.  Legitimate P2P
# accounts occasionally split a payment, but 8 outbound transfers within
# 25 minutes of a large inbound is a very strong signal.
#
SOURCE = "frd.mule.source@ybl"
MULE   = "frd.mule.hub@ybl"
DROPS  = [f"frd.drop.{i:02d}@ybl" for i in range(1, 9)]   # 8 drop accounts

FANOUT_ROWS = [
    # Large lump-sum arrives at the mule at 1 AM
    row("UPI/FR/MULE/000", SOURCE, MULE, 200_000.00, ts(1, 0),
        remarks="mule-inbound"),
    # Rapid dispersal over the next 25 minutes
    row("UPI/FR/MULE/001", MULE, DROPS[0],  23_500.00, ts(1, -5),  remarks="mule-out"),
    row("UPI/FR/MULE/002", MULE, DROPS[1],  24_000.00, ts(1, -8),  remarks="mule-out"),
    row("UPI/FR/MULE/003", MULE, DROPS[2],  23_200.00, ts(1, -11), remarks="mule-out"),
    row("UPI/FR/MULE/004", MULE, DROPS[3],  25_100.00, ts(1, -14), remarks="mule-out"),
    row("UPI/FR/MULE/005", MULE, DROPS[4],  22_800.00, ts(1, -17), remarks="mule-out"),
    row("UPI/FR/MULE/006", MULE, DROPS[5],  24_600.00, ts(1, -19), remarks="mule-out"),
    row("UPI/FR/MULE/007", MULE, DROPS[6],  23_900.00, ts(1, -22), remarks="mule-out"),
    row("UPI/FR/MULE/008", MULE, DROPS[7],  24_300.00, ts(1, -25), remarks="mule-out"),
]


# ── Pattern 3: Fan-in collector ───────────────────────────────────────────────
#
# Real-world meaning — aggregation stage of a scam network.
# Many low-value victims (or mule accounts) each send a small amount to the
# same destination.  Common in UPI scam operations where a phishing campaign
# tricks dozens of users into sending money to one QR code / collect request.
#
# Graph signature:
#   COLLECTOR node: in_degree = 8,  out_degree = 0  → pure sink
#   SENDER nodes:   in_degree = 0,  out_degree = 1  (pure sources here)
#
# A node with high in_degree and low out_degree is a "sink" — suspicious
# unless it is a known merchant.  8+ inbound sources with no outbound is
# a strong structural anomaly.
#
COLLECTOR = "frd.collector@ybl"
SENDERS   = [f"frd.sender.{i:02d}@ybl" for i in range(1, 9)]  # 8 senders

FANIN_ROWS = [
    row("UPI/FR/FANIN/001", SENDERS[0], COLLECTOR,  9_500.00, ts(4, 0),  remarks="fan-in"),
    row("UPI/FR/FANIN/002", SENDERS[1], COLLECTOR, 11_200.00, ts(4, 3),  remarks="fan-in"),
    row("UPI/FR/FANIN/003", SENDERS[2], COLLECTOR,  8_800.00, ts(4, 6),  remarks="fan-in"),
    row("UPI/FR/FANIN/004", SENDERS[3], COLLECTOR, 10_500.00, ts(4, 9),  remarks="fan-in"),
    row("UPI/FR/FANIN/005", SENDERS[4], COLLECTOR,  9_900.00, ts(4, 12), remarks="fan-in"),
    row("UPI/FR/FANIN/006", SENDERS[5], COLLECTOR, 12_000.00, ts(4, 15), remarks="fan-in"),
    row("UPI/FR/FANIN/007", SENDERS[6], COLLECTOR,  8_300.00, ts(4, 18), remarks="fan-in"),
    row("UPI/FR/FANIN/008", SENDERS[7], COLLECTOR, 10_100.00, ts(4, 21), remarks="fan-in"),
]

ALL_ROWS = CYCLE_ROWS + FANOUT_ROWS + FANIN_ROWS


# ── insert / dry-run ──────────────────────────────────────────────────────────

INSERT_SQL = """
INSERT INTO transactions (
    ref, channel, payer_vpa, payer_bank, payee_vpa, payee_bank,
    mcc, tx_type, amount, currency, auth, timestamp, location,
    device_id, ip, devtype, rooted, remarks, screened_at,
    risk_score, risk_tier, rules_json, saved_at
) VALUES (
    %(ref)s, %(channel)s, %(payer_vpa)s, %(payer_bank)s,
    %(payee_vpa)s, %(payee_bank)s,
    %(mcc)s, %(tx_type)s, %(amount)s, %(currency)s,
    %(auth)s, %(timestamp)s, %(location)s,
    %(device_id)s, %(ip)s, %(devtype)s, %(rooted)s,
    %(remarks)s, %(screened_at)s,
    %(risk_score)s, %(risk_tier)s, %(rules_json)s, %(saved_at)s
)
"""


def dry_run() -> None:
    print(f"\nDry run — {len(ALL_ROWS)} rows would be inserted:\n")
    header = f"  {'REF':<22} {'PAYER':<26} {'PAYEE':<26} {'AMOUNT':>10}"
    print(header)
    print("  " + "-" * 86)

    sections = [
        ("Cycle (A→B→C→A)", CYCLE_ROWS),
        ("Fan-out mule",     FANOUT_ROWS),
        ("Fan-in collector", FANIN_ROWS),
    ]
    for label, rows_ in sections:
        print(f"\n  [{label}]")
        for r in rows_:
            print(
                f"  {r['ref']:<22} {r['payer_vpa']:<26} "
                f"{r['payee_vpa']:<26} {r['amount']:>10,.2f}"
            )

    print("\n  Expected degree signatures after insert:")
    print(f"  {'Node':<30} {'in':>5} {'out':>5}  Pattern")
    print("  " + "-" * 55)
    sigs = [
        (A,         1, 1, "cycle — receives from C, sends to B"),
        (B,         1, 1, "cycle — receives from A, sends to C"),
        (C,         1, 1, "cycle — receives from B, sends to A"),
        (MULE,      1, 8, "mule  — 1 large inbound, 8 rapid outbound"),
        (COLLECTOR, 8, 0, "fan-in — 8 inbound, no outbound (pure sink)"),
    ]
    for node, ind, outd, note in sigs:
        print(f"  {node:<30} {ind:>5} {outd:>5}  {note}")
    print()


def insert_rows(rows_: list[dict]) -> None:
    already_planted = check_already_planted()
    if already_planted:
        print(
            f"WARNING: {already_planted} fraud-ring row(s) already exist in the "
            "database.\nRe-running will insert duplicates.  "
            "Clean up first if you don't want that:\n"
            "  DELETE FROM transactions WHERE payer_vpa LIKE 'frd.%' "
            "OR payee_vpa LIKE 'frd.%';\n"
        )

    print(f"Connecting to PostgreSQL...")
    conn = psycopg2.connect(**DB_CONFIG)
    try:
        with conn.cursor() as cur:
            # executemany sends all rows in one round-trip per batch.
            psycopg2.extras.execute_batch(cur, INSERT_SQL, rows_, page_size=50)
        conn.commit()
        print(f"Inserted {len(rows_)} synthetic fraud-ring rows.")
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()


def check_already_planted() -> int:
    """Return how many frd.* rows are already in the table (0 = safe to insert)."""
    try:
        conn = psycopg2.connect(**DB_CONFIG)
        with conn.cursor() as cur:
            cur.execute(
                "SELECT COUNT(*) FROM transactions "
                "WHERE payer_vpa LIKE 'frd.%%' OR payee_vpa LIKE 'frd.%%'"
            )
            count = cur.fetchone()[0]
        conn.close()
        return count
    except psycopg2.OperationalError:
        return 0   # DB unreachable — the insert call will surface the real error


# ── entry point ───────────────────────────────────────────────────────────────

def main() -> None:
    parser = argparse.ArgumentParser(description="Seed fraud-ring transactions.")
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Print what would be inserted without touching the database.",
    )
    args = parser.parse_args()

    if args.dry_run:
        dry_run()
    else:
        dry_run()   # always show the plan first
        insert_rows(ALL_ROWS)
        print("\nDone.  Run build_graph.py to see the planted nodes appear in the graph.")


if __name__ == "__main__":
    main()
