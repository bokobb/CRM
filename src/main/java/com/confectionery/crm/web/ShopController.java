package com.confectionery.crm.web;

import com.confectionery.crm.domain.Client;
import com.confectionery.crm.domain.Order;
import com.confectionery.crm.domain.OrderItem;
import com.confectionery.crm.domain.Product;
import com.confectionery.crm.domain.ProductReturn;
import com.confectionery.crm.service.ClientPriceService;
import com.confectionery.crm.service.ClientService;
import com.confectionery.crm.service.OrderService;
import com.confectionery.crm.service.ProductReturnService;
import com.confectionery.crm.service.ProductService;
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

    private static final String SESSION_CLIENT_ID = "shopClientId";
    private static final String SESSION_CLIENT_NAME = "shopClientName";
    private static final String SESSION_CART = "shopCart";
    private static final String AJAX_MARKER = "fetch";

    /**
     * Номер телефона запоминается в отдельной долгоживущей cookie (не в сессии — та живёт
     * только пока открыт браузер/до истечения таймаута на сервере, а после перезапуска
     * backend сессии вообще пропадают). Так клиенту не нужно вводить номер заново при
     * каждом визите — только один раз, дальше сессия на /shop поднимается автоматически.
     */
    private static final String PHONE_COOKIE = "shop_phone";
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

    @GetMapping
    public String phoneForm(HttpSession session, HttpServletRequest request) {
        if (session.getAttribute(SESSION_CLIENT_ID) != null) {
            return "redirect:/shop/products";
        }
        String rememberedPhone = readPhoneCookie(request);
        if (rememberedPhone != null) {
            Client client = clientService.findByPhone(rememberedPhone).orElse(null);
            if (client != null) {
                session.setAttribute(SESSION_CLIENT_ID, client.getId());
                session.setAttribute(SESSION_CLIENT_NAME, client.getName());
                return "redirect:/shop/products";
            }
        }
        return "shop/phone";
    }

    @PostMapping("/start")
    public String start(@RequestParam String phone, @RequestParam(required = false) String name,
                         HttpSession session, HttpServletResponse response, RedirectAttributes redirectAttributes) {
        if (phone == null || phone.replaceAll("\\D", "").isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Укажите номер телефона");
            return "redirect:/shop";
        }
        Client client = clientService.findByPhone(phone).orElseGet(() -> {
            Client c = new Client();
            c.setPhone(phone.trim());
            c.setName((name == null || name.isBlank()) ? "Клиент " + phone.trim() : name.trim());
            return clientService.save(c);
        });
        session.setAttribute(SESSION_CLIENT_ID, client.getId());
        session.setAttribute(SESSION_CLIENT_NAME, client.getName());
        writePhoneCookie(response, client.getPhone());
        return "redirect:/shop/products";
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
        cookie.setPath("/shop");
        cookie.setMaxAge(PHONE_COOKIE_MAX_AGE);
        cookie.setHttpOnly(true);
        response.addCookie(cookie);
    }

    private void clearPhoneCookie(HttpServletResponse response) {
        Cookie cookie = new Cookie(PHONE_COOKIE, "");
        cookie.setPath("/shop");
        cookie.setMaxAge(0);
        cookie.setHttpOnly(true);
        response.addCookie(cookie);
    }

    /** Экран заказа — аналог order_form_screen.dart: позиции корзины + степпер + итог. */
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
        CartSummary summary = summarize(client, cart(session));
        model.addAttribute("clientName", client.getName());
        model.addAttribute("rows", summary.rows());
        model.addAttribute("canOrder", summary.canOrder());
        model.addAttribute("headerLabel", summary.headerLabel());
        model.addAttribute("bottomButtonLabel", summary.bottomButtonLabel());
        model.addAttribute("cartCount", cart(session).size());
        return "shop/catalog";
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

    /** Главный экран витрины — карточки товаров по категориям, тап по карточке открывает товар крупным планом. */
    @GetMapping("/products")
    public String products(HttpSession session, Model model) {
        Client client = currentClient(session);
        if (client == null) {
            return "redirect:/shop";
        }
        Map<String, List<Product>> byCategory = productService.groupByCategory(productService.findVisibleToClients());
        Map<String, List<ProductRow>> rowsByCategory = new LinkedHashMap<>();
        for (Map.Entry<String, List<Product>> entry : byCategory.entrySet()) {
            rowsByCategory.put(entry.getKey(), entry.getValue().stream()
                    .map(p -> new ProductRow(p, priceUnitLabel(clientPriceService.resolvePrice(client, p), p.getUnit())))
                    .toList());
        }
        model.addAttribute("clientName", client.getName());
        model.addAttribute("rowsByCategory", rowsByCategory);
        model.addAttribute("cartCount", cart(session).size());
        return "shop/products";
    }

    /**
     * Товар крупным планом — аналог product_quantity_screen.dart. Степпер здесь целиком на
     * стороне браузера (см. shop/product.html) — сервер отдаёт только начальное количество
     * (то, что уже в корзине, или один шаг) и цену для расчёта суммы в JS.
     */
    @GetMapping("/products/{id}")
    public String productDetail(@PathVariable Long id, HttpSession session, Model model) {
        Client client = currentClient(session);
        if (client == null) {
            return "redirect:/shop";
        }
        Product product = productService.findById(id);
        if (!product.isVisibleToClients()) {
            return "redirect:/shop/products";
        }
        BigDecimal step = step(product);
        BigDecimal quantity = cart(session).getOrDefault(id, step);
        if (quantity.compareTo(step) < 0) {
            quantity = step;
        }
        BigDecimal price = clientPriceService.resolvePrice(client, product);
        boolean isEditing = cart(session).containsKey(id);
        model.addAttribute("product", product);
        model.addAttribute("priceLabel", priceUnitLabel(price, product.getUnit()));
        model.addAttribute("qty", quantity);
        model.addAttribute("qtyLabel", QTY_FORMAT.format(quantity));
        model.addAttribute("priceRaw", price.toPlainString());
        model.addAttribute("stepRaw", step.toPlainString());
        model.addAttribute("addLabelPrefix", isEditing ? "Сохранить за " : "Добавить за ");
        model.addAttribute("addButtonLabel", (isEditing ? "Сохранить за " : "Добавить за ")
                + MONEY_FORMAT.format(price.multiply(quantity)) + " ₸");
        return "shop/product";
    }

    @PostMapping("/products/{id}/add")
    public String addToCart(@PathVariable Long id, @RequestParam BigDecimal qty, HttpSession session) {
        if (currentClient(session) == null) {
            return "redirect:/shop";
        }
        if (productService.findById(id).isVisibleToClients()) {
            cart(session).put(id, qty);
        }
        return "redirect:/shop/products";
    }

    @PostMapping("/cart/submit")
    public String submit(HttpSession session, RedirectAttributes redirectAttributes) {
        Client client = currentClient(session);
        if (client == null) {
            return "redirect:/shop";
        }
        Map<Long, BigDecimal> cart = cart(session);
        OrderForm form = new OrderForm();
        form.setClientId(client.getId());
        form.setFromShop(true);
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
            return "redirect:/shop/confirmation/" + order.getId();
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/shop/catalog";
        }
    }

    private static final DateTimeFormatter WEEK_FORMAT = DateTimeFormatter.ofPattern("dd.MM");

    /**
     * История заказов клиента — все каналы (сама витрина, WhatsApp, ручной ввод), не только /shop.
     * Показывается по календарным неделям (пн–вс) по дате заказа: {@code week} — сколько недель
     * назад, 0 — текущая. У каждого заказа — закреплённые за ним возвраты (кнопка «Показать возврат»).
     */
    @GetMapping("/orders")
    public String orders(@RequestParam(defaultValue = "0") int week, HttpSession session, Model model) {
        Client client = currentClient(session);
        if (client == null) {
            return "redirect:/shop";
        }
        int weeksAgo = Math.max(week, 0);
        LocalDate from = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(weeksAgo);
        LocalDate to = from.plusDays(6);
        List<OrderHistoryRow> rows = orderService.findByClient(client.getId()).stream()
                .filter(o -> {
                    LocalDate d = o.getCreatedAt().toLocalDate();
                    return !d.isBefore(from) && !d.isAfter(to);
                })
                .map(this::historyRow)
                .toList();
        model.addAttribute("clientName", client.getName());
        model.addAttribute("rows", rows);
        model.addAttribute("week", weeksAgo);
        model.addAttribute("weekLabel", weeksAgo == 0 ? "Эта неделя" : weeksAgo == 1 ? "Прошлая неделя"
                : WEEK_FORMAT.format(from) + " – " + WEEK_FORMAT.format(to));
        model.addAttribute("weekRange", WEEK_FORMAT.format(from) + " – " + WEEK_FORMAT.format(to));
        model.addAttribute("cartCount", cart(session).size());
        return "shop/orders";
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

    private OrderHistoryRow historyRow(Order o) {
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
        return new OrderHistoryRow(o, items, MONEY_FORMAT.format(o.getTotalPrice()) + " ₸", returns,
                returns.isEmpty() ? null : "−" + MONEY_FORMAT.format(returnTotal) + " ₸");
    }

    private ItemLine itemLine(Product product, BigDecimal quantity, BigDecimal price) {
        String unit = product.getUnit() == null || product.getUnit().isBlank() ? "шт" : product.getUnit();
        BigDecimal sum = price == null ? null : price.multiply(quantity);
        return new ItemLine(product.getName(), QTY_FORMAT.format(quantity) + " " + unit,
                sum == null ? "" : MONEY_FORMAT.format(sum) + " ₸");
    }

    @GetMapping("/confirmation/{orderId}")
    public String confirmation(@PathVariable Long orderId, Model model) {
        Order order = orderService.findById(orderId);
        model.addAttribute("order", order);
        model.addAttribute("totalLabel", MONEY_FORMAT.format(order.getTotalPrice()));
        return "shop/confirmation";
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
        return FRACTIONAL_UNITS.contains(product.getUnit()) ? new BigDecimal("0.1") : BigDecimal.ONE;
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

    public record ProductRow(Product product, String priceLabel) {
    }

    public record OrderHistoryRow(Order order, List<ItemLine> items, String totalLabel,
                                  List<ReturnRow> returns, String returnTotalLabel) {
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
