package com.zhuri.coding.content.service.coding;

import com.zhuri.coding.model.common.dtos.ResponseResult;
import org.springframework.web.multipart.MultipartFile;

/**
 * 简历解析（Coding 延展第三层 · Stage A 补充）
 *
 * <p>只做"文件 → 文本"的转换，<b>不落库、不缓存</b>：解析结果直接回给前端，由前端回填到
 * 可编辑输入框，最后由用户随开面请求提交。这样服务端不留存简历这类敏感个人信息。</p>
 */
public interface CodingResumeService {

    /**
     * 解析简历文件为纯文本（PDF / DOCX / DOC / TXT / MD）
     *
     * @param file 上传的简历文件
     * @return {@code ResponseResult<CodingResumeParseVO>}；格式不支持/超限/无有效文字时返回业务错误
     */
    ResponseResult parse(MultipartFile file);
}
