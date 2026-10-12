package com.confectionery.crm.web;

import com.confectionery.crm.domain.Client;
import com.confectionery.crm.domain.CompanyProfile;
import com.confectionery.crm.domain.Order;
import com.confectionery.crm.domain.OrderItem;
import com.confectionery.crm.domain.Product;
import com.confectionery.crm.domain.ProductReturn;
import com.confectionery.crm.service.ClientPriceService;
import com.confectionery.crm.service.ClientService;
import com.confectionery.crm.service.CompanyProfileService;
import com.confectionery.crm.service.OrderService;
import com.confectionery.crm.service.ProductReturnService;
import com.confectionery.crm.service.ProductService;
import com.confectionery.crm.service.PromoPageService;
import com.confectionery.crm.service.ShopAccessService;
import com.confectionery.crm.web.dto.OrderForm;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Публичная витрина для клиентов — без логина, клиент идентифицируется по номеру телефона
 * (см. ClientService.findByPhone), корзина живёт в HTTP-сессии (без БД). UX намеренно
 * повторяет мобильное приложение (см. mobile/lib/screens/order_form_screen.dart и
 * product_quantity_screen.dart) — экран заказа со степпером количества по каждой позиции
 * и большой суммой внизу, отдельный экран товара "крупным планом" для выбора количества
 * перед добавлением. Создание заказа переиспользует OrderService.createOrder() — ту же
 * точку входа, что и ручной ввод на складе, и WhatsApp-приём.
 *
 * <p>Степпер на экране заказа шлёт фоновый POST (см. shop/catalog.html, крохотный vanilla
 * JS без библиотек) — сервер отвечает JSON ({@link CartUpdateResponse}), если запрос помечен
 * заголовком X-Requested-With; без JS (или если заголовка нет) — обычный редирект назад на
 * /shop/catalog, как раньше. Степпер на экране товара вообще не ходит на сервер — значение
 * меняется в браузере, сервер узнаёт о нём только по нажатию "Добавить"/"Сохранить".</p>
 *
 * <p>Номер телефона запоминается в cookie {@link #PHONE_COOKIE} на 180 дней — иначе клиенту
 * пришлось бы вводить его заново при каждом визите (сессия живёт только пока открыт браузер
 * или до перезапуска backend). Кнопка "Сменить номер" (см. {@link #reset}) стирает и сессию,
 * и эту cookie.</p>
 */
@Controller
@RequestMapping("/shop")
@RequiredArgsConstructor
public class ShopController {

    /** Публичные — по ним DashboardController узнаёт вошедшего клиента и ведёт с "/" сразу в магазин. */
    public static final String SESSION_CLIENT_ID = "shopClientId";
    private static final String SESSION_CLIENT_NAME = "shopClientName";
    private static final String SESSION_CART = "shopCart";
    private static final String AJAX_MARKER = "fetch";

    /**
     * Номер телефона запоминается в отдельной долгоживущей cookie (не в сессии — та живёт
     * только пока открыт браузер/до истечения таймаута на сервере, а после перезапуска
     * backend сессии вообще пропадают). Так клиенту не нужно вводить номер заново при
     * каждом визите — только один раз, дальше сессия на /shop поднимается автоматически.
     */
    public static final String PHONE_COOKIE = "shop_phone";
    private static final int PHONE_COOKIE_MAX_AGE = 60 * 60 * 24 * 180; // 180 дней

    private static final Set<String> FRACTIONAL_UNITS = Set.of("кг", "л", "г");

    private static final NumberFormat MONEY_FORMAT = NumberFormat.getInstance(new Locale("ru"));
    private static final NumberFormat QTY_FORMAT = NumberFormat.getInstance(new Locale("ru"));

    static {
        MONEY_FORMAT.setMaximumFractionDigits(2);
        MONEY_FORMAT.setMinimumFractionDigits(0);
        QTY_FORMAT.setMaximumFractionDigits(3);
        QTY_FORMAT.setMinimumFractionDigits(0);
    }

    private final ClientService clientService;
    private final ProductService productService;
    private final ClientPriceService clientPriceService;
    private final OrderService orderService;
    private final ProductReturnService productReturnService;
    private final ShopAccessService shopAccessService;
    private final PromoPageService promoPageService;
    private final CompanyProfileService companyProfileService;

    /** Контакты в шапке/подвале магазина (shop/fragments/frame.html) и номер для wa.me / tel:. */
    @ModelAttribute
    public void companyInfo(Model model) {
        CompanyProfile company = companyProfileService.get();
        model.addAttribute("company", company);
        // В «Телефоне» может быть несколько номеров через запятую — звоним на первый.
        String firstPhone = company.getPhone() == null ? "" : company.getPhone().split("[,;]")[0].trim();
        String wa = company.getWhatsappPhone() == null || company.getWhatsappPhone().isBlank()
                ? firstPhone : company.getWhatsappPhone();
        model.addAttribute("firstPhone", firstPhone);
        model.addAttribute("telPhone", firstPhone.replaceAll("\\D", ""));
        model.addAttribute("waPhone", wa.replaceAll("\\D", ""));
    }

    @GetMapping
    public String phoneForm(HttpSession session, HttpServletRequest request, HttpServletResponse response, Model model) {
        if (session.getAttribute(SESSION_CLIENT_ID) != null) {
            return "redirect:/shop/products";
        }
        String rememberedPhone = readPhoneCookie(request);
        if (rememberedPhone != null) {
            Client client = clientService.findByPhone(rememberedPhone).orElse(null);
            if (client != null) {
                session.setAttribute(SESSION_CLIENT_ID, client.getId());
                session.setAttribute(SESSION_CLIENT_NAME, client.getName());
                // переписать cookie на путь "/" (раньше была на /shop) — чтобы "/" тоже узнавал клиента
                writePhoneCookie(response, client.getPhone());
                return "redirect:/shop/products";
            }
            if (shopAccessService.findOpen(rememberedPhone).isPresent()) {
                return "redirect:/shop/access";
            }
        }
        model.addAttribute("state", "phone");
        return "shop/enter";
    }

    /**
     * Номера нет среди клиентов — вместо входа заявка админу (см. ShopAccessService). Номер
     * всё равно запоминается в cookie: как только админ разрешит, следующее открытие /shop
     * впустит клиента само, без повторного ввода.
     */
    @GetMapping("/access")
    public String access(HttpServletRequest request, Model model) {
        String phone = readPhoneCookie(request);
        if (phone == null) {
            return "redirect:/shop";
        }
        if (clientService.findByPhone(phone).isPresent()) {
            return "redirect:/shop";
        }
        var open = shopAccessService.findOpen(phone);
        if (open.isEmpty()) {
            return "redirect:/shop";
        }
        model.addAttribute("phone", open.get().getPhone());
        model.addAttribute("state", "REJECTED".equals(open.get().getStatus()) ? "rejected" : "pending");
        return "shop/enter";
    }

    @PostMapping("/start")
    public String start(@RequestParam String phone, @RequestParam(required = false) String name,
                         HttpSession session, HttpServletResponse response, RedirectAttributes redirectAttributes) {
        if (phone == null || phone.replaceAll("\\D", "").length() < 10) {
            redirectAttributes.addFlashAttribute("error", "Укажите номер телефона полностью");
            return "redirect:/shop";
        }
        Client client = clientService.findByPhone(phone).orElse(null);
        if (client == null) {
            if (shopAccessService.findOpen(phone).isEmpty()) {
                if (name == null || name.isBlank()) {
                    redirectAttributes.addFlashAttribute("error", "Номер новый — укажите имя, заявка уйдёт администратору");
                    redirectAttributes.addFlashAttribute("phone", phone.trim());
                    return "redirect:/shop";
                }
                shopAccessService.request(phone, name);
            }
            writePhoneCookie(response, phone.trim());
            return "redirect:/shop/access";
        }
        session.setAttribute(SESSION_CLIENT_ID, client.getId());
        session.setAttribute(SESSION_CLIENT_NAME, client.getName());
        writePhoneCookie(response, client.getPhone());
        return "redirect:/shop/products";
    }

    /** Форма «Перезвоните мне» с рекламной страницы (fetch из static/promo/index.html). */
    @PostMapping("/callback")
    public ResponseEntity<Void> callback(@RequestParam String phone,
                                         @RequestParam(required = false) String name,
                                         @RequestParam(required = false) String type) {
        if (phone.replaceAll("\\D", "").length() < 10 || phone.length() > 40
                || (name != null && name.length() > 100) || (type != null && type.length() > 100)) {
            return ResponseEntity.badRequest().build();
        }
        shopAccessService.requestCallback(phone, name, type);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/reset")
    public String reset(HttpSession session, HttpServletResponse response) {
        session.removeAttribute(SESSION_CLIENT_ID);
        session.removeAttribute(SESSION_CLIENT_NAME);
        session.removeAttribute(SESSION_CART);
        clearPhoneCookie(response);
        return "redirect:/shop";
    }

    private String readPhoneCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (PHONE_COOKIE.equals(cookie.getName()) && !cookie.getValue().isBlank()) {
                return URLDecoder.decode(cookie.getValue(), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private void writePhoneCookie(HttpServletResponse response, String phone) {
        Cookie cookie = new Cookie(PHONE_COOKIE, URLEncoder.encode(phone, StandardCharsets.UTF_8));
        cookie.setPath("/");
        cookie.setMaxAge(PHONE_COOKIE_MAX_AGE);
        cookie.setHttpOnly(true);
        response.addCookie(cookie);
    }

    /** Стираем на обоих путях: до 09.10 cookie ставилась на /shop, теперь на "/". */
    private void clearPhoneCookie(HttpServletResponse response) {
        for (String path : List.of("/", "/shop")) {
            Cookie cookie = new Cookie(PHONE_COOKIE, "");
            cookie.setPath(path);
            cookie.setMaxAge(0);
            cookie.setHttpOnly(true);
            response.addCookie(cookie);
        }
    }

    /** Корзина: состав, день/время выдачи, оплата, комментарий (shop/app.html, view = cart). */
    @GetMapping("/catalog")
    public String orderBuilder(HttpSession session, Model model) {
        Client client = currentClient(session);
        if (client == null) {
            return "redirect:/shop";
        }
        List<String> removed = dropHiddenFromCart(cart(session));
        if (!removed.isEmpty()) {
            model.addAttribute("error", "Временно недоступно и убрано из корзины: " + String.join(", ", removed));
        }
        return app("cart", client, session, model);
    }

    @PostMapping("/cart/item/{productId}/inc")
    public ResponseEntity<Object> increment(@PathVariable Long productId, HttpSession session,
                                             @RequestHeader(value = "X-Requested-With", required = false) String ajax) {
        Client client = currentClient(session);
        if (client == null) {
            return redirectToShop();
        }
        Product product = productService.findById(productId);
        Map<Long, BigDecimal> cart = cart(session);
        if (!product.isVisibleToClients()) {
            cart.remove(productId);
            return respond(client, session, productId, ajax);
        }
        BigDecimal current = cart.getOrDefault(productId, BigDecimal.ZERO);
        cart.put(productId, current.add(step(product)));
        return respond(client, session, productId, ajax);
    }

    @PostMapping("/cart/item/{productId}/dec")
    public ResponseEntity<Object> decrement(@PathVariable Long productId, HttpSession session,
                                             @RequestHeader(value = "X-Requested-With", required = false) String ajax) {
        Client client = currentClient(session);
        if (client == null) {
            return redirectToShop();
        }
        Product product = productService.findById(productId);
        Map<Long, BigDecimal> cart = cart(session);
        BigDecimal step = step(product);
        BigDecimal current = cart.getOrDefault(productId, step);
        BigDecimal next = current.subtract(step);
        cart.put(productId, next.compareTo(step) < 0 ? step : next);
        return respond(client, session, productId, ajax);
    }

    /**
     * Прямой ввод количества (тап по цифре в степпере — см. shop/catalog.html) вместо
     * многократного нажатия +/-: незаменимо для оптовых заказов на сотни/тысячи штук.
     */
    @PostMapping("/cart/item/{productId}/set")
    public ResponseEntity<Object> setQuantity(@PathVariable Long productId, @RequestParam BigDecimal qty,
                                               HttpSession session,
                                               @RequestHeader(value = "X-Requested-With", required = false) String ajax) {
        Client client = currentClient(session);
        if (client == null) {
            return redirectToShop();
        }
        Product product = productService.findById(productId);
        if (!product.isVisibleToClients()) {
            cart(session).remove(productId);
            return respond(client, session, productId, ajax);
        }
        BigDecimal step = step(product);
        cart(session).put(productId, qty.compareTo(step) < 0 ? step : qty);
        return respond(client, session, productId, ajax);
    }

    @PostMapping("/cart/item/{productId}/remove")
    public ResponseEntity<Object> remove(@PathVariable Long productId, HttpSession session,
                                          @RequestHeader(value = "X-Requested-With", required = false) String ajax) {
        Client client = currentClient(session);
        if (client == null) {
            return redirectToShop();
        }
        cart(session).remove(productId);
        return respond(client, session, productId, ajax);
    }

    /** Главный экран вошедшего клиента — каталог с ценами и добавлением в корзину (shop/app.html). */
    @GetMapping("/products")
    public String products(HttpSession session, Model model) {
        Client client = currentClient(session);
        if (client == null) {
            return "redirect:/shop";
        }
        dropHiddenFromCart(cart(session));
        return app("products", client, session, model);
    }

    /** Отдельной страницы товара больше нет (09.10) — старые ссылки ведут в каталог. */
    @GetMapping("/products/{id}")
    public String productDetail(@PathVariable Long id) {
        return "redirect:/shop/products";
    }

    private String app(String view, Client client, HttpSession session, Model model) {
        model.addAttribute("view", view);
        model.addAttribute("clientName", client.getName());
        model.addAttribute("cartCount", cart(session).size());
        model.addAttribute("shopData", shopData(client, cart(session)));
        return "shop/app";
    }

    /**
     * Всё, что нужно странице магазина, одним JSON: товары (видимые клиентам, цена — клиентская,
     * вес/коробка — как на рекламной странице, см. PromoPageService.card), группы для фильтра,
     * текущая корзина и дни выдачи (сегодня + 5; после 17:00 по умолчанию завтра).
     */
    private Map<String, Object> shopData(Client client, Map<Long, BigDecimal> cart) {
        java.text.Collator collator = java.text.Collator.getInstance(new Locale("ru", "RU"));
        collator.setStrength(java.text.Collator.SECONDARY);
        List<Product> visible = productService.findVisibleToClients().stream()
                .sorted((a, b) -> collator.compare(a.getName().trim(), b.getName().trim()))
                .toList();
        Map<String, String> groupKeys = new LinkedHashMap<>();
        Map<String, Integer> groupCounts = new LinkedHashMap<>();
        List<Map<String, Object>> products = new ArrayList<>();
        visible.stream()
                .map(p -> promoPageService.card(p).group())
                .distinct()
                .sorted((a, b) -> "Другое".equals(a) ? 1 : "Другое".equals(b) ? -1 : collator.compare(a, b))
                .forEach(g -> { groupKeys.put(g, "g" + groupKeys.size()); groupCounts.put(g, 0); });
        for (Product p : visible) {
            PromoPageService.Card card = promoPageService.card(p);
            groupCounts.merge(card.group(), 1, Integer::sum);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", p.getId());
            row.put("name", card.name());
            row.put("groupKey", groupKeys.get(card.group()));
            row.put("img", card.imageUrl());
            // альбом по нажатию на фото (static/promo/album.js); Map, а не record — Thymeleaf сериализует в JS только Map/бины
            row.put("photos", card.photos().stream().map(ph -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("src", ph.src());
                if (ph.w() != null) m.put("w", ph.w());
                if (ph.h() != null) m.put("h", ph.h());
                m.put("alt", ph.alt());
                return m;
            }).toList());
            row.put("weight", card.weight());
            row.put("weightG", card.weightGrams());
            row.put("box", card.boxUnits());
            row.put("boxText", card.perBox());
            row.put("price", clientPriceService.resolvePrice(client, p));
            row.put("unit", p.getUnit() == null || p.getUnit().isBlank() ? "шт" : p.getUnit());
            row.put("step", step(p));
            products.add(row);
        }
        List<List<Object>> groups = new ArrayList<>();
        groupKeys.forEach((name, key) -> groups.add(List.of(key, name, groupCounts.get(name))));

        Map<String, BigDecimal> cartJson = new LinkedHashMap<>();
        cart.forEach((id, qty) -> cartJson.put(String.valueOf(id), qty));

        LocalDate today = LocalDate.now();
        List<Map<String, Object>> days = new ArrayList<>();
        for (int i = 0; i < PICKUP_DAYS; i++) {
            LocalDate d = today.plusDays(i);
            days.add(Map.of("date", d.toString(), "today", i == 0,
                    "title", i == 0 ? "Сегодня" : i == 1 ? "Завтра" : capitalize(DAY_TITLE.format(d)),
                    "sub", DAY_SUB.format(d)));
        }
        int hour = java.time.LocalTime.now().getHour();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("products", products);
        data.put("groups", groups);
        data.put("cart", cartJson);
        data.put("days", days);
        data.put("hourNow", hour);
        data.put("defaultDay", hour >= LAST_PICKUP_HOUR - 1 ? 1 : 0);
        return data;
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Окна выдачи — по часу с 8:00 до 18:00, на {@link #PICKUP_DAYS} дней вперёд (см. shop/app.html). */
    private static final int FIRST_PICKUP_HOUR = 8;
    private static final int LAST_PICKUP_HOUR = 18;
    private static final int PICKUP_DAYS = 6;
    private static final DateTimeFormatter DAY_TITLE = DateTimeFormatter.ofPattern("EE", new Locale("ru"));
    private static final DateTimeFormatter DAY_SUB = DateTimeFormatter.ofPattern("d MMM", new Locale("ru"));
    private static final java.util.regex.Pattern SLOT = java.util.regex.Pattern.compile("(\\d{2}):00–(\\d{2}):00");
    private static final Set<String> PAYMENTS = Set.of("Kaspi", "По счёту");

    /**
     * Оформление: день выдачи → дата отгрузки заказа (dueDate), окно, оплата и комментарий → в
     * примечание заказа — сотрудники видят их в приложении (отдельных полей в заказе нет).
     * С 09.10 выбор времени и оплаты в корзине закомментирован (shop/app.html) — тогда приходит
     * только комментарий; если блоки вернут, проверки ниже снова заработают сами.
     */
    @PostMapping("/cart/submit")
    public String submit(@RequestParam(required = false) LocalDate day, @RequestParam(required = false) String slot,
                         @RequestParam(required = false) String pay, @RequestParam(required = false) String note,
                         HttpSession session, RedirectAttributes redirectAttributes) {
        Client client = currentClient(session);
        if (client == null) {
            return "redirect:/shop";
        }
        String pickup = null;
        if (slot != null && !slot.isBlank()) {
            LocalDate today = LocalDate.now();
            java.util.regex.Matcher m = SLOT.matcher(slot);
            if (day == null || day.isBefore(today) || day.isAfter(today.plusDays(PICKUP_DAYS - 1)) || !m.matches()) {
                redirectAttributes.addFlashAttribute("error", "Выберите день и время выдачи");
                return "redirect:/shop/catalog";
            }
            int from = Integer.parseInt(m.group(1));
            if (from < FIRST_PICKUP_HOUR || from >= LAST_PICKUP_HOUR || Integer.parseInt(m.group(2)) != from + 1
                    || (day.equals(today) && from <= java.time.LocalTime.now().getHour())) {
                redirectAttributes.addFlashAttribute("error", "Это время выдачи уже недоступно — выберите другое");
                return "redirect:/shop/catalog";
            }
            pickup = DAY_SUB.format(day) + ", " + slot;
        }
        String payment = pay == null || pay.isBlank() ? null : PAYMENTS.contains(pay) ? pay : "Kaspi";
        String comment = note == null ? "" : note.trim();
        if (comment.length() > 500) comment = comment.substring(0, 500);
        List<String> head = new ArrayList<>();
        if (pickup != null) head.add("Выдача: " + pickup);
        if (payment != null) head.add("Оплата: " + payment);
        String notes = String.join(" · ", head);
        if (!comment.isEmpty()) notes = (notes.isEmpty() ? "" : notes + "\n") + "Комментарий: " + comment;

        Map<Long, BigDecimal> cart = cart(session);
        OrderForm form = new OrderForm();
        form.setClientId(client.getId());
        form.setFromShop(true);
        form.setDueDate(pickup != null ? day : null);
        form.setNotes(notes.isEmpty() ? null : notes);
        List<OrderForm.OrderItemForm> items = new ArrayList<>();
        for (Map.Entry<Long, BigDecimal> entry : cart.entrySet()) {
            OrderForm.OrderItemForm item = new OrderForm.OrderItemForm();
            item.setProductId(entry.getKey());
            item.setQuantity(entry.getValue());
            items.add(item);
        }
        form.setItems(items);
        try {
            Order order = orderService.createOrder(form);
            session.removeAttribute(SESSION_CART);
            if (pickup != null) redirectAttributes.addFlashAttribute("when", pickup);
            if (payment != null) redirectAttributes.addFlashAttribute("pay", payment);
            return "redirect:/shop/confirmation/" + order.getId();
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/shop/catalog";
        }
    }

    private static final DateTimeFormatter WEEK_FORMAT = DateTimeFormatter.ofPattern("dd.MM");

    /**
     * История заказов клиента — все каналы (сама витрина, WhatsApp, ручной ввод), не только /shop.
     * По умолчанию (без {@code week}) — последние 7 дней, включая сегодня. Стрелкой назад —
     * календарные недели (пн–вс) по дате заказа: {@code week} — сколько недель назад, 0 — текущая.
     * У каждого заказа — закреплённые за ним возвраты (кнопка «Показать возврат»).
     */
    @GetMapping("/orders")
    public String orders(@RequestParam(required = false) Integer week, HttpSession session, Model model) {
        Client client = currentClient(session);
        if (client == null) {
            return "redirect:/shop";
        }
        boolean last7Days = week == null || week < 0;
        int weeksAgo = last7Days ? -1 : week;
        LocalDate today = LocalDate.now();
        LocalDate from = last7Days ? today.minusDays(6)
                : today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(weeksAgo);
        LocalDate to = last7Days ? today : from.plusDays(6);
        List<OrderView> rows = orderService.findByClient(client.getId()).stream()
                .filter(o -> {
                    LocalDate d = o.getCreatedAt().toLocalDate();
                    return !d.isBefore(from) && !d.isAfter(to);
                })
                .map(this::orderView)
                .toList();
        model.addAttribute("view", "orders");
        model.addAttribute("clientName", client.getName());
        model.addAttribute("orders", rows);
        model.addAttribute("week", weeksAgo);
        model.addAttribute("weekLabel", last7Days ? "Последние 7 дней" : weeksAgo == 0 ? "Эта неделя" : weeksAgo == 1 ? "Прошлая неделя"
                : WEEK_FORMAT.format(from) + " – " + WEEK_FORMAT.format(to));
        model.addAttribute("weekRange", WEEK_FORMAT.format(from) + " – " + WEEK_FORMAT.format(to));
        model.addAttribute("cartCount", cart(session).size());
        return "shop/app";
    }

    /**
     * Повторить прошлый заказ — заменяет текущую корзину его составом и отправляет на
     * /shop/catalog, где можно поправить количество/состав перед оформлением (переиспользует
     * весь существующий UI редактирования корзины, отдельного экрана не нужно).
     */
    @PostMapping("/orders/{id}/repeat")
    public String repeat(@PathVariable Long id, HttpSession session, RedirectAttributes redirectAttributes) {
        Client client = currentClient(session);
        if (client == null) {
            return "redirect:/shop";
        }
        Order order;
        try {
            order = orderService.findById(id);
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("error", "Заказ не найден");
            return "redirect:/shop/orders";
        }
        if (!order.getClient().getId().equals(client.getId())) {
            redirectAttributes.addFlashAttribute("error", "Заказ не найден");
            return "redirect:/shop/orders";
        }
        Map<Long, BigDecimal> cart = cart(session);
        cart.clear();
        for (OrderItem item : order.getItems()) {
            cart.put(item.getProduct().getId(), item.getQuantity());
        }
        // скрытые от клиентов товары убирает и объясняет сам экран корзины (см. orderBuilder)
        return "redirect:/shop/catalog";
    }

    /**
     * Убрать из корзины товары, которые сейчас не видны клиентам (скрыты админом или сняты с
     * продажи уже после того, как клиент положил их в корзину). Возвращает их названия.
     */
    private List<String> dropHiddenFromCart(Map<Long, BigDecimal> cart) {
        List<String> removed = new ArrayList<>();
        cart.keySet().removeIf(productId -> {
            Product product = productService.findById(productId);
            if (product.isVisibleToClients()) return false;
            removed.add(product.getName());
            return true;
        });
        return removed;
    }

    private OrderView orderView(Order o) {
        List<ItemLine> items = o.getItems().stream()
                .map(i -> itemLine(i.getProduct(), i.getQuantity(), i.getPrice()))
                .toList();
        List<ProductReturn> orderReturns = productReturnService.findByOrder(o.getId());
        List<ReturnRow> returns = orderReturns.stream()
                .map(r -> new ReturnRow(
                        r.getReturnDate().format(DateTimeFormatter.ofPattern("dd.MM.yyyy")),
                        r.getOneCDocumentNumber(),
                        r.getReason(),
                        r.getItems().stream().map(i -> itemLine(i.getProduct(), i.getQuantity(), i.getPrice())).toList(),
                        MONEY_FORMAT.format(r.getTotalSum()) + " ₸"))
                .toList();
        BigDecimal returnTotal = orderReturns.stream()
                .map(ProductReturn::getTotalSum).reduce(BigDecimal.ZERO, BigDecimal::add);
        Packing packing = packing(o.getItems().stream().map(i -> Map.entry(i.getProduct(), i.getQuantity())).toList());
        String status = switch (o.getStatus()) {
            case NEW -> "new";
            case CONFIRMED, IN_PRODUCTION -> "work";
            case READY -> "ready";
            case DELIVERED, COMPLETED -> "done";
            case CANCELLED -> "off";
        };
        String statusLabel = switch (status) {
            case "new" -> "Принят";
            case "work" -> "Собирается";
            case "ready" -> "Готов к выдаче";
            case "done" -> "Выдан";
            default -> "Отменён";
        };
        String when = "Оформлен " + DAY_SUB.format(o.getCreatedAt())
                + (o.getDueDate() != null ? " · выдача " + DAY_SUB.format(o.getDueDate()) : "");
        return new OrderView(o.getId(), when, status, statusLabel,
                status.equals("new") || status.equals("work") || status.equals("ready"),
                items.size(), packing.boxes(), packing.weight(),
                MONEY_FORMAT.format(o.getTotalPrice()) + " ₸",
                returns.isEmpty() ? null : "−" + MONEY_FORMAT.format(returnTotal) + " ₸",
                returns.isEmpty() ? null : MONEY_FORMAT.format(o.getTotalPrice().subtract(returnTotal)) + " ₸",
                items, returns, o.getNotes());
    }

    /**
     * Коробки (⌈кол-во / в коробке⌉ по каждой позиции) и общий вес — по карточкам товаров, как в
     * корзине (shop/app.html). Позиции без размера коробки/веса не считаются; «+» — есть такие.
     */
    private Packing packing(List<Map.Entry<Product, BigDecimal>> lines) {
        int boxes = 0;
        boolean unknownBox = false;
        BigDecimal grams = BigDecimal.ZERO;
        for (Map.Entry<Product, BigDecimal> line : lines) {
            PromoPageService.Card card = promoPageService.card(line.getKey());
            if (card.boxUnits() != null) {
                boxes += line.getValue().divide(card.boxUnits(), 0, java.math.RoundingMode.CEILING).intValue();
            } else {
                unknownBox = true;
            }
            if (card.weightGrams() != null) {
                grams = grams.add(card.weightGrams().multiply(line.getValue()));
            }
        }
        String boxesLabel = boxes == 0 ? null : boxes + (unknownBox ? "+" : "");
        String weight = grams.signum() == 0 ? null
                : QTY_FORMAT.format(grams.movePointLeft(3).setScale(2, java.math.RoundingMode.HALF_UP)) + " кг";
        return new Packing(boxesLabel, weight);
    }

    private record Packing(String boxes, String weight) {
    }

    private ItemLine itemLine(Product product, BigDecimal quantity, BigDecimal price) {
        String unit = product.getUnit() == null || product.getUnit().isBlank() ? "шт" : product.getUnit();
        BigDecimal sum = price == null ? null : price.multiply(quantity);
        return new ItemLine(product.getName(), QTY_FORMAT.format(quantity) + " " + unit,
                sum == null ? "" : MONEY_FORMAT.format(sum) + " ₸");
    }

    /** «Заказ принят» — только для своего заказа; выдача/оплата приходят из submit (flash). */
    @GetMapping("/confirmation/{orderId}")
    public String confirmation(@PathVariable Long orderId, HttpSession session, Model model) {
        Client client = currentClient(session);
        if (client == null) {
            return "redirect:/shop";
        }
        Order order;
        try {
            order = orderService.findById(orderId);
        } catch (IllegalArgumentException e) {
            return "redirect:/shop/orders";
        }
        if (!order.getClient().getId().equals(client.getId())) {
            return "redirect:/shop/orders";
        }
        if (!model.containsAttribute("when") && order.getDueDate() != null) {
            model.addAttribute("when", DAY_SUB.format(order.getDueDate()));
        }
        Packing packing = packing(order.getItems().stream()
                .map(i -> Map.entry(i.getProduct(), i.getQuantity())).toList());
        model.addAttribute("order", order);
        model.addAttribute("boxes", packing.boxes());
        model.addAttribute("weight", packing.weight());
        model.addAttribute("total", MONEY_FORMAT.format(order.getTotalPrice()) + " ₸");
        model.addAttribute("clientName", client.getName());
        model.addAttribute("cartCount", cart(session).size());
        return "shop/done";
    }

    /** Общий ответ для степперов /cart/item/**: JSON для фонового запроса, редирект иначе. */
    private ResponseEntity<Object> respond(Client client, HttpSession session, Long changedProductId, String ajax) {
        if (!AJAX_MARKER.equals(ajax)) {
            return redirect("/shop/catalog");
        }
        CartSummary summary = summarize(client, cart(session));
        CartRow changedRow = summary.rows().stream()
                .filter(r -> r.product().getId().equals(changedProductId))
                .findFirst()
                .orElse(null);
        CartUpdateResponse body = new CartUpdateResponse(
                summary.headerLabel(),
                summary.canOrder(),
                summary.bottomButtonLabel(),
                changedProductId,
                changedRow != null ? changedRow.qtyLabel() : null,
                changedRow == null,
                summary.rows().isEmpty(),
                summary.rows().size());
        return ResponseEntity.ok(body);
    }

    private ResponseEntity<Object> redirectToShop() {
        return redirect("/shop");
    }

    private ResponseEntity<Object> redirect(String location) {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location)).build();
    }

    private CartSummary summarize(Client client, Map<Long, BigDecimal> cart) {
        List<CartRow> rows = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<Long, BigDecimal> entry : cart.entrySet()) {
            Product product;
            try {
                product = productService.findById(entry.getKey());
            } catch (IllegalArgumentException e) {
                continue;
            }
            BigDecimal price = clientPriceService.resolvePrice(client, product);
            BigDecimal sum = price.multiply(entry.getValue());
            total = total.add(sum);
            rows.add(new CartRow(product, entry.getValue(), QTY_FORMAT.format(entry.getValue()),
                    priceUnitLabel(price, product.getUnit()), MONEY_FORMAT.format(sum), step(product)));
        }
        String totalLabel = MONEY_FORMAT.format(total) + " ₸";
        String headerLabel = rows.isEmpty() ? "Ваш заказ" : itemsLabel(rows.size()) + " в заказе на сумму " + totalLabel;
        return new CartSummary(rows, headerLabel, !rows.isEmpty(), "Оформить заказ на " + totalLabel);
    }

    private Client currentClient(HttpSession session) {
        Long clientId = (Long) session.getAttribute(SESSION_CLIENT_ID);
        if (clientId == null) {
            return null;
        }
        try {
            return clientService.findById(clientId);
        } catch (IllegalArgumentException e) {
            session.removeAttribute(SESSION_CLIENT_ID);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<Long, BigDecimal> cart(HttpSession session) {
        Map<Long, BigDecimal> cart = (Map<Long, BigDecimal>) session.getAttribute(SESSION_CART);
        if (cart == null) {
            cart = new LinkedHashMap<>();
            session.setAttribute(SESSION_CART, cart);
        }
        return cart;
    }

    private static String priceUnitLabel(BigDecimal price, String unit) {
        String label = MONEY_FORMAT.format(price) + " ₸";
        return (unit == null || unit.isBlank()) ? label : label + " / " + unit;
    }

    private static BigDecimal step(Product product) {
        return product.getUnit() != null && FRACTIONAL_UNITS.contains(product.getUnit()) ? new BigDecimal("0.1") : BigDecimal.ONE;
    }

    /** Склонение "N товаров/товар/товара" — как в mobile order_form_screen.dart (_itemsLabel). */
    private static String itemsLabel(int n) {
        int mod10 = n % 10, mod100 = n % 100;
        String word;
        if (mod10 == 1 && mod100 != 11) {
            word = "товар";
        } else if (mod10 >= 2 && mod10 <= 4 && (mod100 < 10 || mod100 >= 20)) {
            word = "товара";
        } else {
            word = "товаров";
        }
        return n + " " + word;
    }

    public record CartRow(Product product, BigDecimal quantity, String qtyLabel, String priceLabel,
                           String sumLabel, BigDecimal step) {
    }

    /**
     * Заказ в истории (shop/app.html, view = orders). statusKey — new/work/ready/done/off для цвета
     * метки; netTotal — «заказ минус возвраты», только если возвраты есть.
     */
    public record OrderView(Long id, String whenLabel, String statusKey, String statusLabel, boolean active,
                            int positions, String boxes, String weight, String total, String returnTotal,
                            String netTotal, List<ItemLine> lines, List<ReturnRow> returns, String notes) {
        public Long number() {
            return id;
        }
    }

    public record ItemLine(String name, String qtyLabel, String sumLabel) {
    }

    public record ReturnRow(String dateLabel, String documentNumber, String reason, List<ItemLine> items,
                            String totalLabel) {
    }

    private record CartSummary(List<CartRow> rows, String headerLabel, boolean canOrder, String bottomButtonLabel) {
    }

    /** Ответ степпера в фоновом режиме (см. shop/catalog.html и быстрое добавление в shop/products.html). */
    public record CartUpdateResponse(String headerLabel, boolean canOrder, String bottomButtonLabel,
                                      Long productId, String qtyLabel, boolean removed, boolean empty,
                                      int cartCount) {
    }
}
