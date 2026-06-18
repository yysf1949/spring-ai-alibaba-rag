package io.github.yysf1949.rag.agent.builtin.store;

import io.github.yysf1949.rag.agent.builtin.port.ComplaintRepositoryPort;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

@Component
public class InMemoryComplaintRepository implements ComplaintRepositoryPort {

    private final ConcurrentHashMap<String, ComplaintRecord> complaints = new ConcurrentHashMap<>();

    @Override
    public ComplaintRecord save(ComplaintRecord complaint) {
        complaints.put(key(complaint.tenantId(), complaint.complaintId()), complaint);
        return complaint;
    }

    @Override
    public Optional<ComplaintRecord> findByIdAndTenant(String complaintId, String tenantId) {
        return Optional.ofNullable(complaints.get(key(tenantId, complaintId)));
    }

    private static String key(String tenantId, String complaintId) {
        return tenantId + ":" + complaintId;
    }
}