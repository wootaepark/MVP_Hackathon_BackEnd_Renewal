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
public class OcrType2Parser implements OcrSubParser { // Type 2 전용 파서

    private static final Pattern DOC_ID_PATTERN = Pattern.compile("(?:문서번호|No\\.)\\s*[:\\=\\-]?\\s*(\\S+)");
    private static final Pattern EFFECTIVE_DATE_PATTERN = Pattern.compile(
            "시행일\\s*(\\d{4}년\\s*\\d{1,2}월\\s*\\d{1,2}일|\\d{4}[.-]\\d{2}[.-]\\d{2})");

    // Type 2: [번호, 품목명, 규격, 단위, 기존단가, 변경단가, 인상률] 형태의 컬럼 순서
    private static final Pattern TYPE2_HEADER_PATTERN =
            Pattern.compile("(번호|No).*품목명.*규\\s*격.*단\\s*위.*기존\\s*단가.*변경\\s*단가.*인상률");

    public static List<CreateCommonItemDocumentReqDto> parseToDtos(List<String> lines, ParseContext context,
                                                                   SourceType sourceType) {
        List<CreateCommonItemDocumentReqDto> resultList = new ArrayList<>();

        if (lines == null || lines.isEmpty()) {
            return resultList;
        }

        // 문서 상단 정보 추출
        String supplierName = extractSupplierName(lines);
        String docId = extractDocId(lines);
        String globalEffectiveDate = extractEffectiveDate(lines);

        log.info("[Type2] 공급업체: {}, 문서ID: {}, 공통 시행일: {}", supplierName, docId, globalEffectiveDate);

        int tableHeaderEndIndex = findTableHeaderEndIndex(lines);
        if (tableHeaderEndIndex == -1) {
            log.info("종료 1");
            return resultList;
        }

        List<String> tableTokens = new ArrayList<>();
        for (int i = tableHeaderEndIndex + 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();

            // 표 종료 조건 (비고 문구 ※ 또는 발신 재등장, 하단 안내문구 시)
            if (line.startsWith("※") || line.startsWith("발신:") || line.contains("부가가치세")) {
                log.info("종료 2");
                break;
            }

            if (line.isBlank()) {
                log.info("종료 3");
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

        // Type 2 행 파싱
        while (idx < tableTokens.size()) {

            // 맨 앞 토큰이 순수 숫자(번호)이면 스킵하고,
            // 번호 1이 안 읽혀서 품목명이 먼저 나와도 스킵 없이 진행
            if (tableTokens.get(idx).matches("^\\d+$")) {
                idx++;
            }

            List<String> rowTokens = new ArrayList<>();
            while (idx < tableTokens.size()) {
                String token = tableTokens.get(idx);

                // 인상률(%로 끝나는 토큰)을 만나면 해당 행 파싱 종료
                if (token.endsWith("%")) {
                    idx++; // % 토큰 소비 후 탈출
                    break;
                }

                // 다음 번호 토큰을 예기치 않게 만난 경우 방어 처리 (5개 이상 수집 시)
                if (token.matches("^\\d+$") && rowTokens.size() >= 5) {
                    break;
                }

                rowTokens.add(token);
                idx++;
            }

            if (rowTokens.size() >= 5) {
                int tokenSize = rowTokens.size();

                // 1. 역방향 탐색으로 변경단가, 기존단가, 단위 동적 매칭
                String priceAfterStr = null;
                String priceBeforeStr = null;
                String unit = null;

                int rightIdx = tokenSize - 1;

                // (1) priceAfter (변경단가) 탐색
                priceAfterStr = rowTokens.get(rightIdx--);

                // (2) priceBefore (기존단가) 탐색
                if (rightIdx >= 0) {
                    String candidate = rowTokens.get(rightIdx);
                    if (candidate.matches(".*\\d+.*")) {
                        priceBeforeStr = candidate;
                        rightIdx--;
                    } else {
                        priceBeforeStr = candidate;
                        rightIdx--;

                        if (rightIdx >= 0 && rowTokens.get(rightIdx).matches(".*\\d+.*")) {
                            priceBeforeStr = rowTokens.get(rightIdx);
                            rightIdx--;
                        }
                    }
                }

                // (3) unit (단위) 탐색
                if (rightIdx >= 0) {
                    unit = rowTokens.get(rightIdx--);
                }

                // 2. Spec(규격) 시작 위치 탐색
                int specStartIndex = 1;
                for (int i = 1; i <= rightIdx; i++) {
                    if (rowTokens.get(i).matches(".*\\d+.*")) {
                        specStartIndex = i;
                        break;
                    }
                }

                // 3. [품목명] 조합
                StringBuilder itemParts = new StringBuilder();
                for (int i = 0; i < specStartIndex; i++) {
                    if (itemParts.length() > 0) {
                        itemParts.append(" ");
                    }
                    itemParts.append(rowTokens.get(i));
                }
                String rawItemName = itemParts.toString();

                // 4. [규격] 조합
                StringBuilder specParts = new StringBuilder();
                for (int i = specStartIndex; i <= rightIdx; i++) {
                    if (specParts.length() > 0) {
                        specParts.append(" ");
                    }
                    specParts.append(rowTokens.get(i));
                }
                String rawSpec = specParts.toString();

                String formattedDocId = String.format("%s-%03d", docId, subIndex++);

                log.info("[Type2] Row #{}: item={}, spec={}, unit={}, priceBefore={}, priceAfter={}, date={}",
                        rowNo, rawItemName, rawSpec, unit, priceBeforeStr, priceAfterStr, globalEffectiveDate);

                CreateCommonItemDocumentReqDto dto = CreateCommonItemDocumentReqDto.builder()
                        .rowNo(rowNo++)
                        .docId(formattedDocId)
                        .sourceType(sourceType.name())
                        .supplierName(supplierName)
                        .rawItemName(rawItemName)
                        .spec(rawSpec)
                        .unit(unit)
                        .priceBefore(parseSafeLong(priceBeforeStr, context))
                        .priceAfter(parseSafeLong(priceAfterStr, context))
                        .effectiveDate(globalEffectiveDate != null ? ParseOcrValueHelper.parseDate(globalEffectiveDate,
                                context) : null)
                        .hasParseError(context.hasError())
                        .build();

                resultList.add(dto);
            }
        }

        return resultList;
    }

    private static String extractDocId(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.contains("문서번호") && i + 1 < lines.size()) {
                String nextLine = lines.get(i + 1).trim();
                if (nextLine.matches("^[A-Z0-9-]+$")) {
                    return nextLine;
                }
            }
            Matcher matcher = DOC_ID_PATTERN.matcher(line);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return "UNKNOWN_DOC_ID";
    }

    private static String extractSupplierName(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();

            if (line.contains("발신")) {
                String cleaned = line.replace("발신", "").replace(":", "").trim();
                if (!cleaned.isBlank()) {
                    return cleaned;
                }

                if (i + 1 < lines.size()) {
                    String nextLine = lines.get(i + 1).trim();
                    if (!nextLine.isBlank()) {
                        return nextLine;
                    }
                }
            }
        }
        return "UNKNOWN";
    }

    private static String extractEffectiveDate(List<String> lines) {
        Pattern datePattern = Pattern.compile("(\\d{4})[년.-]\\s*(\\d{1,2})[월.-]\\s*(\\d{1,2})일?");

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();

            if (line.contains("시행일") || line.contains("적용일자")) {
                Matcher matcher = datePattern.matcher(line);
                if (matcher.find()) {
                    return formatToStandardDate(matcher.group(1), matcher.group(2), matcher.group(3));
                }

                if (i + 1 < lines.size()) {
                    Matcher nextMatcher = datePattern.matcher(lines.get(i + 1));
                    if (nextMatcher.find()) {
                        return formatToStandardDate(nextMatcher.group(1), nextMatcher.group(2), nextMatcher.group(3));
                    }
                }
            }
        }
        return null;
    }

    private static String formatToStandardDate(String year, String month, String day) {
        int m = Integer.parseInt(month);
        int d = Integer.parseInt(day);
        return String.format("%s-%02d-%02d", year, m, d);
    }

    // [핵심 수정 위치] 문서번호에 걸리지 않고 진짜 표 헤더만 정확히 매칭
    private static int findTableHeaderEndIndex(List<String> lines) {
        int lastHeaderIdx = -1;
        for (int i = 0; i < lines.size(); i++) {
            String cleaned = lines.get(i).replaceAll("\\s+", "");

            // 1. 헤더가 한 줄로 다 들어오는 경우 (번호...품목명...인상률)
            if (cleaned.contains("품목명") && cleaned.contains("인상률")) {
                return i;
            }

            // 2. OCR이 한 줄씩 컬럼명을 뱉는 경우: 마지막 컬럼인 "인상률"의 위치를 검색
            if (cleaned.equals("인상률") || cleaned.endsWith("인상률")) {
                lastHeaderIdx = i;
                break;
            }
        }

        if (lastHeaderIdx != -1) {
            log.info("표 헤더 종료 위치 발견(인상률): index {}", lastHeaderIdx);
            return lastHeaderIdx;
        }

        return -1;
    }

    private static Long parseSafeLong(String rawValue, ParseContext context) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }

        String cleaned = rawValue.replace("원", "").replace(",", "").trim();

        if (!cleaned.matches("^\\d+$")) {
            log.warn("숫자로 변환할 수 없는 단가 값 발견: '{}' -> null 처리됨", rawValue);
            return null;
        }

        return ParseOcrValueHelper.parseLong(cleaned, context);
    }

    @Override
    public boolean supports(List<String> ocrLines) {
        if (ocrLines == null || ocrLines.isEmpty()) {
            return false;
        }
        String fullText = String.join(" ", ocrLines);
        return TYPE2_HEADER_PATTERN.matcher(fullText).find();
    }

    @Override
    public List<CreateCommonItemDocumentReqDto> parse(List<String> ocrLines, MultipartFile file, ParseContext context,
                                                      SourceType sourceType) {
        return parseToDtos(ocrLines, context, sourceType);
    }
}
