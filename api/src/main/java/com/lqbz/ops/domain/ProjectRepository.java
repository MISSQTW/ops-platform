package com.lqbz.ops.domain;
import org.springframework.data.jpa.repository.JpaRepository;
public interface ProjectRepository extends JpaRepository<Project, Long> {
  boolean existsByName(String name);
}
