"""
INFERENCE SERVICE — inference_service.py
==========================================
FastAPI service that:
  1. Receives subgraph JSON from Spring Boot
  2. Preprocesses into PyG format
  3. Runs GNN model inference
  4. Applies risk scoring engine
  5. Returns complete fraud analysis result

ENDPOINTS:
  GET  /health              - service status
  POST /score               - score a transaction subgraph
  POST /report              - generate FIU-IND STR report via Gemini

HOW TO RUN:
  pip install fastapi uvicorn torch torch_geometric pydantic google-generativeai
  python inference_service.py

  OR:
  uvicorn inference_service:app --host 0.0.0.0 --port 8000 --reload
"""

import math
import time
import os
import json
import random
import traceback
from datetime import datetime
from typing import List, Optional, Dict, Any

import numpy as np
import torch
import torch.nn as nn
from torch_geometric.data import Data
from torch_geometric.nn import GINConv, global_mean_pool
from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from dotenv import load_dotenv

load_dotenv()



# response = client.models.generate_content(
#     model="gemini-1.5-flash",
#     contents="Hello"
# )

# ── CONFIG ────────────────────────────────────────────────────────────
MODEL_PATH             = "best_model1.pt"
INFERENCE_TEMPERATURE  = 3.0
REPORTING_THRESHOLD    = 1_000_000   # INR 10 lakh
GEMINI_API_KEY         = os.getenv("GEMINI_API_KEY", "")
GEMINI_MODEL           = os.getenv("GEMINI_MODEL", "gemini-1.5-flash")
FRAUD_SCORE_THRESHOLD  = 0.50        # above this = alert frontend

LABEL_NAMES = {
    0: "Legitimate",  1: "Fan-Out",    2: "Fan-In",
    3: "Round-Trip",  4: "Mutual",     5: "Structuring", 6: "Dormant"
}

PAYMENT_FORMATS = ["NEFT", "RTGS", "IMPS", "UPI", "Cheque"]

# ── PYDANTIC MODELS (request/response schemas) ────────────────────────
# These define exactly what JSON Spring Boot sends and receives.

class NodeData(BaseModel):
    """
    One account node from Neo4j.
    These are the stored properties we update on every transaction.
    """
    account_id:          str
    name:                Optional[str] = "Unknown"
    total_sent:          float = 0.0
    total_received:      float = 0.0
    tx_count_out:        int   = 0
    tx_count_in:         int   = 0
    unique_counterparts: int   = 0
    is_new_account:      bool  = False
    dormancy_score:      float = 0.0
    last_tx_timestamp:   Optional[str] = None
    first_tx_timestamp:  Optional[str] = None


class EdgeData(BaseModel):
    """
    One transaction edge from Neo4j.
    """
    edge_id:        str
    from_account:   str
    to_account:     str
    amount:         float
    currency:       str   = "INR"
    payment_format: str   = "NEFT"
    timestamp:      str
    is_trigger:     bool  = False   # True for the new transaction that triggered the query


class ScoreRequest(BaseModel):
    """
    What Spring Boot sends to /score.
    Contains the 2-hop neighborhood of the trigger transaction.
    """
    trigger_transaction_id: str
    nodes:                  List[NodeData]
    edges:                  List[EdgeData]


class EvidenceStep(BaseModel):
    step:           int
    from_account:   str
    from_name:      str
    to_account:     str
    to_name:        str
    amount:         float
    currency:       str
    timestamp:      str
    payment_format: str
    suspicion_score: float


class GraphNode(BaseModel):
    id:          str
    name:        str
    fraud_score: float
    is_suspect:  bool


class GraphEdge(BaseModel):
    source:         str
    target:         str
    amount:         float
    timestamp:      str
    payment_format: str
    suspicion:      float
    is_trigger:     bool


class ScoreResponse(BaseModel):
    """
    What /score returns to Spring Boot.
    Spring Boot stores this and sends to frontend.
    """
    trigger_transaction_id: str
    is_fraud:        bool
    typology:        str
    risk_level:      str
    fraud_score:     float
    raw_gnn_score:   float
    confidence:      float
    evidence_chain:  List[EvidenceStep]
    graph_data:      Dict[str, Any]   # nodes + edges for React frontend
    risk_breakdown:  Dict[str, Any]
    latency_ms:      float


class ReportRequest(BaseModel):
    """What /report receives — the score result + raw transaction data."""
    score_result:       Dict[str, Any]
    nodes:              List[NodeData]
    edges:              List[EdgeData]
    reporting_entity:   str = "Demo Bank Ltd"
    branch:             str = "Main Branch"


class ReportResponse(BaseModel):
    """FIU-IND STR format report."""
    report_id:              str
    generated_at:           str
    generation_mode:        str
    generation_note:        str
    reporting_entity:       str
    typology:               str
    risk_level:             str
    nature_of_suspicion:    str
    fund_trail_narrative:   str
    aggravating_factors:    List[str]
    recommended_action:     str
    total_amount_involved:  float
    accounts_involved:      List[str]
    transaction_count:      int
    time_span_description:  str
    evidence_chain:         List[Dict]


# ── GNN MODEL ─────────────────────────────────────────────────────────
class AML_GIN(nn.Module):
    """
    Identical to train_v2.py — must match exactly.
    Loaded once at startup, stays in memory.
    """
    def __init__(self, num_node_features, num_edge_features,
                 num_classes, hidden_dim=64, num_layers=3, dropout=0.3):
        super().__init__()
        self.num_layers = num_layers
        self.dropout    = nn.Dropout(dropout)
        input_dim       = num_node_features + num_edge_features

        self.convs = nn.ModuleList()
        self.bns   = nn.ModuleList()

        for i in range(num_layers):
            in_dim = input_dim if i == 0 else hidden_dim
            mlp = nn.Sequential(
                nn.Linear(in_dim, hidden_dim), nn.ReLU(),
                nn.Linear(hidden_dim, hidden_dim),
            )
            self.convs.append(GINConv(mlp))
            self.bns.append(nn.BatchNorm1d(hidden_dim))

        self.classifier = nn.Sequential(
            nn.Linear(hidden_dim, hidden_dim // 2), nn.ReLU(),
            nn.Dropout(dropout),
            nn.Linear(hidden_dim // 2, num_classes)
        )

    def forward(self, x, edge_index, edge_attr, batch):
        row, col = edge_index
        edge_agg = torch.zeros(x.size(0), edge_attr.size(1))
        edge_agg.scatter_add_(
            0, row.unsqueeze(1).expand_as(edge_attr), edge_attr
        )
        counts = torch.zeros(x.size(0), 1)
        counts.scatter_add_(
            0, row.unsqueeze(1), torch.ones(row.size(0), 1)
        )
        edge_agg = edge_agg / counts.clamp(min=1)
        h = torch.cat([x, edge_agg], dim=1)
        for i in range(self.num_layers):
            h = self.convs[i](h, edge_index)
            h = self.bns[i](h)
            h = torch.relu(h)
            h = self.dropout(h)
        h = global_mean_pool(h, torch.zeros(x.size(0), dtype=torch.long))
        return self.classifier(h)


# ── FEATURE ENGINEERING ───────────────────────────────────────────────
def make_node_feature_vector(node: NodeData) -> List[float]:
    """
    Converts NodeData (from Neo4j) → 7-dim feature vector.
    Normalization must match generate_dataset.py exactly.
    
    LEARNING:
    These are the same features stored as Neo4j node properties.
    We normalize here (not in Neo4j) to keep raw values in the DB
    so the report layer can show human-readable numbers.
    """
    return [
        min(node.total_sent          / 1_000_000, 1.0),
        min(node.total_received      / 1_000_000, 1.0),
        min(node.tx_count_out        / 100,       1.0),
        min(node.tx_count_in         / 100,       1.0),
        min(node.unique_counterparts / 50,        1.0),
        1.0 if node.is_new_account else 0.0,
        min(node.dormancy_score,                  1.0),
    ]


def make_edge_feature_vector(
    edge: EdgeData,
    time_delta_hours: float
) -> List[float]:
    """
    Converts EdgeData (from Neo4j) → 11-dim feature vector.
    
    time_delta_hours: hours since previous transaction in this subgraph.
    Computed by sorting edges by timestamp and taking differences.
    """
    amount_norm     = min(edge.amount / REPORTING_THRESHOLD, 3.0)

    try:
        ts   = datetime.fromisoformat(edge.timestamp)
        hour = ts.hour
    except Exception:
        hour = 12

    hour_sin        = math.sin(2 * math.pi * hour / 24)
    hour_cos        = math.cos(2 * math.pi * hour / 24)
    time_delta_norm = min(time_delta_hours / 72.0, 1.0)
    time_urgency    = 1.0 - time_delta_norm

    fmt_map = {"NEFT":0, "RTGS":1, "IMPS":2, "UPI":3, "Cheque":4}
    ohe = [0.0] * 7
    if edge.payment_format in fmt_map:
        ohe[fmt_map[edge.payment_format]] = 1.0

    return [amount_norm, hour_sin, hour_cos, time_urgency] + ohe


def compute_time_deltas(edges: List[EdgeData]) -> Dict[str, float]:
    """
    Computes time delta (hours) for each edge relative to previous edge.
    Edges are sorted by timestamp first.
    First edge always gets delta = 0.0.
    
    Returns: {edge_id: time_delta_hours}
    """
    sorted_edges = sorted(edges, key=lambda e: e.timestamp)
    deltas       = {}

    for i, edge in enumerate(sorted_edges):
        if i == 0:
            deltas[edge.edge_id] = 0.0
        else:
            try:
                t_curr = datetime.fromisoformat(edge.timestamp)
                t_prev = datetime.fromisoformat(sorted_edges[i-1].timestamp)
                delta  = abs((t_curr - t_prev).total_seconds() / 3600)
                deltas[edge.edge_id] = delta
            except Exception:
                deltas[edge.edge_id] = 1.0

    return deltas


def build_pyg_graph(
    nodes: List[NodeData],
    edges: List[EdgeData]
) -> Optional[Data]:
    """
    Converts Neo4j subgraph (nodes + edges) → PyG Data object.
    
    LEARNING — this is the inference-time preprocessing pipeline:
    1. Build account → integer index map
    2. Build node feature matrix [n_nodes, 7]
    3. Compute time deltas between edges
    4. Build edge feature matrix [n_edges, 11]
    5. Build edge_index [2, n_edges]
    6. Return PyG Data object
    
    This is the lightweight version of the training pipeline —
    no pattern detection, no subgraph extraction, just feature building.
    """
    if not nodes or not edges:
        return None

    # Account → local integer index
    node_map = {node.account_id: i for i, node in enumerate(nodes)}

    # Handle edges whose endpoints might not be in node list
    # (shouldn't happen if Neo4j query is correct but defensive)
    valid_edges = [
        e for e in edges
        if e.from_account in node_map and e.to_account in node_map
    ]
    if not valid_edges:
        return None

    # Node feature matrix
    x = torch.tensor(
        [make_node_feature_vector(n) for n in nodes],
        dtype=torch.float
    )

    # Time deltas
    time_deltas = compute_time_deltas(valid_edges)

    # Edge index and features
    src_list   = []
    dst_list   = []
    edge_feats = []

    for edge in valid_edges:
        src_list.append(node_map[edge.from_account])
        dst_list.append(node_map[edge.to_account])
        edge_feats.append(
            make_edge_feature_vector(edge, time_deltas[edge.edge_id])
        )

    edge_index = torch.tensor([src_list, dst_list], dtype=torch.long)
    edge_attr  = torch.tensor(edge_feats, dtype=torch.float)

    return Data(x=x, edge_index=edge_index, edge_attr=edge_attr)


# ── MODEL INFERENCE ───────────────────────────────────────────────────
@torch.no_grad()
def run_model_inference(
    model: AML_GIN,
    graph: Data,
    temperature: float = INFERENCE_TEMPERATURE
) -> Dict:
    """
    Runs model on preprocessed graph.
    Returns raw GNN output before risk scoring.
    
    Temperature scaling: divides logits by temperature before softmax.
    Higher temperature = softer, more realistic probabilities.
    Calibrated value: 3.0 (from validation sweep).
    """
    model.eval()
    batch         = torch.zeros(graph.x.size(0), dtype=torch.long)
    logits        = model(graph.x, graph.edge_index, graph.edge_attr, batch)
    scaled_logits = logits / temperature
    probs         = torch.softmax(scaled_logits, dim=1)[0]

    predicted_class = probs.argmax().item()
    confidence      = probs[predicted_class].item()
    legitimate_prob = probs[0].item()

    # Weighted fraud score: high when confident AND not legitimate
    fraud_score = (1.0 - legitimate_prob) * confidence

    return {
        "typology":      LABEL_NAMES[predicted_class],
        "label":         predicted_class,
        "is_fraud":      predicted_class != 0,
        "confidence":    round(confidence, 4),
        "fraud_score":   round(fraud_score, 4),
        "all_probs":     {
            LABEL_NAMES[i]: round(p.item(), 4)
            for i, p in enumerate(probs)
        }
    }


# ── RISK ENGINE ───────────────────────────────────────────────────────
def compute_final_risk(
    model_result: Dict,
    edges: List[EdgeData],
    nodes: List[NodeData]
) -> Dict:
    """
    Adjusts raw GNN fraud score using contextual factors.
    Works on EdgeData/NodeData directly (not raw dicts).
    """
    typology = model_result["typology"]
    base     = model_result["fraud_score"]

    if typology == "Legitimate":
        return {
            **model_result,
            "is_fraud":      False,
            "risk_level":    "LOW",
            "risk_breakdown": {"reason": "No fraud pattern detected"}
        }

    amounts      = [e.amount for e in edges]
    total_amount = sum(amounts)
    max_amount   = max(amounts)

    # Time span in hours
    try:
        timestamps  = sorted([
            datetime.fromisoformat(e.timestamp) for e in edges
        ])
        time_span   = (timestamps[-1] - timestamps[0]).total_seconds() / 3600
    except Exception:
        time_span   = 1.0

    # Hours of day
    try:
        hours = [datetime.fromisoformat(e.timestamp).hour for e in edges]
    except Exception:
        hours = [12]

    # 1. Amount factor (log scale)
    log_amount    = math.log10(max(total_amount, 1))
    amount_factor = max(0.5, min(1.4, 0.4 + (log_amount / 10.0)))

    # 2. Velocity factor
    if time_span == 0:
        velocity_factor = 1.45
    elif time_span <= 2:
        velocity_factor = 1.35
    elif time_span <= 6:
        velocity_factor = 1.20
    elif time_span <= 12:
        velocity_factor = 1.08
    elif time_span <= 24:
        velocity_factor = 0.95
    else:
        velocity_factor = 0.80

    # 3. Account age factor
    new_count          = sum(1 for n in nodes if n.is_new_account)
    new_ratio          = new_count / max(len(nodes), 1)
    account_age_factor = 1.0 + (new_ratio * 0.35)

    # 4. Night factor (11pm - 4am)
    night_count  = sum(1 for h in hours if h >= 23 or h <= 4)
    night_ratio  = night_count / max(len(hours), 1)
    night_factor = 1.0 + (night_ratio * 0.15)

    # 5. Typology factor
    typology_factor = {
        "Round-Trip":  1.10,
        "Fan-Out":     1.05,
        "Fan-In":      1.05,
        "Structuring": 1.15,
        "Mutual":      0.95,
        "Dormant":     1.10,
    }.get(typology, 1.0)

    # Structuring special rule
    if typology == "Structuring":
        near = sum(
            1 for a in amounts
            if REPORTING_THRESHOLD * 0.90 <= a < REPORTING_THRESHOLD
        )
        if near / max(len(amounts), 1) > 0.5:
            amount_factor = max(amount_factor, 1.20)

    # Combine
    raw   = (base * amount_factor * velocity_factor
             * account_age_factor * night_factor * typology_factor)
    final = max(0.25, min(0.97, raw))

    if final >= 0.75:
        risk_level = "HIGH"
    elif final >= 0.50:
        risk_level = "MEDIUM"
    elif final >= 0.30:
        risk_level = "LOW-MEDIUM"
    else:
        risk_level = "LOW"

    return {
        **model_result,
        "is_fraud":      True,
        "fraud_score":   round(final, 4),
        "raw_gnn_score": round(base, 4),
        "risk_level":    risk_level,
        "risk_breakdown": {
            "base_gnn_score":     round(base, 4),
            "amount_factor":      round(amount_factor, 3),
            "velocity_factor":    round(velocity_factor, 3),
            "account_age_factor": round(account_age_factor, 3),
            "night_factor":       round(night_factor, 3),
            "typology_factor":    round(typology_factor, 3),
            "total_amount_inr":   round(total_amount, 2),
            "time_span_hours":    round(time_span, 2),
        }
    }


# ── EVIDENCE CHAIN BUILDER ────────────────────────────────────────────
def build_evidence_chain(
    edges: List[EdgeData],
    nodes: List[NodeData],
    edge_suspicion: Dict[str, float]
) -> List[EvidenceStep]:
    """
    Builds chronological evidence chain for the report.
    Each step = one transaction in the fraud pattern.
    suspicion_score = how much this specific edge contributed to detection.
    """
    node_map = {n.account_id: n for n in nodes}
    sorted_edges = sorted(edges, key=lambda e: e.timestamp)

    chain = []
    for i, edge in enumerate(sorted_edges):
        from_node = node_map.get(edge.from_account)
        to_node   = node_map.get(edge.to_account)
        chain.append(EvidenceStep(
            step           = i + 1,
            from_account   = edge.from_account,
            from_name      = from_node.name if from_node else "Unknown",
            to_account     = edge.to_account,
            to_name        = to_node.name if to_node else "Unknown",
            amount         = edge.amount,
            currency       = edge.currency,
            timestamp      = edge.timestamp,
            payment_format = edge.payment_format,
            suspicion_score= edge_suspicion.get(edge.edge_id, 0.5)
        ))
    return chain


def build_graph_data(
    nodes: List[NodeData],
    edges: List[EdgeData],
    fraud_score: float,
    edge_suspicion: Dict[str, float]
) -> Dict:
    """
    Builds graph data for React frontend visualization.
    Frontend uses this to color nodes/edges by risk.
    """
    graph_nodes = [
        {
            "id":          n.account_id,
            "name":        n.name,
            "fraud_score": round(fraud_score * (1.2 if n.is_new_account
                                                 else 0.9), 4),
            "is_suspect":  True,
            "total_sent":  n.total_sent,
            "total_received": n.total_received,
            "is_new":      n.is_new_account,
            "dormancy":    n.dormancy_score,
        }
        for n in nodes
    ]

    graph_edges = [
        {
            "source":         e.from_account,
            "target":         e.to_account,
            "amount":         e.amount,
            "currency":       e.currency,
            "timestamp":      e.timestamp,
            "payment_format": e.payment_format,
            "suspicion":      edge_suspicion.get(e.edge_id, 0.5),
            "is_trigger":     e.is_trigger,
        }
        for e in edges
    ]

    return {"nodes": graph_nodes, "edges": graph_edges}


def has_valid_gemini_key() -> bool:
    key = GEMINI_API_KEY.strip().strip('"').strip("'")
    if not key:
        return False

    placeholder_tokens = [
        "your_api_key_here",
        "your_actual_key_here",
        "your_actual_api_key_here",
        "aizasyxxxx",
    ]
    return not any(token in key.lower() for token in placeholder_tokens)


def parse_llm_json(raw_text: str) -> Dict[str, Any]:
    cleaned = raw_text.strip()

    if cleaned.startswith("```"):
        parts = cleaned.split("```")
        if len(parts) >= 2:
            cleaned = parts[1].strip()
        if cleaned.lower().startswith("json"):
            cleaned = cleaned[4:].strip()

    try:
        return json.loads(cleaned)
    except json.JSONDecodeError:
        start = cleaned.find("{")
        end = cleaned.rfind("}")
        if start == -1 or end == -1 or end <= start:
            raise
        return json.loads(cleaned[start:end + 1])


def generate_gemini_narrative(prompt: str) -> Dict[str, Any]:
    """
    Generates the narrative using the Gemini SDK available in the environment.
    The project depends on google-generativeai, but this also supports the
    newer google-genai package if that is what the local environment has.
    """
    legacy_error = None

    try:
        import google.generativeai as genai

        genai.configure(api_key=GEMINI_API_KEY)
        model = genai.GenerativeModel(GEMINI_MODEL)
        response = model.generate_content(prompt)
        return parse_llm_json(response.text or "")
    except (ModuleNotFoundError, ImportError) as e:
        legacy_error = e

    try:
        from google import genai

        client = genai.Client(api_key=GEMINI_API_KEY)
        response = client.models.generate_content(
            model=GEMINI_MODEL,
            contents=prompt
        )
        return parse_llm_json(response.text or "")
    except (ModuleNotFoundError, ImportError):
        if legacy_error:
            raise legacy_error
        raise


# ── GEMINI REPORT GENERATOR ───────────────────────────────────────────
def generate_str_report(
    score_result:     Dict,
    nodes:            List[NodeData],
    edges:            List[EdgeData],
    reporting_entity: str,
    branch:           str
) -> ReportResponse:
    """
    Generates FIU-IND Suspicious Transaction Report using Gemini Flash.
    Falls back to rule-based report if Gemini unavailable.
    """
    total_amount  = sum(e.amount for e in edges)
    account_ids   = list(set(
        [e.from_account for e in edges] + [e.to_account for e in edges]
    ))
    sorted_edges  = sorted(edges, key=lambda e: e.timestamp)

    # Format evidence chain for LLM
    chain_text = "\n".join([
        f"  Step {i+1}: {e.from_account} → {e.to_account} | "
        f"₹{e.amount:,.0f} | {e.timestamp} | {e.payment_format}"
        for i, e in enumerate(sorted_edges)
    ])

    if not chain_text:
        chain_text = "  No transactions supplied"

    account_flags = []
    node_map = {n.account_id: n for n in nodes}
    for acc_id in account_ids:
        node = node_map.get(acc_id)
        if node:
            if node.is_new_account:
                account_flags.append(f"{acc_id}: new account")
            if node.dormancy_score > 0.5:
                account_flags.append(
                    f"{acc_id}: dormant "
                    f"({node.dormancy_score*365:.0f} days inactive)"
                )

    llm_data = None
    generation_mode = "rule_based"
    generation_note = "Gemini was unavailable, so a deterministic fallback narrative was used."

    # Try Gemini first
    if has_valid_gemini_key():
        try:
            prompt = f"""You are an AML investigator writing a Suspicious Transaction Report for FIU-IND submission.

DETECTED PATTERN: {score_result['typology']}
RISK LEVEL: {score_result['risk_level']}
RISK SCORE: {score_result['fraud_score']}
REPORTING ENTITY: {reporting_entity}, {branch}

TRANSACTION CHAIN (chronological):
{chain_text}

ACCOUNT FLAGS:
{chr(10).join(account_flags) if account_flags else "  No special flags"}

RISK FACTORS:
{json.dumps(score_result.get('risk_breakdown', {}), indent=2)}

Write a formal STR narrative suitable for FIU-IND submission under PMLA 2002.
Be factual. Reference specific amounts, timestamps, and account IDs.
Do not speculate beyond what the data shows.

Output ONLY valid JSON with these exact fields:
{{
  "nature_of_suspicion": "one paragraph formal statement",
  "fund_trail_narrative": "detailed paragraph tracing each transaction",
  "aggravating_factors": ["factor 1", "factor 2", "factor 3"],
  "recommended_action": "specific action recommendation"
}}
No markdown, no backticks, no explanation outside the JSON."""

            llm_data = generate_gemini_narrative(prompt)
            generation_mode = "gemini"
            generation_note = f"Generated with {GEMINI_MODEL}."

        except Exception as e:
            print(f"Gemini failed: {e}, using rule-based fallback")
            generation_note = f"Gemini failed ({e}); deterministic fallback narrative was used."
    else:
        generation_note = "GEMINI_API_KEY is missing or still set to a placeholder."

    # Rule-based fallback if Gemini unavailable
    if not llm_data:
        llm_data = generate_rule_based_narrative(
            score_result, sorted_edges, account_flags, total_amount
        )

    aggravating_factors = llm_data.get("aggravating_factors", [])
    if not isinstance(aggravating_factors, list):
        aggravating_factors = [str(aggravating_factors)]

    llm_data = {
        "nature_of_suspicion": str(
            llm_data.get("nature_of_suspicion") or "Suspicious activity detected."
        ),
        "fund_trail_narrative": str(
            llm_data.get("fund_trail_narrative") or "No fund trail narrative supplied."
        ),
        "aggravating_factors": [str(factor) for factor in aggravating_factors],
        "recommended_action": str(
            llm_data.get("recommended_action") or "Review the alert and supporting evidence."
        ),
    }

    if sorted_edges:
        try:
            t_start = datetime.fromisoformat(sorted_edges[0].timestamp)
            t_end   = datetime.fromisoformat(sorted_edges[-1].timestamp)
            span    = (t_end - t_start).total_seconds() / 3600
            if span < 1:
                time_desc = f"{int(span * 60)} minutes"
            elif span < 24:
                time_desc = f"{span:.1f} hours"
            else:
                time_desc = f"{span/24:.1f} days"
        except Exception:
            time_desc = "unknown duration"
    else:
        time_desc = "no transactions supplied"

    return ReportResponse(
        report_id             = f"STR-{datetime.now().strftime('%Y%m%d%H%M%S')}",
        generated_at          = datetime.now().isoformat(),
        generation_mode       = generation_mode,
        generation_note       = generation_note,
        reporting_entity      = f"{reporting_entity} - {branch}",
        typology              = score_result["typology"],
        risk_level            = score_result["risk_level"],
        nature_of_suspicion   = llm_data["nature_of_suspicion"],
        fund_trail_narrative  = llm_data["fund_trail_narrative"],
        aggravating_factors   = llm_data["aggravating_factors"],
        recommended_action    = llm_data["recommended_action"],
        total_amount_involved = round(total_amount, 2),
        accounts_involved     = account_ids,
        transaction_count     = len(edges),
        time_span_description = time_desc,
        evidence_chain        = [
            {
                "step":           i + 1,
                "from":           e.from_account,
                "to":             e.to_account,
                "amount":         e.amount,
                "timestamp":      e.timestamp,
                "payment_format": e.payment_format,
            }
            for i, e in enumerate(sorted_edges)
        ]
    )


def generate_rule_based_narrative(
    score_result: Dict,
    edges: List[EdgeData],
    account_flags: List[str],
    total_amount: float
) -> Dict:
    """Fallback when Gemini is unavailable."""
    typology = score_result["typology"]

    narratives = {
        "Round-Trip": (
            f"Circular movement of funds detected across "
            f"{len(edges)} transactions totalling ₹{total_amount:,.0f}. "
            f"Funds originated and returned to the same account, "
            f"consistent with layering activity under PMLA 2002 Section 3."
        ),
        "Fan-Out": (
            f"Layering activity detected. ₹{total_amount:,.0f} distributed "
            f"from single source across {len(edges)} accounts in rapid succession, "
            f"consistent with placement and layering of illicit funds."
        ),
        "Fan-In": (
            f"Fund consolidation detected. Multiple accounts transferred "
            f"₹{total_amount:,.0f} to single destination, "
            f"consistent with integration stage of money laundering."
        ),
        "Structuring": (
            f"Structuring (smurfing) detected. {len(edges)} transactions "
            f"averaging ₹{total_amount/max(len(edges),1):,.0f} each, "
            f"all deliberately below ₹{REPORTING_THRESHOLD:,} reporting threshold."
        ),
        "Dormant": (
            f"Dormant account reactivation detected. Account inactive for "
            f"extended period suddenly received ₹{total_amount:,.0f} "
            f"and immediately forwarded funds — consistent with pass-through laundering."
        ),
        "Mutual": (
            f"Suspicious bidirectional transfers detected. "
            f"₹{total_amount:,.0f} transferred back and forth between accounts "
            f"with minimal time gap — consistent with layering activity."
        ),
    }

    return {
        "nature_of_suspicion":  narratives.get(typology, f"{typology} pattern detected."),
        "fund_trail_narrative": " → ".join(
            [f"{e.from_account} (₹{e.amount:,.0f})" for e in edges[:3]]
        ) + ("..." if len(edges) > 3 else ""),
        "aggravating_factors":  account_flags or [
            f"Pattern completed in short time window",
            f"Total amount: ₹{total_amount:,.0f}",
            f"Risk score: {score_result['fraud_score']}"
        ],
        "recommended_action": (
            "File STR with FIU-IND immediately. "
            "Freeze accounts pending investigation. "
            "Request KYC documentation for all involved accounts."
        )
    }


# ── FASTAPI APP ───────────────────────────────────────────────────────
app = FastAPI(
    title       = "AML Inference Service",
    description = "GNN-based fraud detection for transaction monitoring",
    version     = "1.0.0"
)

app.add_middleware(
    CORSMiddleware,
    allow_origins     = ["*"],
    allow_credentials = True,
    allow_methods     = ["*"],
    allow_headers     = ["*"],
)

# ── STARTUP: load model once ──────────────────────────────────────────
model_instance    = None
model_checkpoint  = None

@app.on_event("startup")
async def load_model():
    global model_instance, model_checkpoint
    print(f"Loading model from {MODEL_PATH}...")
    try:
        checkpoint = torch.load(MODEL_PATH, weights_only=False)
        m = AML_GIN(
            num_node_features = checkpoint["num_node_features"],
            num_edge_features = checkpoint["num_edge_features"],
            num_classes       = checkpoint["num_classes"],
            hidden_dim        = checkpoint["hidden_dim"],
            num_layers        = checkpoint["num_layers"],
        )
        m.load_state_dict(checkpoint["model_state"])
        m.eval()
        model_instance   = m
        model_checkpoint = checkpoint
        print(f"Model loaded. Classes: {checkpoint['num_classes']}, "
              f"Node feats: {checkpoint['num_node_features']}, "
              f"Edge feats: {checkpoint['num_edge_features']}")
    except Exception as e:
        print(f"ERROR loading model: {e}")
        raise


# ── ENDPOINTS ─────────────────────────────────────────────────────────
@app.get("/health")
def health():
    return {
        "status":       "ok",
        "model_loaded": model_instance is not None,
        "temperature":  INFERENCE_TEMPERATURE,
        "classes":      LABEL_NAMES,
    }


@app.post("/score", response_model=ScoreResponse)
def score(request: ScoreRequest):
    """
    Main inference endpoint.
    Called by Spring Boot for every new transaction.
    
    Flow:
      1. Build PyG graph from nodes + edges
      2. Run GNN inference with temperature scaling
      3. Apply risk engine
      4. Build evidence chain
      5. Build graph data for frontend
      6. Return complete result
    """
    if model_instance is None:
        raise HTTPException(status_code=503, detail="Model not loaded")

    start = time.time()

    try:
        # Build graph
        graph = build_pyg_graph(request.nodes, request.edges)
        if graph is None:
            # No valid graph — return legitimate
            return ScoreResponse(
                trigger_transaction_id = request.trigger_transaction_id,
                is_fraud        = False,
                typology        = "Legitimate",
                risk_level      = "LOW",
                fraud_score     = 0.0,
                raw_gnn_score   = 0.0,
                confidence      = 1.0,
                evidence_chain  = [],
                graph_data      = {"nodes": [], "edges": []},
                risk_breakdown  = {"reason": "Insufficient graph data"},
                latency_ms      = round((time.time() - start) * 1000, 2)
            )

        # Model inference
        raw_result = run_model_inference(model_instance, graph)

        # Risk engine
        final_result = compute_final_risk(
            raw_result, request.edges, request.nodes
        )

        # Edge suspicion scores
        # Simple heuristic: trigger edge gets highest suspicion,
        # others get fraud_score * slight variation
        edge_suspicion = {}
        for edge in request.edges:
            base_susp = final_result["fraud_score"]
            if edge.is_trigger:
                edge_suspicion[edge.edge_id] = min(base_susp * 1.1, 0.99)
            else:
                edge_suspicion[edge.edge_id] = base_susp * random.uniform(0.85, 1.0)

        # Evidence chain
        evidence_chain = build_evidence_chain(
            request.edges, request.nodes, edge_suspicion
        )

        # Graph data for React
        graph_data = build_graph_data(
            request.nodes, request.edges,
            final_result["fraud_score"], edge_suspicion
        )

        latency = round((time.time() - start) * 1000, 2)

        return ScoreResponse(
            trigger_transaction_id = request.trigger_transaction_id,
            is_fraud        = final_result["is_fraud"],
            typology        = final_result["typology"],
            risk_level      = final_result["risk_level"],
            fraud_score     = final_result["fraud_score"],
            raw_gnn_score   = final_result.get("raw_gnn_score", 0.0),
            confidence      = final_result["confidence"],
            evidence_chain  = evidence_chain,
            graph_data      = graph_data,
            risk_breakdown  = final_result.get("risk_breakdown", {}),
            latency_ms      = latency
        )

    except Exception as e:
        traceback.print_exc()
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/report", response_model=ReportResponse)
def generate_report(request: ReportRequest):
    """
    Generates FIU-IND STR report for a detected fraud.
    Called on demand when the frontend requests a report for an alert.
    
    Uses Gemini Flash for narrative generation.
    Falls back to rule-based if Gemini unavailable.
    """
    try:
        return generate_str_report(
            score_result     = request.score_result,
            nodes            = request.nodes,
            edges            = request.edges,
            reporting_entity = request.reporting_entity,
            branch           = request.branch
        )
    except Exception as e:
        traceback.print_exc()
        raise HTTPException(status_code=500, detail=str(e))


# ── RUN ───────────────────────────────────────────────────────────────
if __name__ == "__main__":
    import uvicorn
    uvicorn.run(
        "inference_service:app",
        host     = "0.0.0.0",
        port     = 8000,
        reload   = False,
        workers  = 1
    )
