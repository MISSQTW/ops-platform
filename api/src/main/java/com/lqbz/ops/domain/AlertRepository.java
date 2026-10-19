package com.lqbz.ops.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AlertRepository extends JpaRepository<Alert, Long> {
  long countByStatus(String status);
  Optional<Alert> findByExternalSourceAndExternalAlertId(String externalSource, String externalAlertId);
}
