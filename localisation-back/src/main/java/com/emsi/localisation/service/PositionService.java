package com.emsi.localisation.service;

import com.emsi.localisation.beans.Position;
import com.emsi.localisation.dao.PositionDao;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

@Service
public class PositionService {
    private final PositionDao positionDao;

    public PositionService(PositionDao positionDao) {
        this.positionDao = positionDao;
    }

    public Position createPosition(Position position) {
        position.setDate(new Date());
        return positionDao.save(position);
    }

    public List<Position> getAllPositions() {
        return positionDao.findAllByOrderByDateDesc();
    }

    public List<Position> getPositionsByUniqueId(String uniqueId) {
        return positionDao.findByUniqueIdOrderByDateDesc(uniqueId);
    }
}
