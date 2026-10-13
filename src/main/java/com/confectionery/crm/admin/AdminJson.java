package com.confectionery.crm.admin;

import tools.jackson.databind.json.JsonMapper;

/** JSON для {@code <script type="application/json">} в страницах админки — без «</» внутри. */
public final class AdminJson {

    private static final JsonMapper MAPPER = JsonMapper.builder().findAndAddModules().build();

    private AdminJson() {
    }

    public static String of(Object value) {
        return MAPPER.writeValueAsString(value).replace("</", "<\\/");
    }
}
