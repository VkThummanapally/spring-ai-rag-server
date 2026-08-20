# Spring AI RAG Server

A **Retrieval-Augmented Generation (RAG)** server built with Spring Boot, Spring AI, Ollama, and PostgreSQL (PGVector). It ingests documents (PDF, DOCX, TXT, etc.) into a vector database and answers questions based on their content using a local LLM.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                        Spring AI RAG Server                        │
│                         (localhost:8080)                            │
│                                                                    │
│  ┌──────────────────────┐         ┌──────────────────────────┐     │
│  │  PdfDataLoaderRunner │         │    RagChatController     │     │
│  │  (Startup Ingestion) │         │   POST /api/v1/rag/chat  │     │
│  └──────┬───────────────┘         └──────────┬───────────────┘     │
│         │                                    │                     │
│         │ 1. Read files from classpath:pdf/  │ 1. Receive question │
│         │ 2. Split into chunks               │ 2. Search vectors   │
│         │ 3. Compute SHA-256 hash            │ 3. Send context+    │
│         │ 4. Embed via Ollama                │    question to LLM  │
│         │ 5. Store in PGVector               │ 4. Return answer    │
│         │ 6. Log in ingestion table          │                     │
│         ▼                                    ▼                     │
│  ┌─────────────┐                    ┌─────────────┐               │
│  │   Ollama    │                    │   Ollama    │               │
│  │ all-minilm  │                    │ llama3.2:1b │               │
│  │ (Embedding) │                    │  (Chat LLM) │               │
│  └──────┬──────┘                    └─────────────┘               │
│         │                                                          │
│         ▼                                                          │
│  ┌──────────────────────────────────────────┐                      │
│  │          PostgreSQL + PGVector            │                      │
│  │                                          │                      │
│  │  vector_store              document_     │                      │
│  │  ├── id (UUID)             ingestion_log │                      │
│  │  ├── content (TEXT)        ├── filename   │                      │
│  │  ├── metadata (JSON)      ├── file_hash  │                      │
│  │  └── embedding (vector)   ├── chunk_count│                      │
│  │      (384 dims)           └── ingested_at│                      │
│  └──────────────────────────────────────────┘                      │
└─────────────────────────────────────────────────────────────────────┘
```

### RAG Flow — How it works

**Document Ingestion (on startup):**
```
Files in classpath:pdf/
    → Apache Tika reads file content (supports PDF, DOCX, TXT, etc.)
    → TokenTextSplitter splits content into chunks
    → SHA-256 hash computed per file (skip if already ingested)
    → Ollama all-minilm converts each chunk into a 384-dim vector
    → Vectors stored in PostgreSQL via PGVector extension
    → Ingestion recorded in document_ingestion_log table
```

**Chat Query (user request):**
```
User sends question via POST /api/v1/rag/chat
    → QuestionAnswerAdvisor searches PGVector for relevant chunks (similarity search)
    → Relevant chunks + user question sent to Ollama llama3.2:1b
    → LLM generates an answer grounded in the document context
    → Response returned to user
```

---

## Tech Stack

| Component | Version | Purpose |
|-----------|---------|---------|
| **Java** | 21+ | Runtime |
| **Spring Boot** | 4.1.0 | Application framework |
| **Spring AI** | 2.0.0 | AI/LLM integration framework |
| **Ollama** | Latest | Local LLM inference server |
| **llama3.2:1b** | 1B (~1.3GB) | Chat/language model for generating answers |
| **all-minilm** | ~45MB | Embedding model (384 dimensions) |
| **PostgreSQL** | 14+ | Relational database |
| **PGVector** | Extension | Vector similarity search in PostgreSQL |
| **Apache Tika** | 3.x | Document parsing (PDF, DOCX, TXT, etc.) |
| **Maven** | Wrapper included | Build tool |

### Key Dependencies

| Dependency | Description |
|------------|-------------|
| `spring-boot-starter-webmvc` | REST API (Tomcat embedded) |
| `spring-ai-starter-model-ollama` | Ollama chat + embedding integration |
| `spring-ai-starter-vector-store-pgvector` | PGVector vector store auto-configuration |
| `spring-ai-tika-document-reader` | Read PDF/DOCX/TXT documents via Apache Tika |
| `spring-ai-vector-store-advisor` | QuestionAnswerAdvisor for RAG pattern |

---

## Prerequisites

Make sure the following are installed before running the server:

### 1. Java 21+

```bash
java -version
```

Download from [https://adoptium.net/](https://adoptium.net/) if not installed.

### 2. PostgreSQL with PGVector Extension

**Install PostgreSQL** (v14 or later): [https://www.postgresql.org/download/](https://www.postgresql.org/download/)

**Install PGVector extension:**

```sql
-- Connect to your PostgreSQL instance and run:
CREATE EXTENSION IF NOT EXISTS vector;
```

> On Windows, if using the installer, PGVector may need to be installed separately.
> See: [https://github.com/pgvector/pgvector](https://github.com/pgvector/pgvector)

**Default connection settings** (configured in `application.yml`):

| Property | Value |
|----------|-------|
| Host | `localhost` |
| Port | `5432` |
| Database | `vectordb` |
| Username | `postgres` |
| Password | `root` |

### 3. Ollama

**Install Ollama:** [https://ollama.com/download](https://ollama.com/download)

**Verify it's running:**

```bash
curl http://localhost:11434
# Should return: Ollama is running
```

**Pull the required models:**

```bash
# Embedding model (~45 MB) — required for document vectorization
ollama pull all-minilm

# Chat model (~1.3 GB) — required for answering questions
ollama pull llama3.2:1b
```

**Verify models are available:**

```bash
ollama list
```

Expected output:
```
NAME                    SIZE
llama3.2:1b:latest      1.3 GB
all-minilm:latest       45 MB
```

---

## Installation & Setup

### 1. Clone the Repository

```bash
git clone <repository-url>
cd spring-ai-rag-server
```

### 2. Add Documents

Place your documents in the resources directory:

```
src/main/resources/pdf/
├── document1.pdf
├── document2.docx
├── notes.txt
└── ...          ← Any file format supported by Apache Tika
```

> **Supported formats:** PDF, DOCX, XLSX, PPTX, TXT, HTML, RTF, and more.
> See [Apache Tika supported formats](https://tika.apache.org/3.0.0/formats.html).

### 3. Configure (Optional)

Edit `src/main/resources/application.yml` if your setup differs from defaults:

```yaml
spring:
  application:
    name: spring-ai-rag-server

  datasource:
    url: jdbc:postgresql://localhost:5432/vectordb
    username: postgres
    password: root
    driver-class-name: org.postgresql.Driver

  ai:
    vectorstore:
      pgvector:
        initialize-schema: true
        index-type: HNSW
        distance-type: COSINE_DISTANCE
        dimensions: 384

    ollama:
      chat:
        model: llama3.2:1b
        options:
          keep-alive: 5m
      embedding:
        model: all-minilm
        options:
          keep-alive: 5m
```

### 4. Build

```bash
./mvnw clean package -DskipTests
```

### 5. Run

```bash
./mvnw spring-boot:run
```

The server starts on **http://localhost:8080**.

On first startup, the ingestion pipeline runs automatically:
1. Reads all files from `classpath:pdf/`
2. Computes SHA-256 hash for each file
3. Splits documents into chunks
4. Embeds chunks via Ollama (`all-minilm`)
5. Stores vectors in PGVector
6. Logs ingestion in `document_ingestion_log` table

> **Note:** First-time ingestion may take several minutes depending on document size and hardware (CPU vs GPU).

---

## API Reference

### Chat Endpoint

Ask questions about your ingested documents.

```
POST /api/v1/rag/chat
Content-Type: text/plain

<your question here>
```

**Example using curl:**

```bash
curl -X POST http://localhost:8080/api/v1/rag/chat \
  -H "Content-Type: text/plain" \
  -d "Summarize the document"
```

**Example using PowerShell:**

```powershell
Invoke-RestMethod -Uri "http://localhost:8080/api/v1/rag/chat" `
  -Method Post `
  -ContentType "text/plain" `
  -Body "What are the key concepts discussed in the document?"
```

---

## Database Tables

The application creates two tables automatically:

### `vector_store` (managed by Spring AI PGVector)

Stores document chunks and their vector embeddings.

| Column | Type | Description |
|--------|------|-------------|
| `id` | UUID | Primary key |
| `content` | TEXT | Document chunk text |
| `metadata` | JSON | Source metadata (filename, page, etc.) |
| `embedding` | vector(384) | Vector embedding from all-minilm |

### `document_ingestion_log` (managed by application)

Tracks which documents have been ingested to avoid duplicates.

| Column | Type | Description |
|--------|------|-------------|
| `id` | SERIAL | Primary key |
| `filename` | VARCHAR(500) | Original filename (unique) |
| `file_hash` | VARCHAR(64) | SHA-256 hash of file content |
| `chunk_count` | INTEGER | Number of chunks produced |
| `ingested_at` | TIMESTAMP | When the file was ingested |

### Smart Ingestion Behavior

| Scenario | Action |
|----------|--------|
| New file added to `pdf/` | ✅ Ingested |
| File unchanged since last run | ⏭️ Skipped (same SHA-256 hash) |
| File modified since last run | 🔄 Re-ingested (hash mismatch detected) |

---

## Project Structure

```
spring-ai-rag-server/
├── src/main/java/com/ai/rag/spring_ai_rag_server/
│   ├── SpringAiRagServerApplication.java      # Main application entry point
│   ├── controller/
│   │   └── RagChatController.java             # REST API — POST /api/v1/rag/chat
│   └── loader/
│       └── PdfDataLoaderRunner.java           # Startup document ingestion pipeline
├── src/main/resources/
│   ├── application.yml                 # Configuration
│   └── pdf/                                   # Place documents here
│       └── *.pdf, *.docx, *.txt, ...
├── pom.xml                                    # Maven dependencies
├── mvnw / mvnw.cmd                            # Maven wrapper
└── README.md
```

---

## Troubleshooting

### Ollama connection refused

```
Connection refused: localhost/127.0.0.1:11434
```

**Fix:** Start Ollama — run `ollama serve` or launch the Ollama desktop app.

### Model not found

```
model "mistral" not found
```

**Fix:** Ensure the correct models are pulled and configured in `application.yml`:

```bash
ollama pull llama3
ollama pull mxbai-embed-large
```

### Dimension mismatch

```
ERROR: expected 1536 dimensions, not 1024
```

**Fix:** The `vector_store` table was created with a different dimension. Drop and let it recreate:

```sql
DROP TABLE IF EXISTS vector_store;
```

### Re-ingest all documents

To force re-ingestion of all documents, clear both tables:

```sql
DROP TABLE IF EXISTS vector_store;
DROP TABLE IF EXISTS document_ingestion_log;
```

---

## License

This project is for educational and development purposes.
