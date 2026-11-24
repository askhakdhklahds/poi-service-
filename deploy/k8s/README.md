# Kubernetes 部署说明

`app.yaml` 包含：

- ConfigMap / Secret
- Deployment（2 副本、滚动更新、readiness/liveness）
- Service
- HPA（CPU 70%，2–6 副本）
- Ingress（按需修改域名）

## 前提

集群里需要有这些服务（可以是托管服务或独立部署）：

- PostgreSQL/PostGIS（Service 名 `postgres`，端口 5432）
- Redis（Service 名 `redis`，端口 6379）
- Kafka（Service 名 `kafka`，端口 9092）
- GeoServer（Service 名 `geoserver`，端口 8080）

如果 Kafka 还没部署，可以先用本地 compose：

```bash
docker compose -f docker-compose.kafka.yml up -d
```

再以 `APP_IMPORT_MODE=kafka`、`KAFKA_BOOTSTRAP_SERVERS=localhost:9092` 启动应用。

## 部署

```bash
docker build -t poi-service:latest .
kubectl apply -f deploy/k8s/app.yaml
kubectl get pods -w
kubectl get hpa poi-service
```

## 说明

- 应用端口是 `8081`，探针和 Service 都指向 8081。
- HPA 默认按 CPU 70% 扩缩；如果有 Prometheus Adapter，可以改成 QPS 自定义指标。
- Kafka/PostgreSQL/Redis/GeoServer 没有写进 `app.yaml`，避免把有状态服务塞进同一个清单；生产建议用托管服务或单独的 StatefulSet/Operator。
- `AUTH_JWT_SECRET` 等敏感配置在 Secret 里，生产不要用示例值。
