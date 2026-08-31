package com.bosalpim.compozi_ai.domain.document.repository.item;

import com.bosalpim.compozi_ai.domain.inbox.entity.Issue;
import java.sql.PreparedStatement;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class IssueBulkRepository {
    private final JdbcTemplate jdbcTemplate;

    @Transactional
    public void saveAllIssuesInBatch(List<Issue> issues, int batchSize) {
        if (issues == null || issues.isEmpty()) {
            return;
        }

        // 테이블명: issues (BaseTimeStampEntity 필드인 created_at, updated_at에 NOW() 할당)
        String sql =
                "INSERT INTO `issues` (`issue_type`, `detail`, `resolved`, `item_id`, `created_at`) "
                        + "VALUES (?, ?, ?, ?, NOW())";

        jdbcTemplate.batchUpdate(
                sql,
                issues,
                batchSize,
                (PreparedStatement ps, Issue issue) -> {
                    // 1. issueType (Enum -> String)
                    ps.setString(1, issue.getIssueType() != null ? issue.getIssueType().name() : null);

                    // 2. detail
                    ps.setString(2, issue.getDetail());

                    // 3. resolved (Boolean -> Boolean/Bit/TinyInt 지원)
                    ps.setObject(3, issue.getResolved());

                    // 4. item_id (FK - null 체크 필수)
                    if (issue.getItem() != null && issue.getItem().getId() != null) {
                        ps.setLong(4, issue.getItem().getId());
                    } else {
                        ps.setNull(4, java.sql.Types.BIGINT);
                    }

                }
        );
    }
}
