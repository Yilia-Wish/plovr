/*
 * Copyright 2010 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.template.soy.jssrc.internal;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.CaseFormat;
import com.google.common.base.Charsets;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.io.Files;

import com.google.template.soy.shared.restricted.EscapingConventions;
import com.google.template.soy.shared.restricted.TagWhitelist;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;

import org.apache.tools.ant.Task;


/**
 * Generates JavaScript code relied upon by soyutils.js and soyutils_use_goog.js.
 *
 * <p>
 * This is an ant task and can be invoked as:
 * <xmp>
 *   <taskdef name="gen.escape.directives"
 *    classname="com.google.template.soy.jssrc.internal.GenerateSoyUtilsEscapingDirectiveCode">
 *     <classpath>
 *       <!-- classpath to Soy classes and dependencies -->
 *     </classpath>
 *   </taskdef>
 *   <gen.escape.directives>
 *     <input path="one or more JS files that use the generated helpers"/>
 *     <output path="the output JS file"/>
 *     <jsdefined pattern="goog.*"/>  <!-- enables closure alternatives -->
 *   </gen.escape.directives>
 * </xmp>
 *
 * <p>
 * In the above, the first {@code <taskdef>} is an Ant builtin which links the element named
 * {@code <gen.escape.directives>} to this class.
 * <p>
 * That element contains zero or more {@code <input>}s which are JavaScript source files that may
 * use the helper functions generated by this task.
 * <p>
 * There must be exactly one {@code <output>} element which specifies where the output should be
 * written.  That output contains the input sources and the generated helper functions.
 * <p>
 * There may be zero or more {@code <jsdefined>} elements which specify which functions should be
 * available in the context in which {@code <output>} is run.
 *
 * @author Mike Samuel
 */
@ParametersAreNonnullByDefault
public final class GenerateSoyUtilsEscapingDirectiveCode extends Task {


  // Ant looks for a mutator like createFoo methods when it hits a <foo>.
  // Below we define adders for these inner classes.
  // See http://ant.apache.org/manual/develop.html for more details.


  /**
   * A file reference like {@code <input path="foo.txt"/>}.
   * This is the basis for the {@code <input/>} and {@code <output/>} child elements of the Ant
   * task.
   */
  public static final class FileRef {
    /** True iff the file must exist before we can execute the task. */
    private final boolean isInput;
    /** The file that is to be read from or written to. */
    private File file;

    public FileRef(boolean isInput) {
      this.isInput = isInput;
    }

    /**
     * Invoked reflectively by Ant when it sees a {@code path="..."} attribute.
     */
    public void setPath(String path) throws IOException {
      file = new File(path);
      if (isInput) {
        if (!file.isFile() || !file.canRead()) {
          throw new IOException("Missing input file " + path);
        }
      } else if (file.isDirectory() || !file.getParentFile().isDirectory()) {
        throw new IOException("Cannot write output file " + path);
      }
    }
  }


  /**
   * A wrapper around a JavaScript function name predicate like {@code <jsdefined name="goog.*"/>}.
   */
  public static final class FunctionNamePredicate {
    /** A regular expression derived from a glob specified in the Ant build file. */
    private Pattern namePattern;

    /**
     * Called reflectively by Ant with the value of the {@code pattern="<glob>"} attribute of the
     * {@code <jsdefined>} element.
     */
    public void setPattern(String s) {
      // \Q starts a RegExp literal block, and \E ends one.
      String regex = "\\Q" + s.replace("*", "\\E\\w+\\Q") + "\\E";
      // E.g. "foo.*.bar" -> "\Qfoo.\E\w+\Q.bar\E"
      // which will match anything starting with the literal "foo.", then some identifier chars,
      // then ending with the literal ".bar".
      namePattern = Pattern.compile(regex);
    }
  }

  /** JavaScript source files that use the generated helper functions. */
  private List<FileRef> inputs = Lists.newArrayList();

  /** A file which receives the JavaScript source from the inputs and the generated helpers. */
  private FileRef output;

  /**
   * Matches functions available in the environment in which output will be run including things
   * like {@link EscapingConventions.CrossLanguageStringXform#getJsFunctionNames}.
   */
  private Predicate<String> availableJavaScript = new Predicate<String>() {
    public boolean apply(String javaScriptFunctionName) {
      return javaScriptFunctionName.indexOf('.') < 0;  // Only match builtins.
    }
  };


  /**
   * A matcher for functions available in the environment in which output will be run including
   * things like {@link EscapingConventions.CrossLanguageStringXform#getJsFunctionNames}.
   */
  public Predicate<String> getAvailableJavaScript() {
    return availableJavaScript;
  }


  /**
   * Called reflectively when Ant sees {@code <input>} to specify a file that uses the generated
   * helper functions.
   */
  public FileRef createInput() {
    FileRef ref = new FileRef(true);
    inputs.add(ref);
    return ref;
  }

  /**
   * Called reflectively when Ant sees {@code <output>} to specify the file that should receive
   * the output.
   */
  public FileRef createOutput() {
    if (output != null) {
      throw new IllegalStateException("Too many <output>s");
    }
    output = new FileRef(false);
    return output;
  }

  /**
   * Called reflectively when Ant sees {@code <jsdefined>}.
   */
  public void addConfiguredJsdefined(FunctionNamePredicate p) {
    final Pattern namePattern = p.namePattern;
    if (namePattern == null) {
      throw new IllegalStateException("Please specify a pattern attribute for <jsdefined>");
    }
    availableJavaScript = Predicates.or(availableJavaScript, new Predicate<String>() {
      public boolean apply(String javaScriptFunctionName) {
        return namePattern.matcher(javaScriptFunctionName).matches();
      }
    });
  }


  /**
   * Called to actually build the output by Ant.
   */
  @Override
  public void execute() {
    super.execute();
    if (output == null) {
      System.err.println(
          "Please add an <output> for the <" + getTaskName() + "> at " + this.getLocation());
      return;
    }

    // Gather output in a buffer rather than generating a bad file with a valid timestamp.
    StringBuilder sb = new StringBuilder();

    // Output the source files that use the helper functions first, so we get the appropriate file
    // overviews and copyright headers.
    for (FileRef input : inputs) {
      try {
        boolean inGeneratedCode = false;
        for (String line : Files.readLines(input.file, Charsets.UTF_8)) {
          // Skip code between generated code markers so that this transformation is idempotent.
          // We can run an old output through this class, and get the latest version out.
          if (inGeneratedCode) {
            if (GENERATED_CODE_END_MARKER.equals(line.trim())) {
              inGeneratedCode = false;
            }
          } else if (GENERATED_CODE_START_MARKER.equals(line.trim())) {
            inGeneratedCode = true;
          } else {
            sb.append(line).append('\n');
          }
        }
        sb.append('\n');
      } catch (IOException ex) {
        System.err.println("Failed to read " + input.file);
        ex.printStackTrace();
        return;
      }
    }

    // Generate helper functions for escape directives.
    generateJavaScript(availableJavaScript, sb);

    // Output a file now that we know generation hasn't failed.
    try {
      Writer out = new OutputStreamWriter(new FileOutputStream(output.file), Charsets.UTF_8);
      try {
        out.append(sb);
      } finally {
        out.close();
      }
    } catch (IOException ex) {
      // Make sure an abortive write does not leave a file w
      output.file.delete();
    }
  }


  /** A line that precedes the rest of the generated code. */
  static final String GENERATED_CODE_START_MARKER = "// START GENERATED CODE FOR ESCAPERS.";

  /** A line that follows the rest of the generated code. */
  static final String GENERATED_CODE_END_MARKER = "// END GENERATED CODE";


  /**
   * Appends JavaScript to the given buffer.
   *
   * <p>
   * The output JavaScript contains symbol definitions in the soy namespace.
   * <pre>
   *   soy.esc.$$ESCAPES_FOR_ESCAPE_HTML_  = { ... };  // Maps of characters to escaped versions
   *   soy.esc.$$MATCHER_FOR_ESCAPE_HTML_  = /.../g;  // A single character matching RegExp
   *   soy.esc.$$REPLACER_FOR_ESCAPE_HTML_ = function(ch) { ... };  // Usable with String.replace
   *   soy.esc.$$FILTER_FOR_ESCAPE_HTML_ = /.../g;  // Optional regular expression that vets values.
   *   // A function that uses the above definitions.
   *   soy.esc.$$escapeHtmlHelper = function(value) { return ...; };
   * </pre>
   * These definitions are all marked {@code @private} and have Closure compiler type
   * annotations.
   *
   * <p>There is not necessarily a one-to-one relationship between any of the symbols above
   * and escape directives except for the {@code soy.esc.$$escape...Helper} function.
   *
   * @param availableJavaScript Determines whether a qualified JavaScript identifier, like
   *     {@code goog.foo.Bar}, is available.
   * @param outputJs Receives output JavaScript.
   */
  @VisibleForTesting
  void generateJavaScript(Predicate<String> availableJavaScript, StringBuilder outputJs) {


    /**
     * The JS identifiers associated with the support for a particular escaping directive.
     */
    class DirectiveDigest {
      /** The name of the directive to output. */
      final String directiveName;

      /** Index into escapes of the object that maps characters to escaped text. */
      final int escapesVar;

      /** Index into matchers. */
      final int matcherVar;

      /** Index into filters. */
      final int filterVar;

      /** The prefix to use for non-ASCII characters not in the escape map. */
      final @Nullable String nonAsciiPrefix;

      /** Innocuous output for this context. */
      final String innocuousOutput;

      DirectiveDigest(String directiveName, int escapesVar, int matcherVar, int filterVar,
                      @Nullable String nonAsciiPrefix, String innocuousOutput) {
        this.directiveName = directiveName;
        this.escapesVar = escapesVar;
        this.matcherVar = matcherVar;
        this.filterVar = filterVar;
        this.nonAsciiPrefix = nonAsciiPrefix;
        this.innocuousOutput = innocuousOutput;
      }
    }


    outputJs.append('\n').append(GENERATED_CODE_START_MARKER).append('\n');


    // First we collect all the side tables.

    // Like { '\n': '\\n', ... } that map characters to escape.
    List<Map<Character, String>> escapeMaps = Lists.newArrayList();
    // Mangled directive names corresponding to escapeMaps used to generate soy.esc.$$..._ names.
    List<String> escapeMapNames = Lists.newArrayList();
    // Like /[\n\r'"]/g that match all the characters that need escaping.
    List<String> matchers = Lists.newArrayList();
    // Mangled directive names corresponding to matchers.
    List<String> matcherNames = Lists.newArrayList();
    // RegExps that vet input values.
    List<String> filters = Lists.newArrayList();
    // Mangled directive names corresponding to filters.
    List<String> filterNames = Lists.newArrayList();
    // Bundles of directiveNames and indices into escapeMaps, matchers, etc.
    List<DirectiveDigest> digests = Lists.newArrayList();

    escaperLoop:
    for (EscapingConventions.CrossLanguageStringXform escaper
             : EscapingConventions.getAllEscapers()) {
      // "|escapeHtml" -> "escapeHtml"
      String escapeDirectiveIdent = escaper.getDirectiveName().substring(1);
      // "escapeHtml" -> "ESCAPE_HTML"
      String escapeDirectiveUIdent = CaseFormat.LOWER_CAMEL.to(
          CaseFormat.UPPER_UNDERSCORE, escapeDirectiveIdent);

      // If there is an existing function, use it.
      for (String existingFunction : escaper.getJsFunctionNames()) {
        if (availableJavaScript.apply(existingFunction)) {
          // soy.esc.$$escapeFooHelper = bar;
          outputJs
              .append('\n')
              .append("/**\n")
              .append(" * @type {function (*) : string}\n")
              .append(" */\n")
              .append("soy.esc.$$").append(escapeDirectiveIdent).append("Helper = function(v) {\n")
              .append("  return ").append(existingFunction).append("(String(v));\n")
              .append("};\n");
          continue escaperLoop;
        }
      }

      // Else generate definitions for side tables.
      int escapesVar = -1;
      int matcherVar = -1;
      if (!escaper.getEscapes().isEmpty()) {
        Map<Character, String> escapeMap = Maps.newLinkedHashMap();
        StringBuilder matcherRegexBuf = new StringBuilder("/[");
        int lastCodeUnit = Integer.MIN_VALUE;
        int rangeStart = Integer.MIN_VALUE;
        for (EscapingConventions.Escape esc : escaper.getEscapes()) {
          char ch = esc.getPlainText();
          if (ch == lastCodeUnit) {
            throw new IllegalStateException(
                "Ambiguous escape " + esc.getEscaped() + " for " + escapeDirectiveIdent);
          }
          escapeMap.put(ch, esc.getEscaped());
          if (ch != lastCodeUnit + 1) {
            if (rangeStart != Integer.MIN_VALUE) {
              escapeRegexpRangeOnto((char) rangeStart, (char) lastCodeUnit, matcherRegexBuf);
            }
            rangeStart = ch;
          }
          lastCodeUnit = ch;
        }
        if (rangeStart < 0) {
          throw new IllegalStateException();
        }
        escapeRegexpRangeOnto((char) rangeStart, (char) lastCodeUnit, matcherRegexBuf);
        matcherRegexBuf.append("]/g");

        // See if we can reuse an existing map.
        int numEscapeMaps = escapeMaps.size();
        for (int i = 0; i < numEscapeMaps; ++i) {
          if (mapsHaveCompatibleOverlap(escapeMaps.get(i), escapeMap)) {
            escapesVar = i;
            break;
          }
        }
        if (escapesVar == -1) {
          escapesVar = numEscapeMaps;
          escapeMaps.add(escapeMap);
          escapeMapNames.add(escapeDirectiveUIdent);
        } else {
          escapeMaps.get(escapesVar).putAll(escapeMap);
          // ESCAPE_JS -> ESCAPE_JS_STRING__AND__ESCAPE_JS_REGEX
          escapeMapNames.set(
              escapesVar, escapeMapNames.get(escapesVar) + "__AND__" + escapeDirectiveUIdent);
        }

        String matcherRegex = matcherRegexBuf.toString();
        matcherVar = matchers.indexOf(matcherRegex);
        if (matcherVar < 0) {
          matcherVar = matchers.size();
          matchers.add(matcherRegex);
          matcherNames.add(escapeDirectiveUIdent);
        } else {
          matcherNames.set(
              matcherVar, matcherNames.get(matcherVar) + "__AND__" + escapeDirectiveUIdent);
        }
      }


      // Find a suitable filter or add one to filters.
      int filterVar = -1;
      Pattern filterPatternJava = escaper.getValueFilter();
      if (filterPatternJava != null) {
        // This is an approximate translation from Java patterns to JavaScript patterns.
        String filterPattern = javaRegexToJs(filterPatternJava);
        filterVar = filters.indexOf(filterPattern);
        if (filterVar == -1) {
          filterVar = filters.size();
          filters.add(filterPattern);
          filterNames.add(escapeDirectiveUIdent);
        } else {
          filterNames.set(
              filterVar, filterNames.get(filterVar) + "__AND__" + escapeDirectiveUIdent);
        }
      }

      digests.add(new DirectiveDigest(
          escapeDirectiveIdent, escapesVar, matcherVar, filterVar, escaper.getNonAsciiPrefix(),
          escaper.getInnocuousOutput()));
    }


    // TODO: Maybe use java Soy templates to generate the JS?

    // Output the tables.
    for (int i = 0; i < escapeMaps.size(); ++i) {
      Map<Character, String> escapeMap = escapeMaps.get(i);
      String escapeMapName = escapeMapNames.get(i);
      outputJs
          .append('\n')
          .append("/**\n")
          .append(" * Maps charcters to the escaped versions for the named escape directives.\n")
          .append(" * @type {Object.<string, string>}\n")
          .append(" * @private\n")
          .append(" */\n")
          .append("soy.esc.$$ESCAPE_MAP_FOR_").append(escapeMapName).append("_ = {");
      boolean needsComma = false;
      for (Map.Entry<Character, String> e : escapeMap.entrySet()) {
        if (needsComma) {
          outputJs.append(',');
        }
        outputJs.append("\n  ");
        writeJsChar(e.getKey(), outputJs);
        outputJs.append(": ");
        writeJsString(e.getValue(), outputJs);
        needsComma = true;
      }
      outputJs.append("\n};\n");

      outputJs
          .append('\n')
          .append("/**\n")
          .append(" * A function that can be used with String.replace..\n")
          .append(" * @param {string} ch A single character matched by a compatible matcher.\n")
          .append(" * @return {string} A token in the output language.\n")
          .append(" * @private\n")
          .append(" */\n")
          .append("soy.esc.$$REPLACER_FOR_")
          .append(escapeMapName)
          .append("_ = function(ch) {\n")
          .append("  return soy.esc.$$ESCAPE_MAP_FOR_").append(escapeMapName).append("_[ch];\n")
          .append("};\n");
    }

    for (int i = 0; i < matchers.size(); ++i) {
      String matcher = matchers.get(i);
      String matcherName = matcherNames.get(i);
      outputJs
          .append('\n')
          .append("/**\n")
          .append(" * Matches characters that need to be escaped for the named directives.\n")
          .append(" * @type RegExp\n")
          .append(" * @private\n")
          .append(" */\n")
          .append("soy.esc.$$MATCHER_FOR_").append(matcherName).append("_ = ").append(matcher)
          .append(";\n");
    }

    for (int i = 0; i < filters.size(); ++i) {
      String filter = filters.get(i);
      String filterName = filterNames.get(i);
      outputJs
          .append('\n')
          .append("/**\n")
          .append(" * A pattern that vets values produced by the named directives.\n")
          .append(" * @type RegExp\n")
          .append(" * @private\n")
          .append(" */\n")
          .append("soy.esc.$$FILTER_FOR_").append(filterName).append("_ = ").append(filter)
          .append(";\n");
    }

    // Finally, define the helper functions that use the escapes, filters, matchers, etc.
    for (DirectiveDigest digest : digests) {
      String name = digest.directiveName;
      outputJs
          .append('\n')
          .append("/**\n")
          .append(" * A helper for the Soy directive |").append(name).append('\n')
          .append(" * @param {*} value Can be of any type but will be coerced to a string.\n")
          .append(" * @return {string} The escaped text.\n")
          .append(" */\n")
          .append("soy.esc.$$").append(name).append("Helper = function(value) {\n")
          .append("  var str = String(value);\n");
      if (digest.filterVar != -1) {
        String filterName = filterNames.get(digest.filterVar);
        outputJs
            .append("  if (!soy.esc.$$FILTER_FOR_").append(filterName).append("_.test(str)) {\n");
        if (availableJavaScript.apply("goog.asserts.fail")) {
          outputJs
              .append("    goog.asserts.fail('Bad value `%s` for |").append(name)
              .append("', [str]);\n");
        }
        outputJs
            .append("    return '").append(digest.innocuousOutput).append("';\n")
            .append("  }\n");
      }

      if (digest.nonAsciiPrefix != null) {
        // TODO: We can add a second replace of all non-ascii codepoints below.
        throw new UnsupportedOperationException("Non ASCII prefix escapers not implemented yet.");
      }
      if (digest.escapesVar >= 0) {
        String escapeMapName = escapeMapNames.get(digest.escapesVar);
        String matcherName = matcherNames.get(digest.matcherVar);
        outputJs
            .append("  return str.replace(\n")
            .append("      soy.esc.$$MATCHER_FOR_").append(matcherName).append("_,\n")
            .append("      soy.esc.$$REPLACER_FOR_").append(escapeMapName).append("_);\n");
      } else {
        outputJs.append("  return str;\n");
      }
      outputJs.append("};\n");
    }

    // Emit patterns and constants needed by escaping functions that are not part of any one
    // escaping convention.
    outputJs.append('\n')
        .append("/**\n")
        .append(" * Matches all tags, HTML comments, and DOCTYPEs in tag soup HTML.\n")
        .append(" * By removing these, and replacing any '<' or '>' characters with\n")
        .append(" * entities we guarantee that the result can be embedded into a\n")
        .append(" * an attribute without introducing a tag boundary.\n")
        .append(" *\n")
        .append(" * @type {RegExp}\n")
        .append(" * @private\n")
        .append(" */\n")
        .append("soy.esc.$$HTML_TAG_REGEX_ = ")
        .append(javaRegexToJs(EscapingConventions.HTML_TAG_CONTENT))
        .append("g;\n")
        .append("\n")
        .append("/**\n")
        .append(" * Matches all occurrences of '<'.\n")
        .append(" *\n")
        .append(" * @type {RegExp}\n")
        .append(" * @private\n")
        .append(" */\n")
        .append("soy.esc.$$LT_REGEX_ = /</g;\n");

    outputJs.append('\n')
        .append("/**\n")
        .append(" * Maps lower-case names of innocuous tags to 1.\n")
        .append(" *\n")
        .append(" * @type {Object.<string,number>}\n")
        .append(" * @private\n")
        .append(" */\n")
        .append("soy.esc.$$SAFE_TAG_WHITELIST_ = ")
        .append(toJsStringSet(TagWhitelist.FORMATTING.asSet()))
        .append(";\n");

    outputJs.append('\n').append(GENERATED_CODE_END_MARKER).append('\n');
  }


  /**
   * True if the two maps have at least one (key, value) pair in common, and no pairs with the
   * same key but different values according to {@link Object#equals}.
   */
  private static <K, V> boolean mapsHaveCompatibleOverlap(Map<K, V> a, Map<K, V> b) {
    if (b.size() < a.size()) {
      Map<K, V> t = a;
      a = b;
      b = t;
    }
    boolean overlap = false;
    for (Map.Entry<K, V> e : a.entrySet()) {
      V value = b.get(e.getKey());
      if (value != null) {
        if (!value.equals(e.getValue())) {
          return false;
        }
        overlap = true;
      } else if (b.containsKey(e.getKey())) {
        if (e.getValue() != null) {
          return false;
        }
        overlap = true;
      }
    }
    return overlap;
  }

  /**
   * Appends a JavaScript string literal with the given value onto the given buffer.
   */
  private static void writeJsString(String value, StringBuilder out) {
    out.append('\'')
        .append(EscapingConventions.EscapeJsString.INSTANCE.escape(value))
        .append('\'');
  }

  /**
   * Appends a JavaScript string literal with the given value onto the given buffer.
   */
  private static void writeJsChar(char value, StringBuilder out) {
    if (!isPrintable(value)) {
      // Don't emit non-Latin characters or control characters since they don't roundtrip well.
      out.append(String.format(value >= 0x100 ? "'\\u%04x'" : "'\\x%02x'", (int) value));
    } else {
      out.append('\'')
          .append(EscapingConventions.EscapeJsString.INSTANCE.escape(String.valueOf(value)))
          .append('\'');
    }
  }

  /**
   * Appends a JavaScript RegExp character range set onto the given buffer.
   * E.g. given the letters 'a' and 'z' as start and end, appends {@code a-z}.
   * These are meant to be concatenated to create character sets like {@code /[a-zA-Z0-9]/}.
   * This method will omit unnecessary ends or range separators.
   */
  private static void escapeRegexpRangeOnto(char start, char end, StringBuilder out) {
    if (!isPrintable(start)) {
      out.append(String.format(start >= 0x100 ? "\\u%04x" : "\\x%02x", (int) start));
    } else {
      out.append(EscapingConventions.EscapeJsRegex.INSTANCE.escape(String.valueOf(start)));
    }
    if (start != end) {
      // If end - start is 1, then don't bother to put a dash.  [a-b] is the same as [ab].
      if (end - start > 1) {
        out.append('-');
      }
      if (!isPrintable(end)) {
        out.append(String.format(end >= 0x100 ? "\\u%04x" : "\\x%02x", (int) end));
      } else {
        out.append(EscapingConventions.EscapeJsRegex.INSTANCE.escape(String.valueOf(end)));
      }
    }
  }

  /**
   * True iff ch is not a control character or non-Latin character.
   */
  private static boolean isPrintable(char ch) {
    return 0x20 <= ch && ch <= 0x7e;
  }

  /**
   * Return a JavaScript regular expression literal equivalent to the given Java pattern.
   */
  private static String javaRegexToJs(Pattern p) {
    String body = p.pattern()
        .replace("\r", "\\r")
        .replace("\n", "\\n")
        .replace("\u2028", "\\u2028")
        .replace("\u2029", "\\u2029")
        .replace("\\A", "^")
        .replace("\\z", "$")
        .replaceAll("(?<!\\\\)(?:\\\\{2})*/", "\\\\/");
    // Some features supported by Java are not supported by JavaScript such as lookbehind,
    // DOTALL, and unicode character classes.
    if (body.contains("(?<")) {
      throw new IllegalArgumentException("Pattern " + p + " uses lookbehind.");
    } else if ((p.flags() & Pattern.DOTALL) != 0) {
      throw new IllegalArgumentException("Pattern " + p + " uses DOTALL.");
    } else if (NAMED_CLASS.matcher(body).find()) {
      throw new IllegalArgumentException("Pattern " + p + " uses named characer classes.");
    }

    StringBuilder buffer = new StringBuilder(body.length() + 4);
    buffer.append('/').append(body).append('/');
    if ((p.flags() & Pattern.CASE_INSENSITIVE) != 0) {
      buffer.append('i');
    }
    if ((p.flags() & Pattern.MULTILINE) != 0) {
      buffer.append('m');
    }
    return buffer.toString();
  }

  /** ["foo", "bar"] -> '{"foo": 1, "bar": 1}' */
  private static String toJsStringSet(Iterable<? extends String> strings) {
    StringBuilder sb = new StringBuilder();
    boolean isFirst = true;
    sb.append('{');
    for (String str : strings) {
      if (!isFirst) { sb.append(", "); }
      isFirst = false;
      writeJsString(str, sb);
      sb.append(": 1");
    }
    sb.append('}');
    return sb.toString();
  }

  /** Matches named character classes in Java regular expressions. */
  private static final Pattern NAMED_CLASS = Pattern.compile("(?<!\\\\)(\\\\{2})*\\\\p\\{");


  /**
   * A non Ant interface for this class.
   */
  public static void main(String[] args) throws IOException {
    GenerateSoyUtilsEscapingDirectiveCode generator = new GenerateSoyUtilsEscapingDirectiveCode();
    for (String arg : args) {
      if (arg.startsWith("--input=")) {
        FileRef ref = generator.createInput();
        ref.setPath(arg.substring(arg.indexOf('=') + 1));
      } else if (arg.startsWith("--output=")) {
        FileRef ref = generator.createOutput();
        ref.setPath(arg.substring(arg.indexOf('=') + 1));
      } else if (arg.startsWith("--jsdefined=")) {
        FunctionNamePredicate jsdefined = new FunctionNamePredicate();
        jsdefined.setPattern(arg.substring(arg.indexOf('=') + 1));
        generator.addConfiguredJsdefined(jsdefined);
      } else {
        throw new IllegalArgumentException(arg);
      }
    }
    generator.execute();
  }
}
