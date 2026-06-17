# Deployment — spring-ai-alibaba-rag

> 设计 Spec §17 — 部署演进 (本地 → 小规模生产 → 中大型 K8s)
>
> 来源文章 §17 — Spring Boot + Spring AI Alibaba + Redis 企业级向量检索与 RAG 引擎实战

---

## 1. 4 阶段演进

| 阶段 | 规模 | 架构 | 适用场景 |
|---|---|---|---|
| **P1 本地试点** | 1 Redis + 1 App | Docker Compose | 个人开发 / POC |
| **P2 小规模生产** | App 2 实例 + Redis Sentinel + DashScope | Compose + 独立 Sentinel 集群 | 1-10 QPS,内部使用 |
| **P3 中大型** | K8s Deployment (HPA) + Redis Cluster + 独立 Embedding 池 | K8s + Helm | 100-1000 QPS,商业化 |
| **P4 全球化** | 多 region + CDN + 边缘缓存 | K8s + 多集群 + 对象存储 | 1000+ QPS,全球用户 |

本文聚焦 **P1-P3** (P4 已超出 spec 范围)。

---

## 2. P1 — 本地 Docker Compose

### 2.1 文件布局

```
spring-ai-alibaba-rag/
├── Dockerfile                # 多阶段构建 (Aliyun Maven mirror)
├── docker-compose.yml        # app + redis profiles
├── .dockerignore             # 排除 target/ .git 等
└── scripts/
    └── build-docker.sh       # 便捷构建脚本
```

> **注**: Spec §3 原本要求 `docker/` 子目录,实际为保持 Docker 标准 (Dockerfile 在 root 是惯例),改为 root 布局。功能等价。

### 2.2 Dockerfile (多阶段)

```dockerfile
# Stage 1 — build
FROM eclipse-temurin:21-jdk AS build
COPY .docker-maven/settings.xml /root/.m2/settings.xml  # Aliyun mirror
WORKDIR /build
COPY pom.xml .
RUN mvn -B -ntp -q dependency:go-offline  # 预下载依赖
COPY rag-core rag-core
COPY rag-redis rag-redis
COPY rag-embedding rag-embedding
COPY rag-pipeline rag-pipeline
COPY rag-app rag-app
RUN mvn -B -ntp -pl rag-app -am package -DskipTests

# Stage 2 — runtime
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=build /build/rag-app/target/rag-app-0.1.0-SNAPSHOT-boot.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

镜像大小: **353 MB** (cluster 4 验证)

### 2.3 docker-compose.yml (profile 隔离)

```yaml
services:
  redis:
    image: redis/redis-stack:7.4.0-v1
    ports: ["6379:6379", "8001:8001"]
    profiles: [full]  # 默认不起,避免与已有 rag-redis-stack 冲突

  app:
    build: .
    ports: ["18081:8080"]
    depends_on:
      redis:
        condition: service_started
        required: false
    environment:
      SPRING_PROFILES_ACTIVE: docker
      SILICONFLOW_API_KEY: ${SILICONFLOW_API_KEY:-}
      SPRING_RAG_REDIS_HOST: ${SPRING_RAG_REDIS_HOST:-host.docker.internal}
      SPRING_RAG_REDIS_PORT: ${SPRING_RAG_REDIS_PORT:-6379}
    profiles: [full, app]
```

### 2.4 操作

```bash
# 仅起 app (复用已有 redis 容器)
docker compose up -d app

# 全起 (redis + app)
docker compose --profile full up -d --build

# 验证
docker compose ps
curl -s http://localhost:18081/actuator/health
# → {"status":"UP"}

# 停止
docker compose down
```

### 2.5 镜像优化 (LESSONS §8)

| 项 | 优化 | 节省 |
|---|---|---|
| 基础镜像 | `eclipse-temurin:21-jre-jammy` 替代 `-jdk` | -200 MB |
| 多阶段构建 | builder + runtime 分层 | -800 MB |
| `.dockerignore` | 排除 `target/` `.git/` `.m2/` | 构建速度 ↑ |
| Aliyun Maven mirror | `maven.aliyun.com` 替代 central | 国内下载 ↑↑ |
| `dependency:go-offline` | 预下载依赖,Docker 层缓存 | 增量构建 ↑↑ |

---

## 3. P2 — 小规模生产

### 3.1 架构

```
                    ┌─────────────┐
                    │  LB / Nginx │
                    └──────┬──────┘
                           │
              ┌────────────┴────────────┐
              ▼                         ▼
       ┌─────────────┐          ┌─────────────┐
       │  rag-app-1  │          │  rag-app-2  │
       └──────┬──────┘          └──────┬──────┘
              │                        │
              └────────────┬───────────┘
                           ▼
              ┌──────────────────────────┐
              │   Redis Sentinel         │
              │  (master + 2 replicas)   │
              └──────────────────────────┘
                           │
                           ▼
                  ┌──────────────────┐
                  │   DashScope API  │
                  └──────────────────┘
```

### 3.2 部署清单

| 组件 | 数量 | 配置 |
|---|---|---|
| rag-app | 2 实例 | 2 CPU / 4 GB / JVM heap 2 GB |
| Redis Sentinel | 1 master + 2 replicas + 3 sentinels | 4 CPU / 8 GB / AOF 每秒 fsync |
| DashScope | 1 账号 | QPS 上限 100 (按需扩容) |
| LB | Nginx / SLB | round-robin + health check |

### 3.3 反亲和性

```yaml
# docker-compose-p2.yml (简化)
services:
  rag-app:
    image: rag-app:0.1.0
    deploy:
      replicas: 2
      resources:
        limits:
          cpus: '2'
          memory: 4G
      placement:
        constraints: [node.role != manager]
```

### 3.4 监控接入

```yaml
# prometheus.yml (scrape config)
scrape_configs:
  - job_name: 'rag-app'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['rag-app-1:8080', 'rag-app-2:8080']

  - job_name: 'redis'
    static_configs:
      - targets: ['redis-exporter:9121']
```

---

## 4. P3 — K8s 中大型

### 4.1 架构

```
                    ┌──────────────┐
                    │   Ingress    │
                    │   (nginx)    │
                    └──────┬───────┘
                           │
              ┌────────────┴────────────┐
              ▼                         ▼
       ┌─────────────┐          ┌─────────────┐
       │  rag-app    │          │  rag-app    │
       │  Pod 1      │          │  Pod 2      │
       └──────┬──────┘          └──────┬──────┘
              │     HPA (CPU 70%)       │
              └────────────┬───────────┘
                           ▼
              ┌──────────────────────────┐
              │   Redis Cluster          │
              │   (6 nodes: 3M + 3S)     │
              └──────────────────────────┘
                           │
                           ▼
              ┌──────────────────────────┐
              │   Embedding Service      │
              │   (独立 Deployment)      │
              │   HPA: QPS 1000          │
              └──────────────────────────┘
```

### 4.2 K8s 关键 manifest

```yaml
# rag-app-deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: rag-app
spec:
  replicas: 3
  selector:
    matchLabels:
      app: rag-app
  template:
    metadata:
      labels:
        app: rag-app
    spec:
      containers:
        - name: rag-app
          image: registry.example.com/rag-app:0.1.0
          ports:
            - containerPort: 8080
          resources:
            requests:
              cpu: "1"
              memory: "2Gi"
            limits:
              cpu: "2"
              memory: "4Gi"
          env:
            - name: SPRING_PROFILES_ACTIVE
              value: k8s
            - name: SPRING_RAG_REDIS_HOST
              valueFrom:
                configMapKeyRef:
                  name: rag-config
                  key: redis.host
            - name: SILICONFLOW_API_KEY
              valueFrom:
                secretKeyRef:
                  name: rag-secrets
                  key: siliconflow.api-key
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: 8080
            initialDelaySeconds: 30
            periodSeconds: 10
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: 8080
            initialDelaySeconds: 10
            periodSeconds: 5
          startupProbe:
            httpGet:
              path: /actuator/health
              port: 8080
            failureThreshold: 60
            periodSeconds: 5   # 给首次 embedding 索引留 5 分钟
---
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: rag-app-hpa
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: rag-app
  minReplicas: 3
  maxReplicas: 10
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 70
    - type: Pods
      pods:
        metric:
          name: rag_qa_requests_per_second
        target:
          type: AverageValue
          averageValue: "1000"
```

### 4.3 ConfigMap vs Secret 分层

```yaml
# ConfigMap (非敏感)
apiVersion: v1
kind: ConfigMap
metadata:
  name: rag-config
data:
  redis.host: "redis-cluster.data.svc.cluster.local"
  redis.port: "6379"
  ingest.executor.core-size: "4"
  qa.cache.ttl-seconds: "3600"

---
# Secret (敏感)
apiVersion: v1
kind: Secret
metadata:
  name: rag-secrets
type: Opaque
stringData:
  siliconflow.api-key: "sk-xxx"
  dashscope.api-key: "sk-yyy"
```

### 4.4 PodDisruptionBudget

```yaml
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: rag-app-pdb
spec:
  minAvailable: 2
  selector:
    matchLabels:
      app: rag-app
```

### 4.5 K8s 关注点

| 关注点 | 配置 | 理由 |
|---|---|---|
| Liveness | `/actuator/health/liveness` | Redis 挂掉 = pod restart? ❌ 会重建更多连接,更糟 |
| Readiness | `/actuator/health/readiness` | Redis 不可达 → 0 流量,等恢复 |
| Startup | 60s 阈值 | 首次 embedding 索引创建可能慢 |
| HPA CPU | 70% | 防止过度调度 |
| HPA QPS | 1000/instance | 业务自定义指标 |
| PDB minAvailable | 2 | 保证滚动升级时至少 2 实例在线 |
| ResourceQuota | 命名空间级 | 防止某 team 抢占资源 |

---

## 5. 环境变量 vs application.yml

| 项 | 优先级 | 用途 |
|---|---|---|
| 环境变量 `SPRING_APPLICATION_JSON` | 最高 | CI/CD 注入 |
| `application-{profile}.yml` | 中 | 环境特定配置 |
| `application.yml` | 低 | 默认值 |

### 5.1 推荐: K8s 用 ConfigMap + Secret + 环境变量引用

```yaml
env:
  - name: SPRING_RAG_REDIS_HOST
    valueFrom:
      configMapKeyRef:
        name: rag-config
        key: redis.host
```

### 5.2 推荐: 本地用 `.env` + `env_file:`

```yaml
# docker-compose.yml
services:
  app:
    env_file: .env
```

```bash
# .env
SPRING_RAG_REDIS_HOST=host.docker.internal
SILICONFLOW_API_KEY=sk-xxx
```

---

## 6. 灰度发布 (Blue-Green / Canary)

### 6.1 Blue-Green (零停机)

```bash
# 1. 部署 green (新版本)
kubectl apply -f rag-app-green.yaml

# 2. 验证 green 健康
kubectl wait --for=condition=ready pod -l app=rag-app,version=green

# 3. 切流量 (修改 Service selector)
kubectl patch service rag-app -p '{"spec":{"selector":{"version":"green"}}}'

# 4. 监控 5 分钟,无异常则下线 blue
kubectl delete -f rag-app-blue.yaml
```

### 6.2 Canary (10% → 50% → 100%)

```yaml
# 使用 Istio VirtualService
apiVersion: networking.istio.io/v1beta1
kind: VirtualService
metadata:
  name: rag-app
spec:
  hosts: [rag-app]
  http:
    - route:
        - destination:
            host: rag-app
            subset: stable
          weight: 90
        - destination:
            host: rag-app
            subset: canary
          weight: 10
```

---

## 7. 回滚策略

```bash
# Helm 回滚
helm history rag-app
helm rollback rag-app 1   # 回滚到 revision 1

# K8s 回滚
kubectl rollout undo deployment/rag-app

# 查看历史
kubectl rollout history deployment/rag-app
```

回滚前提:
- 数据库 schema 兼容 (我们用 Redis,无 schema,无需迁移)
- 配置项有默认值 (`application.yml` + 环境变量 fallback)

---

## 8. 部署 checklist

- [ ] Redis Sentinel / Cluster 已就绪 + RediSearch module 已加载
- [ ] SiliconFlow API Key 已配置 + 余额充足
- [ ] Liveness/Readiness/Startup probe 都配置
- [ ] HPA 启用 (CPU + QPS 双指标)
- [ ] PDB 配置 (minAvailable 至少 2)
- [ ] ResourceQuota 在命名空间级
- [ ] Prometheus 抓取 + Grafana dashboard
- [ ] AlertManager 告警 (latency / breaker / cache hit ratio)
- [ ] 灰度发布流程已演练
- [ ] 回滚流程已演练
- [ ] 备份策略 (Redis AOF + 跨 region 复制)
- [ ] 文档同步更新 (RUNBOOK + this file)

详见 [docs/checklist.md](./checklist.md)。

---

## 9. 参考

- [docs/RUNBOOK.md](./RUNBOOK.md) — 本地开发 + Docker Compose 操作
- [docs/architecture.md](./architecture.md) — 整体架构
- [docs/checklist.md](./checklist.md) — 生产落地 checklist
- [docs/evolution.md](./evolution.md) — 后续演进路径
- K8s 官方: https://kubernetes.io/docs/concepts/
- Spring Boot K8s: https://spring.io/guides/gs/spring-boot-kubernetes/
