package com.lqbz.ops.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InspectionItemRepository extends JpaRepository<InspectionItem, Long> {
  List<InspectionItem> findByInspectionIdOrderById(Long inspectionId);
}
