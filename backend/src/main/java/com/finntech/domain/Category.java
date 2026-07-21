package com.finntech.domain;

import jakarta.persistence.*;

/**
 * 소비 카테고리. <b>DB 데이터이며 코드에 이름이 등장해서는 안 된다</b> (문서 §8 설계 제약 1).
 * 페르소나가 바뀌면 이 테이블의 행만 교체한다.
 */
@Entity
@Table(name = "category")
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 40)
    private String code;

    @Column(nullable = false, length = 60)
    private String displayName;

    protected Category() {}

    public Category(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getDisplayName() { return displayName; }
}
