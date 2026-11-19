package com.wengu.source.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;

/** 源码导入的一个文件，对应 source_file 表（#90，决策 050）。导入后不再修改，是学习时用的快照。 */
public class SourceFile {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long sourceId;
    /** 相对项目文件夹的路径，分隔符统一为 / */
    private String path;
    /** 文件内容，换行统一为 \n */
    private String content;
    /** content 的字符数 */
    private Integer chars;
    /** 导入时的顺序，从 1 开始 */
    private Integer fileOrder;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getSourceId() {
        return sourceId;
    }

    public void setSourceId(Long sourceId) {
        this.sourceId = sourceId;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Integer getChars() {
        return chars;
    }

    public void setChars(Integer chars) {
        this.chars = chars;
    }

    public Integer getFileOrder() {
        return fileOrder;
    }

    public void setFileOrder(Integer fileOrder) {
        this.fileOrder = fileOrder;
    }
}
