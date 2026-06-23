package io.github.yysf1949.rag.pipeline.version;

import javax.sql.DataSource;

/**
 * H2 dialect of {@link JdbcKbVersionService}. DDL uses H2's native types
 * (CLOB for free-form text; no {@code ENGINE} clause — H2 ignores it).
 */
public class H2KbVersionService extends JdbcKbVersionService {

    private static final String SCHEMA_DDL = """
            CREATE TABLE IF NOT EXISTS kb_version (
              tenant_id    VARCHAR(128) NOT NULL,
              kb_id        VARCHAR(128) NOT NULL,
              version_id   BIGINT       NOT NULL,
              status       VARCHAR(16)  NOT NULL,
              created_at   TIMESTAMP    NOT NULL,
              published_at TIMESTAMP    NULL,
              doc_count    INT          NOT NULL DEFAULT 0,
              source_label VARCHAR(255) NULL,
              PRIMARY KEY (tenant_id, kb_id, version_id)
            )
            """;

    private static final String ACTIVE_SCHEMA_DDL = """
            CREATE TABLE IF NOT EXISTS kb_active_version (
              tenant_id  VARCHAR(128) NOT NULL,
              kb_id      VARCHAR(128) NOT NULL,
              version_id BIGINT       NOT NULL,
              PRIMARY KEY (tenant_id, kb_id)
            )
            """;

    public H2KbVersionService(DataSource dataSource) {
        super(dataSource);
    }

    @Override
    protected String schemaDdl() {
        return SCHEMA_DDL;
    }

    @Override
    protected String activeSchemaDdl() {
        return ACTIVE_SCHEMA_DDL;
    }
}