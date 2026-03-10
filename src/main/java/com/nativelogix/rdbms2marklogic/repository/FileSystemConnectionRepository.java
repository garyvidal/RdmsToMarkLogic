package com.nativelogix.rdbms2marklogic.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nativelogix.rdbms2marklogic.model.Connection;
import com.nativelogix.rdbms2marklogic.model.SavedConnection;
import com.nativelogix.rdbms2marklogic.service.PasswordEncryptionService;
import org.springframework.stereotype.Repository;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
public class FileSystemConnectionRepository implements ConnectionRepository {

    private final Path connectionsDir;
    private final ObjectMapper objectMapper;
    private final PasswordEncryptionService encryptionService;

    public FileSystemConnectionRepository(PasswordEncryptionService encryptionService) {
        this.encryptionService = encryptionService;
        this.objectMapper = new ObjectMapper();
        String userHome = System.getProperty("user.home");
        this.connectionsDir = Paths.get(userHome, ".rdbms2marklogic", "connections");

        try {
            Files.createDirectories(connectionsDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create connections directory: " + e.getMessage(), e);
        }
    }

    /**
     * Saves a connection with the password encrypted at rest.
     * The returned object retains the plaintext password for in-memory use.
     */
    @Override
    public SavedConnection save(SavedConnection savedConnection) {
        try {
            SavedConnection toWrite = withEncryptedPassword(savedConnection);
            Path filePath = connectionsDir.resolve(savedConnection.getName() + ".json");
            objectMapper.writeValue(filePath.toFile(), toWrite);
            return savedConnection; // return original (plaintext password in memory)
        } catch (IOException e) {
            throw new RuntimeException("Failed to save connection: " + e.getMessage(), e);
        }
    }

    /**
     * Updates an existing connection. If the new password is blank, the stored
     * password is preserved so the user doesn't have to re-enter it on every edit.
     * Handles name changes by deleting the old file.
     */
    @Override
    public SavedConnection update(String originalName, SavedConnection updated) {
        String newPassword = updated.getConnection().getPassword();
        if (newPassword == null || newPassword.isBlank()) {
            // Keep existing password
            findByName(originalName).ifPresent(existing ->
                    updated.getConnection().setPassword(existing.getConnection().getPassword()));
        }
        // Delete old file if the connection was renamed
        if (!originalName.equals(updated.getName())) {
            delete(originalName);
        }
        return save(updated);
    }

    @Override
    public Optional<SavedConnection> findByName(String name) {
        Path filePath = connectionsDir.resolve(name + ".json");
        if (!Files.exists(filePath)) {
            return Optional.empty();
        }
        try {
            return Optional.of(readAndMigrate(name, filePath.toFile()));
        } catch (IOException e) {
            throw new RuntimeException("Failed to read connection: " + e.getMessage(), e);
        }
    }

    @Override
    public List<SavedConnection> findAll() {
        try {
            if (!Files.exists(connectionsDir)) {
                return new ArrayList<>();
            }
            return Files.list(connectionsDir)
                    .filter(path -> path.toString().endsWith(".json"))
                    .map(path -> {
                        String filename = path.getFileName().toString();
                        String name = filename.substring(0, filename.length() - 5);
                        try {
                            return readAndMigrate(name, path.toFile());
                        } catch (IOException e) {
                            throw new RuntimeException("Failed to read connection file: " + path, e);
                        }
                    })
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new RuntimeException("Failed to list connections: " + e.getMessage(), e);
        }
    }

    @Override
    public void delete(String name) {
        try {
            Path filePath = connectionsDir.resolve(name + ".json");
            Files.deleteIfExists(filePath);
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete connection: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean exists(String name) {
        return Files.exists(connectionsDir.resolve(name + ".json"));
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Reads a connection file and returns a SavedConnection with the password
     * decrypted in memory. Migrates legacy formats transparently:
     *   - bare Connection object → wrapped in SavedConnection
     *   - missing id → assigned and persisted
     *   - plaintext password → re-saved with encryption
     */
    private SavedConnection readAndMigrate(String name, File file) throws IOException {
        JsonNode root = objectMapper.readTree(file);
        SavedConnection sc;

        if (root.has("connection")) {
            sc = objectMapper.treeToValue(root, SavedConnection.class);
        } else {
            // Old format — bare Connection object; wrap it
            Connection connection = objectMapper.treeToValue(root, Connection.class);
            sc = new SavedConnection(UUID.randomUUID().toString(), name, null, connection);
        }

        if (sc.getId() == null || sc.getId().isBlank()) {
            sc.setId(UUID.randomUUID().toString());
        }

        // Decrypt password for in-memory use; detect legacy plaintext for migration
        String storedPassword = sc.getConnection() != null ? sc.getConnection().getPassword() : null;
        boolean isLegacyPlaintext = storedPassword != null && !storedPassword.isEmpty()
                && !storedPassword.startsWith(PasswordEncryptionService.ENC_PREFIX);

        if (sc.getConnection() != null) {
            sc.getConnection().setPassword(encryptionService.decrypt(storedPassword));
        }

        // Persist migration: re-save if format changed (this encrypts any plaintext password)
        boolean formatMigrated = !root.has("connection") || (sc.getId() != null && !root.has("id"));
        if (isLegacyPlaintext || formatMigrated) {
            save(sc);
        }

        return sc;
    }

    /**
     * Returns a shallow copy of the SavedConnection with the password encrypted,
     * suitable for writing to disk. The original object is not modified.
     */
    private SavedConnection withEncryptedPassword(SavedConnection sc) {
        if (sc.getConnection() == null) return sc;
        Connection original = sc.getConnection();
        Connection encrypted = objectMapper.convertValue(original, Connection.class);
        encrypted.setPassword(encryptionService.encrypt(original.getPassword()));
        return new SavedConnection(sc.getId(), sc.getName(), sc.getEnvironment(), encrypted);
    }
}
