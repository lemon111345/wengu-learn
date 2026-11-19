package com.wengu.knowledge.dto;

import java.util.List;

/**
 * 源码知识点学习时额外的材料（#90，决策 048、050），来自 kp_code 和导入时的文件快照。
 *
 * @param details       细节，漏了不降档；没有时为 null
 * @param unverifiable  代码证明不了、只有学生能回答的问题
 * @param prerequisites 前置概念
 * @param files         代码依据涉及的文件，按依据中第一次出现的顺序，去重；快照找不到的文件不在其中
 */
public record CodeMaterial(String details, List<String> unverifiable, List<CodePrerequisite> prerequisites,
                           List<CodeFile> files) {

    public boolean hasDetails() {
        return details != null && !details.isBlank();
    }
}
