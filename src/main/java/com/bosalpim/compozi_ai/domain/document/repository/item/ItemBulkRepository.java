package com.bosalpim.compozi_ai.domain.document.repository.item;

import com.bosalpim.compozi_ai.domain.document.entity.Item;
import java.sql.PreparedStatement;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class ItemBulkRepository {
    private final JdbcTemplate jdbcTemplate;

    @Transactional
    public void saveAllItemsInBatch(List<Item> items, int batchSize) {

        String sql =
                "INSERT INTO `items` (\n"
                        + "  `effective_date`, `duplicated_group_id`, `file_id`, `price_after`, `price_before`,\n"
                        + "  `row_no`, `doc_id`, `normalized_item_name`, `raw_item_name`, `spec`, \n"
                        + "  `supplier_name`, `unit`, `review_status`, `source_type`, `created_at`\n"
                        + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())";

        jdbcTemplate.batchUpdate(
                sql,
                items,
                batchSize,
                (PreparedStatement ps, Item item) -> {
                    ps.setObject(1, item.getEffectiveDate());
                    if (item.getDuplicatedGroup() != null) {
                        ps.setLong(2, item.getDuplicatedGroup().getId());
                    } else {
                        ps.setNull(2, java.sql.Types.BIGINT);
                    }
                    if (item.getFile() != null) {
                        ps.setLong(3, item.getFile().getId());

                    } else {
                        ps.setNull(3, java.sql.Types.BIGINT);
                    }
                    ps.setLong(4, item.getPriceAfter());
                    ps.setLong(5, item.getPriceBefore());
                    ps.setLong(6, item.getRowNo());
                    ps.setString(7, item.getDocId());
                    ps.setString(8, item.getNormalizedItemName());
                    ps.setString(9, item.getRawItemName());
                    ps.setString(10, item.getSpec());
                    ps.setString(11, item.getSupplierName());
                    ps.setString(12, item.getUnit());
                    ps.setString(13, item.getReviewStatus() != null ? item.getReviewStatus().name() : null);
                    ps.setString(14, item.getSourceType() != null ? item.getSourceType().name() : null);
                }

        );

    }


}
