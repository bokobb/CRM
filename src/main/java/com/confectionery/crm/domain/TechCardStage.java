package com.confectionery.crm.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Этап техпроцесса в техкарте. ccp — критическая контрольная точка (фиксируется в журнале). */
@Entity
@Table(name = "tech_card_stages")
@Getter
@Setter
@NoArgsConstructor
public class TechCardStage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tech_card_id", nullable = false)
    private TechCard techCard;

    @Column(nullable = false)
    private int position;

    @Column(nullable = false)
    private String title;

    @Column(length = 2000)
    private String description;

    private String temperature;

    private String duration;

    @Column(nullable = false)
    private boolean ccp;
}
