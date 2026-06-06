import { useEffect, useRef, useState } from "react";
import { Client } from "@stomp/stompjs";
import SockJS from "sockjs-client";

const API_BASE_URL = "http://localhost:8080/api";
const WS_URL = "http://localhost:8080/ws";
const PAGE_LIMIT = 10;
const DEFAULT_REPORT_MESSAGE =
  "Generate a report from the selected alert to preview the backend report flow.";
const DEFAULT_REPORT_OUTPUT = {
  kind: "idle",
  message: DEFAULT_REPORT_MESSAGE,
};
const DETAIL_EMPTY_MESSAGE =
  "Select an alert to inspect graph details, evidence, and reporting actions.";

function formatCurrency(amount) {
  return new Intl.NumberFormat("en-IN", {
    style: "currency",
    currency: "INR",
    maximumFractionDigits: 0,
  }).format(Number(amount || 0));
}

function formatTimestamp(timestamp) {
  if (!timestamp) return "Unknown time";

  const date = new Date(timestamp);
  if (Number.isNaN(date.getTime())) return timestamp;

  return date.toLocaleString("en-IN", {
    dateStyle: "medium",
    timeStyle: "short",
  });
}

function getRiskClass(riskLevel = "") {
  const upper = riskLevel.toUpperCase();
  if (upper.includes("HIGH")) return "chip-high";
  if (upper.includes("MEDIUM")) return "chip-medium";
  return "chip-low";
}

function formatCompactCurrency(amount) {
  return new Intl.NumberFormat("en-IN", {
    style: "currency",
    currency: "INR",
    notation: "compact",
    maximumFractionDigits: 1,
  }).format(Number(amount || 0));
}

function truncateMiddle(value, maxLength = 18) {
  const text = String(value || "Unknown");
  if (text.length <= maxLength) return text;

  const edgeLength = Math.floor((maxLength - 3) / 2);
  return `${text.slice(0, edgeLength)}...${text.slice(-edgeLength)}`;
}

function getRiskTone(score = 0) {
  const normalizedScore = Number(score || 0);
  if (normalizedScore >= 0.75) return "risk-high";
  if (normalizedScore >= 0.45) return "risk-medium";
  return "risk-low";
}

function normalizeGraphNode(node, defaultScore = 0) {
  const id =
    node?.id ||
    node?.account_id ||
    node?.accountId ||
    node?.account ||
    node?.name ||
    "unknown";

  return {
    id: String(id),
    name: node?.name || node?.account_name || node?.accountName || String(id),
    fraud_score: Number(node?.fraud_score ?? node?.fraudScore ?? defaultScore),
    is_new: Boolean(
      node?.is_new ?? node?.isNew ?? node?.is_new_account ?? node?.isNewAccount
    ),
    total_sent: Number(node?.total_sent ?? node?.totalSent ?? 0),
    total_received: Number(node?.total_received ?? node?.totalReceived ?? 0),
  };
}

function normalizeGraphEdge(edge, index, defaultSuspicion = 0) {
  const source =
    edge?.source || edge?.from_account || edge?.fromAccount || edge?.from;
  const target = edge?.target || edge?.to_account || edge?.toAccount || edge?.to;

  return {
    id: edge?.edge_id || edge?.edgeId || `edge-${index + 1}`,
    source: source ? String(source) : "",
    target: target ? String(target) : "",
    amount: Number(edge?.amount || 0),
    currency: edge?.currency || "INR",
    payment_format: edge?.payment_format || edge?.paymentFormat || "NEFT",
    timestamp: edge?.timestamp || null,
    suspicion: Number(
      edge?.suspicion ??
        edge?.suspicion_score ??
        edge?.suspicionScore ??
        defaultSuspicion
    ),
    is_trigger: Boolean(edge?.is_trigger ?? edge?.isTrigger),
  };
}

function buildGraphModel(alert) {
  const graphData = alert?.graph_data || alert?.graphData || {};
  const graphNodes = Array.isArray(graphData.nodes) ? graphData.nodes : [];
  const graphEdges = Array.isArray(graphData.edges) ? graphData.edges : [];
  const fallbackNodes = Array.isArray(alert?.nodes) ? alert.nodes : [];
  const fallbackEdges = Array.isArray(alert?.edges) ? alert.edges : [];
  const alertFraudScore = Number(alert?.fraud_score ?? alert?.fraudScore ?? 0);

  const rawNodes = graphNodes.length > 0 ? graphNodes : fallbackNodes;
  const rawEdges = graphEdges.length > 0 ? graphEdges : fallbackEdges;
  const nodeMap = new Map();

  rawNodes
    .map((node) => normalizeGraphNode(node, alertFraudScore))
    .filter((node) => node.id)
    .forEach((node) => nodeMap.set(node.id, node));

  const edges = rawEdges
    .map((edge, index) => normalizeGraphEdge(edge, index, alertFraudScore))
    .filter((edge) => edge.source && edge.target);

  edges.forEach((edge) => {
    [edge.source, edge.target].forEach((accountId) => {
      if (!nodeMap.has(accountId)) {
        nodeMap.set(accountId, {
          id: accountId,
          name: accountId,
          fraud_score: edge.suspicion,
          is_new: false,
          total_sent: 0,
          total_received: 0,
        });
      }
    });
  });

  return {
    nodes: Array.from(nodeMap.values()),
    edges,
  };
}

function positionGraphNodes(nodes) {
  if (nodes.length === 0) return [];
  if (nodes.length === 1) return [{ ...nodes[0], x: 50, y: 50 }];

  const radiusX = nodes.length <= 3 ? 31 : 38;
  const radiusY = nodes.length <= 3 ? 26 : 31;

  return nodes.map((node, index) => {
    const angle = (index / nodes.length) * Math.PI * 2 - Math.PI / 2;
    return {
      ...node,
      x: Number((50 + radiusX * Math.cos(angle)).toFixed(2)),
      y: Number((50 + radiusY * Math.sin(angle)).toFixed(2)),
    };
  });
}

function buildEdgePath(fromNode, toNode, edgeIndex) {
  if (fromNode.id === toNode.id) {
    return {
      path: `M ${fromNode.x - 4} ${fromNode.y} C ${fromNode.x - 16} ${
        fromNode.y - 16
      }, ${fromNode.x + 16} ${fromNode.y - 16}, ${fromNode.x + 4} ${
        fromNode.y
      }`,
      labelX: fromNode.x,
      labelY: fromNode.y - 16,
    };
  }

  const dx = toNode.x - fromNode.x;
  const dy = toNode.y - fromNode.y;
  const distance = Math.max(Math.sqrt(dx * dx + dy * dy), 1);
  const normalX = -dy / distance;
  const normalY = dx / distance;
  const curveOffset = ((edgeIndex % 3) - 1) * 5;
  const labelX = (fromNode.x + toNode.x) / 2 + normalX * curveOffset;
  const labelY = (fromNode.y + toNode.y) / 2 + normalY * curveOffset;

  return {
    path: `M ${fromNode.x} ${fromNode.y} Q ${labelX} ${labelY} ${toNode.x} ${toNode.y}`,
    labelX,
    labelY,
  };
}

function FraudGraph({ alert }) {
  const { nodes, edges } = buildGraphModel(alert);
  const positionedNodes = positionGraphNodes(nodes);
  const nodePositions = new Map(
    positionedNodes.map((node) => [node.id, node])
  );
  const drawableEdges = edges
    .map((edge, index) => {
      const fromNode = nodePositions.get(edge.source);
      const toNode = nodePositions.get(edge.target);
      if (!fromNode || !toNode) return null;

      return {
        edge,
        fromNode,
        toNode,
        ...buildEdgePath(fromNode, toNode, index),
      };
    })
    .filter(Boolean);

  if (nodes.length === 0 && edges.length === 0) {
    return <div className="detail-state">No graph data available.</div>;
  }

  return (
    <div className="fraud-graph-card">
      <div className="graph-stage">
        <svg
          className="fraud-graph"
          viewBox="0 0 100 100"
          role="img"
          aria-label={`Fraud network graph with ${nodes.length} nodes and ${edges.length} edges`}
        >
          <defs>
            <marker
              id="arrow-risk-high"
              markerWidth="7"
              markerHeight="7"
              refX="6"
              refY="3.5"
              orient="auto"
            >
              <path d="M0,0 L7,3.5 L0,7 Z" fill="#fb7185" />
            </marker>
            <marker
              id="arrow-risk-medium"
              markerWidth="7"
              markerHeight="7"
              refX="6"
              refY="3.5"
              orient="auto"
            >
              <path d="M0,0 L7,3.5 L0,7 Z" fill="#fbbf24" />
            </marker>
            <marker
              id="arrow-risk-low"
              markerWidth="7"
              markerHeight="7"
              refX="6"
              refY="3.5"
              orient="auto"
            >
              <path d="M0,0 L7,3.5 L0,7 Z" fill="#34d399" />
            </marker>
          </defs>

          <g className="network-edge-layer">
            {drawableEdges.map(
              ({ edge, path, labelX, labelY }, edgeIndex) => {
                const tone = getRiskTone(edge.suspicion);
                return (
                  <g
                    key={`${edge.id}-${edgeIndex}`}
                    className={`network-edge ${tone} ${
                      edge.is_trigger ? "edge-trigger" : ""
                    }`}
                  >
                    <path d={path} markerEnd={`url(#arrow-${tone})`} />
                    <text x={labelX} y={labelY - 1}>
                      {formatCompactCurrency(edge.amount)}
                    </text>
                  </g>
                );
              }
            )}
          </g>

          <g className="network-node-layer">
            {positionedNodes.map((node) => (
              <g
                key={node.id}
                className={`network-node ${getRiskTone(node.fraud_score)} ${
                  node.is_new ? "node-new" : ""
                }`}
                transform={`translate(${node.x} ${node.y})`}
              >
                <circle className="node-halo" r="7" />
                <circle className="node-core" r={node.is_new ? "4.8" : "4.2"} />
                <text className="node-name" y="-9">
                  {truncateMiddle(node.name, 15)}
                </text>
                <text className="node-id" y="10">
                  {truncateMiddle(node.id, 13)}
                </text>
              </g>
            ))}
          </g>
        </svg>
      </div>

      <div className="graph-summary">
        <div className="graph-stat">
          <span>Nodes</span>
          <strong>{nodes.length}</strong>
        </div>
        <div className="graph-stat">
          <span>Edges</span>
          <strong>{edges.length}</strong>
        </div>
        <div className="graph-stat">
          <span>Trigger</span>
          <strong>{edges.some((edge) => edge.is_trigger) ? "Marked" : "Auto"}</strong>
        </div>
      </div>

      <div className="graph-legend">
        <span className="legend-item risk-high">High</span>
        <span className="legend-item risk-medium">Medium</span>
        <span className="legend-item risk-low">Low</span>
      </div>
    </div>
  );
}

function normalizeLiveAlert(alert) {
  if (!alert || typeof alert !== "object") return null;
  return {
    id: alert.id || alert.transaction_id || alert.trigger_transaction_id || null,
    timestamp: alert.timestamp || new Date().toISOString(),
    typology: alert.typology || alert.type || "Unknown",
    risk_level: alert.risk_level || alert.riskLevel || "UNKNOWN",
    fraud_score: Number(alert.fraud_score || alert.fraudScore || 0),
    raw_gnn_score: Number(alert.raw_gnn_score || alert.rawGnnScore || 0),
    confidence: Number(alert.confidence || 0),
    latency_ms: Number(alert.latency_ms || alert.latencyMs || 0),
    trigger_transaction_id:
      alert.trigger_transaction_id || alert.triggerTransactionId || alert.id || null,
    graph_data: alert.graph_data || alert.graphData || { nodes: [], edges: [] },
    nodes: Array.isArray(alert.nodes) ? alert.nodes : [],
    edges: Array.isArray(alert.edges) ? alert.edges : [],
    evidence_chain: Array.isArray(alert.evidence_chain)
      ? alert.evidence_chain
      : alert.evidenceChain || [],
    risk_breakdown: alert.risk_breakdown || alert.riskBreakdown || {},
    total_amount: Number(alert.total_amount || alert.totalAmount || 0),
    accounts_involved: Array.isArray(alert.accounts_involved)
      ? alert.accounts_involved
      : alert.accountsInvolved || [],
  };
}

function buildReportPayload(alert) {
  const graphData = alert.graph_data || {};
  const graphNodes = Array.isArray(graphData.nodes) ? graphData.nodes : [];
  const graphEdges = Array.isArray(graphData.edges) ? graphData.edges : [];

  const nodes =
    Array.isArray(alert.nodes) && alert.nodes.length > 0
      ? alert.nodes
      : graphNodes.map((node) => ({
          account_id: node.id,
          name: node.name || "Unknown",
          total_sent: Number(node.total_sent || 0),
          total_received: Number(node.total_received || 0),
          tx_count_out: Number(node.tx_count_out || 0),
          tx_count_in: Number(node.tx_count_in || 0),
          unique_counterparts: Number(node.unique_counterparts || 0),
          is_new_account: Boolean(node.is_new),
          dormancy_score: Number(node.dormancy || 0),
          last_tx_timestamp: null,
          first_tx_timestamp: null,
        }));

  const edges =
    Array.isArray(alert.edges) && alert.edges.length > 0
      ? alert.edges
      : graphEdges.map((edge, index) => ({
          edge_id: `graph-edge-${index + 1}`,
          from_account: edge.source,
          to_account: edge.target,
          amount: Number(edge.amount || 0),
          currency: edge.currency || "INR",
          payment_format: edge.payment_format || "NEFT",
          timestamp: edge.timestamp || new Date().toISOString(),
          is_trigger: Boolean(edge.is_trigger),
        }));

  return {
    score_result: {
      trigger_transaction_id: alert.trigger_transaction_id,
      is_fraud: true,
      typology: alert.typology,
      risk_level: alert.risk_level,
      fraud_score: Number(alert.fraud_score || 0),
      raw_gnn_score: Number(alert.raw_gnn_score || 0),
      confidence: Number(alert.confidence || 0),
      latency_ms: Number(alert.latency_ms || 0),
      evidence_chain: alert.evidence_chain || [],
      graph_data: alert.graph_data || { nodes: [], edges: [] },
      risk_breakdown: alert.risk_breakdown || {},
    },
    nodes,
    edges,
    reporting_entity: "Demo Bank Ltd",
    branch: "Main Branch",
  };
}

function MetricCard({ label, value }) {
  return (
    <article className="metric-card">
      <span>{label}</span>
      <strong>{value}</strong>
    </article>
  );
}

function ReportPreview({ output }) {
  if (!output || output.kind === "idle" || output.kind === "loading") {
    return (
      <div className="report-output report-message">
        {output?.message || DEFAULT_REPORT_MESSAGE}
      </div>
    );
  }

  if (typeof output === "string" || output.kind === "error") {
    return (
      <div className="report-output report-message report-error">
        {typeof output === "string" ? output : output.message}
      </div>
    );
  }

  const report = output.report || output;
  const factors = Array.isArray(report.aggravating_factors)
    ? report.aggravating_factors
    : [];
  const evidence = Array.isArray(report.evidence_chain)
    ? report.evidence_chain
    : [];
  const isAiGenerated = report.generation_mode === "gemini";

  return (
    <article className="report-card">
      <div className="report-title-row">
        <div>
          <span className="meta-label">Report ID</span>
          <strong>{report.report_id || "Generated report"}</strong>
        </div>
        <span className={`chip ${isAiGenerated ? "chip-low" : "chip-medium"}`}>
          {isAiGenerated ? "AI generated" : "Rule fallback"}
        </span>
      </div>

      {report.generation_note ? (
        <p className="report-note">{report.generation_note}</p>
      ) : null}

      <div className="report-meta-grid">
        <div>
          <span className="detail-label">Typology</span>
          <strong>{report.typology || "Unknown"}</strong>
        </div>
        <div>
          <span className="detail-label">Risk</span>
          <strong>{report.risk_level || "Unknown"}</strong>
        </div>
        <div>
          <span className="detail-label">Amount</span>
          <strong>{formatCurrency(report.total_amount_involved || 0)}</strong>
        </div>
        <div>
          <span className="detail-label">Duration</span>
          <strong>{report.time_span_description || "Unknown"}</strong>
        </div>
      </div>

      <section className="report-section">
        <h3>Nature of Suspicion</h3>
        <p>{report.nature_of_suspicion || "No narrative returned."}</p>
      </section>

      <section className="report-section">
        <h3>Fund Trail Narrative</h3>
        <p>{report.fund_trail_narrative || "No fund trail returned."}</p>
      </section>

      <section className="report-section">
        <h3>Aggravating Factors</h3>
        {factors.length > 0 ? (
          <ul className="report-list">
            {factors.map((factor, index) => (
              <li key={`${factor}-${index}`}>{factor}</li>
            ))}
          </ul>
        ) : (
          <p>No aggravating factors returned.</p>
        )}
      </section>

      <section className="report-section">
        <h3>Recommended Action</h3>
        <p>{report.recommended_action || "No action returned."}</p>
      </section>

      {evidence.length > 0 ? (
        <section className="report-section">
          <h3>Evidence Trail</h3>
          <div className="report-evidence">
            {evidence.map((step, index) => (
              <div key={`${step.step || index}-${step.from}-${step.to}`}>
                <span className="meta-label">Step {step.step || index + 1}</span>
                <strong>
                  {truncateMiddle(step.from, 14)} to{" "}
                  {truncateMiddle(step.to, 14)}
                </strong>
                <span>{formatCurrency(step.amount || 0)}</span>
              </div>
            ))}
          </div>
        </section>
      ) : null}
    </article>
  );
}

function AlertList({ alerts, selectedAlertId, onSelect }) {
  return (
    <div className="alert-list">
      {alerts.map((alert) => (
        <button
          key={alert.id}
          type="button"
          className={`alert-item ${
            alert.id === selectedAlertId ? "active" : ""
          }`}
          aria-pressed={alert.id === selectedAlertId}
          onClick={() => onSelect(alert.id)}
        >
          <div className="alert-topline">
            <div>
              <h3>{alert.typology || "Unknown Typology"}</h3>
              <p className="alert-route">
                {alert.trigger_transaction_id || alert.id}
              </p>
            </div>
            <span className={`chip ${getRiskClass(alert.risk_level)}`}>
              {alert.risk_level || "UNKNOWN"}
            </span>
          </div>

          <div className="chip-row">
            <span className="chip">
              Score {Number(alert.fraud_score || 0).toFixed(2)}
            </span>
            <span className="chip">
              Exposure {formatCurrency(alert.total_amount || 0)}
            </span>
            <span className="chip chip-graph">View graph</span>
          </div>

          <div className="alert-route">{formatTimestamp(alert.timestamp)}</div>
        </button>
      ))}
    </div>
  );
}

function AlertDetail({ alert, reportLoading, onGenerateReport }) {
  if (!alert) {
    return <div className="detail-state">{DETAIL_EMPTY_MESSAGE}</div>;
  }

  const { nodes: graphNodes, edges: graphEdges } = buildGraphModel(alert);
  const evidence = Array.isArray(alert.evidence_chain)
    ? alert.evidence_chain
    : [];
  const riskBreakdown = alert.risk_breakdown || {};
  const riskEntries = Object.entries(riskBreakdown);

  return (
    <div className="detail-stack">
      <section className="detail-section">
        <div className="detail-grid">
          <div>
            <span className="detail-label">Typology</span>
            <div className="detail-value strong-danger">
              {alert.typology || "Unknown"}
            </div>
          </div>
          <div>
            <span className="detail-label">Risk Level</span>
            <div className="detail-value">{alert.risk_level || "Unknown"}</div>
          </div>
          <div>
            <span className="detail-label">Fraud Score</span>
            <div className="detail-value">
              {Number(alert.fraud_score || 0).toFixed(2)}
            </div>
          </div>
          <div>
            <span className="detail-label">Raw GNN Score</span>
            <div className="detail-value">
              {Number(alert.raw_gnn_score || 0).toFixed(2)}
            </div>
          </div>
          <div>
            <span className="detail-label">Confidence</span>
            <div className="detail-value">
              {Number(alert.confidence || 0).toFixed(2)}
            </div>
          </div>
          <div>
            <span className="detail-label">Latency</span>
            <div className="detail-value">
              {Number(alert.latency_ms || 0).toFixed(0)} ms
            </div>
          </div>
          <div>
            <span className="detail-label">Graph Nodes</span>
            <div className="detail-value">{graphNodes.length}</div>
          </div>
          <div>
            <span className="detail-label">Graph Edges</span>
            <div className="detail-value">{graphEdges.length}</div>
          </div>
        </div>

        <div className="detail-actions">
          <button
            type="button"
            className="button button-primary"
            onClick={() => onGenerateReport(alert)}
            disabled={reportLoading}
          >
            {reportLoading ? "Generating..." : "Generate STR Report"}
          </button>
        </div>
      </section>

      <section className="detail-section">
        <div className="section-heading">
          <h3>Fraud Network</h3>
          <span className="section-meta">Nodes, edges, and transfer direction</span>
        </div>
        <FraudGraph alert={alert} />
      </section>

      <section className="detail-section">
        <h3>Risk Breakdown</h3>
        <div className="detail-grid">
          {riskEntries.length > 0 ? (
            riskEntries.map(([key, value]) => (
              <div key={key} className="detail-card">
                <span className="detail-label">{key.replaceAll("_", " ")}</span>
                <div className="detail-value">
                  {typeof value === "number" ? value.toFixed(3) : String(value)}
                </div>
              </div>
            ))
          ) : (
            <div className="detail-state">No risk breakdown available.</div>
          )}
        </div>
      </section>

      <section className="detail-section">
        <h3>Graph Nodes</h3>
        <div className="graph-list">
          {graphNodes.length > 0 ? (
            graphNodes.map((node, index) => (
              <article
                key={node.id || `${node.name || "node"}-${index}`}
                className="graph-node"
              >
                <span className="meta-label">{node.id}</span>
                <strong>{node.name || "Unknown"}</strong>
                <div className="chip-row">
                  <span className="chip">
                    Node score {Number(node.fraud_score || 0).toFixed(2)}
                  </span>
                  <span className="chip">
                    {node.is_new ? "New account" : "Established"}
                  </span>
                </div>
              </article>
            ))
          ) : (
            <div className="detail-state">No graph nodes available.</div>
          )}
        </div>
      </section>

      <section className="detail-section">
        <h3>Graph Edges</h3>
        <div className="edge-list">
          {graphEdges.length > 0 ? (
            graphEdges.map((edge, index) => (
              <article
                key={edge.id || `${edge.source}-${edge.target}-${index}`}
                className={`edge-item ${edge.is_trigger ? "trigger-edge" : ""}`}
              >
                <span className="meta-label">
                  {edge.payment_format || "NEFT"} ·{" "}
                  {formatTimestamp(edge.timestamp)}
                </span>
                <strong>
                  {truncateMiddle(edge.source, 16)} to{" "}
                  {truncateMiddle(edge.target, 16)}
                </strong>
                <div className="chip-row">
                  <span className="chip">{formatCurrency(edge.amount || 0)}</span>
                  <span className={`chip ${getRiskClass(alert.risk_level)}`}>
                    Suspicion {Number(edge.suspicion || 0).toFixed(2)}
                  </span>
                  {edge.is_trigger ? (
                    <span className="chip chip-trigger">Trigger edge</span>
                  ) : null}
                </div>
              </article>
            ))
          ) : (
            <div className="detail-state">No graph edges available.</div>
          )}
        </div>
      </section>

      <section className="detail-section">
        <h3>Evidence Chain</h3>
        <div className="evidence-list">
          {evidence.length > 0 ? (
            evidence.map((step, index) => (
              <article
                key={`${step.step || index}-${step.from_account || "from"}-${
                  step.to_account || "to"
                }`}
                className="evidence-item"
              >
                <span className="meta-label">Step {step.step || "?"}</span>
                <strong>
                  {step.from_name || step.from_account} to{" "}
                  {step.to_name || step.to_account}
                </strong>
                <div className="chip-row">
                  <span className="chip">
                    {formatCurrency(step.amount || 0)}
                  </span>
                  <span className="chip">
                    {step.payment_format || "NEFT"}
                  </span>
                  <span className="chip">
                    Suspicion {Number(step.suspicion_score || 0).toFixed(2)}
                  </span>
                </div>
                <p className="alert-route">{formatTimestamp(step.timestamp)}</p>
              </article>
            ))
          ) : (
            <div className="detail-state">No evidence chain available.</div>
          )}
        </div>
      </section>
    </div>
  );
}

export default function App() {
  const [alerts, setAlerts] = useState([]);
  const [selectedAlertId, setSelectedAlertId] = useState(null);
  const [loading, setLoading] = useState(true);
  const [reportLoading, setReportLoading] = useState(false);
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [backendStatus, setBackendStatus] = useState("Connecting");
  const [connectionMode, setConnectionMode] = useState("WebSocket");
  const [lastRefresh, setLastRefresh] = useState("Waiting");
  const [reportOutput, setReportOutput] = useState(DEFAULT_REPORT_OUTPUT);
  const loadingRef = useRef(false);
  const reportLoadingRef = useRef(false);
  const stompClientRef = useRef(null);
  const isConnectedRef = useRef(false);
  const pageRef = useRef(page);
  const mountedRef = useRef(false);

  const selectedAlert =
    alerts.find((alert) => alert.id === selectedAlertId) || alerts[0] || null;

  const highRisk = alerts.filter((alert) =>
    String(alert.risk_level || "").toUpperCase().includes("HIGH")
  ).length;
  const typologies = new Set(alerts.map((alert) => alert.typology).filter(Boolean))
    .size;
  const exposure = alerts.reduce(
    (sum, alert) => sum + Number(alert.total_amount || 0),
    0
  );
  const visibleTotalPages = Math.max(totalPages, 1);
  const visiblePage = Math.min(page, visibleTotalPages);

  function applyAlertSnapshot(payload) {
    const nextAlerts = Array.isArray(payload?.items) ? payload.items : [];
    const nextTotal = Number(payload?.total || 0);
    const nextTotalPages = Number(
      payload?.total_pages ?? payload?.totalPages ?? 0
    );

    setAlerts(nextAlerts);
    setTotal(nextTotal);
    setTotalPages(nextTotalPages);
    setSelectedAlertId((currentSelectedAlertId) => {
      if (
        currentSelectedAlertId &&
        nextAlerts.some((alert) => alert.id === currentSelectedAlertId)
      ) {
        return currentSelectedAlertId;
      }

      return nextAlerts[0]?.id || null;
    });
    setBackendStatus("Connected");
    setConnectionMode("WebSocket");
    setLastRefresh(new Date().toLocaleTimeString("en-IN"));
  }

  async function loadAlertsHttpFallback(targetPage = pageRef.current) {
    if (loadingRef.current) return;

    loadingRef.current = true;
    setLoading(true);

    try {
      const response = await fetch(
        `${API_BASE_URL}/alerts?page=${targetPage}&limit=${PAGE_LIMIT}`
      );
      if (!response.ok) {
        throw new Error(`Request failed with status ${response.status}`);
      }

      const payload = await response.json();
      applyAlertSnapshot(payload);
      setConnectionMode("HTTP fallback");
    } catch (error) {
      setAlerts([]);
      setSelectedAlertId(null);
      setTotal(0);
      setTotalPages(0);
      setBackendStatus("Offline");
      setLastRefresh("Failed");
      setReportOutput({ kind: "error", message: error.message });
    } finally {
      loadingRef.current = false;
      setLoading(false);
    }
  }

  function requestAlertSnapshot(targetPage = pageRef.current) {
    const client = stompClientRef.current;
    if (!client || !isConnectedRef.current) {
      return;
    }

    loadingRef.current = true;
    setLoading(true);
    client.publish({
      destination: "/app/alerts.snapshot",
      body: JSON.stringify({
        page: targetPage,
        limit: PAGE_LIMIT,
      }),
    });
  }

  async function generateReport(alert) {
    if (!alert || reportLoadingRef.current) return;

    reportLoadingRef.current = true;
    setReportLoading(true);
    setReportOutput({ kind: "loading", message: "Generating report..." });

    try {
      const payload = buildReportPayload(alert);
      const response = await fetch(
        `${API_BASE_URL}/alerts/${encodeURIComponent(
          alert.trigger_transaction_id || alert.id
        )}/report`,
        {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
          },
          body: JSON.stringify(payload),
        }
      );

      if (!response.ok) {
        throw new Error(`Report request failed with status ${response.status}`);
      }

      const result = await response.json();
      if (result?.error) {
        setReportOutput({ kind: "error", message: result.error });
        return;
      }

      setReportOutput({ kind: "report", report: result });
    } catch (error) {
      setReportOutput({
        kind: "error",
        message: `Report generation failed: ${error.message}`,
      });
    } finally {
      reportLoadingRef.current = false;
      setReportLoading(false);
    }
  }

  useEffect(() => {
    pageRef.current = page;
  }, [page]);

  useEffect(() => {
    mountedRef.current = true;

    const client = new Client({
      webSocketFactory: () => new SockJS(WS_URL),
      reconnectDelay: 5000,
      onConnect: () => {
        console.log("STOMP connected");
        stompClientRef.current = client;
        isConnectedRef.current = true;
        setBackendStatus("Connected");
        setConnectionMode("WebSocket");

        client.subscribe("/user/queue/alerts.snapshot", (message) => {
          console.log("STOMP snapshot response received", message.body);
          try {
            const payload = JSON.parse(message.body);
            applyAlertSnapshot(payload);
          } catch (error) {
            console.error("Failed to parse alert snapshot", error);
            setReportOutput({
              kind: "error",
              message: `Failed to parse alert snapshot: ${error.message}`,
            });
          } finally {
            loadingRef.current = false;
            setLoading(false);
          }
        });

        client.subscribe("/topic/fraud-alerts", (message) => {
          try {
            console.log("STOMP live alert received", message.body);
            const payload = JSON.parse(message.body);
            const liveAlert = normalizeLiveAlert(payload);
            if (liveAlert && liveAlert.id) {
              setAlerts((current) => {
                const next = [liveAlert, ...current.filter((a) => a.id !== liveAlert.id)];
                return next.slice(0, PAGE_LIMIT);
              });
              setSelectedAlertId(liveAlert.id);
              setTotal((current) => Math.max(current, 1));
              setTotalPages((current) => Math.max(current, 1));
            }
            setBackendStatus("Connected");
            setConnectionMode("WebSocket");
            setLastRefresh(new Date().toLocaleTimeString("en-IN"));
            requestAlertSnapshot(pageRef.current);
          } catch (error) {
            console.error("Failed to parse live alert", error);
            setReportOutput({
              kind: "error",
              message: `Failed to parse live alert: ${error.message}`,
            });
            requestAlertSnapshot(pageRef.current);
          }
        });

        requestAlertSnapshot(pageRef.current);
      },
      onStompError: (frame) => {
        console.error("STOMP error", frame);
        isConnectedRef.current = false;
        setBackendStatus("Offline");
        setConnectionMode("HTTP fallback");
        setReportOutput({
          kind: "error",
          message:
            frame.headers.message || "WebSocket broker reported an error.",
        });
        loadAlertsHttpFallback(pageRef.current);
      },
      onWebSocketClose: () => {
        console.warn("WebSocket closed");
        isConnectedRef.current = false;
        if (mountedRef.current) {
          setBackendStatus("Reconnecting");
        }
      },
      onWebSocketError: () => {
        console.error("WebSocket error");
        isConnectedRef.current = false;
        setBackendStatus("Offline");
        setConnectionMode("HTTP fallback");
        loadAlertsHttpFallback(pageRef.current);
      },
    });

    stompClientRef.current = client;
    client.activate();

    return () => {
      mountedRef.current = false;
      isConnectedRef.current = false;
      stompClientRef.current = null;
      client.deactivate();
    };
  }, []);

  useEffect(() => {
    if (isConnectedRef.current) {
      requestAlertSnapshot(page);
    }
  }, [page]);

  useEffect(() => {
    setReportOutput(DEFAULT_REPORT_OUTPUT);
  }, [selectedAlertId]);

  const detailMessage =
    backendStatus === "Offline"
      ? "The dashboard could not reach the backend. Start Spring Boot on port 8080 and refresh."
      : DETAIL_EMPTY_MESSAGE;

  return (
    <div className="app-shell">
      <header className="hero">
        <div>
          <p className="eyebrow">AML Detection Console</p>
          <h1>FinTrace Intelligence Dashboard</h1>
          <p className="hero-copy">
            Live monitoring for suspicious transaction patterns, risk scoring,
            and evidence review.
          </p>
        </div>

        <div className="hero-status">
          <div className="status-card">
            <span className="status-label">Backend</span>
            <strong>{backendStatus}</strong>
          </div>
          <div className="status-card">
            <span className="status-label">Channel</span>
            <strong>{connectionMode}</strong>
          </div>
          <div className="status-card">
            <span className="status-label">Last Update</span>
            <strong>{lastRefresh}</strong>
          </div>
        </div>
      </header>

      <main className="layout">
        <section className="panel panel-wide">
          <div className="panel-header">
            <div>
              <p className="eyebrow">Overview</p>
              <h2>Recent Alert Stream</h2>
            </div>
            <button
              type="button"
              className="button button-secondary"
              onClick={() => {
                if (isConnectedRef.current) {
                  requestAlertSnapshot(page);
                  return;
                }

                loadAlertsHttpFallback(page);
              }}
              disabled={loading}
            >
              {loading ? "Syncing..." : "Sync Now"}
            </button>
          </div>

          <div className="metrics">
            <MetricCard label="Total Alerts" value={String(total)} />
            <MetricCard label="High Risk" value={String(highRisk)} />
            <MetricCard label="Typologies" value={String(typologies)} />
            <MetricCard
              label="Total Exposure"
              value={formatCurrency(exposure)}
            />
          </div>

          {alerts.length > 0 ? (
            <AlertList
              alerts={alerts}
              selectedAlertId={selectedAlert?.id}
              onSelect={setSelectedAlertId}
            />
          ) : (
            <p className="empty-state">
              No fraud alerts yet. Start the backend services and send some
              transactions to populate the dashboard.
            </p>
          )}

          <div className="pagination">
            <button
              type="button"
              className="button button-secondary"
              onClick={() => setPage((currentPage) => currentPage - 1)}
              disabled={loading || page <= 1}
            >
              Previous
            </button>
            <span className="pagination-info">
              Page {visiblePage} of {visibleTotalPages} | {total} total
            </span>
            <button
              type="button"
              className="button button-secondary"
              onClick={() => setPage((currentPage) => currentPage + 1)}
              disabled={loading || totalPages === 0 || page >= totalPages}
            >
              Next
            </button>
          </div>
        </section>

        <aside className="sidebar">
          <section className="panel">
            <div className="panel-header">
              <div>
                <p className="eyebrow">Details</p>
                <h2>Selected Alert</h2>
              </div>
            </div>

            {backendStatus === "Offline" && !selectedAlert ? (
              <div className="detail-state">{detailMessage}</div>
            ) : (
              <AlertDetail
                alert={selectedAlert}
                reportLoading={reportLoading}
                onGenerateReport={generateReport}
              />
            )}
          </section>

          <section className="panel">
            <div className="panel-header">
              <div>
                <p className="eyebrow">Report</p>
                <h2>STR Preview</h2>
              </div>
            </div>
            <ReportPreview output={reportOutput} />
          </section>
        </aside>
      </main>
    </div>
  );
}
