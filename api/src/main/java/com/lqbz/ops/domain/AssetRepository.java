package com.lqbz.ops.domain;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
public interface AssetRepository extends JpaRepository<Asset, Long> {
  long countByStatusNot(String status);
  List<Asset> findByProjectNameOrderById(String projectName);
}
