package com.mnc.ausy.domain;
import jakarta.persistence.*;
import java.time.LocalDateTime;
@Entity @Table(name="nala_uploads")
public class NalaUpload {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="upload_id") private String uploadId;
    @Column(name="user_id") private String userId;
    @Column(name="original_name") private String originalName;
    @Column(name="file_size") private Long fileSize;
    @Column(name="total_chunks") private Integer totalChunks;
    @Column(name="chunks_received") private Integer chunksReceived = 0;
    private String checksum;
    private String status;
    @Column(name="temp_path") private String tempPath;
    @Column(name="final_path") private String finalPath;
    @Column(name="result_msg") private String resultMsg;
    @Column(name="created_at") private LocalDateTime createdAt;
    @Column(name="updated_at") private LocalDateTime updatedAt;
    @Column(name="completed_at") private LocalDateTime completedAt;
    public Long getId(){return id;} public void setId(Long v){id=v;}
    public String getUploadId(){return uploadId;} public void setUploadId(String v){uploadId=v;}
    public String getUserId(){return userId;} public void setUserId(String v){userId=v;}
    public String getOriginalName(){return originalName;} public void setOriginalName(String v){originalName=v;}
    public Long getFileSize(){return fileSize;} public void setFileSize(Long v){fileSize=v;}
    public Integer getTotalChunks(){return totalChunks;} public void setTotalChunks(Integer v){totalChunks=v;}
    public Integer getChunksReceived(){return chunksReceived;} public void setChunksReceived(Integer v){chunksReceived=v;}
    public String getChecksum(){return checksum;} public void setChecksum(String v){checksum=v;}
    public String getStatus(){return status;} public void setStatus(String v){status=v;}
    public String getTempPath(){return tempPath;} public void setTempPath(String v){tempPath=v;}
    public String getFinalPath(){return finalPath;} public void setFinalPath(String v){finalPath=v;}
    public String getResultMsg(){return resultMsg;} public void setResultMsg(String v){resultMsg=v;}
    public LocalDateTime getCreatedAt(){return createdAt;} public void setCreatedAt(LocalDateTime v){createdAt=v;}
    public LocalDateTime getUpdatedAt(){return updatedAt;} public void setUpdatedAt(LocalDateTime v){updatedAt=v;}
    public LocalDateTime getCompletedAt(){return completedAt;} public void setCompletedAt(LocalDateTime v){completedAt=v;}
}
