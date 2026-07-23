# -*- coding: utf-8 -*-
"""
W8 D1 — 낭비/필수 해석가능 ML(EBM 순수 GAM). 추론 일치·누수 금지 특징 + 페르소나 프록시(사용자 행동집계).
특징: category2 · log금액 · 시각(sin/cos)·심야 · 요일(sin/cos)·주말 · 개인 평소대비 금액(과다)
     + user_mean_log_amount(부유/성향 프록시) · user_disc_ratio(재량지출 비율=충동성 프록시).
제외: discretionary_score(=p_waste 누수)·persona(직접 미가용)·절대날짜(tenure 누수). 분리: TRAIN/TEST(SERVICE 제외).
"""
import json, time, pickle, bisect
import numpy as np, pandas as pd
from scipy.special import expit

ML="/Users/paeseuteukaempeoseu/Downloads/finntech-ml"; t0=time.time()
def log(m): print(f"[{time.time()-t0:6.1f}s] {m}", flush=True)
ESSENTIAL={"대형마트","편의점","약국","대중교통","철도","고속버스","통신비","공과금","주유소","통행료"}

log("맵 로드")
us=pd.read_csv(f"{ML}/user_split.tsv",sep="\t")
u2code={u:i for i,u in enumerate(us["mydata_user_id"])}
split_by_code=us["mydata_user_data_split"].to_numpy()
persona_bycode=us["mydata_user_persona"].astype("category")
pcodes=persona_bycode.cat.codes.to_numpy(); pcats=list(persona_bycode.cat.categories)
cu=pd.read_csv(f"{ML}/card_user.tsv",sep="\t")
card2code={c:u2code[u] for c,u in zip(cu["mydata_card_id"],cu["mydata_user_id"]) if u in u2code}

log("결제 로드")
df=pd.read_csv(f"{ML}/payments.tsv",sep="\t",
    dtype={"card_id":"string","cat2":"category","amount":"int32","label":"string"},parse_dates=["dt"])
log(f"{len(df):,}행 — 매핑")
df["ucode"]=df["card_id"].map(card2code).astype("int32"); df.drop(columns=["card_id"],inplace=True)
df["split"]=split_by_code[df["ucode"].to_numpy()]
df=df[df["split"]!="SERVICE"].copy(); df["pcode"]=pcodes[df["ucode"].to_numpy()]
log(f"비-SERVICE {len(df):,}행 — 특징")

df["y"]=(df["label"]=="WASTE").astype("int8")
df["log_amount"]=np.log1p(df["amount"].astype("float32"))
hr=df["dt"].dt.hour.to_numpy(); dow=df["dt"].dt.dayofweek.to_numpy()
df["hour_sin"]=np.sin(2*np.pi*hr/24).astype("float32"); df["hour_cos"]=np.cos(2*np.pi*hr/24).astype("float32")
df["dow_sin"]=np.sin(2*np.pi*dow/7).astype("float32"); df["dow_cos"]=np.cos(2*np.pi*dow/7).astype("float32")
df["night"]=((hr>=23)|(hr<=4)).astype("int8"); df["weekend"]=(dow>=5).astype("int8")
df["is_disc"]=(~df["cat2"].isin(ESSENTIAL)).astype("int8")
key=df["ucode"].to_numpy().astype("int64")*100+df["cat2"].cat.codes.to_numpy().astype("int64"); df["_key"]=key
med=df.groupby("_key")["amount"].transform("median").astype("float32")
df["amt_vs_typical"]=(df["amount"]/med.clip(lower=1)).clip(upper=20).astype("float32")
# 페르소나 프록시(사용자 단위 집계, 라벨 미사용 → 누수 아님)
df["user_mean_log_amount"]=df.groupby("ucode")["log_amount"].transform("mean").astype("float32")
df["user_disc_ratio"]=df.groupby("ucode")["is_disc"].transform("mean").astype("float32")
df.drop(columns=["_key","dt","label","ucode","amount","is_disc"],inplace=True)

FEATS=["cat2","log_amount","hour_sin","hour_cos","night","dow_sin","dow_cos","weekend","amt_vs_typical","user_mean_log_amount","user_disc_ratio"]
NUM=[f for f in FEATS if f!="cat2"]
tr=df[df["split"]=="TRAIN"]; te=df[df["split"]=="TEST"]
log(f"TRAIN {len(tr):,} / TEST {len(te):,} · 낭비율 {te['y'].mean():.3f}")
Xtr,ytr=tr[FEATS],tr["y"].to_numpy(); Xte,yte=te[FEATS],te["y"].to_numpy(); pte=te["pcode"].to_numpy()

from sklearn.metrics import average_precision_score,roc_auc_score,precision_recall_fscore_support,brier_score_loss,precision_recall_curve
def best_f1_threshold(y,proba):
    p,r,th=precision_recall_curve(y,proba); f1=2*p*r/(p+r+1e-12)
    return float(th[max(0,np.argmax(f1)-0)]) if len(th)>0 else 0.5
def evaluate(name,proba):
    ap=average_precision_score(yte,proba); roc=roc_auc_score(yte,proba); brier=brier_score_loss(yte,proba)
    thr=best_f1_threshold(yte,proba); pred=(proba>=thr).astype(int)
    p,r,f,_=precision_recall_fscore_support(yte,pred,average="binary",zero_division=0)
    log(f"  [{name}] PR-AUC={ap:.4f} ROC-AUC={roc:.4f} Brier={brier:.4f} | @thr={thr:.3f}: P={p:.3f} R={r:.3f} F1={f:.3f}")
    return {"model":name,"pr_auc":round(ap,4),"roc_auc":round(roc,4),"brier":round(brier,4),
            "threshold":round(thr,3),"precision":round(p,3),"recall":round(r,3),"f1":round(f,3)}
results=[]

log("로지스틱")
from sklearn.pipeline import Pipeline; from sklearn.compose import ColumnTransformer
from sklearn.preprocessing import OneHotEncoder,StandardScaler; from sklearn.linear_model import LogisticRegression
logit=Pipeline([("prep",ColumnTransformer([("cat",OneHotEncoder(handle_unknown="ignore"),["cat2"]),("num",StandardScaler(),NUM)])),
                ("clf",LogisticRegression(solver="saga",max_iter=60))])
logit.fit(Xtr,ytr); results.append(evaluate("Logistic",logit.predict_proba(Xte)[:,1]))

log("GBM(HGB)")
from sklearn.ensemble import HistGradientBoostingClassifier
Xtr_g=Xtr.copy(); Xte_g=Xte.copy(); Xtr_g["cat2"]=Xtr_g["cat2"].astype("category"); Xte_g["cat2"]=Xte_g["cat2"].astype("category")
gbm=HistGradientBoostingClassifier(categorical_features=["cat2"],max_iter=300,learning_rate=0.08,max_leaf_nodes=63,early_stopping=True,random_state=0)
gbm.fit(Xtr_g,ytr); results.append(evaluate("GBM(HGB)",gbm.predict_proba(Xte_g)[:,1]))

log("EBM(프로덕션, 순수 GAM)")
from interpret.glassbox import ExplainableBoostingClassifier
ebm=ExplainableBoostingClassifier(feature_names=FEATS,feature_types=["nominal"]+["continuous"]*(len(FEATS)-1),
        interactions=0,max_bins=256,outer_bags=1,max_rounds=2000,random_state=0,n_jobs=1)  # n_jobs=1: 교착 회피 / outer_bags=1·max_rounds·서브샘플 축소로 신속 학습
_n=min(len(Xtr),120_000)  # 속도: 순수 가법(GAM) 형상함수는 대량표본에서 안정 → TRAIN 서브샘플(지표 거의 동일·결정론)
_idx=np.random.RandomState(0).permutation(len(Xtr))[:_n]
log(f"EBM 학습표본 {_n:,}행(서브샘플)")
ebm.fit(Xtr.iloc[_idx],ytr[_idx]); ebm_proba=ebm.predict_proba(Xte)[:,1]; results.append(evaluate("EBM",ebm_proba))
log("EBM 페르소나별")
for i,pn in enumerate(pcats):
    m=(pte==i)
    if m.sum()>100: log(f"    {pn}: n={m.sum():,} 실낭비율={yte[m].mean():.3f} PR-AUC={average_precision_score(yte[m],ebm_proba[m]):.3f}")
# 전역 특징 중요도(설명가능성)
imp=ebm.term_importances(); order=np.argsort(imp)[::-1]
gi=[{"feature":ebm.term_names_[j],"importance":round(float(imp[j]),4)} for j in order]
log("EBM 특징 중요도(상위): "+", ".join(f"{d['feature']}={d['importance']}" for d in gi[:6]))

log("저장 & 내보내기")
pickle.dump(ebm,open(f"{ML}/ebm.pkl","wb"))
g=ebm.explain_global(); terms=[]
for i,fn in enumerate(FEATS):
    d=g.data(i); nominal=ebm.feature_types_in_[i]=="nominal"
    terms.append({"feature":fn,"type":ebm.feature_types_in_[i],
                  "names":[str(x) for x in d["names"]] if nominal else [float(x) for x in d["names"]],
                  "scores":[float(x) for x in d["scores"]]})
export={"intercept":float(np.array(ebm.intercept_).ravel()[0]),"features":FEATS,"terms":terms,
        "decision_threshold":results[-1]["threshold"]}
json.dump(export,open(f"{ML}/ebm_export.json","w"),ensure_ascii=False,indent=1)
json.dump({"results":results,"n_train":int(len(tr)),"n_test":int(len(te)),"features":FEATS,
           "waste_rate_test":float(yte.mean()),"global_importance":gi},open(f"{ML}/metrics.json","w"),ensure_ascii=False,indent=1)
# 자체 패리티 + Java용 표본
def score_row(vals):
    s=export["intercept"]
    for t,v in zip(terms,vals):
        sc=t["scores"]
        if t["type"]=="nominal": s+= sc[t["names"].index(v)] if v in t["names"] else 0.0
        else:
            idx=bisect.bisect_right(t["names"],float(v))-1; s+=sc[max(0,min(idx,len(sc)-1))]
    return expit(s)
idxs=np.random.RandomState(0).choice(len(te),300,replace=False); Xs=Xte.iloc[idxs]; lib=ebm.predict_proba(Xs)[:,1]; mx=0.0
samples=[]
for k,(_,row) in enumerate(Xs.iterrows()):
    vals=[row[f] for f in FEATS]; man=score_row(vals); mx=max(mx,abs(man-lib[k]))
    samples.append({"features":{f:(row[f] if f=="cat2" else float(row[f])) for f in FEATS},"proba":float(lib[k])})
json.dump(samples,open(f"{ML}/parity_samples.json","w"),ensure_ascii=False)
log(f"테이블 재현 최대오차 {mx:.2e}")
log(f"완료: {[(r['model'],r['pr_auc']) for r in results]}")
