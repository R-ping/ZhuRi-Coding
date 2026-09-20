package com.zhuri.coding.content.schedule;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.DeleteObjectsRequest;
import com.aliyun.oss.model.DeleteObjectsResult;
import com.aliyun.oss.model.ListObjectsRequest;
import com.aliyun.oss.model.ObjectListing;
import com.aliyun.oss.model.OSSObjectSummary;
import com.zhuri.coding.content.config.OssConfig;
import com.zhuri.coding.content.mapper.OssImageCleanupMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
@Slf4j
public class OssImageCleanupTask {

    @Autowired
    private OSS ossClient;

    @Autowired
    private OssConfig ossConfig;

    @Autowired
    private OssImageCleanupMapper ossImageCleanupMapper;

    @Scheduled(cron = "0 0 3 */2 * ?")
    public void cleanupOrphanImages() {
        log.info("===== OSS 脏图片清理任务开始 =====");

        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            Calendar cal = Calendar.getInstance();
            String endTime = sdf.format(cal.getTime());
            cal.add(Calendar.DAY_OF_MONTH, -3);
            String startTime = sdf.format(cal.getTime());

            log.info("查询时间范围: {} 至 {}", startTime, endTime);

            String prefix = ossConfig.getDir();
            if (prefix == null) {
                prefix = "material/";
            }
            if (!prefix.endsWith("/")) {
                prefix = prefix + "/";
            }

            Set<String> ossObjectNames = listRecentOssObjects(prefix, startTime, endTime);
            log.info("OSS 中待检查文件数: {}", ossObjectNames.size());

            if (ossObjectNames.isEmpty()) {
                log.info("OSS 中无待清理文件，任务结束");
                return;
            }

            Set<String> dbImageUrls = collectDbImageUrls(startTime, endTime);
            log.info("数据库中引用的图片数: {}", dbImageUrls.size());

            Set<String> dirtyObjectNames = computeDirtyImages(ossObjectNames, dbImageUrls);
            log.info("识别出脏图片数: {}", dirtyObjectNames.size());

            if (dirtyObjectNames.isEmpty()) {
                log.info("无需清理的脏图片，任务结束");
                return;
            }

            int deletedCount = batchDeleteFromOss(dirtyObjectNames);
            log.info("成功删除脏图片数: {}", deletedCount);

        } catch (Exception e) {
            log.error("OSS 脏图片清理任务异常", e);
        }

        log.info("===== OSS 脏图片清理任务结束 =====");
    }

    private Set<String> listRecentOssObjects(String prefix, String startTime, String endTime) {
        Set<String> objectNames = new HashSet<>();
        try {
            ListObjectsRequest listRequest = new ListObjectsRequest();
            listRequest.setBucketName(ossConfig.getBucket());
            listRequest.setPrefix(prefix);
            listRequest.setMaxKeys(1000);

            Calendar startCal = Calendar.getInstance();
            startCal.setTime(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(startTime));

            ObjectListing objectListing;
            do {
                objectListing = ossClient.listObjects(listRequest);
                for (OSSObjectSummary summary : objectListing.getObjectSummaries()) {
                    Date lastModified = summary.getLastModified();
                    if (lastModified != null) {
                        Calendar fileCal = Calendar.getInstance();
                        fileCal.setTime(lastModified);
                        if (fileCal.after(startCal)) {
                            objectNames.add(summary.getKey());
                        }
                    }
                }
                listRequest.setMarker(objectListing.getNextMarker());
            } while (objectListing.isTruncated());
        } catch (Exception e) {
            log.error(" 列出 OSS 对象失败", e);
        }
        return objectNames;
    }

    private Set<String> collectDbImageUrls(String startTime, String endTime) {
        Set<String> imageUrls = new HashSet<>();

        try {
            List<String> covers = ossImageCleanupMapper.findArticleCoverImages(startTime, endTime);
            if (covers != null) {
                imageUrls.addAll(covers);
            }
        } catch (Exception e) {
            log.warn("查询文章封面图片失败: {}", e.getMessage());
        }

        try {
            List<String> contents = ossImageCleanupMapper.findArticleContentImages(startTime, endTime);
            if (contents != null) {
                for (String content : contents) {
                    if (content != null) {
                        extractUrlsFromString(content, imageUrls);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("查询文章内容图片失败: {}", e.getMessage());
        }

        try {
            List<String> pinsImages = ossImageCleanupMapper.findPinsImages(startTime, endTime);
            if (pinsImages != null) {
                for (String urls : pinsImages) {
                    if (urls != null && !urls.isEmpty()) {
                        String[] parts = urls.split(",");
                        for (String part : parts) {
                            String trimmed = part.trim();
                            if (!trimmed.isEmpty()) {
                                imageUrls.add(trimmed);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("查询沸点图片失败: {}", e.getMessage());
        }

        try {
            List<String> covers = ossImageCleanupMapper.findColumnCoverImages(startTime, endTime);
            if (covers != null) {
                imageUrls.addAll(covers);
            }
        } catch (Exception e) {
            log.warn("查询专栏封面图片失败: {}", e.getMessage());
        }

        try {
            List<String> covers = ossImageCleanupMapper.findCourseCoverImages(startTime, endTime);
            if (covers != null) {
                imageUrls.addAll(covers);
            }
        } catch (Exception e) {
            log.warn("查询课程封面图片失败: {}", e.getMessage());
        }

        return imageUrls;
    }

    private void extractUrlsFromString(String content, Set<String> urlSet) {
        if (content == null || content.isEmpty()) {
            return;
        }
        String[] parts = content.split("https?://");
        for (int i = 1; i < parts.length; i++) {
            String url = "https://" + parts[i].split("[\\s\\)\\]\\}\"]")[0];
            urlSet.add(url);
        }
    }

    private Set<String> computeDirtyImages(Set<String> ossObjects, Set<String> dbUrls) {
        Set<String> dirty = new HashSet<>();
        String host = ossConfig.getHost();
        if (host == null) {
            host = "";
        }

        for (String objectName : ossObjects) {
            String fullUrl = host + "/" + objectName;

            boolean referenced = false;
            for (String dbUrl : dbUrls) {
                if (dbUrl != null && !dbUrl.isEmpty()) {
                    if (dbUrl.contains(objectName) || fullUrl.contains(dbUrl) || dbUrl.equals(fullUrl)) {
                        referenced = true;
                        break;
                    }
                }
            }

            if (!referenced) {
                dirty.add(objectName);
            }
        }

        return dirty;
    }

    private int batchDeleteFromOss(Set<String> objectNames) {
        int deletedCount = 0;
        try {
            List<String> nameList = new ArrayList<>(objectNames);
            int batchSize = 1000;

            for (int i = 0; i < nameList.size(); i += batchSize) {
                int end = Math.min(i + batchSize, nameList.size());
                List<String> batch = nameList.subList(i, end);

                DeleteObjectsRequest deleteRequest = new DeleteObjectsRequest(ossConfig.getBucket());
                deleteRequest.setKeys(batch);
                DeleteObjectsResult deleteResult = ossClient.deleteObjects(deleteRequest);
                deletedCount += deleteResult.getDeletedObjects().size();
            }
        } catch (Exception e) {
            log.error("批量删除 OSS 对象失败", e);
        }
        return deletedCount;
    }
}