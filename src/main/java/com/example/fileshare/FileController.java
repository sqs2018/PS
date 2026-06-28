package com.example.fileshare;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class FileController {

    @Value("${file.upload.dir:./uploads}")
    private String uploadDir;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> uploadFolder(@RequestParam("files") MultipartFile[] files,
                                                             @RequestParam(value = "targetPath", required = false) String targetPath,
                                                             HttpServletRequest request) {
        try {
            String baseUrl = ServletUriComponentsBuilder.fromRequestUri(request)
                    .replacePath(null)
                    .build()
                    .toUriString();

            String folderName;
            Path folderPath;

            if (targetPath != null && !targetPath.isEmpty()) {
                folderPath = Paths.get(targetPath);
                if (!Files.exists(folderPath) || !Files.isDirectory(folderPath)) {
                    Map<String, Object> error = new HashMap<>();
                    error.put("error", "指定的目录不存在");
                    return ResponseEntity.badRequest().body(error);
                }
                folderName = folderPath.getFileName().toString();
            } else {
                String folderId = UUID.randomUUID().toString().substring(0, 8);
                String timestamp = LocalDateTime.now().format(FORMATTER);
                folderName = "share_" + timestamp + "_" + folderId;
                folderPath = Paths.get(uploadDir).resolve(folderName);
                Files.createDirectories(folderPath);
            }

            for (MultipartFile file : files) {
                if (!file.isEmpty()) {
                    String filename = file.getOriginalFilename();
                    if (filename != null && !filename.isEmpty()) {
                        Path filePath = folderPath.resolve(filename);
                        Files.createDirectories(filePath.getParent());
                        try (InputStream is = file.getInputStream()) {
                            Files.copy(is, filePath, StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                }
            }

            String downloadUrl = baseUrl + "/api/download/path?path=" + folderPath.toString().replace("\\", "/");

            Map<String, Object> response = new HashMap<>();
            response.put("folderName", folderName);
            response.put("downloadUrl", downloadUrl);
            response.put("qrCode", "/api/qr/download?path=" + java.net.URLEncoder.encode(folderPath.toString(), "UTF-8"));

            return ResponseEntity.ok(response);
        } catch (IOException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    @GetMapping("/files/{folderName}")
    public ResponseEntity<Map<String, Object>> listFiles(@PathVariable String folderName) {
        try {
            Path folderPath = Paths.get(uploadDir).resolve(folderName);
            if (!Files.exists(folderPath) || !Files.isDirectory(folderPath)) {
                return ResponseEntity.notFound().build();
            }

            List<Map<String, Object>> files = new ArrayList<>();
            List<Path> found = Files.find(folderPath, Integer.MAX_VALUE,
                    (p, a) -> a.isRegularFile()).toList();

            for (Path p : found) {
                Map<String, Object> fileInfo = new HashMap<>();
                String relativePath = folderPath.relativize(p).toString().replace("\\", "/");
                fileInfo.put("name", p.getFileName().toString());
                fileInfo.put("path", relativePath);
                fileInfo.put("size", p.toFile().length());
                files.add(fileInfo);
            }

            Map<String, Object> response = new HashMap<>();
            response.put("folderName", folderName);
            response.put("files", files);
            response.put("downloadUrl", "/api/download/" + folderName);
            response.put("qrCode", "/api/qr/" + folderName);

            return ResponseEntity.ok(response);
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/download/{folderName}")
    public ResponseEntity<Resource> downloadFolder(@PathVariable String folderName) {
        try {
            Path folderPath = Paths.get(uploadDir).resolve(folderName);
            if (!Files.exists(folderPath)) {
                return ResponseEntity.notFound().build();
            }

            Path zipPath = Paths.get(new File(uploadDir).getParent()).resolve(folderName + ".zip");
            if (!Files.exists(zipPath)) {
                zipFolder(folderPath, zipPath);
            }

            Resource resource = new UrlResource(zipPath.toUri());
            
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + folderName + ".zip\"")
                    .body(resource);
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/qr/{folderName}")
    public ResponseEntity<byte[]> getQRCode(@PathVariable String folderName,
                                             @RequestParam(defaultValue = "300") int size) {
        try {
            String baseUrl = ServletUriComponentsBuilder.fromCurrentRequestUri()
                    .replacePath(null)
                    .build()
                    .toUriString();
            String url = baseUrl + "/api/files/" + folderName;

            byte[] qrCode = QRCodeService.generateQRCode(url, size, size);
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_PNG)
                    .body(qrCode);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }



    @GetMapping("/qr/service")
    public ResponseEntity<byte[]> getServiceQRCode(@RequestParam(defaultValue = "200") int size) {
        try {
            String baseUrl = ServletUriComponentsBuilder.fromCurrentContextPath()
                    .build()
                    .toUriString();

            byte[] qrCode = QRCodeService.generateQRCode(baseUrl, size, size);
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_PNG)
                    .body(qrCode);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/qr/download")
    public ResponseEntity<byte[]> getDownloadQRCode(
            @RequestParam String path,
            @RequestParam(defaultValue = "200") int size) {
        try {
            String baseUrl = ServletUriComponentsBuilder.fromCurrentContextPath()
                    .build()
                    .toUriString();

            String url;
            Path filePath = Paths.get(path);
            if (Files.isDirectory(filePath)) {
                url = baseUrl + "/api/download/path?path=" + path;
            } else {
                String filename = filePath.getFileName().toString();
                url = baseUrl + "/api/download/file?name=" + filename + "&path=" + path;
            }

            byte[] qrCode = QRCodeService.generateQRCode(url, size, size);
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_PNG)
                    .body(qrCode);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/files/tree")
    public ResponseEntity<Map<String, Object>> getFileTree() {
        try {
            Path uploadPath = Paths.get(uploadDir);
            if (!Files.exists(uploadPath)) {
                Map<String, Object> root = new HashMap<>();
                root.put("name", "uploads");
                root.put("type", "folder");
                root.put("children", Collections.emptyList());
                return ResponseEntity.ok(root);
            }

            Map<String, Object> root = new HashMap<>();
            root.put("name", "uploads");
            root.put("type", "folder");
            root.put("path", uploadPath.toString());
            root.put("children", buildTree(uploadPath, uploadPath));

            return ResponseEntity.ok(root);
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    private List<Map<String, Object>> buildTree(Path root, Path current) throws IOException {
        List<Map<String, Object>> children = new ArrayList<>();

        List<Path> paths = Files.list(current)
                .collect(java.util.stream.Collectors.toList());

        for (Path path : paths) {
            Map<String, Object> node = new HashMap<>();
            node.put("name", path.getFileName().toString());
            node.put("path", path.toString());

            if (Files.isDirectory(path)) {
                node.put("type", "folder");
                List<Map<String, Object>> subChildren = buildTree(root, path);
                node.put("children", subChildren);
                node.put("hasChildren", !subChildren.isEmpty());
            } else {
                node.put("type", "file");
                node.put("size", path.toFile().length());
                node.put("lastModified", path.toFile().lastModified());
            }

            children.add(node);
        }

        return children;
    }

    @Scheduled(fixedRate = 60000)
    public void cleanupOldZipFiles() {
        try {
            Path uploadPath = Paths.get(new File(uploadDir).getParent());
            if (!Files.exists(uploadPath)) {
                return;
            }

            long oneHourAgo = System.currentTimeMillis() - (60 * 60 * 1000);
            Files.list(uploadPath)
                    .filter(p -> p.toString().endsWith(".zip"))
                    .filter(p -> p.toFile().lastModified() < oneHourAgo)
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
    }

    @GetMapping("/download/file")
    public ResponseEntity<Resource> downloadFile(@RequestParam String name, @RequestParam String path) {
        try {
            Path filePath = Paths.get(path);

            if (!Files.exists(filePath)) {
                return ResponseEntity.notFound().build();
            }

            Resource resource = new UrlResource(filePath.toUri());
            String contentType = Files.probeContentType(filePath);
            MediaType mediaType = contentType != null ? 
                    MediaType.parseMediaType(contentType) : 
                    MediaType.APPLICATION_OCTET_STREAM;

            return ResponseEntity.ok()
                    .contentType(mediaType)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + name + "\"")
                    .body(resource);
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/download/path")
    public ResponseEntity<Resource> downloadByPath(@RequestParam String path) {
        try {
            Path folderPath = Paths.get(path);
            if (!Files.exists(folderPath) || !Files.isDirectory(folderPath)) {
                return ResponseEntity.notFound().build();
            }

            String folderName = folderPath.getFileName().toString();
            Path zipPath = Paths.get(new File(uploadDir).getParent()).resolve(folderName + ".zip");
            
            if (!Files.exists(zipPath)) {
                zipFolder(folderPath, zipPath);
            }

            Resource resource = new UrlResource(zipPath.toUri());
            
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + folderName + ".zip\"")
                    .body(resource);
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/folder/create")
    public ResponseEntity<Map<String, Object>> createFolder(@RequestBody Map<String, String> request) {
        Map<String, Object> response = new HashMap<>();
        try {
            String folderName = request.get("name");
            if (folderName == null || folderName.trim().isEmpty()) {
                response.put("success", false);
                response.put("message", "文件夹名称不能为空");
                return ResponseEntity.badRequest().body(response);
            }

            // 确保上传目录存在
            Path uploadPath = Paths.get(uploadDir);
            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath);
            }

            String sanitizedName = sanitizeFolderName(folderName);
            Path folderPath = uploadPath.resolve(sanitizedName);

            if (Files.exists(folderPath)) {
                response.put("success", false);
                response.put("message", "文件夹已存在");
                return ResponseEntity.badRequest().body(response);
            }

            Files.createDirectories(folderPath);
            response.put("success", true);
            response.put("message", "文件夹创建成功");
            response.put("folderName", sanitizedName);
            response.put("path", folderPath.toString());
            return ResponseEntity.ok(response);
        } catch (IOException e) {
            response.put("success", false);
            response.put("message", "创建失败：" + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @PostMapping("/delete")
    public ResponseEntity<Map<String, Object>> deleteItem(@RequestBody Map<String, String> request) {
        Map<String, Object> response = new HashMap<>();
        try {
            String path = request.get("path");
            String type = request.get("type");
            
            if (path == null || path.trim().isEmpty()) {
                response.put("success", false);
                response.put("message", "路径不能为空");
                return ResponseEntity.badRequest().body(response);
            }
            
            // 安全检查：确保路径在上传目录内
            Path itemPath = Paths.get(path).normalize();
            Path uploadPath = Paths.get(uploadDir).normalize();
            
            if (!itemPath.startsWith(uploadPath)) {
                response.put("success", false);
                response.put("message", "非法路径：只能删除上传目录内的文件");
                return ResponseEntity.badRequest().body(response);
            }
            
            if (!Files.exists(itemPath)) {
                response.put("success", false);
                response.put("message", "文件或文件夹不存在");
                return ResponseEntity.notFound().build();
            }
            
            // 删除文件或文件夹
            if (Files.isDirectory(itemPath)) {
                Files.walk(itemPath)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
            } else {
                Files.delete(itemPath);
            }
            
            response.put("success", true);
            response.put("message", "删除成功");
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "删除失败：" + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    private String sanitizeFolderName(String folderName) {
        // 移除或替换不安全的字符
        String sanitized = folderName.replaceAll("[\\/:*?\"<>|]", "_");
        // 防止路径遍历攻击
        sanitized = sanitized.replace("..", "_");
        sanitized = sanitized.replace("/", "_");
        sanitized = sanitized.replace("\\", "_");
        return sanitized.trim();
    }

    @GetMapping("/list")
    public ResponseEntity<List<Map<String, Object>>> listAllFolders() {
        try {
            Path uploadPath = Paths.get(uploadDir);
            if (!Files.exists(uploadPath)) {
                return ResponseEntity.ok(Collections.emptyList());
            }

            List<Map<String, Object>> folders = new ArrayList<>();
            Files.list(uploadPath)
                    .filter(Files::isDirectory)
                    .filter(p -> !p.toString().endsWith(".zip"))
                    .forEach(p -> {
                        Map<String, Object> folder = new HashMap<>();
                        folder.put("name", p.getFileName().toString());
                        folder.put("url", "/api/files/" + p.getFileName().toString());
                        folder.put("downloadUrl", "/api/download/" + p.getFileName().toString());
                        folder.put("qrCode", "/api/qr/" + p.getFileName().toString());
                        try {
                            folder.put("fileCount", (int) Files.list(p).count());
                            folder.put("size", Files.walk(p).mapToLong(f -> f.toFile().length()).sum());
                        } catch (IOException ignored) {}
                        folders.add(folder);
                    });

            return ResponseEntity.ok(folders);
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    private void zipFolder(Path source, Path zipPath) throws IOException {
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(
                new java.io.FileOutputStream(zipPath.toFile()))) {
            Files.walk(source)
                    .filter(Files::isRegularFile)
                    .forEach(file -> {
                        try {
                            String entryName = source.relativize(file).toString().replace("\\", "/");
                            zos.putNextEntry(new java.util.zip.ZipEntry(entryName));
                            Files.copy(file, zos);
                            zos.closeEntry();
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        }
    }
}