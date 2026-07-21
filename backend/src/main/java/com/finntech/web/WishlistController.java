package com.finntech.web;

import com.finntech.domain.AppUser;
import com.finntech.domain.Enums;
import com.finntech.repository.AppUserRepository;
import com.finntech.service.PointService;
import com.finntech.service.ProductLookupService;
import com.finntech.service.WishlistService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.function.Supplier;

/**
 * 고민 목록(위시리스트) API (문서 §5-5, 폴센트 응용).
 *
 * <p>조회(lookup)는 상품 정보를 <b>추출만</b> 해 화면에 채우고(저장 안 함), 사용자가 확인·보정 후 담는다(add).
 * 안 샀다고 확인하면 그 금액이 아낀 돈으로 적립된다. 전부 가상 — 실제 결제·구매가 아니다.
 */
@RestController
@RequestMapping("/api/points/wishlist")
public class WishlistController {

    private final WishlistService wishlistService;
    private final ProductLookupService lookupService;
    private final AppUserRepository userRepository;
    private final Clock clock;

    public WishlistController(WishlistService wishlistService, ProductLookupService lookupService,
                             AppUserRepository userRepository, Clock clock) {
        this.wishlistService = wishlistService;
        this.lookupService = lookupService;
        this.userRepository = userRepository;
        this.clock = clock;
    }

    private AppUser user(Long userId) {
        return userRepository.findById(userId).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user " + userId + " not found"));
    }

    private LocalDateTime now() { return LocalDateTime.now(clock); }

    private <T> T guard(Supplier<T> action) {
        try { return action.get(); }
        catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage()); }
    }

    // ---- 조회(추출만, 저장 안 함) ------------------------------------------

    /** URL의 OpenGraph/JSON-LD에서 상품명·가격·이미지 추출. */
    @PostMapping("/lookup-url")
    public ProductLookupService.LookupResult lookupUrl(@RequestBody UrlRequest req) {
        return guard(() -> lookupService.fromUrl(req.url()));
    }

    /** 스크린샷(base64)을 AI로 분석해 상품 정보 추출. 키 없으면 빈 결과(수동 입력 유도). */
    @PostMapping("/lookup-image")
    public ProductLookupService.LookupResult lookupImage(@RequestBody ImageRequest req) {
        return lookupService.fromImage(req.imageBase64(), req.mimeType());
    }

    // ---- 담기 / 결정 -------------------------------------------------------

    @PostMapping("/add")
    public PointService.PointSnapshot add(@RequestBody AddRequest req) {
        Enums.WishlistSource source;
        try { source = req.source() == null ? Enums.WishlistSource.MANUAL : Enums.WishlistSource.valueOf(req.source()); }
        catch (IllegalArgumentException e) { source = Enums.WishlistSource.MANUAL; }
        Enums.WishlistSource src = source;
        return guard(() -> wishlistService.add(user(req.userId()), req.name(), req.price(), req.categoryCode(),
                req.imageUrl(), req.sourceUrl(), src, now()));
    }

    @PostMapping("/{itemId}/not-bought")
    public PointService.PointSnapshot notBought(@PathVariable Long itemId, @RequestParam Long userId) {
        return guard(() -> wishlistService.markNotBought(user(userId), itemId, now()));
    }

    @PostMapping("/{itemId}/bought")
    public PointService.PointSnapshot bought(@PathVariable Long itemId, @RequestParam Long userId) {
        return wishlistService.markBought(user(userId), itemId, now());
    }

    @DeleteMapping("/{itemId}")
    public PointService.PointSnapshot delete(@PathVariable Long itemId, @RequestParam Long userId) {
        return wishlistService.delete(user(userId), itemId, now());
    }

    // ---- 요청 바디 ---------------------------------------------------------

    public record UrlRequest(String url) {}
    public record ImageRequest(String imageBase64, String mimeType) {}
    public record AddRequest(Long userId, String name, BigDecimal price, String categoryCode,
                             String imageUrl, String sourceUrl, String source) {}
}
