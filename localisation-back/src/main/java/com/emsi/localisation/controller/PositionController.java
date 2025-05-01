package com.emsi.localisation.controller;

import com.emsi.localisation.beans.Position;
import com.emsi.localisation.service.PositionService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/positions")
public class PositionController {
    private final PositionService positionService;

    public PositionController(PositionService positionService) {
        this.positionService = positionService;
    }

    @PostMapping
    public Position createPosition(@RequestBody Position position) {
        return positionService.createPosition(position);
    }

    @GetMapping
    public List<Position> getAllPositions() {
        return positionService.getAllPositions();
    }

    @GetMapping("/{uniqueId}")
    public List<Position> getPositionsByUniqueId(@PathVariable String uniqueId) {
        return positionService.getPositionsByUniqueId(uniqueId);
    }
}
