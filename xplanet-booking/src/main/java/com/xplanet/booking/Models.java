package com.xplanet.booking;

import jakarta.validation.constraints.*;
import java.time.LocalDateTime;

public final class Models {
  private Models() {}

  public enum Status {
    WAITLISTED,
    OFFERED,
    PENDING_APPROVAL,
    CONFIRMED,
    IN_USE,
    COMPLETED,
    CANCELLED,
    REJECTED,
    EXPIRED,
    NO_SHOW;

    public boolean terminal() {
      return switch (this) {
        case COMPLETED, CANCELLED, REJECTED, EXPIRED, NO_SHOW -> true;
        default -> false;
      };
    }

    public boolean holdsSlots() {
      return this == OFFERED || this == PENDING_APPROVAL || this == CONFIRMED || this == IN_USE;
    }
  }

  public record Actor(long id, String username, String role) {
    public boolean admin() {
      return "ADMIN".equals(role);
    }

    public void requireAdmin() {
      if (!admin()) throw new Problem(403, "需要管理员权限");
    }
  }

  public record Equipment(
      long id,
      String name,
      String category,
      String location,
      String description,
      boolean enabled,
      boolean approvalRequired,
      boolean trainingRequired,
      long version) {}

  public record Reservation(
      long id,
      long equipmentId,
      long userId,
      String requestKey,
      LocalDateTime startAt,
      LocalDateTime endAt,
      String purpose,
      Status status,
      LocalDateTime deadline,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {}

  public record Login(
      @NotBlank @Size(max = 32) String username, @NotBlank @Size(max = 72) String password) {}

  public record Register(
      @Pattern(regexp = "[A-Za-z0-9_]{3,32}") @NotNull String username,
      @NotNull @Size(min = 8, max = 72) String password) {}

  public record Session(String token, Actor user, LocalDateTime expiresAt) {}

  public record CreateReservation(
      @Positive long equipmentId,
      @NotNull LocalDateTime startAt,
      @NotNull LocalDateTime endAt,
      @NotBlank @Size(max = 300) String purpose,
      @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{8,64}") String requestKey) {}

  public record EquipmentInput(
      @NotBlank @Size(max = 100) String name,
      @NotBlank @Size(max = 40) String category,
      @NotBlank @Size(max = 100) String location,
      @NotNull @Size(max = 1000) String description,
      boolean approvalRequired,
      boolean trainingRequired) {}

  public record Enabled(boolean enabled, @PositiveOrZero long expectedVersion) {}

  public record Decision(boolean approve, @NotBlank @Size(max = 300) String reason) {}

  public record Training(@Positive long userId, boolean granted) {}

  public record Reschedule(
      @NotNull LocalDateTime startAt,
      @NotNull LocalDateTime endAt,
      @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{8,64}") String requestKey) {}

  public record BlockInput(
      @NotNull LocalDateTime startAt,
      @NotNull LocalDateTime endAt,
      @NotBlank @Size(max = 300) String reason,
      @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{8,64}") String requestKey) {}
}
