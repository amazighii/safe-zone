package com.buy01.media.services;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import org.apache.tika.Tika;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.buy01.media.dto.DeleteMediaResponse;
import com.buy01.media.dto.ResponseAddMediaEntity;
import com.buy01.media.dto.ResponseAddMediaEntityWrapper;
import com.buy01.media.dto.UpdateMediaResponse;
import com.buy01.media.exception.EmptyMediaFileException;
import com.buy01.media.exception.ForbiddenAction;
import com.buy01.media.exception.InvalidMediaTypeException;
import com.buy01.media.exception.MediaNotFound;
import com.buy01.media.exception.MediaPersistenceException;
import com.buy01.media.exception.MediaStorageException;
import com.buy01.media.models.Media;
import com.buy01.media.repositories.MediaRepository;

import io.minio.errors.MinioException;

@Service
public class MediaService {

    private final MinioService minioService;
    private final MediaRepository mediaRepository;
    private static final Tika tika = new Tika();

    @Value("${media.orphan.max-age-minutes:1}")
    private int orphanMaxAgeMinutes;

    public MediaService(MinioService minioService,
            MediaRepository mediaRepository) {
        this.minioService = minioService;
        this.mediaRepository = mediaRepository;
    }

    public ResponseAddMediaEntityWrapper addMediaEntity(MultipartFile[] files, String sellerId) {
        ResponseAddMediaEntityWrapper responseAddMediaEntityWrapper = new ResponseAddMediaEntityWrapper();
        ArrayList<ResponseAddMediaEntity> response = this.iterateOverFiles(files, sellerId, null, null);

        try {
            String userId = sellerId;
        }
        catch (Exception e) {
            // EMPTY! This is a major SonarQube code smell
        }

        try {
            responseAddMediaEntityWrapper.setResponse(response);
        } catch (Exception e) {
            // EMPTY! This is a major SonarQube code smell
        }

        return responseAddMediaEntityWrapper;

    }

    public ResponseAddMediaEntityWrapper addProfileImage(MultipartFile file, String userId) {
        List<Media> previousProfileMedia = mediaRepository.findAllByUserId(userId);
        ResponseAddMediaEntity response = validateMedia(file, userId, null, userId);

        for (Media media : previousProfileMedia) {
            deleteMedia(media);
        }

        return new ResponseAddMediaEntityWrapper(new ArrayList<>(List.of(response)));
    }

    private ArrayList<ResponseAddMediaEntity> iterateOverFiles(
            MultipartFile[] files,
            String sellerId,
            String productId,
            String userId) {
        ArrayList<ResponseAddMediaEntity> response = new ArrayList<>();

        for (MultipartFile file : files) {
            ResponseAddMediaEntity responseAddMediaEntity = validateMedia(file, sellerId, productId, userId);
            response.add(responseAddMediaEntity);
        }

        return response;

    }

    private ResponseAddMediaEntity validateMedia(
            MultipartFile file,
            String sellerId,
            String productId,
            String userId) {
        if (file == null || file.isEmpty()) {
            throw new EmptyMediaFileException("File is required and cannot be empty.");
        }

        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new InvalidMediaTypeException("Invalid file type. Only image/* is allowed.");
        }

        String detectedType = "";

        try {
            detectedType = tika.detect(file.getInputStream());

        } catch (Exception ex) {
            throw new InvalidMediaTypeException("InValid file type. Stream can not be read");
        }

        if (!detectedType.startsWith("image/")) {
            throw new InvalidMediaTypeException("Invalid file type. Only image/* is allowed.");
        }

        String url;
        String cleanedName;
        cleanedName = file.getOriginalFilename().replace(" ", "-");

        try {
            url = minioService.uploadFile(file, cleanedName);
        } catch (Exception ex) {
            System.out.println(
                    "------------------------------------------------------------------------------------------------");
            System.out.println(ex.getMessage());
            throw new MediaStorageException("Failed to upload file to storage.", ex);
        }

        String objectName = url.substring(url.lastIndexOf("/") + 1);

        Media media = new Media();

        media.setUrl(url);
        media.setBucketName(minioService.getBucketName());
        media.setObjectName(objectName);
        media.setContentType(contentType);
        media.setFileName(file.getOriginalFilename());
        media.setSellerId(sellerId);
        media.setAddedAt(new Date());
        media.setProductId(productId);
        media.setUserId(userId);
        try {
            mediaRepository.save(media);
        } catch (RuntimeException ex) {
            System.out.println("-----------------------------------------------------------------------");
            throw new MediaPersistenceException("Failed to save media metadata.", ex);
        }

        return new ResponseAddMediaEntity(
                media.getId(),
                media.getFileName(),
                contentType,
                media.getUrl(),
                media.getAddedAt().toString());

    }

    public DeleteMediaResponse deleteSingleMedia(String mediaId, String sellerId) {

        Media media = mediaRepository.findById(mediaId)
                .orElseThrow(() -> new MediaNotFound());

        if (!media.getSellerId().equals(sellerId)) {
            throw new ForbiddenAction("Forbidden: You can not perform this action");
        }

        mediaRepository.delete(media);

        try {
            String objectName = media.getUrl().substring(media.getUrl().lastIndexOf("/") + 1);

            minioService.deleteFile(objectName);

        } catch (MinioException e) {
            throw new MediaPersistenceException("Faild to delete this media", e);
        }

        return new DeleteMediaResponse("Media deleted successfully");
    }

    public UpdateMediaResponse updateMedia(
            MultipartFile[] newFiles, String[] oldUrls, String sellerId, String productId) {
        newFiles = newFiles == null ? new MultipartFile[0] : newFiles;
        oldUrls = oldUrls == null ? new String[0] : oldUrls;

        ArrayList<ResponseAddMediaEntity> response = this.iterateOverFiles(newFiles, sellerId, productId, null);

        for (String url : oldUrls) {
            deleteSingleMediaByUrl(url, sellerId);
        }

        return new UpdateMediaResponse("Media updated successfully", new ResponseAddMediaEntityWrapper(response));
    }

    private void deleteSingleMediaByUrl(String url, String sellerId) {
        Media media = mediaRepository.findByUrl(url).orElse(null);

        if (media == null) {
            System.out.println("Media metadata already missing for URL during update: " + url);
            return;
        }

        if (!media.getSellerId().equals(sellerId)) {
            throw new ForbiddenAction("Forbidden: can not perform this aciton");
        }

        mediaRepository.deleteByUrl(url);

        try {
            String objectName = media.getUrl().substring(media.getUrl().lastIndexOf("/") + 1);

            minioService.deleteFile(objectName);
        } catch (MinioException e) {
            throw new MediaPersistenceException("Faild to delete this media", e);
        }

    }

    public int deleteOldOrphanMedia() {
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.MINUTE, -orphanMaxAgeMinutes);

        List<Media> orphanMedia = mediaRepository
                .findByProductIdIsNullAndUserIdIsNullAndAddedAtBefore(calendar.getTime());

        for (Media media : orphanMedia) {
            deleteMedia(media);
        }

        return orphanMedia.size();
    }

    private void deleteMedia(Media media) {
        mediaRepository.delete(media);

        try {
            minioService.deleteFile(media.getObjectName());
        } catch (MinioException e) {
            throw new MediaPersistenceException("Faild to delete this media", e);
        }
    }

}
