package interview.guide.modules.knowledgebase.service;

import interview.guide.modules.knowledgebase.repository.VectorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("KnowledgeBaseVectorService tests")
@SuppressWarnings("unchecked")
class KnowledgeBaseVectorServiceTest {

    private static final double DEFAULT_MIN_SCORE = 0.0;

    private KnowledgeBaseVectorService vectorService;

    @Mock
    private VectorStore vectorStore;

    @Mock
    private VectorRepository vectorRepository;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        vectorService = new KnowledgeBaseVectorService(vectorStore, vectorRepository);
    }

    private String generateLongContent(int paragraphs) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < paragraphs; i++) {
            builder.append("Paragraph ").append(i).append(' ')
                .append("Spring Boot and Spring AI are used to build an interview assistant platform. ")
                .append("PostgreSQL with pgvector stores vectors for semantic search. ")
                .append("Redis streams drive asynchronous processing for resume analysis and knowledge base tasks. ")
                .append("This paragraph is intentionally long so TokenTextSplitter can create chunks. ")
                .append("The system also stores files in S3 compatible object storage such as MinIO. \n\n");
        }
        return builder.toString();
    }

    private List<Document> createDocuments(int count, String kbId) {
        List<Document> documents = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Map<String, Object> metadata = new HashMap<>();
            if (kbId != null) {
                metadata.put("kb_id", kbId);
            }
            documents.add(new Document("content-" + i, metadata));
        }
        return documents;
    }

    private Document createDocumentWithLongKbId(Long kbId) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("kb_id", kbId);
        return new Document("content", metadata);
    }

    @Nested
    @DisplayName("vectorizeAndStore")
    class VectorizeAndStoreTests {

        @Test
        void storesChunksAndDeletesOldVectorsFirst() {
            Long knowledgeBaseId = 1L;

            vectorService.vectorizeAndStore(knowledgeBaseId, generateLongContent(20));

            verify(vectorRepository).deleteByKnowledgeBaseId(knowledgeBaseId);
            verify(vectorStore, atLeastOnce()).add(anyList());

            var order = inOrder(vectorRepository, vectorStore);
            order.verify(vectorRepository).deleteByKnowledgeBaseId(knowledgeBaseId);
            order.verify(vectorStore, atLeastOnce()).add(anyList());
        }

        @Test
        void storesMetadataAndRespectsBatchSize() {
            Long knowledgeBaseId = 2L;
            ArgumentCaptor<List<Document>> batchCaptor = ArgumentCaptor.forClass(List.class);

            vectorService.vectorizeAndStore(knowledgeBaseId, generateLongContent(200));

            verify(vectorStore, atLeastOnce()).add(batchCaptor.capture());
            assertFalse(batchCaptor.getAllValues().isEmpty());

            for (List<Document> batch : batchCaptor.getAllValues()) {
                assertTrue(batch.size() <= 10, "batch size should be <= 10");
                for (Document document : batch) {
                    assertEquals(knowledgeBaseId.toString(), document.getMetadata().get("kb_id"));
                }
            }
        }

        @Test
        void wrapsVectorStoreFailures() {
            doThrow(new RuntimeException("vector store unavailable"))
                .when(vectorStore).add(anyList());

            RuntimeException exception = assertThrows(
                RuntimeException.class,
                () -> vectorService.vectorizeAndStore(1L, generateLongContent(10))
            );

            assertTrue(exception.getMessage().contains("向量化知识库失败") || exception.getMessage().contains("鍚戦噺鍖栫煡璇嗗簱澶辫触"));
        }

        @Test
        void skipsAddForEmptyContent() {
            vectorService.vectorizeAndStore(1L, "");

            verify(vectorRepository).deleteByKnowledgeBaseId(1L);
            verify(vectorStore, never()).add(anyList());
        }
    }

    @Nested
    @DisplayName("similaritySearch")
    class SimilaritySearchTests {

        @Test
        void buildsSearchRequestWithoutFilter() {
            String query = "java backend";
            int topK = 5;
            List<Document> mockResults = createDocuments(3, null);
            ArgumentCaptor<SearchRequest> requestCaptor = ArgumentCaptor.forClass(SearchRequest.class);
            when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(mockResults);

            List<Document> results = vectorService.similaritySearch(query, null, topK, DEFAULT_MIN_SCORE);

            assertEquals(mockResults, results);
            verify(vectorStore).similaritySearch(requestCaptor.capture());
            assertEquals(query, requestCaptor.getValue().getQuery());
            assertEquals(topK, requestCaptor.getValue().getTopK());
            assertFalse(requestCaptor.getValue().hasFilterExpression());
        }

        @Test
        void buildsSearchRequestWithKnowledgeBaseFilter() {
            ArgumentCaptor<SearchRequest> requestCaptor = ArgumentCaptor.forClass(SearchRequest.class);
            List<Document> mockResults = createDocuments(2, "1");
            when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(mockResults);

            List<Document> results = vectorService.similaritySearch("spring", List.of(1L, 2L), 10, DEFAULT_MIN_SCORE);

            assertEquals(mockResults, results);
            verify(vectorStore).similaritySearch(requestCaptor.capture());
            assertTrue(requestCaptor.getValue().hasFilterExpression());
            String filterExpression = requestCaptor.getValue().getFilterExpression().toString();
            assertTrue(filterExpression.contains("kb_id"));
            assertTrue(filterExpression.contains("IN"));
            assertTrue(filterExpression.contains("1"));
            assertTrue(filterExpression.contains("2"));
        }

        @Test
        void appliesMinimumSimilarityThreshold() {
            ArgumentCaptor<SearchRequest> requestCaptor = ArgumentCaptor.forClass(SearchRequest.class);
            when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

            vectorService.similaritySearch("rag", null, 8, 0.42);

            verify(vectorStore).similaritySearch(requestCaptor.capture());
            assertEquals(0.42, requestCaptor.getValue().getSimilarityThreshold());
        }

        @Test
        void normalizesNonPositiveTopKToOne() {
            List<Document> mockResults = createDocuments(2, null);
            ArgumentCaptor<SearchRequest> requestCaptor = ArgumentCaptor.forClass(SearchRequest.class);
            when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(mockResults);

            List<Document> results = vectorService.similaritySearch("test", null, 0, DEFAULT_MIN_SCORE);

            assertEquals(mockResults, results);
            verify(vectorStore).similaritySearch(requestCaptor.capture());
            assertEquals(1, requestCaptor.getValue().getTopK());
        }

        @Test
        void returnsEmptyListWhenVectorStoreReturnsNull() {
            when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(null);

            List<Document> results = vectorService.similaritySearch("missing", null, 5, DEFAULT_MIN_SCORE);

            assertTrue(results.isEmpty());
        }

        @Test
        void fallsBackToLocalFilteringWhenPrimarySearchFails() {
            List<Document> fallbackResults = new ArrayList<>();
            fallbackResults.add(createDocumentWithLongKbId(100L));
            fallbackResults.add(createDocumentWithLongKbId(100L));
            fallbackResults.add(createDocumentWithLongKbId(200L));

            when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenThrow(new RuntimeException("primary failure"))
                .thenReturn(fallbackResults);

            List<Document> results = vectorService.similaritySearch("python", List.of(100L), 2, DEFAULT_MIN_SCORE);

            assertEquals(2, results.size());
            assertTrue(results.stream().allMatch(document -> Long.valueOf(document.getMetadata().get("kb_id").toString()) == 100L));
        }

        @Test
        void wrapsFailuresWhenFallbackAlsoFails() {
            when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenThrow(new RuntimeException("primary failure"))
                .thenThrow(new RuntimeException("fallback failure"));

            RuntimeException exception = assertThrows(
                RuntimeException.class,
                () -> vectorService.similaritySearch("broken", null, 5, DEFAULT_MIN_SCORE)
            );

            assertTrue(exception.getMessage().contains("向量搜索失败") || exception.getMessage().contains("鍚戦噺鎼滅储澶辫触"));
        }
    }

    @Nested
    @DisplayName("deleteByKnowledgeBaseId")
    class DeleteByKnowledgeBaseIdTests {

        @Test
        void deletesVectors() {
            when(vectorRepository.deleteByKnowledgeBaseId(1L)).thenReturn(5);

            vectorService.deleteByKnowledgeBaseId(1L);

            verify(vectorRepository, times(1)).deleteByKnowledgeBaseId(1L);
        }

        @Test
        void swallowsDeleteFailures() {
            doThrow(new RuntimeException("db error"))
                .when(vectorRepository).deleteByKnowledgeBaseId(1L);

            assertDoesNotThrow(() -> vectorService.deleteByKnowledgeBaseId(1L));
        }
    }
}
