package org.spigotmc.fernflower;

import org.eclipse.jdt.core.formatter.CodeFormatter;
import org.eclipse.jdt.core.formatter.DefaultCodeFormatterConstants;
import org.eclipse.jdt.internal.core.util.SimpleDocument;
import org.eclipse.jdt.internal.formatter.DefaultCodeFormatter;
import org.eclipse.jdt.internal.formatter.DefaultCodeFormatterOptions;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.text.edits.TextEdit;

public class EclipseFormatter {

  private static final CodeFormatter formatter;

  static {
    DefaultCodeFormatterOptions options = new DefaultCodeFormatterOptions(null);
    options.setJavaConventionsSettings();

    options.tab_char = DefaultCodeFormatterOptions.SPACE;
    options.page_width = Integer.MAX_VALUE;

    options.indent_switchstatements_compare_to_switch = true;

    options.insert_new_line_after_label = true;
    options.keep_method_body_on_one_line = DefaultCodeFormatterConstants.ONE_LINE_IF_EMPTY;
    options.keep_type_declaration_on_one_line = DefaultCodeFormatterConstants.ONE_LINE_IF_EMPTY;

    options.insert_space_after_opening_brace_in_array_initializer = false;
    options.insert_space_before_opening_brace_in_array_initializer = false;
    options.insert_space_before_closing_brace_in_array_initializer = false;
    // options.blank_lines_before_first_class_body_declaration = 1; // Needed later

    formatter = new DefaultCodeFormatter(options);
  }

  public static String format(String contents) throws BadLocationException {
    TextEdit formatted = formatter.format(CodeFormatter.K_COMPILATION_UNIT, contents, 0, contents.length(), 0, "\n");

    IDocument doc = new SimpleDocument(contents);
    formatted.apply(doc);

    return doc.get();
  }
}
