package stirling.software.SPDF.controller.api.misc;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.imageio.ImageIO;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import io.github.pixee.security.Filenames;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import stirling.software.SPDF.model.api.PDFExtractImagesRequest;
import stirling.software.common.service.CustomPDFDocumentFactory;
import stirling.software.common.util.ExceptionUtils;
import stirling.software.common.util.ImageProcessingUtils;
import stirling.software.common.util.WebResponseUtils;

@RestController
@RequestMapping("/api/v1/misc")
@Slf4j
@Tag(name = "Misc", description = "Miscellaneous APIs")
@RequiredArgsConstructor
public class ExtractImagesController {

    private final CustomPDFDocumentFactory pdfDocumentFactory;

    @PostMapping(consumes = "multipart/form-data", value = "/extract-images")
    @Operation(
            summary = "Extract images from a PDF file",
            description =
                    "This endpoint extracts images from a given PDF file and returns them in a zip"
                            + " file. Users can specify the output image format. Input:PDF"
                            + " Output:IMAGE/ZIP Type:SIMO")
    public ResponseEntity<byte[]> extractImages(@ModelAttribute PDFExtractImagesRequest request)
            throws IOException, InterruptedException, ExecutionException {
        MultipartFile file = request.getFileInput();
        String format = request.getFormat();
        boolean allowDuplicates = Boolean.TRUE.equals(request.getAllowDuplicates());
        PDDocument document = pdfDocumentFactory.load(file);

        // Determine if multithreading should be used based on PDF size or number of pages
        boolean useMultithreading = shouldUseMultithreading(file, document);

        // Create ByteArrayOutputStream to write zip file to byte array
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        // Create ZipOutputStream to create zip file
        ZipOutputStream zos = new ZipOutputStream(baos);

        // Set compression level
        zos.setLevel(Deflater.BEST_COMPRESSION);

        String filename =
                Filenames.toSimpleFileName(file.getOriginalFilename())
                        .replaceFirst("[.][^.]+$", "");
        Set<byte[]> processedImages = new HashSet<>();

        if (useMultithreading) {
            // Executor service to handle multithreading
            ExecutorService executor =
                    Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
            Set<Future<Void>> futures = new HashSet<>();

            // Safely iterate over each page, handling corrupt PDFs where page count might be wrong
            try {
                int pageCount = document.getPages().getCount();
                log.debug("Document reports {} pages", pageCount);

                int consecutiveFailures = 0;

                for (int pgNum = 0; pgNum < pageCount; pgNum++) {
                    try {
                        PDPage page = document.getPage(pgNum);
                        consecutiveFailures = 0; // Reset on success
                        final int currentPageNum = pgNum + 1; // Convert to 1-based page numbering
                        Future<Void> future =
                                executor.submit(
                                        () -> {
                                            try {
                                                // Call the image extraction method for each page
                                                extractImagesFromPage(
                                                        page,
                                                        format,
                                                        filename,
                                                        currentPageNum,
                                                        processedImages,
                                                        zos,
                                                        allowDuplicates);
                                            } catch (Exception e) {
                                                // Log the error and continue processing other pages
                                                ExceptionUtils.logException(
                                                        "image extraction from page "
                                                                + currentPageNum,
                                                        e);
                                            }

                                            return null; // Callable requires a return type
                                        });

                        // Add the Future object to the list to track completion
                        futures.add(future);
                    } catch (Exception e) {
                        consecutiveFailures++;
                        ExceptionUtils.logException("page access for page " + (pgNum + 1), e);

                        if (consecutiveFailures >= 3) {
                            log.warn("Stopping page iteration after 3 consecutive failures");
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                ExceptionUtils.logException("page count determination", e);
                throw e;
            }

            // Wait for all tasks to complete
            for (Future<Void> future : futures) {
                future.get();
            }

            // Close executor service
            executor.shutdown();
        } else {
            // Single-threaded extraction
            for (int pgNum = 0; pgNum < document.getPages().getCount(); pgNum++) {
                PDPage page = document.getPage(pgNum);
                extractImagesFromPage(
                        page, format, filename, pgNum + 1, processedImages, zos, allowDuplicates);
            }
        }

        // Close PDDocument and ZipOutputStream
        document.close();
        zos.close();

        // Create ByteArrayResource from byte array
        byte[] zipContents = baos.toByteArray();

        return WebResponseUtils.baosToWebResponse(
                baos, filename + "_extracted-images.zip", MediaType.APPLICATION_OCTET_STREAM);
    }

    private boolean shouldUseMultithreading(MultipartFile file, PDDocument document) {
        // Criteria: Use multithreading if file size > 10MB or number of pages > 20
        long fileSizeInMB = file.getSize() / (1024 * 1024);
        int numberOfPages = document.getPages().getCount();
        return fileSizeInMB > 10 || numberOfPages > 20;
    }

    private void extractImagesFromPage(
            PDPage page,
            String format,
            String filename,
            int pageNum,
            Set<byte[]> processedImages,
            ZipOutputStream zos,
            boolean allowDuplicates)
            throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            log.error("MD5 algorithm not available for extractImages hash.", e);
            return;
        }
        if (page.getResources() == null || page.getResources().getXObjectNames() == null) {
            return;
        }
        int count = 1;
        for (COSName name : page.getResources().getXObjectNames()) {
            try {
                if (page.getResources().isImageXObject(name)) {
                    PDImageXObject image = (PDImageXObject) page.getResources().getXObject(name);
                    if (!allowDuplicates) {
                        byte[] data = ImageProcessingUtils.getImageData(image.getImage());
                        byte[] imageHash = md.digest(data);
                        synchronized (processedImages) {
                            if (processedImages.stream()
                                    .anyMatch(hash -> Arrays.equals(hash, imageHash))) {
                                continue; // Skip already processed images
                            }
                            processedImages.add(imageHash);
                        }
                    }

                    RenderedImage renderedImage = image.getImage();

                    // Convert to standard RGB colorspace if needed
                    BufferedImage bufferedImage = convertToRGB(renderedImage, format);

                    // Write image to zip file
                    String imageName = filename + "_page_" + pageNum + "_" + count++ + "." + format;
                    synchronized (zos) {
                        zos.putNextEntry(new ZipEntry(imageName));
                        ByteArrayOutputStream imageBaos = new ByteArrayOutputStream();
                        ImageIO.write(bufferedImage, format, imageBaos);
                        zos.write(imageBaos.toByteArray());
                        zos.closeEntry();
                    }
                }
            } catch (IOException e) {
                ExceptionUtils.logException("image extraction", e);
                throw ExceptionUtils.handlePdfException(e, "during image extraction");
            }
        }
    }

    private BufferedImage convertToRGB(RenderedImage renderedImage, String format) {
        int width = renderedImage.getWidth();
        int height = renderedImage.getHeight();
        BufferedImage rgbImage;

        if ("png".equalsIgnoreCase(format)) {
            rgbImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        } else if ("jpeg".equalsIgnoreCase(format) || "jpg".equalsIgnoreCase(format)) {
            rgbImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        } else if ("gif".equalsIgnoreCase(format)) {
            rgbImage = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_INDEXED);
        } else {
            rgbImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        }

        Graphics2D g = rgbImage.createGraphics();
        g.drawImage((Image) renderedImage, 0, 0, null);
        g.dispose();
        return rgbImage;
    }
}
