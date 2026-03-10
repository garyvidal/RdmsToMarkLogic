package com.nativelogix.rdbms2marklogic.controller;

import com.nativelogix.rdbms2marklogic.model.relational.DbDatabase;
import com.nativelogix.rdbms2marklogic.model.requests.SchemaAnalysisRequest;
import com.nativelogix.rdbms2marklogic.service.PasswordEncryptionService;
import com.nativelogix.rdbms2marklogic.service.SchemaService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@CrossOrigin(origins = "http://localhost:5173", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.DELETE, RequestMethod.OPTIONS})
public class SchemaServiceController {

    private final SchemaService schemaService;
    private final PasswordEncryptionService encryptionService;

    @Autowired
    public SchemaServiceController(SchemaService schemaService, PasswordEncryptionService encryptionService) {
        this.schemaService = schemaService;
        this.encryptionService = encryptionService;
    }

    @GetMapping
    public ResponseEntity<String> hello() {
        return ResponseEntity.ok("API is running");
    }

    @GetMapping("/v1/health")
    public ResponseEntity<Map<String, String>> health() {
        Map<String, String> response = new HashMap<>();
        response.put("status", "API is running");
        response.put("timestamp", new java.util.Date().toString());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/v1/encrypt")
    public ResponseEntity<Map<String, String>> encrypt(@RequestBody Map<String, String> body) {
        String encrypted = encryptionService.encrypt(body.get("value"));
        return ResponseEntity.ok(Map.of("encrypted", encrypted));
    }

    @PostMapping("/v1/schemas")
    public ResponseEntity<DbDatabase> post(@RequestBody SchemaAnalysisRequest request) {
        try {
            return ResponseEntity.ok(schemaService.analyzeSchema(request));
        } catch (Exception ex) {
            return ResponseEntity.internalServerError().build();
        }
    }
}
