package com.bosalpim.compozi_ai.domain.document.component.ocr;

import com.bosalpim.compozi_ai.domain.document.component.ocr.ParseOcrValueHelper.ParseContext;
import com.bosalpim.compozi_ai.domain.document.dto.request.commonFile.CreateCommonItemDocumentReqDto;
import com.bosalpim.compozi_ai.domain.document.enums.SourceType;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
@RequiredArgsConstructor
@Slf4j
public class OcrType1Parser implements OcrSubParser { // 44_ 형태 pdf 및 이미지 입력 (형태 1)

    private static final Pattern DOC_ID_PATTERN = Pattern.compile("(?:문서번호|거래명세서|No\\.)\\s*[:\\=\\-]?\\s*(\\S+)");
    private static final Pattern DATE_PATTERN = Pattern.compile("^\\d{4}[.-]\\d{2}[.-]\\d{2}$");

    // Type 1: [품목, 규격, 단위, 기존단가, 변경단가, 적용일자/적용일] 형태의 다양한 컬럼 조합 지원
    private static final Pattern TYPE1_HEADER_PATTERN =
            Pattern.compile("품\\s*목.*규\\s*격(?:\\s*\\([^)]*\\))?.*단\\s*위.*기존\\s*단가.*변경\\s*단가.*적용\\s*일(?:자)?");

    public static List<CreateCommonItemDocumentReqDto> parseToDtos(List<String> lines, ParseContext context,
                                                                   SourceType sourceType) {
        List<CreateCommonItemDocumentReqDto> resultList = new ArrayList<>();

        if (lines == null || lines.isEmpty()) {
            return resultList;
        }

        // 문서 상단에서 공통 정보 추출
        String supplierName = extractSupplierName(lines);
        log.info("[Type1] 공급 업체 명 : {}", supplierName);

        String docId = extractDocId(lines);
        log.info("[Type1] 문서 id : {}", docId);

        int tableHeaderEndIndex = findTableHeaderEndIndex(lines);
        log.info("[Type1] table 헤더 끝 인덱스 : {}", tableHeaderEndIndex);

        if (tableHeaderEndIndex == -1) {
            return resultList;
        }

        List<String> tableTokens = new ArrayList<>();
        // 표 헤더 종료 바로 다음 줄부터 데이터 행 파싱 시작
        for (int i = tableHeaderEndIndex + 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();

            // 표 종료 조건 (하단 비고, 안내 문구, 서명 등)
            if (line.startsWith("◯") || line.startsWith("앞으로도") || line.contains("대표이사") || line.startsWith("※")
                    || line.startsWith("·")) {
                break;
            }

            if (line.isBlank()) {
                continue;
            }

            String[] parts = line.split("\\s+");
            for (String part : parts) {
                if (!part.isBlank()) {
                    tableTokens.add(part);
                }
            }
        }

        long rowNo = 1;
        int subIndex = 1;
        int idx = 0;

        while (idx < tableTokens.size()) {
            List<String> rowTokens = new ArrayList<>();

            // 1개의 Row 단위 수집
            while (idx < tableTokens.size()) {
                String token = tableTokens.get(idx++);
                rowTokens.add(token);

                // 조건 A: YYYY-MM-DD 날짜 패턴을 만나면 행 끝으로 판단
                if (DATE_PATTERN.matcher(token).matches()) {
                    break;
                }

                // 조건 B: 날짜 정보가 포함되어 있지 않은 표 형태 방어 (최소 항목 수집 후 다음 토큰 판단)
                if (rowTokens.size() >= 5 && idx < tableTokens.size()) {
                    // 수집된 토큰의 마지막이 금액 형태이고 다음 토큰이 날짜가 아니며 품목명처럼 보이는 경우
                    if (!DATE_PATTERN.matcher(tableTokens.get(idx)).matches() && tableTokens.get(idx).length() > 1
                            && !tableTokens.get(idx).matches("^[0-9,]+원?$")) {
                        // 기존 문서 호환성을 위해 유연하게 자름
                    }
                }
            }

            // 역방향 구조 분석 (오른쪽 필드부터 채우기 -> 규격 내 공백 문제 완벽 해결)
            if (rowTokens.size() >= 4) {
                int tokenSize = rowTokens.size();
                int rightIdx = tokenSize - 1;

                // 1. 적용일자 (마지막 토큰이 날짜 형태인지 확인)
                String effectiveDateStr = null;
                if (DATE_PATTERN.matcher(rowTokens.get(rightIdx)).matches()) {
                    effectiveDateStr = rowTokens.get(rightIdx--);
                }

                // 2. 변경단가
                String priceAfterStr = rightIdx >= 0 ? rowTokens.get(rightIdx--) : "0";

                // 3. 기존단가
                String priceBeforeStr = rightIdx >= 0 ? rowTokens.get(rightIdx--) : "0";

                // 4. 단위
                String unit = rightIdx >= 0 ? rowTokens.get(rightIdx--) : "";

                // 5. 품목명 (첫 번째 토큰)
                String rawItemName = rowTokens.get(0);

                // 6. 규격 (품목명 다음부터 단위 직전까지 남은 중간 토큰들을 하나로 결합)
                StringBuilder specBuilder = new StringBuilder();
                for (int i = 1; i <= rightIdx; i++) {
                    if (specBuilder.length() > 0) {
                        specBuilder.append(" ");
                    }
                    specBuilder.append(rowTokens.get(i));
                }
                String rawSpec = specBuilder.toString();

                String formattedDocId = String.format("%s-%03d", docId, subIndex++);

                log.info("[Type1] Row #{}: item={}, spec={}, unit={}, priceBefore={}, priceAfter={}, date={}",
                        rowNo, rawItemName, rawSpec, unit, priceBeforeStr, priceAfterStr, effectiveDateStr);

                CreateCommonItemDocumentReqDto dto = CreateCommonItemDocumentReqDto.builder()
                        .rowNo(rowNo++)
                        .docId(formattedDocId)
                        .sourceType(sourceType.name())
                        .supplierName(supplierName)
                        .rawItemName(rawItemName)
                        .spec(rawSpec)
                        .unit(unit)
                        .priceBefore(ParseOcrValueHelper.parseLong(priceBeforeStr, context))
                        .priceAfter(ParseOcrValueHelper.parseLong(priceAfterStr, context))
                        .effectiveDate(
                                effectiveDateStr != null ? ParseOcrValueHelper.parseDate(effectiveDateStr, context)
                                        : null)
                        .hasParseError(context.hasError())
                        .build();

                resultList.add(dto);
            }
        }

        return resultList;
    }

    private static String extractDocId(List<String> lines) {
        for (String line : lines) {
            Matcher matcher = DOC_ID_PATTERN.matcher(line);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return "UNKNOWN_DOC_ID";
    }

    // 문서 상단 키워드를 건너뛰는 기존 호환성 보장 추출 로직
    private static String extractSupplierName(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return "UNKNOWN";
        }

        for (String line : lines) {
            String cleaned = line.replaceAll("\\s+", "");
            if (!cleaned.isBlank()
                    && !cleaned.contains("문서번호")
                    && !cleaned.contains("발신일자")
                    && !cleaned.contains("No.")) {
                return cleaned;
            }
        }

        return "UNKNOWN";
    }

    // 기존/신규 문서 모든 헤더 형태 탐색 지원
    private static int findTableHeaderEndIndex(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            String cleaned = lines.get(i).replaceAll("\\s+", "");

            // 한 줄에 통째로 있는 경우
            if (cleaned.contains("품목") && (cleaned.contains("적용일") || cleaned.contains("변경단가") || cleaned.contains(
                    "적용일자"))) {
                return i;
            }

            // 개별 라인으로 헤더 컬럼이 분리되어 들어오는 경우
            if (cleaned.equals("적용일자") || cleaned.equals("적용일") || cleaned.endsWith("적용일") || cleaned.endsWith(
                    "적용일자")) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public boolean supports(List<String> ocrLines) {
        if (ocrLines == null || ocrLines.isEmpty()) {
            return false;
        }
        String fullText = String.join(" ", ocrLines);
        return TYPE1_HEADER_PATTERN.matcher(fullText).find();
    }

    @Override
    public List<CreateCommonItemDocumentReqDto> parse(List<String> ocrLines, MultipartFile file, ParseContext context,
                                                      SourceType sourceType) {
        return parseToDtos(ocrLines, context, sourceType);
    }
}
