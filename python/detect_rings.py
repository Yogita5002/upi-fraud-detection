"""
detect_rings.py — Structural fraud-ring detection on the transaction graph.

Three rule-based detectors, no ML:
  detect_cycles   — directed cycles (A → B → C → A), the layering pattern
  detect_fan_out  — mule nodes that receive little but spray to many drops
  detect_fan_in   — collector nodes that funnel from many diverse sources

Run:
    python detect_rings.py
"""

import sys
from dataclasses import dataclass
from pathlib import Path

import networkx as nx
import psycopg2

# build_graph.py lives in the same directory; make it importable regardless
# of where the caller's working directory is.
sys.path.insert(0, str(Path(__file__).parent))
from build_graph import DB_CONFIG, build_graph, fetch_transactions


# ── Result type ───────────────────────────────────────────────────────────────

@dataclass
class Finding:
    pattern: str        # "CYCLE" | "FAN_OUT" | "FAN_IN"
    accounts: list      # VPAs involved; first entry is the "focus" node
    metrics: dict       # numeric evidence (degrees, ratio, cycle length …)
    reason: str         # plain-English explanation, safe to quote in a report


# ── Detector 1: Directed cycles ───────────────────────────────────────────────
#
# What it catches: money-laundering "layering" — A pays B, B pays C, C pays A.
# The money never leaves the ring; it just creates the appearance of legitimate
# commerce while the audit trail goes cold.
#
# NetworkX function: nx.simple_cycles(G, length_bound=N)
#
#   Johnson's algorithm (1975).  It performs a modified DFS that tracks which
#   nodes are "blocked" to avoid revisiting them, ensuring each elementary
#   circuit is reported exactly once.  Complexity: O((n + e)(c + 1)) where c
#   is the number of cycles found.  Without length_bound the algorithm must
#   enumerate every cycle, which can be exponential on dense graphs — a
#   realistic transaction graph can have millions of 2-cycles (A↔B) from
#   recurring bill payments.  length_bound=10 prunes search branches early
#   once the current path exceeds 10 nodes, which keeps runtime tractable
#   while catching all practically relevant fraud rings (real layering rings
#   rarely exceed 5-6 hops).
#
# Why collapse to DiGraph first?
#   Our source graph is a MultiDiGraph: two VPAs that traded 3 times have
#   3 parallel edges.  simple_cycles on a MultiDiGraph treats each parallel
#   edge as a distinct path and emits the same node-sequence cycle multiple
#   times.  Collapsing to DiGraph (one edge per ordered pair) de-duplicates:
#   we care that a cycle *exists*, not how many transactions built each hop.
#   nx.DiGraph(multi_graph) does the collapse in one call; parallel-edge
#   attributes are dropped, which is fine — topology is all we need here.

MAX_CYCLE_LEN = 10


def detect_cycles(G: nx.MultiDiGraph) -> list[Finding]:
    simple_G = nx.DiGraph(G)   # collapse parallel edges before cycle search

    findings = []
    for cycle in nx.simple_cycles(simple_G, length_bound=MAX_CYCLE_LEN):
        # cycle = [A, B, C]; the closing edge C → A is implied, not included.
        path_str = " → ".join(cycle) + f" → {cycle[0]}"
        findings.append(Finding(
            pattern="CYCLE",
            accounts=cycle,
            metrics={"length": len(cycle)},
            reason=(
                f"Directed cycle of length {len(cycle)}: {path_str}. "
                "Money completes a closed loop — a textbook layering signal. "
                "Legitimate UPI payments are acyclic: a merchant does not pay "
                "its customers back through the same chain."
            ),
        ))
    return findings


# ── Detector 2: Fan-out mule ──────────────────────────────────────────────────
#
# What it catches: a mule account that receives one (or few) large transfers
# then rapidly disperses the funds to many drop accounts — "smurfing".
# Each drop receives a sub-threshold amount, making individual transactions
# look unremarkable while the aggregate is significant.
#
# Signal: high out-degree AND high out/in ratio.
#
# Threshold choices (both conditions must be true):
#
#   FAN_OUT_MIN_OUT = 5
#     A person splitting a restaurant bill pays 2–4 people at most.
#     Salary disbursements legitimately exceed 5, but those accounts also
#     receive from many employees (high in-degree), so the RATIO catches them
#     — a payroll account paying 20 employees but receiving 200 salary credits
#     has ratio 0.1 (not flagged).  Five is the inflection point between
#     "occasional split" and "systematic dispersal" in our UPI context.
#
#   FAN_OUT_MIN_RATIO = 4.0
#     For every source that paid this node, it pays 4+ destinations.
#     The planted mule has ratio 8/1 = 8.0 — well above threshold.
#     A known merchant hub with out=8, in=200 has ratio 0.04 (not flagged).
#     Four was chosen so a simple 1-to-4 bill split (in=1, out=4 → ratio 4.0)
#     sits exactly on the boundary: it should be reviewed but could be benign,
#     which is the right posture for analyst triage.
#
# On MultiDiGraph, out_degree counts parallel edges, so three rapid transfers
# to the same drop account contribute 3 to out_degree.  That is intentional:
# repeated transfers to the same destination compound the signal.

FAN_OUT_MIN_OUT   = 5
FAN_OUT_MIN_RATIO = 4.0


def detect_fan_out(G: nx.MultiDiGraph) -> list[Finding]:
    findings = []
    for node in G.nodes():
        out_deg = G.out_degree(node)
        in_deg  = G.in_degree(node)

        if out_deg < FAN_OUT_MIN_OUT:
            continue

        # Guard: avoid division by zero when in_deg == 0 (pure source node).
        # Treating it as 1 means a node with no inbound and 5+ outbound has
        # ratio = out_deg — highly suspicious and correctly flagged.
        ratio = out_deg / max(in_deg, 1)

        if ratio < FAN_OUT_MIN_RATIO:
            continue

        # Collect the distinct destination VPAs (set() removes duplicates from
        # parallel edges; the report mentions edge count separately).
        destinations = sorted({v for _, v in G.out_edges(node)})

        findings.append(Finding(
            pattern="FAN_OUT",
            accounts=[node] + destinations,
            metrics={
                "out_degree": out_deg,
                "in_degree":  in_deg,
                "ratio":      round(ratio, 2),
            },
            reason=(
                f"{node} sent to {out_deg} edge(s) across "
                f"{len(destinations)} destination(s), "
                f"but received from only {in_deg} source(s) "
                f"(out/in ratio {ratio:.1f}x). "
                f"Thresholds: out ≥ {FAN_OUT_MIN_OUT}, ratio ≥ {FAN_OUT_MIN_RATIO}. "
                "Consistent with a mule account converting one large inbound "
                "transfer into many smaller, harder-to-trace outbound payments."
            ),
        ))
    return findings


# ── Detector 3: Fan-in collector ──────────────────────────────────────────────
#
# What it catches: an aggregation node that receives from many diverse sources
# — the collection point for a phishing campaign, QR-code scam, or the final
# stage in a layering chain.
#
# Signal: high in-degree.
#
# Threshold choice:
#
#   FAN_IN_MIN_IN = 5
#     Mirrors the fan-out threshold for symmetry and the same reasoning: five
#     distinct payers in a P2P context is unusual.  Known merchants will also
#     exceed this — this detector surfaces candidates for analyst review, not
#     automatic blocks.  In production, a merchant-VPA whitelist would suppress
#     known legitimate high-in-degree nodes before this rule fires.
#
# Why not also require low out-degree as part of the rule?
#   Keeping the condition to one dimension (in_degree alone) makes the rule
#   fully transparent: "if more than N accounts paid this node, investigate."
#   The report exposes out_degree in metrics so analysts can immediately see
#   whether it is a pure sink (out=0, very suspicious) or a node with outbound
#   flows (out>0, possibly a known hub).  Baking the out_degree condition into
#   the rule would make it a two-parameter rule that is harder to explain to
#   compliance and harder to tune independently.

FAN_IN_MIN_IN = 5


def detect_fan_in(G: nx.MultiDiGraph) -> list[Finding]:
    findings = []
    for node in G.nodes():
        in_deg  = G.in_degree(node)
        out_deg = G.out_degree(node)

        if in_deg < FAN_IN_MIN_IN:
            continue

        sources = sorted({u for u, _ in G.in_edges(node)})

        findings.append(Finding(
            pattern="FAN_IN",
            accounts=[node] + sources,
            metrics={
                "in_degree":  in_deg,
                "out_degree": out_deg,
            },
            reason=(
                f"{node} received from {in_deg} edge(s) across "
                f"{len(sources)} source(s), "
                f"and sent to {out_deg} destination(s). "
                f"Threshold: in ≥ {FAN_IN_MIN_IN}. "
                "High inbound from diverse sources with low or zero outbound "
                "is consistent with a phishing collection account or the "
                "aggregation stage of a scam network."
            ),
        ))
    return findings


# ── Orchestration ─────────────────────────────────────────────────────────────

def run_all(G: nx.MultiDiGraph) -> list[Finding]:
    return detect_cycles(G) + detect_fan_out(G) + detect_fan_in(G)


# ── Report printer ────────────────────────────────────────────────────────────

def print_report(findings: list[Finding]) -> None:
    bar = "=" * 62
    print(f"\n{bar}")
    print("  Fraud Ring Detection Report")
    print(bar)

    if not findings:
        print("  No suspicious structures detected.")
        print(bar)
        return

    print(f"  {len(findings)} finding(s)\n")

    for i, f in enumerate(findings, 1):
        print(f"[{i}]  Pattern : {f.pattern}")

        if f.pattern == "CYCLE":
            cycle = f.accounts
            print(f"     Path    : {' → '.join(cycle)} → {cycle[0]}")
            print(f"     Length  : {f.metrics['length']} hop(s)")

        elif f.pattern == "FAN_OUT":
            hub, *drops = f.accounts
            print(f"     Hub     : {hub}")
            print(f"     Drops   : {', '.join(drops)}")
            print(
                f"     Degrees : in={f.metrics['in_degree']}  "
                f"out={f.metrics['out_degree']}  "
                f"ratio={f.metrics['ratio']}x"
            )

        elif f.pattern == "FAN_IN":
            collector, *sources = f.accounts
            print(f"     Sink    : {collector}")
            print(f"     Sources : {', '.join(sources)}")
            print(
                f"     Degrees : in={f.metrics['in_degree']}  "
                f"out={f.metrics['out_degree']}"
            )

        print(f"     Reason  : {f.reason}")
        print()

    print(bar)


# ── Entry point ───────────────────────────────────────────────────────────────

def main() -> None:
    print("Connecting to PostgreSQL...")
    conn = psycopg2.connect(**DB_CONFIG)
    try:
        print("Fetching transactions...")
        rows = fetch_transactions(conn)
        print(f"  {len(rows)} row(s) loaded.")
    finally:
        conn.close()

    print("Building graph...")
    G = build_graph(rows)
    print(f"  {G.number_of_nodes()} node(s), {G.number_of_edges()} edge(s).")

    print("Running detectors...\n")
    findings = run_all(G)
    print_report(findings)


if __name__ == "__main__":
    main()
