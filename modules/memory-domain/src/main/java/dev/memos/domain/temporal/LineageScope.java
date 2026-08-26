package dev.memos.domain.temporal;

public record LineageScope(String tenantId, String userId, String agentId) {
  public LineageScope {
    tenantId = TemporalValidation.text(tenantId, "tenantId", 128);
    userId = TemporalValidation.text(userId, "userId", 128);
    agentId = TemporalValidation.text(agentId, "agentId", 128);
  }
}
