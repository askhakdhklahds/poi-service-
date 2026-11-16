package com.example.poi.agent.intent;

/**
 * 只保留 4 个顶层意图，负责路由到角色。
 * 查询内部是附近/最近/统计/排名/对比，用 entities.scene 区分，不再作为顶层意图。
 */
public enum IntentCategory {
    QUERY,
    ROUTE,
    REPORT,
    GENERAL
}
