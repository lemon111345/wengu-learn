package com.wengu.source.service;

import com.wengu.common.exception.BusinessException;
import com.wengu.common.result.ErrorCode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFStyle;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 从上传的文件中提取文字（#26，决策 037）：PDF 用 PDFBox，DOCX 用 POI，Markdown 和 txt 直接读。
 * 提取出的文字和用户粘贴的原文一样处理。纯函数，不依赖 Spring；看不懂的文件报 BAD_REQUEST，提示用户怎么办。
 */
public final class DocumentTextExtractor {

    private static final Logger log = LoggerFactory.getLogger(DocumentTextExtractor.class);

    /** 上传文件的大小上限，和 spring.servlet.multipart.max-file-size、Nginx 的 client_max_body_size 一致 */
    public static final long MAX_FILE_SIZE = 10L * 1024 * 1024;

    /** Word 的标题样式名，如“heading 1”；中文版 Word 里样式 id 是数字，名称仍是英文 */
    private static final Pattern HEADING = Pattern.compile("heading\\s*(\\d)");

    private DocumentTextExtractor() {
    }

    /**
     * @param filename 原始文件名，按扩展名判断格式
     * @return 整理过的文字：统一换行、去掉行尾空白、连续空行合成一个
     * @throws BusinessException 格式不支持、文件损坏、有打开密码、没有文字（如扫描件）时为 BAD_REQUEST
     */
    public static String extract(String filename, byte[] bytes) {
        String extension = extension(filename);
        String text = switch (extension) {
            case "pdf" -> pdf(bytes);
            case "docx" -> docx(bytes);
            case "md", "markdown", "txt" -> plainText(bytes);
            case "doc" -> throw badRequest("暂不支持旧版 Word 文件（.doc），请在 Word 中另存为 .docx 再上传");
            default -> throw badRequest("只支持 PDF、Word（.docx）、Markdown 和 txt 文件");
        };
        String tidy = tidy(text);
        if (tidy.isEmpty()) {
            throw badRequest(extension.equals("pdf")
                    ? "这个 PDF 里没有可以提取的文字，可能是扫描件，暂不支持"
                    : "文件里没有文字");
        }
        return tidy;
    }

    static String extension(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** 按版面位置排序提取：双栏、文本框较多的 PDF，按文件内部顺序读会前后错乱 */
    private static String pdf(byte[] bytes) {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(document);
        } catch (InvalidPasswordException e) {
            throw badRequest("PDF 设置了打开密码，请去掉密码后再上传");
        } catch (IOException e) {
            log.info("PDF 无法读取: {}", e.toString());
            throw badRequest("文件无法读取，可能已经损坏，或者不是 PDF");
        }
    }

    /** 按文档顺序读段落和表格。标题样式的段落前面加 #，整理题目时模型能看出小节（出处要和它核对） */
    private static String docx(byte[] bytes) {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            StringBuilder text = new StringBuilder();
            for (IBodyElement element : document.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    int level = headingLevel(document, paragraph);
                    if (level > 0 && !paragraph.getText().isBlank()) {
                        text.append("#".repeat(level)).append(' ');
                    }
                    text.append(paragraph.getText()).append('\n');
                } else if (element instanceof XWPFTable table) {
                    for (XWPFTableRow row : table.getRows()) {
                        text.append(row.getTableCells().stream()
                                .map(XWPFTableCell::getText)
                                .map(String::strip)
                                .collect(Collectors.joining(" | "))).append('\n');
                    }
                }
                text.append('\n');
            }
            return text.toString();
        } catch (IOException | RuntimeException e) {
            // POI 遇到不是 docx 的文件会抛出各种运行时异常（NotOfficeXmlFileException、POIXMLException 等）
            log.info("DOCX 无法读取: {}", e.toString());
            throw badRequest("文件无法读取，可能已经损坏，或者不是 .docx 文件");
        }
    }

    /** 段落的标题级别：样式名为“heading N”时为 N，“title”为 1，其他为 0 */
    private static int headingLevel(XWPFDocument document, XWPFParagraph paragraph) {
        String styleId = paragraph.getStyleID();
        if (styleId == null || document.getStyles() == null) {
            return 0;
        }
        XWPFStyle style = document.getStyles().getStyle(styleId);
        String name = (style == null ? styleId : style.getName()).toLowerCase(Locale.ROOT);
        if (name.equals("title")) {
            return 1;
        }
        Matcher matcher = HEADING.matcher(name);
        return matcher.matches() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    /** 先按 UTF-8 严格解码；不是合法的 UTF-8 时按 GB18030（兼容 GBK）解码，国内 Windows 上保存的 txt 常是这种 */
    private static String plainText(byte[] bytes) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            return text.startsWith("﻿") ? text.substring(1) : text;
        } catch (CharacterCodingException e) {
            return new String(bytes, Charset.forName("GB18030"));
        }
    }

    /** 统一换行，去掉行尾空白，三个以上的连续换行合成两个 */
    static String tidy(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n')
                .replaceAll("[ \\t\\u00A0\\u3000]+\n", "\n")
                .replaceAll("\n{3,}", "\n\n")
                .strip();
    }

    private static BusinessException badRequest(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message);
    }
}
