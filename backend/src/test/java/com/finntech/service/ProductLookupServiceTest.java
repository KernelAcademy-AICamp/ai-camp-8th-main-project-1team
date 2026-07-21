package com.finntech.service;

import com.finntech.service.ProductLookupService.LookupResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 상품 조회 파싱·SSRF 방어 검증 (문서 §5-5). 네트워크 없이 순수 정적 메서드를 직접 호출한다.
 */
class ProductLookupServiceTest {

    @Test
    void parseHtml_readsOpenGraphMeta() {
        String html = "<html><head>"
                + "<meta property=\"og:title\" content=\"무선 이어폰\">"
                + "<meta property=\"og:image\" content=\"https://img.example/1.jpg\">"
                + "<meta property=\"product:price:amount\" content=\"179000\">"
                + "</head></html>";
        LookupResult r = ProductLookupService.parseHtml(html);
        assertEquals("무선 이어폰", r.name());
        assertEquals("https://img.example/1.jpg", r.imageUrl());
        assertEquals(0, new BigDecimal("179000").compareTo(r.price()));
    }

    @Test
    void parseHtml_readsJsonLdPrice() {
        String html = "<script type=\"application/ld+json\">"
                + "{\"@type\":\"Product\",\"name\":\"노트북\",\"offers\":{\"price\":\"1290000\"}}</script>";
        LookupResult r = ProductLookupService.parseHtml(html);
        assertEquals(0, new BigDecimal("1290000").compareTo(r.price()));
    }

    @Test
    void parseHtml_readsPriceFromEmbeddedState() {
        // Next.js 임베드 상태에서 상품명·할인가 추출 (원가가 아니라 salePrice)
        String next = "<script id=\"__NEXT_DATA__\" type=\"application/json\">"
                + "{\"props\":{\"product\":{\"productName\":\"무선 이어폰\",\"originalPrice\":259000,\"salePrice\":179000}}}</script>";
        LookupResult r = ProductLookupService.parseHtml(next);
        assertEquals("무선 이어폰", r.name());
        assertEquals(0, new BigDecimal("179000").compareTo(r.price()));

        // window.__PRELOADED_STATE__ 에서 price 추출
        String preload = "<script>window.__PRELOADED_STATE__ = {\"item\":{\"price\":250000}};</script>";
        assertEquals(0, new BigDecimal("250000").compareTo(ProductLookupService.parseHtml(preload).price()));
    }

    @Test
    void structuredPrice_prefersSalePriceOverListPrice() {
        String html = "{\"originalPrice\":300000,\"salePrice\":179000,\"price\":300000}";
        assertEquals("179000", ProductLookupService.structuredPrice(html));  // 할인가 우선
    }

    @Test
    void parsePrice_stripsCommasAndCurrency() {
        assertEquals(0, new BigDecimal("179000").compareTo(ProductLookupService.parsePrice("179,000원")));
        assertEquals(0, new BigDecimal("179000").compareTo(ProductLookupService.parsePrice("₩179000")));
        assertEquals(0, new BigDecimal("250000").compareTo(ProductLookupService.parsePrice("250000.00")));
        assertNull(ProductLookupService.parsePrice("품절"));
        assertNull(ProductLookupService.parsePrice(null));
    }

    @Test
    void parseHtml_ignoresBlockPageTitle() {
        // 안티봇 차단 페이지 제목을 상품명으로 오인하지 않는다
        LookupResult r = ProductLookupService.parseHtml(
                "<html><head><title>Access Denied</title></head><body>You don't have permission</body></html>");
        assertNull(r.name());
        assertNull(r.price());
    }

    @Test
    void validatePublicUrl_blocksInternalAndBadScheme() {
        assertThrows(IllegalArgumentException.class, () -> ProductLookupService.validatePublicUrl("ftp://x/y"));
        assertThrows(IllegalArgumentException.class, () -> ProductLookupService.validatePublicUrl("http://localhost/x"));
        assertThrows(IllegalArgumentException.class, () -> ProductLookupService.validatePublicUrl("http://127.0.0.1/x"));
        assertThrows(IllegalArgumentException.class, () -> ProductLookupService.validatePublicUrl("http://192.168.0.5/x"));
    }
}
