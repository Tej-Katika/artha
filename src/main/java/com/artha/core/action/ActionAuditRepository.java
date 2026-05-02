package com.artha.core.action;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ActionAuditRepository extends JpaRepository<ActionAudit, UUID> {

    List<ActionAudit> findByUserIdOrderByStartedAtDesc(UUID userId);

    List<ActionAudit> findBySessionIdOrderByStartedAtAsc(String sessionId);

    List<ActionAudit> findByActionNameAndOutcome(String actionName, ActionOutcome outcome);
}
