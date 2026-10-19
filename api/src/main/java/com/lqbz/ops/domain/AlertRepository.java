package com.lqbz.ops.domain;
import org.springframework.data.jpa.repository.JpaRepository;
public interface AlertRepository extends JpaRepository<Alert, Long> { long countByStatus(String status); }
