package com.aml.service;

import com.aml.model.*;
import com.aml.repository.Neo4jRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
public class TransactionService {

    private final Neo4jRepository neo4j;
    private final InferenceService inferenceService;
    private final SimpMessagingTemplate messagingTemplate;  // WebSocket
    private final KafkaTemplate<String, Transaction> kafkaTemplate;
    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);
    private final List<Transaction> bufferedTransactions = new ArrayList<>();
    private final Object bufferLock = new Object();

    @Value("${transactions.topic}")
    private String transactionsTopic;

    @Value("${transactions.batch.max-size}")
    private int maxBatchSize;

    @Value("${transactions.batch.flush-interval-ms}")
    private long flushIntervalMs;

    // In-memory store of recent fraud alerts for polling fallback
    private final List<FraudAlert> recentAlerts = Collections.synchronizedList(
            new ArrayList<>()
    );

    public TransactionService(
            Neo4jRepository neo4j,
            InferenceService inferenceService,
            SimpMessagingTemplate messagingTemplate,
            KafkaTemplate<String, Transaction> kafkaTemplate) {
        this.neo4j             = neo4j;
        this.inferenceService  = inferenceService;
        this.messagingTemplate = messagingTemplate;
        this.kafkaTemplate     = kafkaTemplate;
    }

    public Map<String, Object> enqueueTransaction(Transaction tx) {
        try {
            kafkaTemplate.send(transactionsTopic, tx.getTransactionId(), tx)
                    .get(10, TimeUnit.SECONDS);
            log.debug("Queued for Kafka buffer: {} → {} ₹{}",
                    tx.getFromAccount(), tx.getToAccount(), tx.getAmount());
            return Map.of(
                    "transaction_id", tx.getTransactionId(),
                    "status", "queued",
                    "buffer", "kafka",
                    "flush_interval_ms", flushIntervalMs
            );
        } catch (Exception e) {
            log.error("Failed to enqueue transaction {}. Falling back to direct processing.",
                    tx.getTransactionId(), e);
            return processTransactionDirectly(tx, e.getMessage());
        }
    }

    @KafkaListener(topics = "${transactions.topic}", groupId = "${spring.kafka.consumer.group-id}")
    public void bufferTransaction(Transaction tx) {
        boolean flushNow = false;
        int currentSize;

        synchronized (bufferLock) {
            bufferedTransactions.add(tx);
            currentSize = bufferedTransactions.size();
            if (currentSize >= maxBatchSize) {
                flushNow = true;
            }
        }

        log.debug("Buffered transaction {}. Pending batch size={}",
                tx.getTransactionId(), currentSize);

        if (flushNow) {
            flushBufferedTransactions();
        }
    }

    @Scheduled(fixedDelayString = "${transactions.batch.flush-interval-ms}")
    public void flushBufferedTransactions() {
        List<Transaction> batch = drainBufferedTransactions();
        if (batch.isEmpty()) {
            return;
        }

        try {
            List<Transaction> insertedTransactions = neo4j.storeTransactionsBatch(batch);
            log.debug("Flushed {} buffered transactions to Neo4j ({} new, {} duplicates skipped)",
                    batch.size(), insertedTransactions.size(), batch.size() - insertedTransactions.size());

            batch = insertedTransactions;
        } catch (Exception e) {
            log.error("Batch flush failed; re-queueing {} transactions", batch.size(), e);
            synchronized (bufferLock) {
                bufferedTransactions.addAll(0, batch);
            }
            return;
        }

        for (Transaction tx : batch) {
            try {
                analyzeStoredTransaction(tx);
            } catch (Exception e) {
                log.error("Post-persist analysis failed for transaction {}",
                        tx.getTransactionId(), e);
            }
        }
    }

    private List<Transaction> drainBufferedTransactions() {
        synchronized (bufferLock) {
            if (bufferedTransactions.isEmpty()) {
                return Collections.emptyList();
            }

            List<Transaction> batch = new ArrayList<>(bufferedTransactions);
            bufferedTransactions.clear();
            return batch;
        }
    }

    @SuppressWarnings("unchecked")
    private void analyzeStoredTransaction(Transaction tx) {
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
        log.debug("DEBUG neighborhood query returned:");
        log.debug("  Nodes count : " + nodes.size());
        log.debug("  Edges count : " + edges.size());
        for (EdgeData e : edges) {
            log.debug("  Edge: " + e.getFromAccount()
                    + " → " + e.getToAccount()
                    + " ₹" + e.getAmount()
                    + " id=" + e.getEdgeId());
        }
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
        log.debug("Score: " + scoreResult.getTypology()
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
                .rawGnnScore(scoreResult.getRawGnnScore())
                .confidence(scoreResult.getConfidence())
                .latencyMs(scoreResult.getLatencyMs())
                .triggerTransactionId(tx.getTransactionId())
                .graphData(scoreResult.getGraphData())
                .nodes(new ArrayList<>(nodes))
                .edges(new ArrayList<>(edges))
                .evidenceChain(scoreResult.getEvidenceChain())
                .riskBreakdown(scoreResult.getRiskBreakdown())
                .totalAmount(totalAmount)
                .accountsInvolved(accountIds)
                .build();

        recentAlerts.add(0, alert);  // newest first
        if (recentAlerts.size() > 100) {
            recentAlerts.remove(recentAlerts.size() - 1);
        }

        messagingTemplate.convertAndSend("/topic/fraud-alerts", alert);
        log.info("WebSocket alert published for tx {} typology={} risk={} score={}",
                tx.getTransactionId(), scoreResult.getTypology(),
                scoreResult.getRiskLevel(), scoreResult.getFraudScore());
    }

    public List<FraudAlert> getRecentAlerts() {
        return new ArrayList<>(recentAlerts);
    }

    public List<FraudAlert> getRecentAlerts(int limit) {
        int safeLimit = Math.max(limit, 0);
        synchronized (recentAlerts) {
            return recentAlerts.stream()
                    .limit(safeLimit)
                    .toList();
        }
    }

    public AlertPageResponse getRecentAlerts(int page, int limit) {
        int safeLimit = Math.max(limit, 1);
        int safePage = Math.max(page, 1);

        synchronized (recentAlerts) {
            int total = recentAlerts.size();
            int fromIndex = Math.min((safePage - 1) * safeLimit, total);
            int toIndex = Math.min(fromIndex + safeLimit, total);
            int totalPages = total == 0 ? 0 : (int) Math.ceil((double) total / safeLimit);

            return AlertPageResponse.builder()
                    .items(new ArrayList<>(recentAlerts.subList(fromIndex, toIndex)))
                    .page(safePage)
                    .limit(safeLimit)
                    .total(total)
                    .totalPages(totalPages)
                    .build();
        }
    }

    private Map<String, Object> processTransactionDirectly(
            Transaction tx,
            String kafkaError) {
        try {
            boolean inserted = neo4j.storeTransaction(tx);
            if (inserted) {
                analyzeStoredTransaction(tx);
            }

            return Map.of(
                    "transaction_id", tx.getTransactionId(),
                    "status", inserted ? "processed_direct" : "duplicate_skipped",
                    "buffer", "direct",
                    "kafka_error", kafkaError
            );
        } catch (Exception directException) {
            log.error("Direct processing fallback failed for transaction {}",
                    tx.getTransactionId(), directException);
            return Map.of(
                    "transaction_id", tx.getTransactionId(),
                    "status", "processing_failed",
                    "buffer", "direct",
                    "kafka_error", kafkaError,
                    "error", directException.getMessage()
            );
        }
    }
}
