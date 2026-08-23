package com.confectionery.crm.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Синглтон-профиль компании (одна запись, id=1) — используется в шапке PDF-накладной. */
@Entity
@Table(name = "company_profile")
@Getter
@Setter
@NoArgsConstructor
public class CompanyProfile {

    @Id
    private Long id = 1L;

    private String name;

    private String address;

    private String phone;

    private String email;
}
