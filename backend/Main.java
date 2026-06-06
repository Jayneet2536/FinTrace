// ═══════════════════════════════════════════════════════════════════
// SPRING BOOT AML BACKEND
// ═══════════════════════════════════════════════════════════════════
// File structure:
//
// src/main/java/com/aml/
//   ├── AmlApplication.java
//   ├── config/
//   │   └── AppConfig.java
//   ├── model/
//   │   ├── Transaction.java
//   │   ├── AccountNode.java
//   │   ├── ScoreRequest.java
//   │   ├── ScoreResponse.java
//   │   └── FraudAlert.java
//   ├── repository/
//   │   └── Neo4jRepository.java
//   ├── service/
//   │   ├── TransactionService.java
//   │   └── InferenceService.java
//   └── controller/
//       ├── TransactionController.java
//       └── AlertController.java
//
// src/main/resources/
//   └── application.properties
//
// pom.xml (dependencies listed at bottom)
// ═══════════════════════════════════════════════════════════════════


// ─── AmlApplication.java ────────────────────────────────────────────
package com.aml;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AmlApplication {
    public static void main(String[] args) {
        SpringApplication.run(AmlApplication.class, args);
    }
}


// ─── config/AppConfig.java ──────────────────────────────────────────
package com.aml.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class AppConfig {

    // RestTemplate for calling Python inference service
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    // CORS for React frontend
    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/**")
                        .allowedOrigins("*")
                        .allowedMethods("GET", "POST", "PUT", "DELETE")
                        .allowedHeaders("*");
            }
        };
    }
}


// ─── model/Transaction.java ─────────────────────────────────────────
package com.aml.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Transaction {

    @JsonProperty("transaction_id")
    private String transactionId;

    @JsonProperty("from_account")
    private String fromAccount;

    @JsonProperty("from_name")
    private String fromName;

    @JsonProperty("to_account")
    private String toAccount;

    @JsonProperty("to_name")
    private String toName;

    private Double amount;
    private String currency;

    @JsonProperty("payment_format")
    private String paymentFormat;

    private String timestamp;

    @JsonProperty("pattern_id")
    private String patternId;

    @JsonProperty("is_fraud_seed")
    private Boolean isFraudSeed;

    @JsonProperty("typology_hint")
    private String typologyHint;

    private String note;
}


// ─── model/AccountNode.java ─────────────────────────────────────────
package com.aml.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.Builder;

@Data
@Builder
public class AccountNode {
    @JsonProperty("account_id")
    private String accountId;

    private String name;

    @JsonProperty("total_sent")
    private Double totalSent;

    @JsonProperty("total_received")
    private Double totalReceived;

    @JsonProperty("tx_count_out")
    private Integer txCountOut;

    @JsonProperty("tx_count_in")
    private Integer txCountIn;

    @JsonProperty("unique_counterparts")
    private Integer uniqueCounterparts;

    @JsonProperty("is_new_account")
    private Boolean isNewAccount;

    @JsonProperty("dormancy_score")
    private Double dormancyScore;

    @JsonProperty("last_tx_timestamp")
    private String lastTxTimestamp;

    @JsonProperty("first_tx_timestamp")
    private String firstTxTimestamp;
}


// ─── model/EdgeData.java ────────────────────────────────────────────
package com.aml.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.Builder;

@Data
@Builder
public class EdgeData {
    @JsonProperty("edge_id")
    private String edgeId;

    @JsonProperty("from_account")
    private String fromAccount;

    @JsonProperty("to_account")
    private String toAccount;

    private Double amount;
    private String currency;

    @JsonProperty("payment_format")
    private String paymentFormat;

    private String timestamp;

    @JsonProperty("is_trigger")
    private Boolean isTrigger;
}


// ─── model/ScoreRequest.java ────────────────────────────────────────
package com.aml.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.Builder;
import java.util.List;

@Data
@Builder
public class ScoreRequest {
    @JsonProperty("trigger_transaction_id")
    private String triggerTransactionId;

    private List<AccountNode> nodes;
    private List<EdgeData> edges;
}


// ─── model/ScoreResponse.java ───────────────────────────────────────
package com.aml.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class ScoreResponse {
    @JsonProperty("trigger_transaction_id")
    private String triggerTransactionId;

    @JsonProperty("is_fraud")
    private Boolean isFraud;

    private String typology;

    @JsonProperty("risk_level")
    private String riskLevel;

    @JsonProperty("fraud_score")
    private Double fraudScore;

    @JsonProperty("raw_gnn_score")
    private Double rawGnnScore;

    private Double confidence;

    @JsonProperty("evidence_chain")
    private List<Map<String, Object>> evidenceChain;

    @JsonProperty("graph_data")
    private Map<String, Object> graphData;

    @JsonProperty("risk_breakdown")
    private Map<String, Object> riskBreakdown;

    @JsonProperty("latency_ms")
    private Double latencyMs;
}


// ─── model/FraudAlert.java ──────────────────────────────────────────
package com.aml.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.Builder;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class FraudAlert {
    private String id;
    private String timestamp;
    private String typology;

    @JsonProperty("risk_level")
    private String riskLevel;

    @JsonProperty("fraud_score")
    private Double fraudScore;

    @JsonProperty("trigger_transaction_id")
    private String triggerTransactionId;

    @JsonProperty("graph_data")
    private Map<String, Object> graphData;

    @JsonProperty("evidence_chain")
    private List<Map<String, Object>> evidenceChain;

    @JsonProperty("risk_breakdown")
    private Map<String, Object> riskBreakdown;

    @JsonProperty("total_amount")
    private Double totalAmount;

    @JsonProperty("accounts_involved")
    private List<String> accountsInvolved;
}


// ─── repository/Neo4jRepository.java ────────────────────────────────
package com.aml.repository;

import com.aml.model.AccountNode;
import com.aml.model.EdgeData;
import com.aml.model.Transaction;
import org.neo4j.driver.*;
import org.neo4j.driver.types.Node;
import org.neo4j.driver.types.Relationship;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Repository
public class Neo4jRepository {

    private final Driver driver;
    private static final DateTimeFormatter FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    public Neo4jRepository(
            @Value("${neo4j.uri}") String uri,
            @Value("${neo4j.username}") String username,
            @Value("${neo4j.password}") String password) {
        this.driver = GraphDatabase.driver(uri,
                        AuthTokens.basic(username, password));
    }

    // ── STORE TRANSACTION ──────────────────────────────────────────
    public void storeTransaction(Transaction tx) {
        try (Session session = driver.session()) {
            session.writeTransaction(t -> {
                // 1. Create or update FROM account node
                t.run("""
                    MERGE (a:Account {id: $accountId})
                    ON CREATE SET
                        a.name              = $name,
                        a.total_sent        = $amount,
                        a.total_received    = 0.0,
                        a.tx_count_out      = 1,
                        a.tx_count_in       = 0,
                        a.first_tx_timestamp= $timestamp,
                        a.last_tx_timestamp = $timestamp,
                        a.dormancy_score    = 0.0,
                        a.is_new_account    = true
                    ON MATCH SET
                        a.name              = $name,
                        a.total_sent        = a.total_sent + $amount,
                        a.tx_count_out      = a.tx_count_out + 1,
                        a.last_tx_timestamp = $timestamp,
                        a.is_new_account    = false,
                        a.dormancy_score    = $dormancyScore
                    """,
                    Map.of(
                        "accountId",    tx.getFromAccount(),
                        "name",         tx.getFromName() != null ? tx.getFromName() : "Unknown",
                        "amount",       tx.getAmount(),
                        "timestamp",    tx.getTimestamp(),
                        "dormancyScore",0.0
                    )
                );

                // 2. Create or update TO account node
                t.run("""
                    MERGE (a:Account {id: $accountId})
                    ON CREATE SET
                        a.name              = $name,
                        a.total_sent        = 0.0,
                        a.total_received    = $amount,
                        a.tx_count_out      = 0,
                        a.tx_count_in       = 1,
                        a.first_tx_timestamp= $timestamp,
                        a.last_tx_timestamp = $timestamp,
                        a.dormancy_score    = 0.0,
                        a.is_new_account    = true
                    ON MATCH SET
                        a.name              = $name,
                        a.total_received    = a.total_received + $amount,
                        a.tx_count_in       = a.tx_count_in + 1,
                        a.last_tx_timestamp = $timestamp,
                        a.is_new_account    = false,
                        a.dormancy_score    = $dormancyScore
                    """,
                    Map.of(
                        "accountId",    tx.getToAccount(),
                        "name",         tx.getToName() != null ? tx.getToName() : "Unknown",
                        "amount",       tx.getAmount(),
                        "timestamp",    tx.getTimestamp(),
                        "dormancyScore",0.0
                    )
                );

                // 3. Create transaction edge
                t.run("""
                    MATCH (from:Account {id: $fromId})
                    MATCH (to:Account {id: $toId})
                    CREATE (from)-[r:TRANSFER {
                        id:             $txId,
                        amount:         $amount,
                        currency:       $currency,
                        payment_format: $paymentFormat,
                        timestamp:      $timestamp,
                        pattern_id:     $patternId,
                        is_fraud:       false,
                        fraud_score:    0.0,
                        typology:       'Pending'
                    }]->(to)
                    """,
                    Map.of(
                        "fromId",        tx.getFromAccount(),
                        "toId",          tx.getToAccount(),
                        "txId",          tx.getTransactionId(),
                        "amount",        tx.getAmount(),
                        "currency",      tx.getCurrency() != null ? tx.getCurrency() : "INR",
                        "paymentFormat", tx.getPaymentFormat() != null ? tx.getPaymentFormat() : "NEFT",
                        "timestamp",     tx.getTimestamp(),
                        "patternId",     tx.getPatternId() != null ? tx.getPatternId() : ""
                    )
                );

                // 4. Update unique_counterparts for both accounts
                t.run("""
                    MATCH (a:Account {id: $fromId})-[:TRANSFER]->(neighbor)
                    WITH a, count(DISTINCT neighbor) AS cnt
                    SET a.unique_counterparts = cnt
                    """, Map.of("fromId", tx.getFromAccount()));

                t.run("""
                    MATCH (a:Account {id: $toId})<-[:TRANSFER]-(neighbor)
                    WITH a, count(DISTINCT neighbor) AS cnt
                    SET a.unique_counterparts = cnt
                    """, Map.of("toId", tx.getToAccount()));

                return null;
            });
        }
    }


    // ── QUERY 2-HOP NEIGHBORHOOD ──────────────────────────────────
    // Returns all accounts and transactions within 2 hops
    // of either endpoint, within 72 hours of trigger transaction.
    public Map<String, Object> getNeighborhood(
            String fromAccount,
            String toAccount,
            String triggerTimestamp) {

        try (Session session = driver.session()) {
            return session.readTransaction(t -> {

                // Query all related accounts and transactions
                // within 72 hours of trigger timestamp
                Result result = t.run("""
                    MATCH (trigger_from:Account {id: $fromId})
                    MATCH (trigger_to:Account {id: $toId})
                    
                    // Get 2-hop neighborhood of both endpoints
                    CALL {
                        WITH trigger_from
                        MATCH (trigger_from)-[r1:TRANSFER*1..2]-(neighbor1)
                        RETURN DISTINCT neighbor1 AS neighbor
                        UNION
                        WITH trigger_to
                        MATCH (trigger_to)-[r2:TRANSFER*1..2]-(neighbor2)
                        RETURN DISTINCT neighbor2 AS neighbor
                    }
                    
                    // Get all transactions between neighborhood accounts
                    MATCH (a:Account)-[r:TRANSFER]->(b:Account)
                    WHERE (a.id = $fromId OR a.id = $toId 
                           OR a IN collect(neighbor))
                    AND   (b.id = $fromId OR b.id = $toId 
                           OR b IN collect(neighbor))
                    AND   datetime(r.timestamp) >= 
                          datetime($triggerTs) - duration('PT72H')
                    AND   datetime(r.timestamp) <= 
                          datetime($triggerTs) + duration('PT1H')
                    
                    RETURN collect(DISTINCT a) + collect(DISTINCT b) AS nodes,
                           collect(DISTINCT r) AS edges
                    """,
                    Map.of(
                        "fromId",      fromAccount,
                        "toId",        toAccount,
                        "triggerTs",   triggerTimestamp
                    )
                );

                List<AccountNode> nodeList = new ArrayList<>();
                List<EdgeData>    edgeList = new ArrayList<>();

                if (result.hasNext()) {
                    Record record = result.next();

                    // Parse nodes
                    List<Object> rawNodes = record.get("nodes").asList();
                    Set<String> seenNodeIds = new HashSet<>();
                    for (Object obj : rawNodes) {
                        Node n = (Node) obj;
                        String nodeId = n.get("id").asString();
                        if (seenNodeIds.contains(nodeId)) continue;
                        seenNodeIds.add(nodeId);

                        nodeList.add(AccountNode.builder()
                            .accountId(nodeId)
                            .name(n.get("name").asString("Unknown"))
                            .totalSent(n.get("total_sent").asDouble(0.0))
                            .totalReceived(n.get("total_received").asDouble(0.0))
                            .txCountOut(n.get("tx_count_out").asInt(0))
                            .txCountIn(n.get("tx_count_in").asInt(0))
                            .uniqueCounterparts(n.get("unique_counterparts").asInt(0))
                            .isNewAccount(n.get("is_new_account").asBoolean(false))
                            .dormancyScore(n.get("dormancy_score").asDouble(0.0))
                            .lastTxTimestamp(n.get("last_tx_timestamp").asString(null))
                            .firstTxTimestamp(n.get("first_tx_timestamp").asString(null))
                            .build()
                        );
                    }

                    // Parse edges
                    List<Object> rawEdges = record.get("edges").asList();
                    Set<String> seenEdgeIds = new HashSet<>();
                    for (Object obj : rawEdges) {
                        Relationship r = (Relationship) obj;
                        String edgeId = r.get("id").asString();
                        if (seenEdgeIds.contains(edgeId)) continue;
                        seenEdgeIds.add(edgeId);

                        edgeList.add(EdgeData.builder()
                            .edgeId(edgeId)
                            .fromAccount(r.startNodeId() + "") // will be resolved
                            .toAccount(r.endNodeId() + "")
                            .amount(r.get("amount").asDouble(0.0))
                            .currency(r.get("currency").asString("INR"))
                            .paymentFormat(r.get("payment_format").asString("NEFT"))
                            .timestamp(r.get("timestamp").asString(""))
                            .isTrigger(false)
                            .build()
                        );
                    }
                }

                Map<String, Object> neighborhood = new HashMap<>();
                neighborhood.put("nodes", nodeList);
                neighborhood.put("edges", edgeList);
                return neighborhood;
            });
        }
    }


    // ── UPDATE FRAUD RESULT IN NEO4J ───────────────────────────────
    public void updateFraudResult(
            String transactionId,
            boolean isFraud,
            String typology,
            String riskLevel,
            double fraudScore) {

        try (Session session = driver.session()) {
            session.writeTransaction(t -> {
                t.run("""
                    MATCH ()-[r:TRANSFER {id: $txId}]->()
                    SET r.is_fraud    = $isFraud,
                        r.typology   = $typology,
                        r.risk_level = $riskLevel,
                        r.fraud_score= $fraudScore
                    """,
                    Map.of(
                        "txId",       transactionId,
                        "isFraud",    isFraud,
                        "typology",   typology,
                        "riskLevel",  riskLevel,
                        "fraudScore", fraudScore
                    )
                );
                return null;
            });
        }
    }


    // ── GET ALL FRAUD ALERTS ───────────────────────────────────────
    public List<Map<String, Object>> getFraudAlerts(int limit) {
        try (Session session = driver.session()) {
            return session.readTransaction(t -> {
                Result result = t.run("""
                    MATCH (from:Account)-[r:TRANSFER]->(to:Account)
                    WHERE r.is_fraud = true
                    RETURN r.id AS txId,
                           r.timestamp AS timestamp,
                           r.typology AS typology,
                           r.risk_level AS riskLevel,
                           r.fraud_score AS fraudScore,
                           from.id AS fromAccount,
                           from.name AS fromName,
                           to.id AS toAccount,
                           to.name AS toName,
                           r.amount AS amount
                    ORDER BY r.timestamp DESC
                    LIMIT $limit
                    """,
                    Map.of("limit", limit)
                );

                List<Map<String, Object>> alerts = new ArrayList<>();
                while (result.hasNext()) {
                    Record rec = result.next();
                    Map<String, Object> alert = new HashMap<>();
                    alert.put("transaction_id", rec.get("txId").asString());
                    alert.put("timestamp",      rec.get("timestamp").asString());
                    alert.put("typology",       rec.get("typology").asString());
                    alert.put("risk_level",     rec.get("riskLevel").asString());
                    alert.put("fraud_score",    rec.get("fraudScore").asDouble());
                    alert.put("from_account",   rec.get("fromAccount").asString());
                    alert.put("from_name",      rec.get("fromName").asString());
                    alert.put("to_account",     rec.get("toAccount").asString());
                    alert.put("to_name",        rec.get("toName").asString());
                    alert.put("amount",         rec.get("amount").asDouble());
                    alerts.add(alert);
                }
                return alerts;
            });
        }
    }
}


// ─── service/InferenceService.java ──────────────────────────────────
package com.aml.service;

import com.aml.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Service
public class InferenceService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${inference.url}")
    private String inferenceUrl;   // http://localhost:8000

    public InferenceService(RestTemplate restTemplate,
                             ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    // ── SCORE TRANSACTION ─────────────────────────────────────────
    public ScoreResponse score(ScoreRequest request) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<ScoreRequest> entity = new HttpEntity<>(request, headers);

            ResponseEntity<ScoreResponse> response = restTemplate.exchange(
                inferenceUrl + "/score",
                HttpMethod.POST,
                entity,
                ScoreResponse.class
            );

            return response.getBody();

        } catch (Exception e) {
            System.err.println("Inference service error: " + e.getMessage());
            // Return safe default — don't crash the whole pipeline
            ScoreResponse fallback = new ScoreResponse();
            fallback.setIsFraud(false);
            fallback.setTypology("Legitimate");
            fallback.setRiskLevel("LOW");
            fallback.setFraudScore(0.0);
            return fallback;
        }
    }

    // ── GENERATE REPORT ───────────────────────────────────────────
    public Map<String, Object> generateReport(
            ScoreResponse scoreResult,
            List<AccountNode> nodes,
            List<EdgeData> edges) {

        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("score_result",      scoreResult);
            requestBody.put("nodes",             nodes);
            requestBody.put("edges",             edges);
            requestBody.put("reporting_entity",  "Demo Bank Ltd");
            requestBody.put("branch",            "Main Branch");

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<Map<String, Object>> entity =
                new HttpEntity<>(requestBody, headers);

            ResponseEntity<Map> response = restTemplate.exchange(
                inferenceUrl + "/report",
                HttpMethod.POST,
                entity,
                Map.class
            );

            return response.getBody();

        } catch (Exception e) {
            System.err.println("Report generation error: " + e.getMessage());
            return Map.of("error", "Report generation failed: " + e.getMessage());
        }
    }
}


// ─── service/TransactionService.java ────────────────────────────────
package com.aml.service;

import com.aml.model.*;
import com.aml.repository.Neo4jRepository;
import org.springframework.stereotype.Service;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.*;

@Service
public class TransactionService {

    private final Neo4jRepository neo4j;
    private final InferenceService inferenceService;
    private final SimpMessagingTemplate messagingTemplate;  // WebSocket

    // In-memory store of recent fraud alerts for polling fallback
    private final List<FraudAlert> recentAlerts = Collections.synchronizedList(
        new ArrayList<>()
    );

    public TransactionService(
            Neo4jRepository neo4j,
            InferenceService inferenceService,
            SimpMessagingTemplate messagingTemplate) {
        this.neo4j             = neo4j;
        this.inferenceService  = inferenceService;
        this.messagingTemplate = messagingTemplate;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> processTransaction(Transaction tx) {
        // Step 1: Store in Neo4j + update node properties
        neo4j.storeTransaction(tx);
        System.out.println("Stored: " + tx.getFromAccount()
                           + " → " + tx.getToAccount()
                           + " ₹" + tx.getAmount());

        // Step 2: Get 2-hop neighborhood from Neo4j
        Map<String, Object> neighborhood = neo4j.getNeighborhood(
            tx.getFromAccount(),
            tx.getToAccount(),
            tx.getTimestamp()
        );

        List<AccountNode> nodes = (List<AccountNode>)
            neighborhood.getOrDefault("nodes", new ArrayList<>());
        List<EdgeData> edges = (List<EdgeData>)
            neighborhood.getOrDefault("edges", new ArrayList<>());

        // If no related transactions found, just send the trigger itself
        if (edges.isEmpty()) {
            EdgeData triggerEdge = EdgeData.builder()
                .edgeId(tx.getTransactionId())
                .fromAccount(tx.getFromAccount())
                .toAccount(tx.getToAccount())
                .amount(tx.getAmount())
                .currency(tx.getCurrency() != null ? tx.getCurrency() : "INR")
                .paymentFormat(tx.getPaymentFormat() != null
                               ? tx.getPaymentFormat() : "NEFT")
                .timestamp(tx.getTimestamp())
                .isTrigger(true)
                .build();
            edges.add(triggerEdge);

            // Minimal node data from what we know
            AccountNode fromNode = AccountNode.builder()
                .accountId(tx.getFromAccount())
                .name(tx.getFromName() != null ? tx.getFromName() : "Unknown")
                .totalSent(tx.getAmount())
                .totalReceived(0.0)
                .txCountOut(1).txCountIn(0)
                .uniqueCounterparts(1)
                .isNewAccount(true)
                .dormancyScore(0.0)
                .build();

            AccountNode toNode = AccountNode.builder()
                .accountId(tx.getToAccount())
                .name(tx.getToName() != null ? tx.getToName() : "Unknown")
                .totalSent(0.0)
                .totalReceived(tx.getAmount())
                .txCountOut(0).txCountIn(1)
                .uniqueCounterparts(1)
                .isNewAccount(true)
                .dormancyScore(0.0)
                .build();

            nodes.add(fromNode);
            nodes.add(toNode);
        } else {
            // Mark the trigger edge
            for (EdgeData e : edges) {
                if (e.getEdgeId().equals(tx.getTransactionId())) {
                    e.setIsTrigger(true);
                }
            }
        }

        // Step 3: Call Python inference service
        ScoreRequest scoreRequest = ScoreRequest.builder()
            .triggerTransactionId(tx.getTransactionId())
            .nodes(nodes)
            .edges(edges)
            .build();

        ScoreResponse scoreResult = inferenceService.score(scoreRequest);
        System.out.println("Score: " + scoreResult.getTypology()
                           + " | " + scoreResult.getRiskLevel()
                           + " | " + scoreResult.getFraudScore());

        // Step 4: Update Neo4j with result
        neo4j.updateFraudResult(
            tx.getTransactionId(),
            scoreResult.getIsFraud(),
            scoreResult.getTypology(),
            scoreResult.getRiskLevel(),
            scoreResult.getFraudScore() != null ? scoreResult.getFraudScore() : 0.0
        );

        // Step 5: If fraud → create alert + push to frontend via WebSocket
        if (Boolean.TRUE.equals(scoreResult.getIsFraud())) {
            double totalAmount = edges.stream()
                .mapToDouble(EdgeData::getAmount).sum();

            List<String> accountIds = new ArrayList<>();
            nodes.forEach(n -> accountIds.add(n.getAccountId()));

            FraudAlert alert = FraudAlert.builder()
                .id(tx.getTransactionId())
                .timestamp(tx.getTimestamp())
                .typology(scoreResult.getTypology())
                .riskLevel(scoreResult.getRiskLevel())
                .fraudScore(scoreResult.getFraudScore())
                .triggerTransactionId(tx.getTransactionId())
                .graphData(scoreResult.getGraphData())
                .evidenceChain(scoreResult.getEvidenceChain())
                .riskBreakdown(scoreResult.getRiskBreakdown())
                .totalAmount(totalAmount)
                .accountsInvolved(accountIds)
                .build();

            recentAlerts.add(0, alert);  // newest first
            if (recentAlerts.size() > 100) {
                recentAlerts.remove(recentAlerts.size() - 1);
            }

            // Push via WebSocket to React frontend
            messagingTemplate.convertAndSend("/topic/fraud-alerts", alert);
            System.out.println("🚨 FRAUD ALERT sent: " + scoreResult.getTypology());
        }

        return Map.of(
            "transaction_id", tx.getTransactionId(),
            "status",         "processed",
            "is_fraud",       scoreResult.getIsFraud(),
            "typology",       scoreResult.getTypology(),
            "risk_level",     scoreResult.getRiskLevel()
        );
    }

    public List<FraudAlert> getRecentAlerts() {
        return new ArrayList<>(recentAlerts);
    }
}


// ─── controller/TransactionController.java ──────────────────────────
package com.aml.controller;

import com.aml.model.Transaction;
import com.aml.service.TransactionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class TransactionController {

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    // Transaction generator posts here
    @PostMapping("/transactions")
    public ResponseEntity<Map<String, Object>> receiveTransaction(
            @RequestBody Transaction transaction) {

        Map<String, Object> result = transactionService.processTransaction(transaction);
        return ResponseEntity.ok(result);
    }
}


// ─── controller/AlertController.java ────────────────────────────────
package com.aml.controller;

import com.aml.model.FraudAlert;
import com.aml.service.InferenceService;
import com.aml.service.TransactionService;
import com.aml.repository.Neo4jRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api")
public class AlertController {

    private final TransactionService transactionService;
    private final InferenceService inferenceService;
    private final Neo4jRepository neo4j;

    public AlertController(
            TransactionService transactionService,
            InferenceService inferenceService,
            Neo4jRepository neo4j) {
        this.transactionService = transactionService;
        this.inferenceService   = inferenceService;
        this.neo4j              = neo4j;
    }

    // Frontend polls this for recent fraud alerts
    @GetMapping("/alerts")
    public ResponseEntity<List<FraudAlert>> getAlerts(
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(
            transactionService.getRecentAlerts()
        );
    }

    // Frontend requests report generation
    @PostMapping("/alerts/{transactionId}/report")
    public ResponseEntity<Map<String, Object>> generateReport(
            @PathVariable String transactionId,
            @RequestBody Map<String, Object> body) {

        // Get the stored score result and graph data from request body
        @SuppressWarnings("unchecked")
        Map<String, Object> scoreResult = (Map<String, Object>)
            body.get("score_result");

        // For full report we need nodes and edges from the alert
        // Frontend sends these back from what it received in the alert
        @SuppressWarnings("unchecked")
        List<Object> nodesRaw = (List<Object>) body.get("nodes");
        @SuppressWarnings("unchecked")
        List<Object> edgesRaw = (List<Object>) body.get("edges");

        Map<String, Object> report = inferenceService.generateReport(
            null, null, null  // simplified - pass scoreResult directly
        );

        return ResponseEntity.ok(report);
    }
}


// ─── config/WebSocketConfig.java ────────────────────────────────────
package com.aml.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // Frontend subscribes to /topic/fraud-alerts
        config.enableSimpleBroker("/topic");
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Frontend connects to ws://localhost:8080/ws
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }
}


// ═══════════════════════════════════════════════════════════════════
// application.properties
// ═══════════════════════════════════════════════════════════════════
/*
spring.application.name=aml-backend

# Neo4j
neo4j.uri=bolt://localhost:7687
neo4j.username=neo4j
neo4j.password=password

# Python inference service
inference.url=http://localhost:8000

# Server
server.port=8080

# Logging
logging.level.com.aml=DEBUG
*/


// ═══════════════════════════════════════════════════════════════════
// pom.xml dependencies (add to your existing pom.xml)
// ═══════════════════════════════════════════════════════════════════
/*
<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-websocket</artifactId>
    </dependency>
    <dependency>
        <groupId>org.neo4j.driver</groupId>
        <artifactId>neo4j-java-driver</artifactId>
        <version>5.14.0</version>
    </dependency>
    <dependency>
        <groupId>com.fasterxml.jackson.core</groupId>
        <artifactId>jackson-databind</artifactId>
    </dependency>
    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <optional>true</optional>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-test</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
*/
