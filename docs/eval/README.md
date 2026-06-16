# Eval 评测报告

> 由 `EVAL_SUITE=1 mvn test -Peval -pl rag-test` 自动生成。

## 指标说明

| 指标 | 含义 | 阈值 |
|---|---|---|
| Recall@K | 前 K 个 reranked chunk 包含预期 chunk 的比例 | ≥0.5 |
| MRR | 第一个相关 chunk 的 rank 倒数 | ≥0.3 |
| Grounded Rate | LLM 回答引用了预期来源的比例 | ≥0.5 |

## DoD (§16)

- [ ] EvalSuiteTest 通过（pass rate ≥50%）
- [ ] `docs/eval/eval-report.json` 已生成
- [ ] 所有 10+ fixture 覆盖不同场景

## 当前结果

（等待首次运行填充 — 需要真实 SiliconFlow API key + Redis Stack 容器）
