package com.bosalpim.compozi_ai.domain.document.dto.request.commonFile;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.Objects;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateCommonItemDocumentReqDto {

    @NotBlank(message = "docId 가 누락되었습니다.")
    @JsonProperty("doc_id")
    private String docId;

    @NotBlank(message = "sourceType 이 누락되었습니다.")
    @JsonProperty("source_type")
    private String sourceType;

    @JsonProperty("row_no")
    private Long rowNo;

    @NotBlank(message = "공급사명이 누락되었습니다.")
    @JsonProperty("supplier_name")
    private String supplierName;

    @NotBlank(message = "원본 품목명이 누락되었습니다.")
    @JsonProperty("raw_item_name")
    private String rawItemName;

    @Setter
    @JsonProperty("normalized_item_name")
    private String normalizedItemName;

    @NotBlank(message = "규격이 누락되었습니다.")
    @JsonProperty("spec")
    private String spec;

    @NotBlank(message = "단위가 누락되었습니다.")
    @JsonProperty("unit")
    private String unit;

    @NotNull(message = "변경 전 단가가 누락되었습니다.")
    @JsonProperty("price_before")
    private Long priceBefore;

    @NotNull(message = "변경 후 단가가 누락되었습니다.")
    @JsonProperty("price_after")
    private Long priceAfter;

    @NotNull(message = "적용일이 누락되었습니다.")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    @JsonProperty("effective_date")
    private LocalDate effectiveDate;

    @Setter
    @JsonProperty("duplicate_group_key")
    private String duplicateGroupKey;

    @JsonProperty("has_parse_error")
    private boolean hasParseError;


    public String getEffectiveItemName() {
        return this.normalizedItemName != null ? this.normalizedItemName : this.rawItemName;
    }

    // 1 단계 : 해시코드 연산
    @Override
    public int hashCode() {
        return Objects.hash(
                supplierName,
                getEffectiveItemName(),
                spec != null ? spec.trim() : null,
                unit != null ? unit.trim() : null,
                priceBefore,
                priceAfter,
                effectiveDate
        );
    }


    // 2 단계 : 해시 충돌 시에만 호출되는 동등성 비교
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        CreateCommonItemDocumentReqDto dto = (CreateCommonItemDocumentReqDto) o;
        return Objects.equals(supplierName, dto.supplierName) &&
                Objects.equals(getEffectiveItemName(), dto.getEffectiveItemName()) &&
                Objects.equals(spec != null ? spec.trim() : null, dto.spec != null ? dto.spec.trim() : null) &&
                Objects.equals(unit != null ? unit.trim() : null, dto.unit != null ? dto.unit.trim() : null) &&
                Objects.equals(priceBefore, dto.priceBefore) &&
                Objects.equals(priceAfter, dto.priceAfter) &&
                Objects.equals(effectiveDate, dto.effectiveDate);
    }

}
