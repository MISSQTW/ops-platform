package com.lqbz.ops.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AlertHandlingEventRepository extends JpaRepository<AlertHandlingEvent, Long> {
  List<AlertHandlingEvent> findByAlertIdOrderByOccurredAtAscIdAsc(Long alertId);
}
