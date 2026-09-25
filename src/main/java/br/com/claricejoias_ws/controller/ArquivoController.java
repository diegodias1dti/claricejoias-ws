package br.com.claricejoias_ws.controller;

import br.com.claricejoias_ws.service.MinioService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.Map;

@RestController
@RequestMapping("/api/arquivos")
@RequiredArgsConstructor
public class ArquivoController {

    private final MinioService minioService;

    @PostMapping("/upload")
    public ResponseEntity<Map<String, String>> upload(@RequestParam("file") MultipartFile file) throws Exception {
        String objectName = minioService.upload(file);
        String url = minioService.getPresignedUrl(objectName);
        return ResponseEntity.ok(Map.of(
                "objectName", objectName,
                "url", url
        ));
    }

    @GetMapping("/url/{objectName}")
    public ResponseEntity<Map<String, String>> getUrl(@PathVariable String objectName) throws Exception {
        String url = minioService.getPresignedUrl(objectName);
        return ResponseEntity.ok(Map.of("url", url));
    }

    @GetMapping("/download/{objectName}")
    public ResponseEntity<InputStreamResource> download(@PathVariable String objectName) throws Exception {
        InputStream stream = minioService.download(objectName);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + objectName + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new InputStreamResource(stream));
    }


    // Adicione este método no seu ArquivoController
    @GetMapping("/view/{objectName}")
    public ResponseEntity<InputStreamResource> view(@PathVariable String objectName) {
        try {
            String contentType = minioService.getContentType(objectName);
            InputStream stream = minioService.download(objectName);

            // Retorna o arquivo diretamente para o navegador renderizar, com o content-type real armazenado
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .body(new InputStreamResource(stream));
        } catch (Exception e) {
            // objectName inválido/inexistente no MinIO (ex: referência antiga, nome digitado
            // errado) não pode derrubar a requisição com 500 — devolve 404 de forma limpa,
            // o front já trata isso caindo no placeholder (ver ImagemService.getUrl).
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{objectName}")
    public ResponseEntity<Void> delete(@PathVariable String objectName) throws Exception {
        minioService.delete(objectName);
        return ResponseEntity.noContent().build();
    }
}
