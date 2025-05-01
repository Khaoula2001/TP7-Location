package com.emsi.localisation.dao;

import com.emsi.localisation.beans.Position;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PositionDao extends JpaRepository<Position, Long> {
    List<Position> findAllByOrderByDateDesc();
    List<Position> findByUniqueIdOrderByDateDesc(String uniqueId);
}
