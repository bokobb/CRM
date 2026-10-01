package com.confectionery.crm.service;

import com.confectionery.crm.domain.Employee;
import com.confectionery.crm.domain.ProductionBrigadeMember;
import com.confectionery.crm.domain.ProductionRequest;
import com.confectionery.crm.domain.ProductionRequestStatus;
import com.confectionery.crm.repository.EmployeeRepository;
import com.confectionery.crm.repository.ProductionBrigadeMemberRepository;
import com.confectionery.crm.repository.ProductionRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Бригада заявки на сырьё: технолог каждый день отмечает, кто где работал. Сотрудник за день —
 * только в одной бригаде (среди неотменённых заявок с той же датой документа).
 */
@Service
@RequiredArgsConstructor
public class ProductionBrigadeService {

    private final ProductionRequestRepository requestRepository;
    private final EmployeeRepository employeeRepository;
    private final ProductionBrigadeMemberRepository memberRepository;

    @Transactional
    public void setBrigade(Long requestId, Collection<Long> employeeIds) {
        // Блокировка строки заявки — как у остальных изменений (ProductionRequestRepository.findByIdForUpdate).
        ProductionRequest request = requestRepository.findByIdForUpdate(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Заявка не найдена"));
        if (request.getStatus() == ProductionRequestStatus.CANCELLED) {
            throw new IllegalStateException("Заявка отменена — бригаду не назначить");
        }
        Set<Long> wanted = new LinkedHashSet<>(employeeIds == null ? Set.of() : employeeIds);
        Set<Long> current = request.getBrigade().stream().map(m -> m.getEmployee().getId()).collect(Collectors.toSet());

        for (ProductionBrigadeMember other : memberRepository.findForDay(request.getDocumentDate(), ProductionRequestStatus.CANCELLED)) {
            if (!other.getRequest().getId().equals(requestId) && wanted.contains(other.getEmployee().getId())) {
                throw new IllegalStateException(other.getEmployee().getFullName() + " уже в бригаде заявки №"
                        + other.getRequest().getId() + " («" + other.getRequest().getTitle() + "») в этот день");
            }
        }

        request.getBrigade().removeIf(m -> !wanted.contains(m.getEmployee().getId()));
        for (Long id : wanted) {
            if (current.contains(id)) continue;
            Employee e = employeeRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Сотрудник не найден"));
            if (!e.isActive()) {
                throw new IllegalStateException(e.getFullName() + " уволен — в бригаду не добавить");
            }
            ProductionBrigadeMember m = new ProductionBrigadeMember();
            m.setRequest(request);
            m.setEmployee(e);
            request.getBrigade().add(m);
        }
        requestRepository.save(request);
    }
}
