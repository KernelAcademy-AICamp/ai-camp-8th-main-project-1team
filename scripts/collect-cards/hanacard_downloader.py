#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
하나카드 현재 발급 상품 약관 PDF 다운로드

출처: 사용자가 저장한 하나카드 상품약관 MHT
필터: 상품 블록에 "발급중단" 문구가 없는 항목만
고정 대상: 353건

실행:
    pip install requests
    python hanacard_353_download.py
"""

import os
import re
import time
from pathlib import Path
from urllib.parse import urlparse, unquote

import requests

from collector_policy import USER_AGENT, require_robots_allowed

OUTPUT_DIR = str(Path(__file__).resolve().parent / "out" / "hanacard")
DELAY = 0.15
TIMEOUT = 60

PDF_TARGETS = [
    ('원더카드2.0', 'https://m.hanacard.co.kr/leaflet/14/14126_20260708.pdf'),
    ('원더카드2.0 Co-brand', 'https://m.hanacard.co.kr/leaflet/14/14392_20260708.pdf'),
    ('대전 다자녀 교통복지 체크카드', 'https://m.hanacard.co.kr/leaflet/15/15754_20260708.pdf'),
    ('MG+ 트래블로그 하나카드', 'https://m.hanacard.co.kr/leaflet/15/15822_20260701.pdf'),
    ('하나 My Honor 학생증 체크카드(V후불)', 'https://m.hanacard.co.kr/leaflet/15/15795_20260626.pdf'),
    ('런데이 하나카드', 'https://m.hanacard.co.kr/leaflet/15/15802_20260626.pdf'),
    ('MG+ 신용카드 Primo 하나카드', 'https://m.hanacard.co.kr/leaflet/15/15066_20260618.pdf'),
    ('MG+ Blue 하나카드', 'https://m.hanacard.co.kr/leaflet/15/15272_20260618.pdf'),
    ('하나 나라사랑카드(체크/후불교통)_Military', 'https://m.hanacard.co.kr/leaflet/15/15475_20260615.pdf'),
    ('MOVING카드 ALLDAY', 'https://m.hanacard.co.kr/leaflet/15/15725_20260608.pdf'),
    ('MOVING카드 ONLINE', 'https://m.hanacard.co.kr/leaflet/15/15726_20260608.pdf'),
    ('MOVING카드 PLAY', 'https://m.hanacard.co.kr/leaflet/15/15727_20260608.pdf'),
    ('MOVING카드 LIFE', 'https://m.hanacard.co.kr/leaflet/15/15728_20260608.pdf'),
    ('MOVING카드 GLOBAL', 'https://m.hanacard.co.kr/leaflet/15/15729_20260608.pdf'),
    ('Young Hana 체크카드_OK캐쉬백(GREEN, 비교통)', 'https://m.hanacard.co.kr/leaflet/10/10769_20260605.pdf'),
    ('Young Hana 체크카드_OK캐쉬백_국제학생증 ISIC (후불교통)', 'https://m.hanacard.co.kr/leaflet/10/10827_20260605.pdf'),
    ('K리그 축덕_Young Hana 체크카드(비교통)', 'https://m.hanacard.co.kr/leaflet/12/12286_20260605.pdf'),
    ('대구 청년응원 Young Hana 체크카드_GREEN (비교통)', 'https://m.hanacard.co.kr/leaflet/12/12581_20260605.pdf'),
    ('New Medi Goldclub_1Q My Lunch', 'https://m.hanacard.co.kr/leaflet/12/12947_20260605.pdf'),
    ('MEDI PRESTIGE_1Q My Lunch', 'https://m.hanacard.co.kr/leaflet/13/13422_20260605.pdf'),
    ('청년희망내일_Young Hana 체크카드_with OKcashbag', 'https://m.hanacard.co.kr/leaflet/13/13810_20260605.pdf'),
    ('꿈사다리 장학금 Young Hana 체크카드_OK캐쉬백(GREEN)', 'https://m.hanacard.co.kr/leaflet/13/13966_20260605.pdf'),
    ('전북청년 함께도전YoungHana 체크카드_OK캐쉬백(GREEN,후불교통)', 'https://m.hanacard.co.kr/leaflet/14/14340_20260605.pdf'),
    ('#tag1카드 Orange(태그원카드 오렌지)', 'https://m.hanacard.co.kr/leaflet/11/11746_20260604.pdf'),
    ('#tag1카드 Navy(태그원카드 네이비)', 'https://m.hanacard.co.kr/leaflet/11/11747_20260604.pdf'),
    ('대한노인회 하나카드', 'https://m.hanacard.co.kr/leaflet/15/15521_20260602.pdf'),
    ('소노U라이프엔 하나카드', 'https://m.hanacard.co.kr/leaflet/14/14203_20260527.pdf'),
    ('펫프렌즈 하나카드', 'https://m.hanacard.co.kr/leaflet/15/15702_20260519.pdf'),
    ('하나멤버스 1Q(원큐) 카드 Special_다담식자재마트', 'https://m.hanacard.co.kr/leaflet/10/10850_20260518.pdf'),
    ('생활의 달인 선불하이패스 카드', 'https://m.hanacard.co.kr/leaflet/03/03789_20260504.pdf'),
    ('랭킹닭컴 하나카드', 'https://m.hanacard.co.kr/leaflet/11/11845_20260504.pdf'),
    ('MULTI Living 카드', 'https://m.hanacard.co.kr/leaflet/13/13059_20260504.pdf'),
    ('렌탈 플러스 카드', 'https://m.hanacard.co.kr/leaflet/13/13220_20260504.pdf'),
    ('로마드 하나카드', 'https://m.hanacard.co.kr/leaflet/13/13736_20260504.pdf'),
    ('NEW 렌탈 플러스 카드', 'https://m.hanacard.co.kr/leaflet/13/13763_20260504.pdf'),
    ('경동나비엔 New 렌탈플러스카드_온네임', 'https://m.hanacard.co.kr/leaflet/14/14379_20260504.pdf'),
    ('Mile1(UniMile) 하나카드', 'https://m.hanacard.co.kr/leaflet/14/14480_20260504.pdf'),
    ('루헨스 New 렌탈 플러스 카드(온네임용)', 'https://m.hanacard.co.kr/leaflet/14/14577_20260504.pdf'),
    ('효원상조 New 렌탈 플러스 카드_온네임', 'https://m.hanacard.co.kr/leaflet/14/14821_20260504.pdf'),
    ('달달 하나 Fun', 'https://m.hanacard.co.kr/leaflet/15/15193_20260504.pdf'),
    ('ONE PICK STYLE 하나 체크카드', 'https://m.hanacard.co.kr/leaflet/15/15650_20260429.pdf'),
    ('ONE PICK PLAY 하나 체크카드', 'https://m.hanacard.co.kr/leaflet/15/15651_20260429.pdf'),
    ('아산페이 하나 체크카드(비교통)', 'https://m.hanacard.co.kr/leaflet/15/15637_20260427.pdf'),
    ('# MY WAY(샵 마이웨이) 화이트', 'https://m.hanacard.co.kr/leaflet/14/14022_20260424.pdf'),
    ('CLUB1카드 200(스카이패스)', 'https://m.hanacard.co.kr/leaflet/02/02767_20260403.pdf'),
    ('현대S라이프 하나카드', 'https://m.hanacard.co.kr/leaflet/14/14111_20260320.pdf'),
    ('대구로 카드', 'https://m.hanacard.co.kr/leaflet/14/14455_20260320.pdf'),
    ('하나멤버스 1Q(원큐) 카드 Special', 'https://m.hanacard.co.kr/leaflet/10/10326_20260310.pdf'),
    ('하나멤버스 1Q(원큐) 카드 ALL in', 'https://m.hanacard.co.kr/leaflet/10/10385_20260310.pdf'),
    ('빛고을 하나멤버스 1Q(원큐) 카드 Special', 'https://m.hanacard.co.kr/leaflet/10/10480_20260310.pdf'),
    ('하나멤버스 1Q(원큐) 카드 Business', 'https://m.hanacard.co.kr/leaflet/10/10694_20260310.pdf'),
    ('교직원복지카드 하나멤버스 1Q(원큐) 카드 Living', 'https://m.hanacard.co.kr/leaflet/11/11287_20260310.pdf'),
    ('교직원복지카드 하나멤버스 1Q(원큐)카드 Shopping', 'https://m.hanacard.co.kr/leaflet/11/11288_20260310.pdf'),
    ('교직원복지카드 하나멤버스 1Q(원큐)카드 ALL in', 'https://m.hanacard.co.kr/leaflet/11/11289_20260310.pdf'),
    ('1Q Special+(원큐 스페셜플러스)', 'https://m.hanacard.co.kr/leaflet/11/11533_20260310.pdf'),
    ('국가대표 복지 하나멤버스 1Q(원큐) 카드 Daily_온네임용', 'https://m.hanacard.co.kr/leaflet/11/11965_20260310.pdf'),
    ('전북청년지역정착지원 복지 하나멤버스 1Q 카드 Living_온네임', 'https://m.hanacard.co.kr/leaflet/12/12543_20260310.pdf'),
    ('하나멤버스 1Q 카드 Special Auto(원큐 스페셜 오토)', 'https://m.hanacard.co.kr/leaflet/12/12739_20260310.pdf'),
    ('KLPNA 대한간호조무사협회 하나카드', 'https://m.hanacard.co.kr/leaflet/12/12774_20260310.pdf'),
    ('풀무원 하나카드', 'https://m.hanacard.co.kr/leaflet/12/12877_20260310.pdf'),
    ('건설하나로 하나멤버스 1Q 카드 Special_온네임용', 'https://m.hanacard.co.kr/leaflet/14/14004_20260310.pdf'),
    ('하나멤버스 1Q 체크카드', 'https://m.hanacard.co.kr/leaflet/10/10041_20260306.pdf'),
    ('등유나눔카드 하나멤버스 1Q체크카드', 'https://m.hanacard.co.kr/leaflet/12/12135_20260306.pdf'),
    ('전북청년지역정착지원 복지 하나멤버스 1Q 체크카드_온네임용', 'https://m.hanacard.co.kr/leaflet/12/12544_20260306.pdf'),
    ('스포츠꿈나무 특기장려금 1Q 체크카드', 'https://m.hanacard.co.kr/leaflet/12/12580_20260306.pdf'),
    ('청년맞춤 제작소 하나멤버스 1Q 체크카드', 'https://m.hanacard.co.kr/leaflet/12/12682_20260306.pdf'),
    ('MULTI Any 체크카드', 'https://m.hanacard.co.kr/leaflet/13/13278_20260306.pdf'),
    ('호요버스(HoYoverse) 체크카드_원신 슬라임랜드_비교통', 'https://m.hanacard.co.kr/leaflet/13/13834_20260306.pdf'),
    ('국민연금증 하나멤버스 1Q 체크카드(후불교통)', 'https://m.hanacard.co.kr/leaflet/14/14425_20260306.pdf'),
    ('하나 주거래 생활 체크카드(후불교통)', 'https://m.hanacard.co.kr/leaflet/14/14586_20260306.pdf'),
    ('달달 하나 체크카드(핑크, 후불교통)', 'https://m.hanacard.co.kr/leaflet/15/15114_20260306.pdf'),
    ('광주어르신교통_메가마켓 체크카드(후불교통)', 'https://m.hanacard.co.kr/leaflet/10/10908_20260212.pdf'),
    ('BC BIZ CORPORATE 카드', 'https://m.hanacard.co.kr/leaflet/11/11623_20260212.pdf'),
    ('국고보조금 전용 신용카드(e-나라도움)', 'https://m.hanacard.co.kr/leaflet/11/11814_20260212.pdf'),
    ('국민행복카드', 'https://m.hanacard.co.kr/leaflet/13/13009_20260212.pdf'),
    ('지방보조금 전용 개인신용카드', 'https://m.hanacard.co.kr/leaflet/14/14145_20260212.pdf'),
    ('국민연금증 연금하나카드', 'https://m.hanacard.co.kr/leaflet/14/14424_20260212.pdf'),
    ('트래블로그 플러스 신용카드', 'https://m.hanacard.co.kr/leaflet/15/15639_20260212.pdf'),
    ('하나대체투자자산운용(구.하나다올자산운용) 복지 카드', 'https://m.hanacard.co.kr/leaflet/02/02992_20260205.pdf'),
    ('고려대 교직원 신분증 카드(후불)', 'https://m.hanacard.co.kr/leaflet/03/03085_20260205.pdf'),
    ('하늘애 카드', 'https://m.hanacard.co.kr/leaflet/03/03688_20260205.pdf'),
    ('패밀리 3 카드', 'https://m.hanacard.co.kr/leaflet/14/14421_20260205.pdf'),
    ('BT카드', 'https://m.hanacard.co.kr/leaflet/14/14324_20260203.pdf'),
    ('산재연금증 카드', 'https://m.hanacard.co.kr/leaflet/03/03305_20260130.pdf'),
    ('대박 Business 카드', 'https://m.hanacard.co.kr/leaflet/10/10735_20260130.pdf'),
    ('청호나이스 플러스 하나카드', 'https://m.hanacard.co.kr/leaflet/10/10946_20260130.pdf'),
    ('바디프랜드 하나카드', 'https://m.hanacard.co.kr/leaflet/10/10947_20260130.pdf'),
    ('교원wells 하나카드', 'https://m.hanacard.co.kr/leaflet/10/10948_20260130.pdf'),
    ('쿠쿠 프리멤버쉽 하나카드', 'https://m.hanacard.co.kr/leaflet/10/10949_20260130.pdf'),
    ('웰릭스렌탈 하나카드', 'https://m.hanacard.co.kr/leaflet/11/11110_20260130.pdf'),
    ('현대큐밍 하나카드', 'https://m.hanacard.co.kr/leaflet/11/11111_20260130.pdf'),
    ('청호나이스 플러스 할부 하나카드', 'https://m.hanacard.co.kr/leaflet/11/11205_20260130.pdf'),
    ('핀크카드(기명식 선불카드, Finnq)', 'https://m.hanacard.co.kr/leaflet/11/11225_20260130.pdf'),
    ('넥센타이어 하나카드', 'https://m.hanacard.co.kr/leaflet/11/11413_20260130.pdf'),
    ('하나로마트 하나카드', 'https://m.hanacard.co.kr/leaflet/11/11525_20260130.pdf'),
    ('뇌새김 하나카드', 'https://m.hanacard.co.kr/leaflet/11/11539_20260130.pdf'),
    ('BS렌탈(비에스렌탈) 플러스 하나카드', 'https://m.hanacard.co.kr/leaflet/11/11755_20260130.pdf'),
    ('Any PLUS 카드', 'https://m.hanacard.co.kr/leaflet/11/11896_20260130.pdf'),
    ('U+ Family 하나카드 (유플러스 패밀리 하나카드)', 'https://m.hanacard.co.kr/leaflet/11/11930_20260130.pdf'),
    ('신세계 하나 체크카드', 'https://m.hanacard.co.kr/leaflet/11/11990_20260130.pdf'),
    ('시코르 체크카드_Black', 'https://m.hanacard.co.kr/leaflet/12/12032_20260130.pdf'),
    ('우체국BIZ플러스 사업자형(일반)', 'https://m.hanacard.co.kr/leaflet/12/12508_20260130.pdf'),
    ('우체국라이프+ 플러스 (일반형)', 'https://m.hanacard.co.kr/leaflet/12/12512_20260130.pdf'),
    ('애터미 Any PLUS 카드', 'https://m.hanacard.co.kr/leaflet/12/12660_20260130.pdf'),
    ('애터미 캐쉬백 체크카드', 'https://m.hanacard.co.kr/leaflet/12/12684_20260130.pdf'),
    ('Trive(트라이브) Any PLUS 카드', 'https://m.hanacard.co.kr/leaflet/12/12775_20260130.pdf'),
    ('현대렌탈서비스 하나카드', 'https://m.hanacard.co.kr/leaflet/12/12932_20260130.pdf'),
    ('스피킹맥스 하나카드', 'https://m.hanacard.co.kr/leaflet/12/12952_20260130.pdf'),
    ('보람상조그룹 하나카드', 'https://m.hanacard.co.kr/leaflet/13/13258_20260130.pdf'),
    ('예다함 플러스 하나카드', 'https://m.hanacard.co.kr/leaflet/13/13333_20260130.pdf'),
    ('모두의 신세계 하나카드', 'https://m.hanacard.co.kr/leaflet/13/13367_20260130.pdf'),
    ('원스토어 1 하나카드 (캐릭터)', 'https://m.hanacard.co.kr/leaflet/13/13455_20260130.pdf'),
    ('시니어 케어 하나카드', 'https://m.hanacard.co.kr/leaflet/13/13456_20260130.pdf'),
    ('미트박스 하나카드', 'https://m.hanacard.co.kr/leaflet/13/13601_20260130.pdf'),
    ('쿠팡 패밀리 하나카드', 'https://m.hanacard.co.kr/leaflet/13/13602_20260130.pdf'),
    ('웰컴 하나카드', 'https://m.hanacard.co.kr/leaflet/13/13632_20260130.pdf'),
    ('웰컴 플러스 하나카드', 'https://m.hanacard.co.kr/leaflet/13/13633_20260130.pdf'),
    ('빨간펜 하나카드 (스마일)', 'https://m.hanacard.co.kr/leaflet/13/13885_20260130.pdf'),
    ('SB 신용카드', 'https://m.hanacard.co.kr/leaflet/13/13912_20260130.pdf'),
    ('피엠인터내셔널 하나카드', 'https://m.hanacard.co.kr/leaflet/14/14071_20260130.pdf'),
    ('이디야 하나카드 (COFFEE POWER)', 'https://m.hanacard.co.kr/leaflet/14/14112_20260130.pdf'),
    ('HD현대 패밀리카드(Business)', 'https://m.hanacard.co.kr/leaflet/14/14118_20260130.pdf'),
    ('더 심플 하나카드', 'https://m.hanacard.co.kr/leaflet/14/14527_20260130.pdf'),
    ('LG전자 플러스 하나카드', 'https://m.hanacard.co.kr/leaflet/14/14528_20260130.pdf'),
    ('하나카드 KaPick', 'https://m.hanacard.co.kr/leaflet/14/14561_20260130.pdf'),
    ('마이 코웨이 하나카드', 'https://m.hanacard.co.kr/leaflet/14/14911_20260130.pdf'),
    ('CJ ONE 체크카드', 'https://m.hanacard.co.kr/leaflet/03/03687_20260128.pdf'),
    ('하나증권 H CMA 플러스 체크카드', 'https://m.hanacard.co.kr/leaflet/03/03729_20260128.pdf'),
    ('메가마켓 대전시교통복지 체크카드 孝', 'https://m.hanacard.co.kr/leaflet/04/04491_20260128.pdf'),
    ('부천사랑 서포터즈 체크카드', 'https://m.hanacard.co.kr/leaflet/04/04544_20260128.pdf'),
    ('카카오페이 MUZI 체크카드', 'https://m.hanacard.co.kr/leaflet/04/04652_20260128.pdf'),
    ('ITIC 국제교사증 1Q체크카드(비교통)', 'https://m.hanacard.co.kr/leaflet/10/10306_20260128.pdf'),
    ('VIVA + (비바 플러스) 플래티늄 체크카드', 'https://m.hanacard.co.kr/leaflet/11/11832_20260128.pdf'),
    ('국고보조금 전용 체크카드(e-나라도움)', 'https://m.hanacard.co.kr/leaflet/11/11848_20260128.pdf'),
    ('길한통 체크카드', 'https://m.hanacard.co.kr/leaflet/12/12129_20260128.pdf'),
    ('리틀프렌즈 체크카드', 'https://m.hanacard.co.kr/leaflet/12/12373_20260128.pdf'),
    ('VIVA + Allpoint 체크카드', 'https://m.hanacard.co.kr/leaflet/12/12541_20260128.pdf'),
    ('울산페이 하나멤버스 1Q 체크카드 (비교통)', 'https://m.hanacard.co.kr/leaflet/12/12589_20260128.pdf'),
    ('대학교 학생증 체크카드', 'https://m.hanacard.co.kr/leaflet/12/12714_20260128.pdf'),
    ('대전사랑 1Q 체크카드_대전시(비교통)', 'https://m.hanacard.co.kr/leaflet/12/12863_20260128.pdf'),
    ('공주페이 하나멤버스 1Q 체크카드 (비교통)', 'https://m.hanacard.co.kr/leaflet/12/12904_20260128.pdf'),
    ('페이워치 체크카드', 'https://m.hanacard.co.kr/leaflet/13/13260_20260128.pdf'),
    ('대전광역시 교통복지 대전사랑 1Q 체크카드 孝', 'https://m.hanacard.co.kr/leaflet/13/13297_20260128.pdf'),
    ('구미사랑 하나멤버스 1Q 체크카드', 'https://m.hanacard.co.kr/leaflet/13/13445_20260128.pdf'),
    ('네이버페이 머니 하나 체크카드 (그린)', 'https://m.hanacard.co.kr/leaflet/13/13502_20260128.pdf'),
    ('금쪽이 YoungHana+ 체크카드(금쪽이)', 'https://m.hanacard.co.kr/leaflet/14/14271_20260128.pdf'),
    ('대전사랑 체크카드', 'https://m.hanacard.co.kr/leaflet/14/14374_20260128.pdf'),
    ('대전광역시 어르신 무임교통 대전사랑 1Q 체크카드', 'https://m.hanacard.co.kr/leaflet/14/14560_20260128.pdf'),
    ('건설올패스 MULTI Any 체크카드', 'https://m.hanacard.co.kr/leaflet/14/14580_20260128.pdf'),
    ('드림 YoungHana+ 체크카드', 'https://m.hanacard.co.kr/leaflet/14/14581_20260128.pdf'),
    ('예술인패스 체크카드', 'https://m.hanacard.co.kr/leaflet/14/14591_20260128.pdf'),
    ('당근머니 하나 체크카드_Basic', 'https://m.hanacard.co.kr/leaflet/14/14683_20260128.pdf'),
    ('당근머니 하나 체크카드_당근이', 'https://m.hanacard.co.kr/leaflet/14/14684_20260128.pdf'),
    ('경기청년 YoungHana+ 체크카드', 'https://m.hanacard.co.kr/leaflet/14/14689_20260128.pdf'),
    ('하나트래블로그 학생증체크카드(후불교통)', 'https://m.hanacard.co.kr/leaflet/14/14915_20260128.pdf'),
    ('하나트래블로그_ISIC국제학생증체크카드(후불)', 'https://m.hanacard.co.kr/leaflet/14/14972_20260128.pdf'),
    ('희망풍차 체크카드', 'https://m.hanacard.co.kr/leaflet/15/15038_20260128.pdf'),
    ('유팜 체크카드', 'https://m.hanacard.co.kr/leaflet/88/88187_20260128.pdf'),
    ('큰수레 비즈니스 카드', 'https://m.hanacard.co.kr/leaflet/04/04428_20260127.pdf'),
    ('하나 스카이패스 아멕스 플래티늄 카드', 'https://m.hanacard.co.kr/leaflet/13/13351_20260115.pdf'),
    ('하나 CLUB H 아메리칸 익스프레스 리저브 카드', 'https://m.hanacard.co.kr/leaflet/13/13898_20260115.pdf'),
    ('Medi Goldclub_VIVA + (비바 플러스) 플래티늄 체크카드', 'https://m.hanacard.co.kr/leaflet/12/12949_20251231.pdf'),
    ('PRESTIGE N_카카오페이 MUZI 체크카드_온네임용', 'https://m.hanacard.co.kr/leaflet/13/13423_20251231.pdf'),
    ('PRESTIGE N _카카오페이 MUZI 체크카드_온네임', 'https://m.hanacard.co.kr/leaflet/13/13424_20251231.pdf'),
    ('투에버 모두의 일상 체크카드_온네임용', 'https://m.hanacard.co.kr/leaflet/13/13824_20251231.pdf'),
    ('트래블로그 체크카드', 'https://m.hanacard.co.kr/leaflet/13/13889_20251229.pdf'),
    ('트래블로그 신용카드', 'https://m.hanacard.co.kr/leaflet/14/14403_20251229.pdf'),
    ('하나 트래블로그 체크(후불교통)', 'https://m.hanacard.co.kr/leaflet/14/14902_20251229.pdf'),
    ('트래블로그 SKYPASS 신용카드(후불교통)', 'https://m.hanacard.co.kr/leaflet/15/15041_20251229.pdf'),
    ('트래블로그 PRESTIGE 신용카드(후불교통)', 'https://m.hanacard.co.kr/leaflet/15/15045_20251229.pdf'),
    ('트래블GO 체크카드', 'https://m.hanacard.co.kr/leaflet/15/15105_20251229.pdf'),
    ('하나골드클럽멤버스Ⅱ', 'https://m.hanacard.co.kr/leaflet/14/14533_20251226.pdf'),
    ('[법인][플래티늄12]PROPER', 'https://m.hanacard.co.kr/leaflet/90/90193_20251226.pdf'),
    ('그린 카드(전국형)', 'https://m.hanacard.co.kr/leaflet/03/03234_20251224.pdf'),
    ('그린 체크카드', 'https://m.hanacard.co.kr/leaflet/03/03593_20251224.pdf'),
    ('비바 G 플래티늄 체크카드', 'https://m.hanacard.co.kr/leaflet/04/04255_20251224.pdf'),
    ('메가마켓 체크카드', 'https://m.hanacard.co.kr/leaflet/04/04324_20251224.pdf'),
    ('글로벌 페이(블루) 체크카드', 'https://m.hanacard.co.kr/leaflet/04/04359_20251224.pdf'),
    ('대한민국만세 비바G 체크카드', 'https://m.hanacard.co.kr/leaflet/04/04568_20251224.pdf'),
    ('비바 e 플래티늄 체크카드', 'https://m.hanacard.co.kr/leaflet/04/04764_20251224.pdf'),
    ('하나멤버스 Mega 체크카드', 'https://m.hanacard.co.kr/leaflet/10/10172_20251224.pdf'),
    ('Mile 1.6 대한항공', 'https://m.hanacard.co.kr/leaflet/11/11662_20251224.pdf'),
    ('CLUB Signature(클럽시그니처) - skypass', 'https://m.hanacard.co.kr/leaflet/11/11925_20251224.pdf'),
    ('연금하나카드', 'https://m.hanacard.co.kr/leaflet/11/11944_20251224.pdf'),
    ('CLUB Primus(클럽프리머스)_Skypass 카드', 'https://m.hanacard.co.kr/leaflet/12/12121_20251224.pdf'),
    ('My Trip SKYPASS My flight(마이 트립 스카이패스 마이플라이트)', 'https://m.hanacard.co.kr/leaflet/12/12552_20251224.pdf'),
    ('VIVA X 플래티늄 체크카드_공용(교통)', 'https://m.hanacard.co.kr/leaflet/12/12880_20251224.pdf'),
    ('모두의 일상 체크카드', 'https://m.hanacard.co.kr/leaflet/13/13015_20251224.pdf'),
    ('MULTI Oil 카드', 'https://m.hanacard.co.kr/leaflet/13/13060_20251224.pdf'),
    ('하나원큐카드', 'https://m.hanacard.co.kr/leaflet/13/13066_20251224.pdf'),
    ('계좌결제', 'https://m.hanacard.co.kr/leaflet/13/13213_20251224.pdf'),
    ('모두의건강 체크카드', 'https://m.hanacard.co.kr/leaflet/13/13259_20251224.pdf'),
    ('Young Hana+ 체크카드', 'https://m.hanacard.co.kr/leaflet/13/13327_20251224.pdf'),
    ('아이부자카드', 'https://m.hanacard.co.kr/leaflet/13/13438_20251224.pdf'),
    ('하나머니 선불카드', 'https://m.hanacard.co.kr/leaflet/13/13493_20251224.pdf'),
    ('햇살론 카드', 'https://m.hanacard.co.kr/leaflet/13/13503_20251224.pdf'),
    ('CLUB CEO(클럽 CEO)-Skypass', 'https://m.hanacard.co.kr/leaflet/13/13550_20251224.pdf'),
    ('밀리언달러 하나카드', 'https://m.hanacard.co.kr/leaflet/13/13579_20251224.pdf'),
    ('내맘대로 쁨 카드(Basic Design)', 'https://m.hanacard.co.kr/leaflet/13/13615_20251224.pdf'),
    ('교원구몬 아이부자카드', 'https://m.hanacard.co.kr/leaflet/13/13865_20251224.pdf'),
    ('아이캔두 아이부자카드', 'https://m.hanacard.co.kr/leaflet/13/13866_20251224.pdf'),
    ('호전 선불카드', 'https://m.hanacard.co.kr/leaflet/14/14035_20251224.pdf'),
    ('LGU+ 무너_아이부자카드', 'https://m.hanacard.co.kr/leaflet/14/14205_20251224.pdf'),
    ('에너지 더블 카드', 'https://m.hanacard.co.kr/leaflet/14/14422_20251224.pdf'),
    ('Club Leaders 5', 'https://m.hanacard.co.kr/leaflet/14/14423_20251224.pdf'),
    ('하나증권 캐시백 투자 카드', 'https://m.hanacard.co.kr/leaflet/14/14430_20251224.pdf'),
    ('Club Leaders 3', 'https://m.hanacard.co.kr/leaflet/14/14465_20251224.pdf'),
    ('Club Leaders 8', 'https://m.hanacard.co.kr/leaflet/14/14466_20251224.pdf'),
    ('K-패스 하나 신용카드', 'https://m.hanacard.co.kr/leaflet/14/14472_20251224.pdf'),
    ('K-패스 하나 체크카드', 'https://m.hanacard.co.kr/leaflet/14/14474_20251224.pdf'),
    ('Club Leaders 10', 'https://m.hanacard.co.kr/leaflet/14/14484_20251224.pdf'),
    ('Club Leaders 15', 'https://m.hanacard.co.kr/leaflet/14/14485_20251224.pdf'),
    ('하나 주거래 쇼핑 체크카드(후불교통)', 'https://m.hanacard.co.kr/leaflet/14/14584_20251224.pdf'),
    ('모임원(ONE) 체크카드', 'https://m.hanacard.co.kr/leaflet/14/14811_20251224.pdf'),
    ('아이스크림 홈런 아이부자카드', 'https://m.hanacard.co.kr/leaflet/14/14890_20251224.pdf'),
    ('후불하이패스 카드', 'https://m.hanacard.co.kr/leaflet/80/80979_20251224.pdf'),
    ('하이마트 구독 하나카드', 'https://m.hanacard.co.kr/leaflet/15/15579_20251219.pdf'),
    ('산재연금수급자 하나카드', 'https://m.hanacard.co.kr/leaflet/15/15509_20251127.pdf'),
    ('소노아임레디 플러스 하나카드', 'https://m.hanacard.co.kr/leaflet/13/13408_20251120.pdf'),
    ('아정당 하나카드(반투명)', 'https://m.hanacard.co.kr/leaflet/15/15549_20251031.pdf'),
    ('대한노인회 하나 체크카드', 'https://m.hanacard.co.kr/leaflet/15/15522_20251030.pdf'),
    ('MG+ W 하나카드', 'https://m.hanacard.co.kr/leaflet/15/15486_20250922.pdf'),
    ('이응패스 여민전 체크카드', 'https://m.hanacard.co.kr/leaflet/15/15453_20250919.pdf'),
    ('하나 소상공인 특례 햇살론카드', 'https://m.hanacard.co.kr/leaflet/15/15482_20250827.pdf'),
    ('SK 인텔릭스 플러스 하나카드(구 SK매직 플러스)', 'https://m.hanacard.co.kr/leaflet/14/14896_20250820.pdf'),
    ('쿠팡 법인셀러 하나체크카드', 'https://m.hanacard.co.kr/leaflet/15/15103_20250814.pdf'),
    ('하나 더 소호 카드', 'https://m.hanacard.co.kr/leaflet/15/15280_20250812.pdf'),
    ('에스케이패밀리(직원) 생활밀착형 카드', 'https://m.hanacard.co.kr/leaflet/03/03374_20250725.pdf'),
    ('CLUB SK(클럽 SK)', 'https://m.hanacard.co.kr/leaflet/03/03496_20250723.pdf'),
    ('CLUB SK (클럽 SK) 카드 선불하이패스', 'https://m.hanacard.co.kr/leaflet/03/03559_20250723.pdf'),
    ('HERO 체크카드 (후불교통)', 'https://m.hanacard.co.kr/leaflet/15/15449_20250710.pdf'),
    ('HERO 체크카드 (비교통)', 'https://m.hanacard.co.kr/leaflet/15/15464_20250710.pdf'),
    ('토스신용카드(투명)_v2', 'https://m.hanacard.co.kr/leaflet/14/14536_20250703.pdf'),
    ('NEW 웰컴플러스 하나카드', 'https://m.hanacard.co.kr/leaflet/14/14897_20250703.pdf'),
    ('JADE Classic', 'https://m.hanacard.co.kr/leaflet/14/14955_20250703.pdf'),
    ('JADE Prime', 'https://m.hanacard.co.kr/leaflet/14/14997_20250703.pdf'),
    ('JADE First', 'https://m.hanacard.co.kr/leaflet/14/14998_20250703.pdf'),
    ('JADE First Centum', 'https://m.hanacard.co.kr/leaflet/14/14999_20250703.pdf'),
    ('하나 THE 기업카드', 'https://m.hanacard.co.kr/leaflet/15/15064_20250703.pdf'),
    ('삼성 AI 구독 하나카드', 'https://m.hanacard.co.kr/leaflet/15/15111_20250703.pdf'),
    ('하나 더 이지 카드', 'https://m.hanacard.co.kr/leaflet/15/15161_20250703.pdf'),
    ('달달 하나 All', 'https://m.hanacard.co.kr/leaflet/15/15191_20250703.pdf'),
    ('달달 하나 Sweet', 'https://m.hanacard.co.kr/leaflet/15/15192_20250703.pdf'),
    ('더 심플 체크카드', 'https://m.hanacard.co.kr/leaflet/15/15245_20250703.pdf'),
    ('하나 트래블GO 체크(후불교통)', 'https://m.hanacard.co.kr/leaflet/15/15252_20250703.pdf'),
    ('MG+ BLACK 하나카드', 'https://m.hanacard.co.kr/leaflet/15/15273_20250703.pdf'),
    ('신세계 트래블GO 하나카드', 'https://m.hanacard.co.kr/leaflet/15/15282_20250703.pdf'),
    ('하나 더 넥스트 멤버스', 'https://m.hanacard.co.kr/leaflet/15/15299_20250703.pdf'),
    ('하이마트 하나카드', 'https://m.hanacard.co.kr/leaflet/15/15342_20250703.pdf'),
    ('하나 더 이지 체크카드 (한국, 후불교통)', 'https://m.hanacard.co.kr/leaflet/15/15348_20250703.pdf'),
    ('토스뱅크 하나카드 Day (그레이)', 'https://m.hanacard.co.kr/leaflet/15/15439_20250626.pdf'),
    ('One+카드', 'https://m.hanacard.co.kr/leaflet/13/13575_20250504.pdf'),
    ('AMEX 포인트 기업카드', 'https://m.hanacard.co.kr/leaflet/02/02890_20250325.pdf'),
    ('AMEX 스카이패스 기업카드', 'https://m.hanacard.co.kr/leaflet/02/02898_20250325.pdf'),
    ('TAX REFUND 기업카드', 'https://m.hanacard.co.kr/leaflet/03/03205_20250325.pdf'),
    ('TAX REFUND 기업체크카드', 'https://m.hanacard.co.kr/leaflet/03/03206_20250325.pdf'),
    ('플래티늄 법인카드', 'https://m.hanacard.co.kr/leaflet/04/04383_20250325.pdf'),
    ('법인카드(포인트 기업카드)', 'https://m.hanacard.co.kr/leaflet/04/04395_20250325.pdf'),
    ('광운대 교직원 신분증 카드', 'https://m.hanacard.co.kr/leaflet/02/02305_20250313.pdf'),
    ('커피빈코리아 복지 임직원 카드', 'https://m.hanacard.co.kr/leaflet/02/02805_20250313.pdf'),
    ('청강문화산업대학 복지 카드', 'https://m.hanacard.co.kr/leaflet/02/02808_20250313.pdf'),
    ('티센크루프머티리얼코리아 임직원 복지 카드', 'https://m.hanacard.co.kr/leaflet/02/02901_20250313.pdf'),
    ('한국문화정보센터 복지 카드', 'https://m.hanacard.co.kr/leaflet/02/02930_20250313.pdf'),
    ('대전시립예술단 복지 카드', 'https://m.hanacard.co.kr/leaflet/02/02954_20250313.pdf'),
    ('BC 기업카드', 'https://m.hanacard.co.kr/leaflet/00/00003_20250305.pdf'),
    ('윙고 장학재단 체크카드', 'https://m.hanacard.co.kr/leaflet/10/10048_20250305.pdf'),
    ('L.pay 하나 체크카드', 'https://m.hanacard.co.kr/leaflet/12/12496_20250305.pdf'),
    ('고등학교 학생증 체크카드', 'https://m.hanacard.co.kr/leaflet/12/12715_20250128.pdf'),
    ('아토모스 BestONE S 기업카드', 'https://m.hanacard.co.kr/leaflet/15/15278_20241210.pdf'),
    ('MEDI GOLDCLUB JADE Classic', 'https://m.hanacard.co.kr/leaflet/15/15247_20241127.pdf'),
    ('MEDI PRESTIGE JADE Classic', 'https://m.hanacard.co.kr/leaflet/15/15248_20241127.pdf'),
    ('아토모스 BestONE G 기업체크카드', 'https://m.hanacard.co.kr/leaflet/15/15279_20241119.pdf'),
    ('카카오페이 트래블로그 체크', 'https://m.hanacard.co.kr/leaflet/15/15172_20241031.pdf'),
    ('RCMS연구비카드', 'https://m.hanacard.co.kr/leaflet/15/15068_20240829.pdf'),
    ('투에버 모두의 일상 체크카드(비교통)_온네임용', 'https://m.hanacard.co.kr/leaflet/13/13908_20240807.pdf'),
    ('솜씨당 하나기업카드', 'https://m.hanacard.co.kr/leaflet/15/15164_20240617.pdf'),
    ('[법인][플래티늄03]PROPER', 'https://m.hanacard.co.kr/leaflet/90/90190_20230918.pdf'),
    ('통합이지바로연구비 단년도카드', 'https://m.hanacard.co.kr/leaflet/13/13606_20230831.pdf'),
    ('LUCKY BC기업카드', 'https://m.hanacard.co.kr/leaflet/00/00504_20230823.pdf'),
    ('BC Tax Refund 카드(기업신용)', 'https://m.hanacard.co.kr/leaflet/01/01985_20230823.pdf'),
    ('BC Tax Refund 카드(기업체크)', 'https://m.hanacard.co.kr/leaflet/01/01986_20230823.pdf'),
    ('BC 여비 기업카드(일반기업)', 'https://m.hanacard.co.kr/leaflet/02/02084_20230823.pdf'),
    ('쿠팡 셀러 체크카드', 'https://m.hanacard.co.kr/leaflet/14/14334_20230329.pdf'),
    ('SK쉴더스 하나카드', 'https://m.hanacard.co.kr/leaflet/14/14345_20230327.pdf'),
    ('CLUB NOBLE(클럽노블)', 'https://m.hanacard.co.kr/leaflet/14/14333_20230227.pdf'),
    ('법인카드_스카이패스', 'https://m.hanacard.co.kr/leaflet/04/04396_20221222.pdf'),
    ('하나손해보험 체크카드(구. The-K Auto 체크카드)', 'https://m.hanacard.co.kr/leaflet/04/04485_20221214.pdf'),
    ('BC 기업카드_스카이패스', 'https://m.hanacard.co.kr/leaflet/00/00078_20221212.pdf'),
    ('BC 대한항공 Tax Refund 기업카드', 'https://m.hanacard.co.kr/leaflet/02/02503_20221212.pdf'),
    ('BC 골프마일리지 Tax Refund 기업카드', 'https://m.hanacard.co.kr/leaflet/02/02703_20221212.pdf'),
    ('프리드라이프 하나카드', 'https://m.hanacard.co.kr/leaflet/14/14104_20221122.pdf'),
    ('지방보조금 전용 개인체크카드', 'https://m.hanacard.co.kr/leaflet/14/14154_20221122.pdf'),
    ('[법인]지방보조금전용 기업카드', 'https://m.hanacard.co.kr/leaflet/14/14176_20221118.pdf'),
    ('[법인]지방보조금전용 기업체크카드', 'https://m.hanacard.co.kr/leaflet/14/14177_20221118.pdf'),
    ('더피플라이프 100`s Life 하나카드_온네임용', 'https://m.hanacard.co.kr/leaflet/14/14062_20221028.pdf'),
    ('KDB BestONE E 하나기업카드(산업은행)', 'https://m.hanacard.co.kr/leaflet/14/14044_20220831.pdf'),
    ('카페24(cafe24)_BestONE G 기업체크카드', 'https://m.hanacard.co.kr/leaflet/13/13799_20220624.pdf'),
    ('스팬딧(spendit) 기업카드', 'https://m.hanacard.co.kr/leaflet/13/13598_20220525.pdf'),
    ('BASIC 기업카드', 'https://m.hanacard.co.kr/leaflet/13/13687_20220503.pdf'),
    ('DHL코리아_BestONE S 기업카드', 'https://m.hanacard.co.kr/leaflet/13/13800_20220411.pdf'),
    ('한동글로벌학교 아이부자 학생증 체크카드', 'https://m.hanacard.co.kr/leaflet/13/13773_20220330.pdf'),
    ('한동글로벌학교 아이부자 학생증 체크카드', 'https://m.hanacard.co.kr/leaflet/13/13773_20220330.pdf'),
    ('점프컴퍼니(주)_BestONE S 기업카드', 'https://m.hanacard.co.kr/leaflet/13/13725_20220118.pdf'),
    ('[법인]CLUB CEO 기업카드', 'https://m.hanacard.co.kr/leaflet/13/13562_20211128.pdf'),
    ('의약품 결제전용카드(팜코)', 'https://m.hanacard.co.kr/leaflet/01/01124_20211018.pdf'),
    ('스페셜 온라인 기업카드(신용)', 'https://m.hanacard.co.kr/leaflet/04/04601_20211018.pdf'),
    ('BestONE C 기업카드', 'https://m.hanacard.co.kr/leaflet/13/13354_20211018.pdf'),
    ('BestONE E 기업카드', 'https://m.hanacard.co.kr/leaflet/13/13355_20211018.pdf'),
    ('BestONE S 기업카드', 'https://m.hanacard.co.kr/leaflet/13/13356_20211018.pdf'),
    ('BestONE G 기업체크카드', 'https://m.hanacard.co.kr/leaflet/13/13359_20211018.pdf'),
    ('BC 기업체크카드', 'https://m.hanacard.co.kr/leaflet/00/00004_20210929.pdf'),
    ('BC SK주유전용카드', 'https://m.hanacard.co.kr/leaflet/00/00108_20210929.pdf'),
    ('BC 우편요금 결제전용카드', 'https://m.hanacard.co.kr/leaflet/00/00830_20210929.pdf'),
    ('BC BUSINESS SKY(항공권결제전용)', 'https://m.hanacard.co.kr/leaflet/01/01008_20210929.pdf'),
    ('BC 건설보증수수료 결제전용카드', 'https://m.hanacard.co.kr/leaflet/01/01056_20210929.pdf'),
    ('BC 옵티코 기업카드', 'https://m.hanacard.co.kr/leaflet/01/01383_20210929.pdf'),
    ('BC후불하이패스 결제전용기업카드', 'https://m.hanacard.co.kr/leaflet/02/02352_20210929.pdf'),
    ('BC 팜카드', 'https://m.hanacard.co.kr/leaflet/02/02447_20210929.pdf'),
    ('지방세결제전용카드', 'https://m.hanacard.co.kr/leaflet/03/03059_20210929.pdf'),
    ('전기요금 결제전용 기업체크카드', 'https://m.hanacard.co.kr/leaflet/03/03823_20210929.pdf'),
    ('해피포인트 하나 체크카드', 'https://m.hanacard.co.kr/leaflet/03/03857_20210929.pdf'),
    ('해피포인트 더블 체크카드', 'https://m.hanacard.co.kr/leaflet/03/03859_20210929.pdf'),
    ('프리미엄 법인카드', 'https://m.hanacard.co.kr/leaflet/04/04385_20210929.pdf'),
    ('프리미엄 BIZ TR 기업카드', 'https://m.hanacard.co.kr/leaflet/04/04400_20210929.pdf'),
    ('법인카드_A CLASS', 'https://m.hanacard.co.kr/leaflet/04/04406_20210929.pdf'),
    ('프리미엄법인카드_한국무역협회 회원용', 'https://m.hanacard.co.kr/leaflet/10/10714_20210929.pdf'),
    ('[법인][플래티늄03]한국무역협회 회원용', 'https://m.hanacard.co.kr/leaflet/10/10715_20210929.pdf'),
    ('사업자주거래 법인카드', 'https://m.hanacard.co.kr/leaflet/10/10777_20210929.pdf'),
    ('국고보조금 전용카드(e나라도움 기업신용카드)', 'https://m.hanacard.co.kr/leaflet/10/10933_20210929.pdf'),
    ('국고보조금 전용 기업체크카드(e나라도움 기업체크카드)', 'https://m.hanacard.co.kr/leaflet/11/11232_20210929.pdf'),
    ('NEW PREMIUM 기업카드', 'https://m.hanacard.co.kr/leaflet/11/11371_20210929.pdf'),
    ('NICE BIZ 기업카드(오토빌)', 'https://m.hanacard.co.kr/leaflet/11/11948_20210929.pdf'),
    ('[법인]정부구매체크카드', 'https://m.hanacard.co.kr/leaflet/12/12027_20210929.pdf'),
    ('BC BIZ CORPORATE 체크카드', 'https://m.hanacard.co.kr/leaflet/12/12414_20210929.pdf'),
    ('부산 동백전 체크카드(비교통)', 'https://m.hanacard.co.kr/leaflet/12/12730_20210929.pdf'),
    ('익산다이로움 하나멤버스 1Q 체크카드_서동선화(비교통)', 'https://m.hanacard.co.kr/leaflet/12/12757_20210929.pdf'),
    ('세종 여민전 하나멤버스 1Q 체크카드 (비교통)', 'https://m.hanacard.co.kr/leaflet/12/12792_20210929.pdf'),
    ('Medi Goldclub_VIVA + (비바 플러스)플래티늄 체크카드_후불교통', 'https://m.hanacard.co.kr/leaflet/12/12951_20210929.pdf'),
    ('한국무역협회 BC BIZ CORPORATE 카드', 'https://m.hanacard.co.kr/leaflet/12/12997_20210929.pdf'),
    ('국민행복 체크카드', 'https://m.hanacard.co.kr/leaflet/13/13022_20210929.pdf'),
    ('하나기업카드', 'https://m.hanacard.co.kr/leaflet/13/13050_20210929.pdf'),
    ('Prime(프라임)기업카드', 'https://m.hanacard.co.kr/leaflet/13/13051_20210929.pdf'),
    ('굳세어라 하나기업카드', 'https://m.hanacard.co.kr/leaflet/13/13357_20210929.pdf'),
    ('할인돼지 하나카드', 'https://m.hanacard.co.kr/leaflet/13/13391_20210929.pdf'),
    ('[법인]PROPER', 'https://m.hanacard.co.kr/leaflet/90/90188_20210929.pdf'),
    ('[구매]포스트플러스 구매전용카드(우편요금전용)', 'https://m.hanacard.co.kr/leaflet/90/90232_20210929.pdf'),
    ('[법인]정부구매전용카드', 'https://m.hanacard.co.kr/leaflet/90/90680_20210929.pdf'),
    ('[법인][플래티늄03]BIZPARTNER_VISA', 'https://m.hanacard.co.kr/leaflet/91/91139_20210929.pdf'),
    ('달러페이법인', 'https://m.hanacard.co.kr/leaflet/91/91155_20210929.pdf'),
    ('[법인]NEW법인주유전용(휘발유형)', 'https://m.hanacard.co.kr/leaflet/91/91160_20210929.pdf'),
    ('[법인]에스케이주유전용카드', 'https://m.hanacard.co.kr/leaflet/91/91172_20210929.pdf'),
    ('[법인]후불하이패스카드', 'https://m.hanacard.co.kr/leaflet/91/91204_20210929.pdf'),
    ('[법인]PROPER체크', 'https://m.hanacard.co.kr/leaflet/98/98006_20210929.pdf'),
]

def safe_name(value):
    value = re.sub(r'[\\/:*?"<>|]', "_", value or "")
    value = re.sub(r"\s+", " ", value).strip()
    return value[:180]


def filename_from_url(url):
    name = unquote(os.path.basename(urlparse(url).path))
    return safe_name(name or "document.pdf")


def main():
    require_robots_allowed(url for _, url in PDF_TARGETS)
    os.makedirs(OUTPUT_DIR, exist_ok=True)

    if len(PDF_TARGETS) != 353:
        raise RuntimeError(
            f"내장 PDF 대상 수가 353건이 아닙니다: {len(PDF_TARGETS)}"
        )

    print(f"하나카드 다운로드 대상: {len(PDF_TARGETS)}건")
    print()

    # 다운로드 전에 내장된 전체 대상을 출력
    for i, (product_name, url) in enumerate(PDF_TARGETS, 1):
        print(f"{i:03d}. {product_name}")
        print(f"     {url}")

    print("\n다운로드 시작\n")

    session = requests.Session()
    session.headers.update({
        "User-Agent": USER_AGENT,
        "Accept": "application/pdf,*/*;q=0.8",
        "Referer": "https://www.hanacard.co.kr/",
    })

    success = 0
    skipped = 0
    failed = []

    for i, (product_name, url) in enumerate(PDF_TARGETS, 1):
        filename = filename_from_url(url)

        # 파일명 충돌 가능성을 없애기 위해 상품명을 앞에 붙임
        output_name = f"{safe_name(product_name)}__{filename}"
        output_path = os.path.join(OUTPUT_DIR, output_name)

        print(f"[{i:03d}/{len(PDF_TARGETS)}] {product_name}")

        if os.path.exists(output_path) and os.path.getsize(output_path) > 0:
            print(f"  건너뜀(이미 존재): {output_name}")
            skipped += 1
            continue

        try:
            r = session.get(url, timeout=TIMEOUT)
            r.raise_for_status()

            # HTML 오류 페이지 등을 PDF로 저장하지 않도록 검사
            if not r.content.startswith(b"%PDF"):
                content_type = r.headers.get("Content-Type", "")
                raise RuntimeError(
                    f"PDF가 아닌 응답 (Content-Type={content_type!r})"
                )

            with open(output_path, "wb") as f:
                f.write(r.content)

            print(f"  저장: {output_name} ({len(r.content):,} bytes)")
            success += 1

        except Exception as e:
            print(f"  실패: {e}")
            failed.append({
                "product_name": product_name,
                "url": url,
                "error": str(e),
            })

        time.sleep(DELAY)

    print("\n==============================")
    print("완료")
    print(f"전체 대상 : {len(PDF_TARGETS)}")
    print(f"신규 저장 : {success}")
    print(f"기존 파일 : {skipped}")
    print(f"실패      : {len(failed)}")

    if failed:
        print("\n실패 목록:")
        for item in failed:
            print(f"- {item['product_name']}")
            print(f"  {item['url']}")
            print(f"  {item['error']}")


if __name__ == "__main__":
    main()
