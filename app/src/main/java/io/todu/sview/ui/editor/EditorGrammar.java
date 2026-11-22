package io.todu.sview.ui.editor;

import io.noties.prism4j.Prism4j;
import io.noties.prism4j.annotations.PrismBundle;

/** 仅生成网页编辑需要的语法，避免把所有语言打入安装包。 */
@PrismBundle(include = {"javascript", "markup", "css", "json"}, grammarLocatorClassName = ".EditorGrammarLocator")
public final class EditorGrammar {
    private EditorGrammar() { }

    /** 语法实例由编辑器后台任务持有，不在应用启动时创建。 */
    public static Prism4j create() {
        return new Prism4j(new EditorGrammarLocator()) {
            /** 空 script/style 会递归分词空字符串；Prism4j 2.0 会对此误报内部错误。 */
            @Override
            public java.util.List<Node> tokenize(String text, Grammar grammar) {
                return text.isEmpty() ? java.util.Collections.emptyList() : super.tokenize(text, grammar);
            }
        };
    }
}
