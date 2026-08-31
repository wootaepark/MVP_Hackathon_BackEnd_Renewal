package com.bosalpim.compozi_ai.domain.document.service;

import com.bosalpim.compozi_ai.domain.document.component.parser.FileParser;
import com.bosalpim.compozi_ai.domain.document.dto.request.commonFile.CreateCommonItemDocumentReqDto;
import com.bosalpim.compozi_ai.domain.document.dto.request.manualFile.CreateManualItemDocumentListReqDto;
import com.bosalpim.compozi_ai.domain.document.dto.response.CreateItemDocumentResDto;
import com.bosalpim.compozi_ai.domain.document.dto.response.OcrPreviewResDto;
import com.bosalpim.compozi_ai.domain.document.entity.File;
import com.bosalpim.compozi_ai.domain.document.entity.Item;
import com.bosalpim.compozi_ai.domain.document.enums.InputMethod;
import com.bosalpim.compozi_ai.domain.document.repository.FileRepository;
import com.bosalpim.compozi_ai.general.enums.BadStatusCode;
import com.bosalpim.compozi_ai.general.exception.CustomException;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class FileService {

    private final List<FileParser> fileParsers;
    private final FileRepository fileRepository;
    private final ItemService itemService;


    @Transactional
    public CreateItemDocumentResDto createCommonFile(MultipartFile file) throws IOException {
        String filename = file.getOriginalFilename(); // 파일 이름 추출

        FileParser parser = fileParsers.stream()
                .filter(p -> p.supports(filename))
                .findFirst()
                .orElseThrow(() -> new CustomException(BadStatusCode.UNSUPPORTED_FILE_TYPE));
        // 형식에 맞는 파서 추출

        if (parser.isOcrParser()) {
            throw new CustomException(BadStatusCode.INVALID_FILE_PARSER_REQUEST);
        }

        File newFile = File.createFile(filename, InputMethod.FILE);
        File savedFile = fileRepository.save(newFile);
        
        List<Item> items = itemService.createCommonItem(parser.parse(file), savedFile);
        return CreateItemDocumentResDto.from(items);

    }

    @Transactional(readOnly = true) // DB 조작이 없거나 읽기 전용
    public List<OcrPreviewResDto> previewOcrFile(MultipartFile file) throws IOException {
        String filename = file.getOriginalFilename();

        // OCR 파서 전용 검증
        FileParser parser = fileParsers.stream()
                .filter(p -> p.supports(filename) && p.isOcrParser()) // OCR 파서 전용 조건
                .findFirst()
                .orElseThrow(() -> new CustomException(BadStatusCode.UNSUPPORTED_FILE_TYPE));

        // DB 저장 없이 파일 데이터만 추출
        List<CreateCommonItemDocumentReqDto> parsedData = parser.parse(file);

        // 미리보기 DTO 형태로 반환 (클라이언트는 이를 확인 후 수정/확정 가능)
        return parsedData.stream()
                .map(OcrPreviewResDto::from)
                .toList();
    }

    @Transactional
    public CreateItemDocumentResDto createOcrItem(List<CreateCommonItemDocumentReqDto> reqDtos, String filename) {
        // 1. OCR 입력 방식(InputMethod.OCR)으로 파일 메타데이터 저장
        String saveFilename = (filename != null && !filename.isBlank()) ? filename : "OCR_FILE";
        File savedFile = fileRepository.save(File.createFile(saveFilename, InputMethod.FILE));

        // 2. 전달받은 CreateCommonItemDocumentReqDto 리스트를 그대로 사용해 Item 생성
        List<Item> items = itemService.createCommonItem(reqDtos, savedFile);

        return CreateItemDocumentResDto.from(items);
    }


    @Transactional
    public CreateItemDocumentResDto createManualFile(CreateManualItemDocumentListReqDto reqDto) {
        List<File> files = reqDto.getItems().stream()
                .map(req -> File.createFile(null, InputMethod.MANUAL))
                .toList();

        List<File> savedFiles = fileRepository.saveAll(files);
        return CreateItemDocumentResDto.from(itemService.createManualItem(reqDto, savedFiles));

    }
}
