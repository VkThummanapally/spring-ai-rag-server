package com.ai.rag.spring_ai_rag_server.loader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

@Component
public class PdfDataLoaderRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(PdfDataLoaderRunner.class);

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;
    private final ResourcePatternResolver resourcePatternResolver;

    public PdfDataLoaderRunner(VectorStore vectorStore, JdbcTemplate jdbcTemplate,
                               ResourcePatternResolver resourcePatternResolver) {
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
        this.resourcePatternResolver = resourcePatternResolver;
    }

    @Override
    public void run(String... args) throws Exception {
        initIngestionLogTable();

        Resource[] resources = resourcePatternResolver.getResources("classpath:pdf/*");
        if (resources.length == 0) {
            log.warn("No files found in classpath:pdf/ directory. Skipping ingestion.");
            return;
        }

        log.info("Found {} file(s) in classpath:pdf/ directory", resources.length);
        int skippedCount = 0;
        int ingestedCount = 0;

        for (Resource resource : resources) {
            if (!resource.exists() || !resource.isReadable()) {
                log.warn("Skipping unreadable resource: {}", resource.getFilename());
                continue;
            }

            String filename = resource.getFilename();
            String fileHash = computeSha256(resource);

            if (isAlreadyIngested(filename, fileHash)) {
                log.info("Skipping '{}' — already ingested with same content (hash: {})", filename, fileHash);
                skippedCount++;
                continue;
            }

            // If the file exists with a different hash, it was modified — remove old entries
            if (hasExistingEntry(filename)) {
                log.info("Detected modified file '{}'. Removing old ingestion record and re-ingesting.", filename);
                removeIngestionRecord(filename);
            }

            ingestDocument(resource, filename, fileHash);
            ingestedCount++;
        }

        log.info("Ingestion summary: {} file(s) ingested, {} file(s) skipped", ingestedCount, skippedCount);
    }

    /**
     * Creates the document_ingestion_log table if it doesn't exist.
     * Tracks which files have been ingested along with their SHA-256 hash
     * to detect modifications.
     */
    private void initIngestionLogTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS document_ingestion_log (
                    id SERIAL PRIMARY KEY,
                    filename VARCHAR(500) NOT NULL UNIQUE,
                    file_hash VARCHAR(64) NOT NULL,
                    chunk_count INTEGER NOT NULL,
                    ingested_at TIMESTAMP NOT NULL
                )
                """);
        log.info("Document ingestion log table initialized.");
    }

    /**
     * Computes a SHA-256 hash of the file content to detect changes.
     */
    private String computeSha256(Resource resource) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream is = resource.getInputStream()) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private boolean isAlreadyIngested(String filename, String fileHash) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM document_ingestion_log WHERE filename = ? AND file_hash = ?",
                Integer.class, filename, fileHash);
        return count != null && count > 0;
    }

    private boolean hasExistingEntry(String filename) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM document_ingestion_log WHERE filename = ?",
                Integer.class, filename);
        return count != null && count > 0;
    }

    private void removeIngestionRecord(String filename) {
        jdbcTemplate.update("DELETE FROM document_ingestion_log WHERE filename = ?", filename);
    }

    /**
     * Reads, splits, and ingests a single document into the vector store,
     * then records the ingestion in the log table.
     */
    private void ingestDocument(Resource resource, String filename, String fileHash) {
        long totalStart = System.currentTimeMillis();

        log.info("Loading document using TikaDocumentReader: {}", filename);
        long stepStart = System.currentTimeMillis();
        TikaDocumentReader documentReader = new TikaDocumentReader(resource);
        List<Document> documents = documentReader.get();
        log.info("Loaded '{}': {} page(s) in {} ms", filename, documents.size(),
                System.currentTimeMillis() - stepStart);

        log.info("Splitting '{}' into chunks...", filename);
        stepStart = System.currentTimeMillis();
        TokenTextSplitter textSplitter = TokenTextSplitter.builder().build();
        List<Document> splitDocuments = textSplitter.apply(documents);
        log.info("Split '{}' into {} chunks in {} ms", filename, splitDocuments.size(),
                System.currentTimeMillis() - stepStart);

        log.info("Writing {} chunks from '{}' into PGVector...", splitDocuments.size(), filename);
        stepStart = System.currentTimeMillis();
        vectorStore.accept(splitDocuments);
        log.info("Ingested '{}' into PGVector in {} ms", filename, System.currentTimeMillis() - stepStart);

        // Record successful ingestion
        jdbcTemplate.update(
                "INSERT INTO document_ingestion_log (filename, file_hash, chunk_count, ingested_at) VALUES (?, ?, ?, ?)",
                filename, fileHash, splitDocuments.size(), LocalDateTime.now());

        log.info("Recorded ingestion of '{}' (hash: {}, chunks: {}) — total: {} ms",
                filename, fileHash, splitDocuments.size(), System.currentTimeMillis() - totalStart);
    }
}
