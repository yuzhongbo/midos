package com.zhongbo.mindos.assistant.memory;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.document.Field;
import org.apache.lucene.index.VectorSimilarityFunction;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * In-memory hybrid retrieval that combines Lucene BM25 with cosine vector search.
 * It uses Lucene top-k retrieval on both sides so query-time ranking avoids a full scan.
 */
public class HybridSearchService implements Closeable {

    private static final String FIELD_ID = "id";
    private static final String FIELD_CONTENT = "content";
    private static final String FIELD_VECTOR = "embedding";
    private static final double DEFAULT_ALPHA = 0.6d;
    private static final double DEFAULT_BETA = 0.4d;
    private static final int DEFAULT_CANDIDATE_MULTIPLIER = 4;

    private final EmbeddingService embeddingService;
    private final double alpha;
    private final double beta;
    private final int candidateMultiplier;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final StandardAnalyzer analyzer;
    private final Directory directory;
    private final IndexWriter indexWriter;
    private final SearcherManager searcherManager;

    public HybridSearchService(EmbeddingService embeddingService) {
        this(embeddingService, DEFAULT_ALPHA, DEFAULT_BETA, DEFAULT_CANDIDATE_MULTIPLIER);
    }

    public HybridSearchService(EmbeddingService embeddingService, double alpha, double beta) {
        this(embeddingService, alpha, beta, DEFAULT_CANDIDATE_MULTIPLIER);
    }

    public HybridSearchService(EmbeddingService embeddingService,
                               double alpha,
                               double beta,
                               int candidateMultiplier) {
        this.embeddingService = Objects.requireNonNull(embeddingService, "embeddingService");
        if (alpha < 0.0d || beta < 0.0d || (alpha + beta) <= 0.0d) {
            throw new IllegalArgumentException("alpha and beta must be non-negative and not both zero");
        }
        this.alpha = alpha;
        this.beta = beta;
        this.candidateMultiplier = Math.max(1, candidateMultiplier);
        this.analyzer = new StandardAnalyzer();
        this.directory = new ByteBuffersDirectory();
        try {
            IndexWriterConfig config = new IndexWriterConfig(analyzer);
            config.setSimilarity(new BM25Similarity());
            this.indexWriter = new IndexWriter(directory, config);
            this.searcherManager = new SearcherManager(indexWriter, null);
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to initialize hybrid search index", ex);
        }
    }

    public void upsert(String documentId, String content) {
        upsert(documentId, content, embeddingService.embed(content));
    }

    public void upsert(String documentId, String content, float[] embedding) {
        if (documentId == null || documentId.isBlank()) {
            throw new IllegalArgumentException("documentId must not be blank");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
        lock.writeLock().lock();
        try {
            Document document = new Document();
            document.add(new StringField(FIELD_ID, documentId, Field.Store.YES));
            document.add(new TextField(FIELD_CONTENT, content, Field.Store.YES));
            if (embedding != null && embedding.length > 0) {
                document.add(new KnnFloatVectorField(FIELD_VECTOR, Arrays.copyOf(embedding, embedding.length), VectorSimilarityFunction.COSINE));
            }
            indexWriter.updateDocument(new Term(FIELD_ID, documentId), document);
            indexWriter.commit();
            searcherManager.maybeRefreshBlocking();
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to upsert hybrid search document", ex);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void remove(String documentId) {
        if (documentId == null || documentId.isBlank()) {
            return;
        }
        lock.writeLock().lock();
        try {
            indexWriter.deleteDocuments(new Term(FIELD_ID, documentId));
            indexWriter.commit();
            searcherManager.maybeRefreshBlocking();
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to remove hybrid search document", ex);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<HybridSearchResult> search(String query, int topK) {
        if (query == null || query.isBlank() || topK <= 0) {
            return List.of();
        }
        lock.readLock().lock();
        try {
            searcherManager.maybeRefreshBlocking();
            IndexSearcher searcher = searcherManager.acquire();
            try {
                int candidateLimit = Math.max(topK, topK * candidateMultiplier);
                TopDocs bm25TopDocs = searcher.search(buildBm25Query(query), candidateLimit);
                float[] queryEmbedding = embeddingService.embed(query);
                TopDocs vectorTopDocs = hasVector(queryEmbedding)
                        ? searcher.search(new KnnFloatVectorQuery(FIELD_VECTOR, queryEmbedding, candidateLimit), candidateLimit)
                        : TopDocsCollector.empty();
                return mergeAndRank(searcher, bm25TopDocs, vectorTopDocs, topK);
            } finally {
                searcherManager.release(searcher);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to execute hybrid search", ex);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void close() throws IOException {
        lock.writeLock().lock();
        try {
            searcherManager.close();
            indexWriter.close();
            directory.close();
            analyzer.close();
        } finally {
            lock.writeLock().unlock();
        }
    }

    private Query buildBm25Query(String queryText) {
        QueryParser parser = new QueryParser(FIELD_CONTENT, analyzer);
        try {
            String escaped = QueryParser.escape(queryText);
            return parser.parse(escaped.isBlank() ? "\"\"" : escaped);
        } catch (ParseException ex) {
            throw new IllegalArgumentException("Invalid query: " + queryText, ex);
        }
    }

    private List<HybridSearchResult> mergeAndRank(IndexSearcher searcher,
                                                  TopDocs bm25TopDocs,
                                                  TopDocs vectorTopDocs,
                                                  int topK) throws IOException {
        Map<Integer, Float> normalizedBm25 = normalizeScores(bm25TopDocs);
        Map<Integer, Float> normalizedVector = normalizeScores(vectorTopDocs);
        Map<String, CandidateScore> merged = new HashMap<>();
        merge(searcher, bm25TopDocs.scoreDocs, normalizedBm25, merged, true);
        merge(searcher, vectorTopDocs.scoreDocs, normalizedVector, merged, false);

        Comparator<HybridSearchResult> comparator = Comparator
                .comparingDouble(HybridSearchResult::finalScore)
                .thenComparing(HybridSearchResult::documentId);
        PriorityQueue<HybridSearchResult> heap = new PriorityQueue<>(topK, comparator);
        for (CandidateScore candidate : merged.values()) {
            double finalScore = alpha * candidate.bm25Score + beta * candidate.vectorScore;
            HybridSearchResult result = new HybridSearchResult(
                    candidate.documentId,
                    candidate.content,
                    candidate.bm25Score,
                    candidate.vectorScore,
                    finalScore
            );
            if (heap.size() < topK) {
                heap.offer(result);
                continue;
            }
            HybridSearchResult weakest = heap.peek();
            if (weakest != null && comparator.compare(result, weakest) > 0) {
                heap.poll();
                heap.offer(result);
            }
        }
        List<HybridSearchResult> ranked = new ArrayList<>(heap);
        ranked.sort(Comparator.comparingDouble(HybridSearchResult::finalScore).reversed()
                .thenComparing(HybridSearchResult::documentId));
        return ranked;
    }

    private void merge(IndexSearcher searcher,
                       ScoreDoc[] scoreDocs,
                       Map<Integer, Float> normalizedScores,
                       Map<String, CandidateScore> merged,
                       boolean lexical) throws IOException {
        for (ScoreDoc scoreDoc : scoreDocs) {
            Document document = searcher.storedFields().document(scoreDoc.doc);
            String documentId = document.get(FIELD_ID);
            if (documentId == null || documentId.isBlank()) {
                continue;
            }
            CandidateScore candidate = merged.computeIfAbsent(documentId,
                    ignored -> new CandidateScore(documentId, document.get(FIELD_CONTENT)));
            float score = normalizedScores.getOrDefault(scoreDoc.doc, 0.0f);
            if (lexical) {
                candidate.bm25Score = Math.max(candidate.bm25Score, score);
            } else {
                candidate.vectorScore = Math.max(candidate.vectorScore, score);
            }
        }
    }

    private Map<Integer, Float> normalizeScores(TopDocs topDocs) {
        if (topDocs == null || topDocs.scoreDocs == null || topDocs.scoreDocs.length == 0) {
            return Map.of();
        }
        float maxScore = 0.0f;
        for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
            if (scoreDoc.score > maxScore) {
                maxScore = scoreDoc.score;
            }
        }
        if (maxScore <= 0.0f) {
            return Map.of();
        }
        Map<Integer, Float> normalized = new HashMap<>();
        for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
            normalized.put(scoreDoc.doc, scoreDoc.score / maxScore);
        }
        return normalized;
    }

    private boolean hasVector(float[] vector) {
        return vector != null && vector.length > 0;
    }

    public record HybridSearchResult(String documentId,
                                     String content,
                                     double bm25Score,
                                     double vectorScore,
                                     double finalScore) {
    }

    private static final class CandidateScore {
        private final String documentId;
        private final String content;
        private float bm25Score;
        private float vectorScore;

        private CandidateScore(String documentId, String content) {
            this.documentId = documentId;
            this.content = content == null ? "" : content;
        }
    }

    private static final class TopDocsCollector {
        private static TopDocs empty() {
            return new TopDocs(new org.apache.lucene.search.TotalHits(0L, org.apache.lucene.search.TotalHits.Relation.EQUAL_TO), new ScoreDoc[0]);
        }
    }
}
