package com.example.demo.Simulator.Service;

import java.io.File;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Service
public class S3UploadService {

    private final S3Client s3Client;

    @Value("${app.s3.bucket}")
    private String bucket;

    public S3UploadService(S3Client s3Client) {
        this.s3Client = s3Client;
    }

    public void uploadFile(File file, String key) throws Exception {
        System.out.println("Attempting to upload file: " + file.getName() + " to bucket: " + bucket + " with key: " + key);
        
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();

        s3Client.putObject(request, RequestBody.fromFile(file.toPath()));

        System.out.println(" Successfully uploaded to S3: " + key);
    }

    public String uploadFileFromBytes(String key, byte[] content) throws Exception {
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();

        s3Client.putObject(request, RequestBody.fromBytes(content));

        return "https://" + bucket + ".s3.amazonaws.com/" + key;
    }

}