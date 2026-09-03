package com.confectionery.crm.service;

import com.confectionery.crm.domain.*;
import com.confectionery.crm.repository.WhatsAppInboundMessageRepository;
import com.confectionery.crm.web.dto.OrderForm;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Приём заказов из WhatsApp: сообщение клиента -> распознавание позиций через
 * OrderParser (Claude или локальная Ollama — см. crm.order-parser.engine) -> ВСЕГДА запись
 * PENDING на экране /whatsapp-inbox, даже если ИИ распознал все позиции уверенно. Заказ
 * создаётся и клиенту уходит подтверждение только после того, как администратор нажмёт
 * «Подтвердить» (см. resolvePending) — до этого клиенту не отправляется вообще ничего. Так
 * исключены ложные срабатывания ИИ (например, "буду в 15:00" тоже содержит цифру и раньше
 * могло создать заказ и уйти клиенту без участия человека). Каждое ручное уточнение
 * запоминается как ProductAlias — так распознавание "учится" на разговорных названиях
 * конкретных клиентов ("розочка" и т.п.).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WhatsAppOrderIntakeService {

    private final ClientService clientService;
    private final ProductService productService;
    private final OrderService orderService;
    private final NotificationService notificationService;
    private final OrderParser parserService;
    private final WhatsAppGatewayClient gatewayClient;
    private final WhatsAppInboundMessageRepository inboundRepository;

    /**
     * Дешёвый локальный фильтр до вызова Claude API: реальный заказ у наших клиентов всегда
     * содержит хотя бы одну цифру (количество) — "кекс 2", "маффины 150" и т.п. Обычная
     * переписка ("привет", "спасибо", вопросы) отсекается бесплатно, без обращения к ИИ и
     * без создания клиента в базе на пустом месте.
     */
    private static final java.util.regex.Pattern LOOKS_LIKE_ORDER = java.util.regex.Pattern.compile("\\d");

    @Transactional
    public void handleIncomingMessage(String fromPhone, String text, String contactName) {
        if (!LOOKS_LIKE_ORDER.matcher(text).find()) {
            log.debug("Сообщение от {} не похоже на заказ (нет цифр) — Claude API не вызываем: {}", fromPhone, text);
            return;
        }

        Client client = clientService.findByPhone(fromPhone)
                .orElseGet(() -> clientService.save(newClientFromWhatsApp(fromPhone, contactName)));

        OrderParser.ParsedOrder parsed;
        try {
            parsed = parserService.parse(text, productService.findAllActive(), productService.aliasesGroupedByProduct());
        } catch (Exception e) {
            log.warn("Не удалось распознать заказ из WhatsApp от {}: {}", fromPhone, e.getMessage());
            notificationService.notifyRole(Role.ADMIN,
                    "WhatsApp: не удалось распознать заказ от " + client.getName() + " (" + fromPhone + "): " + text, null);
            return;
        }

        if (parsed.lines().isEmpty()) {
            notificationService.notifyRole(Role.ADMIN,
                    "WhatsApp: не удалось найти позиции заказа в сообщении от " + client.getName()
                            + " (" + fromPhone + "): " + text, null);
            return;
        }

        WhatsAppInboundMessage inbound = new WhatsAppInboundMessage();
        inbound.setClient(client);
        inbound.setFromPhone(fromPhone);
        inbound.setRawText(text);
        for (OrderParser.ParsedLine line : parsed.lines()) {
            WhatsAppInboundItem item = new WhatsAppInboundItem();
            item.setRawProductText(line.rawProductText());
            item.setQuantity(line.quantity());
            item.setProduct(line.product());
            inbound.addItem(item);
        }
        inboundRepository.save(inbound);

        // Клиенту на этом этапе НИЧЕГО не отправляем — только уведомление админу (см. javadoc
        // класса). Заказ создастся и клиент получит подтверждение только после resolvePending.
        String readiness = parsed.allMatched()
                ? "все позиции распознаны — можно сразу подтвердить"
                : "нужно уточнить часть позиций";
        notificationService.notifyRole(Role.ADMIN,
                "WhatsApp: новый заказ от " + client.getName() + " (" + fromPhone + "), " + readiness
                        + " — откройте раздел «WhatsApp-заказы»", null);
    }

    /**
     * Администратор доопределил товар для нераспознанных позиций (см. web/WhatsAppInboxController
     * и api/WhatsAppInboxApiController — общая логика для веб- и мобильного экрана уточнения).
     * @param productIdByItemId id позиции WhatsAppInboundItem -&gt; id выбранного товара, только
     *                          для позиций без товара (уже сопоставленные — пропускаются).
     */
    @Transactional
    public void resolvePending(Long messageId, Map<Long, Long> productIdByItemId) {
        WhatsAppInboundMessage inbound = inboundRepository.findById(messageId)
                .orElseThrow(() -> new IllegalArgumentException("Сообщение не найдено: " + messageId));
        if (inbound.getStatus() != WhatsAppInboundStatus.PENDING) {
            throw new IllegalStateException("Сообщение уже обработано");
        }
        for (WhatsAppInboundItem item : inbound.getItems()) {
            if (item.getProduct() != null) continue;
            Long productId = productIdByItemId.get(item.getId());
            if (productId == null) {
                throw new IllegalArgumentException("Выберите товар для позиции \"" + item.getRawProductText() + "\"");
            }
            Product product = productService.findById(productId);
            item.setProduct(product);
            productService.saveAliasIfNew(product, item.getRawProductText());
        }

        // createOrderFrom -> OrderService.createOrder уже уведомляет WAREHOUSE_FG и ACCOUNTANT
        // (тот же путь, что у обычного ручного заказа) — админа здесь повторно не пушим, он и
        // так знает: это он только что подтвердил уточнение.
        Order order = createOrderFrom(inbound);
        inbound.setStatus(WhatsAppInboundStatus.RESOLVED);
        inbound.setResolvedOrderId(order.getId());
        gatewayClient.sendMessage(inbound.getFromPhone(), confirmationText(order));
    }

    @Transactional
    public void ignore(Long messageId) {
        WhatsAppInboundMessage inbound = inboundRepository.findById(messageId)
                .orElseThrow(() -> new IllegalArgumentException("Сообщение не найдено: " + messageId));
        inbound.setStatus(WhatsAppInboundStatus.IGNORED);
    }

    private Order createOrderFrom(WhatsAppInboundMessage inbound) {
        OrderForm form = new OrderForm();
        form.setClientId(inbound.getClient().getId());
        form.setNotes("Заказ через WhatsApp:\n" + inbound.getRawText());
        form.setFromWhatsapp(true);
        for (WhatsAppInboundItem item : inbound.getItems()) {
            OrderForm.OrderItemForm itemForm = new OrderForm.OrderItemForm();
            itemForm.setProductId(item.getProduct().getId());
            itemForm.setQuantity(item.getQuantity());
            form.getItems().add(itemForm);
        }
        return orderService.createOrder(form);
    }

    private Client newClientFromWhatsApp(String phone, String contactName) {
        Client client = new Client();
        client.setName(contactName != null && !contactName.isBlank() ? contactName : "Клиент " + phone);
        client.setPhone(phone);
        client.setNotes("Создан автоматически из WhatsApp");
        return client;
    }

    private String confirmationText(Order order) {
        String items = order.getItems().stream()
                .map(i -> "- " + i.getProduct().getName() + " x" + i.getQuantity().stripTrailingZeros().toPlainString())
                .collect(Collectors.joining("\n"));
        return "Здравствуйте! Ваш заказ №" + order.getId() + " принят:\n" + items
                + "\n\nИтого: " + order.getTotalPrice() + " ₸"
                + "\n\nМы свяжемся с вами для уточнения деталей.";
    }
}
