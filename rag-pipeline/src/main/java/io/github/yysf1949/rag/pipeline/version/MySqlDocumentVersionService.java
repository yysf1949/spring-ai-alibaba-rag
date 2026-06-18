package io.github.yysf1949.rag.pipeline.version;

import javax.sql.DataSource;

/**
 * MySQL dialect of {@link JdbcDocumentVersionService}. DDL uses
 * {@code ENGINE=InnoDB} and {@code utf8mb4} charset.
 */
public class MySqlDocumentVersionService extends JdbcDocumentVersionService {

    private static final String SCHEMA_DDL = """
            CREATE TABLE IF NOT EXISTS kb_doc_version (
              tenant_id    VARCHAR(128) NOT NULL,
              kb_id        VARCHAR(128) NOT NULL,
              doc_id       VARCHAR(255) NOT NULL,
              version_id   BIGINT       NOT NULL,
              status       VARCHAR(16)  NOT NULL,
              created_at   TIMESTAMP    NOT NULL,
              published_at TIMESTAMP    NULL,
              chunk_count  INT          NOT NULL DEFAULT 0,
              source_label VARCHAR(255) NULL,
              PRIMARY KEY (tenant_id, kb_id, doc_id, version_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """;

    private static final String ACTIVE_SCHEMA_DDL = """
            CREATE TABLE IF NOT EXISTS kb_doc_active_version (
              tenant_id  VARCHAR(128) NOT NULL,
              kb_id      VARCHAR(128) NOT NULL,
              doc_id     VARCHAR(255) NOT NULL,
              version_id BIGINT       NOT NULL,
              PRIMARY KEY (tenant_id, kb_id, doc_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """;

    public MySqlDocumentVersionService(DataSource dataSource) {
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
