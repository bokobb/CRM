package com.confectionery.crm.service;

import com.confectionery.crm.domain.CompanyProfile;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SeoServiceTest {

    @Test
    void openingHoursFromProfileText() {
        assertThat(SeoService.openingHours("Пн–Сб с 07:00 до 18:00, обед с 12:00 до 13:00")).isEqualTo("Mo-Sa 07:00-18:00");
        assertThat(SeoService.openingHours("Пн-Пт 8:30–17:00")).isEqualTo("Mo-Fr 08:30-17:00");
        assertThat(SeoService.openingHours("ежедневно")).isNull();
        assertThat(SeoService.openingHours(null)).isNull();
    }

    @Test
    void addressSplitIntoRegionLocalityStreet() {
        SeoService.Address a = SeoService.address("Туркестанская область, Сайрамский район, Карамуртский сельский округ, "
                + "с. Низамабад, ул. Низамабад, 163.");
        assertThat(a.region()).isEqualTo("Туркестанская область");
        assertThat(a.locality()).isEqualTo("Низамабад");
        assertThat(a.street()).isEqualTo("ул. Низамабад, 163");
        assertThat(a.shortText()).isEqualTo("с. Низамабад, ул. Низамабад, 163");

        SeoService.Address city = SeoService.address("г. Шымкент, ж/м Исфиджаб, Елебекова 128 (за рынком)");
        assertThat(city.locality()).isEqualTo("Шымкент");
        assertThat(city.street()).isEqualTo("ж/м Исфиджаб, Елебекова 128");

        assertThat(SeoService.address("ул. Елебекова, 128").street()).isEqualTo("ул. Елебекова, 128");
        assertThat(SeoService.address(" ")).isNull();
    }

    @Test
    void legalNameAndTaxIdFromFirstLineOfRequisites() {
        CompanyProfile c = new CompanyProfile();
        c.setLegalInfo("ИП Иванов И. И., ИИН 810902301665\nАО «Kaspi Bank», KZ00000000000000");
        assertThat(c.legalLine()).isEqualTo("ИП Иванов И. И., ИИН 810902301665");
        assertThat(c.legalName()).isEqualTo("ИП Иванов И. И.");
        assertThat(c.taxId()).isEqualTo("810902301665");

        c.setLegalInfo("ТОО «Юг» · БИН: 190440003203");
        assertThat(c.legalName()).isEqualTo("ТОО «Юг»");
        assertThat(c.taxId()).isEqualTo("190440003203");
    }

    @Test
    void emailOnlyWhenRealAddress() {
        CompanyProfile c = new CompanyProfile();
        c.setEmail("yug-sladosti.kz");
        assertThat(c.siteEmail()).isNull();
        c.setEmail(" info@yug-sladosti.kz ");
        assertThat(c.siteEmail()).isEqualTo("info@yug-sladosti.kz");
    }

    @Test
    void jsonLdDescribesCompanyWebsiteAndPage() throws Exception {
        CompanyProfile c = new CompanyProfile();
        c.setName("Юг Сладости");
        c.setPhone("+7 775 005 02 30, +7 778 700 39 02");
        c.setAddress("Туркестанская область, с. Низамабад, ул. Низамабад, 163.");
        c.setWorkHours("Пн–Сб с 07:00 до 18:00, обед с 12:00 до 13:00");
        c.setFoundedYear(2018);
        c.setInstagramUrl("https://www.instagram.com/yug_sladosti");
        c.setExternalLinks("https://2gis.kz/shymkent/firm/70000001110580791\nне ссылка\nhttps://www.instagram.com/yug_sladosti");
        PromoPageService.Page page = new PromoPageService.Page(c, List.of(), List.of(), 0, null, 2026);

        SeoService seo = new SeoService(null);
        String json = seo.jsonLd(SeoService.Kind.CONTACTS, c, "https://yug-sladosti.kz", "https://yug-sladosti.kz/contacts",
                "Контакты", "Описание", "https://yug-sladosti.kz/og.png", page);
        assertThat(json).doesNotContain("</");

        JsonNode graph = new ObjectMapper().readTree(json).get("@graph");
        JsonNode company = graph.get(0);
        assertThat(company.get("@type").asText()).isEqualTo("Bakery");
        assertThat(company.get("foundingDate").asText()).isEqualTo("2018");
        assertThat(company.get("address").get("addressLocality").asText()).isEqualTo("Низамабад");
        assertThat(company.get("contactPoint")).hasSize(2);
        assertThat(company.get("openingHoursSpecification").get("dayOfWeek")).hasSize(6);
        assertThat(company.get("sameAs")).hasSize(2);   // Instagram не повторяется, «не ссылка» отброшена
        assertThat(graph.get(1).get("@type").asText()).isEqualTo("WebSite");
        assertThat(graph.get(1).get("name").asText()).isEqualTo("Юг Сладости");
        assertThat(graph.get(2).get("@type").asText()).isEqualTo("ContactPage");
        assertThat(graph.get(3).get("@type").asText()).isEqualTo("BreadcrumbList");
    }
}
