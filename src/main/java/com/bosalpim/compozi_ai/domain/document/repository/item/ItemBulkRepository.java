package com.bosalpim.compozi_ai.domain.document.repository.item;

import com.bosalpim.compozi_ai.domain.document.entity.Item;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class ItemBulkRepository {
    // GeneratedKeys(생성된 PK)를 가져오기 위해 NamedParameterJdbcTemplate을 사용
    // 기존 위치 기반 (? 이용한 일반 JdbcTemplate) 에서 이름 기반으로 가독성과 실수를 줄이도록 하였다.
    private final NamedParameterJdbcTemplate jdbcTemplate;

    @Transactional
    public void saveAllItemsInBatch(List<Item> items, int batchSize) {
        if (items == null || items.isEmpty()) {
            return;
        }

        String sql =
                "INSERT INTO `items` (\n"
                        + "  `effective_date`, `duplicated_group_id`, `file_id`, `price_after`, `price_before`,\n"
                        + "  `row_no`, `doc_id`, `normalized_item_name`, `raw_item_name`, `spec`, \n"
                        + "  `supplier_name`, `unit`, `review_status`, `source_type`, `created_at`\n"
                        + ") VALUES (\n"
                        + "  :effectiveDate, :duplicatedGroupId, :fileId, :priceAfter, :priceBefore,\n"
                        + "  :rowNo, :docId, :normalizedItemName, :rawItemName, :spec,\n"
                        + "  :supplierName, :unit, :reviewStatus, :sourceType, NOW()\n"
                        + ")";

        // 배치 단위(batchSize)로 분할 처리하여 KeyHolder를 가져옴
        for (int i = 0; i < items.size(); i += batchSize) {
            List<Item> subList = items.subList(i, Math.min(i + batchSize, items.size()));

            SqlParameterSource[] batchParams = subList.stream()
                    .map(item -> new MapSqlParameterSource()
                            .addValue("effectiveDate", item.getEffectiveDate())
                            .addValue("duplicatedGroupId",
                                    item.getDuplicatedGroup() != null ? item.getDuplicatedGroup().getId() : null)
                            .addValue("fileId", item.getFile() != null ? item.getFile().getId() : null)
                            .addValue("priceAfter", item.getPriceAfter())
                            .addValue("priceBefore", item.getPriceBefore())
                            .addValue("rowNo", item.getRowNo())
                            .addValue("docId", item.getDocId())
                            .addValue("normalizedItemName", item.getNormalizedItemName())
                            .addValue("rawItemName", item.getRawItemName())
                            .addValue("spec", item.getSpec())
                            .addValue("supplierName", item.getSupplierName())
                            .addValue("unit", item.getUnit())
                            .addValue("reviewStatus",
                                    item.getReviewStatus() != null ? item.getReviewStatus().name() : null)
                            .addValue("sourceType", item.getSourceType() != null ? item.getSourceType().name() : null)
                    )
                    .toArray(SqlParameterSource[]::new);

            KeyHolder keyHolder = new GeneratedKeyHolder();

            // Batch Insert 실행 및 생성된 Keys 수집
            jdbcTemplate.batchUpdate(sql, batchParams, keyHolder);

            // DB에서 채번된 AUTO_INCREMENT ID를 엔티티 객체에 매핑
            List<Map<String, Object>> keyList = keyHolder.getKeyList();
            for (int j = 0; j < subList.size(); j++) {
                Map<String, Object> keyMap = keyList.get(j);
                // MySQL 드라이버에 따라 "GENERATED_KEY" 또는 컬럼명("id")으로 넘어옴
                Object generatedKey = keyMap.get("GENERATED_KEY");
                if (generatedKey == null) {
                    generatedKey = keyMap.get("id");
                }

                if (generatedKey instanceof Number numberKey) {
                    subList.get(j).assignGeneratedId(numberKey.longValue());
                }
            }
        }
    }
}
