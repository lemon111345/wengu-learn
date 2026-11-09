package com.wengu.source.dto;

/**
 * 截图识别的进度和结果（决策 044）。
 *
 * @param status  RUNNING 识别中，DONE 完成，FAILED 失败
 * @param text    识别出的文字，只有 DONE 时有
 * @param message 给用户看的失败原因，只有 FAILED 时有
 */
public record ImageRecognitionView(Status status, String text, String message) {

    public enum Status {
        RUNNING, DONE, FAILED
    }

    public static ImageRecognitionView running() {
        return new ImageRecognitionView(Status.RUNNING, null, null);
    }

    public static ImageRecognitionView done(String text) {
        return new ImageRecognitionView(Status.DONE, text, null);
    }

    public static ImageRecognitionView failed(String message) {
        return new ImageRecognitionView(Status.FAILED, null, message);
    }
}
