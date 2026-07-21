package com.finntech.service;

import com.finntech.domain.AppUser;
import com.finntech.domain.Enums;
import com.finntech.domain.WishlistItem;
import com.finntech.repository.WishlistItemRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 고민 목록(위시리스트) (문서 §5-5, 폴센트 응용).
 *
 * <p>사고 싶은 상품을 담아두고 <b>결국 안 샀다</b>고 확인하면 그 금액을 '아낀 돈'으로 적립한다(안 산 걸 칭찬).
 * 적립은 {@link PointService#deposit}를 재사용해 즉시 랜덤 목표로 들어간다 — 구체적 상품 회피는 이번 달 예산과
 * 무관한 실제 절약이므로 예산 캡을 걸지 않는다.
 */
@Service
public class WishlistService {

    private static final String DEFAULT_CATEGORY = "SHOPPING";

    private final WishlistItemRepository wishlistRepository;
    private final PointService pointService;

    public WishlistService(WishlistItemRepository wishlistRepository, PointService pointService) {
        this.wishlistRepository = wishlistRepository;
        this.pointService = pointService;
    }

    /** 고민 목록에 담기 (URL/스크린샷 조회 결과를 확인·보정해 저장, 또는 수동 입력). */
    @Transactional
    public PointService.PointSnapshot add(AppUser user, String name, BigDecimal price, String categoryCode,
                                          String imageUrl, String sourceUrl, Enums.WishlistSource source,
                                          LocalDateTime now) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("상품 이름을 입력해 주세요");
        if (price == null || price.signum() <= 0) throw new IllegalArgumentException("가격을 확인해 주세요");
        String cat = (categoryCode == null || categoryCode.isBlank()) ? DEFAULT_CATEGORY : categoryCode;
        wishlistRepository.save(new WishlistItem(user.getId(), name.trim(), price, cat,
                trim(imageUrl, 1000), trim(sourceUrl, 1000),
                source == null ? Enums.WishlistSource.MANUAL : source, now));
        return pointService.snapshot(user, now);
    }

    /** 안 샀어요 — 그 금액을 즉시 아낀 돈으로 적립하고 칭찬. */
    @Transactional
    public PointService.PointSnapshot markNotBought(AppUser user, Long itemId, LocalDateTime now) {
        WishlistItem item = owned(user, itemId);
        item.setStatus(Enums.WishlistStatus.NOT_BOUGHT);
        wishlistRepository.save(item);
        // 적립(랜덤 목표 직입) — 스냅샷은 상태 변경 후 계산된다.
        return pointService.deposit(user, item.getCategoryCode(), item.getPrice(), "WISHLIST", now);
    }

    /** 결국 샀어요 — 고민 목록에서 내린다(별도 소비 기록은 하지 않음). */
    @Transactional
    public PointService.PointSnapshot markBought(AppUser user, Long itemId, LocalDateTime now) {
        WishlistItem item = owned(user, itemId);
        item.setStatus(Enums.WishlistStatus.BOUGHT);
        wishlistRepository.save(item);
        return pointService.snapshot(user, now);
    }

    @Transactional
    public PointService.PointSnapshot delete(AppUser user, Long itemId, LocalDateTime now) {
        wishlistRepository.delete(owned(user, itemId));
        return pointService.snapshot(user, now);
    }

    private WishlistItem owned(AppUser user, Long itemId) {
        return wishlistRepository.findById(itemId)
                .filter(w -> w.getUserId().equals(user.getId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "wishlist item not found"));
    }

    private static String trim(String s, int max) {
        return s == null ? null : (s.length() > max ? s.substring(0, max) : s);
    }
}
