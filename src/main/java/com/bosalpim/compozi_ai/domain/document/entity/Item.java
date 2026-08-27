package com.bosalpim.compozi_ai.domain.document.entity;

import com.bosalpim.compozi_ai.domain.document.dto.request.commonFile.CreateCommonItemDocumentReqDto;
import com.bosalpim.compozi_ai.domain.document.dto.request.manualFile.CreateManualItemDocumentReqDto;
import com.bosalpim.compozi_ai.domain.document.enums.ReviewStatus;
import com.bosalpim.compozi_ai.domain.document.enums.SourceType;
import com.bosalpim.compozi_ai.domain.inbox.dto.request.ItemUpdateRequestDto;
import com.bosalpim.compozi_ai.domain.inbox.entity.DuplicatedGroup;
import com.bosalpim.compozi_ai.general.entity.BaseTimeStampEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PACKAGE)
@Table(name = "items",
        uniqueConstraints = @UniqueConstraint(columnNames = {"file_id", "doc_id"}),
        indexes = @Index(name = "idx_item_duplicate_check",
                columnList = "supplier_name, normalized_item_name, spec, unit, price_before, price_after, effective_date"))
public class Item extends BaseTimeStampEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String docId;

    private Long rowNo; // manual 의 경우 null 가능

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SourceType sourceType;

    private String supplierName;

    private String rawItemName;

    private String normalizedItemName;

    private String spec;

    private String unit;

    private Long priceBefore;

    private Long priceAfter;

    private LocalDate effectiveDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReviewStatus reviewStatus;

    private LocalDateTime deletedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "file_id")
    private File file;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "duplicated_group_id")
    private DuplicatedGroup duplicatedGroup;

    public static Item CreateCommonItem(CreateCommonItemDocumentReqDto reqDto, File file, DuplicatedGroup group,
                                        ReviewStatus reviewStatus) {

        return Item.builder()
                .file(file)
                .duplicatedGroup(group)
                .docId(reqDto.getDocId())
                .sourceType(SourceType.from(reqDto.getSourceType()))
                .rowNo(reqDto.getRowNo())
                .supplierName(reqDto.getSupplierName().trim())
                .rawItemName(reqDto.getRawItemName().trim())
                .normalizedItemName(reqDto.getNormalizedItemName())
                .spec(reqDto.getSpec().trim())
                .unit(reqDto.getUnit().trim())
                .priceBefore(reqDto.getPriceBefore())
                .priceAfter(reqDto.getPriceAfter())
                .effectiveDate(reqDto.getEffectiveDate())
                .reviewStatus(reviewStatus)
                .build();

    }

    public static Item CreateManualItem(CreateManualItemDocumentReqDto reqDto, File file, String normalizedItemName,
                                        DuplicatedGroup group, ReviewStatus reviewStatus) {
        return Item.builder()
                .file(file)
                .duplicatedGroup(group)
                .sourceType(SourceType.MANUAL)
                .supplierName(reqDto.getSupplierName().trim())
                .rawItemName(reqDto.getRawItemName().trim())
                .normalizedItemName(trimSafely(normalizedItemName))
                .spec(reqDto.getSpec().trim())
                .unit(reqDto.getUnit().trim())
                .priceBefore(reqDto.getPriceBefore())
                .priceAfter(reqDto.getPriceAfter())
                .effectiveDate(reqDto.getEffectiveDate())
                .reviewStatus(reviewStatus)
                .build();
    }

    private static String trimSafely(String value) {
        return value == null ? null : value.trim();
    }

    @PrePersist
    private void prePersist() {
        if (this.docId == null) {
            this.docId = "TEMP-" + UUID.randomUUID().toString().substring(0, 8);
            // 디폴트 docId unique 중복 방지
        }
    }

    @PostPersist
    private void generateDocId() {
        if (this.docId.startsWith("TEMP")) {
            this.docId = String.format("M-%03d", this.id);
        }
    }

    public void approve() {
        this.reviewStatus = ReviewStatus.APPROVED;
    }

    public void reject() {
        this.reviewStatus = ReviewStatus.REJECTED;
    }

    public void reReview() {
        this.reviewStatus = ReviewStatus.NEW;
    }

    public void updateDuplicatedGroup(DuplicatedGroup newGroup) {
        this.duplicatedGroup = newGroup;
    }

    public void updateItem(ItemUpdateRequestDto dto) {
        if (dto.getNormalizedItemName() != null) {
            this.normalizedItemName = dto.getNormalizedItemName().trim();
        }
        if (dto.getSupplierName() != null) {
            this.supplierName = dto.getSupplierName().trim();
        }
        if (dto.getSpec() != null) {
            this.spec = dto.getSpec().trim();
        }
        if (dto.getUnit() != null) {
            this.unit = dto.getUnit().trim();
        }
        if (dto.getPriceBefore() != null) {
            this.priceBefore = dto.getPriceBefore();
        }
        if (dto.getPriceAfter() != null) {
            this.priceAfter = dto.getPriceAfter();
        }
        if (dto.getEffectiveDate() != null) {
            this.effectiveDate = dto.getEffectiveDate();
        }
    }

    public void updateReviewStatus(ReviewStatus reviewStatus) {
        this.reviewStatus = reviewStatus;
    }

    public void delete() {
        this.deletedAt = LocalDateTime.now();
    }
}
