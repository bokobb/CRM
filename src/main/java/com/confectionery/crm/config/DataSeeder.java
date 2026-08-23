package com.confectionery.crm.config;

import com.confectionery.crm.domain.*;
import com.confectionery.crm.repository.*;
import com.confectionery.crm.service.ClientPriceService;
import com.confectionery.crm.service.OrderService;
import com.confectionery.crm.web.dto.OrderForm;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Наполняет пустую базу демо-данными, чтобы CRM сразу можно было пощупать:
 * склад, товары с рецептурой, клиентов, индивидуальные цены и пару заказов на разных стадиях
 * дистрибьюторского потока (заказ -> подтверждён/готов -> отгружен). Товары и пользователи
 * заполняются независимо друг от друга (отдельные guard'ы), чтобы на уже засеянной базе
 * при добавлении новой фичи (роли) демо-пользователи всё равно создались.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataSeeder implements CommandLineRunner {

    private final ProductRepository productRepository;
    private final IngredientRepository ingredientRepository;
    private final RecipeRepository recipeRepository;
    private final ClientRepository clientRepository;
    private final UserRepository userRepository;
    private final OrderService orderService;
    private final ClientPriceService clientPriceService;
    private final PasswordEncoder passwordEncoder;

    @Value("${crm.seed-demo-data:true}")
    private boolean seedEnabled;

    @Override
    @Transactional
    public void run(String... args) {
        if (!seedEnabled) {
            return;
        }
        if (userRepository.count() == 0) {
            seedUsers();
        }
        if (productRepository.count() > 0) {
            return;
        }
        log.info("Пустая база — заполняю демо-данными");

        Map<String, Ingredient> ingredients = seedIngredients();
        Map<String, Product> products = seedProducts(ingredients);
        seedClients();
        seedDemoOrders(products);
    }

    private void seedUsers() {
        log.info("Пользователей нет — создаю демо-учётки по одной на роль");
        user("admin", "admin123", Role.ADMIN, "Администратор");
        user("sales", "sales123", Role.SALES, "Менеджер по продажам");
        user("warehouse", "warehouse123", Role.WAREHOUSE_FG, "Кладовщик (склад ГП)");
        user("accountant", "accountant123", Role.ACCOUNTANT, "Бухгалтер");
    }

    private void user(String username, String rawPassword, Role role, String fullName) {
        User u = new User();
        u.setUsername(username);
        u.setPasswordHash(passwordEncoder.encode(rawPassword));
        u.setRole(role);
        u.setFullName(fullName);
        userRepository.save(u);
    }

    private Map<String, Ingredient> seedIngredients() {
        Map<String, Ingredient> map = new HashMap<>();
        map.put("Мука", ingredient("Мука пшеничная", "кг", "20", "5"));
        map.put("Сахар", ingredient("Сахар", "кг", "15", "4"));
        map.put("Яйца", ingredient("Яйца", "шт", "120", "24"));
        map.put("Масло", ingredient("Масло сливочное", "кг", "8", "2"));
        map.put("Молоко", ingredient("Молоко", "л", "10", "3"));
        map.put("Мёд", ingredient("Мёд", "кг", "3", "1"));
        map.put("Сметана", ingredient("Сметана", "кг", "6", "2"));
        map.put("Клубника", ingredient("Клубника", "кг", "1.5", "2"));
        map.put("Шоколад", ingredient("Шоколад тёмный", "кг", "4", "1"));
        return map;
    }

    private Ingredient ingredient(String name, String unit, String qty, String min) {
        Ingredient i = new Ingredient();
        i.setName(name);
        i.setUnit(unit);
        i.setQuantityInStock(new BigDecimal(qty));
        i.setMinThreshold(new BigDecimal(min));
        return ingredientRepository.save(i);
    }

    private Map<String, Product> seedProducts(Map<String, Ingredient> ing) {
        Map<String, Product> map = new HashMap<>();

        Product medovik = product("Торт «Медовик»", "Торты", "1800", "Классический медовый торт, 1 кг");
        recipe(medovik, "1", Map.of(ing.get("Мука"), "0.5", ing.get("Мёд"), "0.2",
                ing.get("Яйца"), "3", ing.get("Сметана"), "0.6", ing.get("Сахар"), "0.15"));
        map.put("medovik", medovik);

        Product napoleon = product("Торт «Наполеон»", "Торты", "1900", "Слоёный торт с заварным кремом, 1 кг");
        recipe(napoleon, "1", Map.of(ing.get("Мука"), "0.4", ing.get("Масло"), "0.3",
                ing.get("Яйца"), "4", ing.get("Молоко"), "0.5", ing.get("Сахар"), "0.2"));
        map.put("napoleon", napoleon);

        Product cupcake = product("Капкейк ванильный", "Капкейки", "180", "Ванильный капкейк со сливочным кремом");
        recipe(cupcake, "12", Map.of(ing.get("Мука"), "0.5", ing.get("Сахар"), "0.4",
                ing.get("Яйца"), "4", ing.get("Масло"), "0.25", ing.get("Молоко"), "0.2"));
        map.put("cupcake", cupcake);

        Product tart = product("Тарт с клубникой", "Пирожные", "450", "Песочный тарт со свежей клубникой и кремом");
        recipe(tart, "4", Map.of(ing.get("Мука"), "0.3", ing.get("Масло"), "0.15",
                ing.get("Клубника"), "0.4", ing.get("Сахар"), "0.1", ing.get("Яйца"), "1"));
        map.put("tart", tart);

        Product brownie = product("Шоколадный брауни", "Пирожные", "220", "Влажный шоколадный брауни");
        recipe(brownie, "8", Map.of(ing.get("Шоколад"), "0.3", ing.get("Масло"), "0.2",
                ing.get("Сахар"), "0.25", ing.get("Яйца"), "3", ing.get("Мука"), "0.15"));
        map.put("brownie", brownie);

        return map;
    }

    private Product product(String name, String category, String price, String description) {
        Product p = new Product();
        p.setName(name);
        p.setCategory(category);
        p.setPrice(new BigDecimal(price));
        p.setDescription(description);
        return productRepository.save(p);
    }

    private void recipe(Product product, String yield, Map<Ingredient, String> items) {
        Recipe recipe = new Recipe();
        recipe.setProduct(product);
        recipe.setYieldQuantity(new BigDecimal(yield));
        items.forEach((ingredient, qty) -> {
            RecipeItem item = new RecipeItem();
            item.setIngredient(ingredient);
            item.setQuantity(new BigDecimal(qty));
            recipe.addItem(item);
        });
        recipeRepository.save(recipe);
    }

    private void seedClients() {
        client("Анна Кузнецова", "+7 900 111-22-33", "anna@example.com", "ул. Ленина, 10");
        client("Игорь Смирнов", "+7 900 222-33-44", "igor@example.com", "пр. Мира, 5");
        client("Кафе «Уют»", "+7 900 333-44-55", "cafe.uyut@example.com", "ул. Садовая, 21");
    }

    private void client(String name, String phone, String email, String address) {
        Client c = new Client();
        c.setName(name);
        c.setPhone(phone);
        c.setEmail(email);
        c.setAddress(address);
        clientRepository.save(c);
    }

    private void seedDemoOrders(Map<String, Product> products) {
        List<Client> clients = clientRepository.findAll();
        if (clients.size() < 3) {
            return;
        }
        Client cafeUyut = clients.get(2);

        // У "Кафе «Уют»" — индивидуальная цена на тарт с клубникой (450 -> 400), демонстрирует
        // как OrderService.createOrder подставляет клиентский прайс вместо базовой цены товара.
        clientPriceService.upsert(cafeUyut.getId(), products.get("tart").getId(), new BigDecimal("400"));

        // Заказ 1: новый, ждёт подтверждения менеджером по продажам
        OrderForm form1 = new OrderForm();
        form1.setClientId(clients.get(0).getId());
        form1.setDueDate(LocalDate.now().plusDays(3));
        form1.setNotes("Написать поздравление 'С Днём Рождения!' на торте");
        form1.getItems().add(itemForm(products.get("medovik"), "1"));
        orderService.createOrder(form1);

        // Заказ 2: подтверждён, готов к отгрузке складом ГП
        OrderForm form2 = new OrderForm();
        form2.setClientId(clients.get(1).getId());
        form2.setDueDate(LocalDate.now().plusDays(1));
        form2.getItems().add(itemForm(products.get("cupcake"), "24"));
        form2.getItems().add(itemForm(products.get("brownie"), "16"));
        var order2 = orderService.createOrder(form2);
        orderService.confirm(order2.getId());

        // Заказ 3: отгружен (для клиента с индивидуальной ценой) — виден бухгалтеру с гос. номером ТС
        OrderForm form3 = new OrderForm();
        form3.setClientId(cafeUyut.getId());
        form3.setDueDate(LocalDate.now().minusDays(1));
        form3.getItems().add(itemForm(products.get("tart"), "8"));
        var order3 = orderService.createOrder(form3);
        orderService.confirm(order3.getId());
        orderService.ship(order3.getId(), "А123ВС799", "Кладовщик (демо)");
    }

    private OrderForm.OrderItemForm itemForm(Product product, String qty) {
        OrderForm.OrderItemForm item = new OrderForm.OrderItemForm();
        item.setProductId(product.getId());
        item.setQuantity(new BigDecimal(qty));
        return item;
    }
}
