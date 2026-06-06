
package com.aml.repository;
import org.neo4j.driver.Record;
import com.aml.model.AccountNode;
import com.aml.model.EdgeData;
import com.aml.model.Transaction;
import org.neo4j.driver.*;
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

    private String getTimeFrom(String timestamp) {
        // During testing return 30 days ago to catch everything
        return LocalDateTime.now()
                .minus(30, ChronoUnit.DAYS)
                .format(FMT);
    }

    // ── STORE TRANSACTION ──────────────────────────────────────────
    public boolean storeTransaction(Transaction tx) {
        try (Session session = driver.session()) {
            return session.writeTransaction(t -> persistTransaction(t, tx));
        }
    }

    public List<Transaction> storeTransactionsBatch(List<Transaction> transactions) {
        if (transactions == null || transactions.isEmpty()) {
            return Collections.emptyList();
        }

        try (Session session = driver.session()) {
            return session.writeTransaction(t -> {
                List<Transaction> insertedTransactions = new ArrayList<>();
                for (Transaction tx : transactions) {
                    if (persistTransaction(t, tx)) {
                        insertedTransactions.add(tx);
                    }
                }
                return insertedTransactions;
            });
        }
    }

    private boolean persistTransaction(org.neo4j.driver.Transaction txContext,
                                       Transaction tx) {
        boolean alreadyExists = txContext.run("""
                MATCH ()-[r:TRANSFER {id: $txId}]->()
                RETURN count(r) > 0 AS exists
                """,
                Map.of("txId", tx.getTransactionId())
        ).single().get("exists").asBoolean(false);

        if (alreadyExists) {
            return false;
        }

        txContext.run("""
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
                        "accountId", tx.getFromAccount(),
                        "name", tx.getFromName() != null ? tx.getFromName() : "Unknown",
                        "amount", tx.getAmount(),
                        "timestamp", tx.getTimestamp(),
                        "dormancyScore", 0.0
                )
        );

        txContext.run("""
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
                        "accountId", tx.getToAccount(),
                        "name", tx.getToName() != null ? tx.getToName() : "Unknown",
                        "amount", tx.getAmount(),
                        "timestamp", tx.getTimestamp(),
                        "dormancyScore", 0.0
                )
        );

        txContext.run("""
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
                        "fromId", tx.getFromAccount(),
                        "toId", tx.getToAccount(),
                        "txId", tx.getTransactionId(),
                        "amount", tx.getAmount(),
                        "currency", tx.getCurrency() != null ? tx.getCurrency() : "INR",
                        "paymentFormat", tx.getPaymentFormat() != null ? tx.getPaymentFormat() : "NEFT",
                        "timestamp", tx.getTimestamp(),
                        "patternId", tx.getPatternId() != null ? tx.getPatternId() : ""
                )
        );

        txContext.run("""
                MATCH (a:Account {id: $fromId})-[:TRANSFER]->(neighbor)
                WITH a, count(DISTINCT neighbor) AS cnt
                SET a.unique_counterparts = cnt
                """,
                Map.of("fromId", tx.getFromAccount())
        );

        txContext.run("""
                MATCH (a:Account {id: $toId})<-[:TRANSFER]-(neighbor)
                WITH a, count(DISTINCT neighbor) AS cnt
                SET a.unique_counterparts = cnt
                """,
                Map.of("toId", tx.getToAccount())
        );
        return true;
    }


    // ── QUERY 2-HOP NEIGHBORHOOD ──────────────────────────────────
    // Returns all accounts and transactions within 2 hops
    // of either endpoint, using the prototype demo lookback window.
    public Map<String, Object> getNeighborhood(
            String fromAccount,
            String toAccount,
            String triggerTimestamp) {

        try (Session session = driver.session()) {
            return session.readTransaction(t -> {

                // --- 1. FETCH AND PARSE EDGES ---
                Result edgeResult = t.run("""
                    MATCH (seed:Account)
                    WHERE seed.id IN [$fromId, $toId]
                    MATCH path = (seed)-[:TRANSFER*1..2]-(neighbor:Account)
                    WHERE ALL(rel IN relationships(path)
                              WHERE rel.timestamp >= $timeFrom)
                    WITH collect(DISTINCT seed) + collect(DISTINCT neighbor) AS rawAccounts
                    UNWIND rawAccounts AS account
                    WITH collect(DISTINCT account) AS neighborhoodAccounts
                    UNWIND neighborhoodAccounts AS a
                    MATCH (a)-[r:TRANSFER]->(b:Account)
                    WHERE b IN neighborhoodAccounts
                    AND   r.timestamp >= $timeFrom
                    RETURN a.id AS fromAcc,
                           b.id AS toAcc,
                           r.id AS edgeId,
                           r.amount AS amount,
                           r.currency AS currency,
                           r.payment_format AS paymentFormat,
                           r.timestamp AS timestamp
                    """,
                        Map.of(
                                "fromId",   fromAccount,
                                "toId",     toAccount,
                                "timeFrom", getTimeFrom(triggerTimestamp)
                        )
                );

                List<EdgeData> edgeList = new ArrayList<>();
                Set<String> seenEdgeIds = new HashSet<>();

                while (edgeResult.hasNext()) {
                    Record rec = edgeResult.next();
                    String edgeId = rec.get("edgeId").asString("");

                    // Keep the uniqueness check you had originally
                    if (seenEdgeIds.contains(edgeId)) continue;
                    seenEdgeIds.add(edgeId);

                    edgeList.add(EdgeData.builder()
                            .edgeId(edgeId)
                            .fromAccount(rec.get("fromAcc").asString("")) // Now returns the proper String ID!
                            .toAccount(rec.get("toAcc").asString(""))     // Now returns the proper String ID!
                            .amount(rec.get("amount").asDouble(0.0))
                            .currency(rec.get("currency").asString("INR"))
                            .paymentFormat(rec.get("paymentFormat").asString("NEFT"))
                            .timestamp(rec.get("timestamp").asString(""))
                            .isTrigger(false)
                            .build()
                    );
                }

                // --- 2. FETCH AND PARSE NODES ---
                Result nodeResult = t.run("""
                    MATCH (seed:Account)
                    WHERE seed.id IN [$fromId, $toId]
                    MATCH path = (seed)-[:TRANSFER*1..2]-(neighbor:Account)
                    WHERE ALL(rel IN relationships(path)
                              WHERE rel.timestamp >= $timeFrom)
                    WITH collect(DISTINCT seed) + collect(DISTINCT neighbor) AS rawNodes
                    UNWIND rawNodes AS n
                    WITH DISTINCT n AS a
                    RETURN a.id AS accountId,
                           a.name AS name,
                           a.total_sent AS totalSent,
                           a.total_received AS totalReceived,
                           a.tx_count_out AS txCountOut,
                           a.tx_count_in AS txCountIn,
                           a.unique_counterparts AS uniqueCounterparts,
                           a.is_new_account AS isNewAccount,
                           a.dormancy_score AS dormancyScore,
                           a.last_tx_timestamp AS lastTx,
                           a.first_tx_timestamp AS firstTx
                    """,
                        Map.of(
                                "fromId",   fromAccount,
                                "toId",     toAccount,
                                "timeFrom", getTimeFrom(triggerTimestamp)
                        )
                );

                List<AccountNode> nodeList = new ArrayList<>();
                while (nodeResult.hasNext()) {
                    Record rec = nodeResult.next();
                    nodeList.add(AccountNode.builder()
                            .accountId(rec.get("accountId").asString(""))
                            .name(rec.get("name").asString("Unknown"))
                            .totalSent(rec.get("totalSent").asDouble(0.0))
                            .totalReceived(rec.get("totalReceived").asDouble(0.0))
                            .txCountOut(rec.get("txCountOut").asInt(0))
                            .txCountIn(rec.get("txCountIn").asInt(0))
                            .uniqueCounterparts(rec.get("uniqueCounterparts").asInt(0))
                            .isNewAccount(rec.get("isNewAccount").asBoolean(false))
                            .dormancyScore(rec.get("dormancyScore").asDouble(0.0))
                            .lastTxTimestamp(rec.get("lastTx").asString(null))
                            .firstTxTimestamp(rec.get("firstTx").asString(null))
                            .build()
                    );
                }

                // --- 3. BUNDLE AND RETURN ---
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
