package com.confectionery.crm.admin;

import com.confectionery.crm.domain.Product;
import com.confectionery.crm.domain.ProductPhoto;
import com.confectionery.crm.domain.Role;
import com.confectionery.crm.repository.ProductRepository;
import com.confectionery.crm.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static com.confectionery.crm.admin.AdminSupport.*;

/**
 * Товары (карточка товара: название, группы, вес, коробка, объём, цена, показ на сайте) и фото товаров
 * (альбом для сайта: обложка — первое фото, порядок, загрузка, удаление). Править — только администратор.
 */
@Controller
@RequiredArgsConstructor
public class AdminProductController {

    private final ProductService productService;
    private final ProductRepository productRepository;
    private final ProductPhotoService photoService;
    private final OrderPriceFillService orderPriceFillService;
    private final CurrentUserService currentUserService;
    private final PromoPageService promoPageService;

    public record Chip(String key, String label, int count, boolean on, String href) {
    }

    private static String cat(Product p) {
        return p.getCategory() == null || p.getCategory().isBlank() ? "Без группы" : p.getCategory().trim();
    }

    @GetMapping("/admin/products")
    @Transactional(readOnly = true)
    public String list(@RequestParam(defaultValue = "all") String group, @RequestParam(defaultValue = "") String q,
                       @RequestParam(defaultValue = "0") int archived, @RequestParam(defaultValue = "0") int pf, Model model) {
        String nq = AdminOrderController.norm(q);
        List<Product> all = productService.findAll().stream()
                .filter(p -> archived == 1 ? !p.isActive() : p.isActive())
                .filter(p -> pf == 1 || !p.isSemiFinished())
                .filter(p -> nq.isEmpty() || AdminOrderController.norm(p.getName()).contains(nq))
                .sorted(Comparator.comparing(Product::getName, String.CASE_INSENSITIVE_ORDER)).toList();
        Map<String, Integer> counts = new TreeMap<>();
        all.forEach(p -> counts.merge(cat(p), 1, Integer::sum));
        String base = "&q=" + java.net.URLEncoder.encode(q, java.nio.charset.StandardCharsets.UTF_8)
                + (archived == 1 ? "&archived=1" : "") + (pf == 1 ? "&pf=1" : "");
        List<Chip> chips = new ArrayList<>();
        chips.add(new Chip("all", "Все", all.size(), group.equals("all"), "/admin/products?group=all" + base));
        counts.forEach((k, v) -> chips.add(new Chip(k, k, v, group.equals(k),
                "/admin/products?group=" + java.net.URLEncoder.encode(k, java.nio.charset.StandardCharsets.UTF_8) + base)));
        model.addAttribute("rows", all.stream().filter(p -> group.equals("all") || cat(p).equals(group)).toList());
        model.addAttribute("chips", chips);
        model.addAttribute("group", group);
        model.addAttribute("q", q);
        model.addAttribute("archived", archived == 1);
        model.addAttribute("pf", pf == 1);
        model.addAttribute("canEdit", isAdmin());
        page(model, "Товары", "каталог, цены, упаковка и показ на сайте", "products");
        return "admin/products/list";
    }

    private boolean isAdmin() {
        return currentUserService.hasRole(Role.ADMIN);
    }

    @GetMapping({"/admin/products/new", "/admin/products/{id:\\d+}"})
    @Transactional(readOnly = true)
    public String form(@PathVariable(required = false) Long id, Model model) {
        Product p = id == null ? new Product() : productService.findById(id);
        if (id == null) p.setUnit("шт");
        return form(p, List.of(), model);
    }

    private String form(Product p, List<String> errors, Model model) {
        Set<String> cats = new TreeSet<>(), groups = new TreeSet<>();
        for (Product x : productService.findAll()) {
            if (x.getCategory() != null && !x.getCategory().isBlank()) cats.add(x.getCategory().trim());
            if (x.getGroup() != null && !x.getGroup().isBlank()) groups.add(x.getGroup().trim());
        }
        model.addAttribute("p", p);
        model.addAttribute("cats", cats);
        model.addAttribute("groups", groups);
        model.addAttribute("errors", errors);
        model.addAttribute("canEdit", isAdmin());
        model.addAttribute("box", p.getId() == null ? null : AdminOrders.boxUnits(p));
        page(model, "Товары", p.getId() == null ? "новый товар" : p.getName(), "products");
        return "admin/products/form";
    }

    @PostMapping({"/admin/products", "/admin/products/{id:\\d+}"})
    public String save(@PathVariable(required = false) Long id, @RequestParam Map<String, String> f, Model model, RedirectAttributes ra) {
        if (!isAdmin()) {
            fail(ra, new IllegalStateException("Менять товары может администратор"));
            return "redirect:/admin/products";
        }
        Product p = id == null ? new Product() : productService.findById(id);
        BigDecimal oldPrice = p.getPrice();
        List<String> errors = new ArrayList<>();
        p.setName(f.getOrDefault("name", "").trim());
        p.setCategory(blank(f.get("category")));
        p.setGroup(blank(f.get("group")));
        p.setDescription(blank(f.get("description")));
        p.setUnit(f.getOrDefault("unit", "шт").isBlank() ? "шт" : f.get("unit").trim());
        BigDecimal price = num(f.get("price"));
        p.setPrice(price == null ? BigDecimal.ZERO : price);
        p.setNetWeightGrams(num(f.get("netWeightGrams")));
        p.setUnitWeightKg(num(f.get("unitWeightKg")));
        p.setUnitsPerBox(num(f.get("unitsPerBox")));
        p.setBoxVolumeM3(num(f.get("boxVolumeM3")));
        p.setUnitsPerBox2(num(f.get("unitsPerBox2")));
        p.setBoxVolume2M3(num(f.get("boxVolume2M3")));
        p.setSemiFinished("true".equals(f.get("semiFinished")));
        if (p.getName().isEmpty()) errors.add("Укажите название товара.");
        if (price == null || price.signum() < 0) errors.add("Укажите цену (0 или больше).");
        if (errors.isEmpty()) {
            try {
                Product saved = productService.save(p);
                if (id != null && oldPrice != null && saved.getPrice().compareTo(oldPrice) != 0) {
                    orderPriceFillService.afterPriceChange(null, id);
                }
                ok(ra, "Товар «" + saved.getName() + "» сохранён");
                return "redirect:/admin/products/" + saved.getId();
            } catch (RuntimeException e) {
                errors.add(e.getMessage());
            }
        }
        return form(p, errors, model);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    @PostMapping("/admin/products/{id:\\d+}/visibility")
    public String visibility(@PathVariable Long id, @RequestParam boolean hidden, @RequestParam(required = false) String until,
                             RedirectAttributes ra) {
        try {
            if (!isAdmin()) throw new IllegalStateException("Скрыть товар может администратор");
            LocalDate u = until == null || until.isBlank() ? null : LocalDate.parse(until);
            Product p = productService.setHiddenFromClients(id, hidden, u);
            ok(ra, hidden ? "«" + p.getName() + "» скрыт от покупателей" + (u != null ? " до " + u.getDayOfMonth() + "." + u.getMonthValue() : "")
                    : "«" + p.getName() + "» снова на витрине");
        } catch (RuntimeException e) {
            fail(ra, e);
        }
        return "redirect:/admin/products/" + id;
    }

    @PostMapping("/admin/products/{id:\\d+}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        try {
            if (!isAdmin()) throw new IllegalStateException("Удалить товар может администратор");
            ProductService.DeleteOutcome out = productService.delete(id);
            ok(ra, out == ProductService.DeleteOutcome.ARCHIVED
                    ? "У товара есть история — он перенесён в архив" : "Товар удалён");
            return "redirect:/admin/products";
        } catch (RuntimeException e) {
            fail(ra, e);
            return "redirect:/admin/products/" + id;
        }
    }

    @PostMapping("/admin/products/{id:\\d+}/restore")
    public String restore(@PathVariable Long id, RedirectAttributes ra) {
        try {
            if (!isAdmin()) throw new IllegalStateException("Вернуть товар может администратор");
            productService.restore(id);
            ok(ra, "Товар возвращён из архива");
        } catch (RuntimeException e) {
            fail(ra, e);
        }
        return "redirect:/admin/products/" + id;
    }

    // ------------------------------------------------------------------ фото товаров

    @GetMapping("/admin/photos")
    @Transactional(readOnly = true)
    public String photos(@RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "0") int empty, Model model) {
        String nq = AdminOrderController.norm(q);
        List<Product> rows = productService.findAllActiveForManagement().stream()
                .filter(p -> nq.isEmpty() || AdminOrderController.norm(p.getName()).contains(nq))
                .filter(p -> empty == 0 || p.getPhotos().isEmpty() && (p.getImageUrl() == null || p.getImageUrl().isBlank()))
                .sorted(Comparator.comparing(Product::getName, String.CASE_INSENSITIVE_ORDER)).toList();
        model.addAttribute("rows", rows);
        model.addAttribute("q", q);
        model.addAttribute("empty", empty == 1);
        page(model, "Фото товаров", "обложка и альбом для сайта и магазина", "photos");
        return "admin/photos/list";
    }

    @GetMapping("/admin/photos/{id:\\d+}")
    @Transactional(readOnly = true)
    public String productPhotos(@PathVariable Long id, @RequestParam(required = false) Long sel, Model model) {
        Product p = productService.findById(id);
        List<ProductPhoto> album = new ArrayList<>(p.getPhotos());
        album.sort(Comparator.comparingInt(ProductPhoto::getPosition));
        model.addAttribute("p", p);
        model.addAttribute("album", album);
        model.addAttribute("card", promoPageService.card(p));
        model.addAttribute("sel", sel);
        // слоты рядами по 6, как в макете; хотя бы один пустой — «Добавить»
        model.addAttribute("emptySlots", Math.max(1, (6 - album.size() % 6) % 6));
        page(model, "Фото товаров", p.getName(), "photos");
        return "admin/photos/product";
    }

    @PostMapping("/admin/photos/{id:\\d+}/add")
    public String addPhotos(@PathVariable Long id, @RequestParam("files") List<MultipartFile> files, RedirectAttributes ra) {
        try {
            List<MultipartFile> real = files.stream().filter(f -> !f.isEmpty()).toList();
            if (real.isEmpty()) throw new IllegalArgumentException("Выберите фото");
            photoService.add(id, real);
            ok(ra, "Добавлено фото: " + real.size());
        } catch (RuntimeException e) {
            fail(ra, e);
        }
        return "redirect:/admin/photos/" + id;
    }

    @PostMapping("/admin/photos/{id:\\d+}/{photoId}/delete")
    public String deletePhoto(@PathVariable Long id, @PathVariable Long photoId, RedirectAttributes ra) {
        try {
            photoService.delete(id, photoId);
            ok(ra, "Фото удалено");
        } catch (RuntimeException e) {
            fail(ra, e);
        }
        return "redirect:/admin/photos/" + id;
    }

    /** Сдвинуть фото: dir = up / down / first (сделать обложкой). */
    @PostMapping("/admin/photos/{id:\\d+}/{photoId}/move")
    public String move(@PathVariable Long id, @PathVariable Long photoId, @RequestParam String dir, RedirectAttributes ra) {
        try {
            Product p = productService.findById(id);
            List<ProductPhoto> album = new ArrayList<>(p.getPhotos());
            album.sort(Comparator.comparingInt(ProductPhoto::getPosition));
            List<Long> ids = new ArrayList<>(album.stream().map(ProductPhoto::getId).toList());
            int i = ids.indexOf(photoId);
            if (i < 0) throw new IllegalArgumentException("Фото не найдено");
            ids.remove(i);
            int to = switch (dir) {
                case "up" -> Math.max(0, i - 1);
                case "down" -> Math.min(ids.size(), i + 1);
                default -> 0;
            };
            ids.add(to, photoId);
            photoService.reorder(id, ids);
            ok(ra, to == 0 ? "Фото стало обложкой" : "Порядок фото изменён");
        } catch (RuntimeException e) {
            fail(ra, e);
        }
        return "redirect:/admin/photos/" + id + "?sel=" + photoId;
    }
}
