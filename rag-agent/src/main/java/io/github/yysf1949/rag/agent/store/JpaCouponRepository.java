package io.github.yysf1949.rag.agent.store;

import io.github.yysf1949.rag.agent.store.entity.CouponEntity;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@Profile("mysql")
public interface JpaCouponRepository extends JpaRepository<CouponEntity, String> {
    List<CouponEntity> findByTenantIdAndUserIdAndStatus(String tenantId, String userId, String status);
}