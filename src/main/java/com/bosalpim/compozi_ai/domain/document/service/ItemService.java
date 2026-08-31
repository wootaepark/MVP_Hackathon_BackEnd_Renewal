package com.bosalpim.compozi_ai.domain.document.service;

import com.bosalpim.compozi_ai.domain.document.component.validator.ItemDocumentDuplicateValidator;
import com.bosalpim.compozi_ai.domain.document.component.validator.ItemDocumentDuplicateValidator.DuplicateValidationResult;
import com.bosalpim.compozi_ai.domain.document.component.validator.ItemSpecAndUnitValidator;
import com.bosalpim.compozi_ai.domain.document.dto.request.commonFile.CreateCommonItemDocumentReqDto;
import com.bosalpim.compozi_ai.domain.document.dto.request.manualFile.CheckDuplicatedManualItemDto;
import com.bosalpim.compozi_ai.domain.document.dto.request.manualFile.CreateManualItemDocumentListReqDto;
import com.bosalpim.compozi_ai.domain.document.dto.request.manualFile.CreateManualItemDocumentReqDto;
import com.bosalpim.compozi_ai.domain.document.entity.File;
import com.bosalpim.compozi_ai.domain.document.entity.Item;
import com.bosalpim.compozi_ai.domain.document.enums.ReviewStatus;
import com.bosalpim.compozi_ai.domain.document.repository.item.IssueBulkRepository;
import com.bosalpim.compozi_ai.domain.document.repository.item.ItemBulkRepository;
import com.bosalpim.compozi_ai.domain.document.repository.item.ItemRepository;
import com.bosalpim.compozi_ai.domain.inbox.entity.DuplicatedGroup;
import com.bosalpim.compozi_ai.domain.inbox.entity.Issue;
import com.bosalpim.compozi_ai.domain.inbox.enums.IssueType;
import com.bosalpim.compozi_ai.domain.inbox.repository.DuplicatedGroupRepository;
import com.bosalpim.compozi_ai.domain.inbox.repository.issue.IssueRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ItemService {

    private final ItemRepository itemRepository;
    private final DuplicatedGroupRepository duplicatedGroupRepository;
    private final IssueRepository issueRepository;
    private final ItemDocumentDuplicateValidator itemDocumentDuplicateValidator;
    private final ItemSpecAndUnitValidator itemSpecAndUnitValidator;
    private final Validator validator;
    private final ItemBulkRepository itemBulkRepository;
    private final IssueBulkRepository issueBulkRepository;

    @Transactional
    // 아이템 이상 탐지 및 저장 서비스 메서드
    public List<Item> createCommonItem(List<CreateCommonItemDocumentReqDto> reqDtos, File savedFile) {

        log.info(">>>> createCommonItem에 전달된 File ID: {}", savedFile.getId());
        // 1. 검증 및 중복 매핑 결과 취득
        DuplicateValidationResult validationResult = itemDocumentDuplicateValidator.markDuplicatesForCommon(
                reqDtos, itemRepository.findAllByDeletedAtIsNullOrderByIdAsc()
        );

        // DB 업데이트 대상 및 신규 그룹 처리를 위한 변수 선언
        List<Item> existingItemsToUpdate = new ArrayList<>();
        List<Boolean> isDuplicateFlags = new ArrayList<>(); // 각 항목별 실질적 중복 여부 저장

        // 2. DTO -> Item 엔티티 및 DuplicatedGroup 연관관계 구성
        List<Item> itemsToSave = processItemsAndGroups(
                reqDtos, validationResult, savedFile, existingItemsToUpdate, isDuplicateFlags
        );
        // 3. 기타 이상 탐지 및 이슈(Issue) 수집
        List<Issue> issues = detectIssues(itemsToSave, reqDtos, isDuplicateFlags);

        // 4. 데이터 일괄 저장 (Item, Issue, Group 등)
        return saveAllEntities(itemsToSave, existingItemsToUpdate, issues);
    }

    @Transactional
    public List<Item> createManualItem(CreateManualItemDocumentListReqDto reqDtos, List<File> savedFiles) {

        // 1. 검증 및 중복 매핑 결과 취득
        List<CreateManualItemDocumentReqDto> itemDtos = reqDtos.getItems();
        DuplicateValidationResult validationResult = itemDocumentDuplicateValidator.markDuplicatesForManual(
                itemDtos, itemRepository.findAllByDeletedAtIsNullOrderByIdAsc()
        );

        // DB 업데이트 대상 및 신규 그룹 처리를 위한 변수 선언
        List<Item> existingItemsToUpdate = new ArrayList<>();
        List<Boolean> isDuplicateFlags = new ArrayList<>(); // 각 항목별 실질적 중복 여부 저장

        // 2. DTO -> Item 엔티티 및 DuplicatedGroup 연관관계 구성
        List<Item> itemsToSave = processManualItemsAndGroups(
                itemDtos, validationResult, savedFiles, existingItemsToUpdate, isDuplicateFlags
        );

        // 3. 기타 이상 탐지 및 이슈(Issue) 수집
        List<Issue> issues = detectManualIssues(itemsToSave, itemDtos, isDuplicateFlags);

        // 4. 데이터 일괄 저장 (Item, Issue, Group 등)
        return saveAllEntities(itemsToSave, existingItemsToUpdate, issues);
    }

    // ----- 아래는 ItemService 전용 private 메서드 -------

    private List<Item> processManualItemsAndGroups(
            List<CreateManualItemDocumentReqDto> reqDtos,
            DuplicateValidationResult validationResult,
            List<File> savedFiles,
            List<Item> existingItemsToUpdate,
            List<Boolean> isDuplicateFlags
    ) {
        Map<String, DuplicatedGroup> groupMap = new HashMap<>();
        List<Item> items = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();

        @SuppressWarnings("unchecked")
        List<CheckDuplicatedManualItemDto> checkedDtos =
                (List<CheckDuplicatedManualItemDto>) validationResult.firstSeenInRequestMap();

        for (int i = 0; i < reqDtos.size(); i++) {
            CreateManualItemDocumentReqDto dto = reqDtos.get(i);
            CheckDuplicatedManualItemDto checkedDto = checkedDtos.get(i);

            String groupKey = checkedDto.getDuplicateGroupKey();
            DuplicatedGroup group = resolveDuplicatedGroup(
                    groupKey, groupMap, validationResult.existingDbMap(), existingItemsToUpdate
            );

            boolean isDuplicated = (groupKey != null) &&
                    (validationResult.existingDbMap().containsKey(groupKey) || seenKeys.contains(groupKey));

            if (groupKey != null) {
                seenKeys.add(groupKey);
            }
            isDuplicateFlags.add(isDuplicated);

            Set<ConstraintViolation<CreateManualItemDocumentReqDto>> violations = validator.validate(dto);
            boolean hasMissingFieldOrDataLacking =
                    !violations.isEmpty() || (checkedDto.getNormalizedItemName() == null);

            ReviewStatus reviewStatus = determineReviewStatusV2(
                    dto.getSpec(), dto.getUnit(), isDuplicated, hasMissingFieldOrDataLacking
            );

            // DTO -> Item 변환 (Manual 생성자 호출)
            Item item = Item.CreateManualItem(
                    dto,
                    savedFiles.get(i),
                    checkedDto.getNormalizedItemName(),
                    group,
                    reviewStatus
            );
            items.add(item);
        }

        return items;
    }

    private List<Issue> detectManualIssues(
            List<Item> itemsToSave,
            List<CreateManualItemDocumentReqDto> reqDtos,
            List<Boolean> isDuplicateFlags
    ) {
        List<Issue> issues = new ArrayList<>();

        for (int i = 0; i < itemsToSave.size(); i++) {
            Item item = itemsToSave.get(i);
            CreateManualItemDocumentReqDto dto = reqDtos.get(i);
            boolean isDuplicated = isDuplicateFlags.get(i);

            // 1. 규격(Spec) 불일치 검사
            if (itemSpecAndUnitValidator.isSpecMismatch(dto.getSpec())) {
                issues.add(Issue.create(IssueType.SPEC_MISMATCH, "규격 불일치", false, item));
            }

            // 2. 단위(Unit) 불일치 검사
            if (itemSpecAndUnitValidator.isUnitMismatch(dto.getUnit())) {
                issues.add(Issue.create(IssueType.UNIT_MISMATCH, "단위 불일치", false, item));
            }

            // 3. Jakarta Validator를 통한 필수값 누락 및 정규화 이름 누락 검사
            Set<ConstraintViolation<CreateManualItemDocumentReqDto>> violations = validator.validate(dto);
            boolean hasMissingField = !violations.isEmpty();
            boolean isDataLacking = (item.getNormalizedItemName() == null);

            if (isDataLacking || hasMissingField) {
                issues.add(Issue.create(IssueType.MISSING_REQUIRED, "필수값 누락", false, item));
            }

            // 4. 중복 의심 이슈 등록
            if (isDuplicated) {
                issues.add(Issue.create(IssueType.DUPLICATE_SUSPECTED, "중복 의심", false, item));
            }
        }

        return issues;
    }

    private List<Item> processItemsAndGroups(
            List<CreateCommonItemDocumentReqDto> reqDtos,
            DuplicateValidationResult validationResult,
            File savedFile,
            List<Item> existingItemsToUpdate, // DB 업데이트 대상 수집용,
            List<Boolean> isDuplicateFlags
    ) {
        // key별로 생성된 중복그룹을 재사용하기 위한 Map
        Map<String, DuplicatedGroup> groupMap = new HashMap<>();
        List<Item> items = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();

        for (CreateCommonItemDocumentReqDto dto : reqDtos) {
            String groupKey = dto.getDuplicateGroupKey();
            DuplicatedGroup group = resolveDuplicatedGroup(groupKey, groupMap, validationResult.existingDbMap(),
                    existingItemsToUpdate);

            boolean isDuplicated = (groupKey != null) &&
                    (validationResult.existingDbMap().containsKey(groupKey) || seenKeys.contains(groupKey));

            if (groupKey != null) {
                seenKeys.add(groupKey);
            }
            isDuplicateFlags.add(isDuplicated); // detectIssues에서 재활용하기 위해 기록

            Set<ConstraintViolation<CreateCommonItemDocumentReqDto>> violations = validator.validate(dto);
            boolean hasMissingFieldOrDataLacking = !violations.isEmpty() || (dto.getNormalizedItemName() == null);

            ReviewStatus reviewStatus = determineReviewStatusV2(dto.getSpec(), dto.getUnit(), isDuplicated,
                    hasMissingFieldOrDataLacking);

            // DTO -> Item 변환 (이때 그룹 할당)
            Item item = Item.CreateCommonItem(dto, savedFile, group, reviewStatus);
            items.add(item);
        }

        return items;
    }


    private DuplicatedGroup resolveDuplicatedGroup(String groupKey, Map<String, DuplicatedGroup> groupMap,
                                                   Map<String, Item> existingDbMap, List<Item> existingItemsToUpdate) {

        if (groupKey == null) {
            return null;
        }
        return groupMap.computeIfAbsent(groupKey, key -> {
            Item originalDbItem = existingDbMap.get(key);

            if (originalDbItem != null) {
                // Case 1: 기존 DB 항목에 이미 중복 그룹이 존재하는 경우
                if (originalDbItem.getDuplicatedGroup() != null) {
                    return originalDbItem.getDuplicatedGroup();
                }

                // Case 2: DB 항목은 존재하지만 아직 중복 그룹이 없는 경우 (새 그룹 생성 후 DB 항목 업데이트 대상 추가)
                DuplicatedGroup newGroup = DuplicatedGroup.create();
                originalDbItem.updateDuplicatedGroup(newGroup);
                existingItemsToUpdate.add(originalDbItem);
                return newGroup;
            }

            // Case 3: DB 항목 없이 요청 DTO 간 내부 중복인 경우
            return DuplicatedGroup.create();
        });

    }

    private List<Issue> detectIssues(List<Item> itemsToSave, List<CreateCommonItemDocumentReqDto> reqDtos,
                                     List<Boolean> isDuplicateFlags) {
        List<Issue> issues = new ArrayList<>();

        for (int i = 0; i < itemsToSave.size(); i++) {
            Item item = itemsToSave.get(i);
            CreateCommonItemDocumentReqDto dto = reqDtos.get(i);
            boolean isDuplicated = isDuplicateFlags.get(i);

            // 1. 규격(Spec) 불일치 검사
            if (itemSpecAndUnitValidator.isSpecMismatch(dto.getSpec())) {
                issues.add(Issue.create(IssueType.SPEC_MISMATCH, "규격 불일치", false, item));
            }

            // 2. 단위(Unit) 불일치 검사
            if (itemSpecAndUnitValidator.isUnitMismatch(dto.getUnit())) {
                issues.add(Issue.create(IssueType.UNIT_MISMATCH, "단위 불일치", false, item));
            }

            // 3. Jakarta Validator를 통한 필수값 누락 검사
            Set<ConstraintViolation<CreateCommonItemDocumentReqDto>> violations = validator.validate(dto);
            boolean hasMissingField = !violations.isEmpty();
            boolean isDataLacking = (dto.getNormalizedItemName() == null);

            if (isDataLacking || hasMissingField) {
                issues.add(Issue.create(IssueType.MISSING_REQUIRED, "필수값 누락", false, item));
            }

            // 4. 중복 의심 이슈 (그룹 연결 유무가 아닌, '후속 중복' 항목일 때만 등록)
            if (isDuplicated) {
                issues.add(Issue.create(IssueType.DUPLICATE_SUSPECTED, "중복 의심", false, item));
            }
        }

        return issues;
    }

    private List<Item> saveAllEntities(
            List<Item> itemsToSave,
            List<Item> existingItemsToUpdate,
            List<Issue> issues
    ) {
        // 1. 신규 Item 들에 연관된 DuplicatedGroup 중 아직 DB에 저장되지 않은(id가 null인) 그룹만 추출하여 우선 저장
        List<DuplicatedGroup> newGroupsToSave = itemsToSave.stream()
                .map(Item::getDuplicatedGroup)
                .filter(group -> group != null && group.getId() == null)
                .distinct()
                .toList();

        if (!newGroupsToSave.isEmpty()) {
            duplicatedGroupRepository.saveAll(newGroupsToSave); // 부모(Group) 먼저 저장 -> ID 생성
        }

        // 2. 새로 생성된 중복 그룹이 할당된 기존 DB Item들 업데이트
//        if (!existingItemsToUpdate.isEmpty()) {
//            itemRepository.saveAll(existingItemsToUpdate);
//        }
        // 변경 감지(dirty checking ) 때문에 없애도 된다.

        // 3. 신규 Item 저장 (부모의 ID가 채워진 상태이므로 FK 정상 매핑)
        itemBulkRepository.saveAllItemsInBatch(itemsToSave, 5000);

        // 4. 수집된 Issue 저장
        if (!issues.isEmpty()) {
            issueBulkRepository.saveAllIssuesInBatch(issues, 5000);
        }

        return itemsToSave;
    }

    private ReviewStatus determineReviewStatusV2(
            String spec,
            String unit,
            boolean isDuplicated,
            boolean hasMissingFieldOrDataLacking
    ) {

        // 1순위. 필수값 누락 또는 필수 데이터 부족 -> NEEDS_REVIEW

        if (hasMissingFieldOrDataLacking) {
            return ReviewStatus.NEEDS_REVIEW;
        }
        // 2순위. 규격/단위 불일치 및 중복 조건 -> ON_HOLD
        boolean hasSpecOrUnitIssue = itemSpecAndUnitValidator.isSpecMismatch(spec)
                || itemSpecAndUnitValidator.isUnitMismatch(unit);

        if (hasSpecOrUnitIssue || isDuplicated) {
            return ReviewStatus.ON_HOLD;
        }

        // 3. 이상 없음 -> NEW
        return ReviewStatus.NEW;
    }

    // ----------------------------------------------------- 이전 코드 => inbox 사용 중이기 때문에 수정은 x  ----------------------------------------


    public ReviewStatus determineReviewStatus(String spec, String unit, boolean isDuplicate) {
        boolean hasSpecOrUnitIssue = itemSpecAndUnitValidator.isSpecMismatch(spec)
                || itemSpecAndUnitValidator.isUnitMismatch(unit);

        return (hasSpecOrUnitIssue || isDuplicate) ? ReviewStatus.ON_HOLD : ReviewStatus.NEW;
    }

    public void collectIssuesIfNeeded(Item item, String spec, String unit, Consumer<Issue> issueCollector,
                                      boolean hasMissingField, boolean isDataLacking) {
        if (itemSpecAndUnitValidator.isSpecMismatch(spec)) {
            issueCollector.accept(Issue.create(IssueType.SPEC_MISMATCH, "규격 불일치", false, item));
        }
        if (itemSpecAndUnitValidator.isUnitMismatch(unit)) {
            issueCollector.accept(Issue.create(IssueType.UNIT_MISMATCH, "단위 불일치", false, item));
        }
        if (hasMissingField || isDataLacking) {
            issueCollector.accept(Issue.create(IssueType.MISSING_REQUIRED, "필수값 누락", false, item));
        }
    }


}
