package com.confectionery.crm.api;

import com.confectionery.crm.api.dto.ProductionTaskDto;
import com.confectionery.crm.domain.ProductionTaskStatus;
import com.confectionery.crm.service.ProductionTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/production-tasks")
@RequiredArgsConstructor
public class ProductionTaskApiController {

    private final ProductionTaskService productionTaskService;

    @GetMapping
    public List<ProductionTaskDto> list(@RequestParam(required = false) ProductionTaskStatus status) {
        var tasks = status == null ? productionTaskService.findAll() : productionTaskService.findByStatus(status);
        return tasks.stream().map(ProductionTaskDto::from).toList();
    }

    @PostMapping("/{id}/start")
    public ProductionTaskDto start(@PathVariable Long id, @RequestParam(required = false) String assignedTo) {
        return ProductionTaskDto.from(productionTaskService.start(id, assignedTo));
    }

    @PostMapping("/{id}/complete")
    public ProductionTaskDto complete(@PathVariable Long id) {
        return ProductionTaskDto.from(productionTaskService.complete(id));
    }
}
