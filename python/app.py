from dataclasses import asdict
import os

import psycopg2
from flask import Flask, jsonify
from flask_cors import CORS

from build_graph import build_graph, fetch_transactions
from detect_rings import run_all

DB_CONFIG = {
    "host": os.getenv("DB_HOST", "localhost"),
    "port": int(os.getenv("DB_PORT", "5433")),
    "dbname": os.getenv("DB_NAME", "frauddb"),
    "user": os.getenv("DB_USER", "fraud_user"),
    "password": os.getenv("DB_PASSWORD", "fraud_pass"),
}

app = Flask(__name__)
CORS(app)


@app.route("/health", methods=["GET"])
def health():
    return jsonify({"status": "ok"})


@app.route("/findings", methods=["GET"])
def get_findings():
    try:
        conn = psycopg2.connect(**DB_CONFIG)
        try:
            rows = fetch_transactions(conn)
        finally:
            conn.close()

        G = build_graph(rows)
        findings = run_all(G)

        return jsonify({
            "status": "ok",
            "graph": {
                "nodes": G.number_of_nodes(),
                "edges": G.number_of_edges(),
            },
            "findings": [asdict(f) for f in findings],
        })

    except Exception as e:
        return jsonify({"status": "error", "message": str(e)}), 500


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5001, debug=True)
