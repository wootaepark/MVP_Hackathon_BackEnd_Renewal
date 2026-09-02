package com.bosalpim.compozi_ai.domain.document.component.validator;

import com.bosalpim.compozi_ai.domain.document.component.mapper.ItemNameMapper;
import com.bosalpim.compozi_ai.domain.document.dto.request.commonFile.CreateCommonItemDocumentReqDto;
import com.bosalpim.compozi_ai.domain.document.dto.request.manualFile.CheckDuplicatedManualItemDto;
import com.bosalpim.compozi_ai.domain.document.dto.request.manualFile.CreateManualItemDocumentReqDto;
import com.bosalpim.compozi_ai.domain.document.entity.Item;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ItemDocumentDuplicateValidator {

    private final ItemNameMapper itemNameMapper;


    // --- [ Common 파일 중복 검사 ] ---
    public DuplicateValidationResult markDuplicatesForCommon(List<CreateCommonItemDocumentReqDto> dtos,
                                                             List<Item> allExistingItems) {
        if (dtos.isEmpty()) {
            return new DuplicateValidationResult(Map.of(), List.of());
        }

        // 1. DTO 품목명 정규화 처리 (선행 조건)
        normalizeItemNames(dtos);

        // 2. 입력 파일 내부 (DTO 간) 중복 탐지 및 GroupKey 부여
        markFileSelfDuplicates(dtos);

        // 3. DB 기존 데이터 매핑 및 DB 데이터와의 중복 탐지
        Map<String, Item> existingDbMap = markDbDuplicates(dtos, allExistingItems);

        return new DuplicateValidationResult(existingDbMap, dtos);
    }

    private void normalizeItemNames(List<CreateCommonItemDocumentReqDto> dtos) {
        for (CreateCommonItemDocumentReqDto dto : dtos) {
            String normalizedName = itemNameMapper.map(dto.getRawItemName());
            dto.setNormalizedItemName(normalizedName);
        }
    }

    private void markFileSelfDuplicates(List<CreateCommonItemDocumentReqDto> dtos) {

        Map<CreateCommonItemDocumentReqDto, CreateCommonItemDocumentReqDto> firstSeenMap = new HashMap<>(135_000);

        for (CreateCommonItemDocumentReqDto dto : dtos) {
            CreateCommonItemDocumentReqDto firstSeenDto = firstSeenMap.get(dto);

            if (firstSeenDto == null) {
                // 최초 등장한 DTO는 Map에 등록
                firstSeenMap.put(dto, dto);
            } else {
                // 이미 등장했던 DTO (중복)
                // 필요 시점에만 String Key를 1회 생성하여 공유
                if (firstSeenDto.getDuplicateGroupKey() == null) {
                    String groupKey = generateKeyFromDto(firstSeenDto);
                    firstSeenDto.setDuplicateGroupKey(groupKey);
                }
                // 후속 중복 DTO에도 동일한 GroupKey 할당
                dto.setDuplicateGroupKey(firstSeenDto.getDuplicateGroupKey());
            }
        }
    }


    private Map<String, Item> markDbDuplicates(List<CreateCommonItemDocumentReqDto> dtos,
                                               List<Item> allExistingItems) {
        if (allExistingItems == null || allExistingItems.isEmpty()) {
            return Map.of();
        }

        // DB 기존 데이터 Map 생성 (Key: String, Value: Item)
        Map<String, Item> existingDbMap = new HashMap<>(allExistingItems.size());
        for (Item item : allExistingItems) {
            String dbKey = generateKeyFromItem(item);
            existingDbMap.putIfAbsent(dbKey, item); // 최초 ID 원본만 보관
        }

        // DTO 목록 중 DB에 이미 존재하는 데이터 체크
        for (CreateCommonItemDocumentReqDto dto : dtos) {
            // 자가 중복으로 이미 GroupKey가 발급된 경우 해당 Key 사용, 없으면 신규 생성하여 비교
            String dtoKey = (dto.getDuplicateGroupKey() != null)
                    ? dto.getDuplicateGroupKey()
                    : generateKeyFromDto(dto);

            if (existingDbMap.containsKey(dtoKey)) {
                dto.setDuplicateGroupKey(dtoKey);
            }
        }

        return existingDbMap;
    }

    private String generateKeyFromDto(CreateCommonItemDocumentReqDto dto) {
        return generateKey(
                dto.getSupplierName(),
                dto.getEffectiveItemName(),
                dto.getSpec() != null ? dto.getSpec().trim() : "",
                dto.getUnit() != null ? dto.getUnit().trim() : "",
                dto.getPriceBefore(),
                dto.getPriceAfter(),
                dto.getEffectiveDate()
        );
    }

    private String generateKeyFromItem(Item item) {
        return generateKey(
                item.getSupplierName(),
                getEffectiveItemName(item),
                item.getSpec(),
                item.getUnit(),
                item.getPriceBefore(),
                item.getPriceAfter(),
                item.getEffectiveDate()
        );
    }

    // -----------------------------

    // --- [ Manual 파일 중복 검사 ] ---
    public DuplicateValidationResult markDuplicatesForManual(List<CreateManualItemDocumentReqDto> dtos,
                                                             List<Item> allExistingItems) {
        if (dtos.isEmpty()) {
            return new DuplicateValidationResult(Map.of(), List.of());
        }

        // 1. DTO 변환 및 정규화 이름 매핑
        List<CheckDuplicatedManualItemDto> checkDtos = dtos.stream()
                .map(dto -> CheckDuplicatedManualItemDto.create(dto, itemNameMapper.map(dto.getRawItemName())))
                .toList();

        // 2. DB 전체 Item 조회

        Map<String, Item> existingDbMap = new HashMap<>();
        for (Item item : allExistingItems) {
            String key = generateKey(
                    item.getSupplierName(), getEffectiveItemName(item), item.getSpec(),
                    item.getUnit(), item.getPriceBefore(), item.getPriceAfter(), item.getEffectiveDate()
            );
            existingDbMap.putIfAbsent(key, item);
        }

        Map<String, CheckDuplicatedManualItemDto> firstSeenMap = new HashMap<>();

        for (CheckDuplicatedManualItemDto checkDto : checkDtos) {
            String key = generateKey(
                    checkDto.getSupplierName(), getEffectiveItemName(checkDto), checkDto.getSpec().trim(),
                    checkDto.getUnit().trim(), checkDto.getPriceBefore(), checkDto.getPriceAfter(),
                    checkDto.getEffectiveDate()
            );

            if (existingDbMap.containsKey(key) || firstSeenMap.containsKey(key)) {
                if (firstSeenMap.containsKey(key)) {
                    CheckDuplicatedManualItemDto firstDto = firstSeenMap.get(key);
                    if (firstDto.getDuplicateGroupKey() == null) {
                        firstDto.setDuplicateGroupKey(key);
                    }
                }
                checkDto.setDuplicateGroupKey(key);
            } else {
                firstSeenMap.put(key, checkDto);
            }
        }

        // second 파라미터로 처리된 checkDtos 반환
        return new DuplicateValidationResult(existingDbMap, checkDtos);
    }

    public String generateKey(Object... fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            sb.append(fields[i]);
            if (i < fields.length - 1) {
                sb.append("|");
            }
        }
        return sb.toString();
    }

    // 기존 db 저장 데이터의 rawName
    private String getEffectiveItemName(Item item) {
        if (item.getNormalizedItemName() == null) {
            return item.getRawItemName();
        }
        return item.getNormalizedItemName();
    }

    // 파일 입력 데이터의 rawName
    private String getEffectiveItemName(CreateCommonItemDocumentReqDto dto) {
        if (dto.getNormalizedItemName() == null) {
            return dto.getRawItemName();
        }
        return dto.getNormalizedItemName();
    }

    // 수기 입력 데이터의 rawName
    private String getEffectiveItemName(CheckDuplicatedManualItemDto dto) {
        if (dto.getNormalizedItemName() == null) {
            return dto.getRawItemName();
        }
        return dto.getNormalizedItemName();
    }

    // --- [ 반환용 DTO/Record ] ---
    public record DuplicateValidationResult(
            Map<String, Item> existingDbMap,
            Object firstSeenInRequestMap // DTO 리스트 또는 맵 형태로 유연하게 받기 위함
    ) {
    }
}
