package com.animeai.rag;

import java.util.Map;

public record KnowledgeHit(long subjectId, double score, String text, Map<String, Object> payload) {}
