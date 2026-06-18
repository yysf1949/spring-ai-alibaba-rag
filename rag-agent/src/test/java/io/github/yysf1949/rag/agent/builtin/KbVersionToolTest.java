package io.github.yysf1949.rag.agent.builtin;

import io.github.yysf1949.rag.core.exception.KbVersionNotFoundException;
import io.github.yysf1949.rag.core.model.KbVersionMeta;
import io.github.yysf1949.rag.core.port.KbVersionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link KbVersionTool} — 4 actions, validation, error surfacing.
 */
class KbVersionToolTest {

    private KbVersionService kbVersionService;
    private KbVersionTool tool;

    @BeforeEach
    void setUp() {
        kbVersionService = mock(KbVersionService.class);
        tool = new KbVersionTool(kbVersionService);
    }

    @Test
    void listActionReturnsVersions() {
        List<KbVersionMeta> versions = List.of(
                new KbVersionMeta(2, KbVersionMeta.Status.ACTIVE, Instant.EPOCH, Instant.EPOCH, 10, null),
                new KbVersionMeta(1, KbVersionMeta.Status.DEPRECATED, Instant.EPOCH, Instant.EPOCH, 5, "v1"));
        when(kbVersionService.listVersions("t1", "kb1")).thenReturn(versions);

        KbVersionResponse resp = tool.manage(
                new KbVersionRequest(KbVersionAction.LIST, "t1", "kb1", null));

        assertEquals(KbVersionAction.LIST, resp.action());
        assertEquals(2, resp.versions().size());
        assertEquals("OK", resp.message());
        assertNull(resp.activeVersionId());
    }

    @Test
    void getActiveActionReturnsVersionId() {
        when(kbVersionService.getActiveVersion("t1", "kb1")).thenReturn(Optional.of(5L));

        KbVersionResponse resp = tool.manage(
                new KbVersionRequest(KbVersionAction.GET_ACTIVE, "t1", "kb1", null));

        assertEquals(KbVersionAction.GET_ACTIVE, resp.action());
        assertEquals(5L, resp.activeVersionId());
        assertTrue(resp.versions().isEmpty());
    }

    @Test
    void getActiveActionWhenNoActiveReturnsNullAndMessage() {
        when(kbVersionService.getActiveVersion("t1", "kb1")).thenReturn(Optional.empty());

        KbVersionResponse resp = tool.manage(
                new KbVersionRequest(KbVersionAction.GET_ACTIVE, "t1", "kb1", null));

        assertNull(resp.activeVersionId());
        assertEquals("no active version", resp.message());
    }

    @Test
    void switchActionPublishesAndReturnsNewActive() {
        KbVersionResponse resp = tool.manage(
                new KbVersionRequest(KbVersionAction.SWITCH, "t1", "kb1", 3L));

        verify(kbVersionService).publish("t1", "kb1", 3L);
        assertEquals(KbVersionAction.SWITCH, resp.action());
        assertEquals(3L, resp.activeVersionId());
        assertTrue(resp.message().contains("3"));
    }

    @Test
    void switchActionWithoutVersionIdThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> tool.manage(new KbVersionRequest(KbVersionAction.SWITCH, "t1", "kb1", null)));
        verify(kbVersionService, never()).publish(anyString(), anyString(), anyLong());
    }

    @Test
    void rollbackActionCallsServiceRollback() {
        KbVersionResponse resp = tool.manage(
                new KbVersionRequest(KbVersionAction.ROLLBACK, "t1", "kb1", 1L));

        verify(kbVersionService).rollback("t1", "kb1", 1L);
        assertEquals(KbVersionAction.ROLLBACK, resp.action());
        assertEquals(1L, resp.activeVersionId());
    }

    @Test
    void rollbackPropagatesKbVersionNotFoundException() {
        Mockito.doThrow(new KbVersionNotFoundException("version 5 not found"))
                .when(kbVersionService).rollback("t1", "kb1", 5L);

        KbVersionNotFoundException ex = assertThrows(KbVersionNotFoundException.class,
                () -> tool.manage(
                        new KbVersionRequest(KbVersionAction.ROLLBACK, "t1", "kb1", 5L)));
        assertTrue(ex.getMessage().contains("version 5 not found"));
    }

    @Test
    void blankTenantIdRejectedAtRequestConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> new KbVersionRequest(KbVersionAction.LIST, "", "kb1", null));
        assertThrows(IllegalArgumentException.class,
                () -> new KbVersionRequest(KbVersionAction.LIST, "t1", "", null));
    }

    @Test
    void nullActionRejectedAtRequestConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> new KbVersionRequest(null, "t1", "kb1", null));
    }

    @Test
    void negativeVersionIdRejectedAtRequestConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> new KbVersionRequest(KbVersionAction.SWITCH, "t1", "kb1", -1L));
    }
}