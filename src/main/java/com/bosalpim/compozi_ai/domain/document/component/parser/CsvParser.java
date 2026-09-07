package com.bosalpim.compozi_ai.domain.document.component.parser;

import com.bosalpim.compozi_ai.domain.document.dto.request.commonFile.CreateCommonItemDocumentReqDto;
import com.bosalpim.compozi_ai.general.enums.BadStatusCode;
import com.bosalpim.compozi_ai.general.exception.CustomException;
import com.opencsv.CSVReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class CsvParser implements FileParser {
    @Override
    public boolean supports(String filename) {
        return filename != null && filename.toLowerCase().endsWith(".csv");
    }

    @Override
    public List<CreateCommonItemDocumentReqDto> parse(MultipartFile file) {
        List<CreateCommonItemDocumentReqDto> list = new ArrayList<>(135_000);

        try (InputStreamReader inputStreamReader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8);
             CSVReader csvReader = new CSVReader(inputStreamReader)) {

            String[] row;
            long rowNo = 0;

            csvReader.readNext(); // 첫째 줄 건너뛰기

            // readAll() 대신에 한 줄 씩 읽기

            while ((row = csvReader.readNext()) != null) {
                rowNo++;

                ParseCsvValueHelper.ParseContext context = new ParseCsvValueHelper.ParseContext();

                CreateCommonItemDocumentReqDto dto = CreateCommonItemDocumentReqDto.builder()
                        .rowNo(rowNo)
                        .docId(ParseCsvValueHelper.parseString(getValue(row, 0)))
                        .sourceType(ParseCsvValueHelper.parseString(getValue(row, 1)))
                        .supplierName(ParseCsvValueHelper.parseString(getValue(row, 2)))
                        .rawItemName(ParseCsvValueHelper.parseString(getValue(row, 3)))
                        .spec(ParseCsvValueHelper.parseString(getValue(row, 4)))
                        .unit(ParseCsvValueHelper.parseString(getValue(row, 5)))
                        .priceBefore(ParseCsvValueHelper.parseLong(getValue(row, 6), context))
                        .priceAfter(ParseCsvValueHelper.parseLong(getValue(row, 7), context))
                        .effectiveDate(ParseCsvValueHelper.parseDate(getValue(row, 8), context))
                        .hasParseError(context.hasError())
                        .build();

                list.add(dto);


            }


        } catch (Exception e) {
            throw new CustomException(BadStatusCode.FILE_PARSE_FAILED);
        }

        return list;
    }

    private String getValue(String[] row, int index) {
        return (index < row.length) ? row[index] : null;
    }


}
