package com.wengu.source.service;

import com.wengu.common.exception.BusinessException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFStyle;
import org.apache.poi.xwpf.usermodel.XWPFStyles;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTString;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTStyle;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STStyleType;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 上传文件的文字提取（#26）。测试用的 DOCX、PDF 由 POI、PDFBox 当场生成，仓库里不放二进制文件。 */
class DocumentTextExtractorTest {

    private static void assertBadRequest(String filename, byte[] bytes, String message) {
        assertThatThrownBy(() -> DocumentTextExtractor.extract(filename, bytes))
                .isInstanceOf(BusinessException.class)
                .hasMessage(message);
    }

    // ===== DOCX =====

    /** 加一个段落样式：id 和中文版 Word 一样是数字，名称是英文的“heading N” */
    private static void addHeadingStyle(XWPFStyles styles, String id, String name) {
        CTStyle ct = CTStyle.Factory.newInstance();
        ct.setStyleId(id);
        ct.setType(STStyleType.PARAGRAPH);
        CTString ctName = CTString.Factory.newInstance();
        ctName.setVal(name);
        ct.setName(ctName);
        styles.addStyle(new XWPFStyle(ct));
    }

    private static byte[] docx() throws IOException {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XWPFStyles styles = doc.createStyles();
            addHeadingStyle(styles, "1", "heading 1");
            addHeadingStyle(styles, "2", "heading 2");
            doc.createParagraph().createRun().setText("线程池笔记");
            doc.getParagraphs().getFirst().setStyle("1");
            var h2 = doc.createParagraph();
            h2.setStyle("2");
            h2.createRun().setText("核心参数");
            doc.createParagraph().createRun().setText("corePoolSize 是核心线程数。");
            XWPFTable table = doc.createTable(2, 2);
            table.getRow(0).getCell(0).setText("策略");
            table.getRow(0).getCell(1).setText("行为");
            table.getRow(1).getCell(0).setText("AbortPolicy");
            table.getRow(1).getCell(1).setText("抛异常");
            doc.write(out);
            return out.toByteArray();
        }
    }

    @Test
    void DOCX_按顺序读段落和表格_标题样式前加井号() throws IOException {
        assertThat(DocumentTextExtractor.extract("笔记.docx", docx())).isEqualTo("""
                # 线程池笔记

                ## 核心参数

                corePoolSize 是核心线程数。

                策略 | 行为
                AbortPolicy | 抛异常""");
    }

    @Test
    void 扩展名不区分大小写() throws IOException {
        assertThat(DocumentTextExtractor.extract("笔记.DOCX", docx())).startsWith("# 线程池笔记");
    }

    // ===== PDF =====

    private static byte[] pdf(String text, StandardProtectionPolicy protection) throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            if (text != null) {
                try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
                    stream.beginText();
                    stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    stream.newLineAtOffset(72, 700);
                    stream.showText(text);
                    stream.endText();
                }
            }
            if (protection != null) {
                doc.protect(protection);
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void PDF_提取文字() throws IOException {
        assertThat(DocumentTextExtractor.extract("notes.pdf", pdf("Thread pool core parameters", null)))
                .isEqualTo("Thread pool core parameters");
    }

    @Test
    void PDF_没有文字时提示可能是扫描件() throws IOException {
        assertBadRequest("scan.pdf", pdf(null, null), "这个 PDF 里没有可以提取的文字，可能是扫描件，暂不支持");
    }

    @Test
    void PDF_有打开密码时提示去掉密码() throws IOException {
        byte[] locked = pdf("secret", new StandardProtectionPolicy("owner", "user", new AccessPermission()));
        assertBadRequest("locked.pdf", locked, "PDF 设置了打开密码，请去掉密码后再上传");
    }

    @Test
    void 文件损坏时提示无法读取() {
        byte[] garbage = "这不是一个正常的文件".getBytes(StandardCharsets.UTF_8);
        assertBadRequest("broken.pdf", garbage, "文件无法读取，可能已经损坏，或者不是 PDF");
        assertBadRequest("broken.docx", garbage, "文件无法读取，可能已经损坏，或者不是 .docx 文件");
    }

    // ===== Markdown、txt =====

    @Test
    void Markdown_按UTF8读_去掉BOM并整理空白() {
        byte[] bytes = "﻿# 标题\r\n\r\n\r\n\r\n正文  \r\n第二行　\n".getBytes(StandardCharsets.UTF_8);
        assertThat(DocumentTextExtractor.extract("note.md", bytes)).isEqualTo("# 标题\n\n正文\n第二行");
    }

    @Test
    void txt_不是UTF8时按GBK读() {
        byte[] gbk = "线程池的核心参数".getBytes(Charset.forName("GBK"));
        assertThat(DocumentTextExtractor.extract("note.txt", gbk)).isEqualTo("线程池的核心参数");
    }

    @Test
    void 空文件提示没有文字() {
        assertBadRequest("empty.md", " \n ".getBytes(StandardCharsets.UTF_8), "文件里没有文字");
    }

    // ===== 不支持的格式 =====

    @Test
    void 旧版Word和其他格式不支持() {
        byte[] bytes = {1, 2, 3};
        assertBadRequest("old.doc", bytes, "暂不支持旧版 Word 文件（.doc），请在 Word 中另存为 .docx 再上传");
        assertBadRequest("slides.pptx", bytes, "只支持 PDF、Word（.docx）、Markdown 和 txt 文件");
        assertBadRequest("no-extension", bytes, "只支持 PDF、Word（.docx）、Markdown 和 txt 文件");
    }
}
