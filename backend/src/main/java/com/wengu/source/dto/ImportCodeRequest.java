package com.wengu.source.dto;

import java.util.List;

/**
 * 导入源码的请求（#90，决策 050）：前端在浏览器里读出勾选文件的文字，一起发过来。
 *
 * @param name  材料名称，默认是项目文件夹名
 * @param files 勾选的文件，按文件树中的顺序
 */
public record ImportCodeRequest(String name, List<CodeFile> files) {

    /**
     * @param path    相对项目文件夹的路径
     * @param content 文件内容
     */
    public record CodeFile(String path, String content) {
    }
}
