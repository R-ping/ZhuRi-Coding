package com.heima.file.utils;

import cn.hutool.core.util.StrUtil;
import com.heima.file.config.MinIOConfig;
import com.heima.model.search.vos.SearchArticleVo;
import com.rabbitmq.client.Channel;
import freemarker.template.Configuration;
import freemarker.template.Template;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class MinioUtil {

    @Autowired
    private MinIOConfig prop;
    @Autowired
    private MinioClient minioClient;

    private final static String separator = "/";

    /**
     * @param dirPath 目录路径，默认“”
     * @param filename articleId
     */
    public String builderFilePath(String dirPath, String filename) {
        StringBuilder stringBuilder = new StringBuilder(50);
        if (!StrUtil.isEmpty(dirPath)) {
            stringBuilder.append(dirPath).append(separator);
        }
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy/MM/dd");
        String todayStr = sdf.format(new Date());
        stringBuilder.append(todayStr).append(separator);
        stringBuilder.append(filename);
        return stringBuilder.toString();
    }

    /**
     * 上传图片文件
     *
     * @param prefix 文件前缀
     * @param filename 文件名
     * @param inputStream 文件流
     * @return 文件全路径
     */
    public String uploadImgFile(String prefix, String filename, InputStream inputStream) {
        String filePath = builderFilePath(prefix, filename);
        try {
            PutObjectArgs putObjectArgs = PutObjectArgs.builder()
                .object(filePath)
                .contentType("image/jpg")
                .bucket(prop.getBucket()).stream(inputStream, inputStream.available(), -1)
                .build();
            minioClient.putObject(putObjectArgs);
            StringBuilder urlPath = new StringBuilder(prop.getReadPath());
            urlPath.append(separator + prop.getBucket());
            urlPath.append(separator);
            urlPath.append(filePath);
            return urlPath.toString();
        } catch (Exception ex) {
            log.error("minio put file error.", ex);
            throw new RuntimeException("上传文件失败");
        }
    }

    /**
     * 上传html文件
     *
     * @param prefix 文件前缀
     * @param filename 文件名
     * @param inputStream 文件流
     * @return 文件全路径
     */
    public String uploadHtmlFile(String prefix, String filename, InputStream inputStream) {
        String filePath = builderFilePath(prefix, filename);
        try {
            PutObjectArgs putObjectArgs = PutObjectArgs.builder()
                .object(filePath)
                .contentType("text/html")
                .bucket(prop.getBucket()).stream(inputStream, inputStream.available(), -1)
                .build();
            minioClient.putObject(putObjectArgs);
            StringBuilder urlPath = new StringBuilder(prop.getReadPath());
            urlPath.append(separator + prop.getBucket());
            urlPath.append(separator);
            urlPath.append(filePath);
            return urlPath.toString();
        } catch (Exception ex) {
            log.error("minio put file error.", ex);
            log.error("异常信息", ex);
            throw new RuntimeException("上传文件失败");
        }
    }

    /**
     * 上传字符串内容到 MinIO
     *
     * @param content 字符串内容
     * @param objectName 对象名称（完整路径）
     * @param contentType 内容类型，如 "text/html"
     */
    public void uploadString(String content, String objectName, String contentType) {
        try {
            ByteArrayInputStream inputStream = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
            PutObjectArgs putObjectArgs = PutObjectArgs.builder()
                .object(objectName)
                .contentType(contentType)
                .bucket(prop.getBucket())
                .stream(inputStream, inputStream.available(), -1)
                .build();
            minioClient.putObject(putObjectArgs);
        } catch (Exception ex) {
            log.error("minio upload string error, bucket={}, objectName={}", prop.getBucket()   , objectName, ex);
            throw new RuntimeException("上传文件失败");
        }
    }

    @Autowired
    private Configuration configuration;

    @Autowired
    private RabbitTemplate rabbitTemplate;
    @RabbitListener(bindings = @QueueBinding(
        value = @Queue(value = "minio.queue", durable = "true"),
        exchange = @Exchange(value = "article.exchange"),
        key = "article.minio.*"
    ))
    public void uploadHtmlFile(Message message, SearchArticleVo vo, Channel channel) {
        HashMap<String,String> resultMap=new HashMap<>();
        resultMap.put("articleId",vo.getId().toString());
        resultMap.put("type","minio");
        try {
            String fileName = vo.getFileName();// yyyy/mm/dd/articleId
            StringWriter out = new StringWriter();
            Template template = configuration.getTemplate("article.ftl");
            //数据模型
            Map<String, Object> contentDataModel = buildDataModel(vo);
            //合成
            template.process(contentDataModel, out);
            // 将HTML内容转换为输入流
            InputStream inputStream = new ByteArrayInputStream(out.toString().getBytes());
            PutObjectArgs putObjectArgs = PutObjectArgs.builder()
                .object(fileName)
                .contentType("text/html")
                .bucket(prop.getBucket()).stream(inputStream, inputStream.available(), -1)
                .build();
            minioClient.putObject(putObjectArgs);
            resultMap = new HashMap<>();
            resultMap.put("status", "success");
            rabbitTemplate.convertAndSend("process.exchange","process.result",resultMap);
            channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
        } catch (Exception ex) {
            log.error("minio put file error.", ex);
            resultMap.put("status", "fail");
            rabbitTemplate.convertAndSend("process.exchange","process.result",resultMap);
        }
    }

    /**
     * 构建 Freemarker 数据模型
     */
    private Map<String, Object> buildDataModel(SearchArticleVo vo) {
        Map<String, Object> contentDataModel = new HashMap<>();
        contentDataModel.put("title", vo.getTitle());
        contentDataModel.put("authorName", vo.getAuthorName());
        contentDataModel.put("publishTime", vo.getPublishTime());
        contentDataModel.put("articleId", vo.getId());
        contentDataModel.put("htmlContent", vo.getHtmlContent());
        List<?> tocList = vo.getTocList();
        contentDataModel.put("tocList", tocList != null ? tocList : new ArrayList<>());
        contentDataModel.put("relation", defaultRelation());
        // 作者作品：从 SearchArticleVo 中获取，如果为空则传空列表
        List<?> authorWorks = vo.getAuthorWorks();
        contentDataModel.put("authorWorks", authorWorks != null ? authorWorks : new ArrayList<>());
        return contentDataModel;
    }

    /**
     * 默认的点赞/收藏/关注状态，页面加载后可通过 JS 从接口刷新真实状态
     */
    private Map<String, Object> defaultRelation() {
        Map<String, Object> relation = new HashMap<>();
        relation.put("islike", false);
        relation.put("iscollection", false);
        relation.put("isfollow", false);
        relation.put("isunlike", false);
        return relation;
    }

    /**
     * 删除文件
     *
     * @param pathUrl 文件全路径
     */
    public void delete(String pathUrl) {
        String key = pathUrl.replace(prop.getEndPoint() + "/", "");
        int index = key.indexOf(separator);
        String bucket = key.substring(0, index);
        String filePath = key.substring(index + 1);
        // 删除Objects
        RemoveObjectArgs removeObjectArgs = RemoveObjectArgs.builder().bucket(bucket).object(filePath).build();
        try {
            minioClient.removeObject(removeObjectArgs);
        } catch (Exception e) {
            log.error("minio remove file error.  pathUrl:{}", pathUrl);
            log.error("异常信息", e);
        }
    }


    /**
     * 下载文件
     *
     * @param pathUrl 文件全路径
     * @return 文件流
     */
    public byte[] downLoadFile(String pathUrl) {
        String key = pathUrl.replace(prop.getEndPoint() + "/", "");
        int index = key.indexOf(separator);
        String bucket = key.substring(0, index);
        String filePath = key.substring(index + 1);
        InputStream inputStream = null;
        try {
            inputStream = minioClient.getObject(
                GetObjectArgs.builder().bucket(prop.getBucket()).object(filePath).build());
        } catch (Exception e) {
            log.error("minio down file error.  pathUrl:{}", pathUrl);
            log.error("异常信息", e);
        }

        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        byte[] buff = new byte[100];
        int rc = 0;
        while (true) {
            try {
                if (!((rc = inputStream.read(buff, 0, 100)) > 0)) {
                    break;
                }
            } catch (IOException e) {
                log.error("异常信息", e);
            }
            byteArrayOutputStream.write(buff, 0, rc);
        }
        return byteArrayOutputStream.toByteArray();
    }

}
