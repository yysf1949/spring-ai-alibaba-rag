package io.github.yysf1949.rag.agent.store;

import io.github.yysf1949.rag.agent.store.entity.OrderEntity;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@Profile("mysql")
public interface JpaOrderRepository extends JpaRepository<OrderEntity, String> {
    Optional<OrderEntity> findByOrderIdAndTenantId(String orderId, String tenantId);
}