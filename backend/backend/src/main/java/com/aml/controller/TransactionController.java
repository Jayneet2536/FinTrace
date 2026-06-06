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

        Map<String, Object> result = transactionService.enqueueTransaction(transaction);
        return ResponseEntity.ok(result);
    }
}
