package com.heima.model.article.pojos;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.io.Serializable;
import java.util.Date;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApArticleEmbedding implements Serializable {

    private Long id;
    private Long articleId;
    private double[] embedding;
    private Date createdTime;
}