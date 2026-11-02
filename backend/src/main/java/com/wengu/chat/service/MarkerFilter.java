package com.wengu.chat.service;

import com.wengu.chat.entity.Marker;

import java.util.EnumSet;
import java.util.Set;

/**
 * 从模型的流式输出中去掉标记（【完整作答】【学习结束】），并记下出现过哪些（决策 025）。
 * <p>
 * 一个标记可能被拆在两段里（如“【学习”和“结束】”），所以每段结尾处可能是标记开头的那几个字先压住，
 * 等下一段来了再判断；不是标记就原样放出。纯计算，一次回复用一个实例，不能多线程共用。
 */
final class MarkerFilter {

    private final StringBuilder pending = new StringBuilder();
    private final StringBuilder emitted = new StringBuilder();
    private final Set<Marker> found = EnumSet.noneOf(Marker.class);

    /** 收到一段输出，返回现在可以推给前端的文字（可能为空串）。 */
    String push(String chunk) {
        pending.append(chunk);
        for (Marker marker : Marker.values()) {
            int at;
            while ((at = pending.indexOf(marker.text())) >= 0) {
                pending.delete(at, at + marker.text().length());
                found.add(marker);
            }
        }
        int hold = partialMarkerLength(pending);
        String out = pending.substring(0, pending.length() - hold);
        pending.delete(0, pending.length() - hold);
        emitted.append(out);
        return out;
    }

    /** 输出结束：压住的字不是标记，原样放出。 */
    String finish() {
        String out = pending.toString();
        pending.setLength(0);
        emitted.append(out);
        return out;
    }

    /** 存库的内容：去掉标记后的全文，标记所在行留下的空行一并去掉。 */
    String content() {
        return emitted.toString().strip();
    }

    /** 这次回复的标记：学习结束优先于完整作答；都没有时为 null。 */
    Marker marker() {
        if (found.contains(Marker.END)) {
            return Marker.END;
        }
        return found.contains(Marker.RESTATE) ? Marker.RESTATE : null;
    }

    /** text 的结尾最长有几个字可能是某个标记的开头（不含完整的标记）。 */
    private static int partialMarkerLength(CharSequence text) {
        int longest = 0;
        for (Marker marker : Marker.values()) {
            String m = marker.text();
            for (int len = Math.min(m.length() - 1, text.length()); len > longest; len--) {
                if (text.subSequence(text.length() - len, text.length()).toString().equals(m.substring(0, len))) {
                    longest = len;
                    break;
                }
            }
        }
        return longest;
    }
}
