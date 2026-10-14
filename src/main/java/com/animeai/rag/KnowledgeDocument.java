package com.animeai.rag;

import java.util.Map;

/** 一条可被向量检索的完整语义单元。M2 不切分番剧简介。 */
public record KnowledgeDocument(long subjectId, String text, Map<String, Object> payload) {}
