package com.example.ragapp.document;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.regex.Pattern;

import com.example.ragapp.common.InvalidDocumentException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/** All upload checks in one place. Every check throws InvalidDocumentException (HTTP 400). */
@Component
public class DocumentValidator {

    private static final Pattern TENANT_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,50}");
    private static final Pattern VERSION_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,30}");

    private static final byte[] PDF_START = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ZIP_START = {'P', 'K', 3, 4};   // a DOCX file is a ZIP file

    private final DocumentProperties properties;

    public DocumentValidator(DocumentProperties properties) {
        this.properties = properties;
    }

    public void validateTenant(String tenantId) {
        if (tenantId == null || !TENANT_PATTERN.matcher(tenantId).matches()) {
            throw new InvalidDocumentException(
                    "Invalid tenant id. Use 1-50 letters, digits, '-' or '_'.");
        }
    }

    /** Checks the file and returns its type. */
    public DocumentType validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidDocumentException("The file is empty. Please choose a PDF or DOCX file.");
        }

        String fileName = cleanFileName(file.getOriginalFilename());
        DocumentType type = DocumentType.fromFileName(fileName).orElseThrow(() ->
                new InvalidDocumentException("Unsupported file type. Only PDF and DOCX files are allowed."));

        // The file name can lie, so also check the first bytes of the file
        if (!startsWith(file, type == DocumentType.PDF ? PDF_START : ZIP_START)) {
            throw new InvalidDocumentException(
                    "The file content does not match its extension. Is it a real " + type.name() + " file?");
        }
        return type;
    }

    /** Uses the given title, or the file name without extension when no title is given. */
    public String resolveTitle(String title, String fileName) {
        String result = StringUtils.hasText(title) ? title.trim() : stripExtension(fileName);
        if (result.length() > properties.maxTitleLength()) {
            throw new InvalidDocumentException(
                    "The title is too long (maximum " + properties.maxTitleLength() + " characters).");
        }
        return result;
    }

    public String validateVersion(String version) {
        if (version == null || !VERSION_PATTERN.matcher(version.trim()).matches()) {
            throw new InvalidDocumentException(
                    "Invalid version. Use up to 30 letters, digits, '.', '-' or '_' (for example 2025-01).");
        }
        return version.trim();
    }

    /** Keeps only the file name (no folders), so "../../evil.pdf" becomes "evil.pdf". */
    public String cleanFileName(String originalName) {
        if (!StringUtils.hasText(originalName)) {
            throw new InvalidDocumentException("The file has no name.");
        }
        String name = StringUtils.getFilename(StringUtils.cleanPath(originalName.replace('\\', '/')));
        if (!StringUtils.hasText(name) || name.length() > 255) {
            throw new InvalidDocumentException("The file name is missing or too long.");
        }
        return name;
    }

    private String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private boolean startsWith(MultipartFile file, byte[] expected) {
        try (InputStream in = file.getInputStream()) {
            byte[] actual = in.readNBytes(expected.length);
            return Arrays.equals(actual, expected);
        } catch (IOException e) {
            throw new InvalidDocumentException("The uploaded file could not be read.");
        }
    }
}
