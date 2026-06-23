package io.github.yysf1949.rag.agent.builtin;

import io.github.yysf1949.rag.core.model.DocumentVersionMeta;
import io.github.yysf1949.rag.core.port.DocumentVersionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DocumentVersionTool} — 4 actions, validation, error surfacing.
 *
 * <p>Mirrors {@link KbVersionToolTest} (Phase 18 P2), but exercises the
 * extra {@code docId} dimension and the {@code sourceLabel} parameter on
 * publish.</p>
 */
class DocumentVersionToolTest {

    private DocumentVersionService documentVersionService;
    private DocumentVersionTool tool;

    @BeforeEach
    void setUp() {
        documentVersionService = mock(DocumentVersionService.class);
        tool = new DocumentVersionTool(documentVersionService);
    }

    @Test
    void listActionReturnsVersionsFromService() {
        List<DocumentVersionMeta> versions = List.of(
                new DocumentVersionMeta(2, "doc1",
                        DocumentVersionMeta.Status.ACTIVE,
                        Instant.EPOCH, Instant.EPOCH, 10, null),
                new DocumentVersionMeta(1, "doc1",
                        DocumentVersionMeta.Status.DEPRECATED,
                        Instant.EPOCH, Instant.EPOCH, 5, "v1"));
        when(documentVersionService.listVersions("t1", "kb1", "doc1")).thenReturn(versions);

        DocumentVersionResponse resp = tool.manage(
                new DocumentVersionRequest(DocumentVersionAction.LIST,
                        "t1", "kb1", "doc1", null, null));

        assertEquals(DocumentVersionAction.LIST, resp.action());
        assertEquals(2, resp.versions().size());
        assertEquals("OK", resp.message());
        assertNull(resp.activeVersion());
        verify(documentVersionService).listVersions("t1", "kb1", "doc1");
    }

    @Test
    void getActiveActionReturnsVersionIdFromService() {
        when(documentVersionService.getActiveVersion("t1", "kb1", "doc1"))
                .thenReturn(Optional.of(5L));

        DocumentVersionResponse resp = tool.manage(
                new DocumentVersionRequest(DocumentVersionAction.GET_ACTIVE,
                        "t1", "kb1", "doc1", null, null));

        assertEquals(DocumentVersionAction.GET_ACTIVE, resp.action());
        assertEquals(5L, resp.activeVersion());
        assertTrue(resp.versions().isEmpty());
        assertTrue(resp.message().contains("5"));
    }

    @Test
    void getActiveActionWhenEmptyReturnsNullActiveAndMessage() {
        when(documentVersionService.getActiveVersion("t1", "kb1", "doc1"))
                .thenReturn(Optional.empty());

        DocumentVersionResponse resp = tool.manage(
                new DocumentVersionRequest(DocumentVersionAction.GET_ACTIVE,
                        "t1", "kb1", "doc1", null, null));

        assertNull(resp.activeVersion());
        assertEquals("no active version", resp.message());
        assertTrue(resp.versions().isEmpty());
    }

    @Test
    void publishActionCallsServicePublishWithCorrectArgs() {
        DocumentVersionMeta meta = new DocumentVersionMeta(3, "doc1",
                DocumentVersionMeta.Status.ACTIVE,
                Instant.EPOCH, Instant.EPOCH, 7, "manual");
        when(documentVersionService.publish("t1", "kb1", "doc1", 3L, "manual"))
                .thenReturn(meta);

        DocumentVersionResponse resp = tool.manage(
                new DocumentVersionRequest(DocumentVersionAction.PUBLISH,
                        "t1", "kb1", "doc1", 3L, "manual"));

        verify(documentVersionService).publish("t1", "kb1", "doc1", 3L, "manual");
        assertEquals(DocumentVersionAction.PUBLISH, resp.action());
        assertEquals(3L, resp.activeVersion());
        assertTrue(resp.message().contains("3"));
    }

    @Test
    void publishActionReturnsMetaFromServiceInResponse() {
        DocumentVersionMeta meta = new DocumentVersionMeta(8, "doc1",
                DocumentVersionMeta.Status.ACTIVE,
                Instant.EPOCH, Instant.EPOCH, 12, "hotfix");
        when(documentVersionService.publish("t1", "kb1", "doc1", 8L, "hotfix"))
                .thenReturn(meta);

        DocumentVersionResponse resp = tool.manage(
                new DocumentVersionRequest(DocumentVersionAction.PUBLISH,
                        "t1", "kb1", "doc1", 8L, "hotfix"));

        assertEquals(1, resp.versions().size());
        assertSame(meta, resp.versions().get(0));
        assertNotNull(resp.versions().get(0));
        assertEquals(8L, resp.versions().get(0).versionId());
        assertEquals("doc1", resp.versions().get(0).docId());
    }

    @Test
    void rollbackActionCallsServiceRollbackWithCorrectArgs() {
        DocumentVersionMeta meta = new DocumentVersionMeta(1, "doc1",
                DocumentVersionMeta.Status.ACTIVE,
                Instant.EPOCH, Instant.EPOCH, 5, "v1");
        when(documentVersionService.rollback("t1", "kb1", "doc1", 1L))
                .thenReturn(meta);

        DocumentVersionResponse resp = tool.manage(
                new DocumentVersionRequest(DocumentVersionAction.ROLLBACK,
                        "t1", "kb1", "doc1", 1L, null));

        verify(documentVersionService).rollback("t1", "kb1", "doc1", 1L);
        assertEquals(DocumentVersionAction.ROLLBACK, resp.action());
        assertEquals(1L, resp.activeVersion());
        assertTrue(resp.message().contains("1"));
    }

    @Test
    void publishActionWithoutVersionIdThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> tool.manage(new DocumentVersionRequest(
                        DocumentVersionAction.PUBLISH, "t1", "kb1", "doc1", null, "label")));
        verify(documentVersionService, never())
                .publish(anyString(), anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    void rollbackActionWithoutVersionIdThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> tool.manage(new DocumentVersionRequest(
                        DocumentVersionAction.ROLLBACK, "t1", "kb1", "doc1", null, null)));
        verify(documentVersionService, never())
                .rollback(anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    void nullActionRejectedAtRequestConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> new DocumentVersionRequest(
                        null, "t1", "kb1", "doc1", null, null));
    }

    @Test
    void blankTenantKbOrDocIdRejectedAtRequestConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> new DocumentVersionRequest(
                        DocumentVersionAction.LIST, "", "kb1", "doc1", null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new DocumentVersionRequest(
                        DocumentVersionAction.LIST, "t1", "", "doc1", null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new DocumentVersionRequest(
                        DocumentVersionAction.LIST, "t1", "kb1", "", null, null));
        // also covers nulls (blank check rejects null too)
        assertThrows(IllegalArgumentException.class,
                () -> new DocumentVersionRequest(
                        DocumentVersionAction.LIST, null, "kb1", "doc1", null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new DocumentVersionRequest(
                        DocumentVersionAction.LIST, "t1", null, "doc1", null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new DocumentVersionRequest(
                        DocumentVersionAction.LIST, "t1", "kb1", null, null, null));
    }

}
