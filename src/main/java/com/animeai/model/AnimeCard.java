package com.animeai.model;

import java.util.List;

/**
 * 精简后的番剧卡片。
 *
 * <p>刻意只保留必要字段：Bangumi 的条目详情体很大，整个塞进上下文会直接打爆 token 预算。
 * 前端拿 {@code id} 就能跳到 {@code /detail/:anime_id} 渲染卡片。
 */
public record AnimeCard(
        long id,
        String name,
        String nameCn,
        String cover,
        Double score,
        Integer rank,
        String date,
        List<String> tags,
        String summary) {}
