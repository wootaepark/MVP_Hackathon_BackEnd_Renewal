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
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ItemService {

    private final ItemRepository itemRepository;
    private final DuplicatedGroupRepository duplicatedGroupRepository;
    private final IssueRepository issueRepository;
    private final ItemDocumentDuplicateValidator itemDocumentDuplicateValidator;
    private final ItemSpecAndUnitValidator itemSpecAndUnitValidator;
    private final Validator validator;

    @Transactional
    // 아이템 이상 탐지 및 저장 서비스 메서드
    public List<Item> createCommonItem(List<CreateCommonItemDocumentReqDto> reqDtos, File savedFile) {

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
        if (!existingItemsToUpdate.isEmpty()) {
            itemRepository.saveAll(existingItemsToUpdate);
        }

        // 3. 신규 Item 저장 (부모의 ID가 채워진 상태이므로 FK 정상 매핑)
        List<Item> savedItems = itemRepository.saveAll(itemsToSave);

        // 4. 수집된 Issue 저장
        if (!issues.isEmpty()) {
            issueRepository.saveAll(issues);
        }

        return savedItems;
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

    // ----------------------------------------------------- 이전 코드 ----------------------------------------

    @Transactional
    public List<Item> createManualItem(CreateManualItemDocumentListReqDto reqDtos, List<File> savedFiles) {
        List<CreateManualItemDocumentReqDto> itemDtos = reqDtos.getItems();
        DuplicateValidationResult validationResult = itemDocumentDuplicateValidator.markDuplicatesForManual(itemDtos,
                itemRepository.findAllByDeletedAtIsNullOrderByIdAsc());

        @SuppressWarnings("unchecked")
        List<CheckDuplicatedManualItemDto> checkedDtos = (List<CheckDuplicatedManualItemDto>) validationResult.firstSeenInRequestMap();

        return processAndSaveItemsWithDbCheck(
                itemDtos.size(),
                i -> checkedDtos.get(i).getDuplicateGroupKey(),
                validationResult.existingDbMap(),
                (i, group, issueCollector, isDuplicate) -> {
                    CreateManualItemDocumentReqDto itemDto = itemDtos.get(i);
                    CheckDuplicatedManualItemDto checkedDto = checkedDtos.get(i);

                    ReviewStatus reviewStatus = determineReviewStatus(itemDto.getSpec(), itemDto.getUnit(),
                            isDuplicate);

                    Set<ConstraintViolation<CreateManualItemDocumentReqDto>> violations = validator.validate(itemDto);
                    boolean hasMissingField = !violations.isEmpty();
                    boolean isDataLacking = checkedDto.getNormalizedItemName() == null;

                    if (isDataLacking || (reviewStatus.equals(ReviewStatus.NEW)) && hasMissingField) {
                        reviewStatus = ReviewStatus.NEEDS_REVIEW;
                    }

                    Item item = Item.CreateManualItem(
                            itemDto,
                            savedFiles.get(i),
                            checkedDto.getNormalizedItemName(),
                            group,
                            reviewStatus
                    );

                    collectIssuesIfNeeded(item, itemDto.getSpec(), itemDto.getUnit(), issueCollector, hasMissingField,
                            isDataLacking);

                    return item;
                }
        );
    }

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

    // --- [ 기존 그룹 병합 로직 수정 반영 메서드 ] ---
    private List<Item> processAndSaveItemsWithDbCheck(
            int size,
            Function<Integer, String> keyExtractor,
            Map<String, Item> existingDbMap,
            QuadFunction<Integer, DuplicatedGroup, Consumer<Issue>, Boolean, Item> itemMapper
    ) {
        Map<String, DuplicatedGroup> groupMap = new HashMap<>();
        Set<String> seenKeys = new HashSet<>();

        List<Item> itemsToSave = new ArrayList<>();
        List<Item> existingItemsToUpdate = new ArrayList<>();
        List<Issue> issues = new ArrayList<>();

        for (int i = 0; i < size; i++) {
            String duplicateKey = keyExtractor.apply(i);
            DuplicatedGroup group = null;

            if (duplicateKey != null) {

                // 기존 DB 항목이 이미 DuplicatedGroup을 가지고 있으면 해당 그룹을 재활용
                group = groupMap.computeIfAbsent(duplicateKey, key -> {
                    Item originalDbItem = existingDbMap.get(key);

                    if (originalDbItem != null) {
                        // 1. DB 항목에 이미 존재하는 중복 그룹이 있는 경우 -> 그 그룹 재사용
                        if (originalDbItem.getDuplicatedGroup() != null) {
                            return originalDbItem.getDuplicatedGroup();
                        }

                        // 2. DB 항목은 있지만 아직 중복 그룹이 없는 경우 -> 새 그룹 생성 및 DB 항목 업데이트
                        DuplicatedGroup newGroup = DuplicatedGroup.create();
                        originalDbItem.updateDuplicatedGroup(newGroup);
                        existingItemsToUpdate.add(originalDbItem);
                        return newGroup;
                    }

                    // 3. DB 항목도 없는 순수 요청 내 중복 -> 새 그룹 생성
                    return DuplicatedGroup.create();
                });
            }

            boolean isDuplicate = (duplicateKey != null) &&
                    (existingDbMap.containsKey(duplicateKey) || seenKeys.contains(duplicateKey));

            Item item = itemMapper.apply(i, group, issues::add, isDuplicate);
            itemsToSave.add(item);

            if (duplicateKey != null) {
                if (isDuplicate) {
                    issues.add(Issue.create(IssueType.DUPLICATE_SUSPECTED, "중복 의심", false, item));
                } else {
                    seenKeys.add(duplicateKey);
                }
            }
        }

        // 1. 신규 생성된 DuplicatedGroup 중 영속화되지 않은(id가 null인) 그룹들만 필터링하여 저장
        List<DuplicatedGroup> newGroupsToSave = groupMap.values().stream()
                .filter(g -> g.getId() == null)
                .toList();

        if (!newGroupsToSave.isEmpty()) {
            duplicatedGroupRepository.saveAll(newGroupsToSave);
        }

        // 2. 그룹이 새로 할당된 기존 DB 데이터 업데이트
        if (!existingItemsToUpdate.isEmpty()) {
            itemRepository.saveAll(existingItemsToUpdate);
        }

        // 3. 신규 Item 일괄 저장
        List<Item> savedItems = itemRepository.saveAll(itemsToSave);

        // 4. 중복 이슈 저장
        if (!issues.isEmpty()) {
            issueRepository.saveAll(issues);
        }

        return savedItems;
    }

    @FunctionalInterface
    public interface QuadFunction<T, U, V, W, R> {
        R apply(T t, U u, V v, W w);
    }
}
