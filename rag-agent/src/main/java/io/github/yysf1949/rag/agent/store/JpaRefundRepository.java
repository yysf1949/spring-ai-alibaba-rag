package io.github.yysf1949.rag.agent.store;

import io.github.yysf1949.rag.agent.store.entity.RefundEntity;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@Profile("mysql")
public interface JpaRefundRepository extends JpaRepository<RefundEntity, String> {
    Optional<RefundEntity> findByRefundIdAndTenantId(String refundId, String tenantId);
}