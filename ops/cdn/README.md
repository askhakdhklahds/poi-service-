# 瓦片 CDN / OSS 部署

## 1. 创建 Bucket

阿里云 OSS 创建一个 Bucket，例如 `poi-tiles-prod`，开启静态网站/公共读或绑定 CDN。

目录规划：

```
oss://poi-tiles-prod/
├── poi-tiles/        # WGS84 瓦片（tiles/）
└── poi-tiles-gcj/    # GCJ-02 高德对齐瓦片（tiles-gcj/）
```

## 2. 上传瓦片

```bash
export OSS_BUCKET=poi-tiles-prod
bash scripts/upload_tiles_to_cdn.sh
```

或使用 AWS S3：

```bash
aws s3 sync tiles/ s3://$OSS_BUCKET/poi-tiles/ --cache-control "public,max-age=604800"
aws s3 sync tiles-gcj/ s3://$OSS_BUCKET/poi-tiles-gcj/ --cache-control "public,max-age=604800"
```

## 3. 配置 CDN

- 源站指向 OSS Bucket 根目录
- 缓存规则：`.pbf` 缓存 7 天
- 开启 CORS：允许你的站点域名（或 `*`，仅演示）
- 建议开启 HTTPS

## 4. 应用配置

启动应用时设置：

```bash
TILES_PUBLIC_BASE_URL=https://cdn.example.com/poi-tiles
TILES_PUBLIC_GCJ_BASE_URL=https://cdn.example.com/poi-tiles-gcj
```

应用会通过 `/api/config` 返回：

```json
{"tileBaseUrl":"https://cdn.example.com/poi-tiles","gcjTileBaseUrl":"https://cdn.example.com/poi-tiles-gcj"}
```

`all-tiles.html` 会自动把瓦片源切到 CDN；本地未配置时继续用 `/tiles-gcj/...`。

## 5. 本地回退

- 未配置 CDN 时，Spring Boot 从 `tiles/`、`tiles-gcj/` 读磁盘并带 7 天缓存头
- 配置 CDN 后，首屏由 CDN 承担，应用只提供 API
