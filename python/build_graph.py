"""
build_graph.py — Load UPI transactions from PostgreSQL and build a directed
graph with NetworkX.  No fraud detection yet; this just verifies the data
pipeline end-to-end.

Run:
    pip install -r requirements.txt
    python build_graph.py
"""

import psycopg2
import networkx as nx

# ── 1. Database connection ────────────────────────────────────────────────────
# These credentials match the docker-compose.yml service definition.
# psycopg2 opens a single synchronous connection to PostgreSQL.
DB_CONFIG = {
    "host": "localhost",
    "port": 5432,
    "dbname": "frauddb",
    "user": "fraud_user",
    "password": "fraud_pass",
}


def fetch_transactions(conn) -> list[tuple]:
    """
    Return every row from the transactions table as a list of
    (payer_vpa, payee_vpa, amount, saved_at) tuples.

    We use a server-side cursor (name="tx_cursor") so that PostgreSQL
    streams rows in batches rather than loading them all into memory at once.
    This matters when the table grows large.
    """
    with conn.cursor(name="tx_cursor") as cur:
        cur.execute(
            "SELECT payer_vpa, payee_vpa, amount, saved_at FROM transactions"
        )
        return cur.fetchall()


# ── 2. Graph construction ─────────────────────────────────────────────────────
def build_graph(rows: list[tuple]) -> nx.MultiDiGraph:
    """
    Build a directed multigraph from transaction rows.

    Why MultiDiGraph?
    - Directed (Di): money flows payer → payee, direction matters for fraud
      signals like fan-out (one sender, many receivers) or fan-in.
    - Multi: the same VPA pair can transact many times.  A MultiDiGraph keeps
      every transaction as a separate edge instead of collapsing them, so edge
      metadata (amount, timestamp) stays intact per transaction.

    Node = a VPA string  (e.g. "alice@upi")
    Edge = one transaction from payer_vpa to payee_vpa, with amount + saved_at
           stored as edge attributes.
    """
    G = nx.MultiDiGraph()

    for payer_vpa, payee_vpa, amount, saved_at in rows:
        # Skip rows where either VPA is missing — they can't form a valid edge.
        if not payer_vpa or not payee_vpa:
            continue

        # add_edge automatically creates the nodes if they don't exist yet.
        G.add_edge(
            payer_vpa,
            payee_vpa,
            amount=float(amount) if amount is not None else 0.0,
            saved_at=str(saved_at),
        )

    return G


# ── 3. Stats ──────────────────────────────────────────────────────────────────
def print_stats(G: nx.MultiDiGraph) -> None:
    """
    Print a human-readable summary of the graph.

    Degree in a directed graph:
    - in-degree  = number of edges arriving at a node  (money received)
    - out-degree = number of edges leaving a node       (money sent)
    - total degree = in + out

    The top-5 by total degree are the most "connected" VPAs — they appear
    most often as either payer or payee across all transactions.
    """
    print("=" * 50)
    print("Transaction Graph Summary")
    print("=" * 50)
    print(f"  Nodes (unique VPAs)  : {G.number_of_nodes()}")
    print(f"  Edges (transactions) : {G.number_of_edges()}")
    print()

    if G.number_of_nodes() == 0:
        print("  No data found — is the database populated?")
        return

    # Compute total degree (in + out) for every node.
    # G.degree() on a MultiDiGraph returns the sum of in- and out-degree,
    # counting each parallel edge separately.
    degree_sequence = sorted(G.degree(), key=lambda item: item[1], reverse=True)

    print("  Top 5 nodes by total degree:")
    print(f"  {'VPA':<35} {'in':>6} {'out':>6} {'total':>7}")
    print("  " + "-" * 57)
    for vpa, total_deg in degree_sequence[:5]:
        in_deg  = G.in_degree(vpa)
        out_deg = G.out_degree(vpa)
        print(f"  {vpa:<35} {in_deg:>6} {out_deg:>6} {total_deg:>7}")

    print("=" * 50)


# ── 4. Entry point ────────────────────────────────────────────────────────────
def main() -> None:
    print("Connecting to PostgreSQL...")
    conn = psycopg2.connect(**DB_CONFIG)

    try:
        print("Fetching transactions...")
        rows = fetch_transactions(conn)
        print(f"  Retrieved {len(rows)} rows.")

        print("Building graph...")
        G = build_graph(rows)

        print_stats(G)
    finally:
        conn.close()


if __name__ == "__main__":
    main()
