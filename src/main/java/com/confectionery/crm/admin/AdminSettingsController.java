package com.confectionery.crm.admin;

import com.confectionery.crm.api.dto.WhatsAppSessionDto;
import com.confectionery.crm.domain.*;
import com.confectionery.crm.repository.*;
import com.confectionery.crm.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static com.confectionery.crm.admin.AdminSupport.*;

/**
 * Настройки по макету (часть 4), только администратор: компания (шапка накладной), сайт, магазин для клиентов, окна
 * выдачи (часы, заказов в окне, дни выдачи — PickupSlotService), WhatsApp (статус шлюза, QR, отключить), 1С (что и
 * куда уходит, очередь и ошибки, что забирается из 1С, загрузки по кнопке), уведомления (кому какой push приходит —
 * как в коде NotificationService.notifyRole, журнал), входы и пароли. Каждая карточка сохраняется своей формой.
 */
@Controller
@RequiredArgsConstructor
public class AdminSettingsController {

    private static final AdminFormat F = new AdminFormat();
    private static final String NB = AdminFormat.NB;
    static final String[] WD = {"пн", "вт", "ср", "чт", "пт", "сб", "вс"};

    private final CompanyProfileService profileService;
    private final PickupSlotService pickupSlotService;
    private final WhatsAppGatewayClient whatsApp;
    private final NotificationService notificationService;
    private final OneCRecipeImportService recipeImportService;
    private final OrderRepository orderRepository;
    private final PurchaseRepository purchaseRepository;
    private final ProductionRequestRepository requestRepository;
    private final ProductReturnRepository returnRepository;
    private final StockDocumentRepository stockDocumentRepository;
    private final CurrentUserService currentUserService;

    @Value("${crm.onec.base-url:}")
    private String onecUrl;

    private void admin() {
        if (!currentUserService.hasRole(Role.ADMIN)) throw new IllegalStateException("Настройки меняет администратор");
    }

    // ------------------------------------------------------------------ страница

    public record OneCRow(String what, String doc, long queue, long err, String href) {
    }

    /** Строка таблицы уведомлений: событие, пояснение, кому (ADMIN, ACCOUNTANT, WAREHOUSE_FG, SALES), null — только журнал. */
    public record Ntf(String event, String note, Set<Role> to) {
        public boolean has(Role r) {
            return to != null && to.contains(r);
        }
    }

    public static final List<Role> NT_ROLES = List.of(Role.ADMIN, Role.ACCOUNTANT, Role.WAREHOUSE_FG, Role.SALES);

    /** Кому что приходит — по вызовам NotificationService.notifyRole в коде. */
    public static final List<Ntf> NTF = List.of(
            new Ntf("Новый заказ", "складу ГП — «нужно собрать», бухгалтеру — с суммой, администратору — только если заказал сам клиент: на сайте или в WhatsApp",
                    EnumSet.of(Role.ADMIN, Role.ACCOUNTANT, Role.WAREHOUSE_FG)),
            new Ntf("Заказ собран, готов к выдаче", null, EnumSet.of(Role.ADMIN, Role.ACCOUNTANT)),
            new Ntf("Заказ выдан", null, EnumSet.of(Role.ADMIN, Role.ACCOUNTANT)),
            new Ntf("Заказ отменён", null, EnumSet.of(Role.ADMIN, Role.ACCOUNTANT)),
            new Ntf("Заказ завершён", null, EnumSet.of(Role.ADMIN)),
            new Ntf("Клиент не приехал", null, EnumSet.of(Role.ADMIN)),
            new Ntf("Долг просрочен — каждый день в 9:00", null, EnumSet.of(Role.ACCOUNTANT)),
            new Ntf("Заказ из WhatsApp: новый или не разобран", null, EnumSet.of(Role.ADMIN)),
            new Ntf("«Перезвоните мне» с сайта", null, EnumSet.of(Role.ADMIN, Role.SALES)),
            new Ntf("Новый клиент просит доступ к магазину", null, EnumSet.of(Role.ADMIN)),
            new Ntf("Отклик на вакансию", null, EnumSet.of(Role.ADMIN, Role.ACCOUNTANT)),
            new Ntf("Документ не ушёл в 1С — заказ, покупка, заявка, возврат, документ склада", null, EnumSet.of(Role.ADMIN)),
            new Ntf("Сырьё ниже минимума — раз в сутки", null, null));

    /** Запись журнала: когда, что, кому (роли через запятую) или «журнал» — если push не ушёл или это запись складу. */
    public record Log(LocalDateTime at, String text, String to, boolean log) {
    }

    @GetMapping("/admin/settings")
    @Transactional(readOnly = true)
    public String settings(@RequestParam(required = false) String qr, Model model) {
        CompanyProfile p = profileService.get();
        model.addAttribute("openQr", qr != null);
        PickupSlotService.Settings st = pickupSlotService.settings();
        LocalDate today = LocalDate.now();
        model.addAttribute("p", p);
        model.addAttribute("phones", p.getPhone() == null ? "" : p.getPhone());

        // шапка накладной: последний заказ и его клиент — для примера
        Order last = orderRepository.findAllByOrderByCreatedAtDesc().stream().findFirst().orElse(null);
        model.addAttribute("invNo", last == null ? "1" : String.valueOf(last.getId() + 1));
        model.addAttribute("invBuyer", last != null && last.getClient() != null ? last.getClient().getName() : "Покупатель");

        // окна выдачи: настройки и занятость на 7 дней по часам
        List<Integer> hours = new ArrayList<>();
        for (int h = 5; h <= 22; h++) hours.add(h);
        model.addAttribute("hours", hours);
        model.addAttribute("slots", st);
        String days = st.days() == null || st.days().length() != 7 ? "1111111" : st.days();
        List<Boolean> dayOn = new ArrayList<>();
        for (int i = 0; i < 7; i++) dayOn.add(days.charAt(i) == '1');
        model.addAttribute("dayOn", dayOn);
        List<Map<String, Object>> week = new ArrayList<>();
        for (int i = 0; i < PickupSlotService.DAYS; i++) {
            LocalDate d = today.plusDays(i);
            Map<String, Integer> taken = new LinkedHashMap<>();
            for (Object[] r : orderRepository.countBySlot(d, null)) taken.put(String.valueOf(((Number) r[0]).intValue()), ((Number) r[1]).intValue());
            week.add(Map.of("d", d.toString(), "rel", F.cap(F.relDay(d)).replace(NB, " "), "wd", WD[d.getDayOfWeek().getValue() - 1],
                    "w", d.getDayOfWeek().getValue() - 1, "taken", taken));
        }
        Set<Integer> openDays = new TreeSet<>();
        for (Order o : orderRepository.findByStatusInOrderByCreatedAtAsc(AdminStock.OPEN)) {
            if (o.getDueDate() != null && !o.getDueDate().isBefore(today)) openDays.add(o.getDueDate().getDayOfWeek().getValue() - 1);
        }
        model.addAttribute("slotJson", AdminJson.of(Map.of("week", week, "nowHour", LocalDateTime.now().getHour(), "today", today.toString(),
                "openDays", openDays, "days", PickupSlotService.DAYS)));

        // WhatsApp
        WhatsAppSessionDto wa = whatsApp.getStatus();
        String ws = wa == null || wa.status() == null ? "unavailable" : wa.status();
        model.addAttribute("waStatus", ws);
        model.addAttribute("waOn", ws.equals("open"));
        model.addAttribute("waPhone", wa == null ? null : wa.phone());
        model.addAttribute("waText", switch (ws) {
            case "open" -> "заказы из WhatsApp принимаются" + (wa.phone() != null ? " · номер " + wa.phone() : "");
            case "qr" -> "ждёт подключения — покажите QR и отсканируйте его телефоном";
            case "connecting" -> "подключается…";
            case "closed" -> "номер отключён — заказы из WhatsApp не принимаются";
            default -> "шлюз WhatsApp не отвечает — заказы из WhatsApp не принимаются";
        });
        model.addAttribute("waTitle", switch (ws) {
            case "open" -> "Подключён";
            case "qr", "connecting" -> "Не подключён";
            case "closed" -> "Отключён";
            default -> "Нет связи";
        });

        // 1С
        model.addAttribute("onecOn", onecUrl != null && !onecUrl.isBlank());
        model.addAttribute("onec", List.of(
                new OneCRow("Заказы", "«Реализация товаров и услуг» — черновиком, проводит бухгалтер", orderRepository.findOneCPendingIds().size(),
                        orderRepository.countByOneCSyncErrorIsNotNull(), "/admin/orders"),
                new OneCRow("Покупки", "«Поступление товаров и услуг»", purchaseRepository.findOneCPendingIds().size(),
                        purchaseRepository.countByOneCSyncErrorIsNotNull(), "/admin/purchases?seg=err&per=all"),
                new OneCRow("Заявки на сырьё", "«Отчёт производства за смену» и «Требование-накладная»", requestRepository.findOneCPendingIds().size(),
                        requestRepository.countByOneCSyncErrorIsNotNull(), "/admin/requests?per=all"),
                new OneCRow("Возвраты", "возврат от покупателя — на склад «Испорченные товары»", returnRepository.findOneCPendingIds().size(),
                        returnRepository.countByOneCSyncErrorIsNotNull(), "/admin/returns"),
                new OneCRow("Документы склада", "«Поступление» и «Списание» товара и сырья", stockDocumentRepository.findOneCPendingIds().size(),
                        stockDocumentRepository.countByOneCSyncErrorIsNotNull(), "/admin/stock?tab=moves")));

        // уведомления
        model.addAttribute("pushOn", notificationService.pushEnabled());
        model.addAttribute("ntRoles", NT_ROLES);
        model.addAttribute("ntRoleShort", NT_ROLES.stream().map(AdminTeam::roleShort).toList());
        model.addAttribute("ntf", NTF);
        model.addAttribute("log", journal(notificationService.recentStaff()));

        page(model, "Настройки", "компания и сайт, магазин, окна выдачи, WhatsApp, 1С, уведомления", "settings");
        return "admin/settings";
    }

    /** Одно сообщение нескольким ролям в одну минуту — одной строкой; последние 8. */
    static List<Log> journal(List<Notification> list) {
        Map<String, Object[]> by = new LinkedHashMap<>();
        for (Notification n : list) {
            String key = n.getMessage() + "|" + n.getCreatedAt().withSecond(0).withNano(0);
            Object[] x = by.computeIfAbsent(key, k -> new Object[]{n, new ArrayList<String>(), Boolean.FALSE});
            String r = n.getRecipient();
            @SuppressWarnings("unchecked") List<String> to = (List<String>) x[1];
            if (r.startsWith("role:")) {
                try {
                    String label = Role.valueOf(r.substring(5)).getLabel();
                    if (!to.contains(label)) to.add(label);
                } catch (IllegalArgumentException e) {
                    to.add(r.substring(5));
                }
            }
            if (!"PUSH".equals(n.getChannel())) x[2] = Boolean.TRUE;
        }
        List<Log> out = new ArrayList<>();
        for (Object[] x : by.values()) {
            Notification n = (Notification) x[0];
            @SuppressWarnings("unchecked") List<String> to = (List<String>) x[1];
            out.add(new Log(n.getCreatedAt(), n.getMessage(), String.join(", ", to), (Boolean) x[2]));
            if (out.size() == 8) break;
        }
        return out;
    }

    // ------------------------------------------------------------------ сохранение

    private static String t(Map<String, String> f, String k) {
        String v = f.get(k);
        return v == null ? "" : v.trim();
    }

    @PostMapping("/admin/settings/company")
    public String company(@RequestParam Map<String, String> f, RedirectAttributes ra) {
        try {
            admin();
            if (t(f, "name").isEmpty()) throw new IllegalArgumentException("Введите название компании.");
            if (!t(f, "legal").isEmpty() && !t(f, "legal").matches(".*\\b\\d{12}\\b.*")) throw new IllegalArgumentException("БИН — 12 цифр, например «ТОО «…» · БИН 123456789012».");
            profileService.saveCompany(t(f, "name"), t(f, "legal"), t(f, "address"), t(f, "phone"), t(f, "email"));
            ok(ra, "Компания: сохранено");
        } catch (RuntimeException e) {
            fail(ra, e);
        }
        return "redirect:/admin/settings#st-co";
    }

    @PostMapping("/admin/settings/site")
    public String site(@RequestParam Map<String, String> f, RedirectAttributes ra) {
        try {
            admin();
            if (!t(f, "map").isEmpty() && !t(f, "map").matches("-?\\d{1,2}(\\.\\d+)?\\s*,\\s*-?\\d{1,3}(\\.\\d+)?"))
                throw new IllegalArgumentException("Точка на карте — «широта, долгота», например 42.3417, 69.5901.");
            profileService.saveSite(t(f, "about"), t(f, "work"), t(f, "pickup"), t(f, "whatsapp"), t(f, "jobs"), t(f, "insta"), t(f, "tiktok"), t(f, "map"));
            ok(ra, "Сайт: сохранено");
        } catch (RuntimeException e) {
            fail(ra, e);
        }
        return "redirect:/admin/settings#st-site";
    }

    @PostMapping("/admin/settings/shop")
    public String shop(@RequestParam(required = false) String pickupChoice, @RequestParam(required = false) String paymentChoice, RedirectAttributes ra) {
        try {
            admin();
            profileService.saveShopOptions(pickupChoice != null, paymentChoice != null);
            ok(ra, "Магазин: сохранено — магазин на сайте обновился");
        } catch (RuntimeException e) {
            fail(ra, e);
        }
        return "redirect:/admin/settings#st-shop";
    }

    @PostMapping("/admin/settings/slots")
    public String slots(@RequestParam(defaultValue = "0") int from, @RequestParam(defaultValue = "0") int to,
                        @RequestParam(defaultValue = "0") int cap, @RequestParam(required = false) List<Integer> wd, RedirectAttributes ra) {
        try {
            admin();
            char[] d = "0000000".toCharArray();
            if (wd != null) for (Integer x : wd) if (x != null && x >= 0 && x < 7) d[x] = '1';
            profileService.saveSlots(from, to, cap, new String(d));
            ok(ra, "Окна выдачи: сохранено — новые окна уже в форме заказа и в магазине");
        } catch (RuntimeException e) {
            fail(ra, e);
        }
        return "redirect:/admin/settings#st-slots";
    }

    // ------------------------------------------------------------------ WhatsApp

    @GetMapping("/admin/settings/whatsapp/status")
    @ResponseBody
    public Map<String, Object> waStatus() {
        admin();
        WhatsAppSessionDto wa = whatsApp.getStatus();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", wa == null || wa.status() == null ? "unavailable" : wa.status());
        m.put("phone", wa == null ? null : wa.phone());
        return m;
    }

    @GetMapping(value = "/admin/settings/whatsapp/qr", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> waQr() {
        admin();
        byte[] png = whatsApp.getQrCode();
        if (png == null) return ResponseEntity.status(404).build();
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(png);
    }

    @PostMapping("/admin/settings/whatsapp/logout")
    public String waLogout(@RequestParam(required = false) String again, RedirectAttributes ra) {
        try {
            admin();
            if (!whatsApp.logout()) throw new IllegalStateException("Шлюз WhatsApp не ответил — попробуйте ещё раз");
            ok(ra, again != null ? "Старое подключение разорвано — покажите новый QR и отсканируйте его" : "WhatsApp отключён");
        } catch (RuntimeException e) {
            fail(ra, e);
        }
        return "redirect:/admin/settings" + (again != null ? "?qr=1" : "") + "#st-wa";
    }

    // ------------------------------------------------------------------ 1С: рецептуры (остальные загрузки — в своих разделах, ?next=)

    @PostMapping("/admin/settings/recipes-1c")
    public String recipes(RedirectAttributes ra) {
        try {
            admin();
            OneCRecipeImportService.ImportResult r = recipeImportService.importAll();
            ok(ra, "Рецептуры из 1С: обновлено " + r.imported() + ", без спецификации " + r.skippedNoSpec() + ", не связаны с 1С " + r.skippedNoOneCMatch());
        } catch (RuntimeException e) {
            fail(ra, e);
        }
        return "redirect:/admin/settings#st-onec";
    }
}
