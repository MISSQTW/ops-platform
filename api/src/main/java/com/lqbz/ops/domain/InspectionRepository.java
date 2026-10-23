package com.lqbz.ops.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InspectionRepository extends JpaRepository<Inspection, Long> {

    Optional<Inspection> findFirstByName(String name);
}
