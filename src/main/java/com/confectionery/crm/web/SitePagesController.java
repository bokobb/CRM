package com.confectionery.crm.web;

import com.confectionery.crm.domain.CompanyProfile;
import com.confectionery.crm.domain.Vacancy;
import com.confectionery.crm.service.CompanyProfileService;
import com.confectionery.crm.service.VacancyReplyService;
import com.confectionery.crm.service.VacancyService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Year;
import java.util.Arrays;
import java.util.List;

/**
 * Страницы сайта «Контакты» (/contacts) и «Вакансии» (/vacancies) — для гостей, по прямой ссылке
 * (с главной ссылок пока нет: главную не трогаем, пока владелец не разрешит). Шапка и подвал —
 * templates/site/frame.html, стили — копия стилей главной promo/site.css + promo/pages.css.
 */
@Controller
@RequiredArgsConstructor
public class SitePagesController {

    /** Адрес для кнопок маршрута, пока не задана точка ворот (CompanyProfile.mapPoint). */
    private static final String ROUTE_ADDRESS = "улица Жусипбека Елебекова, 128";

    private final CompanyProfileService companyProfileService;
    private final VacancyService vacancyService;
    private final VacancyReplyService vacancyReplyService;

    /** «Как добраться»: точка ворот (lat/lon, 6 знаков) или null — тогда карты нет, кнопки ищут адрес. Карта — виджет Яндекса. */
    public record Route(String lat, String lon, String gisUrl, String yandexUrl, String yandexWidgetUrl) {
        public boolean hasPoint() {
            return lat != null;
        }
    }

    public record VacancyView(Long id, String title, String summary, String schedule, String experience,
                              String salary, List<String> duties, List<String> requirements, List<String> conditions) {
        public boolean hasMore() {
            return !duties.isEmpty() || !requirements.isEmpty() || !conditions.isEmpty();
        }
    }

    /** company и year — те же имена, что у главной (PromoPageService.Page), чтобы подвал читался одинаково. */
    public record SitePage(String current, String baseUrl, CompanyProfile company, int year, String hoursMain,
                           String hoursTail, String jobsPhone, Route route, List<VacancyView> vacancies) {
    }

    @GetMapping("/contacts")
    public String contacts(Model model) {
        model.addAttribute("page", page("contacts"));
        return "site/contacts";
    }

    @GetMapping("/vacancies")
    public String vacancies(Model model) {
        model.addAttribute("page", page("vacancies"));
        return "site/vacancies";
    }

    /** Окно «Откликнуться» (fetch из site/vacancies.html), тот же способ, что у формы «Перезвоните мне». */
    @PostMapping("/vacancies/reply")
    public ResponseEntity<String> reply(@RequestParam(required = false) String vacancyId,
                                        @RequestParam(required = false) String name,
                                        @RequestParam(required = false) String phone,
                                        @RequestParam(required = false) String about,
                                        @RequestParam(required = false) String website,
                                        HttpServletRequest request) {
        Long id;
        try {
            id = vacancyId == null || vacancyId.isBlank() ? null : Long.valueOf(vacancyId.trim());
        } catch (NumberFormatException e) {
            return text(400, "Выберите вакансию из списка.");
        }
        try {
            vacancyReplyService.submit(id, name, phone, about, website, request.getRemoteAddr());
        } catch (VacancyReplyService.ReplyRejected e) {
            return text(400, e.getMessage());
        }
        return ResponseEntity.noContent().build();
    }

    private static ResponseEntity<String> text(int status, String message) {
        return ResponseEntity.status(status).contentType(new MediaType("text", "plain", StandardCharsets.UTF_8)).body(message);
    }

    private SitePage page(String current) {
        CompanyProfile c = companyProfileService.get();
        String hours = blank(c.getWorkHours()) ? null : c.getWorkHours().strip();
        String main = hours, tail = null;
        int cut = hours == null ? -1 : hours.indexOf(", обед");
        if (cut > 0) {                       // «…, обед с 12:00 до 13:00» не разрывается при переносе строки
            main = hours.substring(0, cut + 1);
            tail = hours.substring(cut + 2);
        }
        String jobsPhone = !blank(c.getJobsPhone()) ? c.getJobsPhone().strip()
                : blank(c.getPhone()) ? null : c.getPhone().split("[,;]")[0].strip();
        List<VacancyView> vacancies = "vacancies".equals(current)
                ? vacancyService.findActive().stream().map(SitePagesController::view).toList() : List.of();
        String base = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
        return new SitePage(current, base, c, Year.now().getValue(), main, tail, jobsPhone, route(c.getMapPoint()), vacancies);
    }

    /** «42.123456, 69.654321» → ссылки маршрута; не разобрали — как без точки. */
    static Route route(String mapPoint) {
        if (!blank(mapPoint)) {
            String[] p = mapPoint.split("[,;\\s]+");
            try {
                if (p.length == 2) {
                    String lat = String.format(java.util.Locale.ROOT, "%.6f", Double.parseDouble(p[0]));
                    String lon = String.format(java.util.Locale.ROOT, "%.6f", Double.parseDouble(p[1]));
                    return new Route(lat, lon, "https://2gis.kz/shymkent/directions/points/%7C" + lon + "%2C" + lat,
                            "https://yandex.kz/maps/?rtext=~" + lat + "%2C" + lon + "&rtt=auto",
                            "https://yandex.kz/map-widget/v1/?ll=" + lon + "%2C" + lat + "&z=17&pt=" + lon + "%2C" + lat
                                    + "%2Cpm2rdm&lang=ru_RU");
                }
            } catch (NumberFormatException ignored) {
                // ниже — как без точки
            }
        }
        String q = URLEncoder.encode(ROUTE_ADDRESS, StandardCharsets.UTF_8).replace("+", "%20");
        String qCity = URLEncoder.encode("Шымкент, " + ROUTE_ADDRESS, StandardCharsets.UTF_8).replace("+", "%20");
        return new Route(null, null, "https://2gis.kz/shymkent/search/" + q, "https://yandex.kz/maps/?text=" + qCity, null);
    }

    private static VacancyView view(Vacancy v) {
        return new VacancyView(v.getId(), v.getTitle(), v.getSummary(), v.getSchedule(), v.getExperience(),
                blank(v.getSalary()) ? "по договорённости" : v.getSalary(),
                lines(v.getDuties()), lines(v.getRequirements()), lines(v.getConditions()));
    }

    /** Один пункт — одна строка; маркеры «-», «•» в начале строки убираем. */
    private static List<String> lines(String text) {
        if (blank(text)) return List.of();
        return Arrays.stream(text.split("\\R")).map(s -> s.strip().replaceFirst("^[-•*–—]\\s*", ""))
                .filter(s -> !s.isEmpty()).toList();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
