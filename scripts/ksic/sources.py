"""원천 자료 9종을 한 형식으로 읽는다 — (원천구분, 표기명, 상호명) 스트림.

각 파일이 인코딩·형식·상태컬럼이 제각각이라(cp949 csv / utf-8 csv / 구형 xls /
shapefile dbf / xlsx) 여기서 흡수한다. 대조표와 상호 풀 재구성이 같은 리더를 쓴다 —
두 곳이 갈라지면 "표에 있는 상호수"와 "실제 풀 크기"가 어긋난다.
"""
import csv, io, os, struct, zipfile
import xml.etree.ElementTree as ET

REF = os.path.join(os.path.dirname(__file__), '..', '..', 'reference')
NS = '{http://schemas.openxmlformats.org/spreadsheetml/2006/main}'
LIVE = {'영업', '영업중', '정상'}


def _text(path, encodings=('cp949', 'utf-8-sig', 'euc-kr', 'utf-8')):
    """인코딩을 앞에서부터 시도하고, 전부 실패하면 **첫 인코딩으로 손실 허용** 디코딩한다.

    서울시 일반음식점 자료(222MB)에는 cp949로 해석되지 않는 바이트가 드물게 섞여 있다
    (예: 위치 3,512,448의 `\\x82`). 인코딩이 틀린 게 아니라 원본에 잡음이 있는 것이라,
    전부 포기하면 10만 개 상호를 통째로 잃는다. 깨진 글자 몇 개는 그 상호만 버리면 된다.
    """
    raw = open(path, 'rb').read()
    for enc in encodings:
        try:
            return raw.decode(enc)
        except UnicodeDecodeError:
            continue
    return raw.decode(encodings[0], 'replace')


def _clean(name):
    """디코딩 실패로 대체문자가 섞인 상호는 버린다. 화면에 �가 뜨면 안 된다."""
    return name and '�' not in name


def read_csv(name, name_col, status_col=None, type_col=None, encodings=None):
    """인허가 CSV — 상태컬럼이 있으면 영업 중만 남긴다."""
    txt = _text(os.path.join(REF, name), encodings or ('cp949', 'utf-8-sig', 'euc-kr', 'utf-8'))
    rows = list(csv.reader(io.StringIO(txt)))
    idx = {h.strip(): i for i, h in enumerate(rows[0])}
    ni, si, ti = idx.get(name_col), idx.get(status_col), idx.get(type_col)
    if ni is None:
        raise ValueError(f'{name}: 상호 컬럼 {name_col} 없음 — 헤더 {list(idx)[:12]}')
    out = []
    for r in rows[1:]:
        if len(r) <= ni:
            continue
        if si is not None and (len(r) <= si or r[si].strip() not in LIVE):
            continue
        nm = r[ni].strip()
        if not _clean(nm):
            continue
        kind = r[ti].strip() if ti is not None and len(r) > ti else ''
        out.append((kind, nm))
    return out


def read_xlsx(name, name_col, type_col=None):
    """xlsx — sharedStrings/inlineStr 양쪽을 흡수한다."""
    z = zipfile.ZipFile(os.path.join(REF, name))
    shared = []
    if 'xl/sharedStrings.xml' in z.namelist():
        for si in ET.fromstring(z.read('xl/sharedStrings.xml')).iter(NS + 'si'):
            shared.append(''.join(t.text or '' for t in si.iter(NS + 't')))
    sheet = sorted(x for x in z.namelist() if x.startswith('xl/worksheets/sheet'))[0]
    hdr, out = None, []
    for row in ET.fromstring(z.read(sheet)).iter(NS + 'row'):
        vals = []
        for c in row.iter(NS + 'c'):
            t, v = c.get('t'), c.find(NS + 'v')
            if t == 'inlineStr':
                vals.append(''.join(x.text or '' for x in c.iter(NS + 't')))
            elif v is None:
                vals.append('')
            else:
                vals.append(shared[int(v.text)] if t == 's' else v.text)
        if hdr is None:
            hdr = {h.strip(): i for i, h in enumerate(vals)}
            continue
        ni, ti = hdr.get(name_col), hdr.get(type_col)
        if ni is None or len(vals) <= ni or not vals[ni].strip():
            continue
        kind = vals[ti].strip() if ti is not None and len(vals) > ti else ''
        out.append((kind, vals[ni].strip()))
    return out


def read_xls(name, name_col, type_col=None):
    """구형 BIFF(.xls) — xlrd로 읽는다. 헤더가 1행이 아닐 수 있어 찾아간다."""
    import xlrd
    book = xlrd.open_workbook(os.path.join(REF, name))
    sh = book.sheet_by_index(0)
    hdr_row = None
    for r in range(min(5, sh.nrows)):
        cells = [str(sh.cell_value(r, c)).strip() for c in range(sh.ncols)]
        if name_col in cells:
            hdr_row = r
            hdr = {h: i for i, h in enumerate(cells)}
            break
    if hdr_row is None:
        raise ValueError(f'{name}: 헤더에서 {name_col} 못 찾음')
    ni, ti = hdr.get(name_col), hdr.get(type_col)
    out = []
    for r in range(hdr_row + 1, sh.nrows):
        nm = str(sh.cell_value(r, ni)).strip()
        if not nm:
            continue
        kind = str(sh.cell_value(r, ti)).strip() if ti is not None else ''
        out.append((kind, nm))
    return out


def read_dbf(relpath, name_field):
    """shapefile의 속성 테이블. dBase III 구조라 표준 라이브러리로 충분하다.

    인코딩은 같은 이름의 `.cpg`가 선언한다 — 경북 목욕탕은 UTF-8이다.
    dbf라고 cp949로 단정하면 한글이 통째로 깨진다(`깙궗슦굹 紐⑦뀛`).
    """
    path = os.path.join(REF, relpath)
    cpg = os.path.splitext(path)[0] + '.cpg'
    enc = 'cp949'
    if os.path.exists(cpg):
        declared = open(cpg, encoding='ascii', errors='ignore').read().strip()
        if declared:
            enc = declared
    with open(path, 'rb') as f:
        head = f.read(32)
        n_rec = struct.unpack('<I', head[4:8])[0]
        hdr_len = struct.unpack('<H', head[8:10])[0]
        rec_len = struct.unpack('<H', head[10:12])[0]
        fields = []
        for _ in range((hdr_len - 33) // 32):
            fd = f.read(32)
            fields.append((fd[:11].split(b'\x00')[0].decode(enc, 'ignore'), fd[16]))
        f.seek(hdr_len)
        try:
            ni = [x[0] for x in fields].index(name_field)
        except ValueError:
            raise ValueError(f'{relpath}: 필드 {name_field} 없음 — {[x[0] for x in fields]}')
        out = []
        for _ in range(n_rec):
            rec = f.read(rec_len)
            if not rec or rec[:1] == b'*':      # 삭제 표시된 행
                continue
            pos = 1
            for i, (_, size) in enumerate(fields):
                if i == ni:
                    nm = rec[pos:pos + size].decode(enc, 'ignore').strip()
                    if _clean(nm):
                        out.append(('', nm))
                    break
                pos += size
    return out
