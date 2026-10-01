package com.confectionery.crm.service;

import com.confectionery.crm.domain.Employee;
import com.confectionery.crm.repository.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Импорт сотрудников из 1С (кнопка «Загрузить из 1С» в меню «Сотрудники»). 1С — источник истины
 * для ФИО, должности, телефона, ИИН, даты приёма, признака «уволен» и месячного оклада (регистр
 * «Плановые начисления», последняя запись по каждому виду расчёта); ставку за смену и группу
 * затрат CRM ведёт сама и импорт их не трогает.
 *
 * <p>Структура кадровых справочников в Бухгалтерии для Казахстана разная от редакции к редакции,
 * поэтому сущности ищутся по списку кандидатов, а поля — по нескольким именам; чего нет, то
 * просто не заполняется. В нашей базе (проверено 01.10.2026): «СотрудникиОрганизаций» (текущая
 * должность, даты приёма/увольнения, Актуальность), «ФизическиеЛица» (ИИН =
 * ИдентификационныйКодЛичности, дата рождения), «ДолжностиОрганизаций»; контактная информация в
 * OData не опубликована — телефонов из 1С нет. Один человек = одно физлицо: если в 1С у него несколько элементов
 * «Сотрудники» (уволили и приняли снова), в CRM он один, ключ — физлицо.
 *
 * <p>Заведённые до импорта вручную сотрудники (без oneCKey) сопоставляются по ФИО (совпадение
 * фамилии и имени), чтобы не задвоить их и не потерять историю бригад и ставки.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OneCEmployeeImportService {

    private static final String EMPTY_GUID = "00000000-0000-0000-0000-000000000000";

    private static final List<String> EMPLOYEE_ENTITIES = List.of("Catalog_СотрудникиОрганизаций", "Catalog_Сотрудники");
    private static final List<String> PERSON_ENTITIES = List.of("Catalog_ФизическиеЛица");
    private static final List<String> POSITION_ENTITIES = List.of("Catalog_ДолжностиОрганизаций", "Catalog_Должности");
    private static final String SALARY_ENTITY = "InformationRegister_ПлановыеНачисленияРаботниковОрганизаций";
    private static final List<String> HISTORY_ENTITIES = List.of(
            "InformationRegister_РаботникиОрганизаций", "InformationRegister_КадроваяИсторияСотрудников");

    private final OneCClient oneCClient;
    private final EmployeeRepository employeeRepository;

    @Transactional
    public ImportResult importAll() {
        if (!oneCClient.isConfigured()) {
            throw new IllegalStateException("Подключение к 1С не настроено (crm.onec.base-url/username/password)");
        }
        List<JsonNode> employees = fetchFirst(EMPLOYEE_ENTITIES);
        if (employees == null) {
            throw new IllegalStateException("В 1С не найден справочник сотрудников (Catalog_Сотрудники) — "
                    + "проверьте, что он включён в состав OData");
        }
        Map<String, JsonNode> persons = byRef(fetchFirst(PERSON_ENTITIES));
        Map<String, String> positionNames = new HashMap<>();
        byRef(fetchFirst(POSITION_ENTITIES)).forEach((k, v) -> positionNames.put(k, text(v, "Description")));
        Map<String, History> history = buildHistory(fetchFirst(HISTORY_ENTITIES));
        Map<String, BigDecimal> salaries = buildSalaries(oneCClient.fetchAllOrNull(SALARY_ENTITY));

        // Группируем элементы «Сотрудники» по физлицу: действующий важнее уволенного.
        Map<String, Candidate> byPerson = new LinkedHashMap<>();
        for (JsonNode e : employees) {
            if (e.path("IsFolder").asBoolean(false)) continue;
            String ref = text(e, "Ref_Key");
            String personKey = text(e, "ФизическоеЛицо_Key", "Физлицо_Key", "ФизЛицо_Key");
            JsonNode person = personKey == null ? null : persons.get(personKey);
            History h = history.get(ref);

            String name = person != null ? text(person, "Description") : null;
            if (name == null) name = text(e, "Description");
            if (name == null) continue;

            String positionKey = firstKeyLike(e, "Должност");
            if (positionKey == null && h != null) positionKey = h.positionKey;
            String position = positionKey == null ? null : positionNames.get(positionKey);

            String phone = phone(e);
            if (phone == null && person != null) phone = phone(person);

            String iin = person != null ? text(person, "ИдентификационныйКодЛичности", "ИИН", "ИНН") : null;
            LocalDate born = person != null ? date(person, "ДатаРождения") : null;
            if (iin == null) iin = text(e, "ИИН");

            LocalDate hired = date(e, "ДатаПриемаНаРаботу", "ДатаПриема");
            if (hired == null && h != null) hired = h.hired;

            LocalDate fired = date(e, "ДатаУвольнения");
            boolean dismissed = e.path("DeletionMark").asBoolean(false)
                    || e.path("ВАрхиве").asBoolean(false)
                    || e.path("Уволен").asBoolean(false)
                    || (e.has("Актуальность") && !e.path("Актуальность").asBoolean(true))
                    || (fired != null && !fired.isAfter(LocalDate.now()))
                    || (h != null && h.dismissed);

            Candidate c = new Candidate(personKey != null ? personKey : ref, name.trim().replaceAll("\\s+", " "), position, phone, iin,
                    hired, born, salaries.get(ref), !dismissed);
            byPerson.merge(c.key, c, (a, b) -> b.active && !a.active ? b : a);
        }

        List<Employee> unlinked = new ArrayList<>(employeeRepository.findAll().stream()
                .filter(x -> x.getOneCKey() == null).toList());
        int created = 0, updated = 0, matched = 0;
        for (Candidate c : byPerson.values()) {
            Employee emp = employeeRepository.findByOneCKey(c.key).orElse(null);
            if (emp == null) {
                emp = unlinked.stream().filter(x -> sameName(x.getFullName(), c.name)).findFirst().orElse(null);
                if (emp != null) {
                    unlinked.remove(emp);
                    matched++;
                } else {
                    emp = new Employee();
                    created++;
                }
                emp.setOneCKey(c.key);
            } else {
                updated++;
            }
            // ФИО из 1С — только новым и переименованным: у сопоставленных в CRM бывает полнее/аккуратнее
            // (в 1С есть «БИЛАЛОВ ...» капсом и без отчества).
            if (emp.getFullName() == null || !sameName(emp.getFullName(), c.name)) emp.setFullName(c.name);
            if (c.position != null) emp.setPosition(c.position);
            if (c.phone != null) emp.setPhone(c.phone);
            if (c.iin != null) emp.setIin(c.iin);
            if (c.hired != null) emp.setHiredAt(c.hired);
            if (c.born != null) emp.setBirthDate(c.born);
            if (c.salary != null) emp.setMonthlySalary(c.salary);
            emp.setActive(c.active);
            employeeRepository.save(emp);
        }
        long active = byPerson.values().stream().filter(c -> c.active).count();
        log.info("Импорт сотрудников из 1С: всего {} (работают {}), новых {}, обновлено {}, сопоставлено по ФИО {}",
                byPerson.size(), active, created, updated, matched);
        return new ImportResult(byPerson.size(), (int) active, created, updated, matched);
    }

    public record ImportResult(int total, int active, int created, int updated, int matchedByName) {
    }

    private record Candidate(String key, String name, String position, String phone, String iin, LocalDate hired,
                             LocalDate born, BigDecimal salary, boolean active) {
    }

    private static final class History {
        String positionKey;
        LocalDate hired;
        LocalDate lastPeriod;
        boolean dismissed;
    }

    /** По каждому сотруднику: последняя должность, первая дата (приём) и уволен ли последним событием. */
    private static Map<String, History> buildHistory(List<JsonNode> rows) {
        Map<String, History> result = new HashMap<>();
        if (rows == null) return result;
        // Регистр, подчинённый регистратору (РаботникиОрганизаций), отдаёт наборы записей: строки в RecordSet.
        List<JsonNode> records = new ArrayList<>();
        for (JsonNode row : rows) {
            if (row.has("RecordSet")) row.path("RecordSet").forEach(records::add);
            else records.add(row);
        }
        for (JsonNode r : records) {
            if (r.has("Active") && !r.path("Active").asBoolean(true)) continue;
            String employeeKey = text(r, "Сотрудник_Key", "Работник_Key", "ФизЛицо_Key");
            LocalDate period = date(r, "Period", "Период");
            if (employeeKey == null || period == null) continue;
            History h = result.computeIfAbsent(employeeKey, k -> new History());
            if (h.hired == null || period.isBefore(h.hired)) h.hired = period;
            if (h.lastPeriod == null || !period.isBefore(h.lastPeriod)) {
                h.lastPeriod = period;
                String event = text(r, "ВидСобытия", "ПричинаИзмененияСостояния", "ВидСобытия_Type");
                h.dismissed = event != null && event.contains("Увол");
                String pos = firstKeyLike(r, "Должност");
                if (pos != null) h.positionKey = pos;
            }
        }
        return result;
    }

    /** Сотрудник → оклад в месяц: по каждому виду расчёта последняя (по Period) запись, активные суммируются. */
    private static Map<String, BigDecimal> buildSalaries(List<JsonNode> rows) {
        Map<String, JsonNode> last = new HashMap<>();
        if (rows != null) {
            for (JsonNode row : rows) {
                List<JsonNode> records = new ArrayList<>();
                if (row.has("RecordSet")) row.path("RecordSet").forEach(records::add);
                else records.add(row);
                for (JsonNode r : records) {
                    String employeeKey = text(r, "Сотрудник_Key");
                    String period = text(r, "Period");
                    if (employeeKey == null || period == null) continue;
                    String key = employeeKey + "|" + text(r, "ВидРасчета_Key");
                    JsonNode prev = last.get(key);
                    if (prev == null || text(prev, "Period").compareTo(period) <= 0) last.put(key, r);
                }
            }
        }
        Map<String, BigDecimal> result = new HashMap<>();
        last.forEach((key, r) -> {
            if (r.has("Active") && !r.path("Active").asBoolean(true)) return;
            BigDecimal size = r.path("Размер").isNumber() ? r.path("Размер").decimalValue() : BigDecimal.ZERO;
            if (size.signum() > 0) result.merge(key.substring(0, key.indexOf('|')), size, BigDecimal::add);
        });
        return result;
    }

    private List<JsonNode> fetchFirst(List<String> entities) {
        for (String entity : entities) {
            List<JsonNode> rows = oneCClient.fetchAllOrNull(entity);
            if (rows != null) return rows;
        }
        return null;
    }

    private static Map<String, JsonNode> byRef(List<JsonNode> rows) {
        Map<String, JsonNode> map = new HashMap<>();
        if (rows != null) rows.forEach(r -> {
            String ref = text(r, "Ref_Key");
            if (ref != null) map.put(ref, r);
        });
        return map;
    }

    /** Телефон из табличной части «КонтактнаяИнформация» (тип «Телефон») или из поля «Телефон». */
    private static String phone(JsonNode node) {
        for (JsonNode ci : node.path("КонтактнаяИнформация")) {
            String type = text(ci, "Тип");
            if (type != null && type.contains("Телефон")) {
                String value = text(ci, "Представление", "НомерТелефона");
                if (value != null) return value;
            }
        }
        return text(node, "Телефон", "ТелефонМобильный");
    }

    private static String firstKeyLike(JsonNode node, String part) {
        for (var it = node.properties().iterator(); it.hasNext(); ) {
            var f = it.next();
            if (f.getKey().contains(part) && f.getKey().endsWith("_Key")) {
                String v = f.getValue().asText("");
                if (!v.isBlank() && !EMPTY_GUID.equals(v)) return v;
            }
        }
        return null;
    }

    private static String text(JsonNode node, String... names) {
        for (String n : names) {
            JsonNode v = node.get(n);
            if (v == null || v.isNull() || v.isContainer()) continue;
            String s = v.asText("").trim();
            if (!s.isEmpty() && !EMPTY_GUID.equals(s)) return s;
        }
        return null;
    }

    private static LocalDate date(JsonNode node, String... names) {
        String s = text(node, names);
        if (s == null || s.length() < 10 || s.startsWith("0001-01-01")) return null;
        try {
            return LocalDate.parse(s.substring(0, 10));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Совпадают фамилия и имя (отчество в CRM могли не писать). */
    static boolean sameName(String a, String b) {
        String[] x = tokens(a), y = tokens(b);
        if (x.length == 0 || y.length == 0) return false;
        int n = Math.min(Math.min(x.length, y.length), 2);
        if (n == 1 && (x.length > 1 || y.length > 1)) return false;
        return Arrays.equals(Arrays.copyOf(x, n), Arrays.copyOf(y, n));
    }

    private static String[] tokens(String s) {
        if (s == null) return new String[0];
        String t = s.toLowerCase().replace('ё', 'е').replaceAll("[^\\p{L}\\s]", " ").trim();
        return t.isEmpty() ? new String[0] : t.split("\\s+");
    }
}
