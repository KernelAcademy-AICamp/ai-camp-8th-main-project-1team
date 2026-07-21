package com.finntech.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 고민 목록(위시리스트) 항목 (문서 §5-5, 폴센트 응용).
 *
 * <p>사고 싶은 상품을 담아두고, <b>결국 안 샀다</b>는 걸 확인해 그 금액을 '아낀 돈'으로 적립한다(안 산 걸 칭찬).
 * 상품 정보는 사용자가 건넨 URL의 OpenGraph/JSON-LD 파싱, 스크린샷 AI 추출, 또는 수동 입력으로 채운다 —
 * 화면을 훔쳐보는 게 아니라 사용자가 넘겨준다.
 */
@Entity
@Table(name = "wishlist_item", indexes = { @Index(name = "idx_wish_user", columnList = "user_id") })
public class WishlistItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal price;

    /** 상품 카테고리 코드(적립 시 사용). 기본 SHOPPING. */
    @Column(name = "category_code", length = 40)
    private String categoryCode;

    @Column(name = "image_url", length = 1000)
    private String imageUrl;

    @Column(name = "source_url", length = 1000)
    private String sourceUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Enums.WishlistSource source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Enums.WishlistStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected WishlistItem() {}

    public WishlistItem(Long userId, String name, BigDecimal price, String categoryCode,
                        String imageUrl, String sourceUrl, Enums.WishlistSource source, LocalDateTime createdAt) {
        this.userId = userId;
        this.name = name;
        this.price = price;
        this.categoryCode = categoryCode;
        this.imageUrl = imageUrl;
        this.sourceUrl = sourceUrl;
        this.source = source;
        this.status = Enums.WishlistStatus.CONSIDERING;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getName() { return name; }
    public BigDecimal getPrice() { return price; }
    public String getCategoryCode() { return categoryCode; }
    public String getImageUrl() { return imageUrl; }
    public String getSourceUrl() { return sourceUrl; }
    public Enums.WishlistSource getSource() { return source; }
    public Enums.WishlistStatus getStatus() { return status; }
    public void setStatus(Enums.WishlistStatus s) { this.status = s; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
