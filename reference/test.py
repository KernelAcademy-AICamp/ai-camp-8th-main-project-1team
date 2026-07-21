import requests

API_URL = "https://new-m.pay.naver.com/savings/api/v1/productList"

headers = {
    "User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
    "Referer": "https://new-m.pay.naver.com/savings/list/saving",
    "Accept": "application/json",
}

# 검색 조건 (여기만 바꾸면 됨)
params = {
    "productTypeCode": "1003",  # 적금
    "companyGroupCode": "BA",   # 은행
    "sortType": "INTEREST_RATE",  # 기본금리 순 (최고금리 순은 PRIME_INTEREST_RATE)
    "depositPeriod": "6",
    "depositAmount": "30000",
}


def fetch_products(search_params):
    """조건에 맞는 상품을 offset으로 넘겨가며 전부 가져온다."""
    products = []
    offset = 0

    while True:
        response = requests.get(
            API_URL, params={**search_params, "offset": offset}, headers=headers, timeout=10
        )
        response.raise_for_status()

        body = response.json()
        if not body.get("isSuccess"):
            raise RuntimeError(f"API 응답 실패: {body.get('message') or '알 수 없는 오류'}")

        result = body["result"]
        page = result["products"]
        if not page:
            break

        products.extend(page)
        offset += result["size"]

        if len(products) >= result["totalCount"]:
            break

    return products


def print_table(products):
    """은행 / 상품명 / 기본금리 / 최고금리 를 표로 출력한다."""
    company_width = max(len(p["companyName"]) for p in products)
    name_width = max(len(p["name"]) for p in products)

    print(f"{'순위':>4}  {'은행':<{company_width}}  {'상품명':<{name_width}}  {'기본':>6}  {'최고':>6}")
    print("-" * (4 + company_width + name_width + 24))

    for rank, product in enumerate(products, start=1):
        print(
            f"{rank:>4}  "
            f"{product['companyName']:<{company_width}}  "
            f"{product['name']:<{name_width}}  "
            f"{product['interestRate']:>6}  "
            f"{product['primeInterestRate']:>6}"
        )


try:
    products = fetch_products(params)
except (requests.RequestException, RuntimeError) as error:
    print(f"❌ {error}")
else:
    if products:
        print(f"✅ 데이터 추출 성공! 총 {len(products)}개 상품\n")
        print_table(products)
    else:
        print("❌ 조건에 맞는 상품이 없습니다.")
