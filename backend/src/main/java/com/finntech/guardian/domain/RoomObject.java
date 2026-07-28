package com.finntech.guardian.domain;

import com.finntech.guardian.domain.GuardianEnums.Grade;
import com.finntech.guardian.domain.GuardianEnums.ObjectSource;
import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 마이룸 사물 — 보유 상태와 배치 슬롯 (설계서 §3.6).
 *
 * <p>사물 마스터 데이터(이름·이미지)는 코드 상수로 두고 여기엔 키만 남긴다.
 * {@code slotIndex}가 null이면 창고에 있는 것이다.
 *
 * <p>{@code reasonCode}는 {@link DailyVerdict#getReasonCode()}를 복사해 온다 —
 * 사물을 눌렀을 때 "무지출 4일째에 받았어요"라고 말해주려면 사물 쪽에도 근거가 있어야 한다.
 */
@Entity
@Table(name = "guardian_room_object",
        uniqueConstraints = @UniqueConstraint(name = "uk_groom_user_object",
                columnNames = {"user_id", "object_id"}),
        indexes = @Index(name = "idx_groom_user_slot", columnList = "user_id, slot_index"))
public class RoomObject {

    /** 마이룸 슬롯 수. */
    public static final int SLOT_COUNT = 20;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 사물 마스터 데이터 키. */
    @Column(name = "object_id", nullable = false, length = 60)
    private String objectId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Grade grade;

    @Column(name = "acquired_date", nullable = false)
    private LocalDate acquiredDate;

    @Column(name = "reason_code", length = 40)
    private String reasonCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ObjectSource source = ObjectSource.DAILY;

    /** 0..19. null이면 창고. */
    @Column(name = "slot_index")
    private Integer slotIndex;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected RoomObject() {}

    public RoomObject(Long userId, String objectId, Grade grade, LocalDate acquiredDate,
                      String reasonCode, ObjectSource source, LocalDateTime createdAt) {
        this.userId = userId;
        this.objectId = objectId;
        this.grade = grade;
        this.acquiredDate = acquiredDate;
        this.reasonCode = reasonCode;
        this.source = source == null ? ObjectSource.DAILY : source;
        this.createdAt = createdAt;
    }

    public void placeAt(Integer slotIndex) {
        if (slotIndex != null && (slotIndex < 0 || slotIndex >= SLOT_COUNT)) {
            throw new IllegalArgumentException("슬롯은 0~" + (SLOT_COUNT - 1) + " 사이여야 해요");
        }
        this.slotIndex = slotIndex;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getObjectId() { return objectId; }
    public Grade getGrade() { return grade; }
    public LocalDate getAcquiredDate() { return acquiredDate; }
    public String getReasonCode() { return reasonCode; }
    public ObjectSource getSource() { return source; }
    public Integer getSlotIndex() { return slotIndex; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
